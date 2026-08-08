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
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (BridgeService.LocalBinder) service;
            binder.addListener(AnchorPairingActivity.this);
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
    private Uri pairingUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pairingStore = new AnchorPairingStore(this);
        setContentView(buildContent());
        refreshPairingCode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPairingCode();
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
            if (state.shoreMonitorConnected && state.shoreMonitorSoundReady) {
                monitorStatusValue.setText("Connected · Sound ready");
                monitorStatusValue.setTextColor(getColor(R.color.nav_success));
            } else if (state.shoreMonitorConnected) {
                monitorStatusValue.setText("Connected · Setup needed");
                monitorStatusValue.setTextColor(getColor(R.color.nav_warning));
            } else {
                monitorStatusValue.setText("Not paired · Waiting for shore monitor");
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

        TextView title = text("Pair shore monitor", 28, R.color.nav_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title, matchWrap());
        TextView subtitle = text(
                "This boat device remains authoritative. The paired Android phone can view and silence only its own alarm.",
                15,
                R.color.nav_secondary);
        LinearLayout.LayoutParams subtitleParams = matchWrap();
        subtitleParams.topMargin = dp(6);
        subtitleParams.bottomMargin = dp(18);
        page.addView(subtitle, subtitleParams);

        LinearLayout card = card();
        monitorStatusValue = text("Not paired · Waiting for shore monitor", 14, R.color.nav_secondary);
        monitorStatusValue.setTypeface(Typeface.DEFAULT_BOLD);
        monitorStatusValue.setGravity(Gravity.CENTER);
        card.addView(monitorStatusValue, matchWrap());
        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setMaxWidth(dp(310));
        qrView.setMaxHeight(dp(310));
        qrView.setContentDescription("Shore-monitor pairing QR code");
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrParams.topMargin = dp(12);
        card.addView(qrView, qrParams);
        TextView instruction = text(
                "On the Android shore phone, use Camera to scan this code, then tap the pairing link.",
                15,
                R.color.nav_text);
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
        Button share = button("Share pairing link", R.color.nav_blue);
        share.setOnClickListener(view -> sharePairingLink());
        LinearLayout.LayoutParams shareParams = matchWrap();
        shareParams.topMargin = dp(12);
        card.addView(share, shareParams);
        page.addView(card, cardParams());

        LinearLayout options = card();
        options.setVisibility(View.GONE);
        TextView manualTitle = text("Pairing link", 14, R.color.nav_secondary);
        manualTitle.setTypeface(Typeface.DEFAULT_BOLD);
        options.addView(manualTitle, matchWrap());
        pairingLinkValue = text("", 12, R.color.nav_secondary);
        pairingLinkValue.setTextIsSelectable(true);
        pairingLinkValue.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams linkParams = matchWrap();
        linkParams.topMargin = dp(10);
        linkParams.bottomMargin = dp(12);
        options.addView(pairingLinkValue, linkParams);

        Button refresh = button("Refresh addresses", R.color.nav_blue_dark);
        refresh.setOnClickListener(view -> refreshPairingCode());
        options.addView(refresh, matchWrap());

        Button rotate = button("Replace paired monitor", R.color.nav_error);
        LinearLayout.LayoutParams rotateParams = matchWrap();
        rotateParams.topMargin = dp(12);
        options.addView(rotate, rotateParams);
        rotate.setOnClickListener(view -> confirmRotate());

        Button optionsToggle = disclosureButton("More options");
        optionsToggle.setOnClickListener(view -> {
            boolean expanded = options.getVisibility() == View.VISIBLE;
            options.setVisibility(expanded ? View.GONE : View.VISIBLE);
            optionsToggle.setText(expanded ? "More options  ›" : "More options  ⌄");
        });
        page.addView(optionsToggle, cardParams());
        page.addView(options, cardParams());
        return scroll;
    }

    private void refreshPairingCode() {
        try {
            AnchorPairingStore.HostConfiguration configuration =
                    pairingStore.loadOrCreateHost();
            List<BridgeState.NetworkAddress> addresses =
                    AnchorPairingAddressProvider.currentIpv4Addresses();
            if (addresses.isEmpty()) {
                pairingUri = null;
                pairingLinkValue.setText("");
                qrView.setImageDrawable(null);
                addressValue.setText("No LAN or Tailscale IPv4 address available");
                errorValue.setText("Connect both phones to the same subnet or Tailscale, then refresh.");
                return;
            }
            pairingUri = pairingStore.pairingUri(configuration, addresses);
            pairingLinkValue.setText(pairingUri.toString());
            qrView.setImageBitmap(AnchorQrCode.bitmap(pairingUri.toString(), dp(310)));
            StringBuilder addressText = new StringBuilder("Boat address");
            if (addresses.size() > 1) addressText.append("es");
            addressText.append(": ");
            for (int index = 0; index < addresses.size(); index++) {
                if (index > 0) addressText.append(", ");
                addressText.append(addresses.get(index).address);
            }
            addressValue.setText(addressText.toString());
            errorValue.setText("");
        } catch (GeneralSecurityException | WriterException error) {
            qrView.setImageDrawable(null);
            errorValue.setText("Pairing code failed: " + error.getLocalizedMessage());
        }
    }

    private void sharePairingLink() {
        if (pairingUri == null) return;
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, pairingUri.toString())
                .putExtra(Intent.EXTRA_SUBJECT, "Anchor monitor pairing");
        startActivity(Intent.createChooser(share, "Share pairing link"));
    }

    private void confirmRotate() {
        new AlertDialog.Builder(this)
                .setTitle("Invalidate current pairing?")
                .setMessage("The shore phone will disconnect and must scan the new code.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Make new code", (dialog, which) -> {
                    try {
                        pairingStore.rotateHost();
                        startService(new Intent(this, BridgeService.class)
                                .setAction(BridgeService.ACTION_ROTATE_ANCHOR_PAIRING));
                        refreshPairingCode();
                    } catch (GeneralSecurityException error) {
                        errorValue.setText("Could not rotate pairing: "
                                + error.getLocalizedMessage());
                    }
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
        button.setText(title + "  ›");
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
