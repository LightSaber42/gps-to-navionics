package com.jaco.navionicsgpsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores an explicitly enabled shore monitor after the Android device restarts. */
public final class ShoreMonitorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && ShoreMonitorService.isEnabled(context)) {
            ShoreMonitorService.start(context);
        }
    }
}
