# Anchor Watch 2.0 Publishing Plan

This document is the implementation and submission checklist for repositioning the app from a GPS-to-NMEA utility into an anchor-first product on iOS and Android. Checkboxes are updated as work is completed and verified.

## Product decisions

- [x] Make Anchor Watch the default and primary app experience.
- [x] Rename the published app to **Anchor Watch & GPS Bridge** and use **Anchor Watch** as the compact on-device name/header.
- [x] Rename the free bridge feature to **NMEA Stream**.
- [x] Keep the existing Apple bundle identifier and Android application ID.
- [x] Ship three cumulative tiers:
  - Free: unlimited, ad-free NMEA TCP/UDP streaming, setup help, GPS diagnostics, and NMEA preview.
  - Anchor Watch: $9.99 one-time lifetime unlock, including local anchor setup, monitoring, alarms, history, both anchoring modes, and NMEA Stream.
  - Remote Anchor Watch: requires Anchor Watch and adds the complete paired Android shore-monitor feature for $2.99/month or $19.99/year.
- [x] Offer no introductory trial.
- [x] Make purchases platform-specific and restorable only through the same Apple or Google account/store.
- [x] Require the tier-3 subscription only on the authoritative boat phone; the paired Android shore phone is free.
- [x] Require tier 3 for every shore-monitor connection, whether over LAN or Tailscale.
- [x] Disclose before subscription that a separate Android phone is required and iPhone/iPad cannot be the shore monitor.
- [x] Require an explicit Android-companion acknowledgement immediately above subscription checkout.
- [x] Use staged checkout: free users buy Anchor Watch first, then may subscribe to Remote Anchor Watch.
- [x] Keep subscription validation on-device with StoreKit 2 and Google Play Billing; do not add accounts or a developer entitlement backend.
- [x] Trust a last-known-active remote entitlement for up to seven days when store validation is unavailable.
- [x] Never interrupt an already-armed local or remote safety session because billing state changes. Revalidate before the next session.
- [x] Keep pairing credentials stored but inactive after tier-3 expiry so renewal does not require needless re-pairing.
- [x] Ship English only in 2.0, but move strings into localization resources for future translations.
- [x] Support metres and feet for display; retain metres internally and in the wire protocol.
- [x] Keep the first iOS release iPhone-only.
- [x] Publish worldwide where the stores and seller/tax configuration permit, using automatic regional price conversion.
- [x] Release as version 2.0.0 on both platforms with monotonically increasing build/version codes.
- [x] Include no analytics, advertising SDKs, accounts, or third-party crash-reporting SDKs.
- [x] Use TestFlight and Google Play internal/closed testing before a staged production rollout.

## Safety and entitlement invariants

- [x] The phone left aboard remains authoritative; the Android shore role is read-only.
- [x] Silencing remains local to each device.
- [x] The compact 10-second state heartbeat remains active in the background; plot history is fetched only while the shore screen is visible.
- [x] Store/billing connectivity must never disarm a watch or disconnect an active remote session.
- [x] First arming will require a device-readiness flow covering precise/background location, notifications, platform power settings, and a confirmed siren test.
- [x] First arming will require one concise acknowledgement that the app is an auxiliary aid, not the sole vessel watch or navigation system.
- [x] Apply for Apple Critical Alerts, but do not block 2.0 on approval and do not claim Focus/mute override until approved.
- [x] Preserve and integrate the in-progress degraded-GPS-precision warning currently present in the worktree.
- [x] Add deterministic tests for entitlement evaluation, offline cache, expiry/revocation, and active-session continuity.
- [x] Add deterministic tests for metres/feet conversions and formatting.
- [x] Add purchase/restore/expiry physical-device test cases to the release checklist.

## 1. Release and dependency audit

