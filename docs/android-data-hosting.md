# Hosting game data for the Android installer

The Android APK no longer expects the player to copy `Data/` over USB. On first
launch `SetupActivity` downloads a versioned set of ZIP packs, verifies each
SHA-256, extracts it into the app's external files directory, and starts the
game. Later launches check `version.json` and download only packs whose hash
changed.

This page covers the server side: cutting an extraction down to a profile,
packing it, uploading it, and publishing updates.

## 1. Prepare the Data tree

Start from an extracted `Data` directory, as described in
`tools/android/README.md`. For a phone, use a profile rather than the full 18 GB:

```
python tools/android/make_minimal_data.py --source ~/Data --profile login --out ~/Data-login
python tools/android/make_minimal_data.py --source ~/Data --profile world --out ~/Data-world
```

The packer needs `manifest.json` at the root of the tree. Top-level files and
`Data/opcodes` go into a small `core` pack. `Data/expansions` is packed like any
other top-level directory, so the large per-expansion trees in a full
extraction are split instead of becoming one oversized core pack.

## 2. Pack it

```
python tools/android/pack_data.py --source ~/Data-login --out ~/dist --version 1
```

Output:

```
dist/version.json
dist/packs/core-v1.zip
dist/packs/sound-v1.zip
dist/packs/terrain-v1.zip
...
```

The packer keeps top-level directories together, so `textures`, `world` and
`sound` update independently. A directory larger than `--max-pack-bytes`
(default 1.5 GB) is split into `name-01`, `name-02`, and so on. Top-level files
and `opcodes/` go into `core`; `expansions/` follows the same split rule as any
other directory.

Options:

| Option | Purpose |
|---|---|
| `--base-url https://cdn.example.com/wowee/android` | Write absolute pack URLs instead of relative `packs/...` URLs. |
| `--url-template 'https://github.com/u/r/releases/download/data-v{version}/{file}'` | Build each pack URL from a template. Placeholders: `{file}`, `{version}`, `{pack}`. |
| `--bump` | Read `OUT/version.json` and publish the next version. |
| `--max-pack-bytes N` | Change the unpacked-size limit per pack. |
| `--store` | Store entries without compression. Useful when the data is already compressed. |
| `--dry-run` | Print the pack plan without writing anything. |
| `--clean` | Delete local `packs/*.zip` files the new manifest does not reference. |
| `--client-min 0.2.0` | Require an app version before installing this data. |
| `--client-url https://.../app.apk` | Offer a newer APK when the version is too old. |
| `--client-required` | Block data installation until the app is updated. |

ZIPs are deterministic: timestamps are fixed, order is sorted, and the same
input produces the same hash. This is what lets the app skip unchanged packs.

## 3. Upload

Upload `version.json` and the `packs/` directory keeping their names. With the
default relative URLs, this layout works:

```
https://cdn.example.com/wowee/android/version.json
https://cdn.example.com/wowee/android/packs/core-v1.zip
https://cdn.example.com/wowee/android/packs/world-v1.zip
```

With `--base-url` the pack URLs point at a host that may differ from the one
serving `version.json`, which is convenient when the manifest is small and the
packs sit on a CDN:

```
python tools/android/pack_data.py --source ~/Data-world --out ~/dist --version 1 \
  --base-url https://cdn.example.com/wowee/android
```

Requirements:

- HTTPS. The installer rejects HTTP unless a debug build sets
  `debug.wowee.allow_http=1`.
- Public read access. `DownloadManager` does not send app credentials.
- Range requests are recommended so an interrupted download resumes instead of
  starting over.
- Keep old `packs/*.zip` files available for at least one release while clients
  finish updating.

Any static host works: nginx, Apache, S3/R2, Cloudflare, GitHub Pages, or a
small VPS. GitHub Releases do not preserve a directory layout; use
`--url-template` for that:

```
python tools/android/pack_data.py --source ~/Data-login --out ~/dist --version 1 \
  --url-template 'https://github.com/USER/REPO/releases/download/data-v{version}/{file}'
gh release create data-v1 dist/packs/*.zip
gh release upload data-v1 dist/version.json
```

Then point the APK at the `version.json` download URL itself:

```
-Pwowee.dataUrl=https://github.com/USER/REPO/releases/download/data-v1/version.json
```

