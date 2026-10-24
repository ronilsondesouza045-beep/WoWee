package com.wowee.client;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DataManifest {

    static final int SCHEMA = 1;

    final int version;
    final String generated;
    final String clientMinVersion;
    final String clientUrl;
    final boolean clientRequired;
    final List<Pack> packs;
    final String rawJson;
    final String sourceUrl;

    private DataManifest(int version, String generated, String clientMinVersion,
                         String clientUrl, boolean clientRequired, List<Pack> packs,
                         String rawJson, String sourceUrl) {
        this.sourceUrl = sourceUrl;
        this.version = version;
        this.generated = generated;
        this.clientMinVersion = clientMinVersion;
        this.clientUrl = clientUrl;
        this.clientRequired = clientRequired;
        this.packs = packs;
        this.rawJson = rawJson;
    }

    static final class Pack {
        final String id;
        final int version;
        final String url;
        final long size;
        final long extractedSize;
        final long fileCount;
        final String sha256;

        Pack(String id, int version, String url, long size, long extractedSize,
             long fileCount, String sha256) {
            this.id = id;
            this.version = version;
            this.url = url;
            this.size = size;
            this.extractedSize = extractedSize;
            this.fileCount = fileCount;
            this.sha256 = sha256;
        }
    }

    static DataManifest parse(String manifestUrl, String json) throws JSONException, MalformedURLException {
        JSONObject root = new JSONObject(json);
        int schema = root.optInt("schema", SCHEMA);
        if (schema != SCHEMA) {
            throw new JSONException("unsupported schema " + schema);
        }
        int version = root.getInt("version");
        if (version < 1) {
            throw new JSONException("invalid version " + version);
        }
        String generated = root.optString("generated", "");
        String clientMin = null;
        String clientUrl = null;
        boolean clientRequired = false;
        JSONObject client = root.optJSONObject("client");
        if (client != null) {
            clientMin = client.optString("minVersion", null);
            clientUrl = client.optString("url", null);
            clientRequired = client.optBoolean("required", false);
        }

        JSONArray array = root.getJSONArray("packs");
        if (array.length() == 0) {
            throw new JSONException("no packs");
        }
        URL base = new URL(manifestUrl);
        List<Pack> packs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.getJSONObject(i);
            String id = object.getString("id");
            if (!id.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new JSONException("invalid pack id " + id);
            }
            if (!ids.add(id)) {
                throw new JSONException("duplicate pack id " + id);
            }
            int packVersion = object.optInt("version", version);
            URL url = new URL(base, object.getString("url"));
            if (!"https".equalsIgnoreCase(url.getProtocol()) && !DataClient.allowHttp()) {
                throw new MalformedURLException("pack " + id + " is not HTTPS");
            }
            long size = object.optLong("size", 0);
            long extractedSize = object.optLong("extractedSize", 0);
            long fileCount = object.optLong("fileCount", 0);
            String sha = object.getString("sha256").toLowerCase(Locale.US);
            if (!sha.matches("[0-9a-f]{64}")) {
                throw new JSONException("invalid sha256 for pack " + id);
            }
            if (size < 0 || extractedSize < 0) {
                throw new JSONException("invalid size for pack " + id);
            }
            packs.add(new Pack(id, packVersion, url.toString(), size, extractedSize, fileCount, sha));
        }
        return new DataManifest(version, generated, clientMin, clientUrl, clientRequired, packs, json, manifestUrl);
    }

    List<Pack> changedPacks(InstallerState state) {
        List<Pack> changed = new ArrayList<>();
        for (Pack pack : packs) {
            InstallerState.InstalledPack installed = state.installed.get(pack.id);
            if (installed == null || !pack.sha256.equals(installed.sha256)) {
                changed.add(pack);
            }
        }
        return changed;
    }

    static long totalDownload(List<Pack> packs) {
        long total = 0;
        for (Pack pack : packs) {
            total += Math.max(0, pack.size);
        }
        return total;
    }

    static long totalExtracted(List<Pack> packs) {
        long total = 0;
        for (Pack pack : packs) {
            total += Math.max(0, pack.extractedSize);
        }
        return total;
    }

    static int compareVersions(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("[^0-9]+");
        String[] b = right == null ? new String[0] : right.split("[^0-9]+");
        int count = Math.max(a.length, b.length);
        for (int i = 0; i < count; i++) {
            int x = i < a.length && !a[i].isEmpty() ? parsePart(a[i]) : 0;
            int y = i < b.length && !b[i].isEmpty() ? parsePart(b[i]) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parsePart(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
