package io.github.ddedic.plexfeed;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * plex.tv sign-in with a PIN (plex.tv/link), the account's servers, and what the feed reads from one server:
 * its sections, recently added movies and episodes, On Deck, and artwork through the server's photo transcoder.
 * Blocking calls: run them off the main thread. Only https connections are used.
 */
final class Plex {
    static final String PLEX_TV = "https://plex.tv";
    private static final int TIMEOUT_MS = 8000;
    private static final int PROBE_TIMEOUT_MS = 4000;
    private static final int MAX_ART_BYTES = 3 * 1024 * 1024;

    static final class Pin {
        final long id;
        final String code;

        Pin(long id, String code) {
            this.id = id;
            this.code = code;
        }
    }

    static final class Server {
        final String name, machineId, token;
        final boolean owned;
        /** https connection URIs, best first: direct remote, then local, then relay. */
        final List<String> uris;

        Server(String name, String machineId, String token, boolean owned, List<String> uris) {
            this.name = name;
            this.machineId = machineId;
            this.token = token;
            this.owned = owned;
            this.uris = uris;
        }
    }

    /** One movie or episode from the server, with only the fields the feed shows. */
    static final class Entry {
        String ratingKey = "", type = "", title = "", show = "", showKey = "", summary = "", art = "";
        int season, episode, year;
        long addedAt, viewOffset, duration;
    }

    private final String clientId;

    Plex(String clientId) {
        this.clientId = clientId;
    }

    // ---- plex.tv -------------------------------------------------------------------------------

    Pin createPin() throws IOException, JSONException {
        return parsePin(new JSONObject(request("POST", PLEX_TV + "/api/v2/pins?strong=false", null, PROBE_TIMEOUT_MS * 2)));
    }

    /** The account token once the code was entered at plex.tv/link, or null while it is still waiting. */
    String pollPin(long id) throws IOException, JSONException {
        JSONObject pin = new JSONObject(request("GET", PLEX_TV + "/api/v2/pins/" + id, null, TIMEOUT_MS));
        String token = pin.optString("authToken", "");
        return token.isEmpty() || "null".equals(token) ? null : token;
    }

    List<Server> servers(String accountToken) throws IOException, JSONException {
        return parseServers(new JSONArray(request("GET",
                PLEX_TV + "/api/v2/resources?includeHttps=1&includeRelay=1", accountToken, TIMEOUT_MS)));
    }

    /** The first of the server's connections that answers, or null. */
    String reachable(Server server) {
        for (String uri : server.uris) {
            try {
                request("GET", uri + "/identity", server.token, PROBE_TIMEOUT_MS);
                return uri;
            } catch (IOException unreachable) {
                // Next connection.
            }
        }
        return null;
    }

    // ---- one server ------------------------------------------------------------------------------

    /** [key, type] of each movie and show section. */
    List<String[]> sections(String base, String token) throws IOException, JSONException {
        JSONArray dirs = container(request("GET", base + "/library/sections", token, TIMEOUT_MS)).optJSONArray("Directory");
        List<String[]> out = new ArrayList<>();
        if (dirs == null) return out;
        for (int i = 0; i < dirs.length(); i++) {
            JSONObject dir = dirs.getJSONObject(i);
            String type = dir.optString("type");
            if ("movie".equals(type) || "show".equals(type)) out.add(new String[]{dir.optString("key"), type});
        }
        return out;
    }

    /** Newest first: plexType 1 is movies, 4 is episodes. */
    List<Entry> recentlyAdded(String base, String token, String section, int plexType, int count)
            throws IOException, JSONException {
        String path = base + "/library/sections/" + section + "/all?type=" + plexType
                + "&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=" + count;
        return parseEntries(container(request("GET", path, token, TIMEOUT_MS)));
    }

    List<Entry> onDeck(String base, String token, int count) throws IOException, JSONException {
        String path = base + "/library/onDeck?X-Plex-Container-Start=0&X-Plex-Container-Size=" + count;
        return parseEntries(container(request("GET", path, token, TIMEOUT_MS)));
    }

