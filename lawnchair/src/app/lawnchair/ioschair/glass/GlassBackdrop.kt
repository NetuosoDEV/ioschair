/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import app.lawnchair.ioschair.IosPrefs
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import com.android.launcher3.util.Executors
import com.hoko.blur.HokoBlur
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holds a pre-blurred, down-sampled copy of the current wallpaper laid out in *screen space*.
 *
 * Every Liquid Glass surface (dock, folders, popups, search pill…) samples this bitmap at its own
 * on-screen position, which gives a convincing "frosted glass over the wallpaper" effect without
 * needing system cross-window blur (which third-party launchers cannot use).
 *
 * Only used for static wallpapers with wallpaper access granted. Live wallpapers or missing
 * access leave [bitmap] null and painters use real-time [SystemBlur] instead.
 */
object GlassBackdrop {

    private const val TAG = "GlassBackdrop"

    /** Blurred backdrop; laid out so that bitmap pixel * [scale] == screen pixel. */
    @Volatile
    var bitmap: Bitmap? = null
        private set

    /** Screen pixels per backdrop pixel. */
    @Volatile
    var scale: Float = 1f
        private set

    /** Incremented each time [bitmap] changes, so painters can rebuild their shaders. */
    @Volatile
    var version: Int = 0
        private set

    /** Average luminance (0..1) of the wallpaper, used for adaptive tinting. */
    @Volatile
    var luminance: Float = 0.5f
        private set

    /** True when [bitmap] comes from the real wallpaper rather than synthesized colours. */
    @Volatile
    var isRealWallpaper: Boolean = false
        private set

    private val hosts = WeakHashMap<View, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var appContext: Context? = null
    private var pendingRefresh = false

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, which ->
        if (which and WallpaperManager.FLAG_SYSTEM != 0) {
            appContext?.let { refresh(it) }
        }
    }

    @JvmStatic
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        runCatching {
            WallpaperManager.getInstance(app).addOnColorsChangedListener(colorsListener, mainHandler)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Battery saver / developer options can toggle cross-window blur at runtime.
            runCatching {
                app.getSystemService(WindowManager::class.java)
                    ?.addCrossWindowBlurEnabledListener(app.mainExecutor) { invalidateHosts() }
            }
        }
        refresh(app)
    }

    /** Registers a view to be invalidated whenever the backdrop or the light direction changes. */
    @JvmStatic
    fun track(view: View) {
        synchronized(hosts) { hosts[view] = true }
    }

    @JvmStatic
    fun untrack(view: View) {
        synchronized(hosts) { hosts.remove(view) }
    }

    /** Invalidates all tracked glass hosts (must be called on main thread). */
    @JvmStatic
    fun invalidateHosts() {
        val list = synchronized(hosts) { hosts.keys.toList() }
        for (v in list) {
            if (v.isAttachedToWindow) v.invalidate()
        }
    }

    private var lastRealAccess: Boolean? = null

    /** Rebuilds the backdrop when wallpaper access was granted / revoked since last build. */
    @JvmStatic
    fun refreshIfAccessChanged(context: Context) {
        val access = runCatching {
            FileAccessManager.getInstance(context).refresh()
            FileAccessManager.getInstance(context).wallpaperAccessState.value != FileAccessState.Denied
        }.getOrDefault(false)
        if (lastRealAccess != null && lastRealAccess != access) refresh(context)
        lastRealAccess = access
    }

    /** Thread-safe variant of [invalidateHosts]. */
    @JvmStatic
    fun postInvalidateHosts() {
        mainHandler.post { invalidateHosts() }
    }

    @JvmStatic
    fun refresh(context: Context) {
        if (pendingRefresh) return
        pendingRefresh = true
        val app = context.applicationContext
        Executors.UI_HELPER_EXECUTOR.execute {
            try {
                build(app)
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to build glass backdrop", t)
            } finally {
                pendingRefresh = false
            }
            mainHandler.post { invalidateHosts() }
        }
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    @SuppressLint("MissingPermission")
    private fun build(context: Context) {
        val prefs = IosPrefs.get(context)
        val (sw, sh) = screenSize(context)
        if (sw <= 0 || sh <= 0) return

        // Blur strength 0..100 drives both down-sampling and the blur kernel.
        val strength = prefs.iosGlassBlur.get().coerceIn(0, 100) / 100f
        val downsample = 3f + strength * 7f // 3x .. 10x
        val bw = max(8, (sw / downsample).roundToInt())
        val bh = max(8, (sh / downsample).roundToInt())

        val source = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)

        var real = false
        val hasAccess = runCatching {
            FileAccessManager.getInstance(context).wallpaperAccessState.value != FileAccessState.Denied
        }.getOrDefault(false)
        val wm = WallpaperManager.getInstance(context)
        // Live wallpapers (e.g. Xiaomi "super wallpapers") have no readable bitmap: getDrawable()
        // returns the stock image, which would show a wrong/grey backdrop. Use system blur instead.
        val isLive = runCatching { wm.wallpaperInfo != null }.getOrDefault(false)
        if (hasAccess && !isLive) {
            val drawable = runCatching { wm.drawable }.getOrNull()
            if (drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                // Center-crop the wallpaper to the screen like the system does (no scrolling).
                val dw = drawable.intrinsicWidth.toFloat()
                val dh = drawable.intrinsicHeight.toFloat()
                val s = max(bw / dw, bh / dh)
                val w = dw * s
                val h = dh * s
                val l = (bw - w) / 2f
                val t = (bh - h) / 2f
                drawable.setBounds(l.roundToInt(), t.roundToInt(), (l + w).roundToInt(), (t + h).roundToInt())
                drawable.draw(canvas)
                real = true
            }
        }
        if (!real) {
            // Never fake the backdrop with a synthetic image: it reads as a grey placeholder.
            // Painters fall back to real-time system blur or a translucent frosted tint.
            source.recycle()
            bitmap = null
            isRealWallpaper = false
            version++
            return
        }

        val radius = (4 + strength * 21).roundToInt().coerceIn(1, 25)
        val blurred = runCatching {
            HokoBlur.with(context)
                .scheme(HokoBlur.SCHEME_NATIVE)
                .mode(HokoBlur.MODE_STACK)
                .radius(radius)
                .sampleFactor(1f)
                .forceCopy(true)
                .blur(source)
        }.getOrElse {
            Log.w(TAG, "HokoBlur failed, using unblurred backdrop", it)
            source
        }

        luminance = computeLuminance(blurred)
        scale = sw.toFloat() / blurred.width
        bitmap = blurred
        isRealWallpaper = real
        version++
    }

    private fun computeLuminance(bmp: Bitmap): Float {
        val step = max(1, min(bmp.width, bmp.height) / 16)
        var sum = 0.0
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
                n++
                x += step
            }
            y += step
        }
        return if (n == 0) 0.5f else (sum / n).toFloat()
    }
}
