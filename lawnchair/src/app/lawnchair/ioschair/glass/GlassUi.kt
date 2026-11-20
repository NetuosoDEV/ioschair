/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import app.lawnchair.ioschair.IosPrefs

/** Helpers that convert existing Launcher3 surfaces to Liquid Glass. */
object GlassUi {

    private fun findGradient(d: Drawable?): GradientDrawable? = when (d) {
        is GradientDrawable -> d
        is InsetDrawable -> findGradient(d.drawable)
        is RippleDrawable, is LayerDrawable -> {
            val ld = d as LayerDrawable
            (0 until ld.numberOfLayers).firstNotNullOfOrNull { findGradient(ld.getDrawable(it)) }
        }
        else -> null
    }

    private fun radiusOf(gd: GradientDrawable): Float {
        val radii = runCatching { gd.cornerRadii }.getOrNull()
        return radii?.maxOrNull() ?: gd.cornerRadius
    }

    /**
     * Replaces rounded backgrounds of popup items (long-press menu) with Liquid Glass, keeping
     * the corner radii. Called after Launcher3 assigned its own backgrounds.
     */
    @JvmStatic
    fun glassifyPopup(root: ViewGroup) {
        if (!IosPrefs.popupGlass(root.context)) return
        val density = root.resources.displayMetrics.density
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val bg = child.background
            val gd = findGradient(bg)
            if (gd != null && child.background !is GlassDrawable) {
                val radius = maxOf(radiusOf(gd), 22f * density)
                child.background = GlassDrawable(child.context, radius).apply {
                    painter.tintBoost = 0.08f
                    painter.refractionScale = 0.6f
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) child.clipToOutline = false
            } else if (child is ViewGroup && bg == null) {
                glassifyPopup(child)
            }
        }
    }

    /** Sets a glass background on [view] with the given corner radius (in dp). */
    @JvmStatic
    fun applyGlassBackground(view: View, radiusDp: Float) {
        val density = view.resources.displayMetrics.density
        view.background = GlassDrawable(view.context, radiusDp * density)
    }
}
