# Repository Guidelines

## Project Structure & Module Organization

This repository contains native iOS and Android implementations of the GPS-to-NMEA bridge and anchor watch. The iOS app lives in `NavionicsGPSBridge/`: SwiftUI entry points, anchor state, durable events, and history are under `App/`; NMEA and anchor-sync networking, pairing, protocol, and cryptography are under `Network/`; sentence models/encoding are under `NMEA/`. XCTest coverage is in `NavionicsGPSBridgeTests/`, while project and scheme settings are in `NavionicsGPSBridge.xcodeproj/`.

The Android app is rooted at `android/`; Java sources are in `app/src/main/java/com/jaco/navionicsgpsbridge/` and manifests/resources are in `app/src/main/`. `BridgeService` is the authoritative location/anchor service, while `ShoreMonitorService` is the read-only Android companion. `AnchorSync*`, `AnchorPairing*`, `AnchorEventStore`, and `AnchorHistoryStore` mirror the cross-platform protocol and persistence responsibilities. Treat `android/app/build/`, `.manual-dependencies/`, debug signing files, and Xcode derived data as generated output.

## Build, Test, and Development Commands

- `open NavionicsGPSBridge.xcodeproj` opens the iOS project for device or simulator development.
- `xcodebuild -project NavionicsGPSBridge.xcodeproj -scheme NavionicsGPSBridge -destination 'generic/platform=iOS Simulator' build` performs a command-line iOS build.
- `xcodebuild -project NavionicsGPSBridge.xcodeproj -scheme NavionicsGPSBridge -destination 'platform=iOS Simulator,name=iPhone 17' test` runs XCTest; substitute any installed simulator when needed.
- `cd android && ./build-debug.sh` builds and verifies a signed debug APK using the SDK path in `local.properties`. The result is `android/app/build/outputs/apk/debug/app-debug.apk`.
- After `build-debug.sh`, compile and run `android/test/AnchorSyncCryptoSelfTest.java` against `android/app/build/manual-debug/classes` with the Android Studio JBR to verify the shared Swift/Java handshake vectors and AES framing.

## Anchor Monitor Architecture Invariants

- The phone left aboard is authoritative. It may be iOS or Android. The shore role is Android-only and read-only: it cannot arm, disarm, move the reference, or change the radius.
- Silencing is local. Acknowledging an alarm on one device must not acknowledge or stop audio on another device.
- The direct transport is TCP port `11124` over a reachable LAN or Tailscale. There is no project-owned internet relay. Same-LAN operation must not require Tailscale.
- Pairing is limited to one active shore monitor. Pairing secrets stay out of logs and source control, are stored in Keychain/Android Keystore, and protect a versioned HMAC handshake plus sequenced, direction-bound AES-GCM frames.
- Send state/heartbeat every 10 seconds and alarm for loss after 30 seconds only after the shore device has observed an armed session. Durable boat alarm events must survive and replay across reconnects.
- Record plot samples every five seconds, but request full history only while the shore activity is visible. Page history in protocol-sized batches; do not turn the low-power heartbeat into a continuous plot stream.
- Keep the Android shore service free of GPS and wake locks. It uses a `connectedDevice` foreground service, notification/DND readiness checks, and explicit boot restoration. Preserve the target of no more than roughly 1% battery per hour before screen use, then verify on physical hardware.
- An armed iOS app relies on background location to remain alive. It cannot relaunch itself after a force-quit or reboot. Apple's Critical Alerts entitlement remains an external requirement for overriding mute/Focus after suspension.

## Coding Style & Naming Conventions

Use four-space indentation in Swift and Java. Follow existing platform conventions: `UpperCamelCase` for types, `lowerCamelCase` for methods and properties, and descriptive file names matching the primary type. Keep platform-specific UI and lifecycle code separate from NMEA encoding and TCP transport. Preserve NMEA formatting exactly, including POSIX/US locale use, checksums, UTC timestamps, and `\r\n` terminators. Mirror anchor-sync wire changes in Swift and Java and increment the protocol version for incompatible changes. Never print pairing URLs or keys in tests, logs, or diagnostic output. No formatter or linter is configured, so match the surrounding source and keep diffs focused.

## Testing Guidelines

iOS tests use XCTest and follow `testBehaviorUnderCondition()` naming. Add deterministic encoder tests to `NMEAEncoderTests.swift`, especially for coordinates, validity, speed/course, dates, and checksums. Keep `AnchorSyncTests` deterministic for cross-platform crypto vectors, encrypted framing, protocol payloads, and listener lifecycle.

Android has no JUnit suite yet, but `AnchorSyncCryptoSelfTest` is a dependency-free JVM compatibility check. Build the APK and run the self-test for protocol changes. On physical devices, exercise start/stop, permissions, background streaming, reconnects, stale fixes, QR/manual pairing, DND readiness, reboot restoration, and battery restrictions.

For paired-monitor changes, verify both phones over the actual target transport (LAN and/or Tailscale): authenticated armed state, 10-second heartbeat, 30-second loss alarm, durable alarm replay, separate local silence, automatic reconnect, battery state, and history catch-up only after reopening the shore UI. Background and reopen the Android activity with existing history and check the crash log. Network writes must never run on Android's main thread. On iOS, retain unauthenticated accepted clients until the first handshake bytes arrive; `testServerRetainsClientUntilHandshakeArrives()` guards this regression.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects such as `Handle stale Android fixes`, with one logical change per commit. Rebase onto the current target branch before pushing when the remote has advanced. Pull requests should identify affected platforms, explain user-visible or protocol changes, list build/test and physical-device results, and link relevant issues. Include screenshots for UI changes and sample NMEA output for encoder changes. Never commit pairing links, production signing material, debug keystores, or machine-specific SDK paths; keep `android/local.properties` local.
