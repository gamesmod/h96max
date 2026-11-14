import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('patch_ddr_init', ROOT / 'tools/patch-ddr-init.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DdrInitTests(unittest.TestCase):
    def vendor_init(self):
        return ('on early-init\n    write ' + module.OLD + ' p\n'
                '    write /unrelated/node 1\n'
                'on property:sys.boot_completed=1\n    write ' + module.OLD + ' n\n'
                'on init\n    chown system system ' + module.OLD_PERMISSIONS + '\n'
                '    chmod 0660 ' + module.OLD_PERMISSIONS + '\n'
                'on init\n    chown mediacodec system ' + module.OLD_PERMISSIONS + '\n'
                '    chmod 0660 ' + module.OLD_PERMISSIONS + '\n')

    def test_boost_and_recover_use_real_attribute_and_preserve_other_actions(self):
        text = self.vendor_init()
        result = module.patch(text)
        self.assertEqual(result, text.replace(module.OLD, module.NEW).replace(module.OLD_PERMISSIONS, module.NEW))
        self.assertEqual(module.patch(result), result)

    def test_changed_or_duplicate_vendor_actions_fail_closed(self):
        for text in ('', 'write ' + module.OLD + ' p\n',
                     'write ' + module.OLD + ' p\nwrite ' + module.OLD + ' p\nwrite ' + module.OLD + ' n\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                module.patch(text)

    def test_cannot_leave_attribute_world_writable_or_drop_permissions(self):
        text = self.vendor_init()
        variants = [text.replace('chmod 0660', 'chmod 0666', 1),
                    text.replace('chown mediacodec system', 'chown root root'),
                    text + '    chmod 0666 ' + module.NEW + '\n']
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                module.patch(variant)

    def test_cli_does_not_modify_file_if_vendor_permissions_are_unexpected(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / 'init.rc'
            original = self.vendor_init().replace('chmod 0660', 'chmod 0666', 1)
            path.write_text(original)
            result = subprocess.run([sys.executable, str(ROOT / 'tools/patch-ddr-init.py'), str(path)],
                                    capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(path.read_text(), original)
