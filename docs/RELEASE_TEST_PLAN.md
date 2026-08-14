# Anchor Watch 2.0 release test plan

Record tester, date, exact app build, phone model, OS version, store account type, network path, and result for every run. Use synthetic/non-sensitive coordinates in screenshots and logs. Never record pairing URLs or secrets.

## Test builds and channels

### Apple

1. Use the local StoreKit scheme for deterministic UI/state checks only.
2. Archive the Release configuration with automatic distribution signing and upload to App Store Connect.
3. Confirm build `2.0.0 (4)` completes processing and export-compliance/privacy questions are answered.
4. Add internal TestFlight testers first. Verify StoreKit products in the sandbox environment, then add external testers with concise safety and purchase-testing instructions.
5. Do not use a Debug build for acceptance: Debug deliberately unlocks paid functionality unless `-UseStoreEntitlements` is passed.

### Google Play

1. Build a signed release Android App Bundle with version `2.0.0 (5)` using the protected upload key outside source control.
2. Verify the bundle with `bundletool`, signing certificate, merged manifest, target API 36, and 16 KB page compatibility.
3. Upload to Internal testing and add license testers. Products must be active for the test country/account.
4. Promote the same artifact to Closed testing after internal purchase, alarm, and background checks pass.
5. The shore role must be testable without initiating BillingClient.

## Purchase matrix — both stores

For each case, verify visible status, durable entitlement after relaunch, no secret/token in logs, and that free NMEA Stream remains usable.

- Fresh account: app starts free; Anchor and Remote operational controls are not exposed as already owned.
- Anchor purchase success: localized store price shown; completed purchase unlocks both anchor modes, history, setup, and arming.
- Anchor purchase cancel: returns to paywall without an error entitlement.
- Anchor purchase pending/interrupted: shows pending; remains locked until store reports purchased.
- Anchor purchase billing error/offline: clear, retryable message; no false unlock.
- Restore on a second eligible device signed into the same store account: Anchor restores.
- Reinstall and restore on the same platform/account: entitlement returns; local history/pairing behavior matches the privacy/support copy.
- Different store account: no entitlement leakage.
- Cross-platform: Apple purchase does not restore on Google Play and vice versa.
- Refund/revocation: new Anchor sessions become unavailable after validated revocation; an already-armed session is not deliberately interrupted before disarm.
- Remote monthly success and Remote annual success: each unlocks pairing/server/new remote sessions only when Anchor is also owned.
- Attempt Remote without Anchor: staged checkout requires Anchor first.
- Companion acknowledgement: subscription checkout stays disabled until the separate-Android-phone acknowledgement is selected.
- Remote subscription cancellation: access remains through store-reported expiry.
- Billing retry/grace: behavior follows current verified store entitlement and copy accurately reflects status.
- Remote expiry/revocation: no new remote session; saved pairing remains; local Anchor continues.
- Renew after expiry: stored pairing becomes usable again without unnecessary rotation.
- Offline validation: last-known active Remote access works no longer than seven days; expired/revoked status never gains a new cache window.
- Active-session continuity: local armed and remote active sessions continue through entitlement refresh/cancellation until disarm or genuine transport failure; the next session rechecks entitlement.
- Manage Subscription opens the correct platform page.
- Restore/Refresh Purchases completes without an app account.

## First-arm and alarm matrix

- Deny location, allow approximate only, allow precise When In Use only, and allow background-capable location; verify each readiness state.
- Deny notifications and restore them from Settings.
- Android: DND access absent/present, restrictive Power Saving present/absent, app battery Unrestricted.
- Confirm a short test siren is required and previous volume is restored on Android.
- Revoke a required permission after readiness; completion invalidates before a new arm.
- Revoke a permission while already armed; the app reports degradation without a billing-driven disarm.
- Trigger confirmed drift, GPS loss, silence, return inside radius, and disarm.
- Verify mute/Focus/DND limitations match platform copy; do not claim iOS Critical Alerts without entitlement approval.

## Units and display

- Test System Default, Metres, and Feet on US and metric locale devices.
- Confirm radius, distance, accuracy, warning thresholds, plot label, notification limit, and step hints all change together.
- Confirm Shore Monitor uses its own preference while wire values remain metres.
- Check Shorelined 3–50 m / 1 m step and Free swinging 5–200 m / 5 m step boundaries in both display systems.
- Verify coordinates, NMEA sentences/checksums, geometry, persistence, history, and protocol values are unchanged.

## Transport and paired monitoring

Run iPhone boat → Android shore and Android boat → Android shore over both same LAN and Tailscale/equivalent VPN.

- Authenticated armed state and 10-second heartbeat.
- Connection-loss alarm only after an armed session was observed and 30 seconds elapse.
- Durable alarm replay across reconnect.
- Local-only silence on both phones.
- Automatic reconnect after Wi-Fi/cellular switching.
- Boat battery state and charging flag.
- History catch-up only while shore activity is visible, paged in protocol-sized batches.
- Background and reopen the shore activity with existing history; inspect crash logs.
- Replace pairing; old credential must fail and new credential must work.
- Reboot Android shore after monitoring was explicitly enabled; verify restoration.
- Verify shore service requests no GPS and holds no wake lock.
- Run at least 12 hours on target hardware and record battery drain; target roughly 1% per hour before screen use.

## NMEA Stream

- Free account, no purchases, TCP on iPhone and TCP/UDP on Android.
- Precise valid fix, stale/invalid fix, UTC time/date, coordinates, speed/course, checksum, and CRLF terminators.
- Hotspot with no internet, ordinary Wi-Fi, reconnect, multiple TCP clients, and UDP targets.
- Compatible navigation app connection and non-affiliation wording.

## Release and privacy checks

- iOS archive contains `PrivacyInfo.xcprivacy` and version/build 2.0.0/4.
- Android bundle targets API 36, has version code/name 5/2.0.0, and boot receiver is non-exported.
- Final SDK/dependency inventory matches App Privacy and Data Safety answers.
- No analytics, ads, account, developer relay, production debug entitlement bypass, pairing secret, store token, or signing material in the artifact/logs.
- Privacy, support, and terms URLs are live HTTPS pages and open from the app and both store listings.
- Screenshot set uses release-equivalent entitlements and synthetic coordinates.
- Dynamic Type/text scaling, VoiceOver/TalkBack labels, light/dark appearance, orientation, and contrast are acceptable.

## Staged rollout and stop criteria

1. TestFlight internal and Play Internal: team devices only; stop for any purchase, alarm, background, or data-disclosure mismatch.
2. TestFlight external and Play Closed: required tester period and device diversity; stop for crash-free regression, entitlement leakage, missed alarm, unexpected battery drain, or pairing failure.
3. Production: manual Apple release and the smallest practical Play staged percentage, then increase only after at least 48 hours of clean purchase/crash/support signals at each stage.
4. Halt rollout immediately for false entitlement grants, purchase loops, loss of an already-armed session caused by billing refresh, missed local alarm, pairing-secret exposure, or material privacy mismatch.
5. Roll back server-independent issues by stopping the store rollout and shipping a higher build/version code; there is no backend kill switch or relay to repair behavior remotely.
