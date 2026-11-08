# AGENTS.md

FischDroid is a single-module Android app (`:app`) that automates a fishing minigame:
MediaProjection captures frames, `Detector` locates the fish/bar pixels, an accessibility overlay
draws them, and `TapService` dispatches gestures. There is no README or CI; Gradle is the source of
truth.

Package/application ID: `io.github.freew_w.fischdroid`. UI is classic Views/XML, not Compose.

Source layout: the root package holds `MainActivity`; `services/` holds `ScreenCaptureService` and
`TapService`; `minigame/` holds `Detector`, `ControlLoop`, and `Controller`; `overlay/`
holds `OverlayManager`/`OverlayView`; `shake/` holds `Detector`/`Clicker`. Note that both
`minigame/` and `shake/` define a `Detector`, so refer to them by package.

## Build and verify

- `./gradlew :app:assembleDebug` — debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:testDebugUnitTest` — host unit tests.
- One test class:
  `./gradlew :app:testDebugUnitTest --tests "io.github.freew_w.fischdroid.DetectorTest"`.
- `./gradlew :app:lintDebug` — Android lint; currently passes.
- Device tasks: `./gradlew :app:installDebug`, `./gradlew :app:connectedDebugAndroidTest` (needs a
  device/emulator).

The build requires Android SDK 37 (`compileSdk`/`targetSdk`) and the JDK 25 toolchain selected by
`gradle/gradle-daemon-jvm.properties`; `local.properties` must point `sdk.dir` at the SDK. Java
source/target is 11.

Kotlin is compiled by AGP 9.4.1's built-in Kotlin support — there is no
`org.jetbrains.kotlin.android` plugin. `kotlinx-coroutines` is only present transitively via
AndroidX, not declared in `app/build.gradle.kts`.

## Runtime wiring and gotchas

- `MainActivity` requests the accessibility service, starts/stops `ScreenCaptureService`, and
  performs a hardcoded test tap at `(518, 910)` after 3 seconds. On Android 13+ it requests
  `POST_NOTIFICATIONS` before asking for MediaProjection consent; capture proceeds even if the
  permission is denied. Do not assume an automatic detection→tap loop exists for the minigame; the
  only automatic tapping is the shake button described below.
- `shake/Detector.kt` finds the post-cast "SHAKE" button with no per-device calibration: five
  equal-height white glyphs in a roughly 4:1 row, verified against the bright ring and dark disk
  around them. Its thresholds are all ratios of the frame/glyph size, so resolution, UI scale and
  background do not matter. `shake/Clicker.kt` taps the detected center at most every 100 ms while
  the button is visible, which also stops on its own once the minigame starts. The in-game jump
  button is also a ringed dark disk, but it has an arrow instead of the glyphs and is rejected.
  Reference crops live in the gitignored `Pictures/Shakes/`; the host tests in
  `app/src/test/java/io/github/freew_w/fischdroid/DetectorTest.kt` use copies in
  `app/src/test/resources/shake/`. While detected, `overlay/OverlayView.kt` draws a cyan ring and
  center dot.
- `ScreenCaptureService` must be started with the MediaProjection result token returned to
  `MainActivity`; it cannot be launched usefully from adb/CLI alone. It runs as a media-projection
  foreground service and captures through `ImageReader`. Sending an intent with the extra
  `ACTION=STOP` stops an active capture and removes the notification.
- The accessibility service (`TapService`) must be enabled manually in system settings before the
  overlay or gestures work. `overlay/OverlayManager.kt` uses `TYPE_ACCESSIBILITY_OVERLAY`; there is
  no `SYSTEM_ALERT_WINDOW` permission.
- `minigame/Detector.kt` scans fixed screen rows for the fish line and the controlled bar, and its
  scan coordinates, colors, and expected bar width are calibrated to specific screen geometry.
  Recalibrate its constants and the tap coordinates in `minigame/Controller.kt` for a different
  device/emulator.
- `minigame/Controller.kt` is called from `ScreenCaptureService` after `shake/Clicker`. It ignores
  portrait frames and drives `minigame/ControlLoop.kt`: the bar is a double integrator (constant
  acceleration right while held, left while released, inelastic track bounds). The loop is a port
  of the root `main.js` controller: an alpha-beta observer estimates bar/fish position and
  velocity through the last action, then every candidate pulse (hold 30–500 ms, release 30–60 ms)
  is simulated together with a short terminal braking phase, and the cheapest outcome (squared
  distance outside the deadband, velocity error, wall contact/bounce) is executed. `accelHold` and
  `accelRelease` are adapted from observed velocity changes; there is a 1.4 s warm-up before the
  first action. Press latency and loop overhead are not modelled. `ControlLoopTest` runs the loop
  against a simulated bar.
- Keep R8 keep rules in `app/src/main/keepRules/*.keep` (AGP 9 location), not a
  `proguard-rules.pro`. Release builds enable R8 optimization scoped to `androidx.**`, `kotlin.**`,
  and `kotlinx.**`.
- `ScreenCaptureService.CaptureSession` is the single source of truth for active capture resources.
  Preserve its null-field/re-entrancy handling when editing start, reconfigure, or release paths.

## Debugging captures and tests

- `ScreenCaptureService` has no built-in frame dump. Add temporary debug saving when needed and
  remove it before committing.
- Repo-root `Pictures/` is gitignored and holds reference captures (portrait and landscape, several
  resolutions) useful for calibrating `Detector`. These are development references, not runtime
  output.
- `DetectorTest` is a real host test over the `app/src/test/resources/shake/` crops.
  `ExampleUnitTest`/`ExampleInstrumentedTest` are placeholders; capture, minigame detection,
  overlay, and gestures still need device verification.
