package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Durable, five-second anchor history used by both the local and shore plots. */
final class AnchorHistoryStore {
    private static final String PREFERENCES = "anchor_history";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_NEXT_SEQUENCE = "next_sequence";
    private static final long SAMPLE_INTERVAL_MILLIS = 5_000L;

    private final SharedPreferences preferences;
    private final File file;
    private final List<AnchorSyncProtocol.Point> points = new ArrayList<>();
    private String sessionId;
    private long nextSequence;
    private long lastRecordedAtMillis;

    AnchorHistoryStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        File directory = new File(context.getFilesDir(), "anchor-watch");
        if (!directory.exists()) directory.mkdirs();
        file = new File(directory, "current-history.ndjson");
        sessionId = preferences.getString(KEY_SESSION_ID, null);
        nextSequence = Math.max(1L, preferences.getLong(KEY_NEXT_SEQUENCE, 1L));
        load();
    }

    synchronized String startNewSession() {
        sessionId = UUID.randomUUID().toString().toLowerCase();
        nextSequence = 1L;
        lastRecordedAtMillis = 0L;
        points.clear();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, false))) {
            // Truncate the previous session only after the user explicitly arms a new one.
            writer.flush();
        } catch (IOException ignored) {
            // In-memory plotting and alarm monitoring remain available.
        }
        persistMetadata();
        return sessionId;
    }

    synchronized AnchorSyncProtocol.Point append(double eastMeters, double northMeters,
            double accuracyMeters, long recordedAtMillis) {
        if (sessionId == null) return null;
        if (lastRecordedAtMillis > 0L
                && recordedAtMillis - lastRecordedAtMillis < SAMPLE_INTERVAL_MILLIS) return null;
        AnchorSyncProtocol.Point point = new AnchorSyncProtocol.Point(
                nextSequence++,
                recordedAtMillis,
                eastMeters,
                northMeters,
                Math.max(0.0, accuracyMeters));
        points.add(point);
        lastRecordedAtMillis = recordedAtMillis;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(point.toJson().toString());
            writer.newLine();
        } catch (IOException | JSONException ignored) {
            // A future point may succeed; do not interrupt the safety alarm.
        }
        persistMetadata();
        return point;
    }

    synchronized String sessionId() {
        return sessionId;
    }

    synchronized long latestSequence() {
        return points.isEmpty() ? 0L : points.get(points.size() - 1).sequence;
    }

    synchronized List<AnchorSyncProtocol.Point> renderedPoints() {
        int first = Math.max(0, points.size() - 1_200);
        return new ArrayList<>(points.subList(first, points.size()));
    }

    synchronized List<AnchorSyncProtocol.Point> pageAfter(long sequence, int limit) {
        List<AnchorSyncProtocol.Point> result = new ArrayList<>();
        for (AnchorSyncProtocol.Point point : points) {
            if (point.sequence > sequence) {
                result.add(point);
                if (result.size() >= limit) break;
            }
        }
        return result;
    }

    synchronized boolean hasMoreAfter(long sequence) {
        return !points.isEmpty() && points.get(points.size() - 1).sequence > sequence;
    }

    private void load() {
        if (!file.isFile()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    points.add(AnchorSyncProtocol.Point.fromJson(new JSONObject(line)));
                } catch (JSONException ignored) {
                    // Preserve later valid samples if one line was interrupted during a write.
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (!points.isEmpty()) {
            AnchorSyncProtocol.Point last = points.get(points.size() - 1);
            nextSequence = Math.max(nextSequence, last.sequence + 1L);
            lastRecordedAtMillis = last.recordedAtMillis;
        }
    }

    private void persistMetadata() {
        preferences.edit()
                .putString(KEY_SESSION_ID, sessionId)
                .putLong(KEY_NEXT_SEQUENCE, nextSequence)
                .apply();
    }
}
