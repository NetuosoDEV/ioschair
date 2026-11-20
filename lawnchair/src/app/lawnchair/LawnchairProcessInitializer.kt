package app.lawnchair

import android.content.Context
import androidx.annotation.Keep
import app.lawnchair.bugreport.LawnchairBugReporter
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.launcher3.Utilities
import com.android.launcher3.icons.mono.ThemedIconDrawable
import com.android.quickstep.QuickstepProcessInitializer

@Keep
class LawnchairProcessInitializer(context: Context) : QuickstepProcessInitializer(context) {

    override fun init(context: Context) {
        LawnchairBugReporter.INSTANCE.get(context)
        ThemedIconDrawable.COLORS_LOADER = {
            // IOSChair: iOS 26 "Clear" / "Tinted" icon looks for themed icons.
            val iosStyle = app.lawnchair.ioschair.IosPrefs.get(it).iosIconStyle.get()
            if (iosStyle == "clear") {
                if (Utilities.isDarkTheme(it)) {
                    intArrayOf(0x38FFFFFF, 0xF0FFFFFF.toInt())
                } else {
                    intArrayOf(0x4DFFFFFF, 0xFFFFFFFF.toInt())
                }
            } else if (iosStyle == "dark") {
                intArrayOf(0xFF1C1C1E.toInt(), 0xFFF2F2F7.toInt())
            } else if (Utilities.isDarkTheme(it)) {
                intArrayOf(
                    ColorTokens.Accent2_800.resolveColor(it),
                    ColorTokens.Accent1_200.resolveColor(it),
                )
            } else {
                intArrayOf(
                    ColorTokens.Accent1_100.resolveColor(it),
                    ColorTokens.Accent1_700.resolveColor(it),
                )
            }
        }
        super.init(context)
    }
}
