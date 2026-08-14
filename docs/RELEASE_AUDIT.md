# Anchor Watch 2.0 Release Audit

Audited on 2026-08-13 against the current source tree and the linked primary Apple and Google documentation. An audit item being complete means its requirements and remediation are recorded here; it does not mean every remediation or store-console action is complete.

## Release blockers and required remediation

1. Add `PrivacyInfo.xcprivacy` to the iOS app target. The app uses app-local `UserDefaults`, so declare `NSPrivacyAccessedAPICategoryUserDefaults` with reason `CA92.1`. No other required-reason API category was found in the app sources.
2. Raise Android `targetSdk` and the manual APK link target from 35 to 36 before submission. Google Play requires Android 16 / API 36 for new phone apps and updates beginning 2026-08-31. The project already compiles against API 36.
3. Change `ShoreMonitorBootReceiver` to `android:exported="false"`. It only consumes a system boot broadcast and does not need to accept explicit broadcasts from other apps.
4. Replace the existing Navionics-oriented iOS permission strings and Android labels during the planned naming/localization work. Preserve the current explicit explanations of precise location, background use, local networking, and alarms.
5. Complete Play Console declarations for both `location` and `connectedDevice` foreground-service types, including demonstration videos and the impact of interruption. Long-running user-started anchor monitoring is treated as background-location use by Play policy even though the app intentionally does not request `ACCESS_BACKGROUND_LOCATION`.
6. Make the recurring value of Remote Anchor Watch explicit in App Review notes and subscription copy. Apple permits subscriptions in all categories but requires ongoing customer value.

## StoreKit 2 requirements

The planned product model is compatible with StoreKit:

- Configure Anchor Watch as a non-consumable.
- Configure the monthly and annual Remote Anchor Watch subscriptions in one subscription group, at the same service level. No introductory offer is needed.
- Load StoreKit products and render `Product.displayPrice`; do not format a hard-coded USD price for checkout.
- At launch, iterate verified `Transaction.currentEntitlements`. It returns non-consumables and active or in-grace-period auto-renewable subscriptions, and excludes refunded or revoked products.
- Keep one launch-lifetime observer over `Transaction.updates` so purchases, renewals, revocations, and changes made on other devices refresh local state.
- Grant or cache entitlement only from verified transactions. Persist the minimal commercial state, update the UI, and then call `finish()` on newly delivered verified transactions.
- Provide an explicit Restore Purchases action that calls `AppStore.sync()`. Do not call it automatically because it can prompt for App Store authentication; normal launches use `currentEntitlements` without prompting.
- Provide subscription management, privacy policy, and terms links in the purchase/settings UI. Restorable purchases require a restore mechanism, and subscription benefits and terms must be clear before checkout.
- Restore Apple purchases on the user's other eligible Apple devices. Cross-store restoration remains impossible and must be disclosed.

