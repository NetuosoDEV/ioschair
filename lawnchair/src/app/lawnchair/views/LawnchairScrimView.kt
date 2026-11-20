package app.lawnchair.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import app.lawnchair.ioschair.IosPrefs
import app.lawnchair.ioschair.glass.GlassBackdrop
import android.util.AttributeSet
import app.lawnchair.preferences.PreferenceManager
import com.android.launcher3.R
import com.android.launcher3.util.SystemUiController
import com.android.launcher3.util.Themes
import com.android.launcher3.views.ScrimView

class LawnchairScrimView(context: Context, attrs: AttributeSet?) : ScrimView(context, attrs) {

    private var drawerOpacity = 0f

    init {
        val pm = PreferenceManager.getInstance(context)
        pm.drawerOpacity.subscribeValues(this) {
            drawerOpacity = it
        }
    }

    // IOSChair: App Library style frosted wallpaper behind the app drawer
    private val drawerGlass = IosPrefs.drawerGlass(context)
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val backdropMatrix = Matrix()
    private val loc = IntArray(2)

    override fun draw(canvas: Canvas) {
        if (drawerGlass) {
            val bmp = GlassBackdrop.bitmap
            val maxAlpha = (255f * drawerOpacity).coerceAtLeast(1f)
            val progress = (Color.alpha(mBackgroundColor) / maxAlpha).coerceIn(0f, 1f)
            if (bmp != null && progress > 0f) {
                GlassBackdrop.track(this)
                getLocationOnScreen(loc)
                backdropMatrix.setScale(GlassBackdrop.scale, GlassBackdrop.scale)
                backdropMatrix.postTranslate(-loc[0].toFloat(), -loc[1].toFloat())
                backdropPaint.alpha = (progress * 255).toInt()
                canvas.drawBitmap(bmp, backdropMatrix, backdropPaint)
            }
        }
        super.draw(canvas)
    }

    override fun updateSysUiColors() {
        val threshold = STATUS_BAR_COLOR_FORCE_UPDATE_THRESHOLD
        val forceChange = visibility == VISIBLE &&
            alpha > threshold &&
            Color.alpha(mBackgroundColor) / (255f * drawerOpacity) > threshold
        with(systemUiController) {
            if (forceChange) {
                updateUiState(SystemUiController.UI_STATE_SCRIM_VIEW, !isScrimDark)
            } else {
                updateUiState(SystemUiController.UI_STATE_SCRIM_VIEW, 0)
            }
        }
    }

    override fun isScrimDark() = if (drawerOpacity <= 0.3f) {
        !Themes.getAttrBoolean(context, R.attr.isWorkspaceDarkText)
    } else {
        super.isScrimDark()
    }
}
