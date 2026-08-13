# GPS to Navionics

Native iPhone and Android apps that provide two related functions:

- Convert phone GPS fixes into NMEA 0183 for the Navionics Boating app on a Wi-Fi-only iPad.
- Run an anchor watch on the authoritative phone left aboard and mirror its alarm state and plot to one read-only Android shore phone.

## Data paths

`Phone GPS -> GPGGA/GPRMC/GPVTG -> TCP/UDP port 11123 -> Navionics on iPad`

`Boat anchor watch -> authenticated encrypted TCP port 11124 -> Android shore monitor`

This app does not modify iPadOS Location Services and is not a system-wide GPS provider. The receiving app must explicitly support NMEA over TCP.

## Run on iPhone

1. Open `NavionicsGPSBridge.xcodeproj` in Xcode and run **NavionicsGPSBridge** on an iPhone.
2. Put the iPhone and iPad on the same Wi-Fi network.
3. Tap **Start Streaming** and grant Precise Location and Local Network access.
4. In Navionics, open **Menu -> Connect a Device -> Add Device**.
5. Choose TCP and enter an address shown by the bridge plus port `11123`.

The app enables continuous background location updates for an explicitly started stream and prevents automatic screen locking while it remains in the foreground. Continuous GPS use is power intensive; keep the iPhone powered aboard.

## Run on Android

The Android Studio project is in [`android`](android). It requires Android 8.0 or newer.

1. Open the `android` directory in Android Studio, or run `android/build-debug.sh` to build and verify a signed debug APK through the pinned Gradle wrapper. The first command-line build downloads the Gradle distribution and declared dependencies.
2. Install `android/app/build/outputs/apk/debug/app-debug.apk` on the Android phone.
3. Put the Android phone and iPad on the same Wi-Fi network.
4. Open **GPS to Navionics**, tap **Start Streaming**, and allow Precise Location and notifications.
5. In Navionics, open **Menu -> Connect a Device -> Add Device**. Choose UDP, enter host `0.0.0.0`, and port `11123`. TCP remains available using an address shown by the bridge plus port `11123`.

Android streaming runs as a location foreground service with a persistent notification, so it continues while the screen is locked. Tap **Stop Streaming** in the app or notification when finished. Continuous GPS use is power intensive; keep the phone powered aboard.

UDP broadcasts are sent to each active private IPv4 Wi-Fi or hotspot network. For an offshore setup, turn on the Android phone's hotspot and join it from the iPad; an internet or mobile-data connection is not required.

## Anchor drift alarm

The Android and iPhone apps can run an anchor watch independently of Navionics streaming:

- **Shorelined** records the boat's tied position when armed and watches a tight circular radius around it.
- **Free swinging** records the anchor-drop point first, then watches a circular swing radius while the boat moves around the anchor.

Choose a mode and radius in the app. In free-swinging mode, tap **Drop anchor here** while over the anchor, pay out the rode, then tap **Arm anchor alarm**. While armed, the default screen becomes a north-up plot showing the reference, recent GPS trail, current boat position and accuracy, and the alarm boundary. Drift distance is shown to 0.1 m. Background location keeps monitoring with the screen locked. A drift must remain definitely outside the radius for five seconds after allowing for reported GPS accuracy; loss of a GPS fix for 30 seconds also triggers the alarm.

Android temporarily raises the alarm audio stream to its maximum, rings, and vibrates until silenced, then restores the previous alarm volume. iPhone plays a full-volume in-app siren and posts an immediate time-sensitive alert that repeats every minute until silenced or disarmed. iOS still respects the device's hardware volume because overriding it requires Apple's restricted Critical Alerts entitlement.

Shorelined mode provides a 3–50 m radius in precise 1 m steps. Free-swinging mode provides a 5–200 m radius in 5 m steps.

### Paired shore monitor