- [x] Confirm current branch and preserve unrelated uncommitted GPS-quality work.
- [x] Inventory existing iOS/Android metadata, identifiers, versions, manifests, entitlements, tests, and build scripts.
- [x] Confirm there is no existing billing, account, entitlement, paywall, privacy manifest, store metadata, or release-signing pipeline.
- [x] Verify current StoreKit 2 subscription/non-consumable requirements against Apple documentation. See `docs/RELEASE_AUDIT.md`.
- [x] Verify current Google Play Billing integration, acknowledgement, pending purchase, and subscription requirements against Android documentation. See `docs/RELEASE_AUDIT.md`.
- [x] Audit App Store background-mode usage and permission descriptions for reviewer defensibility. See `docs/RELEASE_AUDIT.md`.
- [x] Audit Android permissions, foreground-service declarations, backup behavior, exported components, and target-SDK requirements. See `docs/RELEASE_AUDIT.md`.
- [x] Audit Apple privacy manifest/API-reason requirements and Google Play Data Safety declarations. See `docs/RELEASE_AUDIT.md`.

## 2. Shared commercial model

- [x] Define `free`, `anchorWatch`, and `remoteAnchorWatch` feature levels on both platforms.
- [x] Define stable product identifiers and document the App Store Connect/Play Console mapping.
- [x] Implement a pure entitlement evaluator with store status, last validation time, seven-day offline policy, and revocation handling.
- [x] Implement a session entitlement lease so an armed session remains enabled until disarm.
- [x] Gate anchor arming/setup/history behind Anchor Watch without hiding the anchor-first product introduction.
- [x] Gate pairing-code creation, sync-server availability, and new remote sessions behind Remote Anchor Watch.
- [x] Preserve stored pairing when remote entitlement becomes inactive.
- [x] Add a debug-only, unmistakably labelled StoreKit/Play-license testing path that cannot ship enabled in Release.

## 3. iOS purchases and entitlement state

- [x] Add StoreKit 2 product loading for the Anchor Watch non-consumable and monthly/annual Remote subscriptions.
- [x] Observe transaction updates and current entitlements using verified StoreKit transactions.
- [x] Finish and persist verified transactions appropriately.
- [x] Implement purchase, restore/sync, pending, cancelled, failure, refund/revocation, expiration, and billing-retry UI states.
- [x] Cache only the minimum entitlement metadata needed for safe offline evaluation.
- [x] Add a StoreKit configuration file for deterministic local purchase tests without changing Release behavior.
- [x] Add `Manage Subscription`, `Restore Purchases`, privacy, terms, and support links.
- [x] Ensure dynamic localized store prices are used; never hard-code checkout prices in UI.

## 4. Android purchases and entitlement state

- [x] Add the current supported Google Play Billing dependency to Gradle.
- [x] Update the manual debug build or replace it with a reproducible supported Gradle wrapper flow so billing builds can still be verified locally.
- [x] Connect/reconnect BillingClient safely and query product details plus existing purchases.
- [x] Enable and handle pending purchases.
- [x] Verify product IDs, purchase state, acknowledgement, cancellation, errors, and subscription changes.
- [x] Acknowledge completed purchases within Google Play's required window.
- [x] Persist only minimum entitlement/cache metadata and enforce the seven-day offline rule.
- [x] Add restore/refresh and Play subscription-management actions.
- [x] Ensure the free shore-monitor path never invokes or requires billing.

## 5. Anchor-first information architecture

- [x] Change the primary selector/order to **Anchor** first and **NMEA Stream** second on iOS and Android.
- [x] Open normal launches on Anchor for free and paid users.
- [x] Show free users a concise Anchor Watch product introduction, anchoring modes, safety behavior, tier comparison, and `Unlock Anchor Watch` action.
- [x] Avoid disabled operational controls in the free preview.
- [x] Send Anchor Watch owners directly to the operational anchor dashboard.
- [x] Show Remote Anchor Watch as a non-blocking add-on after successful Anchor Watch purchase.
- [x] Keep the remote upgrade available from the shore-monitor card.
- [x] Keep Android normal launches on Anchor; QR/deep links and monitor notifications open Shore Monitor directly.
- [x] Preserve a secondary route from the Android main app to an already-paired shore monitor.
- [x] Apply the previously agreed cross-platform UI information contract: canonical anchor state wording, GPS accuracy, plotted fixes, reference details, diagnostics structure, and truthful connection wording. See `docs/UI_INFORMATION_CONTRACT.md`.

