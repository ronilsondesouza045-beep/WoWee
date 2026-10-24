package com.wowee.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SetupActivity extends Activity {

    private static final long FREE_SPACE_MARGIN = 512L * 1024 * 1024;
    private static final long CHECK_CACHE_MS = 15L * 60 * 1000;
    private static final int POLL_MS = 800;

    private enum Phase {
        CHECKING, READY, DOWNLOADING, VERIFYING, EXTRACTING, ERROR, DONE
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::pollDownload;
    private ExecutorService io;
    private InstallerState state;
    private File dataDir;
    private DataManifest manifest;
    private final List<DataManifest.Pack> pending = new ArrayList<>();
    private int pendingIndex;
    private long activeDownloadId = -1;
    private DataManifest.Pack activePack;
    private boolean activeZipVerified;
    private Phase phase = Phase.CHECKING;
    private boolean firstInstall;
    private boolean resumedInstall;
    private boolean startedGame;
    private boolean destroyed;
    private boolean allowMetered;
    private Runnable retryAction;

    private ProgressBar progress;
    private TextView status;
    private TextView details;
    private TextView size;
    private TextView message;
    private CheckBox wifiOnly;
    private Button primary;
    private Button secondary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);
        progress = findViewById(R.id.setup_progress);
        status = findViewById(R.id.setup_status);
        details = findViewById(R.id.setup_details);
        size = findViewById(R.id.setup_size);
        message = findViewById(R.id.setup_message);
        wifiOnly = findViewById(R.id.setup_wifi_only);
        primary = findViewById(R.id.setup_primary);
        secondary = findViewById(R.id.setup_secondary);

        io = Executors.newSingleThreadExecutor();
        dataDir = GamePaths.dataDir(this);
        state = InstallerState.load(this, dataDir);
        wifiOnly.setChecked(state.wifiOnly);
        wifiOnly.setOnCheckedChangeListener((button, checked) -> {
            state.wifiOnly = checked;
            state.save(this);
        });
        primary.setOnClickListener(view -> onPrimary());
        secondary.setOnClickListener(view -> onSecondary());

        boolean hasData = GamePaths.hasGameData(dataDir);
        boolean complete = new File(dataDir, "manifest.json").isFile();
        if (!state.installStarted && (!hasData || !complete)) {
            state.clearInstall();
            state.dataPath = dataDir.getAbsolutePath();
            state.save(this);
            hasData = false;
        }
        firstInstall = !hasData && !state.installStarted;
        resumedInstall = state.installStarted;

        message.setText(R.string.setup_message);

        if (resumedInstall) {
            setChecking(getString(R.string.setup_resuming));
            loadManifest(true);
        } else if (firstInstall) {
            setChecking(getString(R.string.setup_checking));
            loadManifest(true);
        } else if (!state.managed) {
            startGame();
        } else if (System.currentTimeMillis() - state.lastCheckMillis < CHECK_CACHE_MS) {
            startGame();
        } else {
            setChecking(getString(R.string.setup_checking_updates));
            loadManifest(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phase == Phase.DOWNLOADING && activeDownloadId >= 0) {
            pollDownload();
        }
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(pollTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (io != null) {
            io.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
                || phase == Phase.EXTRACTING || phase == Phase.CHECKING) {
            confirmExit();
        } else {
            super.onBackPressed();
        }
    }

    private void onPrimary() {
        switch (phase) {
            case READY:
                beginInstall();
                break;
            case ERROR:
                if (retryAction != null) {
                    retryAction.run();
                }
                break;
            case DONE:
                startGame();
                break;
            default:
                break;
        }
    }

    private void onSecondary() {
        if (phase == Phase.READY || phase == Phase.ERROR || phase == Phase.DONE) {
            finish();
        } else {
            confirmExit();
        }
    }

    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_exit_title)
                .setMessage(R.string.setup_exit_message)
                .setPositiveButton(R.string.setup_exit, (dialog, which) -> finish())
                .setNegativeButton(R.string.setup_cancel, null)
                .show();
    }

    private String manifestUrl() {
        String override = DataClient.systemProperty("debug.wowee.dataurl");
        String base = override != null && !override.trim().isEmpty()
                ? override.trim() : BuildConfig.DATA_BASE_URL;
        if (base == null || base.trim().isEmpty() || base.contains("SEU-SERVIDOR-AQUI")) {
            return null;
        }
        base = base.trim();
        if (base.endsWith(".json")) {
            return base;
        }
        if (!base.endsWith("/")) {
            base += "/";
        }
        return base + "version.json";
    }

    private void loadManifest(boolean required) {
        String url = manifestUrl();
        if (url == null) {
            showError(R.string.setup_error_config, () -> loadManifest(required));
            return;
        }
        String cached = state.manifestJson;
        String cachedUrl = state.manifestUrl;
        boolean haveCache = !cached.isEmpty() && !cachedUrl.isEmpty();
        io.execute(() -> {
            DataManifest loaded = null;
            try {
                int connect = required ? (haveCache ? 8000 : 15000) : 3500;
                int read = required ? (haveCache ? 8000 : 20000) : 5000;
                loaded = DataClient.fetchManifest(url, connect, read);
            } catch (Exception error) {
                if (required && haveCache) {
                    try {
                        loaded = DataManifest.parse(cachedUrl, cached);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (loaded == null) {
                main.post(() -> {
                    if (!destroyed) {
                        onManifestError(required);
                    }
                });
                return;
            }
            DataManifest result = loaded;
            main.post(() -> {
                if (!destroyed) {
                    onManifestLoaded(result, required);
                }
            });
        });
    }

    private void onManifestError(boolean required) {
        if (required) {
            showError(R.string.setup_error_manifest, () -> loadManifest(true));
        } else {
            startGame();
        }
    }

    private void onManifestLoaded(DataManifest loaded, boolean required) {
        if (loaded.clientMinVersion != null && !loaded.clientMinVersion.isEmpty()
                && !"0.0.0".equals(BuildConfig.VERSION_NAME)
                && DataManifest.compareVersions(BuildConfig.VERSION_NAME,
                loaded.clientMinVersion) < 0) {
            showClientUpdate(loaded, required);
            return;
        }
        applyManifest(loaded, required);
    }

    private void showClientUpdate(DataManifest loaded, boolean required) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.setup_client_title)
                .setMessage(R.string.setup_client_message)
                .setCancelable(false);
        if (loaded.clientUrl != null && !loaded.clientUrl.isEmpty()) {
            builder.setPositiveButton(R.string.setup_client_open, (dialog, which) -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(loaded.clientUrl)));
                } catch (Exception ignored) {
                }
                if (loaded.clientRequired) {
                    finish();
                } else {
                    applyManifest(loaded, required);
                }
            });
        } else {
            builder.setPositiveButton(R.string.setup_exit, (dialog, which) -> finish());
        }
        if (!loaded.clientRequired) {
            builder.setNegativeButton(R.string.setup_client_later,
                    (dialog, which) -> applyManifest(loaded, required));
        }
        builder.show();
    }

    private void applyManifest(DataManifest loaded, boolean required) {
        manifest = loaded;
        state.manifestUrl = loaded.sourceUrl;
        state.manifestJson = loaded.rawJson;
        state.lastCheckMillis = System.currentTimeMillis();
        if (!state.installStarted && state.dataVersion != loaded.version) {
            for (Long id : new ArrayList<>(state.downloadIds.values())) {
                DownloadHelper.remove(this, id);
            }
            state.downloadIds.clear();
        }
        state.save(this);

        pending.clear();
        pending.addAll(loaded.changedPacks(state));

        if (pending.isEmpty()) {
            if (!new File(dataDir, "manifest.json").isFile()) {
                state.installed.clear();
                state.downloadIds.clear();
                state.dataVersion = 0;
                state.save(this);
                showError(R.string.setup_error_no_data, () -> loadManifest(true));
                return;
            }
            state.managed = true;
            state.installStarted = false;
            state.dataVersion = loaded.version;
            state.save(this);
            startGame();
            return;
        }

        if (resumedInstall) {
            setReady();
            beginInstall();
        } else if (firstInstall || state.installStarted) {
            setReady();
        } else {
            showUpdateDialog(loaded);
        }
    }

    private void setReady() {
        phase = Phase.READY;
        long download = DataManifest.totalDownload(pending);
        long extracted = DataManifest.totalExtracted(pending);
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setTextColor(getColor(R.color.setup_text));
        status.setText(firstInstall && !resumedInstall
                ? R.string.setup_first_run : R.string.setup_ready);
        size.setText(getString(R.string.setup_size, format(download), format(extracted)));
        size.setVisibility(View.VISIBLE);
        details.setText(getResources().getQuantityString(R.plurals.setup_packs,
                pending.size(), pending.size()));
        wifiOnly.setVisibility(View.VISIBLE);
        wifiOnly.setEnabled(true);
        primary.setText(resumedInstall ? R.string.setup_continue
                : firstInstall ? R.string.setup_download : R.string.setup_update);
        primary.setEnabled(true);
        primary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.setup_exit);
        secondary.setEnabled(true);
        secondary.setVisibility(View.VISIBLE);
        retryAction = this::beginInstall;
    }

    private void showUpdateDialog(DataManifest loaded) {
        long bytes = DataManifest.totalDownload(pending);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_update_title)
                .setMessage(getString(R.string.setup_update_message, format(bytes)))
                .setPositiveButton(R.string.setup_update_now, (dialog, which) -> {
                    state.installStarted = true;
                    state.save(this);
                    beginInstall();
                })
                .setNegativeButton(R.string.setup_update_later,
                        (dialog, which) -> startGame())
                .setCancelable(false)
                .show();
    }

    private void beginInstall() {
        if (manifest == null) {
            loadManifest(true);
            return;
        }
        pending.clear();
        pending.addAll(manifest.changedPacks(state));
        pendingIndex = 0;
        activeDownloadId = -1;
        activePack = null;
        activeZipVerified = false;

        if (pending.isEmpty()) {
            finalizeInstall();
            return;
        }
        if (!networkAvailable()) {
            showError(R.string.setup_error_network, this::beginInstall);
            return;
        }
        if (wifiOnly.isChecked() && isMetered() && !isWifi() && !allowMetered) {
            showWifiDialog();
            return;
        }
        if (!hasFreeSpace()) {
            return;
        }
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            showError(R.string.setup_error_storage, this::beginInstall);
            return;
        }
        state.installStarted = true;
        state.save(this);
        GamePaths.ensureNoMedia(dataDir);
        downloadNext();
    }

    private void showWifiDialog() {
        long bytes = DataManifest.totalDownload(pending);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_wifi_title)
                .setMessage(getString(R.string.setup_wifi_message, format(bytes)))
                .setPositiveButton(R.string.setup_download_anyway, (dialog, which) -> {
                    allowMetered = true;
                    beginInstall();
                })
                .setNegativeButton(R.string.setup_wifi_settings,
                        (dialog, which) -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)))
                .setCancelable(true)
                .show();
    }

    private void downloadNext() {
        if (pendingIndex >= pending.size()) {
            finalizeInstall();
            return;
        }
        activePack = pending.get(pendingIndex);
        activeZipVerified = false;
        Long saved = state.downloadIds.get(activePack.id);
        if (saved != null) {
            DownloadHelper.Status current = DownloadHelper.query(this, saved);
            if (current.state == DownloadHelper.STATE_RUNNING
                    || current.state == DownloadHelper.STATE_PENDING
                    || current.state == DownloadHelper.STATE_PAUSED) {
                activeDownloadId = saved;
                setDownloading();
                updateDownloadProgress(current.bytes, current.total, current.state);
                schedulePoll();
                return;
            }
            if (current.state == DownloadHelper.STATE_SUCCESS) {
                activeDownloadId = saved;
                verifyAndExtract();
                return;
            }
            DownloadHelper.remove(this, saved);
            state.downloadIds.remove(activePack.id);
            state.save(this);
        }

        activeDownloadId = -1;
        setDownloading();
        status.setText(getString(R.string.setup_checking_file));
        progress.setIndeterminate(true);
        final DataManifest.Pack pack = activePack;
        io.execute(() -> {
            File archive = archiveFor(pack);
            boolean valid = false;
            if (archive.isFile() && archive.length() == pack.size) {
                try {
                    valid = pack.sha256.equals(ZipUtils.sha256(archive));
                } catch (IOException ignored) {
                }
            }
            if (!valid && archive.exists()) {
                archive.delete();
            }
            boolean ready = valid;
            main.post(() -> {
                if (destroyed) {
                    return;
                }
                if (ready) {
                    activeZipVerified = true;
                    verifyAndExtract();
                } else {
                    enqueueActive();
                }
            });
        });
    }

    private void enqueueActive() {
        if (activePack == null) {
            return;
        }
        if (availableBytes() < activePack.size + FREE_SPACE_MARGIN) {
            showError(getString(R.string.setup_error_space,
                    format(activePack.size + FREE_SPACE_MARGIN)), this::retryInstall);
            return;
        }
        try {
            activeDownloadId = DownloadHelper.enqueue(this, activePack, archiveFor(activePack),
                    wifiOnly.isChecked() && !allowMetered);
            state.downloadIds.put(activePack.id, activeDownloadId);
            state.save(this);
            setDownloading();
            schedulePoll();
        } catch (Exception error) {
            showError(R.string.setup_error_download, this::retryInstall);
        }
    }

    private void schedulePoll() {
        main.removeCallbacks(pollTask);
        main.postDelayed(pollTask, POLL_MS);
    }

    private void pollDownload() {
        if (destroyed || phase != Phase.DOWNLOADING || activeDownloadId < 0) {
            return;
        }
        DownloadHelper.Status current = DownloadHelper.query(this, activeDownloadId);
        switch (current.state) {
            case DownloadHelper.STATE_RUNNING:
            case DownloadHelper.STATE_PENDING:
            case DownloadHelper.STATE_PAUSED:
                updateDownloadProgress(current.bytes, current.total, current.state);
                schedulePoll();
                break;
            case DownloadHelper.STATE_SUCCESS:
                verifyAndExtract();
                break;
            case DownloadHelper.STATE_FAILED:
                onDownloadFailed(current.reason);
                break;
            default:
                onDownloadFailed(-1);
                break;
        }
    }

    private void updateDownloadProgress(long bytes, long total, int downloadState) {
        long packTotal = activePack != null && activePack.size > 0 ? activePack.size : total;
        long safeBytes = Math.max(0, bytes);
        if (packTotal > 0) {
            safeBytes = Math.min(safeBytes, packTotal);
        }
        long doneBefore = 0;
        for (int i = 0; i < pendingIndex && i < pending.size(); i++) {
            doneBefore += pending.get(i).size;
        }
        long all = DataManifest.totalDownload(pending);
        int overall = all > 0 ? (int) Math.min(1000, (doneBefore + safeBytes) * 1000 / all) : 0;
        progress.setIndeterminate(false);
        progress.setProgress(overall);
        status.setText(downloadState == DownloadHelper.STATE_PAUSED
                ? R.string.setup_waiting_network : R.string.setup_downloading);
        String amount = packTotal > 0
                ? getString(R.string.setup_progress, format(safeBytes), format(packTotal))
                : format(safeBytes);
        details.setText(getString(R.string.setup_pack_progress,
                pendingIndex + 1, pending.size(), amount));
    }

    private void verifyAndExtract() {
        if (activePack == null) {
            return;
        }
        final DataManifest.Pack pack = activePack;
        final File archive = archiveFor(pack);
        phase = Phase.VERIFYING;
        progress.setIndeterminate(true);
        status.setText(getString(R.string.setup_verifying));
        details.setText("");
        primary.setVisibility(View.GONE);
        secondary.setVisibility(View.VISIBLE);
        wifiOnly.setVisibility(View.GONE);
        io.execute(() -> {
            try {
                if (!activeZipVerified) {
                    String digest = ZipUtils.sha256(archive);
                    if (!pack.sha256.equals(digest)) {
                        throw new IOException("checksum mismatch");
                    }
                }
                main.post(() -> {
                    if (!destroyed) {
                        extractActive(pack, archive);
                    }
                });
            } catch (Exception error) {
                if (archive.exists()) {
                    archive.delete();
                }
                main.post(() -> {
                    if (!destroyed) {
                        onPackError(R.string.setup_error_integrity);
                    }
                });
            }
        });
    }

    private void extractActive(DataManifest.Pack pack, File archive) {
        phase = Phase.EXTRACTING;
        progress.setIndeterminate(true);
        status.setText(getString(R.string.setup_extracting));
        details.setText("");
        io.execute(() -> {
            final long[] lastPost = {0};
            try {
                ZipUtils.extract(archive, dataDir, (done, total) -> {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastPost[0] < 250) {
                        return;
                    }
                    lastPost[0] = now;
                    main.post(() -> {
                        if (destroyed) {
                            return;
                        }
                        progress.setIndeterminate(total <= 0);
                        if (total > 0) {
                            progress.setProgress((int) Math.min(1000, done * 1000 / total));
                        }
                    });
                });
                archive.delete();
                main.post(() -> {
                    if (!destroyed) {
                        onPackInstalled(pack);
                    }
                });
            } catch (Exception error) {
                if (archive.exists()) {
                    archive.delete();
                }
                main.post(() -> {
                    if (!destroyed) {
                        onPackError(R.string.setup_error_extract);
                    }
                });
            }
        });
    }

    private void onPackInstalled(DataManifest.Pack pack) {
        state.installed.put(pack.id, new InstallerState.InstalledPack(pack.version, pack.sha256));
        state.downloadIds.remove(pack.id);
        state.save(this);
        if (activeDownloadId >= 0) {
            DownloadHelper.remove(this, activeDownloadId);
        }
        pendingIndex++;
        activeDownloadId = -1;
        activePack = null;
        downloadNext();
    }

    private void onPackError(int messageRes) {
        if (activeDownloadId >= 0) {
            DownloadHelper.remove(this, activeDownloadId);
            activeDownloadId = -1;
        }
        if (activePack != null) {
            state.downloadIds.remove(activePack.id);
            state.save(this);
        }
        showError(messageRes, this::retryInstall);
    }

    private void onDownloadFailed(int reason) {
        if (activeDownloadId >= 0) {
            DownloadHelper.remove(this, activeDownloadId);
        }
        if (activePack != null) {
            File archive = archiveFor(activePack);
            if (archive.exists()) {
                archive.delete();
            }
            state.downloadIds.remove(activePack.id);
            state.save(this);
        }
        if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
            showError(R.string.setup_error_space_device, this::retryInstall);
        } else if (reason == DownloadManager.ERROR_HTTP_DATA_ERROR
                || reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE) {
            showError(R.string.setup_error_network, this::retryInstall);
        } else {
            showError(R.string.setup_error_download, this::retryInstall);
        }
    }

    private void retryInstall() {
        if (manifest == null) {
            loadManifest(true);
            return;
        }
        beginInstall();
    }

    private void finalizeInstall() {
        if (manifest == null) {
            showError(R.string.setup_error_manifest, () -> loadManifest(true));
            return;
        }
        if (!new File(dataDir, "manifest.json").isFile()) {
            showError(R.string.setup_error_no_data, this::retryInstall);
            return;
        }
        state.managed = true;
        state.installStarted = false;
        state.dataVersion = manifest.version;
        state.lastCheckMillis = System.currentTimeMillis();
        state.downloadIds.clear();
        state.save(this);
        cleanupDownloads();
        phase = Phase.DONE;
        progress.setIndeterminate(false);
        progress.setProgress(1000);
        status.setTextColor(getColor(R.color.setup_text));
        status.setText(R.string.setup_done);
        details.setText("");
        size.setVisibility(View.GONE);
        wifiOnly.setVisibility(View.GONE);
        primary.setText(R.string.setup_start);
        primary.setEnabled(true);
        primary.setVisibility(View.VISIBLE);
        secondary.setVisibility(View.GONE);
        main.postDelayed(this::startGame, 900);
    }

    private void cleanupDownloads() {
        File directory = GamePaths.downloadsDir(this);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.getName().endsWith(".zip") || file.getName().endsWith(".part")) {
                file.delete();
            }
        }
    }

    private void startGame() {
        if (startedGame || destroyed) {
            return;
        }
        startedGame = true;
        startActivity(new Intent(this, WoweeActivity.class));
        finish();
    }

    private void setChecking(String label) {
        phase = Phase.CHECKING;
        progress.setIndeterminate(true);
        status.setTextColor(getColor(R.color.setup_text));
        status.setText(label);
        details.setText("");
        size.setVisibility(View.GONE);
        wifiOnly.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        secondary.setText(R.string.setup_exit);
        secondary.setEnabled(true);
        secondary.setVisibility(View.VISIBLE);
    }

    private void setDownloading() {
        phase = Phase.DOWNLOADING;
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setTextColor(getColor(R.color.setup_text));
        status.setText(R.string.setup_downloading);
        details.setText(getString(R.string.setup_pack_progress, pendingIndex + 1,
                pending.size(), format(0)));
        size.setVisibility(View.GONE);
        wifiOnly.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        secondary.setText(R.string.setup_exit);
        secondary.setEnabled(true);
        secondary.setVisibility(View.VISIBLE);
    }

    private void showError(int messageRes, Runnable retry) {
        showError(getString(messageRes), retry);
    }

    private void showError(String text, Runnable retry) {
        phase = Phase.ERROR;
        progress.setIndeterminate(false);
        progress.setProgress(0);
        status.setTextColor(getColor(R.color.setup_error));
        status.setText(text);
        details.setText("");
        size.setVisibility(View.GONE);
        wifiOnly.setVisibility(View.VISIBLE);
        primary.setText(R.string.setup_retry);
        primary.setEnabled(true);
        primary.setVisibility(View.VISIBLE);
        secondary.setText(R.string.setup_exit);
        secondary.setEnabled(true);
        secondary.setVisibility(View.VISIBLE);
        retryAction = retry;
    }

    private File archiveFor(DataManifest.Pack pack) {
        return new File(GamePaths.downloadsDir(this), pack.id + "-v" + pack.version + ".zip");
    }

    private long estimateRequired() {
        long extracted = 0;
        long largest = 0;
        for (DataManifest.Pack pack : pending) {
            long unpacked = pack.extractedSize > 0 ? pack.extractedSize : pack.size;
            extracted += Math.max(0, unpacked);
            largest = Math.max(largest, Math.max(0, pack.size));
        }
        return extracted + largest + FREE_SPACE_MARGIN;
    }

    private boolean hasFreeSpace() {
        long required = estimateRequired();
        if (availableBytes() < required) {
            showError(getString(R.string.setup_error_space, format(required)),
                    this::retryInstall);
            return false;
        }
        return true;
    }

    private long availableBytes() {
        try {
            if (!dataDir.isDirectory()) {
                dataDir.mkdirs();
            }
            StatFs stat = new StatFs(dataDir.getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception error) {
            return Long.MAX_VALUE;
        }
    }

    private NetworkCapabilities capabilities() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        Network network = manager.getActiveNetwork();
        return network == null ? null : manager.getNetworkCapabilities(network);
    }

    private boolean networkAvailable() {
        NetworkCapabilities caps = capabilities();
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private boolean isWifi() {
        NetworkCapabilities caps = capabilities();
        return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
    }

    private boolean isMetered() {
        NetworkCapabilities caps = capabilities();
        return caps != null
                && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    private String format(long bytes) {
        return Formatter.formatShortFileSize(this, Math.max(0, bytes));
    }
}
