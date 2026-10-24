package com.wowee.client;

import android.app.DownloadManager;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;

final class DownloadHelper {

    static final int STATE_UNKNOWN = 0;
    static final int STATE_PENDING = 1;
    static final int STATE_RUNNING = 2;
    static final int STATE_SUCCESS = 3;
    static final int STATE_FAILED = 4;
    static final int STATE_PAUSED = 5;

    static final class Status {
        final int state;
        final long bytes;
        final long total;
        final int reason;

        Status(int state, long bytes, long total, int reason) {
            this.state = state;
            this.bytes = bytes;
            this.total = total;
            this.reason = reason;
        }
    }

    private DownloadHelper() {
    }

    static long enqueue(Context context, DataManifest.Pack pack, File destination, boolean wifiOnly) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pack.url));
        request.setTitle("WoWee - " + pack.id);
        request.setDescription("Dados do jogo");
        request.setMimeType("application/zip");
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        request.setDestinationInExternalFilesDir(context, "downloads", destination.getName());
        request.setAllowedOverMetered(!wifiOnly);
        request.setAllowedOverRoaming(false);
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("DownloadManager unavailable");
        }
        return manager.enqueue(request);
    }

    static Status query(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager == null) {
            return new Status(STATE_UNKNOWN, 0, 0, 0);
        }
        Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id));
        if (cursor == null) {
            return new Status(STATE_UNKNOWN, 0, 0, 0);
        }
        try {
            if (!cursor.moveToFirst()) {
                return new Status(STATE_UNKNOWN, 0, 0, 0);
            }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long bytes = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cursor.getLong(cursor.getColumnIndexOrThrow(
                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
            switch (status) {
                case DownloadManager.STATUS_PENDING:
                    return new Status(STATE_PENDING, bytes, total, reason);
                case DownloadManager.STATUS_RUNNING:
                    return new Status(STATE_RUNNING, bytes, total, reason);
                case DownloadManager.STATUS_SUCCESSFUL:
                    return new Status(STATE_SUCCESS, bytes, total, reason);
                case DownloadManager.STATUS_PAUSED:
                    return new Status(STATE_PAUSED, bytes, total, reason);
                case DownloadManager.STATUS_FAILED:
                    return new Status(STATE_FAILED, bytes, total, reason);
                default:
                    return new Status(STATE_UNKNOWN, bytes, total, reason);
            }
        } finally {
            cursor.close();
        }
    }

    static void remove(Context context, long id) {
        DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (manager != null) {
            manager.remove(id);
        }
    }
}
