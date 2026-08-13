# Anchor Watch product catalog

These identifiers are the permanent source-of-truth values for store configuration and app-side product lookup. Do not rename or reuse an identifier after it has been created in a store console. Checkout UI must use each store's localized price and billing-period metadata rather than the reference prices below.

## App Store Connect

| Access | Product type | Product identifier | Subscription group | Reference launch price |
| --- | --- | --- | --- | --- |
| Anchor Watch | Non-consumable | `com.jaco.navionicsgpsbridge.anchorwatch.lifetime` | — | USD 9.99 |
| Remote Anchor Watch monthly | Auto-renewable subscription | `com.jaco.navionicsgpsbridge.remote.monthly` | Remote Anchor Watch | USD 2.99/month |
| Remote Anchor Watch annual | Auto-renewable subscription | `com.jaco.navionicsgpsbridge.remote.annual` | Remote Anchor Watch | USD 19.99/year |

Place both Remote products in the same subscription group and at the same service level so a customer can hold only one Remote plan at a time. Remote access is cumulative: the app grants it only when both the Anchor Watch non-consumable and one Remote subscription are active.

## Google Play Console

| Access | Play product | Product/base-plan identifier | Reference launch price |
| --- | --- | --- | --- |
| Anchor Watch | One-time product | `anchor_watch_lifetime` | USD 9.99 |
| Remote Anchor Watch monthly | Subscription | `remote_anchor_watch` / `monthly` | USD 2.99/month |
| Remote Anchor Watch annual | Subscription | `remote_anchor_watch` / `annual` | USD 19.99/year |

The two Remote choices are base plans under one subscription product. As on iOS, the boat app grants Remote access only together with Anchor Watch ownership.

Before license testing, replace the empty `play_billing_public_key` string resource with the app's **Licensing & In-App Billing** public key from Play Console. The key is public verification material, not a signing secret. Android entitlement checks fail closed while it is absent. Never add an upload key, app-signing private key, service-account credential, purchase token, or pairing secret to the repository.

## Ownership boundary

Purchases are platform-specific and are restored only with the same Apple or Google account. There is no cross-store entitlement transfer and no developer account or entitlement backend. Only the authoritative boat phone validates or requires Remote Anchor Watch; the paired Android shore monitor remains billing-free.
