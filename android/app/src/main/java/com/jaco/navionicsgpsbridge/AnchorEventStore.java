package com.jaco.navionicsgpsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** Durable event replay plus independent acknowledgement for this device. */
final class AnchorEventStore {
    private static final String PREFERENCES = "anchor_events";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_NEXT_SEQUENCE = "next_sequence";
    private static final String KEY_ACKNOWLEDGED = "acknowledged_sequence";

    private final SharedPreferences preferences;
    private final List<AnchorSyncProtocol.Event> events = new ArrayList<>();
    private long nextSequence;
    private long acknowledgedSequence;

    AnchorEventStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        nextSequence = Math.max(1L, preferences.getLong(KEY_NEXT_SEQUENCE, 1L));
        acknowledgedSequence = preferences.getLong(KEY_ACKNOWLEDGED, 0L);
        JSONArray array;
        try {
            array = new JSONArray(preferences.getString(KEY_EVENTS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                events.add(AnchorSyncProtocol.Event.fromJson(array.getJSONObject(index)));
            }
        } catch (JSONException ignored) {
            events.clear();
        }
        if (!events.isEmpty()) {
            nextSequence = Math.max(nextSequence, events.get(events.size() - 1).sequence + 1L);
        }
    }

    synchronized AnchorSyncProtocol.Event append(String sessionId, String kind, String reason) {
        AnchorSyncProtocol.Event event = AnchorSyncProtocol.Event.create(
                nextSequence++, sessionId, kind, reason);
        events.add(event);
        if (events.size() > 100) events.subList(0, events.size() - 100).clear();
        persist();
        return event;
    }

    synchronized List<AnchorSyncProtocol.Event> events() {
        return new ArrayList<>(events);
    }

    synchronized List<AnchorSyncProtocol.Event> eventsForSession(String sessionId) {
        List<AnchorSyncProtocol.Event> matching = new ArrayList<>();
        for (AnchorSyncProtocol.Event event : events) {
            if (sessionId != null && sessionId.equals(event.sessionId)) matching.add(event);
        }
        return matching;
    }

    synchronized AnchorSyncProtocol.Event latestUnacknowledged() {
        for (int index = events.size() - 1; index >= 0; index--) {
            AnchorSyncProtocol.Event event = events.get(index);
            if (event.sequence > acknowledgedSequence) return event;
        }
        return null;
    }

    synchronized void acknowledgeAll() {
        if (!events.isEmpty()) acknowledgedSequence = events.get(events.size() - 1).sequence;
        persist();
    }

    synchronized long acknowledgedSequence() {
        return acknowledgedSequence;
    }

    private void persist() {
        JSONArray array = new JSONArray();
        for (AnchorSyncProtocol.Event event : events) {
            try {
                array.put(event.toJson());
            } catch (JSONException ignored) {
                // All locally-created events contain valid JSON values.
            }
        }
        preferences.edit()
                .putString(KEY_EVENTS, array.toString())
                .putLong(KEY_NEXT_SEQUENCE, nextSequence)
                .putLong(KEY_ACKNOWLEDGED, acknowledgedSequence)
                .apply();
    }
}
