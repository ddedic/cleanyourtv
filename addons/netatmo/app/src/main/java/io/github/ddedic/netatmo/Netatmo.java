package io.github.ddedic.netatmo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The Netatmo Energy API (api.netatmo.com): the token, the home's rooms, their temperatures, and the two commands. */
final class Netatmo {
    static final String API = "https://api.netatmo.com";
    private static final int TIMEOUT_MS = 8000;

    /** A refreshed token pair; Netatmo replaces the refresh token on every refresh, so both are kept. */
    static final class Tokens {
        final String access, refresh;
        final long expiresAt;

        Tokens(String access, String refresh, long expiresAt) {
            this.access = access;
            this.refresh = refresh;
            this.expiresAt = expiresAt;
        }
    }

    Tokens refresh(String clientId, String clientSecret, String refreshToken) throws IOException, JSONException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        return parseTokens(new JSONObject(request("POST", API + "/oauth2/token", null, form)), System.currentTimeMillis());
    }

    /** The first home with heating rooms: its id and room names, from homesdata. */
    Home home(String token) throws IOException, JSONException {
        return parseHome(new JSONObject(request("GET", API + "/api/homesdata", token, null)));
    }

    Status status(String token, Home home) throws IOException, JSONException {
        String body = request("GET", API + "/api/homestatus?home_id=" + enc(home.id), token, null);
        return parseStatus(new JSONObject(body), home, System.currentTimeMillis());
    }

    /** A room's target until the end of Netatmo's default manual duration; temp NaN returns it to the schedule. */
    void setRoom(String token, String homeId, String roomId, double temp) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("home_id", homeId);
        form.put("room_id", roomId);
        if (Double.isNaN(temp)) {
            form.put("mode", "home");
        } else {
            form.put("mode", "manual");
            form.put("temp", String.valueOf(temp));
        }
        request("POST", API + "/api/setroomthermpoint", token, form);
    }

    /** The whole home: "schedule", "away" or "hg" (frost guard). */
    void setMode(String token, String homeId, String mode) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("home_id", homeId);
        form.put("mode", mode);
        request("POST", API + "/api/setthermmode", token, form);
    }

    // ---- parsing ------------------------------------------------------------------------------------

    static final class Home {
        final String id;
        final Map<String, String> roomNames;

        Home(String id, Map<String, String> roomNames) {
            this.id = id;
            this.roomNames = roomNames;
        }
    }

    static Tokens parseTokens(JSONObject json, long now) throws JSONException {
        String access = json.getString("access_token");
        String refresh = json.getString("refresh_token");
        long seconds = json.optLong("expires_in", 10800);
        return new Tokens(access, refresh, now + seconds * 1000L);
    }

    static Home parseHome(JSONObject json) throws JSONException {
        JSONArray homes = json.getJSONObject("body").optJSONArray("homes");
        for (int i = 0; homes != null && i < homes.length(); i++) {
            JSONObject home = homes.getJSONObject(i);
            JSONArray rooms = home.optJSONArray("rooms");
            if (rooms == null || rooms.length() == 0 || !hasHeating(home)) continue;
            Map<String, String> names = new LinkedHashMap<>();
            for (int r = 0; r < rooms.length(); r++) {
                JSONObject room = rooms.getJSONObject(r);
                names.put(room.optString("id"), room.optString("name", "Room"));
            }
            return new Home(home.getString("id"), names);
        }
        throw new JSONException("no home with a thermostat or valves on this account");
    }

    private static boolean hasHeating(JSONObject home) {
        JSONArray modules = home.optJSONArray("modules");
        for (int i = 0; modules != null && i < modules.length(); i++) {
            String type = modules.optJSONObject(i) == null ? "" : modules.optJSONObject(i).optString("type");
            if (type.equals("NATherm1") || type.equals("NRV") || type.equals("OTM") || type.equals("NAPlug")
                    || type.equals("BNS")) return true;
        }
        return false;
    }

    static Status parseStatus(JSONObject json, Home home, long now) throws JSONException {
        JSONObject h = json.getJSONObject("body").getJSONObject("home");
        boolean boiler = false;
        JSONArray modules = h.optJSONArray("modules");
        for (int i = 0; modules != null && i < modules.length(); i++) {
            JSONObject m = modules.getJSONObject(i);
            if (m.optBoolean("boiler_status", false)) boiler = true;
        }
        List<Status.Room> rooms = new ArrayList<>();
        JSONArray list = h.optJSONArray("rooms");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject r = list.getJSONObject(i);
            if (!r.has("therm_measured_temperature")) continue;
            String id = r.optString("id");
            String name = home.roomNames.containsKey(id) ? home.roomNames.get(id) : "Room";
            boolean heating = r.optInt("heating_power_request", 0) > 0;
            rooms.add(new Status.Room(id, name, r.optDouble("therm_measured_temperature"),
                    r.optDouble("therm_setpoint_temperature"), r.optString("therm_setpoint_mode", "schedule"), heating));
        }
        return new Status(home.id, rooms, boiler, now);
    }

    // ---- HTTP ---------------------------------------------------------------------------------------

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String request(String method, String url, String token, Map<String, String> form) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (form != null) {
                StringBuilder body = new StringBuilder();
                for (Map.Entry<String, String> e : form.entrySet()) {
                    if (body.length() > 0) body.append('&');
                    body.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                }
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            // Only the status code goes into the error: Netatmo's error body can echo the request.
            if (code < 200 || code >= 300) throw new IOException(method + " " + url.replaceAll("\\?.*", "") + " " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) body.write(buffer, 0, n);
                return body.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }
}
