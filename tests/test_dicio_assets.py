import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('prepare_dicio', ROOT / 'tools/prepare-dicio-assets.py')
DICIO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DICIO)


class DicioAssetsTests(unittest.TestCase):
    def archive(self, directory, extra=None):
        path = Path(directory) / 'model.zip'
        prefix = DICIO.INPUTS['model']['archive_root']
        with zipfile.ZipFile(path, 'w') as output:
            for name in DICIO.REQUIRED_MODEL_FILES:
                output.writestr(prefix + '/' + name, b'fixture model')
            if extra:
                extra(output, prefix)
        return path, DICIO.sha256(path)

    def test_prepares_offline_model_url_and_russian_no_wake_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, digest = self.archive(directory)
            dest = Path(directory) / 'assets'
            DICIO.prepare(archive, dest, digest)
            self.assertEqual((dest / 'settings.pb').read_bytes(), bytes.fromhex('180920022801300140014804'))
            for name in DICIO.REQUIRED_MODEL_FILES:
                self.assertEqual((dest / 'vosk-model' / name).read_bytes(), b'fixture model')
            self.assertEqual((dest / 'vosk-model-url').read_text(), DICIO.INPUTS['model']['url'])
            self.assertEqual(json.loads((dest / 'manifest.json').read_text())['model_sha256'], digest)

    def test_wrong_hash_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, _ = self.archive(directory)
            dest = Path(directory) / 'assets'
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                DICIO.prepare(archive, dest)
            self.assertFalse(dest.exists())

    def test_existing_destination_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            archive, digest = self.archive(directory)
            dest = Path(directory) / 'assets'
            dest.mkdir()
            marker = dest / 'user-file'
            marker.write_bytes(b'preserve')
            with self.assertRaises(FileExistsError):
                DICIO.prepare(archive, dest, digest)
            self.assertEqual(marker.read_bytes(), b'preserve')

    def test_path_traversal_and_symlink_archives_rejected(self):
        def symlink(output, prefix):
            entry = zipfile.ZipInfo(prefix + '/link')
            entry.external_attr = (stat.S_IFLNK | 0o777) << 16
            output.writestr(entry, '/outside')
        cases = [lambda z, p: z.writestr(p + '/../outside', b'bad'), symlink]
        for extra in cases:
            with self.subTest(extra=extra), tempfile.TemporaryDirectory() as directory:
                archive, digest = self.archive(directory, extra)
                dest = Path(directory) / 'assets'
                with self.assertRaisesRegex(ValueError, 'Небезопасный'):
                    DICIO.prepare(archive, dest, digest)
                self.assertFalse(dest.exists())
                self.assertFalse((Path(directory) / 'outside').exists())

    def test_fetch_is_pinned_and_preserves_existing_files(self):
        content = b'pinned public fixture'
        expected = dict(path='dl/apk/fixture.apk', url='https://example.invalid/fixture',
                        size_bytes=len(content), sha256=hashlib.sha256(content).hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            opener = mock.Mock()
            opener.open.return_value = io.BytesIO(content)
            with mock.patch.object(DICIO.urllib.request, 'build_opener', return_value=opener):
                DICIO.fetch_input(expected, directory)
            target = Path(directory) / expected['path']
            self.assertEqual(target.read_bytes(), content)
            with mock.patch.object(DICIO.urllib.request, 'build_opener') as network:
                DICIO.fetch_input(expected, directory)
                network.assert_not_called()
            target.write_bytes(b'local user copy')
            with self.assertRaises(ValueError):
                DICIO.fetch_input(expected, directory)
            self.assertEqual(target.read_bytes(), b'local user copy')

    def test_wrong_download_does_not_publish_partial_file(self):
        expected = dict(path='dl/apk/fixture.apk', url='https://example.invalid/fixture',
                        size_bytes=4, sha256=hashlib.sha256(b'good').hexdigest())
        with tempfile.TemporaryDirectory() as directory:
            opener = mock.Mock()
            opener.open.return_value = io.BytesIO(b'bad!')
            with mock.patch.object(DICIO.urllib.request, 'build_opener', return_value=opener):
                with self.assertRaises(ValueError):
                    DICIO.fetch_input(expected, directory)
            self.assertFalse((Path(directory) / expected['path']).exists())
