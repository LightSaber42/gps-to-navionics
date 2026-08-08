package com.jaco.navionicsgpsbridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Versioned JSON payloads carried inside authenticated anchor-sync frames. */
final class AnchorSyncProtocol {
    static final int VERSION = 1;
    static final int PORT = 11_124;
    static final int MAX_LINE_BYTES = 262_144;
    static final int HISTORY_PAGE_SIZE = 250;
    static final long HEARTBEAT_MILLIS = 10_000L;
    static final long CONNECTION_LOST_MILLIS = 30_000L;

    static final String TYPE_STATE = "state";
    static final String TYPE_HISTORY_REQUEST = "history_request";
    static final String TYPE_HISTORY = "history";
    static final String TYPE_CLIENT_STATUS = "client_status";

    private AnchorSyncProtocol() {
    }

    static JSONObject state(State state) throws JSONException {
        JSONObject result = envelope(TYPE_STATE);
        result.put("session_id", nullable(state.sessionId));
        result.put("armed", state.armed);
        result.put("mode", state.mode);
        result.put("radius_m", state.radiusMeters);
        result.put("distance_m", finiteOrNull(state.distanceMeters));
        result.put("accuracy_m", finiteOrNull(state.accuracyMeters));
        result.put("latest_sequence", state.latestSequence);
        result.put("battery_percent", state.batteryPercent);
        result.put("charging", state.charging);
        result.put("server_time_ms", state.serverTimeMillis);
        result.put("events", events(state.events));
        return result;
    }

