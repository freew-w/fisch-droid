# AGENTS.md

FischDroid is a single-module Android app (`:app`) that automates a fishing minigame:
MediaProjection captures frames, `FishingPipeline` classifies each frame and runs an explicit
game-state machine, accessibility overlays draw detections, and `TapService` dispatches gestures.
There is no README or CI; Gradle and the source are the source of truth.

Package/application ID: `io.github.freew_w.fischdroid`. UI is classic Views/XML, not Compose.

## Source layout

The root package holds `FischDroidApp`, `MainActivity`, and `Orchestrator`.

- `automation/` — `FishingPipeline` (per-frame state machine), `GameState`, `FrameObservation`,
  and the ports in `Ports.kt` (`GestureSink`, `DetectionHudSink`, `Clock`).
- `detection/` — `MinigameDetector`, `CoinsDetector`, `ShakeDetector`, their interfaces in
  `Analyzers.kt`, `DetectionSpec`, `TrackGeometry`, and the shared result types in `Detections.kt`
  (`Fish`, `Bar`, `ShakeButton`).
- `control/` — `BarController` (PD loop) and `ControlTuning` (gains/switch band).
- `config/` — `Config` (SharedPreferences), `Settings` (immutable per-frame snapshot composed of
  `TrackGeometry`, `ControlTuning`, `CastTarget`, and the fish colour), and `CastTarget`.
- `services/` — `ScreenCaptureService` and `TapService` (implements `GestureSink`, owns
  `OverlayHost`).
- `overlay/` — `OverlayHost`, `DetectionHud`, `FloatingControls`.
- `setup/` — first-run wizard, full-screen pixel picker (`SetupActivity`/`PixelPickerView`), and
  its serializable field models.

Dependency direction: `automation` depends on `detection`/`control`/`config`; `overlay` implements
`automation`'s HUD port; `services` is the Android boundary that wires everything together. Keep it
that way: detectors do not import overlays or services, and the pipeline does not reach for
`TapService`, `Config`, or a singleton overlay directly.

UI uses a fixed Material 3 (Material Components) theme: sea-blue primary, slate secondary, sand
tertiary, with dynamic color explicitly disabled in `values*/themes.xml`. Colors live in
`values/colors.xml` and `values-night/colors.xml`; shared card/input/icon-button styles live in
`values/themes.xml`. Keep new screens on M3 components and theme attributes instead of ad-hoc
colors.

## Build and verify

