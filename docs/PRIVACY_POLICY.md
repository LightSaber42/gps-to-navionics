# Anchor Watch privacy policy

Effective date: 20 August 2026

Publisher: Achieve Therapy Ltd (`we`, `us`), company number 09790834

Contact: `app@achievetherapy.co.uk` · Reading, United Kingdom

Public URL: `https://achievetherapy.co.uk/anchor-watch/privacy/`

## Summary

Anchor Watch has no developer account system, advertising, analytics, third-party crash reporting, or developer-operated data relay. We do not receive your GPS position, anchor history, pairing secret, or NMEA traffic. The app processes this information on your phones and, only when you turn on a direct feature, sends it to a receiver you choose.

## Information handled on your device

Depending on the features you use, the app stores or processes:

- precise location fixes, reported accuracy, speed, course, and fix time;
- the anchor reference, radius, armed/alarm state, recent plot history, and durable alarm events;
- app settings, readiness acknowledgements, and display-unit preference;
- a shore-monitor pairing secret and reachable network addresses;
- minimum purchase-entitlement status and last-validation time supplied by Apple or Google Play; and
- local diagnostics such as listener status, connected-client count, and recent NMEA output shown in the app.

This information is used only to provide NMEA Stream, local anchor monitoring, alarm/history behavior, direct paired monitoring, purchase restoration, and settings.

## Direct network transfers you control

### NMEA Stream

When you start NMEA Stream, the app sends live GPS-derived NMEA 0183 sentences over TCP and/or UDP to devices on a reachable network. NMEA is plaintext and is not end-to-end encrypted by Anchor Watch. Anyone with access to the selected local or VPN network may be able to observe it. You control when streaming starts, the network, and the receiving navigation app.

### Paired shore monitoring

When you pair and enable a shore phone, the boat and shore phones communicate directly on TCP port 11124. A versioned HMAC handshake authenticates the pairing, and subsequent frames use direction-bound, sequenced AES-GCM encryption. The channel carries armed/alarm state, current distance and accuracy, battery state, durable alarm events, and plot-history pages requested while the shore screen is open.

The project operates no public relay and cannot read this traffic. Same-LAN operation needs no VPN. If you use Tailscale or another VPN, that provider separately handles network metadata and is governed by its own privacy policy.

## Store purchases

Apple App Store or Google Play processes purchases. The app receives product, purchase, validity, renewal, expiration, revocation, and acknowledgement information needed to grant features and restore purchases. We do not receive your payment-card details. There is no developer entitlement server; minimum entitlement state is cached locally for offline behavior.

## Data collection by the publisher

We do not collect data from the app onto systems controlled by us. We do not sell personal data, track users across apps or websites, serve ads, or use app data for analytics or marketing.

Apple and Google may process store, device, diagnostic, and transaction information under their own terms when you download the app or use in-app purchases. Review their privacy information for details.

## Retention and deletion

Location history, events, settings, and entitlement cache remain on the device until the app clears or replaces them, you clear app storage, or you uninstall the app. Android backup is disabled.

Removing a shore pairing on Android deletes its saved pairing. Replacing the paired monitor on the boat invalidates the previous secret. Apple Keychain items can survive an iOS app uninstall under platform behavior; use **Replace paired monitor** before uninstalling if you need to invalidate an existing shore credential.

Because we do not hold an account or server copy, we cannot retrieve or delete device data for you. To remove it, unpair/replace credentials as described above, clear Android app storage, or delete the app. Store purchase records remain with Apple or Google according to their policies.

## Permissions

- Precise and background-capable location supports live NMEA and an armed boat watch while the screen is locked.
- Local Network access on iOS supports direct NMEA and shore-monitor connections.
- Notifications and sound deliver monitoring status and alarms.
- Android notification-policy access is optional but needed for supported Do Not Disturb bypass behavior.
- Android boot completion can restore a shore monitor only after the user explicitly enabled it.

You can revoke permissions in system settings, but doing so can stop the related function. An already force-quit or rebooted iPhone app cannot relaunch itself to resume an armed watch.

## Security

Pairing secrets are stored in iOS Keychain or encrypted with Android Keystore. Shore-monitor frames are authenticated and encrypted. NMEA Stream is intentionally plaintext for compatibility and should be used only on a network you trust. No phone software or network protection can eliminate all risk.

## Children

Anchor Watch is intended for adult boat operators and is not directed to children. We do not knowingly collect children's personal information.

## International use and changes

Your stores and chosen network or VPN providers may process information in other countries under their own policies. We may update this policy when app behavior, dependencies, or legal requirements change. The public page will show the current effective date.

## Store privacy answers

These are submission notes, not part of the public policy page.

### Apple App Privacy

- Tracking: No.
- Data collected by the developer or third-party partners through this app: No.
- Privacy Policy URL: `https://achievetherapy.co.uk/anchor-watch/privacy/`.
- User Privacy Choices URL: omit, or use the same privacy page if it explains local deletion.

This answer depends on the final binary retaining the current no-backend/no-analytics design and on a final dependency audit.

### Google Play Data Safety

Use the conservative disclosure already selected in the release audit:

- Does the app collect or share required user-data types? Yes — disclose **Location → Precise location** as collected for app functionality because an optional user-started NMEA stream can transmit it off-device.
- Shared: No. The transfer is initiated and directed by the user to the user's chosen receiver; there is no developer or advertising recipient.
- Required or optional: Optional; users can use non-location shore functionality where applicable and choose whether to start NMEA/boat monitoring.
- Purpose: App functionality only.
- Processed ephemerally: No, conservatively, because anchor history may also persist locally even though the outbound NMEA feed is live.
- Data encrypted in transit: Do not claim that all collected data is encrypted; compatible NMEA streaming is plaintext. The paired shore channel itself is end-to-end encrypted.
- Data deletion request mechanism: No developer account/server copy exists. Explain that local data is deleted by unpairing/replacing credentials, clearing app storage, or uninstalling.
- Data sold: No.
- Analytics, advertising, personalization, fraud-prevention profiling, or marketing: No.
- Purchase history: do not declare as developer collection under the current client-only design; Apple/Google directly process store payments and the app keeps only local entitlement state. Reassess if a backend, Real-time Developer Notifications, or purchase-token upload is added.

Before publishing, compare these answers against the final Play SDK Index disclosures for Google Play Billing and every packaged dependency.

## Policy references

- Apple App Privacy: https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy/
- Apple App Review privacy requirements: https://developer.apple.com/app-store/review/guidelines/
- Google Play Data Safety: https://support.google.com/googleplay/android-developer/answer/10787469