    /** The artwork at `path` as a 1280x720 JPEG in `out`, through the server's photo transcoder. */
    void art(String base, String token, String path, File out) throws IOException {
        String url = base + "/photo/:/transcode?width=1280&height=720&minSize=1&upscale=1&url="
                + URLEncoder.encode(path, "UTF-8");
        HttpURLConnection connection = open("GET", url, token, TIMEOUT_MS);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("art " + connection.getResponseCode());
            File part = new File(out.getPath() + ".part");
            int total = 0;
            try (InputStream in = connection.getInputStream(); OutputStream file = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > MAX_ART_BYTES) throw new IOException("art too large");
                    file.write(buffer, 0, n);
                }
            }
            if (!part.renameTo(out)) throw new IOException("art rename");
        } finally {
            connection.disconnect();
        }
    }

    // ---- HTTP ------------------------------------------------------------------------------------

    private String request(String method, String url, String token, int timeout) throws IOException {
        HttpURLConnection connection = open(method, url, token, timeout);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException(method + " " + code);
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

    private HttpURLConnection open(String method, String url, String token, int timeout) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("https only");
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Plex-Product", "CleanYourTV Plex Feed");
        connection.setRequestProperty("X-Plex-Version", "1.0");
        connection.setRequestProperty("X-Plex-Client-Identifier", clientId);
        connection.setRequestProperty("X-Plex-Platform", "Android");
        connection.setRequestProperty("X-Plex-Device", "Android TV");
        if (token != null) connection.setRequestProperty("X-Plex-Token", token);
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
        }
        return connection;
    }

    // ---- parsing (plain JSON, unit-tested) ---------------------------------------------------------

    static Pin parsePin(JSONObject pin) throws JSONException {
        return new Pin(pin.getLong("id"), pin.getString("code"));
    }

    static List<Server> parseServers(JSONArray resources) throws JSONException {
        List<Server> out = new ArrayList<>();
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resource = resources.getJSONObject(i);
            if (!resource.optString("provides").contains("server")) continue;
            List<String> remote = new ArrayList<>(), local = new ArrayList<>(), relay = new ArrayList<>();
            JSONArray connections = resource.optJSONArray("connections");
            for (int j = 0; connections != null && j < connections.length(); j++) {
                JSONObject c = connections.getJSONObject(j);
                String uri = c.optString("uri");
                if (!uri.startsWith("https://")) continue;
                if (c.optBoolean("relay")) relay.add(uri);
                else if (c.optBoolean("local")) local.add(uri);
                else remote.add(uri);
            }
            List<String> uris = new ArrayList<>(remote);
            uris.addAll(local);
            uris.addAll(relay);
            if (uris.isEmpty()) continue;
            out.add(new Server(resource.optString("name", "Plex server"), resource.optString("clientIdentifier"),
                    resource.optString("accessToken"), resource.optBoolean("owned"), uris));
        }
        return out;
    }

    static JSONObject container(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONObject container = root.optJSONObject("MediaContainer");
        return container != null ? container : root;
    }

    static List<Entry> parseEntries(JSONObject container) {
        List<Entry> out = new ArrayList<>();
        JSONArray items = container.optJSONArray("Metadata");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject m = items.optJSONObject(i);
            if (m == null) continue;
            String type = m.optString("type");
            if (!"movie".equals(type) && !"episode".equals(type)) continue;
            Entry e = new Entry();
            e.type = type;
            e.ratingKey = m.optString("ratingKey");
            e.title = m.optString("title");
            e.show = m.optString("grandparentTitle");
            e.showKey = m.optString("grandparentRatingKey");
            e.season = m.optInt("parentIndex");
            e.episode = m.optInt("index");
            e.year = m.optInt("year");
            e.summary = m.optString("summary");
            e.addedAt = m.optLong("addedAt");
            e.viewOffset = m.optLong("viewOffset");
            e.duration = m.optLong("duration");
            // A wide backdrop first: the show's for an episode, the film's own for a movie; the poster last.
            String art = "episode".equals(type) ? m.optString("grandparentArt") : "";
            if (art.isEmpty()) art = m.optString("art");
            if (art.isEmpty()) art = m.optString("episode".equals(type) ? "grandparentThumb" : "thumb");
            e.art = art;
            if (!e.ratingKey.isEmpty() && !e.title.isEmpty()) out.add(e);
        }
        return out;
    }
}
