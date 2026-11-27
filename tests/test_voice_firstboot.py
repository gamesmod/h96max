"""Run the actual first-boot helper with fake Android commands and private fixture data."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'overlay/vendor/bin/tvbox-voice-setup.sh'
MOCK = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['MOCK_COMMAND_LOG'], 'a') as log:
    log.write(json.dumps([command] + args) + '\\n')
state = Path(os.environ['MOCK_ASSISTANT_FILE'])
if command == 'pm' and args[0] == 'path':
    if os.environ.get('MISSING_PACKAGE') == args[1]: sys.exit(1)
    print('package:/fixture/app.apk')
elif command == 'stat':
    print('10059' if 'dicio' in args[-1] else '10060')
elif command == 'cmd':
    if os.environ.get('FAIL_ROLE'): sys.exit(1)
    state.write_text('org.tvbox.voicebridge/.MainActivity')
elif command == 'settings':
    print(state.read_text() if state.exists() else 'null')
'''


class VoiceFirstbootTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)
        self.bin = self.path / 'bin'
        self.bin.mkdir()
        for name in ['pm', 'am', 'stat', 'chown', 'restorecon', 'setprop', 'appops', 'cmd', 'settings']:
            file = self.bin / name
            file.write_text(MOCK)
            file.chmod(0o755)
        self.assets = self.path / 'assets'
        (self.assets / 'vosk-model/ivector').mkdir(parents=True)
        (self.assets / 'vosk-model/ivector/final.ie').write_bytes(b'fixture model')
        (self.assets / 'settings.pb').write_bytes(b'fixture settings')
        (self.assets / 'vosk-model-url').write_text('https://example.invalid/model.zip')
        self.files = self.path / 'dicio/files'
        self.files.parent.mkdir()
        self.bridge = self.path / 'voicebridge'
        self.bridge.mkdir()
        self.marker = self.path / 'state/voice-setup.done'
        self.log = self.path / 'commands.jsonl'
        self.assistant = self.path / 'assistant'
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'],
                        TVBOX_DICIO_ASSETS=str(self.assets), TVBOX_DICIO_FILES=str(self.files),
                        TVBOX_VOICE_DATA=str(self.bridge), TVBOX_VOICE_STATE=str(self.marker),
                        MOCK_COMMAND_LOG=str(self.log), MOCK_ASSISTANT_FILE=str(self.assistant))

    def run_helper(self, **env):
        return subprocess.run(['sh', str(SCRIPT)], env=dict(self.env, **env),
                              capture_output=True, text=True, timeout=15)

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_initial_setup_seeds_model_and_assigns_background_assistant(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.files / 'datastore/settings.pb').read_bytes(), b'fixture settings')
        self.assertEqual((self.files / 'vosk-model/ivector/final.ie').read_bytes(), b'fixture model')
        self.assertEqual(self.marker.read_text().strip(), '10059:10060')
        self.assertEqual(self.assistant.read_text(), 'org.tvbox.voicebridge/.MainActivity')
        self.assertIn(['pm', 'grant', 'org.tvbox.voicebridge', 'android.permission.RECORD_AUDIO'], self.commands())

    def test_repeated_boot_preserves_preferences_permissions_and_assistant(self):
        self.assertEqual(self.run_helper().returncode, 0)
        preferences = self.files / 'datastore/settings.pb'
        preferences.write_bytes(b'changed by user')
        self.assistant.write_text('user.chosen/.Assistant')
        self.log.write_text('')
        result = self.run_helper()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(preferences.read_bytes(), b'changed by user')
        self.assertEqual(self.assistant.read_text(), 'user.chosen/.Assistant')
        self.assertFalse(any(c[0] in ('am', 'cmd', 'appops') or c[:2] == ['pm', 'grant'] for c in self.commands()))

    def test_failed_role_assignment_retries_without_destroying_app_data(self):
        self.assertNotEqual(self.run_helper(FAIL_ROLE='1').returncode, 0)
        self.assertFalse(self.marker.exists())
        preferences = self.files / 'datastore/settings.pb'
        preferences.write_bytes(b'preserved on retry')
        self.assertEqual(self.run_helper().returncode, 0)
        self.assertEqual(preferences.read_bytes(), b'preserved on retry')

    def test_symlink_data_path_rejected_without_writing_outside(self):
        outside = self.path / 'outside'
        outside.mkdir()
        self.files.symlink_to(outside, target_is_directory=True)
        result = self.run_helper()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(outside.iterdir()), [])
        self.assertFalse(self.marker.exists())

    def test_partial_existing_model_is_not_removed_or_claimed_ready(self):
        partial = self.files / 'vosk-model'
        partial.mkdir(parents=True)
        (partial / 'keep').write_bytes(b'partial user model')
        self.assertNotEqual(self.run_helper().returncode, 0)
        self.assertEqual((partial / 'keep').read_bytes(), b'partial user model')
        self.assertFalse(self.marker.exists())

    def test_missing_package_skips_voice_setup(self):
        self.assertEqual(self.run_helper(MISSING_PACKAGE='org.stypox.dicio').returncode, 0)
        self.assertFalse(self.files.exists())
        self.assertFalse(self.marker.exists())