- `./gradlew :app:assembleDebug` — debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew :app:testDebugUnitTest` — host unit tests.
- One test class:
  `./gradlew :app:testDebugUnitTest --tests "io.github.freew_w.fischdroid.automation.FishingPipelineTest"`.
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
  13+, then MediaProjection consent on launch; accepting starts `ScreenCaptureService`. Its buttons
  open accessibility settings, start/stop capture, and launch `SetupActivity` for the track region
  and fish colour. Capture start is gated on accessibility: `TapService.isEnabled(context)` reads
  the system's enabled-service list (not the pipeline), `btn_toggle_capture` is disabled until it
  is true, `requestNotificationsThenCapture`/`launchScreenCapture` toast
  `capture_requires_accessibility`, and `ScreenCaptureService.onStartCommand` refuses a non-STOP
  start. `ScreenCaptureService.isActive` (companion flag + listener registry) is set when a session
  is created and cleared in `releaseSession`; `MainActivity.renderStatus` (called from `onCreate`,
  `onStart` via the listener, and `onResume`) tints `card_hero` red (`md_error_container`) for
  "Accessibility needed", sand (`md_tertiary_container`) for "Capture ready to start", and sea-blue
  (`md_primary_container`) for "Ready to fish", swapping the badge icon to `ic_android`/`ic_play`/
  `ic_fish`. The same pass calls `renderCaptureButton`: one button serves both actions, reading
  "Start capture" (the style's own stateful filled tints, captured in `onCreate` so the gated state
  still grays out, `ic_play`) when idle and "Stop capture" (tonal `md_secondary_container`,
  `ic_stop`) while `isActive`, so only the available action is ever shown.
- `TapService.onServiceConnected` creates the `OverlayHost`, calls `Orchestrator.attach(this,
  overlays.hud)`, and starts the overlays. `Orchestrator` is the process-wide composition root: it
  builds a `FishingPipeline` with the real detectors/controller and `Config::snapshot`, and exposes
  `processFrame`/`toggle`/`isRunning`. `onUnbind` calls `Orchestrator.detach()` and hides overlays.
  Before the accessibility service attaches, `Orchestrator.processFrame` is a no-op; that guard is
  defensive only, because capture cannot be started until the service is enabled.
- `ScreenCaptureService` feeds frames to `Orchestrator.processFrame(bitmap, frameWidth,
  frameHeight)` from its worker coroutine. The pipeline may run detectors on that worker thread;
  `DetectionHud` and `TapService` marshal their own work to the main thread.
- `FishingPipeline` is the only place game-state decisions live. Its states are `IDLE`,
  `SEARCHING`, `SHAKING`, and `MINIGAME`:
  - `processFrame` first asks `CoinsDetector.isVisible`. The cream "C$" indicator means the HUD is
    visible and therefore the game is **not** in the minigame.
  - Not in the minigame: sample `ShakeDetector` at most every 100 ms. A detected button puts the
    pipeline in `SHAKING` and taps its centre for 40 ms, at most once per 100 ms. Otherwise it is
    `SEARCHING` and presses `CastTarget` for 500 ms at most once per second. A shake detection also
    delays the next cast, preserving the old cadence.
  - In the minigame: `MinigameDetector` finds the fish/bar and `BarController` returns the hold
    command; the command goes through `GestureSink.setHolding`.
  - `IDLE` (stopped or portrait frame) releases the hold and clears the HUD. Leaving the minigame
    resets the detector, controller, and shake scan cache.
  - `step(FrameObservation, Settings, nowMs)` is the public, Android-free decision seam. It is what
    `FishingPipelineTest` drives; `processFrame` only adds detection. Add a new mode by adding a
    `GameState` and a branch in `step`, not by widening detector types.
- `Settings`/`Config.snapshot()` is read once per frame, so edits made in the main screen or setup
  screens apply live (including `fishColor`), without giving the pipeline a `SharedPreferences`
  dependency. Add a new tunable to `Settings` and `Config.snapshot()`.
- `CoinsDetector` is calibration-free: it masks the cream `#FFFDE4` fill (+/-12 per channel), scans
  only the bottom-right ROI (`x, y >= 0.85` of the frame), keeps components 1.8-5.5 % of frame
  height with glyph aspect 0.30-1.30, then takes the rightmost `C`/`$` pair (combined aspect
  0.85-1.60, height ratio <= 1.45, right edge within 0.8 glyph heights) whose centre is at
  `x >= 0.93 w`, `y >= 0.88 h`. Color and ratio tunables are the constants in its companion object;
  inputs smaller than 320x240 are treated as C$ crops. Pass the real frame width/height (not
  `bitmap.width`, which may include row-stride padding). While a full-screen overlay hides "C$",
  the pipeline reads "in minigame", which is accepted.
- `ShakeDetector` is calibration-free: it looks for five equal-height white glyphs in a roughly
  4:1 row inside a bright ring and dark disk, rejecting the jump button's arrow. It returns null
  for portrait frames; the overlay draws a cyan ring. `find(IntArray, width, height)` remains a
  pure-JVM entry point for tests.
- `MinigameDetector` is stateful: the previous fish/bar positions narrow the next scan range and
  break ties between candidate bars. It samples five rows and takes medians. `reset()` is required
  when stopping or leaving the minigame.
- `BarController` is a raw PD controller:
  `u = positionGain * (fishX - barX) - velocityGain * (barV - fishV)`. Defaults are 4.0 and 3.2;
  it holds when `u > switchBand` (default 200) or when already holding and `u > -switchBand`, with
  a 3-sample warm-up and `reset()` on session/minigame transitions. There is no bar smoothing,
  motion model, or prediction.
