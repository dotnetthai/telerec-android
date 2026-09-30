# TeleRec Android

Android phone app for TeleRec: a Garmin watch is the remote start/stop button and photo shutter, and the phone records.
It talks to the unchanged watch app in [telerec-garmin](https://github.com/dotnetthai/telerec-garmin) with the same wire protocol as
the [iOS app](https://github.com/dotnetthai/telerec-ios).

Status: first implementation, not yet tested on a real phone. See [PLAN.md](PLAN.md) for the design and what still needs a device.

## Supported phones

Video records through one real lens at its native focal length, never through a zoomed or cropped image. Which lenses
qualify depends on the phone, so multi-lens video is only offered on these flagships:

| Phone | `Build.MODEL` |
|---|---|
| Pixel 8 Pro, 9 Pro, 9 Pro XL, 10 Pro, 10 Pro XL | exact name |
| Galaxy S23 Ultra, S24 Ultra, S25 Ultra | `SM-S918*`, `SM-S928*`, `SM-S938*` |

On those phones, a lens is offered only if Camera2 can stream video from it directly, either through its own camera id
or as a physical camera of the logical camera. If a lens fails to configure, TeleRec falls back to 1x and says so.
Where there are two telephotos (Galaxy Ultra), the longer one is offered, as on iOS. Other Android 11+ phones work
too, but video uses the main lens only. Photo mode zooms freely on every phone.

The watch menu's **Lens info** shows what the probe found on the phone (camera ids, fields of view, labels, and
presets per lens). Use it when checking a new phone.

## Setup

1. Install Garmin Connect and pair the watch. Install the TeleRec watch app from the Connect IQ Store.
2. Open TeleRec and allow the camera, microphone and notifications.
3. TeleRec starts a foreground service with a notification. From then on the watch can start and stop recordings
   and take photos even with the phone locked. Tap **Stop** in the notification to release the camera. A recording
   in progress is finished and saved first.

Recordings go to `Movies/TeleRec`, and photos to `Pictures/TeleRec`.

## Build

JDK 17 and the Android SDK (platform 35) are needed. `local.properties` points at the SDK (`sdk.dir=…`).

```sh
./gradlew :core:test          # state machine, protocol, lens rules: no phone needed
./gradlew :app:assembleDebug  # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug
```

### Testing with the Connect IQ simulator (no watch)

Build with `-PciqTethered=true` so the SDK connects to the simulator over adb instead of through Garmin Connect,
then forward the simulator port to the phone: `adb forward tcp:7381 tcp:7381`. In the simulator, run the watch app
and use its *Connection* menu. The exact steps still need to be verified (PLAN.md, phase 2).

## How it works

The protocol is the one in the watch repo's README and in `core/…/WatchProtocol.kt`:

- watch → phone `{cmd: toggle|start|stop|status}`, `{cmd: lens, lens: "1x"}`, `{cmd: mode, mode: photo}`, `{cmd: shoot, id: Int}`
- phone → watch `{state: idle|recording|saving|error, elapsed: Int, lens: "5x", lenses: [...], mode, shot?, msg?}`

When the camera isn't available, the phone replies with `error` and "Open TeleRec on phone".
