/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import app.lawnchair.ioschair.IosPrefs
import java.lang.ref.WeakReference
import kotlin.math.min

/**
 * Renders a Liquid Glass surface (iOS 26 style) onto a canvas.
 *
 * The look is built from four layers, mirroring Apple's description of the material:
 *  1. **Backdrop** – the blurred wallpaper from [GlassBackdrop], sampled at the surface's screen
 *     position, with a vibrancy (saturation) boost.
 *  2. **Lensing** – near the edges the backdrop is pulled inwards following the surface normal,
 *     which reads as light bending through a thick piece of glass. Slight chromatic dispersion
 *     separates the RGB channels at the rim.
 *  3. **Tint** – an adaptive light / dark film so content on top stays legible.
 *  4. **Specular rim** – a thin highlight whose intensity depends on the angle between the edge
 *     normal and the light direction ([GlassMotion] tilts it with the device).
 *
 * Backdrop sources, in "auto" order:
 *  - [SystemBlur]: real-time SurfaceFlinger blur of what is behind the window (works with live
 *    wallpapers, follows animations), with the glass film drawn on top;
 *  - the wallpaper bitmap through the AGSL [RuntimeShader] (adds true lensing / dispersion);
 *  - a denser frosted tint when no blur is possible at all.
 *
 * One painter should be owned by each glass host (it keeps per-host shader instances).
 */
class GlassPainter(context: Context) {

    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmpRect = RectF()
    private val clipBounds = RectF()
    private val blurBounds = Rect()
    private val loc = IntArray(2)
    private val matrix = Matrix()

    private var bitmapShader: BitmapShader? = null
    private var shaderVersion = -1
    private var runtimeShader: Any? = null

    // System (SurfaceFlinger) blur, bound to the host's window.
    private var blurDrawable: Drawable? = null
    private var blurRoot: WeakReference<Any>? = null

    /** Additional per-surface multiplier for the tint (e.g. folders are a bit denser). */
    var tintBoost = 0f

    /** Overrides for very small surfaces (folder icons) where lensing must be gentler. */
    var refractionScale = 1f

    /** Draw the soft ReGlass drop shadow around the surface (off for icons). */
    var shadow = true

    /**
     * Multiply the system-blur opacity with the host's ancestors' alpha. Only safe for hosts that
     * redraw when their alpha changes (dock, Search pill); popups fade in via a parent alpha
     * animation that does not redraw children, so they must not track it.
     */
    var trackHostAlpha = false

    private fun ensureBitmapShader(): BitmapShader? {
        val bmp = GlassBackdrop.bitmap ?: return null
        if (shaderVersion != GlassBackdrop.version || bitmapShader == null) {
            bitmapShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderVersion = GlassBackdrop.version
        }
        return bitmapShader
    }