Primary references: [StoreKit current entitlements](https://developer.apple.com/documentation/storekit/transaction/currententitlements), [restore with AppStore.sync](https://developer.apple.com/documentation/storekit/appstore/sync()), [localized display price](https://developer.apple.com/documentation/storekit/product/displayprice), [Apple subscriptions](https://developer.apple.com/app-store/subscriptions/), and [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/).

## Google Play Billing requirements

- Pin `com.android.billingclient:billing:9.1.0`, the version in Google's integration guide at audit time.
- Configure one non-consumable one-time product for Anchor Watch and one Remote Anchor Watch subscription with monthly and annual auto-renewing base plans.
- Create a single application-scoped `BillingClient`, enable pending one-time purchases, reconnect after service loss, and keep the connection active only while the owning UI/application lifecycle needs it.
- Query both `INAPP` and `SUBS` purchases after a successful connection and whenever the app returns to the foreground. Process `onPurchasesUpdated` as an additional event source, not the only source.
- Grant entitlement only for `PURCHASED`, never `PENDING`. Show pending/cancel/error states without blocking the free tier.
- After locally validating and granting a completed non-consumable or initial/new-token subscription, call `acknowledgePurchase()` if it is not already acknowledged. The acknowledgement deadline is three days after transition to `PURCHASED`; subscription renewals do not need acknowledgement.
- The deliberate client-only design can use client acknowledgement, but it is materially weaker than Google's recommended secure-backend verification and real-time developer notifications. Document that limitation and never represent it as server-validated.
- The Android shore role must not create a `BillingClient`; only authoritative boat-phone flows query or purchase products.

Primary references: [Play Billing integration](https://developer.android.com/google/play/billing/integrate), [purchase security](https://developer.android.com/google/play/billing/security), [subscription base plans](https://support.google.com/googleplay/android-developer/answer/12154973), and [target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878).

## iOS permissions and background modes

Current state:

- `UIBackgroundModes` contains `location` and `audio`. Location updates are enabled only for an active NMEA stream or armed watch. The playback session starts only for an active alarm. These are direct uses of the declared modes and align with App Review Guideline 2.5.4.
- The first-arm readiness flow requests When In Use location in context, then upgrades to Always before arming. It blocks a new watch until Precise Location and Always Allow are active, while leaving an already-armed watch uninterrupted. The Always purpose string explains screen-locked monitoring.
- The local-network purpose covers direct NMEA clients and the paired shore monitor. There is no Bonjour browsing declaration because the app uses direct raw TCP/UDP endpoints.
- Notification authorization requests alert, sound, and badge. Alarm notifications are time-sensitive, matching the existing entitlement; the app does not request or claim Critical Alerts.
- The app correctly tells users it cannot relaunch itself after force-quit or reboot. That limitation must remain in review notes and support copy.
- Because the app uses standard AES-GCM/HMAC cryptography, App Store Connect export-compliance questions still require an explicit determination. Add the appropriate `ITSAppUsesNonExemptEncryption` value only after that determination.

Primary references: [background location updates](https://developer.apple.com/documentation/corelocation/handling-location-updates-in-the-background), [location authorization](https://developer.apple.com/documentation/corelocation/requesting-authorization-to-use-location-services), and [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/).

## Android manifest and platform audit

Current state:

- `BridgeService` correctly declares `location` plus `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`. It starts from a visible user action, owns the boat GPS/wake lock, and stops those resources when streaming/setup/arming no longer needs them.
- `ShoreMonitorService` correctly declares `connectedDevice` plus `FOREGROUND_SERVICE_CONNECTED_DEVICE`. The manifest's `CHANGE_NETWORK_STATE` satisfies a connected-device runtime prerequisite. The shore service itself has no GPS request or wake lock.
- `POST_NOTIFICATIONS`, `ACCESS_NOTIFICATION_POLICY`, `RECEIVE_BOOT_COMPLETED`, and `VIBRATE` match implemented alarm/readiness/restore behavior. Boat-mode readiness verifies notification and Do Not Disturb access, syncs DND bypass onto the alarm channel, and requires a confirmed audible test. There is no full-screen-intent or exact-alarm permission.
- Fine and coarse location are requested together at runtime. Precise GPS is central to the boat feature. The app intentionally uses a user-started location foreground service rather than requesting `ACCESS_BACKGROUND_LOCATION`; Play can still subject this behavior to background-location review.
- `android:allowBackup="false"` keeps preferences, history, cached commercial state, and encrypted pairing material out of Android backup. Pairing secrets are ciphertext under an Android Keystore key.
- Services and `AnchorPairingActivity` are non-exported. `MainActivity` must be exported for the launcher and `ShoreMonitorActivity` must be exported for its pairing deep link. The deep-link parser validates scheme, host, version, port, and key length before storage.
- `ShoreMonitorBootReceiver` is unnecessarily exported and should be hardened as noted above. Its `connectedDevice` foreground service remains an allowed boot-restoration type under the current platform restrictions.
- Android 16/API 36 targeting must be tested on a physical Android 16 device after the target bump, especially foreground-service start, notification, location, and boot restoration.

Primary references: [foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [Play foreground-service declarations](https://support.google.com/googleplay/android-developer/answer/13392821), and [Play background-location review](https://support.google.com/googleplay/android-developer/answer/9799150).

## Privacy manifest and store privacy forms

### Apple

Add an app privacy manifest with:

- tracking disabled and no tracking domains;
- no collected-data entries under the current no-backend/no-analytics design;
- `NSPrivacyAccessedAPICategoryUserDefaults` / `CA92.1` for app-only preferences.

The provisional App Store privacy-label answer is **No, we do not collect data from this app**. GPS, history, settings, alarm events, and pairing secrets are processed locally. Direct user-configured transport is not accessible to the developer or an embedded third-party partner, and Apple does not require disclosure for payment information the developer never accesses. Re-audit the final StoreKit implementation and every final dependency before submission.

### Google Play

The provisional Data Safety answers are deliberately more conservative because Play defines collection as transmitting data off-device:

- Declare precise location as optional collection for app functionality because the user can start plaintext NMEA TCP/UDP streaming to a chosen receiver. Treat the transfer as not shared under the specific user-initiated-action exception.
- The encrypted anchor-sync channel is end-to-end between paired phones, has no intermediary/relay, and falls under Play's end-to-end-encryption collection exception.
- Reassess Purchase history after the Billing adapter is final. If the app retains or transmits product/purchase status beyond Play's direct payment processing, declare it as optional collection for app functionality.
- Do not claim that all collected data is encrypted in transit while plaintext NMEA streaming exists.
- Declare no analytics, ads, crash reporting, account data, developer server collection, or sale of data.
- Keep `allowBackup="false"`; users delete local data by clearing app storage or uninstalling, with explicit in-app clearing controls added where the final UX calls for them.

Primary references: [Apple privacy manifests](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files), [Apple required-reason APIs](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api), [App Store privacy details](https://developer.apple.com/app-store/app-privacy-details/), and [Google Play Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469).
