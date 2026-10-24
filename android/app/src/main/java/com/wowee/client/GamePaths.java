package com.wowee.client;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class GamePaths {

    private GamePaths() {
    }

    static File root(Context context) {
        File root = context.getExternalFilesDir(null);
        return root != null ? root : context.getFilesDir();
    }

    static File primaryData(Context context) {
        return new File(root(context), "Data");
    }

    static File mediaData(Context context) {
        return new File(Environment.getExternalStorageDirectory(),
                "Android/media/" + context.getPackageName() + "/Data");
    }

    static File dataDir(Context context) {
        File primary = primaryData(context);
        if (new File(primary, "manifest.json").isFile()) {
            return primary;
        }
        File media = mediaData(context);
        if (new File(media, "manifest.json").isFile()) {
            return media;
        }
        if (hasGameData(primary)) {
            return primary;
        }
        if (hasGameData(media)) {
            return media;
        }
        return primary;
    }

    static File downloadsDir(Context context) {
        return new File(root(context), "downloads");
    }

    static boolean hasGameData(File data) {
        String[] entries = data.list();
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (!entry.startsWith(".") && !entry.equals("expansions") && !entry.equals("opcodes")) {
                return true;
            }
        }
        File expansions = new File(data, "expansions");
        String[] ids = expansions.list();
        if (ids != null) {
            for (String id : ids) {
                if (new File(expansions, id + "/manifest.json").isFile()) {
                    return true;
                }
            }
        }
        return false;
    }

    static void ensureNoMedia(File data) {
        File marker = new File(data, ".nomedia");
        if (marker.exists()) {
            return;
        }
        if (!data.isDirectory() && !data.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write('\n');
        } catch (IOException ignored) {
        }
    }
}