## 6. Paywalls and purchase disclosures

- [x] Build an Anchor Watch lifetime paywall with benefits, dynamic price, restore action, safety limitation, privacy, support, and terms.
- [x] Build a separate Remote Anchor Watch paywall available only after tier 2 is owned.
- [x] Offer monthly and annual products together and highlight annual as best value.
- [x] Put `Requires a separate Android phone; iPhone and iPad cannot act as the shore monitor` directly above checkout.
- [x] Require the Android-companion acknowledgement before enabling subscription checkout.
- [x] Explain that LAN or user-provided Tailscale/equivalent VPN connectivity is required and no developer relay is provided.
- [x] Explain store-specific ownership before checkout.
- [x] Include auto-renewal, cancellation, billing period, and subscription-management disclosures required by each store.
- [x] Avoid claims that the app overrides iOS Focus/mute or can relaunch after force-quit/reboot.

## 7. Readiness onboarding and settings

- [x] Implement a first-arm readiness flow on iOS.
- [x] Implement a first-arm readiness flow on Android boat mode.
- [x] Verify precise location, required background capability, and notifications.
- [x] Explain/check Android power-saving risk and provide the correct settings deep link.
- [x] Play a short test siren and require user confirmation that it was heard.
- [x] Record the auxiliary-aid acknowledgement locally.
- [x] Persist readiness completion locally and invalidate it if a required permission becomes unavailable.
- [x] Add `Test alarm again` and readiness status to settings without cluttering the live dashboard.
- [x] Add units setting: System Default, Metres, Feet.

## 8. Units and formatting

- [x] Add shared unit conversion/formatting helpers in Swift and Java.
- [x] Default display units from device locale while allowing explicit override.
- [x] Convert anchor radius, current distance, GPS accuracy, plot labels, limit text, and step hints consistently.
- [x] Convert shore-monitor telemetry using the shore phone's display preference without altering protocol metres.
- [x] Keep coordinates, NMEA, persistence, geometry, history, and protocol data unchanged in canonical units.
- [x] Add boundary tests for rounding and mode-specific step sizes in feet.

## 9. Naming, localization, and visual identity

- [x] Change store/display naming to Anchor Watch & GPS Bridge / Anchor Watch.
- [x] Change visible `Bridge` terminology to `NMEA Stream` where it refers to the free feature.
- [x] Remove Navionics from the product title and add a concise unaffiliated compatibility disclaimer where referenced.
- [x] Move iOS user-facing copy into a String Catalog or `Localizable.strings` structure.
- [x] Move Android user-facing copy into `res/values/strings.xml`.
- [x] Design a distinctive anchor + GPS/radar visual system without third-party chart branding.
- [x] Produce iOS App Icon assets at all required sizes.
- [x] Produce Android adaptive foreground/background and monochrome icon assets plus notification-safe iconography.
  - 2026-08-19: English iOS String Catalogs and Android resources compile in Release builds. The navy/white/cyan radar-anchor identity is wired through the iOS asset catalog and Android adaptive, themed monochrome, and notification-safe vectors.
- [ ] Verify light/dark appearance, contrast, Dynamic Type/text scaling, and screen-reader labels.
  - 2026-08-19: The iOS Release build was visually checked in light/dark appearance, increased contrast, and the largest accessibility text size. The navigation title and section switcher were adjusted to avoid accessibility-size truncation. Android device rendering and VoiceOver/TalkBack traversal remain.

