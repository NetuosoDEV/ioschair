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
        val source = IosPrefs.get(appContext).iosGlassSource.get()
        val systemOk = canvas.isHardwareAccelerated && SystemBlur.isAvailable(appContext)
        val shaderOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !shaderBroken &&
            hasBitmap && GlassBackdrop.isRealWallpaper
        return when (source) {
            "wallpaper" -> when {
                shaderOk -> Mode.SHADER
                hasBitmap && GlassBackdrop.isRealWallpaper -> Mode.BITMAP
                systemOk -> Mode.SYSTEM_BLUR
                else -> Mode.TINT_ONLY
            }
            else -> when {
                systemOk -> Mode.SYSTEM_BLUR
                shaderOk -> Mode.SHADER
                hasBitmap && GlassBackdrop.isRealWallpaper -> Mode.BITMAP
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

        if (backdrop != null && (mode == Mode.SHADER || mode == Mode.BITMAP)) {
            host.getLocationOnScreen(loc)
            matrix.reset()
            matrix.setScale(GlassBackdrop.scale, GlassBackdrop.scale)
            matrix.postTranslate(-(loc[0] + left), -(loc[1] + top))
            backdrop.setLocalMatrix(matrix)
        }

        when (mode) {
            Mode.SHADER -> try {
                drawShader(canvas, backdrop!!, w, h, r, alpha)
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
        d.alpha = (255 * (alpha * effectiveAlpha(host)).coerceIn(0f, 1f)).toInt()
        d.draw(canvas)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawShader(canvas: Canvas, backdrop: BitmapShader, w: Float, h: Float, r: Float, alpha: Float) {
        val prefs = IosPrefs.get(appContext)
        val shader = (runtimeShader as? RuntimeShader) ?: RuntimeShader(LIQUID_GLASS_AGSL).also {
            runtimeShader = it
        }
        val tint = resolveTint()
        val minSide = min(w, h)
        val refraction = prefs.iosGlassRefraction.get().coerceIn(0f, 1f) * refractionScale
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uRadius", r)
        shader.setFloatUniform("uRefraction", refraction * minSide * 0.22f)
        shader.setFloatUniform("uEdge", (minSide * 0.32f).coerceAtMost(28f * density).coerceAtLeast(4f * density))
        shader.setFloatUniform("uTint", tint[0], tint[1], tint[2], tint[3])
        shader.setFloatUniform("uHighlight", prefs.iosGlassHighlight.get().coerceIn(0f, 1f))
        shader.setFloatUniform("uDispersion", prefs.iosGlassDispersion.get().coerceIn(0f, 1f))
        shader.setFloatUniform("uSaturation", prefs.iosGlassSaturation.get().coerceIn(0f, 2.5f))
        shader.setFloatUniform("uLight", GlassMotion.lightX, GlassMotion.lightY)
        shader.setFloatUniform("uDensity", density)
        shader.setFloatUniform("uAlpha", alpha.coerceIn(0f, 1f))
        shader.setInputShader("backdrop", backdrop)
        fillPaint.shader = shader
        fillPaint.alpha = 255
        tmpRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(tmpRect, r, r, fillPaint)
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
         * AGSL Liquid Glass shader. Coordinates are local to the surface (0..uSize).
         * Output is premultiplied.
         */
        const val LIQUID_GLASS_AGSL = """
uniform shader backdrop;
uniform float2 uSize;
uniform float uRadius;
uniform float uRefraction;
uniform float uEdge;
uniform float4 uTint;
uniform float uHighlight;
uniform float uDispersion;
uniform float uSaturation;
uniform float2 uLight;
uniform float uDensity;
uniform float uAlpha;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 hs = uSize * 0.5;
    float2 p = coord - hs;
    float r = min(uRadius, min(hs.x, hs.y));
    float d = sdRoundRect(p, hs, r);

    float2 ex = float2(1.0, 0.0);
    float2 ey = float2(0.0, 1.0);
    float2 n = float2(
        sdRoundRect(p + ex, hs, r) - sdRoundRect(p - ex, hs, r),
        sdRoundRect(p + ey, hs, r) - sdRoundRect(p - ey, hs, r));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);

    // 0 at the rim, 1 once we are uEdge px inside.
    float depth = clamp(-d / max(uEdge, 1.0), 0.0, 1.0);
    float bend = (1.0 - depth);
    bend = bend * bend * bend;

    // Lensing: sample from further inside near the rim (magnifies + bends the backdrop).
    float2 off = -n * bend * uRefraction;

    float3 col = float3(0.0, 0.0, 0.0);
    if (uDispersion > 0.001) {
        float2 dd = off * (0.18 * uDispersion);
        col.r = backdrop.eval(coord + off + dd).r;
        col.g = backdrop.eval(coord + off).g;
        col.b = backdrop.eval(coord + off - dd).b;
    } else {
        col = backdrop.eval(coord + off).rgb;
    }

    // Vibrancy.
    float l = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(l, l, l), col, uSaturation);

    // Tint film.
    col = mix(col, uTint.rgb, uTint.a);

    // Specular rim: bright where the edge faces the light, softer on the opposite side.
    float rimW = 1.4 * uDensity;
    float rim = 1.0 - smoothstep(0.0, rimW, -d);
    float glow = 1.0 - smoothstep(0.0, rimW * 4.0, -d);
    float facing = dot(n, -uLight);
    float spec = rim * (0.22 + 0.78 * max(facing, 0.0)) + rim * 0.5 * max(-facing, 0.0);
    spec += glow * 0.18 * max(facing, 0.0);
    col += float3(spec * uHighlight);

    // Inner lens brightening + a faint top sheen.
    col += float3(bend * 0.07 * uHighlight);
    col += float3((1.0 - coord.y / uSize.y) * 0.035 * uHighlight);

    col = clamp(col, 0.0, 1.0);
    return half4(half3(col) * half(uAlpha), half(uAlpha));
}
"""
    }
}
