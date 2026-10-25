package io.github.ddedic.ringdoor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ring's official Partner API (api.amazonvision.com, oauth.ring.com) for a private app: the token exchange, the
 * account link, devices, snapshots and chime playback. Called from the TV, on the home connection.
 */
final class RingApi {
    static final String API = "https://api.amazonvision.com";
    static final String TOKEN_URL = "https://oauth.ring.com/oauth/token";
    private static final String AGENT = "TechnabitTVOS/1.0";

    static final class Tokens {
        final String access, refresh;
        final long expiresAt;

        Tokens(String access, String refresh, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.expiresAt = expiresAt;
        }
    }

    /** A camera, doorbell or chime shared with the app. */
    static final class Device {
        final String id, name;
        final boolean chime, online, snoozed;
        final int volume;

        Device(String id, String name, boolean chime, boolean online, boolean snoozed, int volume) {
            this.id = id;
            this.name = name;
            this.chime = chime;
            this.online = online;
            this.snoozed = snoozed;
            this.volume = volume;
        }
    }

    /** A doorbell press or a motion, from a webhook. */
    static final class Event {
        final String id, kind, deviceId, subType;
        final long at;

        Event(String id, String kind, String deviceId, String subType, long at) {
            this.id = id;
            this.kind = kind;
            this.deviceId = deviceId;
            this.subType = subType;
            this.at = at;
        }

        boolean ding() { return "button_press".equals(kind); }
        boolean person() { return "human".equals(subType); }
    }

    private final String clientId, clientSecret, hardwareId;

