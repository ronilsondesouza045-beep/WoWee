package com.wowee.client;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class DataClient {

    private static final int MAX_MANIFEST_BYTES = 512 * 1024;
    private static final int MAX_REDIRECTS = 4;

    private DataClient() {
    }

    static boolean allowHttp() {
        String value = systemProperty("debug.wowee.allow_http");
        return value != null && !value.isEmpty() && !"0".equals(value);
    }

    static String systemProperty(String name) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            Object value = properties.getMethod("get", String.class).invoke(null, name);
            return value instanceof String ? (String) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    static DataManifest fetchManifest(String manifestUrl, int connectTimeoutMs, int readTimeoutMs)
            throws IOException, JSONException {
        URL url = new URL(manifestUrl);
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            requireHttps(url);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "WoWee-Android/" + BuildConfig.VERSION_NAME);
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("redirect without location");
                    }
                    url = new URL(url, location);
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + code);
                }
                String body = readBounded(connection.getInputStream());
                return DataManifest.parse(url.toString(), body);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    private static void requireHttps(URL url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getProtocol()) && !allowHttp()) {
            throw new IOException("HTTPS required");
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_MANIFEST_BYTES) {
                    throw new IOException("manifest too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
