package com.jaco.navionicsgpsbridge;

import android.app.Activity;
import android.content.Context;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlayBillingStore implements PurchasesUpdatedListener {
    public enum ActionState {
        IDLE,
        CONNECTING,
        PURCHASING,
        PENDING,
        CANCELLED,
        ERROR
    }

    public interface Listener {
        void onBillingSnapshotChanged(Snapshot snapshot);
    }

    public static final class ProductOffer {
        public final String productID;
        public final String basePlanID;
        public final String formattedPrice;
        public final String billingPeriod;

        private final ProductDetails productDetails;
        private final String offerToken;

        ProductOffer(
                String productID,
                String basePlanID,
                String formattedPrice,
                String billingPeriod,
                ProductDetails productDetails,
                String offerToken
        ) {
            this.productID = productID;
            this.basePlanID = basePlanID;
            this.formattedPrice = formattedPrice;
            this.billingPeriod = billingPeriod;
            this.productDetails = productDetails;
            this.offerToken = offerToken;
        }
    }

    public static final class Snapshot {
        public final EntitlementPolicy.Decision entitlementDecision;
        public final ActionState actionState;
        public final ProductOffer anchorWatchOffer;
        public final ProductOffer remoteMonthlyOffer;
        public final ProductOffer remoteAnnualOffer;
        public final String message;

        Snapshot(
                EntitlementPolicy.Decision entitlementDecision,
                ActionState actionState,
                ProductOffer anchorWatchOffer,
                ProductOffer remoteMonthlyOffer,
                ProductOffer remoteAnnualOffer,
                String message
        ) {
            this.entitlementDecision = entitlementDecision;
            this.actionState = actionState;
            this.anchorWatchOffer = anchorWatchOffer;
            this.remoteMonthlyOffer = remoteMonthlyOffer;
            this.remoteAnnualOffer = remoteAnnualOffer;
            this.message = message;
        }
    }

    private final Context context;
    private final BillingClient billingClient;
    private final EntitlementCacheStore cacheStore;
    private final String playPublicKey;
    private final Map<String, ProductDetails> productDetails = new HashMap<>();

    private EntitlementCache cache;
    private EntitlementPolicy.Decision entitlementDecision;
    private ActionState actionState = ActionState.IDLE;
    private String message;
    private Listener listener;
    private ProductOffer anchorWatchOffer;
    private ProductOffer remoteMonthlyOffer;
    private ProductOffer remoteAnnualOffer;
    private boolean connecting;

    public PlayBillingStore(Context context) {
        this.context = context.getApplicationContext();
        cacheStore = new EntitlementCacheStore(this.context);
        cache = cacheStore.load();
        entitlementDecision = EntitlementPolicy.evaluate(
                cache.unavailableEvidence(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH),
                cache.unavailableEvidence(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH),
                System.currentTimeMillis());
        playPublicKey = this.context.getString(R.string.play_billing_public_key).trim();
        billingClient = BillingClient.newBuilder(this.context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        notifyListener();
    }

    public Snapshot currentSnapshot() {
        return new Snapshot(
                entitlementDecision,
                actionState,
                anchorWatchOffer,
                remoteMonthlyOffer,
                remoteAnnualOffer,
                message);
    }

    public void start() {
        if (billingClient.isReady()) {
            refresh();
            return;
        }
        if (connecting) return;
        connecting = true;
        setActionState(ActionState.CONNECTING, null);
        billingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                connecting = false;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    setActionState(ActionState.IDLE, null);
                    refresh();
                } else {
                    useUnavailableCache();
                    setActionState(ActionState.ERROR, billingErrorMessage(result));
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                connecting = false;
                useUnavailableCache();
                setActionState(
                        ActionState.ERROR,
                        "Google Play is temporarily unavailable. Purchases will refresh automatically.");
            }
        });
    }

    public void refresh() {
        if (!billingClient.isReady()) {
            start();
            return;
        }
        queryProducts();
        queryEntitlements();
    }

    public void close() {
        listener = null;
        billingClient.endConnection();
    }

    public void clearActionState() {
        setActionState(ActionState.IDLE, null);
    }

    public void purchaseAnchorWatch(Activity activity) {
        launchBillingFlow(activity, anchorWatchOffer);
    }

    public void purchaseRemote(
            Activity activity,
            String basePlanID,
            boolean androidCompanionAcknowledged
    ) {
        if (!androidCompanionAcknowledged) {
            setActionState(
                    ActionState.ERROR,
                    "Confirm that Remote Anchor Watch requires a separate Android phone.");
            return;
        }
        if (!entitlementDecision.featureLevel.includes(
                EntitlementPolicy.FeatureLevel.ANCHOR_WATCH)) {
            setActionState(ActionState.ERROR, "Unlock Anchor Watch before subscribing.");
            return;
        }
        ProductOffer offer = StoreProductIdentifiers.REMOTE_ANNUAL_BASE_PLAN.equals(basePlanID)
                ? remoteAnnualOffer : remoteMonthlyOffer;
        launchBillingFlow(activity, offer);
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int responseCode = result.getResponseCode();
        if (responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            processPurchases(purchases);
            queryEntitlements();
        } else if (responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            setActionState(ActionState.CANCELLED, null);
        } else if (responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            refresh();
        } else {
            setActionState(ActionState.ERROR, billingErrorMessage(result));
        }
    }

    private void queryProducts() {
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(Arrays.asList(
                        productQuery(
                                StoreProductIdentifiers.ANCHOR_WATCH_LIFETIME,
                                BillingClient.ProductType.INAPP),
                        productQuery(
                                StoreProductIdentifiers.REMOTE_ANCHOR_WATCH,
                                BillingClient.ProductType.SUBS)))
                .build();
        billingClient.queryProductDetailsAsync(params, (result, queryResult) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                setActionState(ActionState.ERROR, billingErrorMessage(result));
                return;
            }
            productDetails.clear();
            for (ProductDetails details : queryResult.getProductDetailsList()) {
                productDetails.put(details.getProductId(), details);
            }
            updateProductOffers();
            notifyListener();
        });
    }

    private QueryProductDetailsParams.Product productQuery(String productID, String type) {
        return QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productID)
                .setProductType(type)
                .build();
    }

    private void updateProductOffers() {
        anchorWatchOffer = null;
        remoteMonthlyOffer = null;
        remoteAnnualOffer = null;

        ProductDetails anchorDetails = productDetails.get(
                StoreProductIdentifiers.ANCHOR_WATCH_LIFETIME);
        if (anchorDetails != null) {
            List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                    anchorDetails.getOneTimePurchaseOfferDetailsList();
            if (offers != null && !offers.isEmpty()) {
                ProductDetails.OneTimePurchaseOfferDetails offer = offers.get(0);
                anchorWatchOffer = new ProductOffer(
                        anchorDetails.getProductId(),
                        null,
                        offer.getFormattedPrice(),
                        null,
                        anchorDetails,
                        offer.getOfferToken());
            }
        }

        ProductDetails remoteDetails = productDetails.get(
                StoreProductIdentifiers.REMOTE_ANCHOR_WATCH);
        if (remoteDetails == null || remoteDetails.getSubscriptionOfferDetails() == null) {
            return;
        }
        for (ProductDetails.SubscriptionOfferDetails offer
                : remoteDetails.getSubscriptionOfferDetails()) {
            if (offer.getOfferId() != null) continue;
            List<ProductDetails.PricingPhase> phases =
                    offer.getPricingPhases().getPricingPhaseList();
            if (phases.isEmpty()) continue;
            ProductDetails.PricingPhase recurringPhase = phases.get(phases.size() - 1);
            ProductOffer mappedOffer = new ProductOffer(
                    remoteDetails.getProductId(),
                    offer.getBasePlanId(),
                    recurringPhase.getFormattedPrice(),
                    recurringPhase.getBillingPeriod(),
                    remoteDetails,
                    offer.getOfferToken());
            if (StoreProductIdentifiers.REMOTE_MONTHLY_BASE_PLAN.equals(
                    offer.getBasePlanId())) {
                remoteMonthlyOffer = mappedOffer;
            } else if (StoreProductIdentifiers.REMOTE_ANNUAL_BASE_PLAN.equals(
                    offer.getBasePlanId())) {
                remoteAnnualOffer = mappedOffer;
            }
        }
    }

    private void queryEntitlements() {
        PurchaseQueryAccumulator accumulator = new PurchaseQueryAccumulator();
        QueryPurchasesParams oneTimeParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();
        billingClient.queryPurchasesAsync(oneTimeParams, (result, purchases) -> {
            accumulator.anchorEvidence = evidenceFromQuery(
                    result,
                    purchases,
                    EntitlementPolicy.FeatureLevel.ANCHOR_WATCH,
                    StoreProductIdentifiers.ANCHOR_WATCH_LIFETIME);
            accumulator.anchorComplete = true;
            accumulator.anchorPending = containsPending(purchases);
            processPurchases(purchases);
            accumulator.finishIfReady();
        });

        QueryPurchasesParams subscriptionParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .includeSuspendedSubscriptions(true)
                .build();
        billingClient.queryPurchasesAsync(subscriptionParams, (result, purchases) -> {
            accumulator.remoteEvidence = evidenceFromQuery(
                    result,
                    purchases,
                    EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH,
                    StoreProductIdentifiers.REMOTE_ANCHOR_WATCH);
            accumulator.remoteComplete = true;
            accumulator.remotePending = containsPending(purchases);
            processPurchases(purchases);
            accumulator.finishIfReady();
        });
    }

    private EntitlementPolicy.Evidence evidenceFromQuery(
            BillingResult result,
            List<Purchase> purchases,
            EntitlementPolicy.FeatureLevel feature,
            String productID
    ) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            return cache.unavailableEvidence(feature);
        }
        boolean active = false;
        if (purchases != null) {
            for (Purchase purchase : purchases) {
                if (purchase.getProducts().contains(productID) && isVerifiedActive(purchase)) {
                    active = true;
                    break;
                }
            }
        }
        if (active) {
            long now = System.currentTimeMillis();
            cache.recordActive(feature, now, null);
            return new EntitlementPolicy.Evidence(
                    EntitlementPolicy.StoreStatus.ACTIVE,
                    now,
                    null,
                    false);
        }
        cache.recordInactive(feature, null, false);
        return new EntitlementPolicy.Evidence(
                EntitlementPolicy.StoreStatus.INACTIVE,
                null,
                null,
                false);
    }

    private boolean isVerifiedActive(Purchase purchase) {
        return purchase != null
                && context.getPackageName().equals(purchase.getPackageName())
                && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED
                && !purchase.isSuspended()
                && PlayPurchaseSignatureVerifier.verify(
                        playPublicKey,
                        purchase.getOriginalJson(),
                        purchase.getSignature());
    }

    private void processPurchases(List<Purchase> purchases) {
        if (purchases == null) return;
        boolean pending = false;
        boolean invalid = false;
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                pending = true;
                continue;
            }
            if (purchase.getPurchaseState() != Purchase.PurchaseState.PURCHASED
                    || purchase.isSuspended()) {
                continue;
            }
            if (!isVerifiedActive(purchase)) {
                invalid = true;
                continue;
            }
            if (!purchase.isAcknowledged()) {
                acknowledge(purchase);
            }
        }
        if (invalid) {
            setActionState(
                    ActionState.ERROR,
                    playPublicKey.isEmpty()
                            ? "Google Play billing is not configured for this build."
                            : "A Google Play purchase could not be verified.");
        } else if (pending) {
            setActionState(
                    ActionState.PENDING,
                    "Payment is pending. Access will unlock only after Google Play confirms it.");
        }
    }

    private boolean containsPending(List<Purchase> purchases) {
        if (purchases == null) return false;
        for (Purchase purchase : purchases) {
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                return true;
            }
        }
        return false;
    }

    private void acknowledge(Purchase purchase) {
        AcknowledgePurchaseParams params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build();
        billingClient.acknowledgePurchase(params, result -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                setActionState(
                        ActionState.ERROR,
                        "The purchase is active, but Google Play acknowledgement will be retried.");
            }
        });
    }

    private void launchBillingFlow(Activity activity, ProductOffer offer) {
        if (!billingClient.isReady()) {
            setActionState(ActionState.ERROR, "Google Play is not ready. Try again shortly.");
            start();
            return;
        }
        if (offer == null) {
            setActionState(
                    ActionState.ERROR,
                    "This purchase is not available from Google Play right now.");
            return;
        }
        BillingFlowParams.ProductDetailsParams.Builder detailsBuilder =
                BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(offer.productDetails);
        if (offer.offerToken != null && !offer.offerToken.isEmpty()) {
            detailsBuilder.setOfferToken(offer.offerToken);
        }
        BillingFlowParams params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Arrays.asList(detailsBuilder.build()))
                .setIsOfferPersonalized(false)
                .build();
        BillingResult result = billingClient.launchBillingFlow(activity, params);
        if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            setActionState(ActionState.PURCHASING, null);
        } else {
            setActionState(ActionState.ERROR, billingErrorMessage(result));
        }
    }

    private void useUnavailableCache() {
        entitlementDecision = EntitlementPolicy.evaluate(
                cache.unavailableEvidence(EntitlementPolicy.FeatureLevel.ANCHOR_WATCH),
                cache.unavailableEvidence(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH),
                System.currentTimeMillis());
        notifyListener();
    }

    private void setActionState(ActionState state, String message) {
        actionState = state;
        this.message = message;
        notifyListener();
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onBillingSnapshotChanged(currentSnapshot());
        }
    }

    private String billingErrorMessage(BillingResult result) {
        switch (result.getResponseCode()) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "Google Play billing is unavailable on this device.";
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
                return "Google Play could not be reached. Check your connection and try again.";
            case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
                return "This product is not available from Google Play right now.";
            default:
                return "Google Play could not complete the request (code "
                        + result.getResponseCode() + ").";
        }
    }

    private final class PurchaseQueryAccumulator {
        EntitlementPolicy.Evidence anchorEvidence;
        EntitlementPolicy.Evidence remoteEvidence;
        boolean anchorComplete;
        boolean remoteComplete;
        boolean anchorPending;
        boolean remotePending;

        void finishIfReady() {
            if (!anchorComplete || !remoteComplete) return;
            cacheStore.save(cache);
            entitlementDecision = EntitlementPolicy.evaluate(
                    anchorEvidence,
                    remoteEvidence,
                    System.currentTimeMillis());
            boolean hasPending = anchorPending || remotePending;
            if (actionState == ActionState.CONNECTING
                    || actionState == ActionState.PURCHASING
                    || actionState == ActionState.CANCELLED
                    || (actionState == ActionState.PENDING && !hasPending)) {
                actionState = ActionState.IDLE;
                message = null;
            }
            notifyListener();
        }
    }
}
