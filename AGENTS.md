# AGENTS.md

FischDroid is a single-module Android app (`:app`) that automates a fishing minigame:
MediaProjection captures frames, `Detector` locates the fish/bar pixels, an accessibility
overlay draws them, and `TapService` dispatches gestures. There is no README or CI; Gradle is
the source of truth.

Package/application ID: `io.github.freew_w.fischdroid`. UI is classic Views/XML, not Compose.

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
  detection→tap loop exists.
- `ScreenCaptureService` must be started with the MediaProjection result token returned to
  `MainActivity`; it cannot be launched usefully from adb/CLI alone. It runs as a media-projection
  foreground service and captures through `ImageReader`.
- The accessibility service (`TapService`) must be enabled manually in system settings before the
  overlay or gestures work. The overlay uses `TYPE_ACCESSIBILITY_OVERLAY`; there is no
  `SYSTEM_ALERT_WINDOW` permission.
- `Detector` ignores portrait frames (`bitmap.height > bitmap.width` returns), and its scan
  coordinates, colors, and expected bar width are calibrated to specific screen geometry.
  Recalibrate the constants in `Detector.kt` and the tap coordinates in `MainActivity.kt` for a
  different device/emulator.
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
- Unit/instrumentation tests are the generated placeholders and do not exercise capture, detection,
  overlay, or gestures; verify behavior on a device.
