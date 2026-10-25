package io.github.ddedic.plexfeed;

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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settings and the cached feed, all in this app's private storage. The tokens stay here: the provider serves only
 * titles, text and pictures (docs/specs/feed.md, Privacy).
 */
final class Store {
    static final String CONTINUE = "continue", MOVIES = "movies", EPISODES = "episodes";
    static final int[] PER_SECTION = {3, 6, 10};

    private final Context context;
    private final SharedPreferences prefs;

    Store(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("plex", Context.MODE_PRIVATE);
    }

    /** A random id for this install, which plex.tv lists under Authorized devices. */
    synchronized String clientId() {
        String id = prefs.getString("client_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("client_id", id).apply();
        }
        return id;
    }

    String accountToken() { return prefs.getString("account_token", ""); }
    void setAccountToken(String token) { prefs.edit().putString("account_token", token).apply(); }
    boolean signedIn() { return !accountToken().isEmpty(); }

    String serverName() { return prefs.getString("server_name", ""); }
    String serverId() { return prefs.getString("server_id", ""); }
    String serverToken() { return prefs.getString("server_token", ""); }
    String serverUri() { return prefs.getString("server_uri", ""); }
    boolean hasServer() { return !serverUri().isEmpty(); }

    void setServer(Plex.Server server, String uri) {
        prefs.edit().putString("server_name", server.name).putString("server_id", server.machineId)
                .putString("server_token", server.token).putString("server_uri", uri).putLong("last_fetch", 0).apply();
    }

    void setServerUri(String uri) { prefs.edit().putString("server_uri", uri).apply(); }

    boolean section(String key) { return prefs.getBoolean("show_" + key, true); }
    void setSection(String key, boolean on) { prefs.edit().putBoolean("show_" + key, on).putLong("last_fetch", 0).apply(); }

    int perSection() { return prefs.getInt("per_section", 6); }
    void setPerSection(int count) { prefs.edit().putInt("per_section", count).putLong("last_fetch", 0).apply(); }

    long lastFetch() { return prefs.getLong("last_fetch", 0); }
    String lastError() { return prefs.getString("last_error", ""); }
    int itemCount() { return prefs.getInt("item_count", 0); }

    void fetched(long at, int count, String error) {
        prefs.edit().putLong("last_fetch", at).putInt("item_count", count).putString("last_error", error).apply();
    }

    File artDir() {
        File dir = new File(context.getFilesDir(), "art");
        if (!dir.isDirectory() && !dir.mkdirs()) return context.getFilesDir();
        return dir;
    }

    private File itemsFile() { return new File(context.getFilesDir(), "items.json"); }

    /** Replaces the cached items in one step, so the provider never reads half a file. */
    void writeItems(List<Feed.Item> items, List<String> artFiles) throws IOException {
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < items.size(); i++) {
                Feed.Item item = items.get(i);
                array.put(new JSONObject().put("title", item.title).put("subtitle", item.subtitle)
                        .put("summary", item.summary).put("badge", item.badge).put("art", artFiles.get(i))
                        .put("link", item.link));
            }
        } catch (JSONException impossible) {
            throw new IOException(impossible);
        }
        File part = new File(context.getFilesDir(), "items.json.part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!part.renameTo(itemsFile())) throw new IOException("items rename");
    }

    /** The cached items as JSON objects (title, subtitle, summary, badge, art file name, link). */
    List<JSONObject> readItems() {
        List<JSONObject> out = new ArrayList<>();
        File file = itemsFile();
        if (!file.isFile()) return out;
        try (InputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 1 << 20)];
            int read = 0;
            for (int n; read < data.length && (n = in.read(data, read, data.length - read)) > 0; ) read += n;
            JSONArray array = new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) out.add(array.getJSONObject(i));
        } catch (IOException | JSONException broken) {
            out.clear();
        }
        return out;
    }

    /** Forgets the account and the server and deletes the cache; the section choices stay. */
    /** Catalogue guid to watch.plex.tv slug, for the titles in the last fetch. */
    Map<String, String> slugs() {
        Map<String, String> out = new HashMap<>();
        try {
            JSONObject map = new JSONObject(prefs.getString("slugs", "{}"));
            for (Iterator<String> keys = map.keys(); keys.hasNext(); ) {
                String key = keys.next();
                out.put(key, map.optString(key));
            }
        } catch (JSONException broken) {
            out.clear();
        }
        return out;
    }

    void setSlugs(Map<String, String> slugs) { prefs.edit().putString("slugs", new JSONObject(slugs).toString()).apply(); }

    void signOut() {
        prefs.edit().remove("account_token").remove("slugs").remove("server_name").remove("server_id").remove("server_token")
                .remove("server_uri").remove("last_fetch").remove("item_count").remove("last_error").apply();
        File[] art = artDir().listFiles();
        if (art != null) for (File file : art) //noinspection ResultOfMethodCallIgnored
            file.delete();
        //noinspection ResultOfMethodCallIgnored
        itemsFile().delete();
    }
}
