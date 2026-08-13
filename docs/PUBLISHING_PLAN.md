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
- [ ] Add deterministic tests for metres/feet conversions and formatting.
- [ ] Add purchase/restore/expiry physical-device test cases to the release checklist.

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
- [ ] Gate anchor arming/setup/history behind Anchor Watch without hiding the anchor-first product introduction.
- [ ] Gate pairing-code creation, sync-server availability, and new remote sessions behind Remote Anchor Watch.
- [ ] Preserve stored pairing when remote entitlement becomes inactive.
- [ ] Add a debug-only, unmistakably labelled StoreKit/Play-license testing path that cannot ship enabled in Release.

## 3. iOS purchases and entitlement state

- [ ] Add StoreKit 2 product loading for the Anchor Watch non-consumable and monthly/annual Remote subscriptions.
- [ ] Observe transaction updates and current entitlements using verified StoreKit transactions.
- [ ] Finish and persist verified transactions appropriately.
- [ ] Implement purchase, restore/sync, pending, cancelled, failure, refund/revocation, expiration, and billing-retry UI states.
- [ ] Cache only the minimum entitlement metadata needed for safe offline evaluation.
- [ ] Add a StoreKit configuration file for deterministic local purchase tests without changing Release behavior.
- [ ] Add `Manage Subscription`, `Restore Purchases`, privacy, terms, and support links.
- [ ] Ensure dynamic localized store prices are used; never hard-code checkout prices in UI.

## 4. Android purchases and entitlement state

- [ ] Add the current supported Google Play Billing dependency to Gradle.
- [ ] Update the manual debug build or replace it with a reproducible supported Gradle wrapper flow so billing builds can still be verified locally.
- [ ] Connect/reconnect BillingClient safely and query product details plus existing purchases.
- [ ] Enable and handle pending purchases.
- [ ] Verify product IDs, purchase state, acknowledgement, cancellation, errors, and subscription changes.
- [ ] Acknowledge completed purchases within Google Play's required window.
- [ ] Persist only minimum entitlement/cache metadata and enforce the seven-day offline rule.
- [ ] Add restore/refresh and Play subscription-management actions.
- [ ] Ensure the free shore-monitor path never invokes or requires billing.

## 5. Anchor-first information architecture

- [ ] Change the primary selector/order to **Anchor** first and **NMEA Stream** second on iOS and Android.
- [ ] Open normal launches on Anchor for free and paid users.
- [ ] Show free users a concise Anchor Watch product introduction, anchoring modes, safety behavior, tier comparison, and `Unlock Anchor Watch` action.
- [ ] Avoid disabled operational controls in the free preview.
- [ ] Send Anchor Watch owners directly to the operational anchor dashboard.
- [ ] Show Remote Anchor Watch as a non-blocking add-on after successful Anchor Watch purchase.
- [ ] Keep the remote upgrade available from the shore-monitor card.
- [ ] Keep Android normal launches on Anchor; QR/deep links and monitor notifications open Shore Monitor directly.
- [ ] Preserve a secondary route from the Android main app to an already-paired shore monitor.
- [ ] Apply the previously agreed cross-platform UI information contract: canonical anchor state wording, GPS accuracy, plotted fixes, reference details, diagnostics structure, and truthful connection wording.

## 6. Paywalls and purchase disclosures

- [ ] Build an Anchor Watch lifetime paywall with benefits, dynamic price, restore action, safety limitation, privacy, support, and terms.
- [ ] Build a separate Remote Anchor Watch paywall available only after tier 2 is owned.
- [ ] Offer monthly and annual products together and highlight annual as best value.
- [ ] Put `Requires a separate Android phone; iPhone and iPad cannot act as the shore monitor` directly above checkout.
- [ ] Require the Android-companion acknowledgement before enabling subscription checkout.
- [ ] Explain that LAN or user-provided Tailscale/equivalent VPN connectivity is required and no developer relay is provided.
- [ ] Explain store-specific ownership before checkout.
- [ ] Include auto-renewal, cancellation, billing period, and subscription-management disclosures required by each store.
- [ ] Avoid claims that the app overrides iOS Focus/mute or can relaunch after force-quit/reboot.

## 7. Readiness onboarding and settings

