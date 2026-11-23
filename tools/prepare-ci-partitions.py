#!/usr/bin/env python3
"""Copy stock ext4 inputs, prune excluded paths, zero freed blocks, shrink; never edit stock."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import tempfile

PARTITIONS = ('system', 'system_ext', 'vendor', 'vendor_dlkm', 'odm', 'odm_dlkm', 'product')
ROOT = pathlib.Path(__file__).resolve().parent.parent
# 8 MiB headroom after resize2fs -M (same idea as build-sd.sh shrink).
HEADROOM_BLOCKS = 2048
PATH_RE = re.compile(r'/[A-Za-z0-9_./+@-]+')
NAME_RE = re.compile(r'[A-Za-z0-9_.@+-]+')
CAMERA_HAL = re.compile(
    r'\n[ \t]*<hal format="hidl">\s*<name>android\.hardware\.camera\.provider</name>.*?</hal>',
    re.S,
)


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


def remove_tree(image, path, removed_apks, removed_paths):
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
            if not NAME_RE.fullmatch(name):
                raise RuntimeError('Unsupported filename in app directory')
            remove_tree(image, path + '/' + name, removed_apks, removed_paths)
        debug(image, 'rmdir ' + path, True)
    else:
        debug(image, 'rm ' + path, True)
        if path.lower().endswith('.apk'):
            removed_apks.append(path)
    if stat(image, path) is not None:
        raise RuntimeError('Removal verification failed: ' + path)
    removed_paths.append(path)


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def image_size(path):
    return path.stat().st_size


def dump_file(image, path, dest):
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    debug(image, f'dump {path} {dest}')
    if not dest.is_file() or dest.stat().st_size == 0:
        raise RuntimeError('debugfs dump failed: ' + path)


def write_file(image, path, source):
    if stat(image, path) is not None:
        debug(image, 'rm ' + path, True)
    debug(image, f'write {source} {path}', True)
    if stat(image, path) is None:
        raise RuntimeError('debugfs write failed: ' + path)


def strip_camera_vintf(image, removed_paths):
    """Drop camera.provider HAL from vendor VINTF so cleaned images do not declare it."""
    manifest = '/etc/vintf/manifest.xml'
    if stat(image, manifest) is None:
        return
    with tempfile.TemporaryDirectory(prefix='h96max-vintf-') as tmp:
        local = pathlib.Path(tmp) / 'manifest.xml'
        dump_file(image, manifest, local)
        text = local.read_text(encoding='utf-8')
        if 'camera.provider' not in text:
            return
        new_text, count = CAMERA_HAL.subn('', text)
        if count != 1:
            raise RuntimeError(f'camera.provider HAL entries expected 1, got {count}')
        if 'camera.provider' in new_text:
            raise RuntimeError('camera.provider survived VINTF cleanup')
        local.write_text(new_text, encoding='utf-8')
        write_file(image, manifest, local)
        removed_paths.append(manifest + '#camera.provider')


def geometry(dest):
    out, _ = run(['dumpe2fs', '-h', str(dest)])
    block_count = block_size = free_blocks = None
    for line in out.splitlines():
        if line.startswith('Block count:'):
            block_count = int(line.split()[2])
        elif line.startswith('Block size:'):
            block_size = int(line.split()[2])
        elif line.startswith('Free blocks:'):
            free_blocks = int(line.split()[2])
    if not block_count or not block_size or free_blocks is None:
        raise RuntimeError('Cannot read ext4 geometry')
    return block_count, block_size, free_blocks


def apply_headroom(dest, original_bytes):
    """Grow by HEADROOM_BLOCKS but never above the original image size."""
    block_count, block_size, _ = geometry(dest)
    target = block_count + HEADROOM_BLOCKS
    max_blocks = max(block_count, (original_bytes + block_size - 1) // block_size)
    if target > max_blocks:
        target = max_blocks
    if target > block_count:
        run(['resize2fs', str(dest), str(target)])
    with dest.open('r+b') as handle:
        handle.truncate(target * block_size)
    run(['e2fsck', '-fn', str(dest)])
    return {'block_size': block_size, 'block_count': target, 'bytes': target * block_size}


def compact_image(dest, original_bytes):
    """Rebuild ext4 when free space is fragmented and resize2fs -M cannot shrink.

    Rockchip stock images often keep used blocks near the end of the volume, so
    resize2fs -M reports a huge minimum. Mount + rsync into a tight image fixes that.
    """
    if shutil.which('sudo') is None or shutil.which('mkfs.ext4') is None:
        return None
    if subprocess.run(['sudo', '-n', 'true'], capture_output=True).returncode != 0:
        return None
    block_count, block_size, free_blocks = geometry(dest)
    used_blocks = block_count - free_blocks
    if free_blocks <= HEADROOM_BLOCKS * 2:
        return None
    with tempfile.TemporaryDirectory(prefix='h96max-compact-') as tmp:
        tmp_path = pathlib.Path(tmp)
        old_mnt = tmp_path / 'old'
        new_mnt = tmp_path / 'new'
        old_mnt.mkdir()
        new_mnt.mkdir()
        new_img = tmp_path / 'compact.img'
        # used + 64 MiB staging room, never larger than the source file
        staging_bytes = min(original_bytes, (used_blocks * block_size) + (64 * 1024 * 1024))
        staging_bytes = max(staging_bytes, (used_blocks + HEADROOM_BLOCKS) * block_size)
        with new_img.open('wb') as handle:
            handle.truncate(staging_bytes)
        run(['mkfs.ext4', '-q', '-F', '-m', '0', str(new_img)])
        run(['sudo', 'mount', '-o', 'loop,ro', str(dest), str(old_mnt)])
        try:
            run(['sudo', 'mount', '-o', 'loop', str(new_img), str(new_mnt)])
            try:
                run([
                    'sudo', 'rsync', '-aHAX', '--numeric-ids', '--delete',
                    str(old_mnt) + '/', str(new_mnt) + '/',
                ])
                run(['sudo', 'sync'])
            finally:
                run(['sudo', 'umount', str(new_mnt)], allowed=(0, 32))
        finally:
            run(['sudo', 'umount', str(old_mnt)], allowed=(0, 32))
        run(['e2fsck', '-fy', str(new_img)], allowed=(0, 1))
        run(['resize2fs', '-M', str(new_img)])
        shutil.copyfile(new_img, dest)
        dest.chmod(0o600)
    info = apply_headroom(dest, original_bytes)
    info['compacted'] = True
    return info


def shrink_image(dest, original_bytes):
    """zerofree already done; try resize2fs -M, then compact if still too sparse."""
    run(['e2fsck', '-fy', str(dest)], allowed=(0, 1))
    run(['resize2fs', '-M', str(dest)])
    _, _, free_blocks = geometry(dest)
    compacted = False
    if free_blocks > HEADROOM_BLOCKS * 2:
        compact_info = compact_image(dest, original_bytes)
        if compact_info is not None:
            return compact_info
    info = apply_headroom(dest, original_bytes)
    info['compacted'] = compacted
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path, help='must not exist')
    parser.add_argument('--no-shrink', action='store_true',
                        help='skip resize2fs -M (still remove paths and zerofree)')
    args = parser.parse_args()
    policy = ROOT / 'tools/unused-stock-apps.txt'
    paths = {p: [] for p in PARTITIONS}
    for line in policy.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        partition, path = line.split()
        if partition not in paths or not PATH_RE.fullmatch(path) or '..' in path or path == '/':
            raise ValueError('Invalid app removal policy')
        paths[partition].append(path)
    args.destination.mkdir(parents=True, mode=0o700, exist_ok=False)
    report = {
        'policy_sha256': sha(policy),
        'headroom_blocks': 0 if args.no_shrink else HEADROOM_BLOCKS,
        'partitions': {},
    }
    for partition in PARTITIONS:
        source = args.source / (partition + '.img')
        dest = args.destination / source.name
        original_sha = sha(source)
        original_size = image_size(source)
        shutil.copyfile(source, dest)
        dest.chmod(0o600)
        if sha(dest) != original_sha:
            raise RuntimeError('Image copy hash mismatch')
        removed_apks = []
        removed_paths = []
        for path in paths[partition]:
            remove_tree(dest, path, removed_apks, removed_paths)
        if partition == 'vendor':
            strip_camera_vintf(dest, removed_paths)
        # Unlink alone leaves APKs and embedded keys in free blocks.
        run(['e2fsck', '-fy', str(dest)], allowed=(0, 1))
        run(['zerofree', str(dest)])
        run(['e2fsck', '-fn', str(dest)])
        for path in paths[partition]:
            if stat(dest, path) is not None:
                raise RuntimeError('Excluded application survived cleanup')
        shrink_info = None
        if not args.no_shrink:
            shrink_info = shrink_image(dest, original_size)
        if sha(source) != original_sha:
            raise RuntimeError('Original image changed concurrently')
        prepared_size = image_size(dest)
        report['partitions'][partition] = {
            'original_sha256': original_sha,
            'prepared_sha256': sha(dest),
            'original_bytes': original_size,
            'prepared_bytes': prepared_size,
            'saved_bytes': original_size - prepared_size,
            'removed_apks': removed_apks,
            'removed_paths': removed_paths,
            'ext4_check': 'pass',
            'free_blocks_zeroed': True,
            'shrunk': shrink_info,
        }
        saved_mib = (original_size - prepared_size) / (1024 * 1024)
        print(
            f'{partition}: removed {len(removed_apks)} APK / {len(removed_paths)} paths; '
            f'{original_size} -> {prepared_size} bytes ({saved_mib:.1f} MiB saved); PASS',
            flush=True,
        )
    report['total_original_bytes'] = sum(p['original_bytes'] for p in report['partitions'].values())
    report['total_prepared_bytes'] = sum(p['prepared_bytes'] for p in report['partitions'].values())
    report['total_saved_bytes'] = report['total_original_bytes'] - report['total_prepared_bytes']
    (args.destination / 'cleanup-report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
