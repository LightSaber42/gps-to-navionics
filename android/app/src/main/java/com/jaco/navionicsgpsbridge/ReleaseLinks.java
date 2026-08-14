package com.jaco.navionicsgpsbridge;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;

/** Opens release-configured HTTPS policy/support pages and rejects placeholder hosts. */
final class ReleaseLinks {
    private ReleaseLinks() {}

    static Uri configuredUri(Activity activity, int resourceId) {
        String value = activity.getString(resourceId).trim();
        Uri uri = Uri.parse(value);
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || host.toLowerCase(java.util.Locale.US).endsWith(".invalid")) {
            return null;
        }
        return uri;
    }

    static void open(Activity activity, int resourceId) {
        Uri uri = configuredUri(activity, resourceId);
        if (uri == null) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            // A valid URL remains visible in configuration even if this device has no browser.
        }
    }
}
