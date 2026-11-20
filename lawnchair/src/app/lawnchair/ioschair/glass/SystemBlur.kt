/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Method

/**
 * Real-time background blur of whatever is *behind the launcher window* (the wallpaper, live
 * wallpapers included), done by SurfaceFlinger exactly like the Pixel launcher / SystemUI do it.
 *
 * Uses the platform `BackgroundBlurDrawable` (Android 12+) created through
 * `ViewRootImpl#createBackgroundBlurDrawable()`. Lawnchair already lifts hidden-API restrictions
 * at startup (`Unseal`), so the calls are made reflectively. Everything is guarded: if any step is
 * unavailable the caller falls back to wallpaper sampling or a plain frosted tint.
 */
object SystemBlur {

    private const val TAG = "IosSystemBlur"

    private var resolved = false
    private var getViewRootImpl: Method? = null
    private var createBlurDrawable: Method? = null
    private var setBlurRadius: Method? = null
    private var setCornerRadius: Method? = null
    private var setColor: Method? = null

    @Volatile
    private var broken = false

    private fun resolve() {
        if (resolved) return
        resolved = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            getViewRootImpl = View::class.java.getMethod("getViewRootImpl")
            val vri = Class.forName("android.view.ViewRootImpl")
            createBlurDrawable = vri.getMethod("createBackgroundBlurDrawable")
            val bbd = Class.forName("com.android.internal.graphics.drawable.BackgroundBlurDrawable")
            setBlurRadius = bbd.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
            setCornerRadius = bbd.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
            setColor = bbd.getMethod("setColor", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            Log.w(TAG, "System blur unavailable", t)
            broken = true
        }
    }

    /** True when the device supports and currently allows cross-window blur. */
    @JvmStatic
    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        resolve()
        if (broken || createBlurDrawable == null) return false
        val wm = context.getSystemService(WindowManager::class.java) ?: return false
        return runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
    }

    /** Creates a blur drawable bound to [view]'s window, or null if not possible right now. */
    @JvmStatic
    fun create(view: View): Drawable? {
        resolve()
        if (broken) return null
        return try {
            val vri = getViewRootImpl?.invoke(view) ?: return null
            createBlurDrawable?.invoke(vri) as? Drawable
        } catch (t: Throwable) {
            Log.w(TAG, "createBackgroundBlurDrawable failed", t)
            broken = true
            null
        }
    }

    /** Returns the ViewRootImpl identity of [view] (used to detect re-attachment). */
    @JvmStatic
    fun rootOf(view: View): Any? = try {
        getViewRootImpl?.invoke(view)
    } catch (_: Throwable) {
        null
    }

    @JvmStatic
    fun configure(drawable: Drawable, blurRadiusPx: Int, cornerRadiusPx: Float, color: Int) {
        try {
            setBlurRadius?.invoke(drawable, blurRadiusPx)
            setCornerRadius?.invoke(drawable, cornerRadiusPx)
            setColor?.invoke(drawable, color)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to configure blur drawable", t)
            broken = true
        }
    }
}
