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

    /** Liquid Glass refraction effect (frosted glass surfaces work without it). */
    @JvmStatic
    fun glass(context: Context): Boolean = get(context).iosGlassEnabled.get()

    @JvmStatic
    fun dockGlass(context: Context): Boolean = get(context).iosDockGlass.get()

    @JvmStatic
    fun folderGlass(context: Context): Boolean = get(context).iosFolderGlass.get()

    @JvmStatic
    fun popupGlass(context: Context): Boolean = get(context).iosPopupGlass.get()

    @JvmStatic
    fun drawerGlass(context: Context): Boolean = get(context).iosDrawerGlass.get()

    @JvmStatic
    fun searchPill(context: Context): Boolean = get(context).iosSearchPill.get()

    @JvmStatic
    fun folderGrid(context: Context): Boolean = get(context).iosFolderGrid.get()

    @JvmStatic
    fun jiggle(context: Context): Boolean = get(context).iosJiggle.get()
}
