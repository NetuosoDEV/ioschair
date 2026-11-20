/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ioschair

import android.content.Context
import app.lawnchair.preferences.PreferenceManager

/** Small facade so Java and Kotlin call sites read the IOSChair preferences uniformly. */
object IosPrefs {
    @JvmStatic
    fun get(context: Context): PreferenceManager = PreferenceManager.getInstance(context)

    @JvmStatic
    fun glass(context: Context): Boolean = get(context).iosGlassEnabled.get()

    @JvmStatic
    fun dockGlass(context: Context): Boolean = glass(context) && get(context).iosDockGlass.get()

    @JvmStatic
    fun folderGlass(context: Context): Boolean = glass(context) && get(context).iosFolderGlass.get()

    @JvmStatic
    fun popupGlass(context: Context): Boolean = glass(context) && get(context).iosPopupGlass.get()

    @JvmStatic
    fun drawerGlass(context: Context): Boolean = glass(context) && get(context).iosDrawerGlass.get()

    @JvmStatic
    fun searchPill(context: Context): Boolean = get(context).iosSearchPill.get()

    @JvmStatic
    fun folderGrid(context: Context): Boolean = get(context).iosFolderGrid.get()

    @JvmStatic
    fun jiggle(context: Context): Boolean = get(context).iosJiggle.get()
}
