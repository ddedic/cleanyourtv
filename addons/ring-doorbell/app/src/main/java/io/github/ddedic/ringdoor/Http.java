package io.github.ddedic.ringdoor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Small blocking HTTP calls. Errors carry the status and the path only, never a body that could echo a token. */
final class Http {
    static final int TIMEOUT_MS = 15000;

    static final class Response {
        final int code;
        final byte[] body;
        final String type, location;

        Response(int code, byte[] body, String type, String location) {
            this.code = code;
            this.body = body;
            this.type = type == null ? "" : type;
            this.location = location == null ? "" : location;
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        Response ok(String what) throws IOException {
            if (code < 200 || code >= 300) throw new IOException(what + " " + code);
            return this;
        }
    }

    private Http() { }

    /** follow false: a redirect comes back as it is (its Location in the response), not followed with our headers. */
    static Response call(String method, String url, Map<String, String> headers, byte[] body, boolean follow)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setInstanceFollowRedirects(follow);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) {
                connection.setRequestProperty(h.getKey(), h.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    byte[] buffer = new byte[16384];
                    for (int n; (n = stream.read(buffer)) > 0; ) data.write(buffer, 0, n);
                }
            }
            return new Response(code, data.toByteArray(), connection.getContentType(),
                    connection.getHeaderField("Location"));
        } finally {
            connection.disconnect();
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
