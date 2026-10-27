package com.jaco.navionicsgpsbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.WriterException;

import java.security.GeneralSecurityException;
import java.util.List;

/** QR/manual credentials for pairing one read-only shore monitor. */
public final class AnchorPairingActivity extends Activity implements BridgeService.StateListener {
    private static final Uri TAILSCALE_SETUP_URI =
            Uri.parse("https://tailscale.com/docs/how-to/quickstart");

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (BridgeService.LocalBinder) service;
            binder.addListener(AnchorPairingActivity.this);
            refreshPairingCode();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private AnchorPairingStore pairingStore;
    private BridgeService.LocalBinder binder;
    private boolean bound;
    private ImageView qrView;
    private TextView addressValue;
    private TextView pairingLinkValue;
    private TextView errorValue;
    private TextView monitorStatusValue;
    private TextView remoteAccessStatusValue;
    private Uri pairingUri;
    private boolean refreshPairingAfterState;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pairingStore = new AnchorPairingStore(this);
        setContentView(buildContent());
        showRemoteAccessRequired();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (binder != null) refreshPairingCode();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(
                new Intent(this, BridgeService.class),
                serviceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        if (binder != null) binder.removeListener(this);
        binder = null;
        if (bound) unbindService(serviceConnection);
        bound = false;
        super.onStop();
    }

