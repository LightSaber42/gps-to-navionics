package com.jaco.navionicsgpsbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements
        BridgeService.StateListener,
        PlayBillingStore.Listener {
    private static final int PERMISSION_REQUEST = 42;
    private static final double STALE_FIX_SECONDS = 10.0;
    private static final double MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS = 15.0;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_STREAM = 1;
    private static final int PENDING_MARK_ANCHOR = 2;
    private static final int PENDING_ARM_ANCHOR = 3;
    private static final int SECTION_BRIDGE = 0;
    private static final int SECTION_ANCHOR = 1;
    private static final String ANCHOR_ALARM_CHANNEL_ID = "anchor_alarm_alerts";
    private static final String KEY_READINESS_COMPLETE = "anchor_readiness_complete_v1";
    private static final String KEY_READINESS_SIREN_CONFIRMED =
            "anchor_readiness_siren_confirmed_v1";
    private static final String KEY_READINESS_SAFETY_ACKNOWLEDGED =
            "anchor_readiness_safety_acknowledged_v1";

    private final BroadcastReceiver powerSaveModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                render(latestState);
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (BridgeService.LocalBinder) service;
            syncAnchorAlarmChannelDndBypass();
            if (billingSnapshot != null) {
                binder.updateEntitlementDecision(billingSnapshot.entitlementDecision);
            }
            binder.addListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private BridgeService.LocalBinder binder;
    private PlayBillingStore billingStore;
    private PlayBillingStore.Snapshot billingSnapshot;
    private boolean bound;
    private boolean powerSaveModeReceiverRegistered;
    private BridgeState latestState;
    private String permissionError;
    private String anchorActionMessage;
    private int pendingPermissionAction;
    private long pendingAnchorFixStartedElapsedNanos;
    private boolean anchorSetupRequested;
    private int selectedSection = SECTION_ANCHOR;
    private boolean lastRenderedAnchorArmed;
    private boolean lastRenderedAnchorTriggered;
    private AnchorAlarm.Mode selectedAnchorMode = AnchorAlarm.Mode.SHORELINED;
    private double selectedAnchorRadiusMeters = 15.0;
    private final Handler readinessHandler = new Handler(Looper.getMainLooper());
    private boolean resumeArmingAfterReadiness;
    private boolean readinessSirenPlayedThisVisit;
    private AlertDialog anchorReadinessDialog;
    private Button readinessCompleteButton;
    private Button readinessSirenButton;
    private Button readinessSirenConfirmationButton;
    private TextView readinessSirenStatus;
    private CheckBox readinessSafetyAcknowledgement;
    private ReadinessRowViews readinessLocationRow;
    private ReadinessRowViews readinessBackgroundRow;
    private ReadinessRowViews readinessNotificationRow;
    private ReadinessRowViews readinessDndRow;
    private ReadinessRowViews readinessPowerRow;
    private Ringtone readinessRingtone;
    private int readinessPreviousAlarmVolume = -1;
    private String readinessSirenError;
    private final List<Button> readinessStatusButtons = new ArrayList<>();
    private final List<Button> displayUnitButtons = new ArrayList<>();
    private final List<TextView> anchorReferenceValues = new ArrayList<>();
    private final List<TextView> anchorDistanceValues = new ArrayList<>();

    private ScrollView pageScrollView;
    private TextView pageTitle;
    private LinearLayout normalDashboard;
    private LinearLayout anchorDashboard;
    private LinearLayout anchorPurchasePreview;
    private LinearLayout disarmedAnchorDashboard;
    private LinearLayout armedAnchorContainer;
    private LinearLayout armedAnchorDashboard;
    private Button bridgeSectionButton;
    private Button anchorSectionButton;
    private View statusDot;
    private TextView statusTitle;
    private TextView bridgeGpsMetric;
    private TextView bridgeClientMetric;
    private Button streamButton;
    private TextView errorText;
    private Button settingsButton;
    private View anchorStatusDot;
    private TextView anchorStatusTitle;
    private TextView anchorDescription;
    private Button shorelinedModeButton;
    private Button freeSwingingModeButton;
    private TextView anchorRadiusValue;
    private TextView anchorRadiusHint;
    private Button anchorRadiusMinusButton;
    private Button anchorRadiusPlusButton;
    private LinearLayout freeSwingingWorkflow;
    private TextView anchorStepOneDetail;
    private TextView anchorMessage;
    private Button anchorPrimaryButton;
    private Button silenceAnchorButton;
    private LinearLayout anchorPowerSavingWarning;
    private TextView permissionValue;
    private TextView positionValue;
    private TextView accuracyValue;
    private TextView fixAgeValue;
    private TextView speedValue;
    private TextView courseValue;
    private TextView clientsValue;
    private TextView udpTargetsValue;
    private TextView serverValue;
    private LinearLayout addressesContainer;
    private TextView sentencesValue;
    private View armedAnchorStatusDot;
    private TextView armedAnchorStatusTitle;
    private TextView armedAnchorMode;
    private TextView armedAnchorDistance;
    private TextView armedAnchorDistanceLabel;
    private LinearLayout armedRadiusControls;
    private TextView armedAnchorRadiusLabel;
    private TextView armedAnchorRadiusValue;
    private Button armedAnchorRadiusMinusButton;
    private Button armedAnchorRadiusPlusButton;
    private Button armedAnchorPrimaryButton;
    private Button cancelAnchorSetupButton;
    private TextView armedAnchorTrackSummary;
    private TextView armedAnchorMessage;
    private Button armedAnchorSilenceButton;
    private AnchorPlotView anchorPlotView;
    private TextView anchorPlotNote;
    private LinearLayout armedPowerSavingWarning;
    private LinearLayout armedGpsAccuracyWarning;
    private TextView armedGpsAccuracyWarningBody;
    private TextView normalShoreMonitorValue;
    private TextView armedShoreMonitorValue;
    private TextView anchorPurchasePrice;
    private Button anchorPurchaseButton;
    private TextView anchorPurchaseStatus;
    private final List<RemoteOfferViews> remoteOfferViews = new ArrayList<>();

    private static final class RemoteOfferViews {
        final LinearLayout offerContainer;
        final CheckBox companionConfirmation;
        final Button monthlyButton;
        final Button annualButton;
        final TextView purchaseStatus;
        final Button pairingButton;
        final Button manageButton;

        RemoteOfferViews(
                LinearLayout offerContainer,
                CheckBox companionConfirmation,
                Button monthlyButton,
                Button annualButton,
                TextView purchaseStatus,
                Button pairingButton,
                Button manageButton
        ) {
            this.offerContainer = offerContainer;
            this.companionConfirmation = companionConfirmation;
            this.monthlyButton = monthlyButton;
            this.annualButton = annualButton;
            this.purchaseStatus = purchaseStatus;
            this.pairingButton = pairingButton;
            this.manageButton = manageButton;
        }
    }

    private static final class ReadinessRowViews {
        final LinearLayout container;
        final TextView title;
        final TextView detail;
        final Button action;

        ReadinessRowViews(
                LinearLayout container,
                TextView title,
                TextView detail,
                Button action
        ) {
            this.container = container;
            this.title = title;
            this.detail = detail;
            this.action = action;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        latestState = emptyState(getString(R.string.stopped));
        getWindow().setStatusBarColor(getColor(R.color.nav_background));
        getWindow().setNavigationBarColor(getColor(R.color.nav_background));
        boolean darkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildContent());
        billingStore = new PlayBillingStore(this);
        billingStore.setListener(this);
        render(latestState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter powerSaveFilter = new IntentFilter(
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    powerSaveModeReceiver,
                    powerSaveFilter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(powerSaveModeReceiver, powerSaveFilter);
        }
        powerSaveModeReceiverRegistered = true;
        bound = bindService(new Intent(this, BridgeService.class), serviceConnection, BIND_AUTO_CREATE);
        billingStore.start();
        render(latestState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        billingStore.refresh();
        render(latestState);
    }

    @Override
    protected void onStop() {
        if (powerSaveModeReceiverRegistered) {
            unregisterReceiver(powerSaveModeReceiver);
            powerSaveModeReceiverRegistered = false;
        }
        if (bound) {
            if (binder != null) binder.removeListener(this);
            unbindService(serviceConnection);
            bound = false;
            binder = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopReadinessSiren();
        if (anchorReadinessDialog != null) {
            anchorReadinessDialog.dismiss();
            anchorReadinessDialog = null;
        }
        billingStore.close();
        super.onDestroy();
    }

    @Override
    public void onBridgeStateChanged(BridgeState state) {
        runOnUiThread(() -> {
            latestState = state;
            if (state.anchorSetupActive || state.anchorAlarmArmed) {
                anchorSetupRequested = false;
            }
            render(state);
            tryCompletePendingAction();
        });
    }

    @Override
    public void onBillingSnapshotChanged(PlayBillingStore.Snapshot snapshot) {
        runOnUiThread(() -> {
            billingSnapshot = snapshot;
            if (binder != null) {
                binder.updateEntitlementDecision(snapshot.entitlementDecision);
            }
            render(latestState);
        });
    }

    private View buildContent() {
        int pagePadding = dp(16);
        pageScrollView = new ScrollView(this);
        pageScrollView.setFillViewport(true);
        pageScrollView.setBackgroundColor(getColor(R.color.nav_background));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pagePadding, dp(12), pagePadding, dp(28));
        ScrollView.LayoutParams contentParams = new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        contentParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pageScrollView.addView(content, contentParams);
        pageScrollView.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup.LayoutParams rawParams = content.getLayoutParams();
            int targetWidth = Math.min(right - left, dp(720));
            if (rawParams.width != targetWidth) {
                rawParams.width = targetWidth;
                content.setLayoutParams(rawParams);
            }
        });

        pageScrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        pageTitle = text(getString(R.string.anchor_watch_title), 28, R.color.nav_text);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageTitle.setPadding(dp(4), dp(10), 0, dp(12));
        content.addView(pageTitle, matchWrap());
        content.addView(buildSectionSwitcher(), cardParams());

        if (isDebuggableBuild()) {
            TextView debugBanner = text(
                    getString(R.string.debug_google_play_test_only),
                    12,
                    R.color.nav_warning);
            debugBanner.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            debugBanner.setPadding(dp(4), 0, dp(4), dp(12));
            content.addView(debugBanner, matchWrap());
        }

        normalDashboard = new LinearLayout(this);
        normalDashboard.setOrientation(LinearLayout.VERTICAL);
        normalDashboard.addView(buildStatusCard(), cardParams());
        normalDashboard.addView(buildNetworkCard(), cardParams());
        LinearLayout setupContent = vertical();
        setupContent.addView(buildInstructionsCard(), cardParams());
        normalDashboard.addView(
                buildDisclosureSection(getString(R.string.set_up_navigation_app), setupContent),
                cardParams());
        LinearLayout bridgeDetails = vertical();
        bridgeDetails.addView(buildGpsCard(), cardParams());
        bridgeDetails.addView(buildDiagnosticsCard(), cardParams());
        normalDashboard.addView(
                buildDisclosureSection(getString(R.string.gps_and_diagnostics), bridgeDetails),
                cardParams());
        normalDashboard.addView(safetyNote(), matchWrap());
        content.addView(normalDashboard, matchWrap());

        anchorDashboard = vertical();
        anchorPurchasePreview = buildAnchorPurchasePreview();
        anchorDashboard.addView(anchorPurchasePreview, matchWrap());
        disarmedAnchorDashboard = vertical();
        disarmedAnchorDashboard.addView(buildAnchorAlarmCard(), cardParams());
        LinearLayout anchorDetails = vertical();
        anchorDetails.addView(buildAnchorDetailsCard(), cardParams());
        disarmedAnchorDashboard.addView(
                buildDisclosureSection(getString(R.string.watch_details), anchorDetails),
                cardParams());
        disarmedAnchorDashboard.addView(buildPairedAlarmCard(false), cardParams());
        anchorDashboard.addView(disarmedAnchorDashboard, matchWrap());

        armedAnchorDashboard = buildArmedAnchorDashboard();
        armedAnchorContainer = vertical();
        armedAnchorContainer.addView(armedAnchorDashboard, matchWrap());
        LinearLayout armedDetails = vertical();
        armedDetails.addView(buildAnchorDetailsCard(), cardParams());
        armedAnchorContainer.addView(
                buildDisclosureSection(getString(R.string.watch_details), armedDetails),
                cardParams());
        armedAnchorContainer.addView(buildPairedAlarmCard(true), cardParams());
        armedAnchorContainer.addView(safetyNote(), matchWrap());
        anchorDashboard.addView(armedAnchorContainer, matchWrap());
        content.addView(anchorDashboard, matchWrap());
        selectSection(SECTION_ANCHOR, false);
        return pageScrollView;
    }

    private LinearLayout buildAnchorPurchasePreview() {
        LinearLayout preview = vertical();

        LinearLayout introduction = card();
        TextView title = text(getString(R.string.anchor_watch_title), 28, R.color.nav_text);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        introduction.addView(title, matchWrap());
        TextView summary = text(getString(R.string.anchor_intro), 14, R.color.nav_secondary);
        summary.setLineSpacing(0.0f, 1.14f);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(7);
        introduction.addView(summary, summaryParams);
        addPurchaseBenefit(
                introduction,
                getString(R.string.two_anchoring_modes),
                getString(R.string.two_anchoring_modes_detail));
        addPurchaseBenefit(
                introduction,
                getString(R.string.durable_watch_history),
                getString(R.string.durable_watch_history_detail));
        addPurchaseBenefit(
                introduction,
                getString(R.string.local_safety_alarms),
                getString(R.string.local_safety_alarms_detail));
        preview.addView(introduction, cardParams());

        LinearLayout tiers = card();
        addCardHeader(tiers, getString(R.string.choose_your_level));
        addTierSummary(
                tiers,
                getString(R.string.nmea_stream_title),
                getString(R.string.free_tier),
                getString(R.string.nmea_free_detail));
        addDivider(tiers);
        anchorPurchasePrice = addTierSummary(
                tiers,
                getString(R.string.anchor_watch_title),
                getString(R.string.price_loading),
                getString(R.string.anchor_one_time_detail));
        addDivider(tiers);
        addTierSummary(
                tiers,
                getString(R.string.remote_anchor_watch_title),
                getString(R.string.optional_add_on),
                getString(R.string.remote_tier_detail));
        preview.addView(tiers, cardParams());

        LinearLayout checkout = card();
        addCardHeader(checkout, getString(R.string.unlock_anchor_watch));
        TextView ownership = text(
                getString(R.string.anchor_purchase_disclosure), 13, R.color.nav_secondary);
        ownership.setLineSpacing(0.0f, 1.12f);
        checkout.addView(ownership, matchWrap());

        anchorPurchaseButton = actionButton(
                getString(R.string.loading_google_play_price), R.color.nav_blue);
        anchorPurchaseButton.setEnabled(false);
        anchorPurchaseButton.setOnClickListener(view -> billingStore.purchaseAnchorWatch(this));
        LinearLayout.LayoutParams purchaseParams = matchWrap();
        purchaseParams.topMargin = dp(12);
        checkout.addView(anchorPurchaseButton, purchaseParams);

        anchorPurchaseStatus = text("", 13, R.color.nav_secondary);
        anchorPurchaseStatus.setLineSpacing(0.0f, 1.12f);
        anchorPurchaseStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        checkout.addView(anchorPurchaseStatus, statusParams);

        Button refresh = smallButton(
                getString(R.string.restore_refresh_purchases), () -> billingStore.refresh());
        LinearLayout.LayoutParams refreshParams = wrapWrap();
        refreshParams.topMargin = dp(6);
        checkout.addView(refresh, refreshParams);
        addReleasePolicyLinks(checkout);

        TextView safety = text(
                getString(R.string.anchor_auxiliary_warning), 12, R.color.nav_secondary);
        safety.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams safetyParams = matchWrap();
        safetyParams.topMargin = dp(8);
        checkout.addView(safety, safetyParams);

        Button shoreMonitor = smallButton(
                getString(R.string.use_as_shore_monitor),
                () -> startActivity(new Intent(this, ShoreMonitorActivity.class)));
        LinearLayout.LayoutParams shoreParams = wrapWrap();
        shoreParams.topMargin = dp(8);
        checkout.addView(shoreMonitor, shoreParams);
        preview.addView(checkout, cardParams());
        return preview;
    }

    private LinearLayout buildArmedAnchorDashboard() {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout plotCard = card();

        anchorPlotView = new AnchorPlotView(this);
        GradientDrawable plotBackground = new GradientDrawable();
        plotBackground.setColor(getColor(R.color.nav_background));
        plotBackground.setCornerRadius(dp(14));
        plotBackground.setStroke(dp(1), getColor(R.color.nav_plot_stroke));
        anchorPlotView.setBackground(plotBackground);
        LinearLayout.LayoutParams plotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plotCard.addView(anchorPlotView, plotParams);

        armedPowerSavingWarning = buildPowerSavingWarning();
        LinearLayout.LayoutParams powerWarningParams = matchWrap();
        powerWarningParams.topMargin = dp(12);
        plotCard.addView(armedPowerSavingWarning, powerWarningParams);

        armedGpsAccuracyWarning = buildGpsAccuracyWarning();
        LinearLayout.LayoutParams gpsWarningParams = matchWrap();
        gpsWarningParams.topMargin = dp(12);
        plotCard.addView(armedGpsAccuracyWarning, gpsWarningParams);

        armedAnchorPrimaryButton = actionButton(
                getString(R.string.arm_anchor_alarm_button), R.color.nav_blue);
        armedAnchorPrimaryButton.setOnClickListener(view -> handleActiveAnchorPrimaryAction());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(12);
        plotCard.addView(armedAnchorPrimaryButton, primaryParams);

        armedAnchorDistance = text("—", 42, R.color.nav_text);
        armedAnchorDistance.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorDistance.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams distanceParams = matchWrap();
        distanceParams.topMargin = dp(14);
        plotCard.addView(armedAnchorDistance, distanceParams);

        armedAnchorDistanceLabel = text(
                getString(R.string.distance_from_reference), 14, R.color.nav_secondary);
        armedAnchorDistanceLabel.setGravity(Gravity.CENTER);
        plotCard.addView(armedAnchorDistanceLabel, matchWrap());

        armedRadiusControls = horizontal();
        armedRadiusControls.setGravity(Gravity.CENTER_VERTICAL);
        armedAnchorRadiusLabel = text(getString(R.string.alarm_radius), 14, R.color.nav_secondary);
        armedRadiusControls.addView(armedAnchorRadiusLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        armedAnchorRadiusMinusButton = radiusStepButton("−", -1.0);
        armedAnchorRadiusMinusButton.setContentDescription(
                getString(R.string.decrease_alarm_radius));
        armedRadiusControls.addView(armedAnchorRadiusMinusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusValue = text(displayUnits.format(50.0), 15, R.color.nav_text);
        armedAnchorRadiusValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorRadiusValue.setGravity(Gravity.CENTER);
        armedRadiusControls.addView(armedAnchorRadiusValue,
                new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusPlusButton = radiusStepButton("+", 1.0);
        armedAnchorRadiusPlusButton.setContentDescription(
                getString(R.string.increase_alarm_radius));
        armedRadiusControls.addView(armedAnchorRadiusPlusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams radiusParams = matchWrap();
        radiusParams.topMargin = dp(10);
        plotCard.addView(armedRadiusControls, radiusParams);

        cancelAnchorSetupButton = outlinedDangerButton(
                getString(R.string.cancel_anchoring), this::cancelAnchorSetup);
        LinearLayout.LayoutParams cancelParams = matchWrap();
        cancelParams.topMargin = dp(12);
        plotCard.addView(cancelAnchorSetupButton, cancelParams);

        LinearLayout statusLine = horizontal();
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        armedAnchorStatusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.setMarginEnd(dp(10));
        statusLine.addView(armedAnchorStatusDot, dotParams);
        armedAnchorStatusTitle = text(
                getString(R.string.anchor_alarm_armed), 20, R.color.nav_text);
        armedAnchorStatusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusLine.addView(armedAnchorStatusTitle,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        armedAnchorMode = text(getString(R.string.shorelined), 12, R.color.nav_secondary);
        armedAnchorMode.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusLine.addView(armedAnchorMode, wrapWrap());
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(14);
        plotCard.addView(statusLine, statusParams);

        LinearLayout legend = horizontal();
        legend.setGravity(Gravity.CENTER);
        addLegendItem(legend, R.color.nav_blue_dark, getString(R.string.gps_trail));
        addLegendItem(legend, R.color.nav_success, getString(R.string.boat_now));
        addLegendItem(legend, R.color.nav_warning, getString(R.string.alarm_limit));
        LinearLayout.LayoutParams legendParams = matchWrap();
        legendParams.topMargin = dp(9);
        plotCard.addView(legend, legendParams);

        armedAnchorTrackSummary = text(
                getString(R.string.waiting_plotted_fixes), 12, R.color.nav_secondary);
        armedAnchorTrackSummary.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(5);
        plotCard.addView(armedAnchorTrackSummary, summaryParams);

        armedAnchorMessage = text("", 14, R.color.nav_error);
        armedAnchorMessage.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorMessage.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams messageParams = matchWrap();
        messageParams.topMargin = dp(11);
        plotCard.addView(armedAnchorMessage, messageParams);

        armedAnchorSilenceButton = actionButton(
                getString(R.string.silence_alarm), R.color.nav_warning);
        armedAnchorSilenceButton.setOnClickListener(view -> sendServiceAction(
                BridgeService.ACTION_SILENCE_ANCHOR));
        LinearLayout.LayoutParams silenceParams = matchWrap();
        silenceParams.topMargin = dp(10);
        plotCard.addView(armedAnchorSilenceButton, silenceParams);

        anchorPlotNote = text(getString(R.string.anchor_plot_note), 12, R.color.nav_secondary);
        anchorPlotNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(10);
        plotCard.addView(anchorPlotNote, noteParams);
        dashboard.addView(plotCard, cardParams());
        return dashboard;
    }

    private View buildStatusCard() {
        LinearLayout card = card();

        LinearLayout line = horizontal();
        line.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMarginEnd(dp(10));
        line.addView(statusDot, dotParams);
        statusTitle = text(getString(R.string.stopped), 17, R.color.nav_text);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(statusTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(line, matchWrap());

        LinearLayout metrics = horizontal();
        metrics.setGravity(Gravity.CENTER);
        bridgeGpsMetric = addMetric(metrics, getString(R.string.gps_acronym), "—");
        addMetricDivider(metrics);
        bridgeClientMetric = addMetric(
                metrics,
                getString(R.string.clients),
                getResources().getQuantityString(R.plurals.tcp_clients, 0, 0));
        addMetricDivider(metrics);
        TextView portMetric = addMetric(
                metrics, getString(R.string.port), String.valueOf(BridgeService.PORT));
        portMetric.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams metricsParams = matchWrap();
        metricsParams.topMargin = dp(16);
        card.addView(metrics, metricsParams);

        streamButton = new Button(this);
        streamButton.setAllCaps(false);
        streamButton.setTextSize(16);
        streamButton.setTextColor(Color.WHITE);
        streamButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        streamButton.setMinHeight(dp(52));
        streamButton.setOnClickListener(view -> toggleStreaming());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(16);
        card.addView(streamButton, buttonParams);

        errorText = text("", 13, R.color.nav_error);
        errorText.setPadding(0, dp(10), 0, 0);
        card.addView(errorText, matchWrap());

        settingsButton = smallButton(
                getString(R.string.open_location_settings), this::openAppSettings);
        LinearLayout.LayoutParams settingsParams = wrapWrap();
        settingsParams.topMargin = dp(5);
        card.addView(settingsButton, settingsParams);
        return card;
    }

    private View buildAnchorAlarmCard() {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        LinearLayout card = card();

        LinearLayout statusLine = horizontal();
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        anchorStatusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMarginEnd(dp(10));
        statusLine.addView(anchorStatusDot, dotParams);
        anchorStatusTitle = text(
                getString(R.string.anchor_alarm_ready), 17, R.color.nav_text);
        anchorStatusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusLine.addView(anchorStatusTitle,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(statusLine, matchWrap());

        anchorDescription = text("", 14, R.color.nav_secondary);
        anchorDescription.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams descriptionParams = matchWrap();
        descriptionParams.topMargin = dp(9);
        card.addView(anchorDescription, descriptionParams);

        LinearLayout modes = horizontal();
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable modesBackground = new GradientDrawable();
        modesBackground.setColor(getColor(R.color.nav_surface_muted));
        modesBackground.setCornerRadius(dp(13));
        modes.setBackground(modesBackground);
        shorelinedModeButton = modeButton(
                getString(R.string.shorelined), AnchorAlarm.Mode.SHORELINED);
        freeSwingingModeButton = modeButton(
                getString(R.string.free_swinging), AnchorAlarm.Mode.FREE_SWINGING);
        modes.addView(shorelinedModeButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        modes.addView(freeSwingingModeButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams modeParams = matchWrap();
        modeParams.topMargin = dp(10);
        card.addView(modes, modeParams);

        LinearLayout radiusLine = horizontal();
        radiusLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView radiusLabel = text(
                getString(R.string.alarm_radius), 14, R.color.nav_secondary);
        anchorRadiusValue = text(displayUnits.format(15.0), 15, R.color.nav_text);
        anchorRadiusValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        anchorRadiusValue.setGravity(Gravity.CENTER);
        radiusLine.addView(radiusLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        anchorRadiusMinusButton = radiusStepButton("−", -1.0);
        radiusLine.addView(anchorRadiusMinusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        radiusLine.addView(anchorRadiusValue,
                new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        anchorRadiusPlusButton = radiusStepButton("+", 1.0);
        radiusLine.addView(anchorRadiusPlusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams radiusLineParams = matchWrap();
        radiusLineParams.topMargin = dp(10);
        card.addView(radiusLine, radiusLineParams);

        anchorRadiusHint = text(displayUnits.range(
                AnchorAlarm.Mode.SHORELINED.minimumRadiusMeters,
                AnchorAlarm.Mode.SHORELINED.maximumRadiusMeters,
                AnchorAlarm.Mode.SHORELINED.radiusStepMeters), 12, R.color.nav_secondary);
        anchorRadiusHint.setGravity(Gravity.END);
        card.addView(anchorRadiusHint, matchWrap());

        freeSwingingWorkflow = vertical();
        freeSwingingWorkflow.setPadding(0, dp(10), 0, 0);
        anchorStepOneDetail = addWorkflowStep(
                freeSwingingWorkflow,
                "1",
                getString(R.string.mark_anchor_position),
                getString(R.string.mark_anchor_position_detail));
        addWorkflowStep(
                freeSwingingWorkflow,
                "2",
                getString(R.string.arm_after_rode),
                getString(R.string.arm_after_rode_detail));
        card.addView(freeSwingingWorkflow, matchWrap());

        anchorPowerSavingWarning = buildPowerSavingWarning();
        LinearLayout.LayoutParams powerWarningParams = matchWrap();
        powerWarningParams.topMargin = dp(12);
        card.addView(anchorPowerSavingWarning, powerWarningParams);

        anchorPrimaryButton = actionButton(
                getString(R.string.arm_anchor_alarm_button), R.color.nav_blue);
        anchorPrimaryButton.setOnClickListener(view -> toggleAnchorAlarm());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(14);
        card.addView(anchorPrimaryButton, primaryParams);

        silenceAnchorButton = actionButton(
                getString(R.string.silence_alarm), R.color.nav_warning);
        silenceAnchorButton.setOnClickListener(view -> sendServiceAction(
                BridgeService.ACTION_SILENCE_ANCHOR));
        LinearLayout.LayoutParams silenceParams = matchWrap();
        silenceParams.topMargin = dp(8);
        card.addView(silenceAnchorButton, silenceParams);

        anchorMessage = text("", 13, R.color.nav_secondary);
        anchorMessage.setLineSpacing(0.0f, 1.12f);
        anchorMessage.setPadding(0, dp(9), 0, 0);
        card.addView(anchorMessage, matchWrap());

        return card;
    }

    private View buildGpsCard() {
        LinearLayout card = card();
        addCardHeader(card, getString(R.string.gps_fix));
        permissionValue = addDetailRow(card, getString(R.string.permission), false);
        positionValue = addDetailRow(card, getString(R.string.position), true);
        accuracyValue = addDetailRow(card, getString(R.string.accuracy), false);
        fixAgeValue = addDetailRow(card, getString(R.string.fix_age), false);
        speedValue = addDetailRow(card, getString(R.string.speed), false);
        courseValue = addDetailRow(card, getString(R.string.course_over_ground), false);
        return card;
    }

    private View buildPairedAlarmCard(boolean armedCard) {
        LinearLayout card = card();
        addCardHeader(card, getString(R.string.optional_shore_monitor));
        TextView description = text(
                getString(R.string.shore_monitor_detail), 13, R.color.nav_secondary);
        description.setLineSpacing(0.0f, 1.12f);
        card.addView(description, matchWrap());
        TextView status = addDetailRow(card, getString(R.string.status), false);
        if (armedCard) armedShoreMonitorValue = status;
        else normalShoreMonitorValue = status;

        Button pair = smallButton(getString(R.string.set_up_pairing_code), () ->
                startActivity(new Intent(this, AnchorPairingActivity.class)));
        LinearLayout.LayoutParams pairParams = wrapWrap();
        pairParams.topMargin = dp(10);
        card.addView(pair, pairParams);

        LinearLayout remoteOffer = vertical();
        addDivider(remoteOffer);
        TextView offerTitle = text(
                getString(R.string.add_remote_anchor_watch), 16, R.color.nav_text);
        offerTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        remoteOffer.addView(offerTitle, matchWrap());
        TextView requirement = text(
                getString(R.string.separate_android_required), 14, R.color.nav_text);
        requirement.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams requirementParams = matchWrap();
        requirementParams.topMargin = dp(8);
        remoteOffer.addView(requirement, requirementParams);
        TextView connectivity = text(
                getString(R.string.direct_transport_detail), 12, R.color.nav_secondary);
        connectivity.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams connectivityParams = matchWrap();
        connectivityParams.topMargin = dp(6);
        remoteOffer.addView(connectivity, connectivityParams);

        CheckBox companionConfirmation = new CheckBox(this);
        companionConfirmation.setText(R.string.android_companion_confirmation);
        companionConfirmation.setTextSize(13);
        companionConfirmation.setTextColor(getColor(R.color.nav_text));
        LinearLayout.LayoutParams confirmationParams = matchWrap();
        confirmationParams.topMargin = dp(8);
        remoteOffer.addView(companionConfirmation, confirmationParams);

        Button monthly = actionButton(
                getString(R.string.monthly_price_loading), R.color.nav_secondary);
        monthly.setEnabled(false);
        monthly.setOnClickListener(view -> billingStore.purchaseRemote(
                this,
                StoreProductIdentifiers.REMOTE_MONTHLY_BASE_PLAN,
                companionConfirmation.isChecked()));
        LinearLayout.LayoutParams monthlyParams = matchWrap();
        monthlyParams.topMargin = dp(8);
        remoteOffer.addView(monthly, monthlyParams);

        Button annual = actionButton(
                getString(R.string.annual_price_loading), R.color.nav_blue);
        annual.setEnabled(false);
        annual.setOnClickListener(view -> billingStore.purchaseRemote(
                this,
                StoreProductIdentifiers.REMOTE_ANNUAL_BASE_PLAN,
                companionConfirmation.isChecked()));
        LinearLayout.LayoutParams annualParams = matchWrap();
        annualParams.topMargin = dp(8);
        remoteOffer.addView(annual, annualParams);

        TextView purchaseStatus = text("", 13, R.color.nav_secondary);
        purchaseStatus.setLineSpacing(0.0f, 1.12f);
        purchaseStatus.setVisibility(View.GONE);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(8);
        remoteOffer.addView(purchaseStatus, statusParams);

        TextView renewal = text(
                getString(R.string.remote_subscription_disclosure),
                12,
                R.color.nav_secondary);
        renewal.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams renewalParams = matchWrap();
        renewalParams.topMargin = dp(8);
        remoteOffer.addView(renewal, renewalParams);

        Button refresh = smallButton(
                getString(R.string.restore_refresh_purchases), () -> billingStore.refresh());
        remoteOffer.addView(refresh, wrapWrap());

        Button manage = smallButton(
                getString(R.string.manage_google_play_subscription),
                this::openPlaySubscriptionManagement);
        card.addView(manage, wrapWrap());

        Button monitor = smallButton(getString(R.string.use_as_shore_monitor), () ->
                startActivity(new Intent(this, ShoreMonitorActivity.class)));
        LinearLayout.LayoutParams monitorParams = wrapWrap();
        monitorParams.topMargin = dp(6);
        card.addView(monitor, monitorParams);
        addReleasePolicyLinks(card);

        card.addView(remoteOffer, matchWrap());
        RemoteOfferViews views = new RemoteOfferViews(
                remoteOffer,
                companionConfirmation,
                monthly,
                annual,
                purchaseStatus,
                pair,
                manage);
        remoteOfferViews.add(views);
        companionConfirmation.setOnCheckedChangeListener(
                (button, checked) -> render(latestState));
        return card;
    }

    private View buildNetworkCard() {
        LinearLayout card = card();
        addCardHeader(card, getString(R.string.nmea_stream_connection));
        TextView summary = text(
                getString(R.string.nmea_connection_detail), 14, R.color.nav_secondary);
        summary.setLineSpacing(0.0f, 1.12f);
        card.addView(summary, matchWrap());
        TextView protocolValue = addDetailRow(card, getString(R.string.protocol), false);
        protocolValue.setText(R.string.udp_recommended_tcp_fallback);
        TextView portValue = addDetailRow(card, getString(R.string.port), true);
        portValue.setText(String.valueOf(BridgeService.PORT));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.nav_divider));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);

        TextView hint = text(
                getString(R.string.tcp_fallback_addresses), 14, R.color.nav_secondary);
        card.addView(hint, matchWrap());

        addressesContainer = new LinearLayout(this);
        addressesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams addressesParams = matchWrap();
        addressesParams.topMargin = dp(6);
        card.addView(addressesContainer, addressesParams);

        Button refresh = smallButton(getString(R.string.refresh_addresses), () -> {
            if (binder != null) binder.refreshNetworkAddresses();
        });
        LinearLayout.LayoutParams refreshParams = wrapWrap();
        refreshParams.topMargin = dp(7);
        card.addView(refresh, refreshParams);
        TextView compatibility = text(
                getString(R.string.navionics_compatibility), 12, R.color.nav_secondary);
        compatibility.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams compatibilityParams = matchWrap();
        compatibilityParams.topMargin = dp(10);
        card.addView(compatibility, compatibilityParams);
        return card;
    }

    private View buildInstructionsCard() {
        LinearLayout card = card();
        addCardHeader(card, getString(R.string.set_up_navigation_app));
        addInstruction(card, 1, getString(R.string.navigation_instruction_hotspot));
        addInstruction(card, 2, getString(R.string.navigation_instruction_permissions));
        addInstruction(card, 3, getString(R.string.navigation_instruction_navionics));
        addInstruction(
                card,
                4,
                getString(R.string.navigation_instruction_udp, BridgeService.PORT));
        addInstruction(card, 5, getString(R.string.navigation_instruction_save));
        addInstruction(card, 6, getString(R.string.navigation_instruction_power));
        return card;
    }

    private View buildDiagnosticsCard() {
        LinearLayout card = card();
        addCardHeader(card, getString(R.string.network_and_nmea));
        clientsValue = addDetailRow(card, getString(R.string.tcp_clients_label), false);
        udpTargetsValue = addDetailRow(card, getString(R.string.udp_networks_label), false);
        serverValue = addDetailRow(card, getString(R.string.tcp_server_label), false);
        TextView lastTransmission = text(
                getString(R.string.last_nmea_transmission), 14, R.color.nav_text);
        lastTransmission.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams transmissionParams = matchWrap();
        transmissionParams.topMargin = dp(14);
        transmissionParams.bottomMargin = dp(7);
        card.addView(lastTransmission, transmissionParams);
        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setFillViewport(true);
        sentencesValue = text(
                getString(R.string.sentences_placeholder), 12, R.color.nav_secondary);
        sentencesValue.setTypeface(Typeface.MONOSPACE);
        sentencesValue.setTextIsSelectable(true);
        horizontalScroll.addView(sentencesValue, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(horizontalScroll, matchWrap());
        return card;
    }

    private void render(BridgeState state) {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        boolean armedScreen = state.anchorAlarmArmed
                || state.anchorSetupActive
                || anchorSetupRequested;
        boolean ownsAnchorWatch = billingSnapshot != null
                && billingSnapshot.entitlementDecision.featureLevel.includes(
                        EntitlementPolicy.FeatureLevel.ANCHOR_WATCH);
        boolean canUseRemoteAnchorWatch = billingSnapshot != null
                && billingSnapshot.entitlementDecision.featureLevel.includes(
                        EntitlementPolicy.FeatureLevel.REMOTE_ANCHOR_WATCH);
        if (binder != null && binder.canUseRemoteAnchorWatch()) {
            canUseRemoteAnchorWatch = true;
        }
        anchorPurchasePreview.setVisibility(
                !ownsAnchorWatch && !armedScreen ? View.VISIBLE : View.GONE);
        disarmedAnchorDashboard.setVisibility(
                ownsAnchorWatch && !armedScreen ? View.VISIBLE : View.GONE);
        armedAnchorContainer.setVisibility(armedScreen ? View.VISIBLE : View.GONE);
        renderBillingUi(ownsAnchorWatch, canUseRemoteAnchorWatch);
        if (armedScreen && !lastRenderedAnchorArmed) {
            selectSection(SECTION_ANCHOR, true);
        }
        if (state.anchorAlarmTriggered && !lastRenderedAnchorTriggered) {
            selectSection(SECTION_ANCHOR, true);
        }
        if (armedScreen != lastRenderedAnchorArmed) {
            lastRenderedAnchorArmed = armedScreen;
            pageScrollView.post(() -> pageScrollView.scrollTo(0, 0));
        }
        lastRenderedAnchorTriggered = state.anchorAlarmTriggered;

        boolean fineLocation = hasFineLocationPermission();
        boolean active = state.streaming;
        statusTitle.setText(statusTitle(state, fineLocation));
        setCircleColor(statusDot, statusColor(state, fineLocation));
        statusDot.setContentDescription(statusTitle.getText());

        streamButton.setText(active
                ? R.string.stop_streaming_button : R.string.start_streaming_button);
        styleActionButton(streamButton, active ? R.color.nav_error : R.color.nav_blue);
        renderAnchorAlarm(state);
        renderArmedAnchorDashboard(state);
        renderAnchorDetails(state, displayUnits);
        renderPowerSavingWarnings();
        renderShoreMonitorStatus(state);
        updateAnchorReadinessDialog();
        updateDisplayUnitButtons();

        String visibleError = state.error != null ? state.error : permissionError;
        errorText.setText(visibleError == null ? "" : visibleError);
        errorText.setVisibility(visibleError == null ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(fineLocation ? View.GONE : View.VISIBLE);
        bridgeGpsMetric.setText(state.hasLocation && state.accuracyMeters >= 0.0
                ? displayUnits.formatAccuracy(state.accuracyMeters)
                : getString(R.string.waiting));
        bridgeClientMetric.setText(getResources().getQuantityString(
                R.plurals.tcp_clients,
                state.clientCount,
                state.clientCount));
        updateSectionButtons(state);

        permissionValue.setText(permissionDescription());
        if (state.hasLocation) {
            positionValue.setText(String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude));
            accuracyValue.setText(state.accuracyMeters >= 0.0
                    ? displayUnits.formatAccuracy(state.accuracyMeters) : "—");
            fixAgeValue.setText(getString(R.string.seconds_value, state.fixAgeSeconds));
            speedValue.setText(state.speedMetersPerSecond >= 0.0
                    ? getString(
                            R.string.knots_value,
                            state.speedMetersPerSecond * 1.9438444924)
                    : "—");
            courseValue.setText(state.courseDegrees >= 0.0
                    ? getString(R.string.degrees_value, state.courseDegrees) : "—");
        } else {
            positionValue.setText("—");
            accuracyValue.setText("—");
            fixAgeValue.setText("—");
            speedValue.setText("—");
            courseValue.setText("—");
        }

        clientsValue.setText(String.valueOf(state.clientCount));
        udpTargetsValue.setText(state.udpBroadcastTargetCount == 0
                ? getString(R.string.waiting_for_hotspot)
                : getResources().getQuantityString(
                        R.plurals.active_udp_networks,
                        state.udpBroadcastTargetCount,
                        state.udpBroadcastTargetCount));
        serverValue.setText(state.listenerDescription);
        renderAddresses(state.networkAddresses);

        if (state.lastSentences.isEmpty()) {
            sentencesValue.setText(R.string.sentences_placeholder);
            sentencesValue.setTextColor(getColor(R.color.nav_secondary));
        } else {
            sentencesValue.setText(state.lastSentences);
            sentencesValue.setTextColor(getColor(R.color.nav_text));
        }

        if (active || armedScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderBillingUi(
            boolean ownsAnchorWatch,
            boolean canUseRemoteAnchorWatch
    ) {
        PlayBillingStore.ProductOffer anchorOffer = billingSnapshot == null
                ? null : billingSnapshot.anchorWatchOffer;
        boolean billingBusy = billingSnapshot != null
                && (billingSnapshot.actionState == PlayBillingStore.ActionState.CONNECTING
                || billingSnapshot.actionState == PlayBillingStore.ActionState.PURCHASING);

        String anchorPrice = anchorOffer == null
                ? getString(R.string.price_loading) : anchorOffer.formattedPrice;
        anchorPurchasePrice.setText(anchorPrice);
        anchorPurchaseButton.setText(anchorOffer == null
                ? getString(R.string.loading_google_play_price)
                : getString(R.string.unlock_anchor_watch_price, anchorOffer.formattedPrice));
        anchorPurchaseButton.setEnabled(anchorOffer != null && !billingBusy);

        String billingMessage = billingStatusMessage(false);
        setBillingStatus(
                anchorPurchaseStatus,
                billingMessage,
                billingStatusColor());

        for (RemoteOfferViews views : remoteOfferViews) {
            views.offerContainer.setVisibility(
                    ownsAnchorWatch && !canUseRemoteAnchorWatch
                            ? View.VISIBLE : View.GONE);
            views.pairingButton.setVisibility(
                    canUseRemoteAnchorWatch ? View.VISIBLE : View.GONE);
            views.manageButton.setVisibility(ownsAnchorWatch ? View.VISIBLE : View.GONE);

            PlayBillingStore.ProductOffer monthlyOffer = billingSnapshot == null
                    ? null : billingSnapshot.remoteMonthlyOffer;
            PlayBillingStore.ProductOffer annualOffer = billingSnapshot == null
                    ? null : billingSnapshot.remoteAnnualOffer;
            views.monthlyButton.setText(monthlyOffer == null
                    ? getString(R.string.monthly_price_loading)
                    : getString(R.string.monthly_price, monthlyOffer.formattedPrice));
            views.annualButton.setText(annualOffer == null
                    ? getString(R.string.annual_price_loading)
                    : getString(R.string.annual_price, annualOffer.formattedPrice));
            boolean checkoutConfirmed = views.companionConfirmation.isChecked();
            views.monthlyButton.setEnabled(
                    monthlyOffer != null && checkoutConfirmed && !billingBusy);
            views.annualButton.setEnabled(
                    annualOffer != null && checkoutConfirmed && !billingBusy);

            String remoteMessage = billingStatusMessage(canUseRemoteAnchorWatch);
            setBillingStatus(
                    views.purchaseStatus,
                    remoteMessage,
                    canUseRemoteAnchorWatch
                            ? R.color.nav_success : billingStatusColor());
        }
    }

    private String billingStatusMessage(boolean remoteActive) {
        if (billingSnapshot == null) return getString(R.string.connecting_google_play);
        switch (billingSnapshot.actionState) {
            case CONNECTING:
                return getString(R.string.connecting_google_play);
            case PURCHASING:
                return getString(R.string.waiting_google_play);
            case PENDING:
                return billingSnapshot.message == null
                        ? getString(R.string.purchase_pending)
                        : billingSnapshot.message;
            case CANCELLED:
                return getString(R.string.purchase_cancelled);
            case ERROR:
                return billingSnapshot.message == null
                        ? getString(R.string.google_play_request_failed)
                        : billingSnapshot.message;
            case IDLE:
            default:
                return remoteActive ? getString(R.string.remote_subscription_active) : null;
        }
    }

    private int billingStatusColor() {
        if (billingSnapshot == null) return R.color.nav_secondary;
        if (billingSnapshot.actionState == PlayBillingStore.ActionState.ERROR) {
            return R.color.nav_error;
        }
        if (billingSnapshot.actionState == PlayBillingStore.ActionState.PENDING) {
            return R.color.nav_warning;
        }
        return R.color.nav_secondary;
    }

    private void setBillingStatus(TextView view, String message, int colorResource) {
        view.setText(message == null ? "" : message);
        view.setTextColor(getColor(colorResource));
        view.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void renderAnchorAlarm(BridgeState state) {
        selectedAnchorMode = state.anchorMode;
        selectedAnchorRadiusMeters = state.anchorRadiusMeters;
        updateRadiusControls();
        updateModeButtons();

        boolean armed = state.anchorAlarmArmed;
        boolean setupActive = state.anchorSetupActive || anchorSetupRequested;
        boolean triggered = state.anchorAlarmTriggered;
        if (triggered) {
            anchorStatusTitle.setText(R.string.anchor_alarm);
            setCircleColor(anchorStatusDot, getColor(R.color.nav_error));
        } else if (state.anchorAlarmSilenced) {
            anchorStatusTitle.setText(R.string.alarm_silenced_still_monitoring);
            setCircleColor(anchorStatusDot, getColor(R.color.nav_warning));
        } else if (armed) {
            anchorStatusTitle.setText(getString(
                    R.string.armed_mode, anchorModeDisplayName(state.anchorMode)));
            setCircleColor(anchorStatusDot, getColor(R.color.nav_success));
        } else if (state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !state.hasAnchorReference) {
            anchorStatusTitle.setText(R.string.record_anchor_position);
            setCircleColor(anchorStatusDot, getColor(R.color.nav_blue));
        } else {
            anchorStatusTitle.setText(R.string.anchor_alarm_ready);
            setCircleColor(anchorStatusDot, getColor(R.color.nav_blue));
        }
        anchorStatusDot.setContentDescription(anchorStatusTitle.getText());

        if (state.anchorMode == AnchorAlarm.Mode.SHORELINED) {
            anchorDescription.setText(R.string.shorelined_description);
        } else {
            anchorDescription.setText(R.string.free_swinging_description);
        }

        shorelinedModeButton.setEnabled(!armed && !setupActive);
        freeSwingingModeButton.setEnabled(!armed && !setupActive);
        anchorRadiusMinusButton.setEnabled(!armed && !setupActive);
        anchorRadiusPlusButton.setEnabled(!armed && !setupActive);
        freeSwingingWorkflow.setVisibility(
                state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING ? View.VISIBLE : View.GONE);
        anchorStepOneDetail.setText(state.hasAnchorReference
                ? getString(R.string.position_recorded_continue)
                : pendingPermissionAction == PENDING_MARK_ANCHOR
                ? getString(R.string.waiting_accurate_gps)
                : getString(R.string.mark_anchor_position_detail));

        if (armed) {
            anchorPrimaryButton.setText(R.string.disarm_anchor_watch_button);
            styleActionButton(anchorPrimaryButton, R.color.nav_error);
        } else if (requiresNewAnchorReferenceFix(pendingPermissionAction)) {
            anchorPrimaryButton.setText(R.string.acquiring_gps_position_button);
            styleActionButton(anchorPrimaryButton, R.color.nav_blue);
        } else if (state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !state.hasAnchorReference) {
            anchorPrimaryButton.setText(R.string.drop_anchor_button);
            styleActionButton(anchorPrimaryButton, R.color.nav_blue);
        } else {
            anchorPrimaryButton.setText(R.string.arm_anchor_alarm_button);
            styleActionButton(anchorPrimaryButton, R.color.nav_blue);
        }
        silenceAnchorButton.setVisibility(triggered ? View.VISIBLE : View.GONE);

        String message = state.anchorAlertReason != null
                ? state.anchorAlertReason : anchorActionMessage;
        anchorMessage.setText(message == null ? "" : message);
        anchorMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
    }

    private void renderShoreMonitorStatus(BridgeState state) {
        String value;
        int color;
        if (state.shoreMonitorConnected && state.shoreMonitorSoundReady) {
            value = getString(R.string.connected_sound_ready);
            color = R.color.nav_success;
        } else if (state.shoreMonitorConnected) {
            value = getString(R.string.connected_setup_needed);
            color = R.color.nav_warning;
        } else {
            value = getString(R.string.no_shore_monitor_connected);
            color = R.color.nav_secondary;
        }
        if (normalShoreMonitorValue != null) {
            normalShoreMonitorValue.setText(value);
            normalShoreMonitorValue.setTextColor(getColor(color));
        }
        if (armedShoreMonitorValue != null) {
            armedShoreMonitorValue.setText(value);
            armedShoreMonitorValue.setTextColor(getColor(color));
        }
    }

    private void renderArmedAnchorDashboard(BridgeState state) {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        boolean setupActive = state.anchorSetupActive || anchorSetupRequested;
        if (state.anchorAlarmTriggered) {
            armedAnchorStatusTitle.setText(R.string.anchor_alarm);
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_error));
        } else if (state.anchorAlarmSilenced) {
            armedAnchorStatusTitle.setText(R.string.alarm_silenced_still_monitoring);
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_warning));
        } else if (setupActive && !state.hasAnchorReference) {
            armedAnchorStatusTitle.setText(R.string.acquiring_anchor_position);
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_blue));
        } else if (setupActive) {
            armedAnchorStatusTitle.setText(R.string.laying_anchor);
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_blue));
        } else {
            armedAnchorStatusTitle.setText(getString(
                    R.string.armed_mode, anchorModeDisplayName(state.anchorMode)));
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_success));
        }
        armedAnchorMode.setText(anchorModeDisplayName(state.anchorMode));
        armedAnchorDistance.setText(Double.isFinite(state.anchorDistanceMeters)
                ? displayUnits.format(state.anchorDistanceMeters, 1) : "—");
        armedAnchorDistance.setTextColor(getColor(
                state.anchorAlarmTriggered ? R.color.nav_error : R.color.nav_text));
        armedAnchorDistanceLabel.setText(getString(
                R.string.distance_from_reference_limit,
                displayUnits.format(state.anchorRadiusMeters)));
        boolean radiusAdjustable = state.anchorAlarmArmed
                || (setupActive && state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING);
        armedRadiusControls.setVisibility(radiusAdjustable ? View.VISIBLE : View.GONE);
        armedAnchorRadiusLabel.setText(state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                ? R.string.swing_radius : R.string.alarm_radius);
        armedAnchorRadiusValue.setText(displayUnits.format(state.anchorRadiusMeters));
        armedAnchorRadiusMinusButton.setEnabled(
                state.anchorRadiusMeters > state.anchorMode.minimumRadiusMeters);
        armedAnchorRadiusPlusButton.setEnabled(
                state.anchorRadiusMeters < state.anchorMode.maximumRadiusMeters);
        anchorPlotView.setDisplayUnits(displayUnits);
        anchorPlotView.setState(state);
        String accuracy = state.hasLocation && state.accuracyMeters >= 0.0
                ? getString(
                        R.string.gps_accuracy_suffix,
                        displayUnits.formatAccuracy(state.accuracyMeters))
                : "";
        armedAnchorTrackSummary.setText(getResources().getQuantityString(
                R.plurals.anchor_track_summary,
                state.anchorTrackPoints.size(),
                displayUnits.format(state.anchorRadiusMeters),
                state.anchorTrackPoints.size(),
                accuracy));
        String gpsWarning = state.hasLocation && state.fixAgeSeconds <= STALE_FIX_SECONDS
                ? AnchorGpsQuality.degradedWarning(
                        state.accuracyMeters,
                        state.anchorRadiusMeters,
                        displayUnits,
                        this)
                : null;
        armedGpsAccuracyWarningBody.setText(gpsWarning == null ? "" : gpsWarning);
        armedGpsAccuracyWarning.setVisibility(
                gpsWarning == null ? View.GONE : View.VISIBLE);
        String message = state.anchorAlertReason != null
                ? state.anchorAlertReason : setupActive ? anchorActionMessage : null;
        armedAnchorMessage.setText(message == null ? "" : message);
        armedAnchorMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        armedAnchorMessage.setTextColor(getColor(
                state.anchorAlarmTriggered ? R.color.nav_error
                        : setupActive ? R.color.nav_secondary : R.color.nav_warning));
        armedAnchorSilenceButton.setVisibility(
                state.anchorAlarmTriggered ? View.VISIBLE : View.GONE);
        armedAnchorPrimaryButton.setText(setupActive
                ? state.hasAnchorReference
                ? getString(R.string.arm_anchor_alarm_button)
                : getString(R.string.acquiring_anchor_position_button)
                : getString(R.string.disarm_anchor_watch_button));
        armedAnchorPrimaryButton.setEnabled(!setupActive || state.hasAnchorReference);
        styleActionButton(armedAnchorPrimaryButton,
                setupActive ? R.color.nav_blue : R.color.nav_error);
        cancelAnchorSetupButton.setVisibility(setupActive ? View.VISIBLE : View.GONE);
        anchorPlotNote.setText(setupActive
                ? R.string.anchor_laying_plot_note : R.string.anchor_plot_note);
    }

    private void renderAnchorDetails(BridgeState state, DisplayUnits displayUnits) {
        String reference = state.hasAnchorReference
                ? String.format(
                        Locale.US,
                        "%.6f, %.6f",
                        state.anchorReferenceLatitude,
                        state.anchorReferenceLongitude)
                : getString(R.string.not_set);
        String distance = state.hasAnchorReference
                && Double.isFinite(state.anchorDistanceMeters)
                ? getString(
                        R.string.distance_and_limit,
                        displayUnits.format(state.anchorDistanceMeters, 1),
                        displayUnits.format(state.anchorRadiusMeters))
                : "—";
        for (TextView value : anchorReferenceValues) value.setText(reference);
        for (TextView value : anchorDistanceValues) value.setText(distance);
    }

    private void renderAddresses(List<BridgeState.NetworkAddress> addresses) {
        addressesContainer.removeAllViews();
        if (addresses.isEmpty()) {
            TextView empty = text(
                    getString(R.string.no_wifi_hotspot_address), 14, R.color.nav_warning);
            addressesContainer.addView(empty, matchWrap());
            return;
        }
        for (BridgeState.NetworkAddress item : addresses) {
            LinearLayout row = horizontal();
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(item.interfaceName, 14, R.color.nav_secondary);
            TextView address = text(item.address, 15, R.color.nav_text);
            address.setTypeface(Typeface.MONOSPACE);
            address.setTextIsSelectable(true);
            address.setGravity(Gravity.END);
            row.addView(name, wrapWrap());
            row.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.topMargin = dp(7);
            addressesContainer.addView(row, rowParams);
        }
    }

    private void toggleStreaming() {
        permissionError = null;
        if (latestState.streaming) {
            sendServiceAction(BridgeService.ACTION_STOP);
            return;
        }
        beginPermissionProtectedAction(PENDING_STREAM);
    }

    private void toggleAnchorAlarm() {
        anchorActionMessage = null;
        if (latestState.anchorAlarmArmed) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.disarm_anchor_watch_question)
                    .setMessage(R.string.disarm_anchor_watch_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.disarm, (dialog, which) -> {
                        pendingPermissionAction = PENDING_NONE;
                        sendServiceAction(BridgeService.ACTION_DISARM_ANCHOR);
                    })
                    .show();
            return;
        }
        continueAnchorPrimaryAction();
    }

    private void continueAnchorPrimaryAction() {
        if (latestState.anchorSetupActive || anchorSetupRequested) {
            if (latestState.hasAnchorReference) {
                beginAnchorArmWithReadiness();
            }
            return;
        }
        if (selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !latestState.hasAnchorReference) {
            beginMarkAnchor();
            return;
        }
        beginAnchorArmWithReadiness();
    }

    private void beginAnchorArmWithReadiness() {
        if (!isAnchorReadinessComplete()) {
            showAnchorReadiness(true);
            return;
        }
        beginPermissionProtectedAction(PENDING_ARM_ANCHOR);
    }

    private void showAnchorReadiness(boolean resumeArming) {
        resumeArmingAfterReadiness = resumeArmingAfterReadiness || resumeArming;
        readinessSirenPlayedThisVisit = false;
        readinessSirenError = null;
        stopReadinessSiren();
        if (anchorReadinessDialog != null) {
            updateAnchorReadinessDialog();
            return;
        }

        LinearLayout content = vertical();
        content.setPadding(dp(20), dp(8), dp(20), dp(8));

        TextView introduction = text(
                getString(R.string.readiness_intro), 14, R.color.nav_secondary);
        introduction.setLineSpacing(0.0f, 1.14f);
        content.addView(introduction, matchWrap());

        readinessLocationRow = buildReadinessRow(
                getString(R.string.precise_location),
                getString(R.string.precise_gps_required),
                getString(R.string.allow_location),
                this::requestReadinessLocationPermission);
        content.addView(readinessLocationRow.container, readinessRowParams());

        readinessBackgroundRow = buildReadinessRow(
                getString(R.string.screen_locked_gps),
                getString(R.string.screen_locked_gps_detail),
                null,
                null);
        content.addView(readinessBackgroundRow.container, readinessRowParams());

        readinessNotificationRow = buildReadinessRow(
                getString(R.string.notifications),
                getString(R.string.alarm_notifications_required),
                getString(R.string.allow_notifications),
                this::requestReadinessNotificationPermission);
        content.addView(readinessNotificationRow.container, readinessRowParams());

        readinessDndRow = buildReadinessRow(
                getString(R.string.dnd_access),
                getString(R.string.dnd_access_detail),
                getString(R.string.open_dnd_access),
                this::openDoNotDisturbAccessSettings);
        content.addView(readinessDndRow.container, readinessRowParams());

        readinessPowerRow = buildReadinessRow(
                getString(R.string.power_settings),
                getString(R.string.power_settings_detail),
                getString(R.string.open_power_settings),
                this::openAnchorPowerSettings);
        content.addView(readinessPowerRow.container, readinessRowParams());

        LinearLayout sirenSection = vertical();
        TextView sirenTitle = text(
                getString(R.string.alarm_sound_test), 15, R.color.nav_text);
        sirenTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sirenSection.addView(sirenTitle, matchWrap());
        readinessSirenStatus = text(
                getString(R.string.siren_test_detail),
                13,
                R.color.nav_secondary);
        readinessSirenStatus.setLineSpacing(0.0f, 1.1f);
        LinearLayout.LayoutParams sirenStatusParams = matchWrap();
        sirenStatusParams.topMargin = dp(3);
        sirenSection.addView(readinessSirenStatus, sirenStatusParams);
        readinessSirenButton = smallButton(
                getString(R.string.play_siren),
                this::playReadinessSiren);
        sirenSection.addView(readinessSirenButton, wrapWrap());
        readinessSirenConfirmationButton = smallButton(
                getString(R.string.heard_siren),
                this::confirmReadinessSirenHeard);
        sirenSection.addView(readinessSirenConfirmationButton, wrapWrap());
        TextView soundLimitation = text(
                getString(R.string.siren_test_warning), 12, R.color.nav_secondary);
        soundLimitation.setLineSpacing(0.0f, 1.1f);
        sirenSection.addView(soundLimitation, matchWrap());
        content.addView(sirenSection, readinessRowParams());

        readinessSafetyAcknowledgement = new CheckBox(this);
        readinessSafetyAcknowledgement.setText(R.string.safety_acknowledgement);
        readinessSafetyAcknowledgement.setTextSize(13);
        readinessSafetyAcknowledgement.setTextColor(getColor(R.color.nav_text));
        readinessSafetyAcknowledgement.setChecked(readinessPreferences().getBoolean(
                KEY_READINESS_SAFETY_ACKNOWLEDGED,
                false));
        readinessSafetyAcknowledgement.setOnCheckedChangeListener((button, checked) -> {
            SharedPreferences preferences = readinessPreferences();
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(KEY_READINESS_SAFETY_ACKNOWLEDGED, checked);
            if (!checked) editor.putBoolean(KEY_READINESS_COMPLETE, false);
            editor.apply();
            updateAnchorReadinessDialog();
        });
        content.addView(readinessSafetyAcknowledgement, readinessRowParams());

        TextView lifecycleWarning = text(
                getString(R.string.foreground_service_warning), 12, R.color.nav_secondary);
        lifecycleWarning.setLineSpacing(0.0f, 1.12f);
        content.addView(lifecycleWarning, readinessRowParams());

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        anchorReadinessDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.safety_readiness)
                .setView(scrollView)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(
                        resumeArmingAfterReadiness
                                ? getString(R.string.continue_and_arm)
                                : getString(R.string.save_readiness),
                        null)
                .create();
        anchorReadinessDialog.setOnShowListener(ignored -> {
            readinessCompleteButton = anchorReadinessDialog.getButton(
                    AlertDialog.BUTTON_POSITIVE);
            readinessCompleteButton.setOnClickListener(view ->
                    completeAnchorReadinessAndContinue());
            updateAnchorReadinessDialog();
        });
        anchorReadinessDialog.setOnDismissListener(ignored -> {
            anchorReadinessDialog = null;
            readinessCompleteButton = null;
            resumeArmingAfterReadiness = false;
            stopReadinessSiren();
        });
        anchorReadinessDialog.show();
    }

    private ReadinessRowViews buildReadinessRow(
            String title,
            String detail,
            String actionTitle,
            Runnable action
    ) {
        LinearLayout container = vertical();
        TextView titleView = text(title, 15, R.color.nav_text);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        container.addView(titleView, matchWrap());
        TextView detailView = text(detail, 13, R.color.nav_secondary);
        detailView.setLineSpacing(0.0f, 1.1f);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(3);
        container.addView(detailView, detailParams);
        Button actionButton = null;
        if (actionTitle != null && action != null) {
            actionButton = smallButton(actionTitle, action);
            container.addView(actionButton, wrapWrap());
        }
        return new ReadinessRowViews(container, titleView, detailView, actionButton);
    }

    private LinearLayout.LayoutParams readinessRowParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(16);
        return params;
    }

    private void updateAnchorReadinessDialog() {
        boolean complete = isAnchorReadinessComplete();
        for (Button button : readinessStatusButtons) {
            button.setText(complete
                    ? R.string.readiness_ready_test_again
                    : R.string.readiness_needs_attention);
            button.setTextColor(getColor(complete ? R.color.nav_success : R.color.nav_warning));
        }
        if (anchorReadinessDialog == null) return;
        syncAnchorAlarmChannelDndBypass();

        boolean locationReady = hasFineLocationPermission();
        boolean notificationReady = areAnchorNotificationsReady();
        boolean dndReady = hasDoNotDisturbAccess();
        boolean powerReady = !isLocationRestrictedByPowerSaving();
        boolean sirenConfirmed = readinessPreferences().getBoolean(
                KEY_READINESS_SIREN_CONFIRMED,
                false);

        updateReadinessRow(
                readinessLocationRow,
                locationReady,
                locationReady
                        ? getString(R.string.precise_gps_allowed)
                        : getString(R.string.choose_precise_location),
                locationReady ? null : getString(R.string.allow_location));
        updateReadinessRow(
                readinessBackgroundRow,
                true,
                getString(R.string.foreground_service_keeps_gps),
                null);
        updateReadinessRow(
                readinessNotificationRow,
                notificationReady,
                notificationReady
                        ? getString(R.string.alarm_notifications_enabled)
                        : getString(R.string.enable_alarm_notifications),
                notificationReady ? null : notificationActionTitle());
        updateReadinessRow(
                readinessDndRow,
                dndReady,
                dndReady
                        ? getString(R.string.dnd_access_granted)
                        : getString(R.string.grant_dnd_and_test),
                dndReady ? null : getString(R.string.open_dnd_access));
        updateReadinessRow(
                readinessPowerRow,
                powerReady,
                powerReady
                        ? getString(R.string.power_saving_not_restrictive)
                        : getString(R.string.power_saving_can_stop_gps),
                powerReady
                        ? getString(R.string.review_battery_settings)
                        : getString(R.string.open_power_settings));

        if (readinessSafetyAcknowledgement != null) {
            readinessSafetyAcknowledgement.setChecked(readinessPreferences().getBoolean(
                    KEY_READINESS_SAFETY_ACKNOWLEDGED,
                    false));
        }
        if (readinessSirenStatus != null) {
            String sirenStatus;
            int sirenStatusColor;
            if (readinessSirenError != null) {
                sirenStatus = readinessSirenError;
                sirenStatusColor = R.color.nav_error;
            } else if (sirenConfirmed) {
                sirenStatus = getString(R.string.siren_confirmed);
                sirenStatusColor = R.color.nav_success;
            } else {
                sirenStatus = getString(R.string.siren_test_detail);
                sirenStatusColor = R.color.nav_secondary;
            }
            readinessSirenStatus.setText(sirenStatus);
            readinessSirenStatus.setTextColor(getColor(sirenStatusColor));
        }
        if (readinessSirenButton != null) {
            boolean canTest = !latestState.anchorAlarmArmed;
            readinessSirenButton.setEnabled(canTest);
            readinessSirenButton.setText(canTest
                    ? sirenConfirmed
                    ? getString(R.string.test_alarm_again)
                    : getString(R.string.play_siren)
                    : getString(R.string.disarm_before_testing));
        }
        if (readinessSirenConfirmationButton != null) {
            readinessSirenConfirmationButton.setVisibility(
                    readinessSirenPlayedThisVisit && !sirenConfirmed
                            ? View.VISIBLE : View.GONE);
        }
        if (readinessCompleteButton != null) {
            readinessCompleteButton.setText(
                    resumeArmingAfterReadiness
                            ? getString(R.string.continue_and_arm)
                            : getString(R.string.save_readiness));
            readinessCompleteButton.setEnabled(anchorReadinessRequirementsSatisfied());
        }
    }

    private void updateReadinessRow(
            ReadinessRowViews row,
            boolean ready,
            String detail,
            String actionTitle
    ) {
        if (row == null) return;
        String rawTitle = row.title.getText().toString();
        if (rawTitle.startsWith("✓  ") || rawTitle.startsWith("⚠  ")) {
            rawTitle = rawTitle.substring(3);
        }
        row.title.setText(getString(
                ready ? R.string.readiness_row_ready : R.string.readiness_row_attention,
                rawTitle));
        row.title.setTextColor(getColor(ready ? R.color.nav_success : R.color.nav_warning));
        row.detail.setText(detail);
        if (row.action != null) {
            row.action.setVisibility(actionTitle == null ? View.GONE : View.VISIBLE);
            if (actionTitle != null) row.action.setText(actionTitle);
        }
    }

    private boolean anchorReadinessRequirementsSatisfied() {
        SharedPreferences preferences = readinessPreferences();
        return hasFineLocationPermission()
                && areAnchorNotificationsReady()
                && hasDoNotDisturbAccess()
                && !isLocationRestrictedByPowerSaving()
                && preferences.getBoolean(KEY_READINESS_SIREN_CONFIRMED, false)
                && preferences.getBoolean(KEY_READINESS_SAFETY_ACKNOWLEDGED, false);
    }

    private boolean isAnchorReadinessComplete() {
        SharedPreferences preferences = readinessPreferences();
        boolean wasComplete = preferences.getBoolean(KEY_READINESS_COMPLETE, false);
        boolean requirementsSatisfied = anchorReadinessRequirementsSatisfied();
        if (wasComplete && !requirementsSatisfied) {
            preferences.edit().putBoolean(KEY_READINESS_COMPLETE, false).apply();
        }
        return wasComplete && requirementsSatisfied;
    }

    private void completeAnchorReadinessAndContinue() {
        if (!anchorReadinessRequirementsSatisfied()) return;
        readinessPreferences().edit().putBoolean(KEY_READINESS_COMPLETE, true).apply();
        boolean shouldResumeArming = resumeArmingAfterReadiness;
        resumeArmingAfterReadiness = false;
        if (anchorReadinessDialog != null) anchorReadinessDialog.dismiss();
        if (shouldResumeArming) beginPermissionProtectedAction(PENDING_ARM_ANCHOR);
        else render(latestState);
    }

    private SharedPreferences readinessPreferences() {
        return getPreferences(MODE_PRIVATE);
    }

    private boolean areAnchorNotificationsReady() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) return false;
        NotificationChannel channel = manager.getNotificationChannel(ANCHOR_ALARM_CHANNEL_ID);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private boolean hasDoNotDisturbAccess() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        return manager != null && manager.isNotificationPolicyAccessGranted();
    }

    private void syncAnchorAlarmChannelDndBypass() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = manager.getNotificationChannel(ANCHOR_ALARM_CHANNEL_ID);
        if (channel == null) return;
        try {
            boolean shouldBypass = manager.isNotificationPolicyAccessGranted();
            if (channel.canBypassDnd() == shouldBypass) return;
            channel.setBypassDnd(shouldBypass);
            manager.createNotificationChannel(channel);
        } catch (RuntimeException ignored) {
            // The confirmed audible siren test remains the final readiness check.
        }
    }

    private void requestReadinessLocationPermission() {
        if (hasFineLocationPermission()) {
            openAppSettings();
            return;
        }
        readinessPreferences().edit().putBoolean("permission_requested", true).apply();
        requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, PERMISSION_REQUEST);
    }

    private void requestReadinessNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    PERMISSION_REQUEST);
            return;
        }
        openNotificationSettings();
    }

    private String notificationActionTitle() {
        return Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                ? getString(R.string.allow_notifications)
                : getString(R.string.open_notification_settings);
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            openAppSettings();
        }
    }

    private void openDoNotDisturbAccessSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        }
    }

    private void openAnchorPowerSettings() {
        if (isLocationRestrictedByPowerSaving()) {
            openPowerSavingSettings();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            openAppSettings();
        }
    }

    private void playReadinessSiren() {
        if (latestState.anchorAlarmArmed) return;
        stopReadinessSiren();
        readinessSirenError = null;
        try {
            AudioManager audioManager = getSystemService(AudioManager.class);
            if (audioManager != null) {
                readinessPreviousAlarmVolume = audioManager.getStreamVolume(
                        AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(
                        AudioManager.STREAM_ALARM,
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                        0);
            }
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            }
            readinessRingtone = RingtoneManager.getRingtone(this, alarmUri);
            if (readinessRingtone == null) throw new IllegalStateException("No alarm sound found");
            readinessRingtone.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .build());
            if (Build.VERSION.SDK_INT >= 28) {
                readinessRingtone.setLooping(true);
                readinessRingtone.setVolume(1.0f);
            }
            readinessRingtone.play();
            readinessSirenPlayedThisVisit = true;
            readinessHandler.postDelayed(this::stopReadinessSiren, 2_000L);
        } catch (RuntimeException error) {
            readinessSirenPlayedThisVisit = false;
            readinessSirenError = getString(R.string.siren_test_failed);
            stopReadinessSiren();
        }
        updateAnchorReadinessDialog();
    }

    private void confirmReadinessSirenHeard() {
        if (!readinessSirenPlayedThisVisit) return;
        stopReadinessSiren();
        readinessPreferences().edit()
                .putBoolean(KEY_READINESS_SIREN_CONFIRMED, true)
                .apply();
        updateAnchorReadinessDialog();
    }

    private void stopReadinessSiren() {
        readinessHandler.removeCallbacksAndMessages(null);
        if (readinessRingtone != null) {
            try {
                readinessRingtone.stop();
            } catch (RuntimeException ignored) {
                // The readiness UI can still explain the failed test.
            }
            readinessRingtone = null;
        }
        if (readinessPreviousAlarmVolume >= 0) {
            try {
                AudioManager audioManager = getSystemService(AudioManager.class);
                if (audioManager != null) {
                    audioManager.setStreamVolume(
                            AudioManager.STREAM_ALARM,
                            Math.min(
                                    readinessPreviousAlarmVolume,
                                    audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)),
                            0);
                }
            } catch (RuntimeException ignored) {
                // The short test is already stopped.
            }
            readinessPreviousAlarmVolume = -1;
        }
    }

    private void beginMarkAnchor() {
        anchorActionMessage = null;
        anchorSetupRequested = true;
        startAnchorSetupService();
        render(latestState);
        beginPermissionProtectedAction(PENDING_MARK_ANCHOR);
    }

    private void handleActiveAnchorPrimaryAction() {
        toggleAnchorAlarm();
    }

    private void cancelAnchorSetup() {
        anchorSetupRequested = false;
        pendingPermissionAction = PENDING_NONE;
        pendingAnchorFixStartedElapsedNanos = 0L;
        anchorActionMessage = null;
        sendServiceAction(BridgeService.ACTION_CANCEL_ANCHOR_SETUP);
        render(latestState);
    }

    private void beginPermissionProtectedAction(int action) {
        pendingPermissionAction = action;
        if (requiresNewAnchorReferenceFix(action)) {
            pendingAnchorFixStartedElapsedNanos = SystemClock.elapsedRealtimeNanos();
            anchorActionMessage = getString(R.string.acquiring_new_gps);
            renderAnchorAlarm(latestState);
        } else {
            pendingAnchorFixStartedElapsedNanos = 0L;
        }

        List<String> missingPermissions = new ArrayList<>();
        if (!hasFineLocationPermission()) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missingPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (action != PENDING_MARK_ANCHOR
                && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            missingPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!missingPermissions.isEmpty()) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("permission_requested", true).apply();
            requestPermissions(missingPermissions.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            if (binder != null) binder.refreshLocationUpdates();
            tryCompletePendingAction();
        }
    }

    private void tryCompletePendingAction() {
        if (pendingPermissionAction == PENDING_NONE || !hasFineLocationPermission()) return;
        if (pendingPermissionAction == PENDING_STREAM) {
            pendingPermissionAction = PENDING_NONE;
            pendingAnchorFixStartedElapsedNanos = 0L;
            startBridgeService();
            return;
        }
        if (!hasFreshGpsFix(latestState)) {
            anchorActionMessage = getString(R.string.acquiring_fresh_gps);
            anchorMessage.setText(anchorActionMessage);
            anchorMessage.setVisibility(View.VISIBLE);
            if (binder != null) binder.refreshLocationUpdates();
            return;
        }

        if (requiresNewAnchorReferenceFix(pendingPermissionAction)) {
            if (latestState.fixElapsedRealtimeNanos
                    <= pendingAnchorFixStartedElapsedNanos) {
                anchorActionMessage = getString(R.string.acquiring_new_gps);
                renderAnchorAlarm(latestState);
                return;
            }
            if (latestState.accuracyMeters
                    > MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS) {
                DisplayUnits displayUnits = DisplayUnits.load(this);
                anchorActionMessage = getString(
                        R.string.improving_gps_accuracy,
                        displayUnits.formatAccuracy(latestState.accuracyMeters),
                        displayUnits.formatAccuracy(
                                MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS));
                renderAnchorAlarm(latestState);
                return;
            }
        }

        int action = pendingPermissionAction;
        pendingPermissionAction = PENDING_NONE;
        pendingAnchorFixStartedElapsedNanos = 0L;
        if (action == PENDING_MARK_ANCHOR) {
            if (binder == null) {
                anchorActionMessage = getString(R.string.gps_service_reconnecting);
                renderAnchorAlarm(latestState);
                return;
            }
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
            binder.markAnchorReference(latestState.latitude, latestState.longitude);
            anchorActionMessage = getString(R.string.anchor_position_recorded);
            renderAnchorAlarm(latestState);
            return;
        }

        if (action == PENDING_ARM_ANCHOR && !isAnchorReadinessComplete()) {
            anchorActionMessage = getString(R.string.complete_readiness_before_arming);
            renderAnchorAlarm(latestState);
            showAnchorReadiness(true);
            return;
        }

        double referenceLatitude = selectedAnchorMode == AnchorAlarm.Mode.SHORELINED
                ? latestState.latitude : latestState.anchorReferenceLatitude;
        double referenceLongitude = selectedAnchorMode == AnchorAlarm.Mode.SHORELINED
                ? latestState.longitude : latestState.anchorReferenceLongitude;
        if (selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !latestState.hasAnchorReference) {
            anchorActionMessage = getString(R.string.record_position_before_arming);
            renderAnchorAlarm(latestState);
            return;
        }
        anchorActionMessage = null;
        startAnchorService(referenceLatitude, referenceLongitude);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        updateAnchorReadinessDialog();
        if (hasFineLocationPermission()) {
            permissionError = null;
            if (binder != null) binder.refreshLocationUpdates();
            tryCompletePendingAction();
        } else {
            if (pendingPermissionAction != PENDING_MARK_ANCHOR) {
                pendingPermissionAction = PENDING_NONE;
                pendingAnchorFixStartedElapsedNanos = 0L;
            }
            permissionError = getString(R.string.choose_precise_location_error);
            render(latestState);
        }
    }

    private void startBridgeService() {
        Intent startIntent = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START);
        startForegroundServiceCompat(startIntent);
    }

    private void startAnchorSetupService() {
        Intent intent = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_BEGIN_ANCHOR_SETUP)
                .putExtra(BridgeService.EXTRA_ANCHOR_MODE, selectedAnchorMode.storageValue)
                .putExtra(BridgeService.EXTRA_ANCHOR_RADIUS, selectedAnchorRadiusMeters);
        startForegroundServiceCompat(intent);
    }

    private void startAnchorService(double referenceLatitude, double referenceLongitude) {
        Intent intent = new Intent(this, BridgeService.class)
                .setAction(BridgeService.ACTION_ARM_ANCHOR)
                .putExtra(BridgeService.EXTRA_ANCHOR_MODE, selectedAnchorMode.storageValue)
                .putExtra(BridgeService.EXTRA_ANCHOR_RADIUS, selectedAnchorRadiusMeters)
                .putExtra(BridgeService.EXTRA_ANCHOR_LATITUDE, referenceLatitude)
                .putExtra(BridgeService.EXTRA_ANCHOR_LONGITUDE, referenceLongitude);
        startForegroundServiceCompat(intent);
    }

    private void startForegroundServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void sendServiceAction(String action) {
        startService(new Intent(this, BridgeService.class).setAction(action));
    }

    private static boolean hasFreshGpsFix(BridgeState state) {
        return state.hasLocation && state.accuracyMeters >= 0.0
                && state.fixAgeSeconds <= STALE_FIX_SECONDS;
    }

    private boolean requiresNewAnchorReferenceFix(int action) {
        return action == PENDING_MARK_ANCHOR
                || (action == PENDING_ARM_ANCHOR
                && selectedAnchorMode == AnchorAlarm.Mode.SHORELINED);
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void openPowerSavingSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        } catch (ActivityNotFoundException ignored) {
            try {
                startActivity(new Intent("android.settings.BATTERY_SETTINGS"));
            } catch (ActivityNotFoundException ignoredAgain) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    private void openPlaySubscriptionManagement() {
        Uri subscriptionUri = Uri.parse(
                "https://play.google.com/store/account/subscriptions")
                .buildUpon()
                .appendQueryParameter("sku", StoreProductIdentifiers.REMOTE_ANCHOR_WATCH)
                .appendQueryParameter("package", getPackageName())
                .build();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, subscriptionUri));
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/account/subscriptions")));
        }
    }

    private void addReleasePolicyLinks(LinearLayout parent) {
        LinearLayout links = horizontal();
        links.setGravity(Gravity.CENTER_VERTICAL);
        addReleasePolicyLink(
                links, getString(R.string.privacy), R.string.anchor_watch_privacy_url);
        addReleasePolicyLink(
                links, getString(R.string.support), R.string.anchor_watch_support_url);
        addReleasePolicyLink(
                links, getString(R.string.terms), R.string.anchor_watch_terms_url);
        LinearLayout.LayoutParams linksParams = matchWrap();
        linksParams.topMargin = dp(8);
        parent.addView(links, linksParams);
    }

    private void addReleasePolicyLink(LinearLayout parent, String title, int resourceId) {
        Button button = smallButton(title, () -> ReleaseLinks.open(this, resourceId));
        boolean configured = ReleaseLinks.configuredUri(this, resourceId) != null;
        button.setEnabled(configured);
        if (!configured) {
            button.setContentDescription(
                    getString(R.string.release_link_pending, title));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1.0f);
        parent.addView(button, params);
    }

    private boolean isLocationRestrictedByPowerSaving() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null || !powerManager.isPowerSaveMode()) return false;
        if (Build.VERSION.SDK_INT < 28) return true;
        int locationMode = powerManager.getLocationPowerSaveMode();
        return locationMode != PowerManager.LOCATION_MODE_NO_CHANGE
                && locationMode != PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
    }

    private void renderPowerSavingWarnings() {
        int visibility = isLocationRestrictedByPowerSaving() ? View.VISIBLE : View.GONE;
        anchorPowerSavingWarning.setVisibility(visibility);
        armedPowerSavingWarning.setVisibility(visibility);
    }

    private boolean hasFineLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDebuggableBuild() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private String permissionDescription() {
        if (hasFineLocationPermission()) return getString(R.string.precise);
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) return getString(R.string.approximate_only);
        boolean requested = getPreferences(MODE_PRIVATE).getBoolean("permission_requested", false);
        return requested ? getString(R.string.denied) : getString(R.string.not_requested);
    }

    private String statusTitle(BridgeState state, boolean fineLocation) {
        if (!state.streaming) return getString(R.string.stopped);
        if (!fineLocation) return getString(R.string.location_permission_needed);
        if (!state.hasLocation) return getString(R.string.acquiring_gps_fix);
        if (state.fixAgeSeconds > STALE_FIX_SECONDS) return getString(R.string.gps_fix_stale);
        if (state.clientCount == 0 && state.udpBroadcastTargetCount == 0) {
            return getString(R.string.waiting_hotspot_wifi);
        }
        if (state.clientCount == 0) return getString(R.string.broadcasting_nmea);
        return getString(R.string.streaming_nmea);
    }

    private int statusColor(BridgeState state, boolean fineLocation) {
        if (!state.streaming) return getColor(R.color.nav_secondary);
        if (!fineLocation) return getColor(R.color.nav_error);
        if (!state.hasLocation || state.fixAgeSeconds > STALE_FIX_SECONDS) {
            return getColor(R.color.nav_warning);
        }
        return getColor(state.clientCount > 0 || state.udpBroadcastTargetCount > 0
                ? R.color.nav_success : R.color.nav_blue);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_card));
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        card.setElevation(dp(1));
        return card;
    }

    private LinearLayout buildPowerSavingWarning() {
        LinearLayout warning = vertical();
        warning.setPadding(dp(14), dp(12), dp(14), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_warning_surface));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(2), getColor(R.color.nav_warning));
        warning.setBackground(background);

        TextView title = text(
                getString(R.string.power_saving_title), 15, R.color.nav_warning);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        warning.addView(title, matchWrap());

        TextView body = text(
                getString(R.string.power_saving_warning), 13, R.color.nav_text);
        body.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(5);
        warning.addView(body, bodyParams);

        Button settings = smallButton(
                getString(R.string.open_power_saving_settings),
                this::openPowerSavingSettings);
        settings.setTextColor(getColor(R.color.nav_warning));
        warning.addView(settings, wrapWrap());
        warning.setVisibility(View.GONE);
        return warning;
    }

    private LinearLayout buildGpsAccuracyWarning() {
        LinearLayout warning = vertical();
        warning.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_warning_surface));
        background.setCornerRadius(dp(14));
        background.setStroke(dp(2), getColor(R.color.nav_warning));
        warning.setBackground(background);

        TextView title = text(
                getString(R.string.degraded_gps_precision), 15, R.color.nav_warning);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        warning.addView(title, matchWrap());

        armedGpsAccuracyWarningBody = text("", 13, R.color.nav_text);
        armedGpsAccuracyWarningBody.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(5);
        warning.addView(armedGpsAccuracyWarningBody, bodyParams);
        warning.setVisibility(View.GONE);
        return warning;
    }

    private void addPurchaseBenefit(
            LinearLayout parent,
            String title,
            String detail
    ) {
        TextView titleView = text(
                getString(R.string.purchase_benefit_title, title), 14, R.color.nav_text);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(12);
        parent.addView(titleView, titleParams);
        TextView detailView = text(detail, 12, R.color.nav_secondary);
        detailView.setLineSpacing(0.0f, 1.1f);
        detailView.setPadding(dp(22), dp(2), 0, 0);
        parent.addView(detailView, matchWrap());
    }

    private TextView addTierSummary(
            LinearLayout parent,
            String title,
            String value,
            String detail
    ) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.TOP);
        TextView titleView = text(title, 14, R.color.nav_text);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView valueView = text(value, 14, R.color.nav_text);
        valueView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        valueView.setGravity(Gravity.END);
        row.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(valueView, wrapWrap());
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(5);
        parent.addView(row, rowParams);
        TextView detailView = text(detail, 12, R.color.nav_secondary);
        detailView.setLineSpacing(0.0f, 1.1f);
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(2);
        parent.addView(detailView, detailParams);
        return valueView;
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.nav_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(12);
        params.bottomMargin = dp(12);
        parent.addView(divider, params);
    }

    private void addCardHeader(LinearLayout card, String title) {
        TextView header = text(title, 17, R.color.nav_text);
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(7);
        card.addView(header, params);
    }

    private TextView addDetailRow(LinearLayout card, String labelText, boolean monospace) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(labelText, 14, R.color.nav_secondary);
        TextView value = text("—", 14, R.color.nav_text);
        if (monospace) value.setTypeface(Typeface.MONOSPACE);
        value.setGravity(Gravity.END);
        value.setTextIsSelectable(true);
        row.addView(label, wrapWrap());
        row.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(7);
        card.addView(row, params);
        return value;
    }

    private void addInstruction(LinearLayout card, int number, String instruction) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.TOP);
        TextView badge = text(String.valueOf(number), 12, android.R.color.white);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(getColor(R.color.nav_blue));
        badge.setBackground(circle);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(23), dp(23));
        badgeParams.setMarginEnd(dp(10));
        row.addView(badge, badgeParams);
        TextView body = text(instruction, 14, R.color.nav_text);
        body.setLineSpacing(0.0f, 1.12f);
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(8);
        card.addView(row, rowParams);
    }

    private View buildSectionSwitcher() {
        LinearLayout switcher = horizontal();
        switcher.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_surface_muted));
        background.setCornerRadius(dp(14));
        switcher.setBackground(background);

        anchorSectionButton = sectionButton(
                getString(R.string.anchor_section_title), SECTION_ANCHOR);
        bridgeSectionButton = sectionButton(
                getString(R.string.nmea_stream_title), SECTION_BRIDGE);
        switcher.addView(anchorSectionButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        switcher.addView(bridgeSectionButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        return switcher;
    }

    private Button sectionButton(String label, int section) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> selectSection(section, true));
        return button;
    }

    private void selectSection(int section, boolean scrollToTop) {
        selectedSection = section;
        if (normalDashboard != null) {
            normalDashboard.setVisibility(section == SECTION_BRIDGE ? View.VISIBLE : View.GONE);
        }
        if (anchorDashboard != null) {
            anchorDashboard.setVisibility(section == SECTION_ANCHOR ? View.VISIBLE : View.GONE);
        }
        if (pageTitle != null) {
            pageTitle.setText(section == SECTION_ANCHOR
                    ? R.string.anchor_watch_title : R.string.nmea_stream_title);
        }
        styleSectionButton(bridgeSectionButton, section == SECTION_BRIDGE);
        styleSectionButton(anchorSectionButton, section == SECTION_ANCHOR);
        if (scrollToTop && pageScrollView != null) {
            pageScrollView.post(() -> pageScrollView.smoothScrollTo(0, 0));
        }
    }

    private void styleSectionButton(Button button, boolean selected) {
        if (button == null) return;
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(selected ? R.color.nav_blue : android.R.color.transparent));
        background.setCornerRadius(dp(11));
        button.setBackground(background);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.nav_text));
    }

    private void updateSectionButtons(BridgeState state) {
        bridgeSectionButton.setText(state.streaming
                ? getString(R.string.section_active, getString(R.string.nmea_stream_title))
                : getString(R.string.nmea_stream_title));
        boolean anchorActive = state.anchorAlarmArmed
                || state.anchorSetupActive
                || anchorSetupRequested;
        anchorSectionButton.setText(anchorActive
                ? getString(R.string.section_active, getString(R.string.anchor_section_title))
                : getString(R.string.anchor_section_title));
        bridgeSectionButton.setContentDescription(
                state.streaming
                        ? getString(R.string.section_state, getString(R.string.nmea_stream_title),
                                getString(R.string.active_accessibility))
                        : getString(R.string.section_state, getString(R.string.nmea_stream_title),
                                getString(R.string.inactive_accessibility)));
        anchorSectionButton.setContentDescription(state.anchorAlarmArmed
                ? getString(R.string.anchor_section_armed)
                : anchorActive
                ? getString(R.string.anchor_section_laying)
                : getString(R.string.anchor_section_disarmed));
    }

    private View buildDisclosureSection(String title, LinearLayout body) {
        LinearLayout section = vertical();
        Button header = new Button(this);
        header.setAllCaps(false);
        header.setText(getString(R.string.disclosure_collapsed, title));
        header.setTextSize(16);
        header.setTextColor(getColor(R.color.nav_text));
        header.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        header.setMinHeight(dp(54));
        header.setElevation(dp(1));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_card));
        background.setCornerRadius(dp(16));
        header.setBackground(background);
        body.setVisibility(View.GONE);
        header.setOnClickListener(view -> {
            boolean expanded = body.getVisibility() == View.VISIBLE;
            body.setVisibility(expanded ? View.GONE : View.VISIBLE);
            header.setText(getString(
                    expanded ? R.string.disclosure_collapsed : R.string.disclosure_expanded,
                    title));
        });
        section.addView(header, matchWrap());
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(10);
        section.addView(body, bodyParams);
        return section;
    }

    private View buildAnchorDetailsCard() {
        LinearLayout card = card();
        TextView reference = addDetailRow(
                card, getString(R.string.reference_position), true);
        anchorReferenceValues.add(reference);
        TextView distance = addDetailRow(
                card, getString(R.string.distance_from_reference_label), false);
        anchorDistanceValues.add(distance);
        addDivider(card);
        TextView behavior = text(
                getString(R.string.anchor_alarm_behavior), 14, R.color.nav_secondary);
        behavior.setLineSpacing(0.0f, 1.12f);
        card.addView(behavior, matchWrap());
        addDivider(card);
        Button readiness = smallButton(
                getString(R.string.readiness_needs_attention),
                () -> showAnchorReadiness(false));
        readinessStatusButtons.add(readiness);
        card.addView(readiness, wrapWrap());
        Button displayUnits = smallButton(
                getString(
                        R.string.display_units_value,
                        DisplayUnits.load(this).preferenceDetail()),
                this::showDisplayUnitPicker);
        displayUnitButtons.add(displayUnits);
        card.addView(displayUnits, wrapWrap());
        return card;
    }

    private void showDisplayUnitPicker() {
        DisplayUnits.Preference[] preferences = DisplayUnits.Preference.values();
        String[] labels = new String[preferences.length];
        DisplayUnits.Preference selectedPreference = DisplayUnits.loadPreference(this);
        int selectedIndex = 0;
        for (int index = 0; index < preferences.length; index++) {
            DisplayUnits candidate = new DisplayUnits(
                    this, preferences[index], Locale.getDefault());
            labels[index] = candidate.preferenceDetail();
            if (preferences[index] == selectedPreference) selectedIndex = index;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.display_units)
                .setSingleChoiceItems(labels, selectedIndex, (dialog, position) -> {
                    DisplayUnits.save(this, preferences[position]);
                    dialog.dismiss();
                    render(latestState);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateDisplayUnitButtons() {
        String label = getString(
                R.string.display_units_value,
                DisplayUnits.load(this).preferenceDetail());
        for (Button button : displayUnitButtons) button.setText(label);
    }

    private TextView safetyNote() {
        TextView safety = text(
                getString(R.string.final_safety_warning), 13, R.color.nav_secondary);
        safety.setLineSpacing(0.0f, 1.18f);
        safety.setPadding(dp(5), dp(2), dp(5), dp(12));
        return safety;
    }

    private TextView addMetric(LinearLayout parent, String label, String initialValue) {
        LinearLayout metric = vertical();
        metric.setGravity(Gravity.CENTER);
        TextView value = text(initialValue, 14, R.color.nav_text);
        value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        TextView caption = text(label, 12, R.color.nav_secondary);
        caption.setGravity(Gravity.CENTER);
        metric.addView(value, matchWrap());
        metric.addView(caption, matchWrap());
        parent.addView(metric, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return value;
    }

    private void addMetricDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.nav_divider));
        parent.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(34)));
    }

    private void addLegendItem(LinearLayout parent, int colorResource, String label) {
        LinearLayout item = horizontal();
        item.setGravity(Gravity.CENTER_VERTICAL);
        View marker = new View(this);
        setCircleColor(marker, getColor(colorResource));
        LinearLayout.LayoutParams markerParams = new LinearLayout.LayoutParams(dp(8), dp(8));
        markerParams.setMarginEnd(dp(5));
        item.addView(marker, markerParams);
        item.addView(text(label, 12, R.color.nav_secondary), wrapWrap());
        LinearLayout.LayoutParams itemParams = wrapWrap();
        itemParams.setMarginEnd(dp(14));
        parent.addView(item, itemParams);
    }

    private Button modeButton(String title, AnchorAlarm.Mode mode) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinWidth(0);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> chooseAnchorMode(mode));
        return button;
    }

    private void chooseAnchorMode(AnchorAlarm.Mode mode) {
        if (mode == selectedAnchorMode || latestState.anchorAlarmArmed
                || latestState.anchorSetupActive || anchorSetupRequested) return;
        pendingPermissionAction = PENDING_NONE;
        pendingAnchorFixStartedElapsedNanos = 0L;
        selectedAnchorMode = mode;
        selectedAnchorRadiusMeters = mode.defaultRadiusMeters;
        anchorActionMessage = null;
        updateRadiusControls();
        updateModeButtons();
        if (binder != null) {
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
        }
        renderAnchorAlarm(latestState);
    }

    private void updateModeButtons() {
        styleModeButton(
                shorelinedModeButton,
                selectedAnchorMode == AnchorAlarm.Mode.SHORELINED);
        styleModeButton(
                freeSwingingModeButton,
                selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING);
    }

    private void styleModeButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(selected ? R.color.nav_blue : android.R.color.transparent));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        button.setTextColor(getColor(selected ? android.R.color.white : R.color.nav_text));
    }

    private TextView addWorkflowStep(
            LinearLayout parent,
            String number,
            String title,
            String detail) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.TOP);
        TextView badge = text(number, 12, android.R.color.white);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setShape(GradientDrawable.OVAL);
        badgeBackground.setColor(getColor(R.color.nav_blue));
        badge.setBackground(badgeBackground);
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(dp(24), dp(24));
        badgeParams.setMarginEnd(dp(10));
        row.addView(badge, badgeParams);

        LinearLayout copy = vertical();
        TextView titleView = text(title, 14, R.color.nav_text);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView detailView = text(detail, 12, R.color.nav_secondary);
        detailView.setLineSpacing(0.0f, 1.1f);
        copy.addView(titleView, matchWrap());
        copy.addView(detailView, matchWrap());
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.bottomMargin = dp(10);
        parent.addView(row, rowParams);
        return detailView;
    }

    private Button smallButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.nav_blue));
        button.setBackground(new GradientDrawable());
        button.setElevation(0);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(44));
        button.setMinWidth(0);
        button.setPadding(0, 0, dp(10), 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button actionButton(String label, int colorResource) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(60));
        styleActionButton(button, colorResource);
        return button;
    }

    private Button outlinedDangerButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(16);
        button.setTextColor(getColor(R.color.nav_error));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(dp(54));
        button.setMinWidth(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setElevation(0);
        button.setStateListAnimator(null);
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_error_surface));
        background.setCornerRadius(dp(24));
        background.setStroke(dp(2), getColor(R.color.nav_error));
        button.setBackground(background);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void styleActionButton(Button button, int colorResource) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(colorResource));
        background.setCornerRadius(dp(26));
        button.setBackground(background);
    }

    private Button radiusStepButton(String label, double direction) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(20);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(dp(44));
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> adjustAnchorRadius(direction));
        return button;
    }

    private void adjustAnchorRadius(double direction) {
        selectedAnchorRadiusMeters = selectedAnchorMode.adjustedRadius(
                selectedAnchorRadiusMeters
                        + direction * selectedAnchorMode.radiusStepMeters);
        updateRadiusControls();
        if (armedAnchorRadiusValue != null) {
            armedAnchorRadiusValue.setText(
                    DisplayUnits.load(this).format(selectedAnchorRadiusMeters));
        }
        if (binder != null) {
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
        }
    }

    private void updateRadiusControls() {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        anchorRadiusValue.setText(displayUnits.format(selectedAnchorRadiusMeters));
        anchorRadiusHint.setText(displayUnits.range(
                selectedAnchorMode.minimumRadiusMeters,
                selectedAnchorMode.maximumRadiusMeters,
                selectedAnchorMode.radiusStepMeters));
        anchorRadiusMinusButton.setEnabled(
                selectedAnchorRadiusMeters > selectedAnchorMode.minimumRadiusMeters);
        anchorRadiusPlusButton.setEnabled(
                selectedAnchorRadiusMeters < selectedAnchorMode.maximumRadiusMeters);
    }

    private String anchorModeDisplayName(AnchorAlarm.Mode mode) {
        return getString(mode == AnchorAlarm.Mode.FREE_SWINGING
                ? R.string.free_swinging : R.string.shorelined);
    }

    private TextView text(String value, float sizeSp, int colorResource) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(getColor(colorResource));
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout vertical() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        return params;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void setCircleColor(View view, int color) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color);
        view.setBackground(circle);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static BridgeState emptyState(String listenerDescription) {
        return new BridgeState(
                false, false,
                0.0, 0.0, 0.0, -1.0, -1.0, -1.0,
                Double.POSITIVE_INFINITY, 0L, 0, 0, listenerDescription,
                new ArrayList<>(), "", null,
                AnchorAlarm.Mode.SHORELINED,
                false, false, false, false, false,
                0.0, 0.0, 15.0, Double.NaN, new ArrayList<>(), null,
                false, false);
    }
}
