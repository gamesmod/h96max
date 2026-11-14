import hashlib
import json
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


@unittest.skipUnless(all(shutil.which(x) for x in ('mkfs.ext4', 'debugfs', 'e2fsck', 'zerofree')),
                     'ext4 tools required')
class CiPartitionCleanupTests(unittest.TestCase):
    def test_removes_excluded_apks_zeroes_data_and_preserves_stock_and_framework(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tree = root / 'tree'
            for name in ('system/preinstall', 'system/framework', 'app/YouTube'):
                (tree / name).mkdir(parents=True)
            marker = b'UNUSED-APK-SECRET-MARKER-' * 128
            (tree / 'system/preinstall/Unused.apk').write_bytes(marker)
            (tree / 'app/YouTube/YouTube.apk').write_bytes(b'YOUTUBE-REMOVAL-MARKER-' * 128)
            retained = b'framework required by the build'
            (tree / 'system/framework/framework-res.apk').write_bytes(retained)
            template = root / 'template.img'
            with template.open('wb') as output:
                output.truncate(16 * 1024 * 1024)
            subprocess.run(['mkfs.ext4', '-q', '-F', '-d', str(tree), str(template)], check=True)
            source = root / 'stock'
            source.mkdir()
            partitions = ('system', 'system_ext', 'vendor', 'vendor_dlkm', 'odm', 'odm_dlkm', 'product')
            for partition in partitions:
                shutil.copyfile(template, source / (partition + '.img'))
            original_hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source.iterdir()}
            destination = root / 'prepared'
            command = ['python3', '-I', str(ROOT / 'tools/prepare-ci-partitions.py'), str(source), str(destination)]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads((destination / 'cleanup-report.json').read_text())
            self.assertIn('/system/preinstall/Unused.apk', report['partitions']['system']['removed_apks'])
            self.assertNotIn(marker, (destination / 'system.img').read_bytes())
            self.assertNotIn(b'YOUTUBE-REMOVAL-MARKER-' * 128, (destination / 'product.img').read_bytes())
            out = subprocess.run(['debugfs', '-R', 'cat /system/framework/framework-res.apk',
                                  str(destination / 'system.img')], capture_output=True, check=True)
            self.assertEqual(out.stdout, retained)
            # The unrelated copy in /app/YouTube is retained in system; product removes it.
            for partition, path in [('system', '/system/preinstall/Unused.apk'), ('product', '/app/YouTube/YouTube.apk')]:
                out = subprocess.run(['debugfs', '-R', 'stat ' + path, str(destination / (partition + '.img'))], capture_output=True)
                self.assertIn(b'File not found', out.stderr)
            self.assertEqual(original_hashes, {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in source.iterdir()})
            # Existing destination must be rejected without touching any prepared image.
            before = (destination / 'system.img').read_bytes()
            result = subprocess.run(command, capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(before, (destination / 'system.img').read_bytes())
