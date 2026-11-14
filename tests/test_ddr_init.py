import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('patch_ddr_init', ROOT / 'tools/patch-ddr-init.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DdrInitTests(unittest.TestCase):
    def test_boost_and_recover_use_real_attribute_and_preserve_other_actions(self):
        text = ('on early-init\n    write ' + module.OLD + ' p\n'
                '    write /unrelated/node 1\n'
                'on property:sys.boot_completed=1\n    write ' + module.OLD + ' n\n')
        result = module.patch(text)
        self.assertEqual(result, text.replace(module.OLD, module.NEW))
        self.assertEqual(module.patch(result), result)

    def test_changed_or_duplicate_vendor_actions_fail_closed(self):
        for text in ('', 'write ' + module.OLD + ' p\n',
                     'write ' + module.OLD + ' p\nwrite ' + module.OLD + ' p\nwrite ' + module.OLD + ' n\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                module.patch(text)
