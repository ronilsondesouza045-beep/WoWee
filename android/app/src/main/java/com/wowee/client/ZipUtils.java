package com.wowee.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ZipUtils {

    interface Progress {
        void onProgress(long done, long total);
    }

    private ZipUtils() {
    }

    static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder text = new StringBuilder(64);
        for (byte value : digest.digest()) {
            text.append(String.format("%02x", value & 0xff));
        }
        return text.toString();
    }

    static void extract(File archive, File destination, Progress progress) throws IOException {
        File root = destination.getCanonicalFile();
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("cannot create " + root);
        }
        try (ZipFile zip = new ZipFile(archive)) {
            long total = 0;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getSize() > 0) {
                    total += entry.getSize();
                }
            }

            long done = 0;
            byte[] buffer = new byte[1024 * 1024];
            entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("__MACOSX/") || name.equals(".DS_Store")
                        || name.endsWith("/.DS_Store")) {
                    continue;
                }
                if (!safeName(name)) {
                    throw new IOException("unsafe archive entry");
                }
                File target = new File(root, name);
                String canonical = target.getCanonicalPath();
                if (!canonical.equals(root.getPath())
                        && !canonical.startsWith(root.getPath() + File.separator)) {
                    throw new IOException("archive entry outside destination");
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("cannot create " + target);
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create " + parent);
                }
                try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1024 * 1024);
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 1024 * 1024)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        done += read;
                        if (progress != null) {
                            progress.onProgress(done, total);
                        }
                    }
                }
            }
            if (progress != null) {
                progress.onProgress(done, total);
            }
        }
    }

    private static boolean safeName(String name) {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) {
            return false;
        }
        if (name.startsWith("/")) {
            return false;
        }
        String[] parts = name.split("/");
        for (String part : parts) {
            if (part.equals("..")) {
                return false;
            }
        }
        return true;
    }
}
