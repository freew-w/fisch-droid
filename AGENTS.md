# AGENTS.md

FischDroid is a single-module Android app (`:app`) that automates a fishing minigame:
MediaProjection captures frames, `Detector` locates the fish/bar pixels, an accessibility
overlay draws them, and `TapService` dispatches gestures. There is no README or CI; Gradle is
the source of truth.

Package/application ID: `io.github.freew_w.fischdroid`. UI is classic Views/XML, not Compose.

Source layout: the root package holds `MainActivity`, `ScreenCaptureService`, and `TapService`;
`minigame/` holds `Detector`; `overlay/` holds `OverlayManager`/`OverlayView`; `shake/` holds
`Detector`/`Clicker`.

## Build and verify

- `./gradlew :app:assembleDebug` — debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:testDebugUnitTest` — host unit tests.
- One unit test: `./gradlew :app:testDebugUnitTest --tests "io.github.freew_w.fischdroid.ExampleUnitTest"`.
- `./gradlew :app:lintDebug` — Android lint; currently passes.
- Device tasks: `./gradlew :app:installDebug`, `./gradlew :app:connectedDebugAndroidTest` (needs a device/emulator).

The build requires Android SDK 37 (`compileSdk`/`targetSdk`) and the JDK 25 toolchain selected by
`gradle/gradle-daemon-jvm.properties`; `local.properties` must point `sdk.dir` at the SDK. Java
source/target is 11.

Kotlin is compiled by AGP 9.4.1's built-in Kotlin support — there is no
`org.jetbrains.kotlin.android` plugin. `kotlinx-coroutines` is only present transitively via
AndroidX, not declared in `app/build.gradle.kts`.

## Runtime wiring and gotchas

- `MainActivity` only requests the accessibility service, requests MediaProjection consent, and
  performs a hardcoded test tap at `(518, 910)` after 3 seconds. Do not assume an automatic
  detection→tap loop exists for the minigame; the only automatic tapping is the shake button
  described below.
- `shake/ShakeDetector.kt` finds the post-cast "SHAKE" button with no per-device calibration: five
  equal-height white glyphs in a roughly 4:1 row, verified against the bright ring and dark disk
  around them. Its thresholds are all ratios of the frame/glyph size, so resolution, UI scale and
  background do not matter. `shake/ShakeClicker.kt` taps the detected center at most every 120 ms
  while the button is visible, which also stops on its own once the minigame starts. The in-game
  jump button is also a ringed dark disk, but it has an arrow instead of the glyphs and is rejected.
  Reference crops live in `Pictures/Shakes/`; the host tests in
  `app/src/test/java/io/github/freew_w/fischdroid/DetectorTest.kt` use copies in
  `app/src/test/resources/shake/`. While detected, `overlay/OverlayView.kt` draws a cyan ring and
  center dot.
- `ScreenCaptureService` must be started with the MediaProjection result token returned to
  `MainActivity`; it cannot be launched usefully from adb/CLI alone. It runs as a media-projection
  foreground service and captures through `ImageReader`.
- The accessibility service (`TapService`) must be enabled manually in system settings before the
  overlay or gestures work. `overlay/OverlayManager.kt` uses `TYPE_ACCESSIBILITY_OVERLAY`; there
  is no `SYSTEM_ALERT_WINDOW` permission.
- `minigame/Detector.kt` ignores portrait frames (`bitmap.height > bitmap.width` returns), and its
  scan coordinates, colors, and expected bar width are calibrated to specific screen geometry.
  Recalibrate its constants and the tap coordinates in `MainActivity.kt` for a different
  device/emulator.
- Keep R8 keep rules in `app/src/main/keepRules/*.keep` (AGP 9 location), not a
  `proguard-rules.pro`. Release builds enable R8 optimization scoped to `androidx.**`, `kotlin.**`,
  and `kotlinx.**`.
- `ScreenCaptureService.CaptureSession` is the single source of truth for active capture
  resources. Preserve its null-field/re-entrancy handling when editing start, reconfigure, or
  release paths.

## Debugging captures and tests

- Set `DEBUG_SAVE_FRAMES = true` in `ScreenCaptureService.kt` to dump the first frame after each
  stream setup, then retrieve it with:
  `adb pull /sdcard/Android/data/io.github.freew_w.fischdroid/files/Pictures/`
- Repo-root `Pictures/` is gitignored and holds reference captures (portrait and landscape, several
  resolutions) useful for calibrating `Detector`. New runtime captures go to app-specific external
  storage, not this directory.
- `DetectorTest` is a real host test over the `app/src/test/resources/shake/` crops.
  `ExampleUnitTest`/`ExampleInstrumentedTest` are placeholders; capture, minigame detection,
  overlay, and gestures still need device verification.
