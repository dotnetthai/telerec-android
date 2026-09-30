# CLAUDE.md

TeleRec for Android: records video through one physical lens, or takes photos with free zoom, driven by the Garmin watch app in [telerec-garmin](https://github.com/dotnetthai/telerec-garmin) (reused unchanged). `core` is a port of the iOS state machine in [telerec-ios](https://github.com/dotnetthai/telerec-ios) (`TeleRec/Core/`). The wire protocol must match the iOS app and the README in the watch repo exactly, and the Connect IQ app UUID (`TeleRecIDs`) must match the watch's `manifest.xml`. PLAN.md is the design; README.md is the user-facing guide, so keep it in sync when behavior changes.

## Commands

```sh
export JAVA_HOME=$(ls -d ~/Library/Java/jdk-17*/Contents/Home)   # local JDK 17; no Homebrew
./gradlew :core:test
./gradlew :core:test --tests 'com.nlmthai.telerec.core.RecorderControllerTests.testFullCycleIdleRecordingSavingIdle'
./gradlew :app:assembleDebug :app:lintDebug
```

The Android SDK is in `~/Library/Android/sdk` (`local.properties`, not committed).

## Architecture

- `core/` is a plain JVM module, so Android types can't compile there. `RecorderController` is confined to the main thread and depends only on `Ports.kt` (`CameraControlling`, `MediaSaving`, `WatchChannel`). All recording and watch-reply rules live there, with the tests. The tests keep the iOS test names. `LensCatalog` holds the lens rules (classification by field of view, standalone beats physical, narrowest telephoto) and `SupportedPhones` holds the flagship allowlist, both testable without a phone.
- `app/camera/CameraService.kt` owns Camera2 on its own `HandlerThread`. It delivers events to the main thread and keeps the UI state in main-thread StateFlows. Video uses a lens's own camera id, or `OutputConfiguration.setPhysicalCameraId` on the logical camera, and is never zoomed. Photo mode uses the logical camera with `CONTROL_ZOOM_RATIO`. Recording uses a persistent MediaRecorder input surface, so starting doesn't reconfigure the session. The preview `SurfaceTexture` belongs to the service, and the `TextureView` only borrows it, so recording survives the activity going away.
- `app/app/CaptureService.kt` is the `camera|microphone` foreground service. It must be started while the activity is on screen (`MainActivity.onStart`). `AppModel` is process-wide (`TeleRecApp`) and wires everything together, like the iOS `AppModel`.
- `app/watch/ConnectIqService.kt` uses the Garmin Android SDK, with one message in flight per watch (`core` `OneInFlight`). The paired watches come from `getKnownDevices()`, so there's no Garmin Connect selection round trip.
- Recordings are written to app storage, then copied into a pending MediaStore entry by `MediaStoreSaver` (the port kept the iOS temp-file flow). A failed save keeps the file.

## Conventions

- Before making a design choice the spec or the user hasn't settled (see "Open decisions" in PLAN.md), ask the user rather than deciding silently.
- The GitHub remote is private. Push with the `gh` account `dotnetthai`. The work account doesn't have access.
