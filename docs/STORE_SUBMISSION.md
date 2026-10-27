# Anchor Watch 2.0 store submission copy

Prepared for the English-only 2.0.0 release. Replace every bracketed value before submission. Store prices must come from App Store Connect or Google Play; do not paste reference USD prices into the app or listing.

## Shared positioning

- Published name: **Anchor Watch & GPS Bridge**
- Compact launcher name: **Anchor Watch**
- Category: Navigation
- Audience: adult boat owners and operators; not designed for children
- Compatibility statement: **Compatible with Navionics Boating via NMEA. Anchor Watch is not affiliated with or endorsed by Navionics or Garmin.**
- Public URLs:
  - Privacy: `https://achievetherapy.co.uk/anchor-watch/privacy/`
  - Support: `https://[PUBLIC_DOMAIN]/anchor-watch/support`
  - Terms: `https://[PUBLIC_DOMAIN]/anchor-watch/terms`

## Apple App Store

### Product page

- Name, 30-character maximum: `Anchor Watch & GPS Bridge`
- Subtitle, 30-character maximum: `Drift alarms and NMEA GPS`
- Promotional text, 170-character maximum: `Watch your anchored boat, review its recent track, and stream live NMEA GPS. Add direct monitoring from a separate Android phone when you go ashore.`
- Primary category: Navigation
- Secondary category: Utilities
- Keywords, 100-byte maximum: `boating,marine,gps,nmea,alarm,drift,moor,shore,monitor,navigation,sailing,position`
- Support URL: `https://[PUBLIC_DOMAIN]/anchor-watch/support`
- Marketing URL: `https://[PUBLIC_DOMAIN]/anchor-watch`
- Privacy Policy URL: `https://achievetherapy.co.uk/anchor-watch/privacy/`

### Description

Anchor Watch helps you monitor an anchored or shore-tied boat and can stream your iPhone's GPS position to a compatible marine navigation app over NMEA 0183.

ANCHOR MONITORING

Choose the setup that matches your boat:

• Shorelined watches the boat's tied position inside a tight alarm radius.
• Free swinging records the anchor-drop point, then watches the swing radius around it.

While armed, Anchor Watch shows current distance, reported GPS accuracy, the alarm boundary, and a north-up plot of recent fixes. A conservative drift check accounts for GPS accuracy, and loss of a usable GPS fix also raises an alarm. Display distances in metres or feet without changing the underlying navigation data.

NMEA STREAM — FREE

Stream standard NMEA 0183 position, speed, and course sentences over TCP to a compatible app on the same reachable network. The NMEA feature is unlimited and ad-free.

REMOTE ANCHOR WATCH

An optional subscription keeps a direct, authenticated connection from the boat phone to one separate Android shore phone. The shore phone can display armed state, distance, boat battery, recent track, durable alarm events, and connection-loss warnings. It is deliberately read-only: the boat phone remains authoritative, and silencing is local to each phone.

Remote monitoring requires:

• an Anchor Watch lifetime unlock on the boat phone;
• a separate Android phone for the shore role — iPhone and iPad cannot act as the shore monitor; and
• a reachable local network or your own Tailscale/equivalent VPN connection.

There is no developer-operated relay or account service. Purchase restoration is limited to the same Apple account and does not transfer to Google Play.

SAFETY AND PLATFORM LIMITS

Anchor Watch is an auxiliary aid, not a certified navigation, alarm, or vessel-watch system. Test the siren, notifications, volume, GPS reception, network path, and battery behavior on the actual phones before relying on it. Keep an independent physical watch and navigation backup.

An armed iPhone uses background location but cannot relaunch itself after the app is force-quit or the phone restarts. Without Apple's restricted Critical Alerts entitlement, iOS can respect mute and Focus settings. Keep the boat phone powered.

Compatible with Navionics Boating via NMEA. Anchor Watch is not affiliated with or endorsed by Navionics or Garmin.

### What's new in 2.0.0

