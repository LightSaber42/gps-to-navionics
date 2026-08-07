# Repository Guidelines

## Project Structure & Module Organization

This repository contains two native implementations of the same GPS-to-NMEA bridge. The iOS app lives in `NavionicsGPSBridge/`: SwiftUI entry points and state are under `App/`, TCP networking under `Network/`, and sentence models/encoding under `NMEA/`. XCTest coverage is in `NavionicsGPSBridgeTests/`, while project and scheme settings are in `NavionicsGPSBridge.xcodeproj/`. The Android app is rooted at `android/`; Java sources are in `app/src/main/java/com/jaco/navionicsgpsbridge/` and manifests/resources in `app/src/main/`. Treat `app/build/` and Xcode derived data as generated output.

## Build, Test, and Development Commands

- `open NavionicsGPSBridge.xcodeproj` opens the iOS project for device or simulator development.
- `xcodebuild -project NavionicsGPSBridge.xcodeproj -scheme NavionicsGPSBridge -destination 'generic/platform=iOS Simulator' build` performs a command-line iOS build.
- `xcodebuild -project NavionicsGPSBridge.xcodeproj -scheme NavionicsGPSBridge -destination 'platform=iOS Simulator,name=iPhone 16' test` runs XCTest; substitute an installed simulator when needed.
- `cd android && ./build-debug.sh` builds and verifies a signed debug APK using the SDK path in `local.properties`. The result is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Coding Style & Naming Conventions

Use four-space indentation in Swift and Java. Follow existing platform conventions: `UpperCamelCase` for types, `lowerCamelCase` for methods and properties, and descriptive file names matching the primary type. Keep platform-specific UI and lifecycle code separate from NMEA encoding and TCP transport. Preserve NMEA formatting exactly, including POSIX/US locale use, checksums, UTC timestamps, and `\r\n` terminators. No formatter or linter is configured, so match the surrounding source and keep diffs focused.

## Testing Guidelines

iOS tests use XCTest and follow `testBehaviorUnderCondition()` naming. Add deterministic encoder tests to `NMEAEncoderTests.swift`, especially for coordinates, validity, speed/course, dates, and checksums. Android has no automated suite yet; verify Android changes by building the APK and exercising start/stop, permissions, background streaming, reconnects, and stale-fix behavior. Mirror protocol changes across both implementations and compare emitted sentences.

## Commit & Pull Request Guidelines

Git history is not included in this checkout. Use short, imperative commit subjects such as `Handle stale Android fixes`, with one logical change per commit. Pull requests should identify affected platforms, explain user-visible or protocol changes, list build/test results, and link relevant issues. Include screenshots for UI changes and sample NMEA output for encoder changes. Never commit production signing material or machine-specific SDK paths; keep `android/local.properties` local.
