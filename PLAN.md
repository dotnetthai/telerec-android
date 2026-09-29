# TeleRec for Android: project plan

Status: plan only, no Android code yet. Written 28 September 2026.

Goal: an Android phone app that does what the iOS app does (video with full-quality zoom steps, photo
mode with free zoom), driven by the **same, unchanged watch app**. The watch doesn't know or care which
phone answers it.

## What we reuse, and what we write

| Part | Reuse? | Notes |
|---|---|---|
| Watch app ([telerec-garmin](https://github.com/dotnetthai/telerec-garmin)) | **As is** | Same `.prg`, same Connect IQ Store listing, same app UUID `70cea3b2-1a8a-4cc5-bc23-76c375279e79`. |
| Wire protocol | **As is** | The messages in README "How it works" (`cmd`, `lens`, `mode`, `shoot` ids, `lenses`, `shot`). Android must match it exactly. |
| iOS repo `TeleRec/Core/` (state machine, lens steps, labels) + tests | **Port** line by line to Kotlin | No Apple types in it, so it translates directly. Port the tests with it. |
| iOS repo `TeleRec/Watch/ConnectIQService.swift` | **Rewrite** on Garmin's Android SDK | Simpler than iOS: the SDK lists paired watches itself. |
| iOS repo `TeleRec/Camera/` | **Rewrite** on Camera2 | The hard part (see "Camera"). |
| iOS repo `TeleRec/UI/`, `App/` | **Rewrite** in Jetpack Compose + a foreground service | Small UI. |
| `docs/index.html` (iOS repo) | **Extend** | Add Android to the privacy policy and supported devices. |

## Project layout

The repo root is the Gradle project (the iOS app and watch app live in their own repos):

```
telerec-android/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml          versions in one place
  core/                              pure Kotlin/JVM module: no Android imports allowed
    src/main/kotlin/com/nlmthai/telerec/core/
      RecorderController.kt          ← Core/RecorderController.swift
      WatchProtocol.kt               ← Core/WatchProtocol.swift
      Ports.kt                       ← Core/Ports.swift (CameraControlling, MediaSaving, WatchChannel)
      CaptureSettings.kt             ← Core/CaptureSettings.swift (VideoPreset, Lens, LensOption, CaptureMode)
      LensLabel.kt                   ← Core/LensLabel.swift
    src/test/kotlin/…                ← TeleRecTests/*.swift (Mocks, RecorderControllerTests, LensLabelAndSettingsTests)
  app/                               Android application module
    src/main/AndroidManifest.xml
    src/main/kotlin/com/nlmthai/telerec/
      camera/CameraService.kt        Camera2 session, video + photo
      camera/LensProbe.kt            finds lenses, labels and crop steps on this phone
      camera/MediaStoreSaver.kt      saves to the gallery (replaces PhotoLibrarySaver)
      watch/ConnectIqService.kt      Garmin SDK adapter (replaces ConnectIQService.swift)
      app/CaptureService.kt          foreground service that owns the camera
      app/AppModel.kt                wiring, like App/AppModel.swift
      ui/CameraScreen.kt, ui/SettingsScreen.kt
```

Keeping `core` a plain JVM module is how the iOS ports-and-adapters rule ("RecorderController has no
AVFoundation or Connect IQ imports") gets enforced on Android: the compiler refuses Android types there.

## Libraries and versions

- Kotlin, Gradle Kotlin DSL, JDK 17, Android Studio.
- **Garmin Connect IQ Mobile SDK for Android** `com.garmin.connectiq:ciq-companion-app-sdk:2.4.0@aar`
  (Maven Central; 2.4.0 is the latest release as of March 2026). Garmin's sample is
  <https://github.com/garmin/connectiq-android-sdk> ("Comm Android").
- Jetpack Compose (UI), AndroidX Lifecycle / ViewModel.
- **Camera2** directly, not CameraX (see "Camera" for why).
- kotlinx-coroutines (`StateFlow` replaces Combine's `@Published`), kotlinx-coroutines-test and JUnit for `core` tests.

## Porting `core`

- `RecorderController`: a class confined to the main thread (like `@MainActor`), exposing `state` as a
  `StateFlow<RecorderState>`. Keep the same injected `now` and temp-file factory so the tests port unchanged.
- `awaitingReconfigure`, the shot-id dedupe (`lastShotID`, `savedShotID`), `requestLens`/`requestMode`,
  and `cameraReconfigured()` keep exactly the same rules. They're what make watch retries safe.
- `LensOption.steps`, `photoZoomRange` (5× the longest real lens) and `photoLabels` port as pure functions.
- Tests: port all of `RecorderControllerTests` and `LensLabelAndSettingsTests`. The Swift `settle()`
  polling loops become `runTest { … advanceUntilIdle() }`.
- Exit criteria: `./gradlew :core:test` is green with the same test names as the iOS suite.

## Garmin link (`ConnectIqService.kt`)

API as used in Garmin's sample:

- `ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)`, then
  `initialize(context, true, listener)` with `onSdkReady` / `onInitializeError` / `onSdkShutDown`.
- Watches: `getKnownDevices()` (or `getConnectedDevices()`), and `registerForDeviceEvents(device) { device, status -> }`.
  No "Select watch in Garmin Connect" round trip like iOS: the user just needs the watch paired in Garmin Connect.
- App: `IQApp("70cea3b21a8a4cc5bc2376c375279e79")` (the UUID without dashes, as in `manifest.xml` in the watch repo),
  `getApplicationInfo(...)` for "No app on watch", and `registerForAppEvents(device, app) { device, app, message, status -> }`.
- Send: `sendMessage(device, app, payload) { _, _, status -> }`. Keep the iOS rule of one message in
  flight per device, where a newer reply replaces a queued one.
- `openStore(storeId)` takes the **Connect IQ Store id**, not the app UUID. (The iOS app currently passes
  the UUID. The same fix is needed there.)
- Clean up with `unregisterAllForEvents()` and `shutdown(context)`.
- Received messages arrive as a `List<Any>`. The watch sends one Dictionary, which should be element 0,
  as a `Map`. **Verify** with the simulator before writing the parser, then mirror `WatchCommand(message:)`.
- Phone → watch replies are a `Map<String, Any>` with the same keys as `WatchReply.message`.
- **Check the merged manifest** for Garmin Connect package visibility (`<queries>` for
  `com.garmin.android.apps.connectmobile`). The AAR may add it. Garmin's sample declares `INTERNET`, which is likely
  only for the tethered (simulator) connection; confirm before adding it to the release build.

**End-to-end testing without a real watch:** the SDK also has `IQConnectType.TETHERED`, which talks to
the Connect IQ simulator over adb (the simulator's *adb Connection* menu). That lets the real watch app
in the simulator drive the Android app. iOS can't do this. Set it up in phase 2 and document the
exact adb port-forward steps once verified.

## Camera

The iOS promise is: **video records through one real lens, at native focal length or a full-resolution
crop, never an upscaled zoom.** On Android, how far that promise can be kept depends on the phone.

**Lens discovery (`LensProbe.kt`):**
1. Find back-facing cameras. For a logical multi-camera (`REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA`),
   read its `physicalCameraIds`. Some phones also expose each lens as its own camera id.
2. For each physical lens, work out its magnification against the main lens from
   `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` and `SENSOR_INFO_PHYSICAL_SIZE` (the equivalent of iOS's
   field-of-view fallback). Label it with the ported `LensLabel.format`.
3. Record what each phone offers. The probe screen doubles as the device-testing tool.

**Video mode, in order of preference per phone:**
1. The lens is exposed as its own camera id: open it directly. This keeps the iOS guarantee.
2. The lens is only a physical camera of a logical camera: stream it with `OutputConfiguration.setPhysicalCameraId`
   if the phone supports that for a video-sized stream. This also keeps the guarantee.
3. Neither works: use the logical camera with `CONTROL_ZOOM_RATIO` set to the lens's magnification. Most
   phones switch to that lens, but may use a cropped main lens in low light, which is what iOS avoids.
   **Decision needed:** offer these steps anyway (marked approximate), or show only the main lens on such phones.

**Full-quality crop steps** (iOS's 2x and 8x): investigate ultra-high-resolution sensor support
(`REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR`, API 31+) and in-sensor zoom on
Android 14+. Offer a crop step only where the phone reports it can do it at full resolution, like iOS.
If that can't be detected reliably, **don't offer crop steps on Android** rather than guess.

**Photo mode:** the logical multi-camera with `CONTROL_ZOOM_RATIO` for pinch zoom. This is Android's
default behavior, so it's easier than iOS. Range and labels come from the ported `photoZoomRange`/`photoLabels`.
JPEG or HEIC through an `ImageReader`.

**Why Camera2 and not CameraX:** CameraX is simpler, but it hides the per-lens (physical camera)
controls that video mode depends on. One Camera2 `CameraService` handles both modes, as on iOS.

**Other details to carry over:** 1080p60 / 4K30 / 4K60 presets filtered per lens, stabilization
(`CONTROL_VIDEO_STABILIZATION_MODE`), portrait/landscape setting (`setOrientationHint`, `JPEG_ORIENTATION`),
continuous focus and exposure, no microphone in photo mode, and zoom locked while recording.

**Saving:** write straight into a pending MediaStore entry (`IS_PENDING`) under `Movies/TeleRec` and
`Pictures/TeleRec`. No storage permission is needed on Android 10+. There's no temp file to lose, because
a failed recording is deleted and a finished one is published.

## Lifecycle and background

Android can keep recording with the screen off, which iOS can't. Plan:

- A foreground service (`CaptureService`) owns the camera, with `foregroundServiceType="camera|microphone"`
  and the `FOREGROUND_SERVICE_CAMERA` / `FOREGROUND_SERVICE_MICROPHONE` permissions, plus a persistent
  notification with a Stop action.
- Android only grants camera access to a foreground service started **while the app is on screen**. So
  opening TeleRec once starts the service, and from then on the watch should be able to start and stop
  recordings with the phone locked. **Verify on real phones** (Android 14+ rules).
- **Decision needed:** keep this background behavior (recommended; it removes most "Open TeleRec on
  phone" cases), or match iOS and stop when the app leaves the screen.
- The unavailable message the phone sends the watch is currently "Open TeleRec on iPhone". On Android,
  send "Open TeleRec on phone". The watch shows whatever text it receives, so the watch app needs no change.
- Interruptions: phone calls, another app taking the camera, thermal shutdown (`CameraDevice.StateCallback`
  errors), low storage. Map them to the same `CameraEvent`s as iOS.

## Phases

| # | Phase | Exit criteria | Size |
|---|---|---|---|
| 0 | Decisions + device check | Open decisions below answered. `LensProbe` run on each target phone and results written into this file. | S |
| 1 | Skeleton + `core` port | the project builds; `:core:test` green with the ported tests. | S |
| 2 | Garmin link, fake camera | The watch simulator (tethered) and a real watch get READY / REC / SAVING from the app with a mock camera. | M |
| 3 | Video mode | Preview, record, save to gallery; lens steps per the probe; lens switch from the watch; locked while recording. | **L** |
| 4 | Photo mode | Hold-DOWN switch, shutter with shot-id dedupe, pinch zoom, the pinched zoom shown on the watch. | M |
| 5 | Background + interruptions | Foreground service; watch control with the phone locked; calls/thermal handled. | M |
| 6 | Release | Play Console listing, internal testing track, privacy page + Data safety form updated. | S |

The camera work (phases 3–5) takes most of the time, and it grows with every phone model we promise to support.

## Release (Google Play)

- Google Play Console developer account (one-time fee), then an internal testing track before production.
- **Data safety** form: "No data collected, no data shared", matching the privacy policy.
- Target the API level Google Play requires at submission time. Garmin's sample uses `minSdk 26`, `targetSdk 34`.
- Update `docs/index.html` (iOS repo): Android in the privacy policy (MediaStore instead of Photos add-only, the
  foreground service and its notification, background recording), Android in "Supported devices", and
  Google Play alongside the App Store.
- The watch app needs no new Store listing: one Connect IQ app serves both phones.

## Open decisions

Per the project convention, these are for the user to settle before building:

1. **Package name**: reuse `com.nlmthai.telerec` (recommended, matching the iOS bundle ID) or another.
2. **Minimum Android version**: Android 11 / API 30 (recommended: it's where `CONTROL_ZOOM_RATIO`
   arrived, and scoped-storage saving needs no permission), or lower, down to Garmin's API 26, with more fallback code.
3. **Target phones** to test and guarantee first (e.g. a Pixel Pro and a Galaxy S Ultra). Which Android phones do you own?
4. **Phones without direct lens access**: offer approximate lens steps, or only the main lens (see Camera, video mode 3).
5. **Background recording**: keep recording with the screen off (recommended), or match iOS.
6. **Gallery folder names**: `Movies/TeleRec` and `Pictures/TeleRec` (recommended) or the default camera folders.

## Risks

- **Lens access differs by manufacturer** and even by firmware update. Mitigation: the probe decides per
  phone, and the supported-phone list only includes models we've checked.
- **Foreground-service rules** keep tightening with new Android releases. Mitigation: test on the newest
  Android first, and keep the service's purpose narrow (camera for the watch remote).
- **Garmin Connect must be installed** and running its service. Same as iOS; surface it the way the iOS
  watch indicator does.
- **The protocol is shared across three codebases** (watch, iOS, Android). Any change must land in all
  three, plus the README's "How it works". Consider a shared JSON fixture of example messages that both
  phone test suites check against.