- `TapService.setHolding` holds via one continued accessibility stroke in 20 ms segments,
  alternating the endpoint by 1 px because a zero-motion continuation produces no event and is
  reported as cancelled; release sends a final 1 ms segment with `willContinue = false`. A fresh
  stroke always starts its path on the hold point because the path start is where `ACTION_DOWN`
  lands. `setHolding`/`press` are called from the pipeline's worker thread, so requests and all hold
  state are marshalled to the main thread. `press` is the finite-tap API used by the pipeline.
- A continuation must only be dispatched after the previous segment's result callback, never while
  a segment is still in flight. `MotionEventInjector` records the touch point when the system
  processes the gesture; an earlier continuation is rejected and the system then removes the still
  queued `ACTION_DOWN`, so the game receives no touch at all. That made short holds vanish
  completely; see `holdCallback`/`gesture` in `TapService`.
- Detection and gesture coordinates are calibrated to a specific landscape screen. Track geometry,
  rod control, control gains, cast/hold point, and fish colour are persisted by `config/Config.kt`
  and edited on the main screen or in `setup/SetupActivity`. Recalibrate the `Config` defaults for
  another device; the C$ and shake detectors are calibration-free and should only need their
  constants adjusted. The HUD's Y position is derived from `Config.trackY`/`trackH` minus 150 px.
- `OverlayHost` owns both overlays and gives them one `show()`/`hide()` lifecycle. `DetectionHud`
  is a class implementing `DetectionHudSink`; it coalesces identical updates and posts view
  mutations to the main thread. `FloatingControls` is a class that takes `isRunning`/`onToggle`
  callbacks, so it has no dependency on the pipeline or `Orchestrator`. Both use
  `TYPE_ACCESSIBILITY_OVERLAY`; there is no `SYSTEM_ALERT_WINDOW` permission. The start/stop bubble
  is gated on readiness: `OverlayHost.setControlsVisible` only attaches it while
  `ScreenCaptureService.isActive`, and `TapService` drives that from
  `ScreenCaptureService.addStateListener` (registered in `onServiceConnected`, removed in `onUnbind`
  and `onDestroy`). A hidden bubble is not an overlay at all, so it cannot be dragged or tapped
  until capture starts; the main screen's hero shows the same readiness.
- The accessibility service (`TapService`) must be enabled manually before the overlay or gestures
  work.
- Keep R8 keep rules in `app/src/main/keepRules/*.keep` (AGP 9 location), not a
  `proguard-rules.pro`. Release builds enable R8 optimization scoped to `androidx.**`, `kotlin.**`,
  and `kotlinx.**`.
- `ScreenCaptureService.CaptureSession` is the single source of truth for active capture resources.
  Preserve its null-field/re-entrancy handling when editing start, reconfigure, or release paths.

## Debugging captures and tests

- `ScreenCaptureService` has no built-in frame dump. Add temporary debug saving when needed and
  remove it before committing.
- There are currently no committed detection fixtures under `app/src/test/resources/`. Put new
  frame fixtures there if needed; repo-root `Pictures/` is gitignored and may be absent, and is
  not runtime output.
- Current host tests: `ExampleUnitTest`, `control/BarControllerTest`, and
  `automation/FishingPipelineTest`; `ExampleInstrumentedTest` is the only instrumented test.
  `FishingPipelineTest` drives `step()` with fake `FishBarAnalyzer`/`CoinsAnalyzer`/`ShakeAnalyzer`,
  fake `GestureSink`/`DetectionHudSink`, and a deterministic clock, so no `Bitmap` or device is
  needed. `BarControllerTest` exercises warm-up, hold/release, and reset.
- `ShakeDetector.find(IntArray, width, height)` is pure JVM and testable without Android.
  `MinigameDetector` and `CoinsDetector` take `Bitmap`/`ColorUtils` and need a device or Robolectric
  for full-frame tests. `RegionSelection.between` is plain Kotlin. Capture, overlay, gesture and
  setup-widget behavior still need device verification.
