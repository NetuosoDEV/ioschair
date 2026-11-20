/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View

/**
 * A [Drawable] that renders Liquid Glass. Set it as a view background: the host view is
 * discovered through the drawable callback, so the glass samples the right part of the wallpaper.
 */
class GlassDrawable @JvmOverloads constructor(
    context: Context,
    var cornerRadius: Float,
    private val insets: Rect = Rect(),
) : Drawable() {

    val painter = GlassPainter(context)
    private var alphaFraction = 1f

    /** Optional explicit host when the callback is not a view (e.g. wrapped drawables). */
    var host: View? = null

    override fun draw(canvas: Canvas) {
        val view = host ?: (callback as? View) ?: return
        val b = bounds
        painter.draw(
            canvas,
            view,
            (b.left + insets.left).toFloat(),
            (b.top + insets.top).toFloat(),
            (b.right - insets.right).toFloat(),
            (b.bottom - insets.bottom).toFloat(),
            cornerRadius,
            alphaFraction,
        )
    }

    override fun getOutline(outline: Outline) {
        val b = bounds
        outline.setRoundRect(
            b.left + insets.left,
            b.top + insets.top,
            b.right - insets.right,
            b.bottom - insets.bottom,
            cornerRadius,
        )
        outline.alpha = 0f
    }

    override fun setAlpha(alpha: Int) {
        alphaFraction = alpha / 255f
        invalidateSelf()
    }

    override fun getAlpha(): Int = (alphaFraction * 255).toInt()

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
