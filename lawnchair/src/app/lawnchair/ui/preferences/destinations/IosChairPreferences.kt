/*
 * Copyright 2026, IOSChair
 *
 * Licensed under the GNU General Public License v3.0 (same as Lawnchair).
 */

package app.lawnchair.ui.preferences.destinations

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.hotseat.DisabledHotseat
import app.lawnchair.icons.shape.IconShape
import app.lawnchair.ioschair.glass.GlassBackdrop
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalIsExpandedScreen
import app.lawnchair.ui.preferences.components.WallpaperAccessPermissionDialog
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.MainSwitchPreference
import app.lawnchair.ui.preferences.components.controls.SliderPreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.ExpandAndShrink
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.util.FileAccessManager
import app.lawnchair.util.FileAccessState
import com.android.launcher3.R
import kotlinx.coroutines.launch

/**
 * IOSChair settings: everything that makes the launcher look like iOS 26, with every knob of the
 * Liquid Glass material exposed for fine tuning.
 */
@Composable
fun IosChairPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs = preferenceManager()
    val prefs2 = preferenceManager2()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val fileAccessManager = remember { FileAccessManager.getInstance(context) }
    val allFilesAccessState by fileAccessManager.allFilesAccessState.collectAsStateWithLifecycle()
    val wallpaperAccessState by fileAccessManager.wallpaperAccessState.collectAsStateWithLifecycle()
    val hasWallpaperAccess = wallpaperAccessState != FileAccessState.Denied
    var showPermissionDialog by remember { mutableStateOf(false) }

    PreferenceLayout(
        label = stringResource(id = R.string.ios_settings_label),
        backArrowVisible = !LocalIsExpandedScreen.current,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = stringResource(R.string.ios_group_home)) {
            ClickablePreference(
                label = stringResource(R.string.ios_preset_apply),
                subtitle = stringResource(R.string.ios_preset_apply_desc),
            ) {
                prefs.workspaceColumns.set(4)
                prefs.workspaceRows.set(6)
                prefs.hotseatColumns.set(4)
                prefs.hotseatBG.set(false)
                prefs.wallpaperScrolling.set(false)
                scope.launch {
                    prefs2.iconShape.set(IconShape.Cupertino)
                    prefs2.folderShape.set(IconShape.Cupertino)
                    prefs2.hotseatMode.set(DisabledHotseat)
                    prefs2.enableSmartspace.set(false)
                    prefs2.showIconLabelsOnHomeScreen.set(true)
                    prefs2.homeIconSizeFactor.set(1.12f)
                    prefs2.homeIconLabelSizeFactor.set(0.92f)
                    prefs2.folderPreviewBackgroundOpacity.set(0f)
                    prefs2.folderBackgroundOpacity.set(0f)
                }
            }
            ClickablePreference(
                label = stringResource(R.string.ios_icon_pack_hint),
                subtitle = stringResource(R.string.ios_icon_pack_hint_desc),
            ) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=iOS%20icon%20pack&c=apps"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/search?q=iOS%20icon%20pack&c=apps"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            val iconStyle = prefs.iosIconStyle.getAdapter()
            ListPreference(
                adapter = iconStyle,
                entries = listOf(
                    ListPreferenceEntry("default") { stringResource(R.string.ios_icon_style_default) },
                    ListPreferenceEntry("dark") { stringResource(R.string.ios_icon_style_dark) },
                    ListPreferenceEntry("clear") { stringResource(R.string.ios_icon_style_clear) },
                    ListPreferenceEntry("tinted") { stringResource(R.string.ios_icon_style_tinted) },
                ),
                label = stringResource(R.string.ios_icon_style),
            )
            var iconStyleInitial by remember { mutableStateOf(true) }
            LaunchedEffect(iconStyle.state.value) {
                // Only react to the user changing the style, not to the screen opening.
                if (iconStyleInitial) {
                    iconStyleInitial = false
                    return@LaunchedEffect
                }
                val themed = iconStyle.state.value != "default"
                if (prefs.themedIcons.get() != themed) prefs.themedIcons.set(themed)
                if (prefs.drawerThemedIcons.get() != themed) prefs.drawerThemedIcons.set(themed)
            }
            SwitchPreference(
                adapter = prefs.iosSearchPill.getAdapter(),
                label = stringResource(R.string.ios_search_pill_label),
                description = stringResource(R.string.ios_search_pill_desc),
            )
            SwitchPreference(
                adapter = prefs.iosJiggle.getAdapter(),
                label = stringResource(R.string.ios_jiggle),
            )
        }

        val glassEnabled = prefs.iosGlassEnabled.getAdapter()
        MainSwitchPreference(
            adapter = glassEnabled,
            label = stringResource(R.string.ios_glass_enabled),
            description = stringResource(R.string.ios_glass_enabled_desc),
        ) {
            PreferenceGroup(heading = stringResource(R.string.ios_group_glass)) {
                if (hasWallpaperAccess) {
                    ClickablePreference(
                        label = stringResource(R.string.ios_wallpaper_access_granted),
                        onClick = { GlassBackdrop.refresh(context) },
                    )
                } else {
                    ClickablePreference(
                        label = stringResource(R.string.ios_wallpaper_access),
                        subtitle = stringResource(R.string.ios_wallpaper_access_desc),
                        onClick = { showPermissionDialog = true },
                    )
                }
                ListPreference(
                    adapter = prefs.iosGlassTintMode.getAdapter(),
                    entries = listOf(
                        ListPreferenceEntry("auto") { stringResource(R.string.ios_glass_tint_auto) },
                        ListPreferenceEntry("light") { stringResource(R.string.ios_glass_tint_light) },
                        ListPreferenceEntry("dark") { stringResource(R.string.ios_glass_tint_dark) },
                        ListPreferenceEntry("clear") { stringResource(R.string.ios_glass_tint_clear) },
                    ),
                    label = stringResource(R.string.ios_glass_tint_mode),
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_blur),
                    adapter = prefs.iosGlassBlur.getAdapter(),
                    valueRange = 0..100,
                    step = 1,
                    showAsPercentage = false,
                    showUnit = "%",
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_tint_opacity),
                    adapter = prefs.iosGlassTintOpacity.getAdapter(),
                    valueRange = 0f..0.8f,
                    step = 0.01f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_refraction),
                    adapter = prefs.iosGlassRefraction.getAdapter(),
                    valueRange = 0f..1f,
                    step = 0.01f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_highlight),
                    adapter = prefs.iosGlassHighlight.getAdapter(),
                    valueRange = 0f..1f,
                    step = 0.01f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_dispersion),
                    adapter = prefs.iosGlassDispersion.getAdapter(),
                    valueRange = 0f..1f,
                    step = 0.01f,
                    showAsPercentage = true,
                )
                SliderPreference(
                    label = stringResource(R.string.ios_glass_saturation),
                    adapter = prefs.iosGlassSaturation.getAdapter(),
                    valueRange = 0f..2.5f,
                    step = 0.05f,
                    showAsPercentage = true,
                )
                SwitchPreference(
                    adapter = prefs.iosGlassMotion.getAdapter(),
                    label = stringResource(R.string.ios_glass_motion),
                )
            }

            PreferenceGroup(heading = stringResource(R.string.ios_group_surfaces)) {
                SwitchPreference(
                    adapter = prefs.iosPopupGlass.getAdapter(),
                    label = stringResource(R.string.ios_popup_glass),
                )
                SwitchPreference(
                    adapter = prefs.iosDrawerGlass.getAdapter(),
                    label = stringResource(R.string.ios_drawer_glass),
                )
            }

            val dockGlass = prefs.iosDockGlass.getAdapter()
            PreferenceGroup(heading = stringResource(R.string.ios_group_dock)) {
                SwitchPreference(
                    adapter = dockGlass,
                    label = stringResource(R.string.ios_dock_glass),
                )
                ExpandAndShrink(visible = dockGlass.state.value) {
                    SliderPreference(
                        label = stringResource(R.string.ios_dock_radius),
                        adapter = prefs.iosDockRadius.getAdapter(),
                        valueRange = 0f..60f,
                        step = 1f,
                        showUnit = "dp",
                    )
                }
                ExpandAndShrink(visible = dockGlass.state.value) {
                    SliderPreference(
                        label = stringResource(R.string.ios_dock_margin),
                        adapter = prefs.iosDockMargin.getAdapter(),
                        valueRange = 0f..48f,
                        step = 1f,
                        showUnit = "dp",
                    )
                }
                ExpandAndShrink(visible = dockGlass.state.value) {
                    SliderPreference(
                        label = stringResource(R.string.ios_dock_padding),
                        adapter = prefs.iosDockPadding.getAdapter(),
                        valueRange = 0f..32f,
                        step = 1f,
                        showUnit = "dp",
                    )
                }
            }

            PreferenceGroup(heading = stringResource(R.string.ios_group_folders)) {
                SwitchPreference(
                    adapter = prefs.iosFolderGlass.getAdapter(),
                    label = stringResource(R.string.ios_folder_glass),
                )
                SwitchPreference(
                    adapter = prefs.iosFolderGrid.getAdapter(),
                    label = stringResource(R.string.ios_folder_grid),
                )
                SliderPreference(
                    label = stringResource(R.string.ios_folder_radius),
                    adapter = prefs.iosFolderRadius.getAdapter(),
                    valueRange = 8f..60f,
                    step = 1f,
                    showUnit = "dp",
                )
            }
        }

        if (showPermissionDialog) {
            WallpaperAccessPermissionDialog(
                managedFilesChecked = allFilesAccessState != FileAccessState.Denied,
                onDismiss = { showPermissionDialog = false },
                onPermissionRequest = { fileAccessManager.refresh() },
            )
        }
        LifecycleResumeEffect(Unit) {
            showPermissionDialog = false
            fileAccessManager.refresh()
            GlassBackdrop.refresh(context)
            onPauseOrDispose { }
        }
    }
}