    private fun isNight(): Boolean =
        (appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Resolves the tint colour (rgb) + amount for the current mode. */
    private fun resolveTint(): FloatArray {
        val prefs = IosPrefs.get(appContext)
        val base = prefs.iosGlassTintOpacity.get().coerceIn(0f, 1f) + tintBoost
        val mode = prefs.iosGlassTintMode.get()
        val dark = when (mode) {
            "light" -> false
            "dark" -> true
            else -> isNight()
        }
        val amount = when (mode) {
            "clear" -> base * 0.35f
            else -> if (dark) base + 0.08f else base + 0.04f
        }.coerceIn(0f, 0.95f)
        return if (dark) {
            // iOS dark glass is a smoky graphite, not black.
            floatArrayOf(0.16f, 0.16f, 0.18f, amount)
        } else {
            floatArrayOf(1f, 1f, 1f, amount)
        }
    }

    /** Effective opacity of [view] including all of its ancestors. */
    private fun effectiveAlpha(view: View): Float {
        var a = 1f
        var v: View? = view
        while (v != null) {
            if (v.visibility != View.VISIBLE) return 0f
            a *= v.alpha
            v = v.parent as? View
        }
        return a
    }

    private fun blurRadiusPx(): Int {
        val strength = IosPrefs.get(appContext).iosGlassBlur.get().coerceIn(0, 100) / 100f
        return ((8f + strength * 52f) * density).toInt()
    }

    private fun ensureBlurDrawable(host: View): Drawable? {
        val root = SystemBlur.rootOf(host) ?: return null
        if (blurDrawable == null || blurRoot?.get() !== root) {
            blurDrawable = SystemBlur.create(host)
            blurRoot = WeakReference(root)
        }
        return blurDrawable
    }

    /** Which pipeline to use for this frame. */
    private enum class Mode { SYSTEM_BLUR, SHADER, BITMAP, TINT_ONLY }

    private fun pickMode(canvas: Canvas, hasBitmap: Boolean): Mode {
        val prefs = IosPrefs.get(appContext)
        val source = prefs.iosGlassSource.get()
        // "Liquid Glass" = refraction shader; when off, surfaces are frosted (matte) glass.
        val liquid = prefs.iosGlassEnabled.get()
        val systemOk = canvas.isHardwareAccelerated && SystemBlur.isAvailable(appContext)
        val realBitmap = hasBitmap && GlassBackdrop.isRealWallpaper
        val shaderOk = liquid && realBitmap && !shaderBroken &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val wallpaperMode = if (shaderOk) Mode.SHADER else Mode.BITMAP
        return when (source) {
            "system" -> when {
                systemOk -> Mode.SYSTEM_BLUR
                realBitmap -> wallpaperMode
                else -> Mode.TINT_ONLY
            }
            "wallpaper" -> when {
                realBitmap -> wallpaperMode
                systemOk -> Mode.SYSTEM_BLUR
                else -> Mode.TINT_ONLY
            }
            else -> when {
                shaderOk -> Mode.SHADER
                systemOk -> Mode.SYSTEM_BLUR
                realBitmap -> Mode.BITMAP
                else -> Mode.TINT_ONLY
            }
        }
    }

    /**
     * Draws a glass rounded rectangle.
     *
     * @param host view whose canvas is being drawn; used to locate the surface on screen.
     * @param left/top/right/bottom rectangle in the host's canvas coordinates.
     * @param radius corner radius in px.
     * @param alpha overall opacity (0..1).
     * @param clip optional path (host canvas coordinates) for non-rectangular shapes.
     */
    @JvmOverloads
    fun draw(
        canvas: Canvas,
        host: View,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        alpha: Float = 1f,
        clip: Path? = null,
    ) {
        if (right <= left || bottom <= top || alpha <= 0f) return
        GlassBackdrop.track(host)
        val w = right - left
        val h = bottom - top
        val r = min(radius, min(w, h) / 2f)

        val backdrop = ensureBitmapShader()
        val mode = pickMode(canvas, backdrop != null)

        if (mode == Mode.SYSTEM_BLUR) {
            drawSystemBlur(canvas, host, left, top, right, bottom, r, alpha, clip)
        } else {
            blurDrawable?.alpha = 0
        }

        val save = canvas.save()
        if (clip != null) canvas.clipPath(clip)
        canvas.translate(left, top)

        // Shader draws a larger area so the drop shadow fits around the surface.
        val pad = if (mode == Mode.SHADER && shadow && clip == null) 22f * density else 0f
        if (backdrop != null && (mode == Mode.SHADER || mode == Mode.BITMAP)) {
            host.getLocationOnScreen(loc)
            matrix.reset()
            matrix.setScale(GlassBackdrop.scale, GlassBackdrop.scale)
            val ox = if (mode == Mode.SHADER) pad else 0f
            matrix.postTranslate(-(loc[0] + left - ox), -(loc[1] + top - ox))
            backdrop.setLocalMatrix(matrix)
        }

        when (mode) {
            Mode.SHADER -> try {
                drawShader(canvas, backdrop!!, w, h, r, alpha, pad)
            } catch (t: Throwable) {
                // Never take the launcher down because of a GPU / shader issue.
                android.util.Log.w("GlassPainter", "Liquid Glass shader failed, using fallback", t)
                shaderBroken = true
                drawOverlay(canvas, backdrop, w, h, r, alpha, blurred = true)
            }
            Mode.BITMAP -> drawOverlay(canvas, backdrop, w, h, r, alpha, blurred = true)
            Mode.SYSTEM_BLUR -> drawOverlay(canvas, null, w, h, r, alpha, blurred = true)
            Mode.TINT_ONLY -> drawOverlay(canvas, null, w, h, r, alpha, blurred = false)
        }
        canvas.restoreToCount(save)
    }

    private fun drawSystemBlur(
        canvas: Canvas,
        host: View,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        r: Float,
        alpha: Float,
        clip: Path?,
    ) {
        val d = ensureBlurDrawable(host) ?: return
        var l = left
        var t = top
        var rr = right
        var b = bottom
        var radius = r
        if (clip != null) {
            // Blur regions are rounded rects: follow the clip (e.g. folder reveal) bounds.
            clip.computeBounds(clipBounds, true)
            l = maxOf(l, clipBounds.left)
            t = maxOf(t, clipBounds.top)
            rr = minOf(rr, clipBounds.right)
            b = minOf(b, clipBounds.bottom)
            if (rr <= l || b <= t) {
                d.alpha = 0
                return
            }
            radius = min(radius, min(rr - l, b - t) / 2f)
        }
        blurBounds.set(l.toInt(), t.toInt(), rr.toInt(), b.toInt())
        d.bounds = blurBounds
        SystemBlur.configure(d, blurRadiusPx(), radius, Color.TRANSPARENT)
        val hostAlpha = if (trackHostAlpha) effectiveAlpha(host) else 1f
        d.alpha = (255 * (alpha * hostAlpha).coerceIn(0f, 1f)).toInt()
        d.draw(canvas)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawShader(
        canvas: Canvas,
        backdrop: BitmapShader,
        w: Float,
        h: Float,
        r: Float,
        alpha: Float,
        pad: Float,
    ) {
        val prefs = IosPrefs.get(appContext)
        val shader = (runtimeShader as? RuntimeShader) ?: RuntimeShader(LIQUID_GLASS_AGSL).also {
            runtimeShader = it
        }
        val tint = resolveTint()
        // One ReGlass unit (a Minecraft framebuffer pixel) ~ 1.2dp on a phone.
        val unit = 1.2f * density
        val refraction = prefs.iosGlassRefraction.get().coerceIn(0f, 1f) * refractionScale
        val highlight = prefs.iosGlassHighlight.get().coerceIn(0f, 1f) / 0.7f
        // Rim thickness: ReGlass default 13.15 units, but never deeper than a third of the surface.
        val thickness = minOf(13.15f, min(w, h) / unit / 3f)
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uPad", pad)
        shader.setFloatUniform("uRadius", r)
        shader.setFloatUniform("uUnit", unit)
        shader.setFloatUniform("uThickness", thickness)
        shader.setFloatUniform("uIor", 1.4f)
        // ReGlass offset = 0.08 * screen height (~86 units at 1080p) for refraction 0.6.
        shader.setFloatUniform("uRefrScale", refraction / 0.6f * 86.4f * unit * (thickness / 13.15f))
        shader.setFloatUniform("uDispersion", prefs.iosGlassDispersion.get().coerceIn(0f, 1f) * 20f)
        shader.setFloatUniform("uFresnelRange", 39.71f)
        shader.setFloatUniform("uFresnelHard", 0.2f)
        shader.setFloatUniform("uFresnelFactor", 0.2f * highlight)
        shader.setFloatUniform("uGlareRange", 30f)
        shader.setFloatUniform("uGlareHard", 0.2f)
        shader.setFloatUniform("uGlareFactor", 0.9f * highlight)
        shader.setFloatUniform("uTint", tint[0], tint[1], tint[2], tint[3] * 0.75f)
        shader.setFloatUniform("uSaturation", prefs.iosGlassSaturation.get().coerceIn(0f, 2.5f))
        shader.setFloatUniform("uLight", GlassMotion.lightX, GlassMotion.lightY)
        shader.setFloatUniform("uShadowExpand", 18.2f)
        shader.setFloatUniform("uShadowFactor", if (pad > 0f) 0.208f else 0f)
        shader.setFloatUniform("uAlpha", alpha.coerceIn(0f, 1f))
        shader.setInputShader("backdrop", backdrop)
        fillPaint.shader = shader
        fillPaint.alpha = 255
        tmpRect.set(-pad, -pad, w + pad, h + pad)
        canvas.save()
        canvas.translate(-pad, -pad)
        tmpRect.set(0f, 0f, w + 2 * pad, h + 2 * pad)
        canvas.drawRect(tmpRect, fillPaint)
        canvas.restore()
        fillPaint.shader = null
    }

    /**
     * Glass film drawn on top of a blurred backdrop (system blur or wallpaper bitmap): tint,
     * top sheen, soft inner lens glow and a light-direction dependent specular rim.
     */
    private fun drawOverlay(
        canvas: Canvas,
        backdrop: BitmapShader?,
        w: Float,
        h: Float,
        r: Float,
        alpha: Float,
        blurred: Boolean,
    ) {
        val prefs = IosPrefs.get(appContext)
        val a = alpha.coerceIn(0f, 1f)
        tmpRect.set(0f, 0f, w, h)
        val tint = resolveTint()
        if (backdrop != null) {
            fillPaint.shader = backdrop
            fillPaint.alpha = (a * 255).toInt()
            canvas.drawRoundRect(tmpRect, r, r, fillPaint)
            fillPaint.shader = null
        }
        if (!blurred) {
            // No blur available at all: denser frosted film so content stays legible.
            tint[3] = (tint[3] + 0.18f).coerceAtMost(0.9f)
        }
        fillPaint.shader = null
        fillPaint.color = Color.argb(
            (tint[3] * a * 255).toInt(),
            (tint[0] * 255).toInt(),
            (tint[1] * 255).toInt(),
            (tint[2] * 255).toInt(),
        )
        canvas.drawRoundRect(tmpRect, r, r, fillPaint)

        val highlight = prefs.iosGlassHighlight.get().coerceIn(0f, 1f)

        // Top sheen.
        fillPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(Color.argb((highlight * a * 34).toInt(), 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f),
            Shader.TileMode.CLAMP,
        )
        fillPaint.color = Color.WHITE
        canvas.drawRoundRect(tmpRect, r, r, fillPaint)
        fillPaint.shader = null

        // Light direction (top-left by default, follows device tilt).
        val lx = -GlassMotion.lightX
        val ly = -GlassMotion.lightY
        val cx = w / 2f
        val cy = h / 2f
        val ext = maxOf(w, h) / 2f
        val sx = cx + lx * ext
        val sy = cy + ly * ext
        val ex = cx - lx * ext
        val ey = cy - ly * ext

        // Soft inner lens glow along the edge.
        val glowW = (min(w, h) * 0.18f).coerceIn(3f * density, 10f * density)
        val glowAlpha = (highlight * a * 40).toInt()
        rimPaint.strokeWidth = glowW
        rimPaint.shader = LinearGradient(
            sx,
            sy,
            ex,
            ey,
            intArrayOf(
                Color.argb(glowAlpha, 255, 255, 255),
                Color.argb(glowAlpha / 5, 255, 255, 255),
                Color.argb(glowAlpha / 2, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        var inset = glowW / 2f
        tmpRect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(tmpRect, (r - inset).coerceAtLeast(0f), (r - inset).coerceAtLeast(0f), rimPaint)

        // Crisp specular rim.
        val rimAlpha = (highlight * a * 235).toInt()
        rimPaint.strokeWidth = 1.1f * density
        rimPaint.shader = LinearGradient(
            sx,
            sy,
            ex,
            ey,
            intArrayOf(
                Color.argb(rimAlpha, 255, 255, 255),
                Color.argb(rimAlpha / 7, 255, 255, 255),
                Color.argb(rimAlpha / 2, 255, 255, 255),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        inset = rimPaint.strokeWidth / 2f
        tmpRect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(tmpRect, r - inset, r - inset, rimPaint)
        rimPaint.shader = null
    }

    companion object {
        @Volatile
        private var shaderBroken = false

        /**
         * AGSL port of the ReGlass liquid glass optics
         * (https://github.com/RedxAx/ReGlass, MIT License, Copyright (c) 2025 ReStudio By RedxAx).
         *
         * Rounded-box SDF with analytic gradient, Snell's-law refraction inside a rim of
         * thickness `uThickness`, per-channel dispersion, Fresnel rim tint, light-direction glare
         * and a soft drop shadow outside the shape. Adapted for IOSChair: single surface per draw,
         * Android canvas coordinates (y down), units scaled by display density, tilt-driven light
         * and premultiplied output.
         *
         * Draw area = shape rect grown by `uPad` on every side (room for the shadow).
         */
        const val LIQUID_GLASS_AGSL = """
uniform shader backdrop;
uniform float2 uSize;
uniform float uPad;
uniform float uRadius;
uniform float uUnit;
uniform float uThickness;
uniform float uIor;
uniform float uRefrScale;
uniform float uDispersion;
uniform float uFresnelRange;
uniform float uFresnelHard;
uniform float uFresnelFactor;
uniform float uGlareRange;
uniform float uGlareHard;
uniform float uGlareFactor;
uniform float4 uTint;
uniform float uSaturation;
uniform float2 uLight;
uniform float uShadowExpand;
uniform float uShadowFactor;
uniform float uAlpha;

float3 sdgBox(float2 p, float2 b, float r) {
    float2 w = abs(p) - (b - float2(r, r));
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float g = max(w.x, w.y);
    float2 q = max(w, float2(0.0, 0.0));
    float l = length(q);
    float dist = (g > 0.0) ? l - r : g - r;
    float2 n = (g > 0.0) ? (q / max(l, 1e-6)) : ((w.x > w.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
    return float3(dist, s * n);
}

float band(float du, float range, float hard) {
    float k = pow(500.0 / max(range, 1e-6), 2.0) / 1500.0;
    return clamp(pow(max(1.0 + du * k + hard, 0.0), 5.0), 0.0, 1.0);
}

half4 main(float2 coord) {
    float2 hs = uSize * 0.5;
    float2 p = coord - float2(uPad, uPad) - hs;
    float r = min(uRadius, min(hs.x, hs.y));
    float3 g = sdgBox(p, hs, r);
    float d = g.x;
    float du = d / uUnit;

    // Soft drop shadow (ReGlass bg pass), offset 2 units down.
    float shA = 0.0;
    if (uShadowFactor > 0.0) {
        float ds = sdgBox(p - float2(0.0, 2.0 * uUnit), hs, r).x / uUnit;
        shA = clamp(exp(-abs(ds) / max(uShadowExpand, 1e-4)) * 0.6 * uShadowFactor, 0.0, 1.0);
    }

    float aa = clamp(0.5 - d, 0.0, 1.0);
    if (aa <= 0.0) {
        return half4(0.0, 0.0, 0.0, half(shA * uAlpha));
    }

    float2 n = g.yz;
    float nlen = length(n);
    if (nlen > 1e-6) n /= nlen;

    // Snell refraction through the rounded rim.
    float depth = -du;
    float xR = 1.0 - depth / max(uThickness, 1e-6);
    float thetaI = asin(pow(clamp(xR, 0.0, 1.0), 2.0));
    float thetaT = asin(clamp((1.0 / max(uIor, 1e-6)) * sin(thetaI), -1.0, 1.0));
    float edge = -tan(thetaT - thetaI);
    if (depth >= uThickness) edge = 0.0;
    float2 off = -n * edge * uRefrScale;

    float3 col = float3(0.0, 0.0, 0.0);
    col.r = backdrop.eval(coord + off * (1.0 - (0.985 - 1.0) * uDispersion)).r;
    col.g = backdrop.eval(coord + off).g;
    col.b = backdrop.eval(coord + off * (1.0 - (1.015 - 1.0) * uDispersion)).b;

    // Vibrancy (iOS boosts saturation behind glass).
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(lum, lum, lum), col, uSaturation);

    col = mix(col, uTint.rgb, uTint.a * 0.8);

    float fres = band(du, uFresnelRange, uFresnelHard);
    float3 fresTint = mix(float3(1.0, 1.0, 1.0), uTint.rgb, uTint.a * 0.5);
    col = mix(col, fresTint, fres * uFresnelFactor * 0.7 * nlen);

    // Glare: strongest on the edge facing the light, weaker on the opposite edge.
    float glare = band(du, uGlareRange, uGlareHard);
    float facing = dot(n, -uLight);
    float dirTerm = max(facing, 0.0) + 0.48 * max(-facing, 0.0);
    col = mix(col, float3(1.0, 1.0, 1.0), clamp(glare * uGlareFactor * 0.45 * dirTerm, 0.0, 1.0));

    col = clamp(col, 0.0, 1.0);
    float a = aa * uAlpha;
    float sa = shA * uAlpha * (1.0 - aa);
    return half4(half3(col * a), half(a + sa));
}
"""
    }
}
