# Anchor Watch UI information contract

This contract keeps the authoritative iOS/Android boat dashboards and the read-only Android shore dashboard semantically aligned. Platform layouts may differ, but labels must describe the same state without implying connectivity or authority that has not been observed.

## Canonical anchor state wording

| Condition | User-facing state |
| --- | --- |
| Disarmed and ready | `Anchor alarm ready` |
| Free-swinging reference missing | `Record the anchor position` |
| Acquiring the reference | `Acquiring anchor position` |
| Paying out rode before arming | `Laying anchor` |
| Armed | `Armed • Shorelined` or `Armed • Free swinging` |
| Local alarm active | `ANCHOR ALARM` |
| Local alarm silenced while still armed | `Alarm silenced • still monitoring` |

Silencing is local. A screen must not imply that silencing or acknowledgement changed another device.

## Connection wording

| Observed condition | User-facing wording |
| --- | --- |
| Boat has no authenticated shore connection | `No shore monitor connected` |
| Authenticated shore connection, sound ready | `Connected · Sound ready` |
| Authenticated shore connection, sound setup incomplete | `Connected · Setup needed` |
| Shore transport authenticated, no boat state received yet | `Connected — waiting for boat state` |
| Shore transport disconnected | Show the service's current reconnect, stopped, pairing, or heartbeat-loss state |

Do not infer that a shore phone is unpaired merely because it is offline. Do not show an armed/disarmed boat state before the first authenticated state payload arrives. For UDP NMEA, use `Broadcasting` rather than `Connected` because the sender cannot observe a receiver.

## Required live-watch information

The authoritative boat dashboard shows:

- canonical alarm state and anchoring mode;
- distance from reference and alarm limit in the selected display unit;
- reference coordinates when a reference exists;
- current GPS accuracy and degraded-precision warning when applicable;
- plotted-fix count, reference marker, current boat position, alarm boundary, and north-up trail;
- optional shore connection and sound-readiness state.

The Android shore dashboard shows:

- transport state, sound readiness, and last heartbeat;
- whether the authoritative boat state has been received;
- boat alarm/armed state without any boat-side controls;
- anchoring mode, distance, alarm limit, GPS accuracy, and plotted-fix count;
- boat battery/charging state and the history plot;
- a local-only silence action when this shore phone is alarming.

## NMEA diagnostics

Both boat implementations group GPS and transport diagnostics under `GPS and diagnostics`. They show permission, position, accuracy, fix age, speed, course, listener/broadcast state, and the last generated NMEA transmission as supported by that platform. TCP client counts indicate authenticated socket presence; Android UDP target counts indicate eligible broadcast networks, not confirmed receivers.

All displayed distances follow the local device's display-unit preference. Coordinates, persistence, geometry, NMEA, and anchor-sync payloads remain in their canonical formats and units.
