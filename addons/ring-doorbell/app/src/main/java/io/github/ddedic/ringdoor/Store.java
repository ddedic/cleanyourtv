package io.github.ddedic.ringdoor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/** Credentials, Ring tokens, devices, recent events and the owner's choices, in the app's private storage. */
final class Store {
    static final String CREDENTIALS_FILE = "credentials.json";
    private static final int KEEP_EVENTS = 40;

    /** Motion alerts: none, people only (Ring's Smart Alerts), or every motion. */
    static final String MOTION_OFF = "off", MOTION_PEOPLE = "people", MOTION_ALL = "all";

    private final Context context;
    private final SharedPreferences prefs;

    Store(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("ring", Context.MODE_PRIVATE);
    }

    // ---- setup --------------------------------------------------------------------------------------

    boolean configured() { return !bridgeUrl().isEmpty() && !tvToken().isEmpty() && !clientSecret().isEmpty(); }
    boolean linked() { return !refreshToken().isEmpty(); }
    String bridgeUrl() { return prefs.getString("bridge_url", ""); }
    String tvToken() { return prefs.getString("tv_token", ""); }
    String clientId() { return prefs.getString("client_id", ""); }
    String clientSecret() { return prefs.getString("client_secret", ""); }
    String hmacKey() { return prefs.getString("hmac_key", ""); }

    synchronized String hardwareId() {
        String id = prefs.getString("hardware_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("hardware_id", id).apply();
        }
        return id;
    }

    RingApi api() { return new RingApi(clientId(), clientSecret(), hardwareId()); }

