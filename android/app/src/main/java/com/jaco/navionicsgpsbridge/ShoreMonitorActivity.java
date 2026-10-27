package com.jaco.navionicsgpsbridge;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** Read-only companion UI. All boat-side anchor controls intentionally live elsewhere. */
public final class ShoreMonitorActivity extends Activity
        implements ShoreMonitorService.StateListener {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 81;
    private static final Uri TAILSCALE_SETUP_URI =
            Uri.parse("https://tailscale.com/docs/how-to/quickstart");

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (ShoreMonitorService.LocalBinder) service;
            binder.addListener(ShoreMonitorActivity.this);
            binder.setHistoryVisible(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private ShoreMonitorService.LocalBinder binder;
    private boolean bound;
    private ShoreMonitorState latestState;
    private TextView statusValue;
    private TextView alarmValue;
    private TextView modeValue;
    private TextView distanceValue;
    private TextView limitValue;
    private TextView gpsAccuracyValue;
    private TextView plottedFixesValue;
    private TextView batteryValue;
    private TextView heartbeatValue;
    private TextView readinessValue;
    private AnchorPlotView plotView;
    private Button silenceButton;
    private EditText manualPairingField;
    private LinearLayout alarmCard;
    private LinearLayout plotCard;
    private LinearLayout pairingCard;
    private LinearLayout safetyCard;
    private LinearLayout gpsAccuracyCard;
    private TextView gpsAccuracyWarning;
    private Button reconnectButton;
    private Button startButton;
    private Button stopButton;
    private Button unpairButton;
    private Button displayUnitsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.shore_anchor_monitor);
        setContentView(buildContent());
        handlePairingIntent(getIntent());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairingIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasStoredPairing() && ShoreMonitorService.isEnabled(this)) {
            ShoreMonitorService.start(this);
        }
        bound = bindService(
                new Intent(this, ShoreMonitorService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDisplayUnitButton();
        if (latestState != null) render(latestState);
    }

    @Override
    protected void onStop() {
        if (binder != null) {
            binder.setHistoryVisible(false);
            binder.removeListener(this);
        }
        binder = null;
        if (bound) unbindService(serviceConnection);
        bound = false;
        super.onStop();
    }

    @Override
    public void onShoreMonitorStateChanged(ShoreMonitorState state) {
        runOnUiThread(() -> {
            latestState = state;
            render(state);
        });
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.nav_background));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottomInset = Build.VERSION.SDK_INT >= 30
                    ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    : insets.getSystemWindowInsetBottom();
            page.setPadding(dp(18), dp(24), dp(18), dp(32) + bottomInset);
            return insets;
        });
        ScrollView.LayoutParams pageParams = new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        scroll.addView(page, pageParams);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup.LayoutParams params = page.getLayoutParams();
            int targetWidth = Math.min(right - left, dp(680));
            if (params.width != targetWidth) {
                params.width = targetWidth;
                page.setLayoutParams(params);
            }
        });

        TextView title = text(getString(R.string.shore_anchor_monitor), 28, R.color.nav_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title, matchWrap());
        TextView subtitle = text(
                getString(R.string.shore_monitor_subtitle), 15, R.color.nav_secondary);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.bottomMargin = dp(18);
        page.addView(subtitle, subtitleParams);

        LinearLayout statusCard = card();
        statusValue = detail(
                statusCard, getString(R.string.connection), getString(R.string.not_paired));
        readinessValue = detail(
                statusCard, getString(R.string.alarm_sound), getString(R.string.needs_setup));
        heartbeatValue = detail(statusCard, getString(R.string.last_heartbeat), "—");
        displayUnitsButton = button(
                getString(
                        R.string.display_units_value,
                        DisplayUnits.load(this).preferenceDetail()),
                R.color.nav_blue_dark);
        displayUnitsButton.setOnClickListener(view -> showDisplayUnitPicker());
        LinearLayout.LayoutParams displayUnitsParams = matchWrap();
        displayUnitsParams.topMargin = dp(10);
        statusCard.addView(displayUnitsButton, displayUnitsParams);
        page.addView(statusCard, cardParams());

        LinearLayout remoteAccessCard = warningCard();
        TextView remoteAccessTitle = text(
                getString(R.string.remote_monitoring_requires_vpn),
                18,
                R.color.nav_warning);
        remoteAccessTitle.setTypeface(Typeface.DEFAULT_BOLD);
        remoteAccessCard.addView(remoteAccessTitle, matchWrap());
        TextView remoteAccessHelp = text(
                getString(R.string.remote_monitoring_vpn_help), 14, R.color.nav_text);
        LinearLayout.LayoutParams remoteAccessHelpParams = matchWrap();
        remoteAccessHelpParams.topMargin = dp(8);
        remoteAccessCard.addView(remoteAccessHelp, remoteAccessHelpParams);
        Button tailscaleSetup = button(
                getString(R.string.tailscale_setup_instructions), R.color.nav_blue_dark);
        tailscaleSetup.setOnClickListener(view -> openTailscaleSetup());
        LinearLayout.LayoutParams tailscaleSetupParams = matchWrap();
        tailscaleSetupParams.topMargin = dp(12);
        remoteAccessCard.addView(tailscaleSetup, tailscaleSetupParams);
        page.addView(remoteAccessCard, cardParams());

        alarmCard = card();
        TextView alarmHeader = text(
                getString(R.string.alarm_state), 13, R.color.nav_secondary);
        alarmHeader.setAllCaps(true);
        alarmCard.addView(alarmHeader, matchWrap());
        alarmValue = text(getString(R.string.no_active_alarm), 19, R.color.nav_success);
        alarmValue.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams alarmParams = matchWrap();
        alarmParams.topMargin = dp(8);
        alarmParams.bottomMargin = dp(12);
        alarmCard.addView(alarmValue, alarmParams);
        silenceButton = button(
                getString(R.string.silence_on_this_phone), R.color.nav_warning);
        silenceButton.setVisibility(View.GONE);
        silenceButton.setOnClickListener(view -> startService(
                new Intent(this, ShoreMonitorService.class)
                        .setAction(ShoreMonitorService.ACTION_SILENCE)));
        alarmCard.addView(silenceButton, matchWrap());
        page.addView(alarmCard, cardParams());

        gpsAccuracyCard = warningCard();
        TextView gpsAccuracyTitle = text(
                getString(R.string.degraded_boat_gps_precision),
                18,
                R.color.nav_warning);
        gpsAccuracyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gpsAccuracyCard.addView(gpsAccuracyTitle, matchWrap());
        gpsAccuracyWarning = text("", 14, R.color.nav_text);
        gpsAccuracyWarning.setLineSpacing(0.0f, 1.12f);
        LinearLayout.LayoutParams gpsAccuracyParams = matchWrap();
        gpsAccuracyParams.topMargin = dp(8);
        gpsAccuracyCard.addView(gpsAccuracyWarning, gpsAccuracyParams);
        page.addView(gpsAccuracyCard, cardParams());

        plotCard = card();
        TextView plotHeader = text(
                getString(R.string.boat_position_history), 18, R.color.nav_text);
        plotHeader.setTypeface(Typeface.DEFAULT_BOLD);
        plotCard.addView(plotHeader, matchWrap());
        modeValue = detail(plotCard, getString(R.string.anchor_mode), "—");
        distanceValue = detail(plotCard, getString(R.string.current_distance), "—");
        limitValue = detail(plotCard, getString(R.string.alarm_limit), "—");
        gpsAccuracyValue = detail(plotCard, getString(R.string.gps_accuracy), "—");
        plottedFixesValue = detail(
                plotCard,
                getString(R.string.gps_trail),
                getResources().getQuantityString(R.plurals.plotted_fixes, 0, 0));
        batteryValue = detail(plotCard, getString(R.string.boat_battery), "—");
        plotView = new AnchorPlotView(this);
        GradientDrawable plotBackground = new GradientDrawable();
        plotBackground.setColor(getColor(R.color.nav_plot));
        plotBackground.setCornerRadius(dp(14));
        plotView.setBackground(plotBackground);
        LinearLayout.LayoutParams plotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        plotParams.topMargin = dp(14);
        plotCard.addView(plotView, plotParams);
        page.addView(plotCard, cardParams());

        pairingCard = card();
        TextView pairingHeader = text(
                getString(R.string.pair_this_shore_phone), 18, R.color.nav_text);
        pairingHeader.setTypeface(Typeface.DEFAULT_BOLD);
        pairingCard.addView(pairingHeader, matchWrap());
        TextView pairingHelp = text(
                getString(R.string.pair_shore_phone_help), 14, R.color.nav_secondary);
        LinearLayout.LayoutParams helpParams = matchWrap();
        helpParams.topMargin = dp(8);
        helpParams.bottomMargin = dp(12);
        pairingCard.addView(pairingHelp, helpParams);
        manualPairingField = new EditText(this);
        manualPairingField.setHint(R.string.pairing_link_hint);
        manualPairingField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        manualPairingField.setSingleLine(false);
        pairingCard.addView(manualPairingField, matchWrap());
        Button applyPairing = button(
                getString(R.string.use_pairing_link), R.color.nav_blue);
        applyPairing.setOnClickListener(view -> pair(Uri.parse(
                manualPairingField.getText().toString().trim())));
        LinearLayout.LayoutParams applyParams = matchWrap();
        applyParams.topMargin = dp(10);
        pairingCard.addView(applyPairing, applyParams);
        page.addView(pairingCard, cardParams());

        safetyCard = card();
        TextView safetyHeader = text(
                getString(R.string.required_alarm_setup), 18, R.color.nav_text);
        safetyHeader.setTypeface(Typeface.DEFAULT_BOLD);
        safetyCard.addView(safetyHeader, matchWrap());
        TextView safetyHelp = text(
                getString(R.string.shore_alarm_setup_help), 14, R.color.nav_secondary);
        LinearLayout.LayoutParams safetyHelpParams = matchWrap();
        safetyHelpParams.topMargin = dp(8);
        safetyHelpParams.bottomMargin = dp(12);
        safetyCard.addView(safetyHelp, safetyHelpParams);
        Button dndButton = button(
                getString(R.string.open_do_not_disturb_access), R.color.nav_blue_dark);
        dndButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        safetyCard.addView(dndButton, matchWrap());
        Button batteryButton = button(
                getString(R.string.open_app_battery_settings), R.color.nav_blue_dark);
        batteryButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams batteryParams = matchWrap();
        batteryParams.topMargin = dp(10);
        safetyCard.addView(batteryButton, batteryParams);
        page.addView(safetyCard, cardParams());

        reconnectButton = button(getString(R.string.reconnect_now), R.color.nav_blue);
        reconnectButton.setOnClickListener(view -> {
            if (binder != null) binder.reconnectNow();
            ShoreMonitorService.start(this);
        });
        page.addView(reconnectButton, matchWrap());

        startButton = button(getString(R.string.start_monitoring), R.color.nav_blue);
        startButton.setOnClickListener(view -> ShoreMonitorService.start(this));
        page.addView(startButton, matchWrap());

        stopButton = button(getString(R.string.stop_monitoring), R.color.nav_error);
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(12);
        page.addView(stopButton, stopParams);
        stopButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.stop_shore_monitoring_question)
                .setMessage(R.string.stop_shore_monitoring_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.stop_monitoring, (dialog, which) -> startService(
                        new Intent(this, ShoreMonitorService.class)
                                .setAction(ShoreMonitorService.ACTION_STOP)))
                .show());

        unpairButton = button(getString(R.string.unpair_this_phone), R.color.nav_error);
        LinearLayout.LayoutParams unpairParams = matchWrap();
        unpairParams.topMargin = dp(12);
        page.addView(unpairButton, unpairParams);
        unpairButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.unpair_from_boat_question)
                .setMessage(R.string.unpair_from_boat_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.unpair, (dialog, which) -> startService(
                        new Intent(this, ShoreMonitorService.class)
                                .setAction(ShoreMonitorService.ACTION_UNPAIR)))
                .show());
        boolean paired = hasStoredPairing();
        boolean enabled = paired && ShoreMonitorService.isEnabled(this);
        pairingCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        alarmCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        gpsAccuracyCard.setVisibility(View.GONE);
        plotCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        safetyCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        reconnectButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        startButton.setVisibility(paired && !enabled ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        unpairButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        return scroll;
    }

    private void render(ShoreMonitorState state) {
        DisplayUnits displayUnits = DisplayUnits.load(this);
        updateDisplayUnitButton();
        boolean paired = state.paired;
        boolean enabled = paired && state.enabled;
        pairingCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        alarmCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        plotCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        safetyCard.setVisibility(enabled && !state.soundReady ? View.VISIBLE : View.GONE);
        reconnectButton.setVisibility(enabled && !state.connected ? View.VISIBLE : View.GONE);
        startButton.setVisibility(paired && !enabled ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        unpairButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        statusValue.setText(state.connected
                ? state.hasBoatState
                ? getString(R.string.connected)
                : getString(R.string.connected_waiting_boat_state)
                : state.statusMessage);
        statusValue.setTextColor(getColor(state.connected
                ? R.color.nav_success : enabled ? R.color.nav_warning : R.color.nav_secondary));
        readinessValue.setText(state.soundReady
                ? R.string.ready_including_dnd : R.string.setup_incomplete_tap_below);
        readinessValue.setTextColor(getColor(state.soundReady
                ? R.color.nav_success : R.color.nav_error));
        heartbeatValue.setText(state.lastHeartbeatAtMillis <= 0L
                ? "—"
                : DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(new Date(state.lastHeartbeatAtMillis)));
        alarmValue.setText(!state.hasBoatState
                ? getString(R.string.waiting_for_boat_state)
                : state.alarmActive
                ? state.alarmReason
                : state.armed
                ? getString(R.string.monitoring_no_active_alarm)
                : getString(R.string.boat_watch_disarmed));
        alarmValue.setTextColor(getColor(!state.hasBoatState
                ? R.color.nav_secondary
                : state.alarmActive
                ? R.color.nav_error : state.armed ? R.color.nav_success : R.color.nav_secondary));
        silenceButton.setVisibility(state.alarmActive ? View.VISIBLE : View.GONE);
        String gpsWarning = state.connected && state.hasBoatState && state.armed
                ? AnchorGpsQuality.degradedWarning(
                        state.accuracyMeters,
                        state.radiusMeters,
                        displayUnits,
                        this)
                : null;
        gpsAccuracyWarning.setText(gpsWarning == null ? "" : gpsWarning);
        gpsAccuracyCard.setVisibility(gpsWarning == null ? View.GONE : View.VISIBLE);
        modeValue.setText(state.hasBoatState ? anchorModeDisplayName(state.mode) : "—");
        distanceValue.setText(state.hasBoatState && Double.isFinite(state.distanceMeters)
                ? displayUnits.format(state.distanceMeters, 1) : "—");
        limitValue.setText(state.hasBoatState
                ? displayUnits.format(state.radiusMeters) : "—");
        gpsAccuracyValue.setText(state.hasBoatState
                && Double.isFinite(state.accuracyMeters)
                && state.accuracyMeters >= 0.0
                ? displayUnits.formatAccuracy(state.accuracyMeters) : "—");
        plottedFixesValue.setText(getResources().getQuantityString(
                R.plurals.plotted_fixes,
                state.history.size(),
                state.history.size()));
        batteryValue.setText(!state.hasBoatState || state.boatBatteryPercent < 0
                ? getString(R.string.unknown)
                : getString(
                        state.boatCharging
                                ? R.string.battery_percent_charging
                                : R.string.battery_percent,
                        state.boatBatteryPercent));
        plotView.setDisplayUnits(displayUnits);
        plotView.setRemoteState(
                state.history, state.radiusMeters, state.alarmActive, state.distanceMeters);
    }

    private String anchorModeDisplayName(String value) {
        return getString(AnchorAlarm.Mode.fromStorageValue(value) == AnchorAlarm.Mode.FREE_SWINGING
                ? R.string.free_swinging : R.string.shorelined);
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
                    updateDisplayUnitButton();
                    if (latestState != null) render(latestState);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateDisplayUnitButton() {
        if (displayUnitsButton == null) return;
        displayUnitsButton.setText(getString(
                R.string.display_units_value,
                DisplayUnits.load(this).preferenceDetail()));
    }

    private void handlePairingIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data != null) pair(data);
    }

    private void pair(Uri uri) {
        try {
            AnchorPairingStore.MonitorConfiguration configuration =
                    AnchorPairingStore.parsePairingUri(uri);
            new AnchorPairingStore(this).saveMonitor(uri);
            ShoreMonitorService.start(this);
            boolean includesTailscaleAddress = configuration.hosts.stream()
                    .anyMatch(host -> host.startsWith("100."));
            Toast.makeText(
                    this,
                    includesTailscaleAddress
                            ? getString(R.string.paired_keep_tailscale_connected)
                            : getString(R.string.paired_no_tailscale_address),
                    Toast.LENGTH_LONG).show();
            if (binder != null) binder.reconnectNow();
            if (!includesTailscaleAddress) showRemoteAccessWarning();
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.pairing_link_not_accepted)
                    .setMessage(error.getLocalizedMessage())
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }

    private void showRemoteAccessWarning() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.remote_access_not_set_up)
                .setMessage(R.string.remote_access_not_set_up_message)
                .setNegativeButton(R.string.continue_locally, null)
                .setPositiveButton(
                        R.string.tailscale_instructions,
                        (dialog, which) -> openTailscaleSetup())
                .show();
    }

    private void openTailscaleSetup() {
        startActivity(new Intent(Intent.ACTION_VIEW, TAILSCALE_SETUP_URI));
    }

    private boolean hasStoredPairing() {
        try {
            return new AnchorPairingStore(this).loadMonitor() != null;
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            return false;
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_card));
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        card.setElevation(dp(2));
        return card;
    }

    private LinearLayout warningCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_warning_surface));
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), getColor(R.color.nav_warning));
        card.setBackground(background);
        return card;
    }

    private TextView detail(LinearLayout parent, String label, String initial) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelView = text(label, 14, R.color.nav_secondary);
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView value = text(initial, 14, R.color.nav_text);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        LinearLayout.LayoutParams rowParams = matchWrap();
        rowParams.topMargin = dp(7);
        parent.addView(row, rowParams);
        return value;
    }

    private Button button(String title, int colorResource) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(0xffffffff);
        button.setAllCaps(false);
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(colorResource));
        background.setCornerRadius(dp(12));
        button.setBackground(background);
        button.setMinHeight(dp(48));
        return button;
    }

    private TextView text(String value, int size, int colorResource) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(colorResource));
        return view;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
