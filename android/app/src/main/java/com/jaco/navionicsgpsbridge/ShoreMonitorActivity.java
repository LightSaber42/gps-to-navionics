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
    private TextView statusValue;
    private TextView alarmValue;
    private TextView distanceValue;
    private TextView limitValue;
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
    private Button reconnectButton;
    private Button startButton;
    private Button stopButton;
    private Button unpairButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Shore anchor monitor");
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
        runOnUiThread(() -> render(state));
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

        TextView title = text("Shore anchor monitor", 28, R.color.nav_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title, matchWrap());
        TextView subtitle = text(
                "Read-only view of the authoritative device left aboard.",
                15,
                R.color.nav_secondary);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.bottomMargin = dp(18);
        page.addView(subtitle, subtitleParams);

        LinearLayout statusCard = card();
        statusValue = detail(statusCard, "Connection", "Not paired");
        readinessValue = detail(statusCard, "Alarm sound", "Needs setup");
        heartbeatValue = detail(statusCard, "Last heartbeat", "—");
        page.addView(statusCard, cardParams());

        LinearLayout remoteAccessCard = warningCard();
        TextView remoteAccessTitle = text(
                "Remote monitoring requires a VPN", 18, R.color.nav_warning);
        remoteAccessTitle.setTypeface(Typeface.DEFAULT_BOLD);
        remoteAccessCard.addView(remoteAccessTitle, matchWrap());
        TextView remoteAccessHelp = text(
                "Before pairing for remote use, install Tailscale on both phones, sign in to the same Tailscale network, and connect it. Pairing alone does not provide internet access. Without Tailscale or an equivalent VPN setup, monitoring works only on the same local network.",
                14,
                R.color.nav_text);
        LinearLayout.LayoutParams remoteAccessHelpParams = matchWrap();
        remoteAccessHelpParams.topMargin = dp(8);
        remoteAccessCard.addView(remoteAccessHelp, remoteAccessHelpParams);
        Button tailscaleSetup = button("Tailscale setup instructions", R.color.nav_blue_dark);
        tailscaleSetup.setOnClickListener(view -> openTailscaleSetup());
        LinearLayout.LayoutParams tailscaleSetupParams = matchWrap();
        tailscaleSetupParams.topMargin = dp(12);
        remoteAccessCard.addView(tailscaleSetup, tailscaleSetupParams);
        page.addView(remoteAccessCard, cardParams());

        alarmCard = card();
        TextView alarmHeader = text("Alarm state", 13, R.color.nav_secondary);
        alarmHeader.setAllCaps(true);
        alarmCard.addView(alarmHeader, matchWrap());
        alarmValue = text("No active alarm", 19, R.color.nav_success);
        alarmValue.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams alarmParams = matchWrap();
        alarmParams.topMargin = dp(8);
        alarmParams.bottomMargin = dp(12);
        alarmCard.addView(alarmValue, alarmParams);
        silenceButton = button("Silence on this phone", R.color.nav_warning);
        silenceButton.setVisibility(View.GONE);
        silenceButton.setOnClickListener(view -> startService(
                new Intent(this, ShoreMonitorService.class)
                        .setAction(ShoreMonitorService.ACTION_SILENCE)));
        alarmCard.addView(silenceButton, matchWrap());
        page.addView(alarmCard, cardParams());

        plotCard = card();
        TextView plotHeader = text("Boat position history", 18, R.color.nav_text);
        plotHeader.setTypeface(Typeface.DEFAULT_BOLD);
        plotCard.addView(plotHeader, matchWrap());
        distanceValue = detail(plotCard, "Current distance", "—");
        limitValue = detail(plotCard, "Alarm limit", "—");
        batteryValue = detail(plotCard, "Boat battery", "—");
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
        TextView pairingHeader = text("Pair this shore phone", 18, R.color.nav_text);
        pairingHeader.setTypeface(Typeface.DEFAULT_BOLD);
        pairingCard.addView(pairingHeader, matchWrap());
        TextView pairingHelp = text(
                "On the boat device open Pair shore monitor. Scan its QR with this phone's Camera and tap the link. You can also paste the pairing link below.",
                14,
                R.color.nav_secondary);
        LinearLayout.LayoutParams helpParams = matchWrap();
        helpParams.topMargin = dp(8);
        helpParams.bottomMargin = dp(12);
        pairingCard.addView(pairingHelp, helpParams);
        manualPairingField = new EditText(this);
        manualPairingField.setHint("navionicsgpsbridge://pair?…");
        manualPairingField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        manualPairingField.setSingleLine(false);
        pairingCard.addView(manualPairingField, matchWrap());
        Button applyPairing = button("Use pairing link", R.color.nav_blue);
        applyPairing.setOnClickListener(view -> pair(Uri.parse(
                manualPairingField.getText().toString().trim())));
        LinearLayout.LayoutParams applyParams = matchWrap();
        applyParams.topMargin = dp(10);
        pairingCard.addView(applyPairing, applyParams);
        page.addView(pairingCard, cardParams());

        safetyCard = card();
        TextView safetyHeader = text("Required alarm setup", 18, R.color.nav_text);
        safetyHeader.setTypeface(Typeface.DEFAULT_BOLD);
        safetyCard.addView(safetyHeader, matchWrap());
        TextView safetyHelp = text(
                "Allow notifications and Do Not Disturb access so anchor alarms can sound while this phone is muted or locked. Set this app's battery use to Unrestricted for reliable 12-hour monitoring.",
                14,
                R.color.nav_secondary);
        LinearLayout.LayoutParams safetyHelpParams = matchWrap();
        safetyHelpParams.topMargin = dp(8);
        safetyHelpParams.bottomMargin = dp(12);
        safetyCard.addView(safetyHelp, safetyHelpParams);
        Button dndButton = button("Open Do Not Disturb access", R.color.nav_blue_dark);
        dndButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
        safetyCard.addView(dndButton, matchWrap());
        Button batteryButton = button("Open app battery settings", R.color.nav_blue_dark);
        batteryButton.setOnClickListener(view -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams batteryParams = matchWrap();
        batteryParams.topMargin = dp(10);
        safetyCard.addView(batteryButton, batteryParams);
        page.addView(safetyCard, cardParams());

        reconnectButton = button("Reconnect now", R.color.nav_blue);
        reconnectButton.setOnClickListener(view -> {
            if (binder != null) binder.reconnectNow();
            ShoreMonitorService.start(this);
        });
        page.addView(reconnectButton, matchWrap());

        startButton = button("Start monitoring", R.color.nav_blue);
        startButton.setOnClickListener(view -> ShoreMonitorService.start(this));
        page.addView(startButton, matchWrap());

        stopButton = button("Stop monitoring", R.color.nav_error);
        LinearLayout.LayoutParams stopParams = matchWrap();
        stopParams.topMargin = dp(12);
        page.addView(stopButton, stopParams);
        stopButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Stop shore monitoring?")
                .setMessage("This phone will stop connecting to the boat and will not sound "
                        + "remote anchor alarms. The pairing will be kept so you can start "
                        + "monitoring again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Stop monitoring", (dialog, which) -> startService(
                        new Intent(this, ShoreMonitorService.class)
                                .setAction(ShoreMonitorService.ACTION_STOP)))
                .show());

        unpairButton = button("Unpair this phone", R.color.nav_error);
        LinearLayout.LayoutParams unpairParams = matchWrap();
        unpairParams.topMargin = dp(12);
        page.addView(unpairButton, unpairParams);
        unpairButton.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle("Unpair from the boat?")
                .setMessage("Monitoring will stop and the saved pairing will be removed. "
                        + "You will need a new pairing link from the boat device to monitor "
                        + "it again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Unpair", (dialog, which) -> startService(
                        new Intent(this, ShoreMonitorService.class)
                                .setAction(ShoreMonitorService.ACTION_UNPAIR)))
                .show());
        boolean paired = hasStoredPairing();
        boolean enabled = paired && ShoreMonitorService.isEnabled(this);
        pairingCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        alarmCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        plotCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        safetyCard.setVisibility(enabled ? View.VISIBLE : View.GONE);
        reconnectButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        startButton.setVisibility(paired && !enabled ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        unpairButton.setVisibility(paired ? View.VISIBLE : View.GONE);
        return scroll;
    }

    private void render(ShoreMonitorState state) {
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
        statusValue.setText(state.connected ? "Connected" : state.statusMessage);
        statusValue.setTextColor(getColor(state.connected
                ? R.color.nav_success : enabled ? R.color.nav_warning : R.color.nav_secondary));
        readinessValue.setText(state.soundReady
                ? "Ready, including DND" : "Setup incomplete — tap below");
        readinessValue.setTextColor(getColor(state.soundReady
                ? R.color.nav_success : R.color.nav_error));
        heartbeatValue.setText(state.lastHeartbeatAtMillis <= 0L
                ? "—"
                : DateFormat.getTimeInstance(DateFormat.MEDIUM)
                        .format(new Date(state.lastHeartbeatAtMillis)));
        alarmValue.setText(state.alarmActive ? state.alarmReason
                : state.armed ? "Monitoring — no active alarm" : "Boat watch is disarmed");
        alarmValue.setTextColor(getColor(state.alarmActive
                ? R.color.nav_error : state.armed ? R.color.nav_success : R.color.nav_secondary));
        silenceButton.setVisibility(state.alarmActive ? View.VISIBLE : View.GONE);
        distanceValue.setText(Double.isFinite(state.distanceMeters)
                ? String.format(Locale.US, "%.1f m", state.distanceMeters) : "—");
        limitValue.setText(String.format(Locale.US, "%.0f m", state.radiusMeters));
        batteryValue.setText(state.boatBatteryPercent < 0
                ? "Unknown"
                : state.boatBatteryPercent + "%" + (state.boatCharging ? " • charging" : ""));
        plotView.setRemoteState(
                state.history, state.radiusMeters, state.alarmActive, state.distanceMeters);
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
                            ? "Paired. Keep Tailscale connected for remote monitoring."
                            : "Paired, but no Tailscale address was detected.",
                    Toast.LENGTH_LONG).show();
            if (binder != null) binder.reconnectNow();
            if (!includesTailscaleAddress) showRemoteAccessWarning();
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            new AlertDialog.Builder(this)
                    .setTitle("Pairing link not accepted")
                    .setMessage(error.getLocalizedMessage())
                    .setPositiveButton("OK", null)
                    .show();
        }
    }

    private void showRemoteAccessWarning() {
        new AlertDialog.Builder(this)
                .setTitle("Remote access is not set up")
                .setMessage("This pairing code has no Tailscale address. Unless one of its addresses belongs to another VPN, monitoring will work only on the same local network. Set up a VPN on both phones, then create and scan a new pairing code.")
                .setNegativeButton("Continue locally", null)
                .setPositiveButton("Tailscale instructions", (dialog, which) -> openTailscaleSetup())
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