- [ ] Implement a first-arm readiness flow on iOS.
- [ ] Implement a first-arm readiness flow on Android boat mode.
- [ ] Verify precise location, required background capability, and notifications.
- [ ] Explain/check Android power-saving risk and provide the correct settings deep link.
- [ ] Play a short test siren and require user confirmation that it was heard.
- [ ] Record the auxiliary-aid acknowledgement locally.
- [ ] Persist readiness completion locally and invalidate it if a required permission becomes unavailable.
- [ ] Add `Test alarm again` and readiness status to settings without cluttering the live dashboard.
- [ ] Add units setting: System Default, Metres, Feet.

## 8. Units and formatting

- [ ] Add shared unit conversion/formatting helpers in Swift and Java.
- [ ] Default display units from device locale while allowing explicit override.
- [ ] Convert anchor radius, current distance, GPS accuracy, plot labels, limit text, and step hints consistently.
- [ ] Convert shore-monitor telemetry using the shore phone's display preference without altering protocol metres.
- [ ] Keep coordinates, NMEA, persistence, geometry, history, and protocol data unchanged in canonical units.
- [ ] Add boundary tests for rounding and mode-specific step sizes in feet.

## 9. Naming, localization, and visual identity

- [ ] Change store/display naming to Anchor Watch & GPS Bridge / Anchor Watch.
- [ ] Change visible `Bridge` terminology to `NMEA Stream` where it refers to the free feature.
- [ ] Remove Navionics from the product title and add a concise unaffiliated compatibility disclaimer where referenced.
- [ ] Move iOS user-facing copy into a String Catalog or `Localizable.strings` structure.
- [ ] Move Android user-facing copy into `res/values/strings.xml`.
- [ ] Design a distinctive anchor + GPS/radar visual system without third-party chart branding.
- [ ] Produce iOS App Icon assets at all required sizes.
- [ ] Produce Android adaptive foreground/background and monochrome icon assets plus notification-safe iconography.
- [ ] Verify light/dark appearance, contrast, Dynamic Type/text scaling, and screen-reader labels.

## 10. Store metadata, privacy, and legal artifacts

- [ ] Set both platforms to version 2.0.0 and increment build/version codes.
- [ ] Add an Apple privacy manifest if required by the final API/dependency audit.
- [ ] Draft App Store privacy-label answers.
- [ ] Draft Google Play Data Safety answers.
- [ ] Draft App Store and Play Store titles, short/promotional text, long descriptions, keywords/tags, release notes, and reviewer notes.
- [ ] Draft privacy policy covering local GPS/history, direct paired transport, store billing, diagnostics, and deletion behavior.
- [ ] Draft support documentation for permissions, alarm testing, battery/power settings, force-quit/reboot limits, purchase restoration, subscription management, Tailscale, and Android-only shore monitoring.
- [ ] Draft terms/subscription terms and auxiliary-safety disclaimer.
- [ ] Add in-app links using placeholder configuration until the public domain is known.
- [ ] Replace every placeholder with live HTTPS URLs before submission.
- [ ] Add a non-affiliation statement for Navionics/Garmin compatibility references.

## 11. Release engineering

- [ ] Add/verify iOS Release archive configuration, signing, capabilities, versioning, and export validation.
- [ ] Apply for Apple Critical Alerts entitlement and track it as optional for 2.0.
- [ ] Configure App Store Connect non-consumable and subscription group/products.
- [ ] Configure Google Play one-time product and one subscription with monthly/annual base plans.
- [ ] Enable Play App Signing and create/protect a separate upload key outside source control.
- [ ] Configure release minification/resource shrinking only after protocol/crypto reflection behavior is verified.
- [ ] Produce an Android App Bundle and verify signing, version code, manifest, and 16 KB page compatibility.
- [ ] Create TestFlight and Play internal/closed-test release instructions.
- [ ] Define staged production rollout and rollback/stop criteria.

## 12. Verification matrix

- [ ] Run all XCTest suites on an installed simulator/device target.
- [ ] Build an iOS Release archive and validate it.
- [ ] Build and sign Android debug APK and release AAB.
- [ ] Run `AnchorSyncCryptoSelfTest` against the final Android build classes.
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

- [ ] Purchase and configure a public domain.
- [ ] Publish live privacy, support, and terms pages over HTTPS.
- [ ] Create domain-based support contact details for store listings.
- [ ] Create and approve all in-app products in App Store Connect and Play Console.
- [ ] Complete Apple agreements, tax, banking, export-compliance, age-rating, and privacy responses.
- [ ] Complete Google Play identity, payments profile, content rating, Data Safety, app-access, ads, target-audience, and foreground-service declarations.
- [ ] Obtain Critical Alerts approval if Apple grants it; otherwise ship without critical-alert claims.
- [ ] Complete the required beta tester period and physical-device verification.