Anchor Watch is now the primary experience, with Shorelined and Free swinging modes, conservative GPS-accuracy handling, alarm history, a recent-position plot, first-arm safety checks, and metre/feet display options. The free GPS bridge is now NMEA Stream. Optional Remote Anchor Watch adds direct monitoring from one paired Android shore phone. Version 2.0 also adds StoreKit purchases, clearer safety and connectivity disclosures, and refreshed privacy behavior.

### App Review notes

The app has no account, login, analytics, advertising, developer backend, or developer relay. Its free NMEA Stream works without a purchase. Anchor Watch is a non-consumable purchase. Remote Anchor Watch is an auto-renewable subscription and is useful only after Anchor Watch is owned.

To review the local feature:

1. Open the app and select Anchor.
2. Purchase Anchor Watch with the App Review sandbox account.
3. Choose Shorelined, select a radius, and tap Arm anchor alarm.
4. Grant Precise Location, Always Allow location, and notifications when prompted.
5. Complete the safety-readiness flow and confirm the short test siren.
6. The live dashboard shows distance, accuracy, limit, plotted fixes, and alarm state. Disarm from the dashboard when finished.

Remote Anchor Watch provides ongoing value through a persistent authenticated heartbeat, current armed/alarm/battery state, durable alarm replay, connection-loss alarms, reconnection, and on-demand plot-history catch-up on a separate Android phone. It requires two physical phones and a reachable LAN or user-provided Tailscale/equivalent VPN. The iPhone is always the authoritative boat phone; the Android shore role cannot arm, disarm, move the reference, or change the radius. No developer server can be used as a substitute during review.

NMEA Stream can be reviewed independently by tapping NMEA Stream and Start streaming. It publishes TCP NMEA on port 11123 to a receiver on the same reachable network.

The app uses background location only while NMEA Stream, anchor setup, or an armed watch requires it. Alarm playback uses the declared audio background mode only during an active alarm. Notifications are time-sensitive, not Critical Alerts. Force-quitting or rebooting iOS prevents automatic relaunch; this is disclosed in the UI.

Pairing uses standard HMAC-SHA256 and AES-GCM. Export-compliance answers must match the final determination in App Store Connect. Purchases are validated on-device with StoreKit 2; there is intentionally no entitlement backend.

Review contact: `[REVIEW_CONTACT_NAME]`, `[REVIEW_CONTACT_EMAIL]`, `[REVIEW_CONTACT_PHONE]`.

### App Store in-app product metadata

| Product | Display name | Description |
| --- | --- | --- |
| `com.jaco.navionicsgpsbridge.anchorwatch.lifetime` | Anchor Watch | Local anchor alarms, plot and history |
| `com.jaco.navionicsgpsbridge.remote.monthly` | Remote Watch Monthly | Monitor your armed boat from Android |
| `com.jaco.navionicsgpsbridge.remote.annual` | Remote Watch Annual | Monitor your armed boat from Android |

Subscription group reference and display name: `Remote Anchor Watch`. Put monthly and annual at the same service level. There is no introductory offer.

## Google Play

### Main store listing

- App name, 30-character maximum: `Anchor Watch & GPS Bridge`
- Short description, 80-character maximum: `Anchor drift alarms, shore monitoring and free NMEA GPS streaming.`
- Category: Maps & Navigation
- Suggested tags, if offered by Play Console: Boating, Navigation
- Contact email: `[SUPPORT_EMAIL]`
- Contact website: `https://[PUBLIC_DOMAIN]/anchor-watch/support`
- Privacy policy: `https://achievetherapy.co.uk/anchor-watch/privacy/`

### Full description

Anchor Watch helps you monitor an anchored or shore-tied boat and stream the phone's live GPS position to compatible marine navigation apps over NMEA 0183.

ANCHOR MONITORING

• Shorelined mode watches a tight radius around the boat's tied position.
• Free swinging mode records the anchor-drop point and watches the boat's swing radius.
• See current distance, GPS accuracy, alarm limit, recent fixes, and a north-up track plot.
• A conservative breach check accounts for reported GPS accuracy.
• GPS-loss alarms warn when an armed watch no longer has a usable fix.
• Choose metres or feet for display while calculations and NMEA stay in canonical units.

