# CLAUDE.md

TeleRec for Android. No code yet; PLAN.md is the plan. The watch app ([telerec-garmin](https://github.com/dotnetthai/telerec-garmin)) is reused unchanged, and `core` is a port of the iOS state machine in [telerec-ios](https://github.com/dotnetthai/telerec-ios) (`TeleRec/Core/`). The wire protocol must match the iOS app and the README in the watch repo exactly, and the Connect IQ app UUID must match the watch's `manifest.xml`.

## Conventions

- Before making a design choice the spec or the user hasn't settled (see "Open decisions" in PLAN.md), ask the user rather than deciding silently.
- The GitHub remote is private. Push with the `gh` account `dotnetthai`. The work account doesn't have access.
