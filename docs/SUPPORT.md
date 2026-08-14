# Anchor Watch support draft

Public URL when published: `https://[PUBLIC_DOMAIN]/anchor-watch/support`

Support: `[SUPPORT_EMAIL]` · `[SUPPORT_PHONE]` · `[POSTAL_ADDRESS]`

Include real contact details on the published page before store submission.

## Before relying on an anchor watch

Anchor Watch is an auxiliary aid, not a certified navigation, alarm, or vessel-watch system. Keep an independent physical watch and navigation backup. On every phone you plan to use:

1. Confirm a fresh, precise GPS fix in open sky.
2. Complete Safety Readiness and hear the test siren.
3. Check notification, volume, mute/Focus/Do Not Disturb, and battery settings.
4. Keep the authoritative boat phone powered.
5. Test a short real drift and a network interruption while someone remains aboard.

## Location and background monitoring

### iPhone

Allow Precise Location, Always Allow, Local Network, and notifications. An armed watch uses background location while the screen is locked. iOS cannot relaunch the app after a force-quit or reboot; reopen Anchor Watch and confirm the armed state and shore connection. Time-sensitive notifications may still respect mute or Focus unless Apple grants the separate Critical Alerts entitlement.

### Android boat phone

Allow precise location and notifications. Keep the ongoing monitoring notification visible. Set the app's battery use to Unrestricted and avoid restrictive Power Saving modes. Android 16 users should retest foreground monitoring after OS updates.

## Alarm cannot be heard

- Open **Watch details → Test alarm again** and confirm the test siren.
- Raise the phone's alarm/media volume as applicable.
- On Android, grant Do Not Disturb access and allow the Anchor alarm notification channel to bypass supported DND modes.
- On iPhone, check the Ring/Silent switch, volume, and Focus. The current release does not claim Critical Alerts.
- Never leave the boat until both boat and shore phones have passed a real audible test.

## GPS precision warning

Move the boat phone to a clear view of the sky and wait for reported accuracy to improve. The watch accounts conservatively for GPS accuracy, so poor fixes require more measured movement before confirming a boundary breach. Do not compensate for poor reception by choosing an unsafe radius.

## NMEA Stream setup

Put the phone and receiving device on the same reachable Wi-Fi or hotspot network. Start **NMEA Stream** and enter port `11123` in a compatible navigation app.

- iPhone publishes NMEA over TCP using an address displayed in the app.
- Android recommends UDP with host `0.0.0.0`; TCP remains available using a displayed address.
- Internet is not required for same-LAN use.
- NMEA is plaintext. Use a trusted local or VPN network.

Compatible with Navionics Boating via NMEA. Anchor Watch is not affiliated with or endorsed by Navionics or Garmin. Menu names in third-party apps can change; consult that app's current documentation if the example path differs.

## Pairing a shore monitor

Remote Anchor Watch requires Anchor Watch plus an active Remote subscription on the authoritative boat phone. The shore role requires a separate Android phone and is free. iPhone and iPad cannot act as the shore monitor.

1. Connect both phones to the same reachable LAN, or install and connect your own Tailscale/equivalent VPN on both.
2. On the boat phone, open **Pair shore monitor** and refresh the address list.
3. Scan the QR code with the Android shore phone's Camera, then open the link. You can paste the pairing link manually instead.
4. On the shore phone, grant notifications and DND access, set battery use to Unrestricted, hear the test alarm, and tap **Start monitoring**.
5. Confirm current armed state, distance, battery, and recent plot before leaving.

Pairing alone does not provide an internet path. There is no developer relay. Once the phones are no longer on the same LAN, both must remain reachable through your VPN.

## Shore connection troubleshooting

- Verify both phones are online and can reach one another through the chosen LAN/VPN.
- Refresh and rescan the pairing code after network addresses or Tailscale networks change.
- If Tailscale says Connected but peers are unreachable, toggle its connection and check that neither device key has expired.
- Keep the boat app armed/running and the Android shore foreground notification visible.
- If the saved credential is wrong or exposed, choose **Replace paired monitor** on the boat and pair again.
- Silencing is local. Silence both phones separately after checking the boat.

## Purchases and restoration

Purchases belong to the store account and platform used to buy them. Apple purchases do not transfer to Google Play or vice versa.

- Use **Restore Purchases** or **Refresh purchases** from the purchase/settings screen while signed into the original store account.
- A pending payment does not unlock a feature until the store reports it purchased.
- Remote Anchor Watch renews monthly or annually until cancelled. Manage or cancel it through the App Store or Google Play subscription screen opened from the app.
- If store validation is temporarily unavailable, a last-known active Remote entitlement may continue for up to seven days. A currently armed safety session is not interrupted by a billing-state change; entitlement is checked before the next session.
- Pairing is retained when a Remote subscription expires, but new remote sessions remain inactive until entitlement returns.

## Removing local data

On the Android shore phone, use **Unpair this phone** to remove the saved pairing. On the boat phone, use **Replace paired monitor** to invalidate the existing credential. Clear Android app storage or uninstall the app to remove ordinary local settings/history. On iOS, Keychain pairing data can survive uninstall, so replace the pairing first when credential invalidation matters.

Store purchase history is retained by Apple or Google under their policies and cannot be deleted by the app publisher.