NMEA STREAM — FREE

Publish standard NMEA 0183 position, speed, and course sentences over UDP or TCP to a compatible app on a reachable Wi-Fi or hotspot network. NMEA Stream is unlimited and ad-free.

REMOTE ANCHOR WATCH

Use Android in either role:

• Leave the authoritative phone aboard to run the anchor watch.
• Pair one separate Android phone as a read-only shore monitor.

The optional Remote Anchor Watch subscription adds authenticated current state, distance, boat battery, alarm events, recent plot history, automatic reconnect, and a connection-loss alarm. The boat phone remains authoritative. The shore phone cannot arm, disarm, move the reference, or change the radius, and silencing one phone never silences the other.

Remote monitoring requires the Anchor Watch lifetime unlock on the boat phone, a separate Android shore phone, and either the same reachable LAN or your own Tailscale/equivalent VPN. An iPhone may be the boat phone but cannot be the shore monitor. There is no developer relay or account service.

Purchase restoration is limited to the same Google account and does not transfer to Apple's App Store. The paired shore role itself is free and does not open Google Play Billing.

SAFETY AND PHONE LIMITS

Anchor Watch is an auxiliary aid, not a certified navigation, alarm, or vessel-watch system. Test alarms, notification and Do Not Disturb settings, GPS reception, network reachability, and battery use on the actual phones. Keep an independent physical watch and navigation backup, and keep the boat phone powered.

Compatible with Navionics Boating via NMEA. Anchor Watch is not affiliated with or endorsed by Navionics or Garmin.

### Play product metadata

| Product/base plan | Customer-facing name | Description |
| --- | --- | --- |
| `anchor_watch_lifetime` | Anchor Watch Lifetime | Unlock local anchor alarms, track plot, history, and both anchoring modes on this store account. |
| `remote_anchor_watch` / `monthly` | Remote Anchor Watch Monthly | Direct read-only monitoring from one paired Android shore phone; renews monthly. |
| `remote_anchor_watch` / `annual` | Remote Anchor Watch Annual | Direct read-only monitoring from one paired Android shore phone; renews annually. |

### Play Console declarations

- App access: all free app areas are accessible without login. Paid feature review uses Play license testers and configured test products; there is no demo account.
- Ads: no ads.
- Target audience: adults; not designed for children.
- Data Safety: use the answers in `docs/PRIVACY_POLICY.md` and the final SDK inventory.
- Background location: the app does not request `ACCESS_BACKGROUND_LOCATION`. Boat monitoring uses a user-started location foreground service with a persistent notification.
- Foreground service — location: the user starts NMEA Stream, anchor setup, or an armed watch. Continuous precise fixes are required while the screen is locked. Interrupting the service stops live NMEA and can stop anchor drift/GPS-loss detection. The declaration video should show Start streaming, Arm anchor alarm, the foreground notification, screen lock, and return to the live dashboard.
- Foreground service — connected device: after explicit pairing and Start monitoring, the Android shore role keeps one authenticated TCP heartbeat connection to the boat. Interrupting it stops remote state and connection-loss/alarm delivery. The declaration video should show pairing, Start monitoring, the ongoing notification, screen lock, and a reconnect/alarm state. This role requests no GPS and holds no wake lock.
- Content rating: answer the questionnaire from actual content; the app contains no violence, gambling, sexual content, user-generated content, or social interaction.
- Government/financial/health status: none.

## Source limits checked

At drafting time Apple limited app name and subtitle to 30 characters, promotional text to 170, description to 4,000, keywords to 100 bytes, and in-app purchase display name/description to 30/45 characters. Google Play limited app name to 30, short description to 80, and full description to 4,000 characters. Re-check the counters in each console before pasting.

- Apple app information: https://developer.apple.com/help/app-store-connect/reference/app-information/app-information
- Apple version information: https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information
- Apple in-app purchase information: https://developer.apple.com/help/app-store-connect/reference/in-app-purchases-and-subscriptions/in-app-purchase-information
- Google Play store listing: https://support.google.com/googleplay/android-developer/answer/9859152