    @Override
    public void onBridgeStateChanged(BridgeState state) {
        runOnUiThread(() -> {
            if (monitorStatusValue == null) return;
            if (binder == null || !binder.canUseRemoteAnchorWatch()) {
                showRemoteAccessRequired();
                return;
            }
            if (refreshPairingAfterState) {
                refreshPairingAfterState = false;
                refreshPairingCode();
            }
            if (state.shoreMonitorConnected && state.shoreMonitorSoundReady) {
                monitorStatusValue.setText(R.string.connected_sound_ready);
                monitorStatusValue.setTextColor(getColor(R.color.nav_success));
            } else if (state.shoreMonitorConnected) {
                monitorStatusValue.setText(R.string.connected_setup_needed);
                monitorStatusValue.setTextColor(getColor(R.color.nav_warning));
            } else {
                monitorStatusValue.setText(R.string.not_paired_waiting_shore_monitor);
                monitorStatusValue.setTextColor(getColor(R.color.nav_secondary));
            }
        });
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.nav_background));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(32));
        ScrollView.LayoutParams pageParams = new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT);
        pageParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        scroll.addView(page, pageParams);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            ViewGroup.LayoutParams params = page.getLayoutParams();
            int targetWidth = Math.min(right - left, dp(600));
            if (params.width != targetWidth) {
                params.width = targetWidth;
                page.setLayoutParams(params);
            }
        });

        TextView title = text(getString(R.string.pair_shore_monitor), 28, R.color.nav_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title, matchWrap());
        TextView subtitle = text(
                getString(R.string.pairing_authority_explanation),
                15,
                R.color.nav_secondary);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6);
        subtitleParams.bottomMargin = dp(18);
        page.addView(subtitle, subtitleParams);

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
        remoteAccessStatusValue = text("", 14, R.color.nav_warning);
        remoteAccessStatusValue.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams remoteAccessStatusParams = matchWrap();
        remoteAccessStatusParams.topMargin = dp(10);
        remoteAccessCard.addView(remoteAccessStatusValue, remoteAccessStatusParams);
        Button tailscaleSetup = button(
                getString(R.string.tailscale_setup_instructions), R.color.nav_blue_dark);
        tailscaleSetup.setOnClickListener(view -> openTailscaleSetup());
        LinearLayout.LayoutParams tailscaleSetupParams = matchWrap();
        tailscaleSetupParams.topMargin = dp(12);
        remoteAccessCard.addView(tailscaleSetup, tailscaleSetupParams);
        page.addView(remoteAccessCard, cardParams());

        LinearLayout card = card();
        monitorStatusValue = text(
                getString(R.string.not_paired_waiting_shore_monitor),
                14,
                R.color.nav_secondary);
        monitorStatusValue.setTypeface(Typeface.DEFAULT_BOLD);
        monitorStatusValue.setGravity(Gravity.CENTER);
        card.addView(monitorStatusValue, matchWrap());
        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setMaxWidth(dp(310));
        qrView.setMaxHeight(dp(310));
        qrView.setContentDescription(getString(R.string.shore_monitor_pairing_qr_code));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = dp(12);
        card.addView(qrView, qrParams);
        TextView instruction = text(
                getString(R.string.scan_pairing_code_instruction), 15, R.color.nav_text);
        instruction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams instructionParams = matchWrap();
        instructionParams.topMargin = dp(12);
        card.addView(instruction, instructionParams);
        addressValue = text("", 13, R.color.nav_secondary);
        addressValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addressParams = matchWrap();
        addressParams.topMargin = dp(10);
        card.addView(addressValue, addressParams);
        errorValue = text("", 14, R.color.nav_error);
        errorValue.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams errorParams = matchWrap();
        errorParams.topMargin = dp(8);
        card.addView(errorValue, errorParams);
        Button share = button(getString(R.string.share_pairing_link), R.color.nav_blue);
        share.setOnClickListener(view -> sharePairingLink());
        LinearLayout.LayoutParams shareParams = matchWrap();
        shareParams.topMargin = dp(12);
        card.addView(share, shareParams);
        page.addView(card, cardParams());

        LinearLayout options = card();
        options.setVisibility(View.GONE);
        TextView manualTitle = text(
                getString(R.string.pairing_link), 14, R.color.nav_secondary);
        manualTitle.setTypeface(Typeface.DEFAULT_BOLD);
        options.addView(manualTitle, matchWrap());
        pairingLinkValue = text("", 12, R.color.nav_secondary);
        pairingLinkValue.setTextIsSelectable(true);
        pairingLinkValue.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams linkParams = matchWrap();
        linkParams.topMargin = dp(10);
        linkParams.bottomMargin = dp(12);
        options.addView(pairingLinkValue, linkParams);

        Button refresh = button(getString(R.string.refresh_addresses), R.color.nav_blue_dark);
        refresh.setOnClickListener(view -> refreshPairingCode());
        options.addView(refresh, matchWrap());

        Button rotate = button(getString(R.string.replace_paired_monitor), R.color.nav_error);
        LinearLayout.LayoutParams rotateParams = matchWrap();
        rotateParams.topMargin = dp(12);
        options.addView(rotate, rotateParams);
        rotate.setOnClickListener(view -> confirmRotate());

        Button optionsToggle = disclosureButton(getString(R.string.more_options));
        optionsToggle.setOnClickListener(view -> {
            boolean expanded = options.getVisibility() == View.VISIBLE;
            options.setVisibility(expanded ? View.GONE : View.VISIBLE);
            optionsToggle.setText(expanded
                    ? getString(R.string.more_options_collapsed)
                    : getString(R.string.more_options_expanded));
        });
        page.addView(optionsToggle, cardParams());
        page.addView(options, cardParams());
        return scroll;
    }

    private void refreshPairingCode() {
        if (binder == null || !binder.canUseRemoteAnchorWatch()) {
            showRemoteAccessRequired();
            return;
        }
        try {
            AnchorPairingStore.HostConfiguration configuration =
                    pairingStore.loadOrCreateHost();
            List<BridgeState.NetworkAddress> addresses =
                    AnchorPairingAddressProvider.currentIpv4Addresses();
            if (addresses.isEmpty()) {
                pairingUri = null;
                pairingLinkValue.setText("");
                qrView.setImageDrawable(null);
                addressValue.setText(R.string.no_pairing_address_available);
                errorValue.setText(R.string.connect_phones_then_refresh);
                remoteAccessStatusValue.setText(R.string.no_tailscale_local_only);
                remoteAccessStatusValue.setTextColor(getColor(R.color.nav_warning));
                return;
            }
            pairingUri = pairingStore.pairingUri(configuration, addresses);
            pairingLinkValue.setText(pairingUri.toString());
            qrView.setImageBitmap(AnchorQrCode.bitmap(pairingUri.toString(), dp(310)));
            StringBuilder addressText = new StringBuilder(getResources().getQuantityString(
                    R.plurals.boat_addresses,
                    addresses.size()));
            addressText.append(": ");
            for (int index = 0; index < addresses.size(); index++) {
                if (index > 0) addressText.append(", ");
                addressText.append(addresses.get(index).address);
            }
            addressValue.setText(addressText.toString());
            boolean includesTailscaleAddress = addresses.stream()
                    .anyMatch(address -> address.address.startsWith("100."));
            remoteAccessStatusValue.setText(includesTailscaleAddress
                    ? R.string.tailscale_address_included
                    : R.string.no_tailscale_unless_other_vpn);
            remoteAccessStatusValue.setTextColor(getColor(includesTailscaleAddress
                    ? R.color.nav_success : R.color.nav_warning));
            errorValue.setText("");
        } catch (GeneralSecurityException | WriterException error) {
            qrView.setImageDrawable(null);
            errorValue.setText(getString(
                    R.string.pairing_code_failed,
                    error.getLocalizedMessage()));
        }
    }

    private void showRemoteAccessRequired() {
        pairingUri = null;
        if (pairingLinkValue != null) pairingLinkValue.setText("");
        if (qrView != null) qrView.setImageDrawable(null);
        if (addressValue != null) addressValue.setText(R.string.pairing_unavailable);
        if (errorValue != null) {
            errorValue.setText(R.string.unlock_remote_for_pairing);
        }
        if (monitorStatusValue != null) {
            monitorStatusValue.setText(R.string.remote_anchor_watch_required);
            monitorStatusValue.setTextColor(getColor(R.color.nav_warning));
        }
    }

    private void sharePairingLink() {
        if (pairingUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, pairingUri.toString())
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.anchor_monitor_pairing));
        startActivity(Intent.createChooser(share, getString(R.string.share_pairing_link)));
    }

    private void openTailscaleSetup() {
        startActivity(new Intent(Intent.ACTION_VIEW, TAILSCALE_SETUP_URI));
    }

    private void confirmRotate() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.invalidate_current_pairing_question)
                .setMessage(R.string.invalidate_current_pairing_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.make_new_code, (dialog, which) -> {
                    if (binder == null || !binder.canUseRemoteAnchorWatch()) {
                        showRemoteAccessRequired();
                        return;
                    }
                    refreshPairingAfterState = true;
                    startService(new Intent(this, BridgeService.class)
                            .setAction(BridgeService.ACTION_ROTATE_ANCHOR_PAIRING));
                })
                .show();
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

    private TextView text(String value, int size, int colorResource) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(colorResource));
        return view;
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

    private Button disclosureButton(String title) {
        Button button = new Button(this);
        button.setText(getString(R.string.disclosure_collapsed, title));
        button.setTextSize(16);
        button.setTextColor(getColor(R.color.nav_text));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(54));
        GradientDrawable background = new GradientDrawable();
        background.setColor(getColor(R.color.nav_card));
        background.setCornerRadius(dp(16));
        button.setBackground(background);
        button.setElevation(dp(1));
        return button;
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
