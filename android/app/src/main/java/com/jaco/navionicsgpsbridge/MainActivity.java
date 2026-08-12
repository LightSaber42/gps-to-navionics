package com.jaco.navionicsgpsbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements BridgeService.StateListener {
    private static final int PERMISSION_REQUEST = 42;
    private static final double STALE_FIX_SECONDS = 10.0;
    private static final double MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS = 15.0;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_STREAM = 1;
    private static final int PENDING_MARK_ANCHOR = 2;
    private static final int PENDING_ARM_ANCHOR = 3;
    private static final int SECTION_BRIDGE = 0;
    private static final int SECTION_ANCHOR = 1;

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
            binder.addListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private BridgeService.LocalBinder binder;
    private boolean bound;
    private boolean powerSaveModeReceiverRegistered;
    private BridgeState latestState = emptyState();
    private String permissionError;
    private String anchorActionMessage;
    private int pendingPermissionAction;
    private long pendingAnchorFixStartedElapsedNanos;
    private boolean anchorSetupRequested;
    private int selectedSection = SECTION_BRIDGE;
    private boolean lastRenderedAnchorArmed;
    private boolean lastRenderedAnchorTriggered;
    private AnchorAlarm.Mode selectedAnchorMode = AnchorAlarm.Mode.SHORELINED;
    private double selectedAnchorRadiusMeters = 15.0;

    private ScrollView pageScrollView;
    private TextView pageTitle;
    private LinearLayout normalDashboard;
    private LinearLayout anchorDashboard;
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
    private TextView normalShoreMonitorValue;
    private TextView armedShoreMonitorValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.nav_background));
        getWindow().setNavigationBarColor(getColor(R.color.nav_background));
        boolean darkMode = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(darkMode ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildContent());
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
        render(latestState);
    }

    @Override
    protected void onResume() {
        super.onResume();
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

        pageTitle = text("GPS Bridge", 28, R.color.nav_text);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageTitle.setPadding(dp(4), dp(10), 0, dp(12));
        content.addView(pageTitle, matchWrap());
        content.addView(buildSectionSwitcher(), cardParams());

        normalDashboard = new LinearLayout(this);
        normalDashboard.setOrientation(LinearLayout.VERTICAL);
        normalDashboard.addView(buildStatusCard(), cardParams());
        normalDashboard.addView(buildNetworkCard(), cardParams());
        LinearLayout setupContent = vertical();
        setupContent.addView(buildInstructionsCard(), cardParams());
        normalDashboard.addView(buildDisclosureSection("Setup in Navionics", setupContent), cardParams());
        LinearLayout bridgeDetails = vertical();
        bridgeDetails.addView(buildGpsCard(), cardParams());
        bridgeDetails.addView(buildDiagnosticsCard(), cardParams());
        normalDashboard.addView(buildDisclosureSection("GPS and diagnostics", bridgeDetails), cardParams());
        normalDashboard.addView(safetyNote(), matchWrap());
        content.addView(normalDashboard, matchWrap());

        anchorDashboard = vertical();
        disarmedAnchorDashboard = vertical();
        disarmedAnchorDashboard.addView(buildAnchorAlarmCard(), cardParams());
        LinearLayout anchorDetails = vertical();
        anchorDetails.addView(buildAnchorDetailsCard(), cardParams());
        disarmedAnchorDashboard.addView(
                buildDisclosureSection("Watch details", anchorDetails), cardParams());
        disarmedAnchorDashboard.addView(buildPairedAlarmCard(false), cardParams());
        anchorDashboard.addView(disarmedAnchorDashboard, matchWrap());

        armedAnchorDashboard = buildArmedAnchorDashboard();
        armedAnchorContainer = vertical();
        armedAnchorContainer.addView(armedAnchorDashboard, matchWrap());
        LinearLayout armedDetails = vertical();
        armedDetails.addView(buildAnchorDetailsCard(), cardParams());
        armedAnchorContainer.addView(
                buildDisclosureSection("Watch details", armedDetails), cardParams());
        armedAnchorContainer.addView(buildPairedAlarmCard(true), cardParams());
        armedAnchorContainer.addView(safetyNote(), matchWrap());
        anchorDashboard.addView(armedAnchorContainer, matchWrap());
        content.addView(anchorDashboard, matchWrap());
        selectSection(SECTION_BRIDGE, false);
        return pageScrollView;
    }

    private LinearLayout buildArmedAnchorDashboard() {
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

        armedAnchorPrimaryButton = actionButton("●  Arm anchor alarm", R.color.nav_blue);
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

        armedAnchorDistanceLabel = text("distance from reference", 14, R.color.nav_secondary);
        armedAnchorDistanceLabel.setGravity(Gravity.CENTER);
        plotCard.addView(armedAnchorDistanceLabel, matchWrap());

        armedRadiusControls = horizontal();
        armedRadiusControls.setGravity(Gravity.CENTER_VERTICAL);
        armedAnchorRadiusLabel = text("Alarm radius", 14, R.color.nav_secondary);
        armedRadiusControls.addView(armedAnchorRadiusLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        armedAnchorRadiusMinusButton = radiusStepButton("−", -1.0);
        armedAnchorRadiusMinusButton.setContentDescription("Decrease alarm radius");
        armedRadiusControls.addView(armedAnchorRadiusMinusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusValue = text("50 m", 15, R.color.nav_text);
        armedAnchorRadiusValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorRadiusValue.setGravity(Gravity.CENTER);
        armedRadiusControls.addView(armedAnchorRadiusValue,
                new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusPlusButton = radiusStepButton("+", 1.0);
        armedAnchorRadiusPlusButton.setContentDescription("Increase alarm radius");
        armedRadiusControls.addView(armedAnchorRadiusPlusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams radiusParams = matchWrap();
        radiusParams.topMargin = dp(10);
        plotCard.addView(armedRadiusControls, radiusParams);

        cancelAnchorSetupButton = outlinedDangerButton(
                "✕  Cancel anchoring", this::cancelAnchorSetup);
        LinearLayout.LayoutParams cancelParams = matchWrap();
        cancelParams.topMargin = dp(12);
        plotCard.addView(cancelAnchorSetupButton, cancelParams);

        LinearLayout statusLine = horizontal();
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        armedAnchorStatusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(14), dp(14));
        dotParams.setMarginEnd(dp(10));
        statusLine.addView(armedAnchorStatusDot, dotParams);
        armedAnchorStatusTitle = text("Anchor alarm armed", 20, R.color.nav_text);
        armedAnchorStatusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusLine.addView(armedAnchorStatusTitle,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        armedAnchorMode = text("Shorelined", 12, R.color.nav_secondary);
        armedAnchorMode.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusLine.addView(armedAnchorMode, wrapWrap());
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(14);
        plotCard.addView(statusLine, statusParams);

        LinearLayout legend = horizontal();
        legend.setGravity(Gravity.CENTER);
        addLegendItem(legend, R.color.nav_blue_dark, "GPS trail");
        addLegendItem(legend, R.color.nav_success, "Boat now");
        addLegendItem(legend, R.color.nav_warning, "Alarm limit");
        LinearLayout.LayoutParams legendParams = matchWrap();
        legendParams.topMargin = dp(9);
        plotCard.addView(legend, legendParams);

        armedAnchorTrackSummary = text("Waiting for plotted GPS fixes", 12, R.color.nav_secondary);
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

        armedAnchorSilenceButton = actionButton("Silence alarm", R.color.nav_warning);
        armedAnchorSilenceButton.setOnClickListener(view -> sendServiceAction(
                BridgeService.ACTION_SILENCE_ANCHOR));
        LinearLayout.LayoutParams silenceParams = matchWrap();
        silenceParams.topMargin = dp(10);
        plotCard.addView(armedAnchorSilenceButton, silenceParams);

        anchorPlotNote = text(
                "North-up • focused on the alarm boundary, current position, and recent trail. "
                        + "Isolated older GPS outliers do not shrink the plot.",
                12, R.color.nav_secondary);
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
        statusTitle = text("Stopped", 17, R.color.nav_text);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        line.addView(statusTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(line, matchWrap());

        LinearLayout metrics = horizontal();
        metrics.setGravity(Gravity.CENTER);
        bridgeGpsMetric = addMetric(metrics, "GPS", "—");
        addMetricDivider(metrics);
        bridgeClientMetric = addMetric(metrics, "Navionics", "0 clients");
        addMetricDivider(metrics);
        TextView portMetric = addMetric(metrics, "Port", String.valueOf(BridgeService.PORT));
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

        settingsButton = smallButton("Open Location Settings", this::openAppSettings);
        LinearLayout.LayoutParams settingsParams = wrapWrap();
        settingsParams.topMargin = dp(5);
        card.addView(settingsButton, settingsParams);
        return card;
    }

    private View buildAnchorAlarmCard() {
        LinearLayout card = card();

        LinearLayout statusLine = horizontal();
        statusLine.setGravity(Gravity.CENTER_VERTICAL);
        anchorStatusDot = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
        dotParams.setMarginEnd(dp(10));
        statusLine.addView(anchorStatusDot, dotParams);
        anchorStatusTitle = text("Anchor alarm ready", 17, R.color.nav_text);
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
        shorelinedModeButton = modeButton("Shorelined", AnchorAlarm.Mode.SHORELINED);
        freeSwingingModeButton = modeButton("Free swinging", AnchorAlarm.Mode.FREE_SWINGING);
        modes.addView(shorelinedModeButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        modes.addView(freeSwingingModeButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        LinearLayout.LayoutParams modeParams = matchWrap();
        modeParams.topMargin = dp(10);
        card.addView(modes, modeParams);

        LinearLayout radiusLine = horizontal();
        radiusLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView radiusLabel = text("Alarm radius", 14, R.color.nav_secondary);
        anchorRadiusValue = text("15 m", 15, R.color.nav_text);
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

        anchorRadiusHint = text("3–50 m range • 1 m steps", 12, R.color.nav_secondary);
        anchorRadiusHint.setGravity(Gravity.END);
        card.addView(anchorRadiusHint, matchWrap());

        freeSwingingWorkflow = vertical();
        freeSwingingWorkflow.setPadding(0, dp(10), 0, 0);
        anchorStepOneDetail = addWorkflowStep(
                freeSwingingWorkflow,
                "1",
                "Mark anchor position",
                "Do this while the boat is over the anchor.");
        addWorkflowStep(
                freeSwingingWorkflow,
                "2",
                "Arm after paying out the rode",
                "The watch uses the marked point as its centre.");
        card.addView(freeSwingingWorkflow, matchWrap());

        anchorPowerSavingWarning = buildPowerSavingWarning();
        LinearLayout.LayoutParams powerWarningParams = matchWrap();
        powerWarningParams.topMargin = dp(12);
        card.addView(anchorPowerSavingWarning, powerWarningParams);

        anchorPrimaryButton = actionButton("Arm anchor alarm", R.color.nav_blue);
        anchorPrimaryButton.setOnClickListener(view -> toggleAnchorAlarm());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(14);
        card.addView(anchorPrimaryButton, primaryParams);

        silenceAnchorButton = actionButton("Silence alarm", R.color.nav_warning);
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
        addCardHeader(card, "GPS fix");
        permissionValue = addDetailRow(card, "Permission", false);
        positionValue = addDetailRow(card, "Position", true);
        accuracyValue = addDetailRow(card, "Accuracy", false);
        fixAgeValue = addDetailRow(card, "Fix age", false);
        speedValue = addDetailRow(card, "Speed", false);
        courseValue = addDetailRow(card, "Course over ground", false);
        return card;
    }

    private View buildPairedAlarmCard(boolean armedCard) {
        LinearLayout card = card();
        addCardHeader(card, "Optional shore monitor");
        TextView description = text(
                "Add an Android phone for backup remote and connection-loss alerts. Remote use requires Tailscale or another VPN on both phones.",
                13,
                R.color.nav_secondary);
        description.setLineSpacing(0.0f, 1.12f);
        card.addView(description, matchWrap());
        TextView status = addDetailRow(card, "Status", false);
        if (armedCard) armedShoreMonitorValue = status;
        else normalShoreMonitorValue = status;

        Button pair = smallButton("Set up or view pairing code", () -> startActivity(
                new Intent(this, AnchorPairingActivity.class)));
        LinearLayout.LayoutParams pairParams = wrapWrap();
        pairParams.topMargin = dp(10);
        card.addView(pair, pairParams);

        Button monitor = smallButton("Use this phone as shore monitor", () -> startActivity(
                new Intent(this, ShoreMonitorActivity.class)));
        LinearLayout.LayoutParams monitorParams = wrapWrap();
        monitorParams.topMargin = dp(6);
        card.addView(monitor, monitorParams);
        return card;
    }

    private View buildNetworkCard() {
        LinearLayout card = card();
        addCardHeader(card, "Navionics connection");
        TextView summary = text(
                "Use UDP for the simplest hotspot connection. TCP remains available as a fallback.",
                14,
                R.color.nav_secondary);
        summary.setLineSpacing(0.0f, 1.12f);
        card.addView(summary, matchWrap());
        TextView protocolValue = addDetailRow(card, "Protocol", false);
        protocolValue.setText("UDP recommended · TCP fallback");
        TextView portValue = addDetailRow(card, "Port", true);
        portValue.setText(String.valueOf(BridgeService.PORT));

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.nav_divider));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);

        TextView hint = text(
                "TCP fallback addresses:",
                14,
                R.color.nav_secondary);
        card.addView(hint, matchWrap());

        addressesContainer = new LinearLayout(this);
        addressesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams addressesParams = matchWrap();
        addressesParams.topMargin = dp(6);
        card.addView(addressesContainer, addressesParams);

        Button refresh = smallButton("Refresh addresses", () -> {
            if (binder != null) binder.refreshNetworkAddresses();
        });
        LinearLayout.LayoutParams refreshParams = wrapWrap();
        refreshParams.topMargin = dp(7);
        card.addView(refresh, refreshParams);
        return card;
    }

    private View buildInstructionsCard() {
        LinearLayout card = card();
        addCardHeader(card, "Connect in Navionics");
        addInstruction(card, 1, "Turn on this phone's hotspot and connect the iPad to it. Internet is not required.");
        addInstruction(card, 2, "Start streaming and allow Precise Location and notifications.");
        addInstruction(card, 3, "In Navionics: Menu → Connect a Device → Add Device.");
        addInstruction(card, 4, "Choose UDP. Enter host 0.0.0.0 and port " + BridgeService.PORT + ".");
        addInstruction(card, 5, "Save the device. TCP remains available as a fallback using an address above.");
        addInstruction(card, 6, "Keep this phone powered during navigation.");
        return card;
    }

    private View buildDiagnosticsCard() {
        LinearLayout card = card();
        addCardHeader(card, "Network and NMEA");
        clientsValue = addDetailRow(card, "TCP clients", false);
        udpTargetsValue = addDetailRow(card, "UDP networks", false);
        serverValue = addDetailRow(card, "TCP server", false);
        TextView lastTransmission = text("Last NMEA transmission", 14, R.color.nav_text);
        lastTransmission.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams transmissionParams = matchWrap();
        transmissionParams.topMargin = dp(14);
        transmissionParams.bottomMargin = dp(7);
        card.addView(lastTransmission, transmissionParams);
        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setFillViewport(true);
        sentencesValue = text("Sentences appear here after the first GPS fix.", 12, R.color.nav_secondary);
        sentencesValue.setTypeface(Typeface.MONOSPACE);
        sentencesValue.setTextIsSelectable(true);
        horizontalScroll.addView(sentencesValue, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(horizontalScroll, matchWrap());
        return card;
    }

    private void render(BridgeState state) {
        boolean armedScreen = state.anchorAlarmArmed
                || state.anchorSetupActive
                || anchorSetupRequested;
        pageTitle.setText("GPS Bridge");
        disarmedAnchorDashboard.setVisibility(armedScreen ? View.GONE : View.VISIBLE);
        armedAnchorContainer.setVisibility(armedScreen ? View.VISIBLE : View.GONE);
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

        streamButton.setText(active ? "■  Stop streaming" : "●  Start streaming");
        styleActionButton(streamButton, active ? R.color.nav_error : R.color.nav_blue);
        renderAnchorAlarm(state);
        renderArmedAnchorDashboard(state);
        renderPowerSavingWarnings();
        renderShoreMonitorStatus(state);

        String visibleError = state.error != null ? state.error : permissionError;
        errorText.setText(visibleError == null ? "" : visibleError);
        errorText.setVisibility(visibleError == null ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(fineLocation ? View.GONE : View.VISIBLE);
        bridgeGpsMetric.setText(state.hasLocation && state.accuracyMeters >= 0.0
                ? String.format(Locale.US, "±%.0f m", state.accuracyMeters) : "Waiting");
        bridgeClientMetric.setText(state.clientCount == 1
                ? "1 client" : state.clientCount + " clients");
        updateSectionButtons(state);

        permissionValue.setText(permissionDescription());
        if (state.hasLocation) {
            positionValue.setText(String.format(Locale.US, "%.6f, %.6f", state.latitude, state.longitude));
            accuracyValue.setText(state.accuracyMeters >= 0.0
                    ? String.format(Locale.US, "±%.0f m", state.accuracyMeters) : "—");
            fixAgeValue.setText(String.format(Locale.US, "%.1f s", state.fixAgeSeconds));
            speedValue.setText(state.speedMetersPerSecond >= 0.0
                    ? String.format(Locale.US, "%.1f kn", state.speedMetersPerSecond * 1.9438444924) : "—");
            courseValue.setText(state.courseDegrees >= 0.0
                    ? String.format(Locale.US, "%.1f°", state.courseDegrees) : "—");
        } else {
            positionValue.setText("—");
            accuracyValue.setText("—");
            fixAgeValue.setText("—");
            speedValue.setText("—");
            courseValue.setText("—");
        }

        clientsValue.setText(String.valueOf(state.clientCount));
        udpTargetsValue.setText(state.udpBroadcastTargetCount == 0
                ? "Waiting for hotspot"
                : state.udpBroadcastTargetCount + " active");
        serverValue.setText(state.listenerDescription);
        renderAddresses(state.networkAddresses);

        if (state.lastSentences.isEmpty()) {
            sentencesValue.setText("Sentences appear here after the first GPS fix.");
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

    private void renderAnchorAlarm(BridgeState state) {
        selectedAnchorMode = state.anchorMode;
        selectedAnchorRadiusMeters = state.anchorRadiusMeters;
        updateRadiusControls();
        updateModeButtons();

        boolean armed = state.anchorAlarmArmed;
        boolean setupActive = state.anchorSetupActive || anchorSetupRequested;
        boolean triggered = state.anchorAlarmTriggered;
        if (triggered) {
            anchorStatusTitle.setText("ANCHOR ALARM");
            setCircleColor(anchorStatusDot, getColor(R.color.nav_error));
        } else if (state.anchorAlarmSilenced) {
            anchorStatusTitle.setText("Alarm silenced • still monitoring");
            setCircleColor(anchorStatusDot, getColor(R.color.nav_warning));
        } else if (armed) {
            anchorStatusTitle.setText("Armed • " + state.anchorMode.displayName);
            setCircleColor(anchorStatusDot, getColor(R.color.nav_success));
        } else if (state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !state.hasAnchorReference) {
            anchorStatusTitle.setText("Record the anchor position");
            setCircleColor(anchorStatusDot, getColor(R.color.nav_blue));
        } else {
            anchorStatusTitle.setText("Anchor alarm ready");
            setCircleColor(anchorStatusDot, getColor(R.color.nav_blue));
        }
        anchorStatusDot.setContentDescription(anchorStatusTitle.getText());

        if (state.anchorMode == AnchorAlarm.Mode.SHORELINED) {
            anchorDescription.setText(
                    "For a bow anchor with the stern tied ashore. Arming records the boat's current "
                            + "tied position and watches a tight circle around it.");
        } else {
            anchorDescription.setText(
                    "For a boat free to swing. Tap Drop anchor at the anchor-drop point, "
                            + "then arm after paying out the rode.");
        }

        shorelinedModeButton.setEnabled(!armed && !setupActive);
        freeSwingingModeButton.setEnabled(!armed && !setupActive);
        anchorRadiusMinusButton.setEnabled(!armed && !setupActive);
        anchorRadiusPlusButton.setEnabled(!armed && !setupActive);
        freeSwingingWorkflow.setVisibility(
                state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING ? View.VISIBLE : View.GONE);
        anchorStepOneDetail.setText(state.hasAnchorReference
                ? "Position recorded. Continue to step 2."
                : pendingPermissionAction == PENDING_MARK_ANCHOR
                ? "Waiting for a new, accurate GPS position."
                : "Do this while the boat is over the anchor.");

        if (armed) {
            anchorPrimaryButton.setText("■  Disarm anchor watch");
            styleActionButton(anchorPrimaryButton, R.color.nav_error);
        } else if (requiresNewAnchorReferenceFix(pendingPermissionAction)) {
            anchorPrimaryButton.setText("⌖  Acquiring GPS position…");
            styleActionButton(anchorPrimaryButton, R.color.nav_blue);
        } else if (state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !state.hasAnchorReference) {
            anchorPrimaryButton.setText("⚓  Drop anchor");
            styleActionButton(anchorPrimaryButton, R.color.nav_blue);
        } else {
            anchorPrimaryButton.setText("●  Arm anchor alarm");
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
            value = "Connected · Sound ready";
            color = R.color.nav_success;
        } else if (state.shoreMonitorConnected) {
            value = state.anchorAlarmArmed ? "Connected · Setup needed" : "Connected";
            color = state.anchorAlarmArmed ? R.color.nav_warning : R.color.nav_secondary;
        } else {
            value = "Not paired · Optional";
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
        boolean setupActive = state.anchorSetupActive || anchorSetupRequested;
        if (state.anchorAlarmTriggered) {
            armedAnchorStatusTitle.setText("ANCHOR ALARM");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_error));
        } else if (state.anchorAlarmSilenced) {
            armedAnchorStatusTitle.setText("Alarm silenced • monitoring");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_warning));
        } else if (setupActive && !state.hasAnchorReference) {
            armedAnchorStatusTitle.setText("Acquiring anchor position");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_blue));
        } else if (setupActive) {
            armedAnchorStatusTitle.setText("Laying anchor");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_blue));
        } else {
            armedAnchorStatusTitle.setText("Anchor alarm armed");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_success));
        }
        armedAnchorMode.setText(state.anchorMode.displayName);
        armedAnchorDistance.setText(Double.isFinite(state.anchorDistanceMeters)
                ? String.format(Locale.US, "%.1f m", state.anchorDistanceMeters) : "—");
        armedAnchorDistance.setTextColor(getColor(
                state.anchorAlarmTriggered ? R.color.nav_error : R.color.nav_text));
        armedAnchorDistanceLabel.setText(String.format(
                Locale.US, "distance from reference • %.0f m limit", state.anchorRadiusMeters));
        boolean radiusAdjustable = state.anchorAlarmArmed
                || (setupActive && state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING);
        armedRadiusControls.setVisibility(radiusAdjustable ? View.VISIBLE : View.GONE);
        armedAnchorRadiusLabel.setText(state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                ? "Swing radius" : "Alarm radius");
        armedAnchorRadiusValue.setText(String.format(
                Locale.US, "%.0f m", state.anchorRadiusMeters));
        armedAnchorRadiusMinusButton.setEnabled(
                state.anchorRadiusMeters > state.anchorMode.minimumRadiusMeters);
        armedAnchorRadiusPlusButton.setEnabled(
                state.anchorRadiusMeters < state.anchorMode.maximumRadiusMeters);
        anchorPlotView.setState(state);
        String accuracy = state.hasLocation && state.accuracyMeters >= 0.0
                ? String.format(Locale.US, " • GPS ±%.0f m", state.accuracyMeters) : "";
        armedAnchorTrackSummary.setText(String.format(
                Locale.US,
                "%.0f m limit • %d plotted fixes%s",
                state.anchorRadiusMeters,
                state.anchorTrackPoints.size(),
                accuracy));
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
                ? "●  Arm anchor alarm"
                : "⌖  Acquiring anchor position…"
                : "■  Disarm anchor watch");
        armedAnchorPrimaryButton.setEnabled(!setupActive || state.hasAnchorReference);
        styleActionButton(armedAnchorPrimaryButton,
                setupActive ? R.color.nav_blue : R.color.nav_error);
        cancelAnchorSetupButton.setVisibility(setupActive ? View.VISIBLE : View.GONE);
        anchorPlotNote.setText(setupActive
                ? "North-up • the trail begins at the anchor-drop point and updates while you "
                        + "pay out the rode. The alarm is not armed yet."
                : "North-up • focused on the alarm boundary, current position, and recent trail. "
                        + "Isolated older GPS outliers do not shrink the plot.");
    }

    private void renderAddresses(List<BridgeState.NetworkAddress> addresses) {
        addressesContainer.removeAllViews();
        if (addresses.isEmpty()) {
            TextView empty = text("No Wi‑Fi or hotspot address found", 14, R.color.nav_warning);
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
                    .setTitle("Disarm anchor watch?")
                    .setMessage("The boat will no longer be monitored for drift or GPS loss.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Disarm", (dialog, which) -> {
                        pendingPermissionAction = PENDING_NONE;
                        sendServiceAction(BridgeService.ACTION_DISARM_ANCHOR);
                    })
                    .show();
            return;
        }
        if (isLocationRestrictedByPowerSaving()) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn off Power saving")
                    .setMessage(
                            "Power saving can stop or delay GPS when the screen locks. "
                                    + "That can trigger a lost-GPS alarm after 30 seconds. "
                                    + "Turn it off before relying on the anchor watch.")
                    .setNegativeButton("Cancel", null)
                    .setNeutralButton("Continue anyway", (dialog, which) ->
                            continueAnchorPrimaryAction())
                    .setPositiveButton("Open settings", (dialog, which) ->
                            openPowerSavingSettings())
                    .show();
            return;
        }
        continueAnchorPrimaryAction();
    }

    private void continueAnchorPrimaryAction() {
        if (latestState.anchorSetupActive || anchorSetupRequested) {
            if (latestState.hasAnchorReference) {
                beginPermissionProtectedAction(PENDING_ARM_ANCHOR);
            }
            return;
        }
        if (selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !latestState.hasAnchorReference) {
            beginMarkAnchor();
            return;
        }
        beginPermissionProtectedAction(PENDING_ARM_ANCHOR);
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
            anchorActionMessage = "Acquiring a new GPS position…";
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
            anchorActionMessage = "Acquiring a fresh GPS position…";
            anchorMessage.setText(anchorActionMessage);
            anchorMessage.setVisibility(View.VISIBLE);
            if (binder != null) binder.refreshLocationUpdates();
            return;
        }

        if (requiresNewAnchorReferenceFix(pendingPermissionAction)) {
            if (latestState.fixElapsedRealtimeNanos
                    <= pendingAnchorFixStartedElapsedNanos) {
                anchorActionMessage = "Acquiring a new GPS position…";
                renderAnchorAlarm(latestState);
                return;
            }
            if (latestState.accuracyMeters
                    > MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS) {
                anchorActionMessage = String.format(
                        Locale.US,
                        "Improving GPS accuracy… currently ±%.0f m; need ±%.0f m.",
                        latestState.accuracyMeters,
                        MAXIMUM_ANCHOR_REFERENCE_ACCURACY_METERS);
                renderAnchorAlarm(latestState);
                return;
            }
        }

        int action = pendingPermissionAction;
        pendingPermissionAction = PENDING_NONE;
        pendingAnchorFixStartedElapsedNanos = 0L;
        if (action == PENDING_MARK_ANCHOR) {
            if (binder == null) {
                anchorActionMessage = "GPS service is reconnecting. Try again in a moment.";
                renderAnchorAlarm(latestState);
                return;
            }
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
            binder.markAnchorReference(latestState.latitude, latestState.longitude);
            anchorActionMessage = "Anchor-drop position recorded. Arm after paying out the rode.";
            renderAnchorAlarm(latestState);
            return;
        }

        double referenceLatitude = selectedAnchorMode == AnchorAlarm.Mode.SHORELINED
                ? latestState.latitude : latestState.anchorReferenceLatitude;
        double referenceLongitude = selectedAnchorMode == AnchorAlarm.Mode.SHORELINED
                ? latestState.longitude : latestState.anchorReferenceLongitude;
        if (selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !latestState.hasAnchorReference) {
            anchorActionMessage = "Record the anchor-drop position before arming free-swinging mode.";
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
        if (hasFineLocationPermission()) {
            permissionError = null;
            if (binder != null) binder.refreshLocationUpdates();
            tryCompletePendingAction();
        } else {
            if (pendingPermissionAction != PENDING_MARK_ANCHOR) {
                pendingPermissionAction = PENDING_NONE;
                pendingAnchorFixStartedElapsedNanos = 0L;
            }
            permissionError = "Choose Precise Location for GPS streaming and the anchor alarm.";
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

    private String permissionDescription() {
        if (hasFineLocationPermission()) return "Precise";
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) return "Approximate only";
        boolean requested = getPreferences(MODE_PRIVATE).getBoolean("permission_requested", false);
        return requested ? "Denied" : "Not requested";
    }

    private String statusTitle(BridgeState state, boolean fineLocation) {
        if (!state.streaming) return "Stopped";
        if (!fineLocation) return "Location permission needed";
        if (!state.hasLocation) return "Acquiring GPS fix";
        if (state.fixAgeSeconds > STALE_FIX_SECONDS) return "GPS fix is stale";
        if (state.clientCount == 0 && state.udpBroadcastTargetCount == 0) {
            return "Waiting for hotspot or Wi-Fi";
        }
        if (state.clientCount == 0) return "Broadcasting GPS";
        return "Streaming to Navionics";
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

        TextView title = text("Turn off Power saving", 15, R.color.nav_warning);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        warning.addView(title, matchWrap());

        TextView body = text(
                "Power saving can interrupt GPS after the screen locks and cause a "
                        + "lost-GPS alarm. Turn it off before relying on the anchor watch.",
                13,
                R.color.nav_text);
        body.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(5);
        warning.addView(body, bodyParams);

        Button settings = smallButton(
                "Open Power saving settings",
                this::openPowerSavingSettings);
        settings.setTextColor(getColor(R.color.nav_warning));
        warning.addView(settings, wrapWrap());
        warning.setVisibility(View.GONE);
        return warning;
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

        bridgeSectionButton = sectionButton("Bridge", SECTION_BRIDGE);
        anchorSectionButton = sectionButton("Anchor", SECTION_ANCHOR);
        switcher.addView(bridgeSectionButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        switcher.addView(anchorSectionButton, new LinearLayout.LayoutParams(0, dp(46), 1));
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
        bridgeSectionButton.setText(state.streaming ? "Bridge  •" : "Bridge");
        boolean anchorActive = state.anchorAlarmArmed
                || state.anchorSetupActive
                || anchorSetupRequested;
        anchorSectionButton.setText(anchorActive ? "Anchor  •" : "Anchor");
        bridgeSectionButton.setContentDescription(
                state.streaming ? "Bridge, active" : "Bridge, inactive");
        anchorSectionButton.setContentDescription(state.anchorAlarmArmed
                ? "Anchor, armed" : anchorActive ? "Anchor, laying anchor" : "Anchor, disarmed");
    }

    private View buildDisclosureSection(String title, LinearLayout body) {
        LinearLayout section = vertical();
        Button header = new Button(this);
        header.setAllCaps(false);
        header.setText(title + "  ›");
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
            header.setText(title + (expanded ? "  ›" : "  ⌄"));
        });
        section.addView(header, matchWrap());
        LinearLayout.LayoutParams bodyParams = matchWrap();
        bodyParams.topMargin = dp(10);
        section.addView(body, bodyParams);
        return section;
    }

    private View buildAnchorDetailsCard() {
        LinearLayout card = card();
        TextView behavior = text(
                "A drift must remain outside the radius for 5 seconds. GPS loss alerts after "
                        + "30 seconds. The alarm continues until silenced or the watch is disarmed.",
                14,
                R.color.nav_secondary);
        behavior.setLineSpacing(0.0f, 1.12f);
        card.addView(behavior, matchWrap());
        return card;
    }

    private TextView safetyNote() {
        TextView safety = text(
                "⚠  Test the alarm and phone volume before relying on it. Keep a physical "
                        + "anchor watch and an independent navigation backup.",
                13,
                R.color.nav_secondary);
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
            armedAnchorRadiusValue.setText(String.format(
                    Locale.US, "%.0f m", selectedAnchorRadiusMeters));
        }
        if (binder != null) {
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
        }
    }

    private void updateRadiusControls() {
        anchorRadiusValue.setText(String.format(
                Locale.US, "%.0f m", selectedAnchorRadiusMeters));
        anchorRadiusHint.setText(String.format(
                Locale.US, "%.0f–%.0f m range • %.0f m steps",
                selectedAnchorMode.minimumRadiusMeters,
                selectedAnchorMode.maximumRadiusMeters,
                selectedAnchorMode.radiusStepMeters));
        anchorRadiusMinusButton.setEnabled(
                selectedAnchorRadiusMeters > selectedAnchorMode.minimumRadiusMeters);
        anchorRadiusPlusButton.setEnabled(
                selectedAnchorRadiusMeters < selectedAnchorMode.maximumRadiusMeters);
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

    private static BridgeState emptyState() {
        return new BridgeState(
                false, false,
                0.0, 0.0, 0.0, -1.0, -1.0, -1.0,
                Double.POSITIVE_INFINITY, 0L, 0, 0, "Stopped",
                new ArrayList<>(), "", null,
                AnchorAlarm.Mode.SHORELINED,
                false, false, false, false, false,
                0.0, 0.0, 15.0, Double.NaN, new ArrayList<>(), null,
                false, false);
    }
}
