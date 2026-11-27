#!/usr/bin/env python3
"""Fetch pinned public Dicio inputs and prepare offline first-boot assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
INPUTS = json.loads(Path(__file__).with_name('dicio-inputs.json').read_text())
# Dicio 4.2 UserSettings: Russian, Vosk, no speech output, auto-finish,
# no wake word, no STT sounds. Do not overwrite an existing user's settings.
SETTINGS = bytes.fromhex('180920022801300140014804')
REQUIRED_MODEL_FILES = ('am/final.mdl', 'conf/model.conf', 'graph/Gr.fst', 'ivector/final.ie')


def sha256(path):
    with Path(path).open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def verify_input(path, expected):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError(f'Нет обычного входного файла: {path}; выполните --fetch')
    if path.stat().st_size != expected['size_bytes'] or sha256(path) != expected['sha256']:
        raise ValueError(f'Не совпадает размер/SHA-256: {path}; существующий файл не заменён')


class HttpsOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlparse(newurl).scheme != 'https':
            raise ValueError('Не разрешено перенаправление загрузки с HTTPS на другой протокол')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch_input(expected, root=ROOT):
    path = Path(root) / expected['path']
    if path.exists() or path.is_symlink():
        verify_input(path, expected)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(HttpsOnlyRedirect())
    with tempfile.NamedTemporaryFile(prefix='.dicio-', dir=path.parent) as temp:
        with opener.open(expected['url'], timeout=60) as response:
            size = 0
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > expected['size_bytes']:
                    raise ValueError('Загрузка больше зафиксированного размера')
                temp.write(chunk)
        temp.flush()
        verify_input(temp.name, expected)
        os.chmod(temp.name, 0o644)
        # Hard link is atomic and refuses to replace a concurrently created file or symlink.
        os.link(temp.name, path)


def prepare(archive, destination, expected_sha=None):
    expected_sha = expected_sha or INPUTS['model']['sha256']
    archive, destination = Path(archive), Path(destination)
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(f'Каталог уже существует: {destination}')
    if archive.is_symlink() or not archive.is_file() or sha256(archive) != expected_sha:
        raise ValueError('Не совпадает SHA-256 модели Vosk')
    prefix = INPUTS['model']['archive_root']
    with zipfile.ZipFile(archive) as source:
        members = source.infolist()
        if len(members) > 10000 or sum(item.file_size for item in members) > 256 * 1024 * 1024:
            raise ValueError('Слишком большой распакованный архив модели')
        paths = []
        for item in members:
            path = PurePosixPath(item.filename)
            kind = stat.S_IFMT(item.external_attr >> 16)
            if (path.is_absolute() or '..' in path.parts or not path.parts
                    or path.parts[0] != prefix or kind not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError('Небезопасный путь или специальный файл в модели')
            relative = Path(*path.parts[1:])
            if len(path.parts) > 1:
                paths.append((item, relative))
        available = {str(path) for item, path in paths if not item.is_dir()}
        if not set(REQUIRED_MODEL_FILES).issubset(available):
            raise ValueError('Неполная модель Vosk')
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.dicio-assets-', dir=destination.parent) as directory:
            staged = Path(directory) / 'assets'
            model = staged / 'vosk-model'
            model.mkdir(parents=True)
            for item, relative in paths:
                target = model / relative
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                else:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with source.open(item) as src, target.open('xb') as dst:
                        shutil.copyfileobj(src, dst)
            (staged / 'settings.pb').write_bytes(SETTINGS)
            (staged / 'vosk-model-url').write_text(INPUTS['model']['url'])
            (staged / 'manifest.json').write_text(json.dumps({
                'dicio_version': INPUTS['apk']['version'], 'model_sha256': expected_sha,
                'settings_sha256': hashlib.sha256(SETTINGS).hexdigest(),
                'expanded_bytes': sum(item.file_size for item in members),
            }, indent=2) + '\n')
            # Reserve the destination; copy only after the complete ZIP/CRC validation above.
            destination.mkdir()
            try:
                shutil.copytree(staged, destination, dirs_exist_ok=True)
            except Exception:
                shutil.rmtree(destination)
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_mutually_exclusive_group(required=True)
    actions.add_argument('--fetch', action='store_true')
    actions.add_argument('--check', action='store_true')
    actions.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.fetch:
        for expected in INPUTS.values():
            fetch_input(expected)
    for expected in INPUTS.values():
        verify_input(ROOT / expected['path'], expected)
    if args.output:
        prepare(ROOT / INPUTS['model']['path'], args.output)
    print('Dicio 4.2 и русская модель: размеры и SHA-256 совпали')


if __name__ == '__main__':
    main()
