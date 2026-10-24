package com.wowee.client;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class InstallerState {

    private static final String PREFS = "wowee_data_installer";

    String dataPath = "";
    boolean installStarted;
    boolean managed;
    boolean wifiOnly = true;
    int dataVersion;
    long lastCheckMillis;
    String manifestUrl = "";
    String manifestJson = "";
    final Map<String, InstalledPack> installed = new HashMap<>();
    final Map<String, Long> downloadIds = new HashMap<>();

    static final class InstalledPack {
        final int version;
        final String sha256;

        InstalledPack(int version, String sha256) {
            this.version = version;
            this.sha256 = sha256;
        }
    }

    static InstallerState load(Context context, File dataDir) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        InstallerState state = new InstallerState();
        String path = dataDir.getAbsolutePath();
        String storedPath = preferences.getString("data_path", "");
        state.wifiOnly = preferences.getBoolean("wifi_only", true);
        if (!path.equals(storedPath)) {
            state.dataPath = path;
            state.save(context);
            return state;
        }
        state.dataPath = storedPath;
        state.installStarted = preferences.getBoolean("install_started", false);
        state.managed = preferences.getBoolean("managed", false);
        state.dataVersion = preferences.getInt("data_version", 0);
        state.lastCheckMillis = preferences.getLong("last_check", 0);
        state.manifestUrl = preferences.getString("manifest_url", "");
        state.manifestJson = preferences.getString("manifest_json", "");
        readInstalled(preferences.getString("installed", ""), state.installed);
        readDownloads(preferences.getString("download_ids", ""), state.downloadIds);
        return state;
    }

    void save(Context context) {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        editor.putString("data_path", dataPath);
        editor.putBoolean("install_started", installStarted);
        editor.putBoolean("managed", managed);
        editor.putBoolean("wifi_only", wifiOnly);
        editor.putInt("data_version", dataVersion);
        editor.putLong("last_check", lastCheckMillis);
        editor.putString("manifest_url", manifestUrl);
        editor.putString("manifest_json", manifestJson);
        editor.putString("installed", installedJson());
        editor.putString("download_ids", downloadsJson());
        editor.apply();
    }

    void clearInstall() {
        installStarted = false;
        managed = false;
        dataVersion = 0;
        lastCheckMillis = 0;
        installed.clear();
        downloadIds.clear();
    }

    private String installedJson() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, InstalledPack> entry : installed.entrySet()) {
                JSONObject pack = new JSONObject();
                pack.put("version", entry.getValue().version);
                pack.put("sha256", entry.getValue().sha256);
                object.put(entry.getKey(), pack);
            }
        } catch (JSONException ignored) {
        }
        return object.toString();
    }

    private String downloadsJson() {
        JSONObject object = new JSONObject();
        try {
            for (Map.Entry<String, Long> entry : downloadIds.entrySet()) {
                object.put(entry.getKey(), entry.getValue());
            }
        } catch (JSONException ignored) {
        }
        return object.toString();
    }

    private static void readInstalled(String json, Map<String, InstalledPack> target) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject pack = object.optJSONObject(key);
                if (pack != null) {
                    target.put(key, new InstalledPack(pack.optInt("version", 0),
                            pack.optString("sha256", "")));
                }
            }
        } catch (JSONException ignored) {
        }
    }

    private static void readDownloads(String json, Map<String, Long> target) {
        if (json == null || json.isEmpty()) {
            return;
        }
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                long id = object.optLong(key, -1);
                if (id >= 0) {
                    target.put(key, id);
                }
            }
        } catch (JSONException ignored) {
        }
    }
}
