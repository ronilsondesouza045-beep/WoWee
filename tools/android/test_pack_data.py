#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import pack_data

MB = 1024 * 1024


class PackDataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        base = Path(self.temporary.name)
        self.root = base / "Data"
        self.out = base / "dist"
        self.root.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, relative, size):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"x" * size)

    def populate(self):
        self.write("manifest.json", 64)
        self.write("version.txt", 16)
        self.write("expansions/wotlk/manifest.json", 64)
        self.write("opcodes/opcodes.json", 64)
        self.write("Sound/music.wav", 600000)
        self.write("terrain/a.bin", 700000)
        self.write("terrain/b.bin", 700000)
        self.write("world/zone.bin", 500000)
        self.write(".nomedia", 8)
        self.write("downloads/old.zip", 8)
        self.write(".cache/ignored.bin", 8)

    def build(self, **kwargs):
        return pack_data.build(self.root, self.out, 3, MB, **kwargs)

    def test_packs_cover_data(self):
        self.populate()
        manifest = self.build()

        self.assertEqual(manifest["schema"], 1)
        self.assertEqual(manifest["version"], 3)
        self.assertEqual(manifest["totalDownloadSize"],
                         sum(pack["size"] for pack in manifest["packs"]))
        self.assertEqual(manifest["totalExtractedSize"],
                         sum(pack["extractedSize"] for pack in manifest["packs"]))
        self.assertEqual([pack["id"] for pack in manifest["packs"]],
                         ["core", "sound", "expansions", "terrain-01", "terrain-02", "world"])

        names = set()
        for pack in manifest["packs"]:
            path = self.out / pack["url"]
            self.assertTrue(path.is_file())
            self.assertEqual(path.stat().st_size, pack["size"])
            digest = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(digest, pack["sha256"])
            self.assertEqual(pack["url"], f"packs/{pack['id']}-v3.zip")
            with zipfile.ZipFile(path) as archive:
                infos = archive.infolist()
                self.assertEqual(len(infos), pack["fileCount"])
                self.assertEqual(sum(info.file_size for info in infos),
                                 pack["extractedSize"])
                for info in infos:
                    self.assertEqual(info.date_time, pack_data.FIXED_TIME)
                    names.add(info.filename)

        self.assertIn("manifest.json", names)
        self.assertIn("version.txt", names)
        self.assertIn("expansions/wotlk/manifest.json", names)
        self.assertIn("opcodes/opcodes.json", names)
        self.assertIn("Sound/music.wav", names)
        self.assertIn("terrain/a.bin", names)
        self.assertIn("terrain/b.bin", names)
        self.assertIn("world/zone.bin", names)
        self.assertNotIn(".nomedia", names)
        self.assertNotIn("downloads/old.zip", names)
        self.assertNotIn(".cache/ignored.bin", names)

    def test_core_does_not_take_expansion_trees(self):
        self.populate()
        manifest = self.build()
        core = next(pack for pack in manifest["packs"] if pack["id"] == "core")
        with zipfile.ZipFile(self.out / core["url"]) as archive:
            names = set(archive.namelist())
        self.assertEqual(names, {"manifest.json", "version.txt", "opcodes/opcodes.json"})

    def test_packs_are_deterministic(self):
        self.populate()
        first = self.build()
        second_out = Path(self.temporary.name) / "dist2"
        second = pack_data.build(self.root, second_out, 3, MB)
        self.assertEqual([(p["id"], p["size"], p["sha256"]) for p in first["packs"]],
                         [(p["id"], p["size"], p["sha256"]) for p in second["packs"]])

    def test_absolute_pack_urls(self):
        self.populate()
        manifest = self.build(base_url="https://cdn.example.com/wowee/android/")
        for pack in manifest["packs"]:
            self.assertEqual(
                pack["url"],
                f"https://cdn.example.com/wowee/android/packs/{pack['id']}-v3.zip")

    def test_url_template(self):
        self.populate()
        manifest = self.build(
            url_template="https://github.com/user/repo/releases/download/data-v{version}/{file}")
        for pack in manifest["packs"]:
            self.assertEqual(
                pack["url"],
                f"https://github.com/user/repo/releases/download/data-v3/{pack['id']}-v3.zip")

    def test_store_does_not_compress(self):
        self.populate()
        manifest = self.build(store=True)
        for pack in manifest["packs"]:
            with zipfile.ZipFile(self.out / pack["url"]) as archive:
                for info in archive.infolist():
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED)

    def test_clean_removes_stale_packs(self):
        self.populate()
        self.build()
        stale = self.out / "packs" / "stale-v1.zip"
        stale.write_bytes(b"stale")
        self.build(clean=True)
        self.assertFalse(stale.exists())
        for pack in json.loads((self.out / "version.json").read_text())["packs"]:
            self.assertTrue((self.out / pack["url"]).is_file())

    def test_bump_and_client(self):
        self.populate()
        self.build(client_min="0.1.0", client_url="https://example.com/app.apk",
                   client_required=True)
        self.assertEqual(pack_data.next_version(self.out), 4)
        manifest = json.loads((self.out / "version.json").read_text())
        self.assertEqual(manifest["client"], {
            "minVersion": "0.1.0",
            "required": True,
            "url": "https://example.com/app.apk",
        })

    def test_dry_run_writes_nothing(self):
        self.populate()
        manifest = self.build(dry_run=True)
        self.assertTrue(manifest["packs"])
        self.assertFalse(self.out.exists())

    def test_requires_manifest(self):
        self.write("world/zone.bin", 10)
        with self.assertRaises(SystemExit):
            self.build()

    def test_rejects_output_inside_source(self):
        self.populate()
        with self.assertRaises(SystemExit):
            pack_data.build(self.root, self.root / "dist", 3, MB)


if __name__ == "__main__":
    unittest.main()