    RingApi(String clientId, String clientSecret, String hardwareId) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.hardwareId = hardwareId;
    }

    // ---- tokens and linking --------------------------------------------------------------------------

    Tokens exchange(String code) throws IOException, JSONException {
        return token("grant_type=authorization_code&code=" + enc(code));
    }

    Tokens refresh(String refreshToken) throws IOException, JSONException {
        return token("grant_type=refresh_token&refresh_token=" + enc(refreshToken));
    }

    private Tokens token(String grant) throws IOException, JSONException {
        Map<String, String> headers = headers(null);
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        String body = grant + "&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
        Http.Response r = Http.call("POST", TOKEN_URL, headers, Http.utf8(body), true).ok("ring token");
        return parseTokens(new JSONObject(r.text()), System.currentTimeMillis());
    }

    String accountId(String token) throws IOException, JSONException {
        JSONObject me = new JSONObject(Http.call("GET", API + "/v1/users/me", headers(token), null, true).ok("ring users/me").text());
        JSONObject data = me.optJSONObject("data");
        String id = data == null ? "" : data.optString("id");
        if (id.isEmpty() && data != null) id = data.optJSONObject("attributes") == null ? "" : data.optJSONObject("attributes").optString("account_id");
        if (id.isEmpty()) throw new IOException("ring users/me gave no account id");
        return id;
    }

    /** Ring's account-link nonce: URL-safe Base64 (no padding) of HMAC-SHA256(key, "<time>:<account id>"). */
    static String nonce(String hmacKey, String time, String accountId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(hmacKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((time + ":" + accountId).getBytes(StandardCharsets.UTF_8));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    /** The two calls that finish a link: POST the nonce (awaiting), then PATCH completed. */
    void completeLink(String token, String nonce, String accountIdentifier) throws IOException, JSONException {
        JSONObject post = new JSONObject().put("nonce", nonce).put("account_identifier", accountIdentifier);
        Http.call("POST", API + "/v1/accounts/me/app-integrations", headers(token), Http.utf8(post.toString()), true)
                .ok("ring link");
        JSONObject patch = new JSONObject().put("status", "completed");
        Http.call("PATCH", API + "/v1/accounts/me/app-integrations", headers(token), Http.utf8(patch.toString()), true)
                .ok("ring link complete");
    }

    // ---- devices -----------------------------------------------------------------------------------

    List<Device> devices(String token) throws IOException, JSONException {
        return parseDevices(new JSONObject(Http.call("GET", API + "/v1/devices?include=status,capabilities,configurations",
                headers(token), null, true).ok("ring devices").text()));
    }

    /**
     * The picture nearest the event (at_timestamp), or the newest in the last hour when at is 0. Ring stores media a
     * few seconds after the event, so 416/425 are retried a few times for a fresh one. Returns JPEG bytes.
     */
    byte[] snapshot(String token, String deviceId, long at, long linkedAt) throws IOException, JSONException, InterruptedException {
        JSONObject body = new JSONObject().put("image_options", new JSONObject().put("format", "jpeg"));
        if (at > 0) body.put("type", "at_timestamp").put("timestamp", at);
        // Ring only releases media recorded after the account was linked.
        else body.put("type", "latest_in_range").put("start_timestamp",
                Math.max(System.currentTimeMillis() - 3_600_000L, linkedAt + 1000));
        // Only a moment that just happened may still be recording; an older one has its media or never will.
        boolean fresh = at <= 0 || System.currentTimeMillis() - at < 120_000;
        long[] waits = fresh ? new long[]{0, 5000, 5000, 10000, 20000, 30000} : new long[]{0};
        IOException last = null;
        for (long wait : waits) {
            if (wait > 0) Thread.sleep(wait);
            Http.Response r = Http.call("POST", API + "/v1/devices/" + deviceId + "/media/image/download",
                    headers(token), Http.utf8(body.toString()), false);
            if (r.code == 303 && !r.location.isEmpty()) {
                // The pre-signed download URL carries its own credentials: no Ring token goes with it.
                Http.Response image = Http.call("GET", r.location, null, null, true);
                if (image.code == 200 && image.type.startsWith("image/")) return image.body;
                last = new IOException("ring image " + image.code);
                if (image.code != 416 && image.code != 425) throw last;
                continue;   // stored a moment later: ask again
            }
            last = new IOException("ring snapshot " + r.code + error(r));
            if (r.code != 416 && r.code != 425 && r.code != 503 && r.code != 429) throw last;
        }
        throw last;
    }

    /** Ring's JSON:API error, " CODE: detail", or "" — never the request. */
    static String error(Http.Response r) {
        try {
            JSONObject e = new JSONObject(r.text()).getJSONArray("errors").getJSONObject(0);
            return " " + e.optString("code", e.optString("title")) + ": " + e.optString("detail");
        } catch (Exception notJson) {
            return "";
        }
    }

    /** Plays the app's audio in one of the chime's slots. */
    void playChime(String token, String chimeId, String audioRef) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("audio_ref", audioRef);
        Http.call("POST", API + "/v1/devices/" + chimeId + "/media/audio/playback", headers(token),
                Http.utf8(body.toString()), true).ok("ring chime");
    }

    /** RTSPS live stream (30 s on battery doorbells, 60 s on wired ones); the token goes in an auth header. */
    static String liveUrl(String deviceId) {
        return "rtsps://video.rtsp.amazonvision.com:322/v1/devices/" + deviceId + "/stream";
    }

    private Map<String, String> headers(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", AGENT);
        headers.put("Hardware_Id", hardwareId);
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ---- parsing ------------------------------------------------------------------------------------

    static Tokens parseTokens(JSONObject json, long now) throws JSONException {
        return new Tokens(json.getString("access_token"), json.getString("refresh_token"),
                now + json.optLong("expires_in", 14400) * 1000L);
    }

    /** JSON:API devices with their included status, capabilities and configurations. */
    static List<Device> parseDevices(JSONObject json) {
        Map<String, JSONObject> included = new LinkedHashMap<>();
        JSONArray inc = json.optJSONArray("included");
        for (int i = 0; inc != null && i < inc.length(); i++) {
            JSONObject o = inc.optJSONObject(i);
            if (o != null) included.put(o.optString("type") + "/" + o.optString("id"), o.optJSONObject("attributes"));
        }
        List<Device> out = new ArrayList<>();
        JSONArray data = json.optJSONArray("data");
        for (int i = 0; data != null && i < data.length(); i++) {
            JSONObject d = data.optJSONObject(i);
            if (d == null) continue;
            JSONObject attributes = d.optJSONObject("attributes"), rel = d.optJSONObject("relationships");
            JSONObject status = related(rel, "status", included), caps = related(rel, "capabilities", included),
                    config = related(rel, "configurations", included);
            JSONObject audio = caps == null ? null : caps.optJSONObject("audio");
            boolean chime = audio != null && audio.optInt("customizable_slots", 0) > 0;
            JSONObject configAudio = config == null ? null : config.optJSONObject("audio");
            out.add(new Device(d.optString("id"), attributes == null ? "Ring" : attributes.optString("name", "Ring"), chime,
                    status == null || status.optBoolean("online", true),
                    status != null && (status.optBoolean("snoozed", false) || status.optJSONObject("snooze") != null
                            && status.optJSONObject("snooze").optBoolean("active", false)),
                    configAudio == null ? -1 : configAudio.optInt("volume", -1)));
        }
        return out;
    }

    private static JSONObject related(JSONObject relationships, String name, Map<String, JSONObject> included) {
        JSONObject r = relationships == null ? null : relationships.optJSONObject(name);
        JSONObject data = r == null ? null : r.optJSONObject("data");
        return data == null ? null : included.get(data.optString("type") + "/" + data.optString("id"));
    }

    /** A webhook body: button_press or motion_detected; null for anything else. */
    static Event parseWebhook(String body) {
        try {
            JSONObject data = new JSONObject(body).optJSONObject("data");
            if (data == null) return null;
            String type = data.optString("type");
            if (!"button_press".equals(type) && !"motion_detected".equals(type)) return null;
            JSONObject a = data.optJSONObject("attributes");
            if (a == null || a.optString("source").isEmpty()) return null;
            return new Event(data.optString("id"), type, a.optString("source"), a.optString("sub_type"),
                    a.optLong("timestamp", System.currentTimeMillis()));
        } catch (JSONException broken) {
            return null;
        }
    }
}