Keep the data release separate from the APK release, and keep the old data
releases so clients can finish updates. GitHub allows 2 GB per release asset, so
the default 1.5 GB pack limit fits; a 12 GB dataset still needs several assets
and enough release storage.

## 4. Point the APK at the manifest

`BuildConfig.DATA_BASE_URL` is compiled into the APK. Set it at build time:

```
WOWEE_DATA_URL=https://cdn.example.com/wowee/android ./gradlew :app:assembleRelease
```

or:

```
./gradlew :app:assembleRelease -Pwowee.dataUrl=https://cdn.example.com/wowee/android
```

The URL can name the directory or the `version.json` file itself. On GitHub
Actions, set the repository variable `WOWEE_DATA_URL`; both `android-apk.yml`
and `release.yml` pass it to Gradle. If it is left at the placeholder
`https://SEU-SERVIDOR-AQUI/wowee/android`, the app shows a configuration error
instead of trying to download.

For local testing, a debuggable build can override the URL without rebuilding:

```
adb shell setprop debug.wowee.dataurl http://192.168.1.10:8000
adb shell setprop debug.wowee.allow_http 1
```

The override is read through `DataClient.systemProperty`, so it needs a build
where the shell can set `debug.*` properties. Clear it with
`adb shell setprop debug.wowee.dataurl ""`.

## 5. Publish an update

Repack the changed tree and increment the data version:

```
python tools/android/pack_data.py --source ~/Data-login --out ~/dist --bump
```

Upload the new `version.json` and the new ZIPs. Pack names include the data
version, so every upload has a fresh URL. The app compares the SHA-256 of each
pack with the installed copy and downloads only packs whose bytes changed;
unchanged packs are skipped even though their URL changed.

The check runs when the app is opened and at most once every 15 minutes. A
required update is offered before the game starts; a non-required update can be
postponed.

Files removed from a pack are not deleted from the installed tree. Pack contents
are overwritten on update, but the installer keeps no per-file manifest. If a
release removes assets, publish it as a fresh install or clear the app's data.

## 6. What the app does with the manifest

`version.json` has this shape:

```json
{
  "schema": 1,
  "version": 3,
  "generated": "2026-10-23T12:00:00Z",
  "totalDownloadSize": 812345678,
  "totalExtractedSize": 1234567890,
  "packs": [
    {
      "id": "core",
      "version": 3,
      "url": "packs/core-v3.zip",
      "size": 123456,
      "extractedSize": 234567,
      "fileCount": 42,
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
  ],
  "client": {
    "minVersion": "0.2.0",
    "url": "https://example.com/wowee/app.apk",
    "required": true
  }
}
```

- `schema` must be `1`.
- `version` is a positive integer and only moves forward.
- Pack `id` matches `[A-Za-z0-9._-]{1,64}` and must be unique.
- `sha256` is 64 lowercase hex characters.
- `url` may be relative to the manifest URL or absolute HTTPS.
- `size` and `extractedSize` are bytes; the app checks free space with a
  512 MB margin before downloading.
- `client` is optional. `minVersion` is compared numerically against the APK's
  `versionName`; `required` decides whether the player can continue.

Packs are installed in manifest order. The app downloads with
`DownloadManager`, then verifies the SHA-256, then extracts with ZIP path
validation (no absolute paths, no `..`, no escaping the data root). The next
pack starts only after the previous one is installed. Interrupted downloads are
resumed from `DownloadManager` state and the cached manifest.

Data lives at:

```
/sdcard/Android/data/com.wowee.client/files/Data/
```

Downloads are staged beside it in `.../files/downloads/` and removed after
extraction. The game's existing `Data/` location is unchanged, so a tree pushed
over USB still works and is picked up without downloading.

## 7. Testing

```
python tools/android/test_pack_data.py
```

The test builds a small tree, checks pack coverage and exclusions, verifies the
hashes, and confirms that two runs over the same input produce identical packs.
It also runs as the `android_data_packs` ctest.

## 8. Legal

The packs contain Blizzard assets. Do not publish them on a public host or
attach them to a release unless you have the rights to distribute them. The
upstream project deliberately ships no game data; this tooling only packages
data the operator already has.