One phone aboard is the authoritative anchor-watch device. It can be either iPhone or Android. A second Android phone can be paired as a read-only shore monitor from **Pair shore monitor** on the boat phone. Scan the QR code with the shore phone's Camera app, or paste the pairing link manually.

The phones connect directly over Wi-Fi or Tailscale on TCP port `11124`. Tailscale is not needed while both phones are on the same reachable LAN. Once the shore phone leaves that subnet, both phones must be connected to the same Tailscale tailnet. Tailscale then provides the internet path; this project runs no public relay, account system, or hosted service.

Pairing creates a 256-bit secret and embeds the current LAN and Tailscale IPv4 addresses in the QR code. The secret is stored in the iOS Keychain or Android Keystore. Each connection uses an HMAC-authenticated handshake followed by direction-bound, sequenced AES-GCM frames. Reopen or refresh the pairing screen before scanning so its address list is current, and rescan after replacing the pairing or changing to a different tailnet.

The boat sends a small heartbeat and current alarm state every 10 seconds. A durable alarm event is replayed after a reconnect, and the shore phone raises a connection-loss alarm after 30 seconds once it has seen an armed watch. Full five-second plot history is paged in batches of 250 and requested only while the shore-monitor screen is open. Only one authenticated shore phone is active at a time.

Silencing is deliberately local: acknowledging the alarm on one phone does not silence the other. The boat remains authoritative for arming, disarming, radius, and reference position. The shore app can only view state, silence its own alarm, reconnect, or remove its pairing.

On the Android shore phone, grant notifications and Do Not Disturb access, and set the app's battery use to **Unrestricted**. Its foreground monitor restarts after an Android reboot only when monitoring was explicitly enabled. The shore mode does not request GPS and holds no wake lock, which keeps its steady-state work to one heartbeat per 10 seconds; actual battery drain still depends on the handset, signal quality, and whether Tailscale is active, so verify the 12-hour drain on the intended phone. The design target is at most 1% per hour before screen use. Plot transfer and rendering increase drain only while the app is open.

The authoritative iPhone uses background location while armed, but iOS will not automatically relaunch the app after a force-quit or device restart. Reopen it and confirm that the armed watch and shore heartbeat have recovered. If an Android Tailscale screen says **Connected** but peers remain unreachable, toggle its Tailscale connection off and on and check that its device key has not expired.

Apple's restricted Critical Alerts entitlement is still required for an iPhone boat alarm notification to override mute and Do Not Disturb after iOS suspends the app. Without that entitlement, the in-app siren and time-sensitive notifications work, but iOS may respect mute or Focus settings. Treat this as an explicit safety limitation and test both phones before leaving the boat.

Test the alarm, notification permission, alarm volume, and battery behavior before relying on it. GPS and phone software are not substitutes for a physical anchor watch.

### Current implementation status

The current protocol is version 1. It has been physically verified with an iPhone as the authoritative armed device and an Android phone as the shore monitor over Tailscale, including authenticated heartbeat/state, battery and distance display, on-demand history catch-up, 30-second link-loss alarm, Android-only silence, and automatic reconnect. The Android-authoritative implementation uses the same protocol and builds successfully; repeat the complete two-Android device checklist before relying on that arrangement aboard.

The device test also has regression coverage for retaining a newly accepted iOS client through its handshake and for keeping Android history requests off the main UI thread. These are important lifecycle/threading requirements, not optional optimizations.

## Validation

- Compare the Navionics result against GPS2IP Lite on the same Wi-Fi network.
- Confirm position, speed, and course while moving safely on shore.
- Confirm stale fixes are marked invalid after 10 seconds.
- Test screen lock, network interruption/reconnection, and an offline Wi-Fi network before use aboard.
- Test separate silence on both phones, a 30-second link loss, alarm replay after reconnect, and plot loading only while the shore screen is open.
- With Tailscale enabled, confirm each phone can reach the other before leaving the boat and repeat the check after switching between Wi-Fi and cellular data.
- Keep an independent navigation backup.
