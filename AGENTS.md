# AGENTS.md

FischDroid is a single-module Android app (`:app`) that automates a fishing minigame:
MediaProjection captures frames, `Orchestrator` selects auto-cast/shake/minigame behavior, an
accessibility overlay draws detections, and `TapService` dispatches gestures. There is no README
or CI; Gradle and the source are the source of truth.

Package/application ID: `io.github.freew_w.fischdroid`. UI is classic Views/XML, not Compose.

Source layout: the root package holds `FischDroidApp`, `MainActivity`, and `Orchestrator`; `config/`
holds the persisted `Config`; `services/` holds `ScreenCaptureService`/`TapService`; `minigame/`
holds `Detector`/`Controller`/`ControlLoop`; `overlay/` holds `DetectionOverlay`/`FloatingOverlay`;
`shake/` holds `Detector`/`Clicker`; `setup/` holds the first-run wizard, the full-screen pixel
picker (`SetupActivity`/`PixelPickerView`) and its serializable field models. Both `minigame/` and
`shake/` define a `Detector`, so refer to them by package.

UI uses a fixed Material 3 (Material Components) theme: sea-blue primary, slate secondary, sand
tertiary, with dynamic color explicitly disabled in `values*/themes.xml`. Colors live in
`values/colors.xml` and `values-night/colors.xml`; shared card/input/icon-button styles live in
`values/themes.xml`. Keep new screens on M3 components and theme attributes instead of ad-hoc
colors.

## Build and verify

- `./gradlew :app:assembleDebug` — debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:testDebugUnitTest` — host unit tests.
- One test class:
  `./gradlew :app:testDebugUnitTest --tests "io.github.freew_w.fischdroid.ExampleUnitTest"`.
- `./gradlew :app:lintDebug` — Android lint.
- Device tasks: `./gradlew :app:installDebug`, `./gradlew :app:connectedDebugAndroidTest` (needs a
  device/emulator).

The build requires Android SDK 37 (`compileSdk`/`targetSdk`) and the JDK 25 toolchain selected by
`gradle/gradle-daemon-jvm.properties`; `local.properties` must point `sdk.dir` at the SDK. Java
source/target is 11.

Kotlin is compiled by AGP 9.4.1's built-in Kotlin support — there is no
`org.jetbrains.kotlin.android` plugin. `kotlinx-coroutines` is only present transitively via
AndroidX, not declared in `app/build.gradle.kts`.

## Runtime wiring and gotchas

- `MainActivity` opens `SetupWizardActivity` on first launch while `Config.setupComplete` is false.
  After the wizard (or once setup is already complete) it requests `POST_NOTIFICATIONS` on Android
  13+, then MediaProjection consent on launch; accepting starts `ScreenCaptureService` and
  automation. Its buttons open accessibility settings, start/stop capture, and launch
  `SetupActivity` for the track region and fish color.
- `Orchestrator` is the per-frame pipeline entry point. When `minigame/Detector.notInMinigame`
  matches 3 of 4 hardcoded coin pixels, it taps `(1100, 600)` for 500 ms at most once per second and
  runs `shake/Clicker`; otherwise it runs `minigame/Controller`.
- `shake/Detector` is calibration-free: it looks for five equal-height white glyphs in a roughly
  4:1 row inside a bright ring and dark disk, rejecting the jump button's arrow. `shake/Clicker`
  detects at most every 100 ms and taps the button center for 40 ms while `Orchestrator` routes
  non-minigame frames. It returns null for portrait frames; the overlay draws a cyan ring.
- `minigame/Controller` releases the hold and returns on portrait frames, otherwise detects the
  fish/bar and drives `minigame/ControlLoop`. The loop is a raw PD controller:
  `u = 4.0 * (fishX - barX) - 3.2 * (barV - fishV)`, holding when `u > 50` or when already holding
  and `u > -50`, with a 3-sample warm-up and 500 ms reset. There is no bar smoothing or prediction.
- `TapService.setHolding` holds via one continued accessibility stroke in 20 ms segments, alternating
  the endpoint by 1 px because a zero-motion continuation produces no event and is reported as
  cancelled; release sends a final 1 ms segment with `willContinue = false`. A fresh stroke always
  starts its path on the hold point because the path start is where `ACTION_DOWN` lands.
  `setHolding`/`press` are called from background coroutines, so requests and all hold state are
  marshalled to the main thread. `press` remains the finite-tap API used by `MainActivity`,
  `shake/Clicker`, and `Orchestrator`.
- A continuation must only be dispatched after the previous segment's result callback, never while
  a segment is still in flight. `MotionEventInjector` records the touch point when the system
  processes the gesture; an earlier continuation is rejected and the system then removes the still
  queued `ACTION_DOWN`, so the game receives no touch at all. That made short holds vanish
  completely; see `holdCallback`/`gesture` in `TapService`.
- Detection and gesture coordinates are calibrated to a specific landscape screen. Track geometry,
  rod control and the fish line color are persisted by `config/Config.kt` and edited on the main
  screen or in `setup/SetupActivity`; `FischDroidApp` initializes it at process start, and
  `Orchestrator` rebuilds `Detector.ScanSpec` each frame so edits apply live (including
  `Config.fishColor`). Recalibrate the `Config` defaults, coin pixels in `minigame/Detector.kt`,
  `HOLD_X`/`HOLD_Y` in `minigame/Controller.kt`, and the cast taps in `Orchestrator.kt` for another
  device. The overlay's Y position is derived from `Config.trackY`/`trackH`. Portrait capture is
  only partially handled: `notInMinigame` runs before `Controller`'s orientation guard.
- `setup/SetupActivity` is reusable: it takes a serializable list of `SetupField`s (`REGION` needs
  two confirms, `POINT` and `COLOR` one) and returns a `Bundle` keyed by field id. Its image comes
  from the photo picker; `PixelPickerView` maps view coordinates back to image pixels, so taps only
  move the cursor and zoom/pan never changes the picked pixel. `Config.lastImageUri` remembers the
  last screenshot. The wizard and main screen consume results through `SetupActivity.results()`.
- The accessibility service (`TapService`) must be enabled manually before the overlay or gestures
  work. `overlay/OverlayManager.kt` uses `TYPE_ACCESSIBILITY_OVERLAY`; there is no
  `SYSTEM_ALERT_WINDOW` permission.
- Keep R8 keep rules in `app/src/main/keepRules/*.keep` (AGP 9 location), not a
  `proguard-rules.pro`. Release builds enable R8 optimization scoped to `androidx.**`, `kotlin.**`,
  and `kotlinx.**`.
- `ScreenCaptureService.CaptureSession` is the single source of truth for active capture resources.
  Preserve its null-field/re-entrancy handling when editing start, reconfigure, or release paths.

## Debugging captures and tests

- `ScreenCaptureService` has no built-in frame dump. Add temporary debug saving when needed and
  remove it before committing.
- The only committed detection fixtures are `app/src/test/resources/shake/`. Repo-root `Pictures/`
  is gitignored and may be absent; it is not runtime output.
- Only `ExampleUnitTest`, `ExampleInstrumentedTest` and `setup/RegionSelectionTest` currently exist;
  there are no detector or control-loop tests. `shake/Detector.find(IntArray, width, height)` is pure
  JVM and testable without Android; `minigame/Detector` depends on Android `Bitmap`/`ColorUtils`.
  `RegionSelection.between` is plain Kotlin and unit-tested. Capture, control, overlay, gesture and
  setup-widget behavior still need device verification.
