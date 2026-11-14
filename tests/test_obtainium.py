import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('prepare_obtainium', ROOT / 'tools/prepare-obtainium.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ObtainiumTests(unittest.TestCase):
    def test_all_required_apks_have_loadable_records(self):
        with tempfile.TemporaryDirectory() as d:
            module.prepare(ROOT / 'obtainium-apps.json', d)
            records = [json.loads(p.read_text()) for p in Path(d).glob('*.json')]
            for app in records:
                self.assertIsNone(app['lastUpdateCheck'])
                self.assertIsNone(app['installedVersion'])
                self.assertIsInstance(json.loads(app['additionalSettings']), dict)
                self.assertEqual(json.loads(app['apkUrls'])[0][1], 'placeholder')
            tc = [a for a in records if a['id'].startswith('com.ghisler.')]
            self.assertEqual(len(tc), 4)
            for app in tc:
                self.assertEqual(app['overrideSource'], 'FDroidRepo')
                self.assertEqual(json.loads(app['additionalSettings'])['appIdOrName'], app['id'])

    def test_missing_source_blocks_image_preparation(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d) / 'apps.json'
            apps = json.loads((ROOT / 'obtainium-apps.json').read_text())
            source.write_text(json.dumps([a for a in apps if a['id'] != 'com.ghisler.tcplugins.FTP']))
            with self.assertRaisesRegex(ValueError, 'Missing Obtainium sources'):
                module.prepare(source, Path(d) / 'out')
            self.assertFalse((Path(d) / 'out').exists())
