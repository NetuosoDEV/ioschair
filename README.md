# IOSChair

**IOSChair** is a fork of [Lawnchair 16](https://github.com/LawnchairLauncher/lawnchair) that turns it
into an iOS 26–style home screen with Apple's **Liquid Glass** look, while keeping every Lawnchair
setting available for fine tuning.

> IOSChair is an independent open-source project. It is not affiliated with or endorsed by Apple.
> It ships no Apple artwork, icons or fonts — use any iOS-style icon pack from the Play Store.

## What changes compared to Lawnchair

| Area | iOS 26 behaviour reproduced |
| --- | --- |
| **Liquid Glass material** | Real-time AGSL shader (Android 13+): blurred wallpaper backdrop, edge **lensing/refraction**, chromatic dispersion, vibrancy boost, adaptive light/dark tint and a **specular rim**. Android 8–12 get a composited fallback. |
| **Motion glints** | The light source follows device tilt (gravity sensor), so highlights slide along glass edges like on iPhone. Only active while the home screen is visible. |
| **Dock** | Floating glass dock with rounded corners, 4 icons, no labels, no search bar under it. Radius / margins / padding are adjustable. |
| **Search pill** | The glass **“Search”** pill above the dock opens app search; while you swipe pages it morphs into page dots. |
| **Folders** | Glass folder icons with an iOS **3×3 mini-grid** preview; the opened folder is a large glass panel that morphs out of the icon. |
| **Context menus** | Long-press menus are glass cards without the Material arrow. |
| **App Library** | The app drawer sits on a frosted copy of your wallpaper. |
| **Jiggle mode** | Icons and widgets wobble while you drag / edit the home screen. |
| **Icons** | Continuous-corner “Cupertino” mask by default, bigger icons, iOS style icon appearances: **Default / Dark / Clear (glass) / Tinted**. Red notification badges with counts. |
| **Typography** | [Inter](https://rsms.me/inter/) (SIL OFL) bundled as the closest free match for SF Pro. |
| **Layout** | 4×6 grid, 4-icon dock, static wallpaper, no At-a-Glance widget, one-tap *“Apply iOS 26 layout preset”*. |

All of it lives in **Settings → IOSChair · Liquid Glass**: frosting, tint, appearance
(Auto / Light / Dark / Clear), refraction, highlights, dispersion, vibrancy, tilt glints, per-surface
toggles, dock geometry, folder style and more.

### Real frosted glass needs wallpaper access

Android does not let third-party launchers blur what is behind them, so IOSChair renders the glass
from a blurred copy of your wallpaper. Grant wallpaper access from the IOSChair settings page
(button *“Allow wallpaper access”*). Without it the glass is tinted from the wallpaper’s colours only.

## Building

Requirements: JDK 21, Android SDK platform 37 (`platforms;android-37.2`) and build-tools 37.0.0.

```bash
git clone --recursive https://github.com/NetuosoDEV/ioschair.git
cd ioschair
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleLawnWithQuickstepGithubDebug
```

The APK ends up in `build/outputs/apk/lawnWithQuickstepGithub/debug/`. The package name is
`app.ioschair(.debug)`, so it installs next to Lawnchair.

Code map of the fork:

- `lawnchair/src/app/lawnchair/ioschair/glass/` — Liquid Glass engine (`GlassBackdrop`, `GlassPainter` + AGSL shader, `GlassMotion`, `GlassDrawable`, `GlassUi`).
- `lawnchair/src/app/lawnchair/ioschair/` — preferences facade, jiggle mode, actions.
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/IosChairPreferences.kt` — settings screen.
- Hooks in Launcher3: `Hotseat`, `PageIndicatorDots`, `folder/Folder`, `folder/PreviewBackground`, `folder/ClippedFolderIconLayoutRule`, `popup/ArrowPopup`, `LawnchairScrimView`.

## Credits & licence

Based on Lawnchair (GPL-3.0) and AOSP Launcher3 (Apache-2.0); see [`docs/LAWNCHAIR_README.md`](docs/LAWNCHAIR_README.md)
and `LICENSE.txt`. Inter font © The Inter Project Authors, SIL Open Font License 1.1
([`docs/licenses/Inter-OFL.txt`](docs/licenses/Inter-OFL.txt)).
