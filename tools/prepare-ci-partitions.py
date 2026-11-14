#!/usr/bin/env python3
"""Copy stock ext4 inputs, prune excluded apps and zero freed blocks; never edit stock."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess

PARTITIONS = ('system', 'system_ext', 'vendor', 'vendor_dlkm', 'odm', 'odm_dlkm', 'product')
ROOT = pathlib.Path(__file__).resolve().parent.parent


def run(args, allowed=(0,)):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode not in allowed:
        raise RuntimeError(f'{args[0]} failed (exit {result.returncode}); input not approved')
    return result.stdout, result.stderr


def debug(image, command, write=False):
    out, err = run(['debugfs', *(['-w'] if write else []), '-R', command, str(image)])
    if any(s in err for s in ('Filesystem not open', 'Command not found', 'Permission denied', 'read-only')):
        raise RuntimeError('debugfs failed; input not approved')
    return out, err


def stat(image, path):
    out, err = debug(image, 'stat ' + path)
    if 'File not found by ext2_lookup' in err:
        return None
    match = re.search(r'Type:\s+(\w+)', out)
    if not match:
        raise RuntimeError('Cannot inspect filesystem path: ' + path)
    return match[1]


def remove_tree(image, path, removed):
    kind = stat(image, path)
    if kind is None:
        return
    if kind == 'directory':
        out, _ = debug(image, 'ls -p ' + path)
        for line in out.splitlines():
            if not line.strip():
                continue
            fields = line.split('/')
            if len(fields) < 7 or not fields[1].isdigit():
                raise RuntimeError('Cannot parse directory listing')
            name = fields[5]
            if name in ('.', '..', ''):
                continue
            if not re.fullmatch(r'[A-Za-z0-9_.@+-]+', name):
                raise RuntimeError('Unsupported filename in app directory')
            remove_tree(image, path + '/' + name, removed)
        debug(image, 'rmdir ' + path, True)
    else:
        debug(image, 'rm ' + path, True)
        if path.lower().endswith('.apk'):
            removed.append(path)
    if stat(image, path) is not None:
        raise RuntimeError('Removal verification failed: ' + path)


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path, help='must not exist')
    args = parser.parse_args()
    policy = ROOT / 'tools/unused-stock-apps.txt'
    paths = {p: [] for p in PARTITIONS}
    for line in policy.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        partition, path = line.split()
        if partition not in paths or not re.fullmatch(r'/[A-Za-z0-9_./+-]+', path) or '..' in path or path == '/':
            raise ValueError('Invalid app removal policy')
        paths[partition].append(path)
    args.destination.mkdir(parents=True, mode=0o700, exist_ok=False)
    report = {'policy_sha256': sha(policy), 'partitions': {}}
    for partition in PARTITIONS:
        source = args.source / (partition + '.img')
        dest = args.destination / source.name
        original_sha = sha(source)
        shutil.copyfile(source, dest)
        dest.chmod(0o600)
        if sha(dest) != original_sha:
            raise RuntimeError('Image copy hash mismatch')
        removed = []
        for path in paths[partition]:
            remove_tree(dest, path, removed)
        # Unlink alone leaves APKs and embedded keys in free blocks.
        run(['e2fsck', '-fy', str(dest)], allowed=(0, 1))
        run(['zerofree', str(dest)])
        run(['e2fsck', '-fn', str(dest)])
        for path in paths[partition]:
            if stat(dest, path) is not None:
                raise RuntimeError('Excluded application survived cleanup')
        if sha(source) != original_sha:
            raise RuntimeError('Original image changed concurrently')
        report['partitions'][partition] = {
            'original_sha256': original_sha, 'prepared_sha256': sha(dest),
            'removed_apks': removed, 'ext4_check': 'pass', 'free_blocks_zeroed': True,
        }
        print(f'{partition}: removed {len(removed)} APK; ext4 and original hash PASS', flush=True)
    (args.destination / 'cleanup-report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