## 10. Store metadata, privacy, and legal artifacts

- [x] Set both platforms to version 2.0.0 and increment build/version codes.
- [x] Add an Apple privacy manifest if required by the final API/dependency audit.
- [x] Draft App Store privacy-label answers.
- [x] Draft Google Play Data Safety answers.
- [x] Draft App Store and Play Store titles, short/promotional text, long descriptions, keywords/tags, release notes, and reviewer notes.
- [x] Draft privacy policy covering local GPS/history, direct paired transport, store billing, diagnostics, and deletion behavior.
- [x] Draft support documentation for permissions, alarm testing, battery/power settings, force-quit/reboot limits, purchase restoration, subscription management, Tailscale, and Android-only shore monitoring.
- [x] Draft terms/subscription terms and auxiliary-safety disclaimer.
- [x] Add in-app links using placeholder configuration until the public domain is known.
- [ ] Replace every placeholder with live HTTPS URLs before submission.
  - 2026-08-20: Privacy is live at `https://achievetherapy.co.uk/anchor-watch/privacy/` and is wired into both apps. Support and terms URLs remain.
- [x] Add a non-affiliation statement for Navionics/Garmin compatibility references.

## 11. Release engineering

- [x] Add/verify iOS Release archive configuration, signing, capabilities, versioning, and export validation.
  - 2026-08-20: Xcode is signed into team `385U4527HY`. Automatic signing created the app-specific profile, and `2.0.0 (4)` archived and exported with the Apple Distribution identity. The exported IPA uses `com.jaco.navionicsgpsbridge`, includes `PrivacyInfo.xcprivacy`, contains the live privacy URL, and has `get-task-allow=false`.
- [ ] Apply for Apple Critical Alerts entitlement and track it as optional for 2.0.
- [ ] Configure App Store Connect non-consumable and subscription group/products.
  - 2026-08-21: Created App Store Connect app `6803948419` for `com.jaco.navionicsgpsbridge` and set its release version to `2.0.0`; configured the USD 9.99 lifetime non-consumable and the USD 2.99 monthly/USD 19.99 annual subscriptions in group `22325758`, with worldwide availability and English (U.K.) localization. The two durations still need to be stacked at the same service level before this item is complete; no product has been submitted for review.
- [ ] Configure Google Play one-time product and one subscription with monthly/annual base plans.
  - 2026-08-21: Created Play app `4975101296680155371`, wired its Licensing public key into Android, and uploaded `2.0.0 (5)` as a saved internal-test draft. Activated `anchor_watch_lifetime` with purchase option `lifetime` at USD 9.99 (automatic regional pricing) and created subscription `remote_anchor_watch`. Play currently rejects the otherwise valid monthly base-plan save with only `Your changes couldn't be saved`; monthly/annual base plans remain.
- [x] Enable Play App Signing and create/protect a separate upload key outside source control.
  - 2026-08-21: Play confirms releases are signed by Google Play. The protected 4,096-bit RSA upload key is stored outside the repository under `~/Library/Application Support/Anchor Watch Signing`, with its generated password in the login Keychain. Its public certificate has SHA-256 fingerprint `FA:46:34:25:A6:AC:8E:96:FA:BD:87:D0:C7:88:64:AF:F8:FA:3F:2A:55:6B:6E:85:3C:17:F7:A3:50:2C:0E:24`. An off-machine backup of the keystore and password is still required.
- [x] Configure release minification/resource shrinking only after protocol/crypto reflection behavior is verified.
  - 2026-08-19: R8 obfuscation, optimization, code shrinking, and resource shrinking are enabled for Release. The shrunk bundle builds cleanly, emits the Play deobfuscation mapping, and the crypto/display compatibility self-tests pass against the Release-compiled classes.
