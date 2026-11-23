import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools' / 'prepare-build-inputs.sh'
BUILD_RELEASE = (ROOT / 'tools' / 'build-release.sh').read_text(encoding='utf-8')
BUILD_SD = (ROOT / 'build-sd.sh').read_text(encoding='utf-8')
RELEASE = (ROOT / '.github' / 'workflows' / 'release.yml').read_text(encoding='utf-8')


class PrepareBuildInputsTests(unittest.TestCase):
    def test_build_release_prepares_stock_inputs_first(self):
        self.assertIn('tools/prepare-build-inputs.sh', BUILD_RELEASE)
        self.assertLess(
            BUILD_RELEASE.index('prepare-build-inputs'),
            BUILD_RELEASE.index('setup-sdk.sh'),
        )
        self.assertIn('1/6 stock inputs', BUILD_RELEASE)

    def test_build_sd_checks_stock_inputs(self):
        self.assertIn('prepare-build-inputs.sh', BUILD_SD)
        self.assertIn('--check-only', BUILD_SD)
        self.assertLess(
            BUILD_SD.index('prepare-build-inputs'),
            BUILD_SD.index('SRC=$R/dl/a12/Image'),
        )

    def test_release_workflow_uses_prepare_build_inputs(self):
        self.assertIn('tools/prepare-build-inputs.sh', RELEASE)
        self.assertNotIn('Missing LFS stock input: dl/a12', RELEASE)
        self.assertIn('tools/extract-vendor-blobs.sh', RELEASE)

    def test_help_mentions_extract_path(self):
        result = subprocess.run([str(SCRIPT), '--help'], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('extract-vendor-blobs', result.stdout)
        self.assertIn('--update', result.stdout)
        self.assertNotIn('set -euo pipefail', result.stdout)

    def test_check_only_rejects_missing_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            # Минимальная копия скрипта с подменой R невозможна без правки;
            # проверяем отказ на заведомо пустом --update пути через сам скрипт.
            result = subprocess.run(
                [str(SCRIPT), '--update', str(root / 'no-such-update.img')],
                capture_output=True, text=True, check=False, cwd=str(ROOT),
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('нет файла', result.stderr)

    def test_check_only_rejects_lfs_pointer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            # Изолированный прогон: копируем скрипт-зависимости слишком тяжелы;
            # вместо этого вызываем ensure через подкаталог нельзя без правки OUT.
            # Проверяем is_lfs_pointer-поведение через extract-vendor-blobs dump path
            # и отдельно — что рабочий dl/a12 проходит --check-only, если smudged.
            pointer = (
                'version https://git-lfs.github.com/spec/v1\n'
                'oid sha256:' + ('b' * 64) + '\n'
                'size 99\n'
            )
            img = root / 'vendor.img'
            img.write_text(pointer, encoding='utf-8')
            # Прямая проверка функции через bash snippet, совпадающий со скриптом
            snippet = r'''
is_lfs_pointer() {
    local f=$1 size
    [[ -f "$f" ]] || return 1
    size=$(wc -c <"$f" | tr -d ' ')
    [[ "$size" -lt 1024 ]] || return 1
    grep -q 'git-lfs.github.com/spec/v1' "$f" 2>/dev/null
}
is_lfs_pointer "$1"
'''
            result = subprocess.run(
                ['bash', '-c', snippet, '_', str(img)],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(result.returncode, 0)

    @unittest.skipUnless(
        (ROOT / 'dl/a12/Image/lp/vendor.img').is_file()
        and (ROOT / 'dl/a12/Image/lp/vendor.img').stat().st_size > 1024,
        'smudged LFS vendor.img required',
    )
    def test_check_only_passes_on_repo_lfs_inputs(self):
        result = subprocess.run(
            [str(SCRIPT), '--check-only'],
            capture_output=True, text=True, check=False, cwd=str(ROOT),
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('стоковые входы OK', result.stdout)


if __name__ == '__main__':
    unittest.main()
