package io.github.ddedic.netatmo;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Credentials, tokens and the last status, in the app's private preferences. Nothing here leaves the app. */
final class Store {
    /** Pushed once over ADB into the app's external files folder, read and deleted at once (README). */
    static final String CREDENTIALS_FILE = "credentials.json";

    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("netatmo", Context.MODE_PRIVATE);
    }

    boolean signedIn() { return !prefs.getString("refresh_token", "").isEmpty(); }
    String clientId() { return prefs.getString("client_id", ""); }
    String clientSecret() { return prefs.getString("client_secret", ""); }
    String refreshToken() { return prefs.getString("refresh_token", ""); }
    String accessToken() { return prefs.getString("access_token", ""); }
    long expiresAt() { return prefs.getLong("expires_at", 0); }

    /** commit, not apply: the old refresh token stops working the moment Netatmo hands out the new one. */
    void setTokens(Netatmo.Tokens tokens) {
        prefs.edit().putString("access_token", tokens.access).putString("refresh_token", tokens.refresh)
                .putLong("expires_at", tokens.expiresAt).commit();
    }

    Netatmo.Home home() {
        String id = prefs.getString("home_id", "");
        if (id.isEmpty()) return null;
        Map<String, String> names = new LinkedHashMap<>();
        try {
            JSONObject json = new JSONObject(prefs.getString("room_names", "{}"));
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String key = keys.next();
                names.put(key, json.optString(key));
            }
        } catch (JSONException broken) {
            return null;
        }
        return new Netatmo.Home(id, names);
    }

    void setHome(Netatmo.Home home) {
        prefs.edit().putString("home_id", home.id).putString("room_names", new JSONObject(home.roomNames).toString()).apply();
    }

    Status status() { return Status.fromJson(prefs.getString("status", "{}")); }
    long lastFetch() { return prefs.getLong("last_fetch", 0); }
    String lastError() { return prefs.getString("last_error", ""); }

    void fetched(Status status, String error) {
        SharedPreferences.Editor editor = prefs.edit().putLong("last_fetch", System.currentTimeMillis())
                .putString("last_error", error);
        if (status != null) {
            try {
                editor.putString("status", status.toJson());
            } catch (JSONException ignored) {
                // keep the previous status
            }
        }
        editor.apply();
    }

    void failed(String error) {
        // Try again two minutes later rather than on every Home visit.
        prefs.edit().putLong("last_fetch", System.currentTimeMillis() - Fetcher.STALE_MS + Fetcher.RETRY_MS)
                .putString("last_error", error).apply();
    }

    String room() { return prefs.getString("room", ""); }
    void setRoom(String id) { prefs.edit().putString("room", id).apply(); }

    /**
     * Takes client_id, client_secret and refresh_token from credentials.json in the external files folder, then
     * deletes the file whatever it held. Returns true when new credentials were stored.
     */
    boolean importCredentials(Context context) {
        File dir = context.getExternalFilesDir(null);
        File file = dir == null ? null : new File(dir, CREDENTIALS_FILE);
        if (file == null || !file.isFile()) return false;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 8192)];
            int read = 0;
            for (int n; read < data.length && (n = in.read(data, read, data.length - read)) > 0; ) read += n;
            JSONObject json = new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            String id = json.optString("client_id").trim(), secret = json.optString("client_secret").trim(),
                    refresh = json.optString("refresh_token").trim();
            if (id.isEmpty() || secret.isEmpty() || refresh.isEmpty()) return false;
            prefs.edit().clear().putString("client_id", id).putString("client_secret", secret)
                    .putString("refresh_token", refresh).commit();
            return true;
        } catch (IOException | JSONException broken) {
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    /** Forgets the credentials, the tokens and the cached status. */
    void signOut() {
        prefs.edit().clear().apply();
    }
}