- [x] Produce an Android App Bundle and verify signing, version code, manifest, and 16 KB page compatibility.
  - 2026-08-21: `android/build-release.sh` produced the R8-shrunk, upload-key-signed `app-release.aab` with version `2.0.0 (5)`, target API 36, the live privacy URL, the Play licensing public key, and no native `.so` libraries, so native 16 KB page-size validation is not applicable. `jarsigner` verification passed and the embedded signer matches the exported upload certificate. SHA-256: `587e8500744ec356c5ea3c64c5391e9bfb22edc4b65f262dff2bda3e2814dd95`.
- [x] Create TestFlight and Play internal/closed-test release instructions.
- [x] Define staged production rollout and rollback/stop criteria.

## 12. Verification matrix

- [x] Run all XCTest suites on an installed simulator/device target.
  - Passed on an iPhone 17 Pro simulator running iOS 26.4 on 2026-08-19.
- [x] Build an iOS Release archive and validate it.
  - The distribution-exported `2.0.0 (4)` IPA passed local signature verification, includes `PrivacyInfo.xcprivacy`, and excludes the Debug entitlement bypass.
- [x] Build and sign Android debug APK and release AAB.
  - 2026-08-20: Debug APK v2 signing was already verified; the `2.0.0 (5)` release AAB is now signed by the protected Play upload key and passes `jarsigner` verification.
- [x] Run `AnchorSyncCryptoSelfTest` against the final Android build classes.
  - `AnchorSyncCryptoSelfTest` and `DisplayUnitsSelfTest` passed against the current Release-compiled classes on 2026-08-19; rerun against the signed submission candidate if its code changes.
- [ ] Test free NMEA Stream without purchases on both platforms.
- [ ] Test Anchor Watch purchase, pending/cancelled/error states, restore, refund/revocation, and reinstall on both stores.
- [ ] Test Remote monthly and annual subscription purchase, upgrade/switch, cancellation, expiry, billing retry/grace, offline cache, and management links.
- [ ] Verify active local and remote sessions survive entitlement state changes until disarm.
- [ ] Verify new sessions are blocked correctly after entitlement expiry/cache timeout.
- [ ] Verify iPhone boat → Android shore and Android boat → Android shore over LAN and Tailscale.
- [ ] Verify pairing secrets never appear in logs, screenshots, analytics, crash text, or store review artifacts.
- [ ] Verify history traffic occurs only while the shore activity is visible.
- [ ] Verify alarm, local-only silence, durable replay, heartbeat loss, reconnect, reboot restoration, and battery behavior on physical devices.
- [ ] Verify first-arm readiness and units on all supported OS versions and text sizes.
- [ ] Capture final store screenshots only from release-equivalent builds with synthetic/non-sensitive coordinates.

## External submission blockers

- [x] Purchase and configure a public domain.
  - `achievetherapy.co.uk` is live on GitHub Pages.
- [ ] Publish live privacy, support, and terms pages over HTTPS.
  - Privacy is live at `https://achievetherapy.co.uk/anchor-watch/privacy/`; support and terms remain.
- [x] Create domain-based support contact details for store listings.
  - Use `app@achievetherapy.co.uk`.
- [ ] Create and approve all in-app products in App Store Connect and Play Console.
- [ ] Complete Apple agreements, tax, banking, export-compliance, age-rating, and privacy responses.
  - 2026-08-21: The Free Apps Agreement is active. The Paid Apps Agreement is `Pending User Info`; the bank account update is processing (Apple says up to 24 hours), and the U.S. Tax Questionnaire still reports `Missing Tax Info`.
- [ ] Complete Google Play identity, payments profile, content rating, Data Safety, app-access, ads, target-audience, and foreground-service declarations.
  - 2026-08-21: The merchant payments profile exists, but Google Play still shows `Add a payment method to receive your earnings`. The Android package is registered for developer verification; the remaining app-content and policy declarations are not complete.
- [ ] Obtain Critical Alerts approval if Apple grants it; otherwise ship without critical-alert claims.
- [ ] Complete the required beta tester period and physical-device verification.
