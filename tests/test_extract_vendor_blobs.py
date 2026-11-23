import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools' / 'extract-vendor-blobs.sh'


class ExtractVendorBlobsTests(unittest.TestCase):
    def test_help_mentions_lineage_wiki_and_rockchip(self):
        result = subprocess.run([str(SCRIPT), '--help'], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('wiki.lineageos.org/extracting_blobs_from_zips_manually', result.stdout)
        self.assertIn('update.img', result.stdout)
        self.assertNotIn('set -euo pipefail', result.stdout)

    def test_requires_source_argument(self):
        result = subprocess.run([str(SCRIPT)], capture_output=True, text=True, check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('--update', result.stderr)

    def test_rejects_missing_source_path(self):
        result = subprocess.run(
            [str(SCRIPT), '--from-images', '/no/such/lp', '--dump', '/tmp/out'],
            capture_output=True, text=True, check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('нет источника', result.stderr)

    @unittest.skipUnless(shutil.which('debugfs') and shutil.which('mkfs.ext4'),
                         'debugfs and mkfs.ext4 required')
    def test_dumps_ext4_image_tree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tree = root / 'tree'
            (tree / 'etc' / 'firmware').mkdir(parents=True)
            (tree / 'build.prop').write_text('ro.vendor.test=1\n', encoding='utf-8')
            (tree / 'etc' / 'firmware' / 'demo.bin').write_bytes(b'VENDOR-BLOB')
            image = root / 'vendor.img'
            with image.open('wb') as output:
                output.truncate(8 * 1024 * 1024)
            subprocess.run(['mkfs.ext4', '-q', '-F', '-d', str(tree), str(image)], check=True)
            lp = root / 'lp'
            lp.mkdir()
            for name in ('system', 'system_ext', 'vendor', 'vendor_dlkm', 'odm', 'odm_dlkm', 'product'):
                # Only vendor is a real ext4; others are empty placeholders skipped by size check
                if name == 'vendor':
                    shutil.copyfile(image, lp / f'{name}.img')
                else:
                    (lp / f'{name}.img').write_bytes(b'')
            dump = root / 'dump'
            result = subprocess.run(
                [str(SCRIPT), '--from-images', str(lp), '--dump', str(dump)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            prop = dump / 'vendor' / 'build.prop'
            blob = dump / 'vendor' / 'etc' / 'firmware' / 'demo.bin'
            self.assertTrue(prop.is_file(), result.stdout)
            self.assertEqual(prop.read_text(encoding='utf-8'), 'ro.vendor.test=1\n')
            self.assertEqual(blob.read_bytes(), b'VENDOR-BLOB')

    def test_detects_lfs_pointer_instead_of_image(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            lp = root / 'lp'
            lp.mkdir()
            pointer = (
                'version https://git-lfs.github.com/spec/v1\n'
                'oid sha256:' + ('a' * 64) + '\n'
                'size 123\n'
            )
            for name in ('system', 'system_ext', 'vendor', 'vendor_dlkm', 'odm', 'odm_dlkm', 'product'):
                (lp / f'{name}.img').write_text(pointer, encoding='utf-8')
            dump = root / 'dump'
            result = subprocess.run(
                [str(SCRIPT), '--from-images', str(lp), '--dump', str(dump)],
                capture_output=True, text=True, check=False,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('Git LFS pointer', result.stderr)


if __name__ == '__main__':
    unittest.main()
