"""Exercise private OTA handoff without real credentials or network changes."""
import os
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'overlay/vendor/bin/tvbox-ota-connectivity.sh'


class OtaConnectivityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.calls = self.root / 'calls'
        self.env = dict(os.environ, PATH=str(self.bin) + ':' + os.environ['PATH'],
                        TEST_CALLS=str(self.calls), TEST_STATE=str(self.root), TEST_FAIL6='0')
        self.cache = self.root / 'cache/recovery/tvbox-connectivity'
        self.wifi = self.root / 'data/misc/apexdata/com.android.wifi/WifiConfigStore.xml'
        self.keys = self.root / 'data/misc/adb/adb_keys'
        self.policy = self.root / 'data/vendor/tvbox/adb-trusted-host'
        self.wifi.parent.mkdir(parents=True)
        self.keys.parent.mkdir(parents=True)
        self.wifi.write_text('<WifiConfigStoreData><NetworkList><Network><string name="PreSharedKey">fixture-password</string></Network></NetworkList></WifiConfigStoreData>')
        self.keys.write_text('A' * 700 + ' fixture-host\n')
        self.peer = self.root / 'peer'
        self.peer.write_text('192.168.77.2\n')
        self.sha = 'a' * 64
        self.fake('id', '#!/bin/sh\necho 0\n')
        real_stat = subprocess.run(['which', 'stat'], capture_output=True, text=True, check=True).stdout.strip()
        self.fake('stat', '#!/bin/sh\nif [ "$2" = %u ]; then echo 0; else exec ' + real_stat + ' "$@"; fi\n')
        for tool in ['chown', 'restorecon', 'sync']:
            self.fake(tool, '#!/bin/sh\nexit 0\n')
        self.fake('getprop', '''#!/bin/sh
case "$1" in ro.tvbox.release) echo 0.4.6;; ro.adb.secure) echo 1;; service.adb.tcp.port) echo "${TEST_TCP_PORT-5555}";; esac
''')
        for tool in ['cmd', 'setprop']:
            self.fake(tool, '#!/bin/sh\necho "' + tool + ' $*" >> "$TEST_CALLS"\n')
        self.fake('iptables', '''#!/bin/sh
echo "iptables $*" >> "$TEST_CALLS"
case "$1" in
 -nL) [ -e "$TEST_STATE/chain4" ];;
 -N) touch "$TEST_STATE/chain4";;
 -S) printf '%s\n' '-N TVBOX_OTA_ADB4' '-A TVBOX_OTA_ADB4 -s 192.168.77.2/32 -j ACCEPT' '-A TVBOX_OTA_ADB4 -j DROP';;
esac
''')
        self.fake('ip6tables', '''#!/bin/sh
echo "ip6tables $*" >> "$TEST_CALLS"
[ "$TEST_FAIL6" = 0 ] || exit 1
case "$1" in
 -nL) [ -e "$TEST_STATE/chain6" ];;
 -N) touch "$TEST_STATE/chain6";;
 -S) printf '%s\n' '-N TVBOX_OTA_ADB6' '-A TVBOX_OTA_ADB6 -j DROP';;
esac
''')
        text = SCRIPT.read_text().replace('/cache/recovery', str(self.root / 'cache/recovery'))
        text = text.replace('/data/misc', str(self.root / 'data/misc'))
        text = text.replace('/data/vendor', str(self.root / 'data/vendor'))
        self.script = self.root / 'handoff.sh'
        self.script.write_text(text)

    def fake(self, name, text):
        p = self.bin / name
        p.write_text(text)
        p.chmod(0o755)

    def run_script(self, *args):
        return subprocess.run(['sh', str(self.script), *args], env=self.env, capture_output=True, text=True)

    def prepare(self, peer=True):
        args = ['prepare', '0.4.6', self.sha] + ([str(self.peer)] if peer else [])
        result = self.run_script(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn('fixture-password', result.stdout + result.stderr)
        self.assertNotIn(self.peer.read_text().strip(), result.stdout + result.stderr)
        return result

    def fresh_data(self):
        self.wifi.unlink()
        self.keys.unlink()
        result = self.run_script('data')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_roundtrip_keeps_credentials_private_and_filters_both_families_before_enable(self):
        wifi, keys = self.wifi.read_bytes(), self.keys.read_bytes()
        self.prepare()
        self.assertEqual(self.cache.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.cache / 'wifi.xml').stat().st_mode & 0o777, 0o600)
        self.fresh_data()
        self.assertEqual(self.wifi.read_bytes(), wifi)
        self.assertEqual(self.keys.read_bytes(), keys)
        self.env['TEST_TCP_PORT'] = ''
        result = self.run_script('network')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls.read_text()
        self.assertIn('cmd wifi set-wifi-enabled enabled', calls)
        port = calls.index('setprop service.adb.tcp.port')
        self.assertLess(calls.index('iptables -I INPUT 1'), port)
        self.assertLess(calls.index('ip6tables -I INPUT 1'), port)
        self.assertIn('setprop ctl.restart adbd', calls)
        self.assertTrue(self.cache.exists())
        self.assertEqual(self.run_script('acknowledge').returncode, 0)
        self.assertFalse(self.cache.exists())
        self.assertTrue(self.policy.exists())

    def test_default_handoff_never_enables_network_adb(self):
        self.prepare(peer=False)
        self.fresh_data()
        result = self.run_script('network')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls.read_text() if self.calls.exists() else ''
        self.assertNotIn('iptables', calls)
        self.assertNotIn('setprop service.adb.tcp.port', calls)

    def test_encrypted_credentials_fail_before_handoff_creation(self):
        self.wifi.write_text('<WifiConfigStoreData><Network><EncryptedData>fixture</EncryptedData></Network></WifiConfigStoreData>')
        result = self.run_script('prepare', '0.4.6', self.sha, str(self.peer))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.cache.exists())

    def test_corrupt_backup_never_overwrites_fresh_userdata(self):
        self.prepare()
        (self.cache / 'wifi.xml').write_text('corruption')
        self.wifi.unlink()
        self.keys.unlink()
        self.assertNotEqual(self.run_script('data').returncode, 0)
        self.assertFalse(self.wifi.exists())
        self.assertFalse(self.keys.exists())

    def test_configured_userdata_is_never_overwritten(self):
        self.prepare()
        self.wifi.write_text('current user configuration')
        self.assertEqual(self.run_script('data').returncode, 0)
        self.assertEqual(self.wifi.read_text(), 'current user configuration')
        self.assertFalse(self.policy.exists())

    def interrupt_key_restore(self):
        self.prepare(peer=False)
        self.wifi.unlink()
        self.keys.unlink()
        self.fake('cp', '#!/bin/sh\ncase "$2" in */adb_keys.tvbox-restore) exit 9;; esac\nexec /bin/cp "$@"\n')
        result = self.run_script('data')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.wifi.exists())
        self.assertFalse(self.keys.exists())
        (self.bin / 'cp').unlink()

    def test_interrupted_restore_resumes_missing_adb_keys(self):
        self.interrupt_key_restore()
        state = self.root / 'data/vendor/tvbox/ota-connectivity'
        self.assertEqual((state / 'restore-in-progress').read_text().strip(), self.sha)
        self.assertEqual(self.run_script('data').returncode, 0)
        self.assertEqual(self.keys.read_bytes(), (self.cache / 'adb_keys').read_bytes())
        self.assertTrue((state / 'data-restored').exists())
        self.assertFalse((state / 'restore-in-progress').exists())

    def test_retry_never_overwrites_credentials_changed_after_interruption(self):
        self.interrupt_key_restore()
        self.wifi.write_text('new user configuration after interruption')
        self.assertNotEqual(self.run_script('data').returncode, 0)
        self.assertEqual(self.wifi.read_text(), 'new user configuration after interruption')
        self.assertFalse(self.keys.exists())

    def test_retry_rejects_wrong_handoff_or_public_transaction_marker(self):
        self.interrupt_key_restore()
        marker = self.root / 'data/vendor/tvbox/ota-connectivity/restore-in-progress'
        marker.write_text('b' * 64 + '\n')
        self.assertNotEqual(self.run_script('data').returncode, 0)
        marker.write_text(self.sha + '\n')
        marker.chmod(0o644)
        self.assertNotEqual(self.run_script('data').returncode, 0)
        self.assertFalse(self.keys.exists())

    def test_ipv6_firewall_failure_never_enables_or_restarts_adb(self):
        self.prepare()
        self.fresh_data()
        self.env['TEST_FAIL6'] = '1'
        self.assertNotEqual(self.run_script('network').returncode, 0)
        calls = self.calls.read_text()
        self.assertNotIn('setprop service.adb.tcp.port', calls)
        self.assertNotIn('setprop ctl.restart', calls)

    def test_replacement_handoff_and_early_ack_are_rejected(self):
        self.prepare()
        self.assertNotEqual(self.run_script('prepare', '0.4.6', 'b' * 64).returncode, 0)
        self.assertNotEqual(self.run_script('acknowledge').returncode, 0)
        self.assertTrue(self.cache.exists())

    def test_wrong_version_leaves_backup_and_userdata_untouched(self):
        self.prepare()
        self.wifi.unlink()
        self.keys.unlink()
        self.fake('getprop', '#!/bin/sh\necho 0.4.5\n')
        self.assertEqual(self.run_script('data').returncode, 0)
        self.assertFalse(self.wifi.exists())
        self.assertTrue(self.cache.exists())

    def test_unsafe_peer_address_is_rejected_without_output(self):
        self.peer.write_text('127.0.0.1; unexpected-command\n')
        result = self.run_script('prepare', '0.4.6', self.sha, str(self.peer))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.cache.exists())
        self.assertNotIn('unexpected-command', result.stdout + result.stderr)

    def test_existing_unknown_firewall_rule_prevents_network_enable(self):
        self.prepare()
        self.fresh_data()
        self.fake('iptables', "#!/bin/sh\necho 'iptables called' >> \"$TEST_CALLS\"\ncase \"$1\" in -nL) exit 0;; -S) printf '%s\\n' '-N TVBOX_OTA_ADB4' '-A TVBOX_OTA_ADB4 -j ACCEPT';; esac\n".replace('\\"','"'))
        result = self.run_script('network')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('setprop service.adb.tcp.port', self.calls.read_text())

    def test_symlinked_backup_is_rejected(self):
        self.prepare()
        target = self.cache / 'wifi.xml'
        target.unlink()
        target.symlink_to(self.wifi)
        self.keys.unlink()
        self.assertNotEqual(self.run_script('data').returncode, 0)
        self.assertFalse(self.keys.exists())

    def test_recovery_is_not_armed_before_private_backup_succeeds(self):
        source = (ROOT / 'apps/tvboxd/src/org/tvbox/daemon/Main.java').read_text()
        start = source.index('static void updateInstall(')
        method = source[start:source.index('static ', start + 15)]
        self.assertLess(method.index('Process connectivity'), method.index('File wipeReq'))
        self.assertIn('new ProcessBuilder', method)
        init = (ROOT / 'overlay/vendor/etc/init/tvbox.rc').read_text()
        self.assertLess(init.index('tvbox-ota-connectivity.sh data'), init.index('tvbox-restore-wifi.sh'))

    def test_usb_only_system_without_wifi_profile_can_preserve_adb(self):
        self.wifi.unlink()
        self.fake('cmd', '#!/bin/sh\necho "Wifi is disabled"\n')
        self.prepare(peer=False)
        self.assertEqual((self.cache / 'wifi.xml').stat().st_size, 0)
        self.keys.unlink()
        result = self.run_script('data')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(self.keys.exists())
        self.assertFalse(self.wifi.exists())
        self.assertFalse((self.root / 'data/vendor/tvbox/ota-connectivity/wifi-restored').exists())
