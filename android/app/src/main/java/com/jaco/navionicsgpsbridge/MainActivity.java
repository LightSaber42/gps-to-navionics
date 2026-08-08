package com.jaco.navionicsgpsbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements BridgeService.StateListener {
    private static final int PERMISSION_REQUEST = 42;
    private static final double STALE_FIX_SECONDS = 10.0;
    private static final int PENDING_NONE = 0;
    private static final int PENDING_STREAM = 1;
    private static final int PENDING_MARK_ANCHOR = 2;
    private static final int PENDING_ARM_ANCHOR = 3;

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
    private BridgeState latestState = emptyState();
    private String permissionError;
    private String anchorActionMessage;
    private int pendingPermissionAction;
    private boolean renderingAnchorControls;
    private boolean anchorRadiusTracking;
    private boolean lastRenderedAnchorArmed;
    private AnchorAlarm.Mode selectedAnchorMode = AnchorAlarm.Mode.SHORELINED;
    private double selectedAnchorRadiusMeters = 15.0;

    private ScrollView pageScrollView;
    private TextView pageTitle;
    private LinearLayout normalDashboard;
    private LinearLayout armedAnchorDashboard;
    private View statusDot;
    private TextView statusTitle;
    private Button streamButton;
    private TextView errorText;
    private Button settingsButton;
    private View anchorStatusDot;
    private TextView anchorStatusTitle;
    private TextView anchorDescription;
    private RadioButton shorelinedModeButton;
    private RadioButton freeSwingingModeButton;
    private SeekBar anchorRadiusSlider;
    private TextView anchorRadiusValue;
    private TextView anchorRadiusHint;
    private Button anchorRadiusMinusButton;
    private Button anchorRadiusPlusButton;
    private TextView anchorReferenceValue;
    private TextView anchorDistanceValue;
    private TextView anchorMessage;
    private Button anchorPrimaryButton;
    private Button markAnchorButton;
    private Button silenceAnchorButton;
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
    private LinearLayout armedSwingRadiusControls;
    private TextView armedAnchorRadiusValue;
    private Button armedAnchorRadiusMinusButton;
    private Button armedAnchorRadiusPlusButton;
    private TextView armedAnchorTrackSummary;
    private TextView armedAnchorMessage;
    private Button armedAnchorSilenceButton;
    private AnchorPlotView anchorPlotView;
    private TextView normalShoreMonitorValue;
    private TextView armedShoreMonitorValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(getColor(R.color.nav_background));
        getWindow().setNavigationBarColor(getColor(R.color.nav_background));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(buildContent());
        render(latestState);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, BridgeService.class), serviceConnection, BIND_AUTO_CREATE);
        render(latestState);
    }

    @Override
    protected void onStop() {
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
        pageScrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pageScrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        pageTitle = text("GPS → Navionics", 30, R.color.nav_text);
        pageTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pageTitle.setPadding(dp(4), dp(10), 0, dp(16));
        content.addView(pageTitle, matchWrap());

        normalDashboard = new LinearLayout(this);
        normalDashboard.setOrientation(LinearLayout.VERTICAL);
        normalDashboard.addView(buildStatusCard(), cardParams());
        normalDashboard.addView(buildAnchorAlarmCard(), cardParams());
        normalDashboard.addView(buildPairedAlarmCard(false), cardParams());
        normalDashboard.addView(buildGpsCard(), cardParams());
        normalDashboard.addView(buildNetworkCard(), cardParams());
        normalDashboard.addView(buildInstructionsCard(), cardParams());
        normalDashboard.addView(buildDiagnosticsCard(), cardParams());

        TextView safety = text(
                "⚠  Test the alarm and phone volume before relying on it. GPS, battery, and Android "
                        + "settings can fail; keep a physical anchor watch and an independent navigation backup.",
                13, R.color.nav_secondary);
        safety.setLineSpacing(0.0f, 1.18f);
        safety.setPadding(dp(5), dp(2), dp(5), dp(12));
        normalDashboard.addView(safety, matchWrap());
        content.addView(normalDashboard, matchWrap());

        armedAnchorDashboard = buildArmedAnchorDashboard();
        armedAnchorDashboard.setVisibility(View.GONE);
        content.addView(armedAnchorDashboard, matchWrap());
        return pageScrollView;
    }

    private LinearLayout buildArmedAnchorDashboard() {
        LinearLayout dashboard = new LinearLayout(this);
        dashboard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout plotCard = card();

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
        plotCard.addView(statusLine, matchWrap());

        armedAnchorDistance = text("—", 42, R.color.nav_text);
        armedAnchorDistance.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorDistance.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams distanceParams = matchWrap();
        distanceParams.topMargin = dp(14);
        plotCard.addView(armedAnchorDistance, distanceParams);

        TextView distanceLabel = text("distance from reference", 14, R.color.nav_secondary);
        distanceLabel.setGravity(Gravity.CENTER);
        plotCard.addView(distanceLabel, matchWrap());

        armedSwingRadiusControls = horizontal();
        armedSwingRadiusControls.setGravity(Gravity.CENTER_VERTICAL);
        TextView radiusLabel = text("Swing radius", 14, R.color.nav_secondary);
        armedSwingRadiusControls.addView(radiusLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        armedAnchorRadiusMinusButton = radiusStepButton("−", -1.0);
        armedAnchorRadiusMinusButton.setContentDescription("Decrease swing radius");
        armedSwingRadiusControls.addView(armedAnchorRadiusMinusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusValue = text("50 m", 15, R.color.nav_text);
        armedAnchorRadiusValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        armedAnchorRadiusValue.setGravity(Gravity.CENTER);
        armedSwingRadiusControls.addView(armedAnchorRadiusValue,
                new LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        armedAnchorRadiusPlusButton = radiusStepButton("+", 1.0);
        armedAnchorRadiusPlusButton.setContentDescription("Increase swing radius");
        armedSwingRadiusControls.addView(armedAnchorRadiusPlusButton,
                new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams radiusParams = matchWrap();
        radiusParams.topMargin = dp(10);
        plotCard.addView(armedSwingRadiusControls, radiusParams);

        anchorPlotView = new AnchorPlotView(this);
        GradientDrawable plotBackground = new GradientDrawable();
        plotBackground.setColor(getColor(R.color.nav_background));
        plotBackground.setCornerRadius(dp(14));
        plotBackground.setStroke(dp(1), 0x24000000);
        anchorPlotView.setBackground(plotBackground);
        LinearLayout.LayoutParams plotParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360));
        plotParams.topMargin = dp(14);
        plotCard.addView(anchorPlotView, plotParams);

        TextView legend = text(
                "Blue: GPS trail   •   Green: boat now   •   Orange: alarm limit",
                12, R.color.nav_secondary);
        legend.setGravity(Gravity.CENTER);
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

        Button disarmButton = actionButton("■  Disarm anchor alarm", R.color.nav_error);
        disarmButton.setOnClickListener(view -> toggleAnchorAlarm());
        LinearLayout.LayoutParams disarmParams = matchWrap();
        disarmParams.topMargin = dp(10);
        plotCard.addView(disarmButton, disarmParams);

        TextView plotNote = text(
                "North-up • automatically zoomed to show the recent trail and complete alarm boundary.",
                12, R.color.nav_secondary);
        plotNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(10);
        plotCard.addView(plotNote, noteParams);
        dashboard.addView(plotCard, cardParams());
        dashboard.addView(buildPairedAlarmCard(true), cardParams());

        TextView safety = text(
                "⚠  Test the alarm and phone volume before relying on it. Keep a physical anchor "
                        + "watch and an independent navigation backup.",
                13, R.color.nav_secondary);
        safety.setLineSpacing(0.0f, 1.18f);
        safety.setPadding(dp(5), dp(2), dp(5), dp(12));
        dashboard.addView(safety, matchWrap());
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

        streamButton = new Button(this);
        streamButton.setAllCaps(false);
        streamButton.setTextSize(16);
        streamButton.setTextColor(Color.WHITE);
        streamButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        streamButton.setMinHeight(dp(52));
        streamButton.setOnClickListener(view -> toggleStreaming());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(14);
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

        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        shorelinedModeButton = new RadioButton(this);
        shorelinedModeButton.setId(View.generateViewId());
        shorelinedModeButton.setText("Shorelined");
        shorelinedModeButton.setTextSize(14);
        freeSwingingModeButton = new RadioButton(this);
        freeSwingingModeButton.setId(View.generateViewId());
        freeSwingingModeButton.setText("Free swinging");
        freeSwingingModeButton.setTextSize(14);
        modes.addView(shorelinedModeButton,
                new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modes.addView(freeSwingingModeButton,
                new RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        modes.setOnCheckedChangeListener((group, checkedId) -> {
            if (renderingAnchorControls) return;
            AnchorAlarm.Mode newMode = checkedId == freeSwingingModeButton.getId()
                    ? AnchorAlarm.Mode.FREE_SWINGING : AnchorAlarm.Mode.SHORELINED;
            if (newMode == selectedAnchorMode) return;
            selectedAnchorMode = newMode;
            selectedAnchorRadiusMeters = newMode.defaultRadiusMeters;
            updateRadiusSlider();
            anchorActionMessage = null;
            if (binder != null) {
                binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
            }
            renderAnchorAlarm(latestState);
        });
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

        anchorRadiusSlider = new SeekBar(this);
        anchorRadiusSlider.setMax(radiusMaximumProgress(selectedAnchorMode));
        anchorRadiusSlider.setProgress(radiusProgress(
                selectedAnchorMode, selectedAnchorRadiusMeters));
        anchorRadiusSlider.setContentDescription("Anchor alarm radius");
        anchorRadiusSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || renderingAnchorControls) return;
                selectedAnchorRadiusMeters = radiusFromProgress(selectedAnchorMode, progress);
                anchorRadiusValue.setText(String.format(
                        Locale.US, "%.0f m", selectedAnchorRadiusMeters));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                anchorRadiusTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                anchorRadiusTracking = false;
                if (binder != null) {
                    binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
                }
            }
        });
        card.addView(anchorRadiusSlider, matchWrap());

        anchorRadiusHint = text("3–50 m range • 1 m steps", 12, R.color.nav_secondary);
        card.addView(anchorRadiusHint, matchWrap());

        anchorReferenceValue = addDetailRow(card, "Reference position", true);
        anchorDistanceValue = addDetailRow(card, "Distance from reference", false);

        anchorPrimaryButton = actionButton("Arm anchor alarm", R.color.nav_blue);
        anchorPrimaryButton.setOnClickListener(view -> toggleAnchorAlarm());
        LinearLayout.LayoutParams primaryParams = matchWrap();
        primaryParams.topMargin = dp(14);
        card.addView(anchorPrimaryButton, primaryParams);

        markAnchorButton = smallButton("Drop anchor here", this::beginMarkAnchor);
        LinearLayout.LayoutParams markParams = wrapWrap();
        markParams.topMargin = dp(5);
        card.addView(markAnchorButton, markParams);

        silenceAnchorButton = actionButton("Silence alarm", R.color.nav_warning);
        silenceAnchorButton.setOnClickListener(view -> sendServiceAction(
                BridgeService.ACTION_SILENCE_ANCHOR));
        LinearLayout.LayoutParams silenceParams = matchWrap();
        silenceParams.topMargin = dp(8);
        card.addView(silenceAnchorButton, silenceParams);

        anchorMessage = text("", 13, R.color.nav_error);
        anchorMessage.setLineSpacing(0.0f, 1.12f);
        anchorMessage.setPadding(0, dp(9), 0, 0);
        card.addView(anchorMessage, matchWrap());

        TextView behavior = text(
                "A drift must remain outside the radius for 5 seconds. The alarm also sounds "
                        + "if GPS is lost for 30 seconds.",
                12, R.color.nav_secondary);
        behavior.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams behaviorParams = matchWrap();
        behaviorParams.topMargin = dp(10);
        card.addView(behavior, behaviorParams);
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
        addCardHeader(card, "Paired shore alarm");
        TextView description = text(
                "Send encrypted heartbeats and alarm events directly over this subnet or Tailscale. The shore phone is read-only and silences only itself.",
                13,
                R.color.nav_secondary);
        description.setLineSpacing(0.0f, 1.12f);
        card.addView(description, matchWrap());
        TextView status = addDetailRow(card, "Shore phone", false);
        if (armedCard) armedShoreMonitorValue = status;
        else normalShoreMonitorValue = status;

        Button pair = smallButton("Show pairing QR", () -> startActivity(
                new Intent(this, AnchorPairingActivity.class)));
        LinearLayout.LayoutParams pairParams = wrapWrap();
        pairParams.topMargin = dp(10);
        card.addView(pair, pairParams);

        Button monitor = smallButton("Use this phone on shore", () -> startActivity(
                new Intent(this, ShoreMonitorActivity.class)));
        LinearLayout.LayoutParams monitorParams = wrapWrap();
        monitorParams.topMargin = dp(6);
        card.addView(monitor, monitorParams);
        return card;
    }

    private View buildNetworkCard() {
        LinearLayout card = card();
        addCardHeader(card, "Navionics connection");
        TextView protocolValue = addDetailRow(card, "Protocol", false);
        protocolValue.setText("TCP + UDP broadcast");
        TextView portValue = addDetailRow(card, "Port", true);
        portValue.setText(String.valueOf(BridgeService.PORT));
        clientsValue = addDetailRow(card, "TCP clients", false);
        udpTargetsValue = addDetailRow(card, "UDP networks", false);
        serverValue = addDetailRow(card, "TCP server", false);

        View divider = new View(this);
        divider.setBackgroundColor(0xffe4e7ec);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(12);
        card.addView(divider, dividerParams);

        TextView hint = text(
                "TCP fallback — enter one of these addresses in Navionics:",
                14,
                R.color.nav_secondary);
        card.addView(hint, matchWrap());

        addressesContainer = new LinearLayout(this);
        addressesContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams addressesParams = matchWrap();
        addressesParams.topMargin = dp(6);
        card.addView(addressesContainer, addressesParams);

        Button refresh = smallButton("Refresh Addresses", () -> {
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
        addInstruction(card, 2, "Tap Start Streaming and allow Precise Location and notifications.");
        addInstruction(card, 3, "In Navionics: Menu → Connect a Device → Add Device.");
        addInstruction(card, 4, "Choose UDP. Enter host 0.0.0.0 and port " + BridgeService.PORT + ".");
        addInstruction(card, 5, "Save the device. TCP remains available as a fallback using an address above.");
        addInstruction(card, 6, "Keep this phone powered during navigation.");
        return card;
    }

    private View buildDiagnosticsCard() {
        LinearLayout card = card();
        addCardHeader(card, "Last NMEA transmission");
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
        boolean armedScreen = state.anchorAlarmArmed;
        pageTitle.setText(armedScreen ? "Anchor watch" : "GPS → Navionics");
        normalDashboard.setVisibility(armedScreen ? View.GONE : View.VISIBLE);
        armedAnchorDashboard.setVisibility(armedScreen ? View.VISIBLE : View.GONE);
        if (armedScreen != lastRenderedAnchorArmed) {
            lastRenderedAnchorArmed = armedScreen;
            pageScrollView.post(() -> pageScrollView.scrollTo(0, 0));
        }

        boolean fineLocation = hasFineLocationPermission();
        boolean active = state.streaming;
        statusTitle.setText(statusTitle(state, fineLocation));
        setCircleColor(statusDot, statusColor(state, fineLocation));
        statusDot.setContentDescription(statusTitle.getText());

        streamButton.setText(active ? "■  Stop Streaming" : "●  Start Streaming");
        streamButton.setBackgroundTintList(ColorStateList.valueOf(
                getColor(active ? R.color.nav_error : R.color.nav_blue)));
        renderAnchorAlarm(state);
        renderArmedAnchorDashboard(state);
        renderShoreMonitorStatus(state);

        String visibleError = state.error != null ? state.error : permissionError;
        errorText.setText(visibleError == null ? "" : visibleError);
        errorText.setVisibility(visibleError == null ? View.GONE : View.VISIBLE);
        settingsButton.setVisibility(fineLocation ? View.GONE : View.VISIBLE);

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

        if (active || state.anchorAlarmArmed) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void renderAnchorAlarm(BridgeState state) {
        selectedAnchorMode = state.anchorMode;
        if (!anchorRadiusTracking) selectedAnchorRadiusMeters = state.anchorRadiusMeters;
        renderingAnchorControls = true;
        shorelinedModeButton.setChecked(state.anchorMode == AnchorAlarm.Mode.SHORELINED);
        freeSwingingModeButton.setChecked(state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING);
        if (!anchorRadiusTracking) updateRadiusSlider();
        renderingAnchorControls = false;

        boolean armed = state.anchorAlarmArmed;
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
            setCircleColor(anchorStatusDot, getColor(R.color.nav_warning));
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
                    "For a boat free to swing. Tap Drop anchor here at the anchor-drop point, "
                            + "then arm after paying out the rode.");
        }

        shorelinedModeButton.setEnabled(!armed);
        freeSwingingModeButton.setEnabled(!armed);
        anchorRadiusSlider.setEnabled(!armed);
        anchorRadiusMinusButton.setEnabled(!armed);
        anchorRadiusPlusButton.setEnabled(!armed);
        if (state.hasAnchorReference) {
            anchorReferenceValue.setText(AnchorAlarm.coordinate(
                    state.anchorReferenceLatitude, state.anchorReferenceLongitude));
        } else {
            anchorReferenceValue.setText("Not set");
        }
        if (Double.isFinite(state.anchorDistanceMeters)) {
            anchorDistanceValue.setText(String.format(Locale.US, "%.1f m / %.0f m limit",
                    state.anchorDistanceMeters, state.anchorRadiusMeters));
        } else {
            anchorDistanceValue.setText("—");
        }

        if (armed) {
            anchorPrimaryButton.setText("■  Disarm anchor alarm");
            anchorPrimaryButton.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.nav_error)));
        } else if (state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !state.hasAnchorReference) {
            anchorPrimaryButton.setText("⚓  Drop anchor here");
            anchorPrimaryButton.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.nav_blue)));
        } else {
            anchorPrimaryButton.setText("●  Arm anchor alarm");
            anchorPrimaryButton.setBackgroundTintList(
                    ColorStateList.valueOf(getColor(R.color.nav_blue)));
        }
        markAnchorButton.setVisibility(
                !armed && state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING
                        ? View.VISIBLE : View.GONE);
        markAnchorButton.setText(state.hasAnchorReference
                ? "Update anchor position here" : "Drop anchor here");
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
            value = "Connected • alarm ready";
            color = R.color.nav_success;
        } else if (state.shoreMonitorConnected) {
            value = "Connected • shore sound setup incomplete";
            color = R.color.nav_warning;
        } else {
            value = "Not connected";
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
        if (state.anchorAlarmTriggered) {
            armedAnchorStatusTitle.setText("ANCHOR ALARM");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_error));
        } else if (state.anchorAlarmSilenced) {
            armedAnchorStatusTitle.setText("Alarm silenced • monitoring");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_warning));
        } else {
            armedAnchorStatusTitle.setText("Anchor alarm armed");
            setCircleColor(armedAnchorStatusDot, getColor(R.color.nav_success));
        }
        armedAnchorMode.setText(state.anchorMode.displayName);
        armedAnchorDistance.setText(Double.isFinite(state.anchorDistanceMeters)
                ? String.format(Locale.US, "%.1f m", state.anchorDistanceMeters) : "—");
        armedAnchorDistance.setTextColor(getColor(
                state.anchorAlarmTriggered ? R.color.nav_error : R.color.nav_text));
        boolean radiusAdjustable = state.anchorAlarmArmed
                && state.anchorMode == AnchorAlarm.Mode.FREE_SWINGING;
        armedSwingRadiusControls.setVisibility(radiusAdjustable ? View.VISIBLE : View.GONE);
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
        String message = state.anchorAlertReason;
        armedAnchorMessage.setText(message == null ? "" : message);
        armedAnchorMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        armedAnchorMessage.setTextColor(getColor(
                state.anchorAlarmTriggered ? R.color.nav_error : R.color.nav_warning));
        armedAnchorSilenceButton.setVisibility(
                state.anchorAlarmTriggered ? View.VISIBLE : View.GONE);
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
            pendingPermissionAction = PENDING_NONE;
            sendServiceAction(BridgeService.ACTION_DISARM_ANCHOR);
            return;
        }
        if (selectedAnchorMode == AnchorAlarm.Mode.FREE_SWINGING
                && !latestState.hasAnchorReference) {
            beginMarkAnchor();
            return;
        }
        if (!latestState.shoreMonitorConnected || !latestState.shoreMonitorSoundReady) {
            new AlertDialog.Builder(this)
                    .setTitle("Shore alarm is not ready")
                    .setMessage("You can arm in degraded mode, but the shore phone may not receive or sound alarms. Pair it and complete its alarm setup when possible.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Arm anyway", (dialog, which) ->
                            beginPermissionProtectedAction(PENDING_ARM_ANCHOR))
                    .show();
            return;
        }
        beginPermissionProtectedAction(PENDING_ARM_ANCHOR);
    }

    private void beginMarkAnchor() {
        anchorActionMessage = null;
        beginPermissionProtectedAction(PENDING_MARK_ANCHOR);
    }

    private void beginPermissionProtectedAction(int action) {
        pendingPermissionAction = action;

        List<String> missingPermissions = new ArrayList<>();
        if (!hasFineLocationPermission()) {
            missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            missingPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
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
            startBridgeService();
            return;
        }
        if (!hasFreshGpsFix(latestState)) {
            anchorActionMessage = "Waiting for a fresh GPS fix…";
            anchorMessage.setText(anchorActionMessage);
            anchorMessage.setVisibility(View.VISIBLE);
            if (binder != null) binder.refreshLocationUpdates();
            return;
        }

        int action = pendingPermissionAction;
        pendingPermissionAction = PENDING_NONE;
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
            pendingPermissionAction = PENDING_NONE;
            permissionError = "Choose Precise Location for GPS streaming and the anchor alarm.";
            render(latestState);
        }
    }

    private void startBridgeService() {
        Intent startIntent = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_START);
        startForegroundServiceCompat(startIntent);
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

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
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

    private Button smallButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(getColor(R.color.nav_blue));
        button.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        button.setMinHeight(dp(36));
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
        button.setMinHeight(dp(52));
        button.setBackgroundTintList(ColorStateList.valueOf(getColor(colorResource)));
        return button;
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
        updateRadiusSlider();
        if (armedAnchorRadiusValue != null) {
            armedAnchorRadiusValue.setText(String.format(
                    Locale.US, "%.0f m", selectedAnchorRadiusMeters));
        }
        if (binder != null) {
            binder.updateAnchorSettings(selectedAnchorMode, selectedAnchorRadiusMeters);
        }
    }

    private void updateRadiusSlider() {
        if (anchorRadiusSlider == null) return;
        anchorRadiusSlider.setMax(radiusMaximumProgress(selectedAnchorMode));
        anchorRadiusSlider.setProgress(radiusProgress(
                selectedAnchorMode, selectedAnchorRadiusMeters));
        anchorRadiusValue.setText(String.format(
                Locale.US, "%.0f m", selectedAnchorRadiusMeters));
        anchorRadiusHint.setText(String.format(
                Locale.US, "%.0f–%.0f m range • %.0f m steps",
                selectedAnchorMode.minimumRadiusMeters,
                selectedAnchorMode.maximumRadiusMeters,
                selectedAnchorMode.radiusStepMeters));
    }

    private static int radiusMaximumProgress(AnchorAlarm.Mode mode) {
        return (int) Math.round(
                (mode.maximumRadiusMeters - mode.minimumRadiusMeters) / mode.radiusStepMeters);
    }

    private static int radiusProgress(AnchorAlarm.Mode mode, double radiusMeters) {
        int progress = (int) Math.round(
                (radiusMeters - mode.minimumRadiusMeters) / mode.radiusStepMeters);
        return Math.max(0, Math.min(radiusMaximumProgress(mode), progress));
    }

    private static double radiusFromProgress(AnchorAlarm.Mode mode, int progress) {
        int safeProgress = Math.max(0, Math.min(radiusMaximumProgress(mode), progress));
        return mode.minimumRadiusMeters + safeProgress * mode.radiusStepMeters;
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
                Double.POSITIVE_INFINITY, 0, 0, "Stopped",
                new ArrayList<>(), "", null,
                AnchorAlarm.Mode.SHORELINED,
                false, false, false, false,
                0.0, 0.0, 15.0, Double.NaN, new ArrayList<>(), null,
                false, false);
    }
}