    static State parseState(JSONObject value) throws JSONException {
        requireEnvelope(value, TYPE_STATE);
        List<Event> events = new ArrayList<>();
        JSONArray array = value.optJSONArray("events");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                events.add(Event.fromJson(array.getJSONObject(index)));
            }
        }
        return new State(
                optionalString(value, "session_id"),
                value.getBoolean("armed"),
                value.getString("mode"),
                value.getDouble("radius_m"),
                optionalDouble(value, "distance_m"),
                optionalDouble(value, "accuracy_m"),
                value.optLong("latest_sequence", 0L),
                value.optInt("battery_percent", -1),
                value.optBoolean("charging", false),
                value.optLong("server_time_ms", System.currentTimeMillis()),
                events);
    }

    static JSONObject historyRequest(String sessionId, long afterSequence) throws JSONException {
        JSONObject result = envelope(TYPE_HISTORY_REQUEST);
        result.put("session_id", sessionId);
        result.put("after_sequence", afterSequence);
        return result;
    }

    static JSONObject history(String sessionId, List<Point> points, boolean complete)
            throws JSONException {
        JSONObject result = envelope(TYPE_HISTORY);
        result.put("session_id", sessionId);
        JSONArray array = new JSONArray();
        for (Point point : points) array.put(point.toJson());
        result.put("points", array);
        result.put("complete", complete);
        return result;
    }

    static History parseHistory(JSONObject value) throws JSONException {
        requireEnvelope(value, TYPE_HISTORY);
        JSONArray array = value.getJSONArray("points");
        List<Point> points = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            points.add(Point.fromJson(array.getJSONObject(index)));
        }
        return new History(value.getString("session_id"), points,
                value.optBoolean("complete", true));
    }

    static JSONObject clientStatus(boolean soundReady) throws JSONException {
        JSONObject result = envelope(TYPE_CLIENT_STATUS);
        result.put("sound_ready", soundReady);
        result.put("client_time_ms", System.currentTimeMillis());
        return result;
    }

    static String type(JSONObject value) throws JSONException {
        if (value.optInt("v", -1) != VERSION) {
            throw new JSONException("Unsupported anchor-sync protocol version");
        }
        return value.getString("type");
    }

    private static JSONObject envelope(String type) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("v", VERSION);
        result.put("type", type);
        return result;
    }

    private static void requireEnvelope(JSONObject value, String expectedType) throws JSONException {
        if (!expectedType.equals(type(value))) {
            throw new JSONException("Expected " + expectedType + " anchor-sync message");
        }
    }

    private static JSONArray events(List<Event> events) throws JSONException {
        JSONArray result = new JSONArray();
        for (Event event : events) result.put(event.toJson());
        return result;
    }

    private static Object nullable(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static Object finiteOrNull(double value) {
        return Double.isFinite(value) ? value : JSONObject.NULL;
    }

    private static double optionalDouble(JSONObject value, String key) {
        return value.isNull(key) ? Double.NaN : value.optDouble(key, Double.NaN);
    }

    private static String optionalString(JSONObject value, String key) {
        return value.isNull(key) ? null : value.optString(key, null);
    }

    static final class Event {
        final String id;
        final long sequence;
        final String sessionId;
        final String kind;
        final long occurredAtMillis;
        final String reason;

        Event(String id, long sequence, String sessionId, String kind,
                long occurredAtMillis, String reason) {
            this.id = id;
            this.sequence = sequence;
            this.sessionId = sessionId;
            this.kind = kind;
            this.occurredAtMillis = occurredAtMillis;
            this.reason = reason;
        }

        static Event create(long sequence, String sessionId, String kind, String reason) {
            return new Event(UUID.randomUUID().toString(), sequence, sessionId, kind,
                    System.currentTimeMillis(), reason);
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("id", id);
            result.put("sequence", sequence);
            result.put("session_id", nullable(sessionId));
            result.put("kind", kind);
            result.put("occurred_at_ms", occurredAtMillis);
            result.put("reason", reason);
            return result;
        }

        static Event fromJson(JSONObject value) throws JSONException {
            return new Event(
                    value.getString("id"),
                    value.getLong("sequence"),
                    optionalString(value, "session_id"),
                    value.getString("kind"),
                    value.getLong("occurred_at_ms"),
                    value.getString("reason"));
        }
    }

    static final class Point {
        final long sequence;
        final long recordedAtMillis;
        final double eastMeters;
        final double northMeters;
        final double accuracyMeters;

        Point(long sequence, long recordedAtMillis, double eastMeters,
                double northMeters, double accuracyMeters) {
            this.sequence = sequence;
            this.recordedAtMillis = recordedAtMillis;
            this.eastMeters = eastMeters;
            this.northMeters = northMeters;
            this.accuracyMeters = accuracyMeters;
        }

        JSONObject toJson() throws JSONException {
            JSONObject result = new JSONObject();
            result.put("sequence", sequence);
            result.put("recorded_at_ms", recordedAtMillis);
            result.put("east_m", eastMeters);
            result.put("north_m", northMeters);
            result.put("accuracy_m", accuracyMeters);
            return result;
        }

        static Point fromJson(JSONObject value) throws JSONException {
            return new Point(
                    value.getLong("sequence"),
                    value.getLong("recorded_at_ms"),
                    value.getDouble("east_m"),
                    value.getDouble("north_m"),
                    value.getDouble("accuracy_m"));
        }
    }

    static final class State {
        final String sessionId;
        final boolean armed;
        final String mode;
        final double radiusMeters;
        final double distanceMeters;
        final double accuracyMeters;
        final long latestSequence;
        final int batteryPercent;
        final boolean charging;
        final long serverTimeMillis;
        final List<Event> events;

        State(String sessionId, boolean armed, String mode, double radiusMeters,
                double distanceMeters, double accuracyMeters, long latestSequence,
                int batteryPercent, boolean charging, long serverTimeMillis,
                List<Event> events) {
            this.sessionId = sessionId;
            this.armed = armed;
            this.mode = mode;
            this.radiusMeters = radiusMeters;
            this.distanceMeters = distanceMeters;
            this.accuracyMeters = accuracyMeters;
            this.latestSequence = latestSequence;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.serverTimeMillis = serverTimeMillis;
            this.events = Collections.unmodifiableList(new ArrayList<>(events));
        }
    }

    static final class History {
        final String sessionId;
        final List<Point> points;
        final boolean complete;

        History(String sessionId, List<Point> points, boolean complete) {
            this.sessionId = sessionId;
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.complete = complete;
        }
    }
}