    /**
     * Reads credentials.json (bridge_url, tv_token, client_id, client_secret, hmac_signing_key) from the external
     * files folder and deletes it whatever it held. Ring tokens and settings stay. True when stored.
     */
    boolean importCredentials() {
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, CREDENTIALS_FILE);
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 16384)];
            int read = 0;
            for (int n; read < data.length && (n = in.read(data, read, data.length - read)) > 0; ) read += n;
            JSONObject json = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            String[] keys = {"bridge_url", "tv_token", "client_id", "client_secret", "hmac_signing_key"};
            for (String key : keys) if (json.optString(key).trim().isEmpty()) return false;
            prefs.edit().putString("bridge_url", json.getString("bridge_url").trim())
                    .putString("tv_token", json.getString("tv_token").trim())
                    .putString("client_id", json.getString("client_id").trim())
                    .putString("client_secret", json.getString("client_secret").trim())
                    .putString("hmac_key", json.getString("hmac_signing_key").trim()).commit();
            return true;
        } catch (IOException | JSONException broken) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ---- Ring tokens --------------------------------------------------------------------------------

    String refreshToken() { return prefs.getString("refresh_token", ""); }
    String accessToken() { return prefs.getString("access_token", ""); }
    long expiresAt() { return prefs.getLong("expires_at", 0); }
    long refreshedAt() { return prefs.getLong("refreshed_at", 0); }
    String accountId() { return prefs.getString("account_id", ""); }

    /** commit: Ring rotates the refresh token, so the old one is dead once this one exists. */
    void setTokens(RingApi.Tokens tokens) {
        prefs.edit().putString("access_token", tokens.access).putString("refresh_token", tokens.refresh)
                .putLong("expires_at", tokens.expiresAt).putLong("refreshed_at", System.currentTimeMillis()).commit();
    }

    /** The account behind the tokens; a new one also restarts linkedAt, since Ring's media starts there. */
    void setAccountId(String id) {
        prefs.edit().putString("account_id", id).remove("linked_at").commit();
    }

    /** The link completed: Ring's media for this app starts here. */
    void markLinked() { prefs.edit().putLong("linked_at", System.currentTimeMillis()).commit(); }

    /** Installs linked before linked_at existed fall back to the token time, the same moment near enough. */
    long linkedAt() { return prefs.getLong("linked_at", refreshedAt() + 60_000); }

    void unlink() {
        prefs.edit().remove("refresh_token").remove("access_token").remove("expires_at").remove("account_id")
                .remove("devices").apply();
    }

    // ---- devices ------------------------------------------------------------------------------------

    List<RingApi.Device> devices() {
        List<RingApi.Device> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs.getString("devices", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                out.add(new RingApi.Device(d.getString("id"), d.getString("name"), d.optBoolean("chime"),
                        d.optBoolean("online", true), d.optBoolean("snoozed"), d.optInt("volume", -1)));
            }
        } catch (JSONException broken) {
            out.clear();
        }
        return out;
    }

    void setDevices(List<RingApi.Device> devices) {
        JSONArray list = new JSONArray();
        try {
            for (RingApi.Device d : devices) {
                list.put(new JSONObject().put("id", d.id).put("name", d.name).put("chime", d.chime)
                        .put("online", d.online).put("snoozed", d.snoozed).put("volume", d.volume));
            }
        } catch (JSONException ignored) {
            return;
        }
        prefs.edit().putString("devices", list.toString()).putLong("devices_at", System.currentTimeMillis()).apply();
    }

    long devicesAt() { return prefs.getLong("devices_at", 0); }

    /** The doorbell or camera to look at: the chosen one, else the first that is not a chime. */
    RingApi.Device camera() {
        String chosen = prefs.getString("camera", "");
        RingApi.Device first = null;
        for (RingApi.Device d : devices()) {
            if (d.chime) continue;
            if (d.id.equals(chosen)) return d;
            if (first == null) first = d;
        }
        return first;
    }

    RingApi.Device chime() {
        for (RingApi.Device d : devices()) if (d.chime) return d;
        return null;
    }

    String deviceName(String id) {
        for (RingApi.Device d : devices()) if (d.id.equals(id)) return d.name;
        return context.getString(R.string.doorbell);
    }

    // ---- events -------------------------------------------------------------------------------------

    static final class Event {
        final String id, kind, deviceId, device, subType;
        final long at;
        final boolean missed;

        Event(String id, String kind, String deviceId, String device, String subType, long at, boolean missed) {
            this.id = id;
            this.kind = kind;
            this.deviceId = deviceId;
            this.device = device;
            this.subType = subType;
            this.at = at;
            this.missed = missed;
        }

        boolean ding() { return "button_press".equals(kind); }
    }

    List<Event> events() {
        List<Event> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(prefs.getString("events", "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject e = list.getJSONObject(i);
                out.add(new Event(e.getString("id"), e.getString("kind"), e.optString("device_id"), e.optString("device"),
                        e.optString("sub"), e.optLong("at"), e.optBoolean("missed")));
            }
        } catch (JSONException broken) {
            out.clear();
        }
        return out;
    }

    /** Newest first; a repeat (Ring retries, bridge replays) returns false. A ring adds to the unseen count. */
    synchronized boolean addEvent(Event event) {
        List<Event> events = events();
        for (Event e : events) if (e.id.equals(event.id)) return false;
        events.add(event);
        events.sort((a, b) -> Long.compare(b.at, a.at));
        while (events.size() > KEEP_EVENTS) {
            Event gone = events.remove(events.size() - 1);
            //noinspection ResultOfMethodCallIgnored
            snapshotFile(gone.id).delete();
        }
        JSONArray list = new JSONArray();
        try {
            for (Event e : events) {
                list.put(new JSONObject().put("id", e.id).put("kind", e.kind).put("device_id", e.deviceId)
                        .put("device", e.device).put("sub", e.subType).put("at", e.at).put("missed", e.missed));
            }
        } catch (JSONException ignored) {
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit().putString("events", list.toString())
                .putLong("last_event", Math.max(lastEvent(), event.at));
        if (event.ding()) editor.putInt("unseen", unseen() + 1);
        editor.apply();
        return true;
    }

    Event newestRing() {
        for (Event e : events()) if (e.ding()) return e;
        return null;
    }

    long lastEvent() { return prefs.getLong("last_event", 0); }
    int unseen() { return prefs.getInt("unseen", 0); }
    void seen() { prefs.edit().putInt("unseen", 0).apply(); }

    File snapshotFile(String eventId) {
        File dir = new File(context.getFilesDir(), "snaps");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, eventId.replaceAll("[^A-Za-z0-9_-]", "_") + ".jpg");
    }

    File latestFile() { return snapshotFile("latest"); }

    void write(File file, byte[] data) {
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(data);
        } catch (IOException failed) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        part.renameTo(file);
    }

    // ---- connection state ---------------------------------------------------------------------------

    boolean connected() { return prefs.getBoolean("connected", false); }
    String lastError() { return prefs.getString("last_error", ""); }

    void setConnected(boolean on, String error) {
        prefs.edit().putBoolean("connected", on).putString("last_error", error).apply();
    }

    // ---- the owner's choices ------------------------------------------------------------------------

    boolean enabled() { return prefs.getBoolean("enabled", true); }
    boolean popup() { return prefs.getBoolean("popup", true); }
    boolean pause() { return prefs.getBoolean("pause", true); }
    String motion() { return prefs.getString("motion", MOTION_OFF); }
    int popupSeconds() { return prefs.getInt("popup_seconds", 25); }
    boolean quietHours() { return prefs.getBoolean("quiet", false); }
    int quietFrom() { return prefs.getInt("quiet_from", 22); }
    int quietTo() { return prefs.getInt("quiet_to", 7); }

    void set(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }
    void set(String key, int value) { prefs.edit().putInt(key, value).apply(); }
    void set(String key, String value) { prefs.edit().putString(key, value).apply(); }

    /** True while quiet hours are on and the clock is inside them (they may run past midnight). */
    boolean quietNow() {
        if (!quietHours()) return false;
        return inQuiet(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), quietFrom(), quietTo());
    }

    static boolean inQuiet(int hour, int from, int to) {
        if (from == to) return false;
        return from < to ? hour >= from && hour < to : hour >= from || hour < to;
    }

    /** Whether this event should interrupt what is on the TV, by the owner's choices. */
    boolean alerts(Event e) {
        if (!enabled() || !popup() || quietNow()) return false;
        if (e.ding()) return true;
        String motion = motion();
        return MOTION_ALL.equals(motion) || MOTION_PEOPLE.equals(motion) && "human".equals(e.subType);
    }
}
