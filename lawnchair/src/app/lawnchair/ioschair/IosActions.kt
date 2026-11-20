/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair

import android.content.Context
import app.lawnchair.animateToAllApps
import com.android.launcher3.Launcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Java-friendly entry points for IOSChair interactions. */
object IosActions {

    /** Opens the app library with the search field focused (iOS 26 "Search" pill). */
    @JvmStatic
    fun openSearch(context: Context) {
        val launcher = runCatching { Launcher.getLauncher(context) }.getOrNull() ?: return
        val searchUiManager = launcher.appsView.searchUiManager
        searchUiManager.setDirectFocus(true)
        searchUiManager.editText?.showKeyboard()
        MainScope().launch {
            launcher.animateToAllApps()
        }
    }
}
