#!/usr/bin/env python3
"""Build a raw (non-sparse) Android dynamic-partition super image, LP metadata v10.0.

  lpbuild.py out.raw SUPER_SIZE_BYTES name=img [name=img ...]

Layout mirrors the A12 vendor super: block device "super", groups "default" +
"rockchip_dynamic_partitions", metadata_max_size 65536, 2 slots, 4 KiB logical
blocks, first data sector 2048 (1 MiB), every partition aligned to 1 MiB,
all partitions read-only (attribute 1) in rockchip_dynamic_partitions.
"""
import hashlib
import os
import struct
import sys

SECTOR = 512
ALIGN = 1 << 20
META_MAX = 65536
SLOTS = 2
GROUP = b"rockchip_dynamic_partitions"


def up(n, a):
    return (n + a - 1) // a * a


def geometry():
    g = bytearray(52)
    struct.pack_into("<II", g, 0, 0x616C4467, 52)
    struct.pack_into("<III", g, 40, META_MAX, SLOTS, 4096)
    g[8:40] = hashlib.sha256(bytes(g)).digest()
    return bytes(g).ljust(4096, b"\0")


def metadata(parts, super_size):
    # parts: list of (name, size_bytes, first_sector)
    ptab = b""
    etab = b""
    for i, (name, size, first) in enumerate(parts):
        ptab += name.encode().ljust(36, b"\0") + struct.pack("<4I", 1, i, 1, 1)
        etab += struct.pack("<QIQI", size // SECTOR, 0, first, 0)
    group_max = up(super_size - ALIGN, ALIGN)  # informational cap
    gtab = b"default".ljust(36, b"\0") + struct.pack("<IQ", 0, 0)
    gtab += GROUP.ljust(36, b"\0") + struct.pack("<IQ", 0, group_max)
    btab = struct.pack("<QIIQ", ALIGN // SECTOR, ALIGN, 0, super_size)
    btab += b"super".ljust(36, b"\0") + struct.pack("<I", 0)
    tables = ptab + etab + gtab + btab
    offs = [0, len(ptab), len(ptab) + len(etab), len(ptab) + len(etab) + len(gtab)]
    hdr = bytearray(128)
    struct.pack_into("<IHHI", hdr, 0, 0x414C5030, 10, 0, 128)
    struct.pack_into("<I", hdr, 44, len(tables))
    hdr[48:80] = hashlib.sha256(tables).digest()
    struct.pack_into("<12I", hdr, 80,
                     offs[0], len(parts), 52,
                     offs[1], len(parts), 24,
                     offs[2], 2, 48,
                     offs[3], 1, 64)
    hdr[12:44] = hashlib.sha256(bytes(hdr)).digest()
    blob = bytes(hdr) + tables
    assert len(blob) <= META_MAX
    return blob.ljust(META_MAX, b"\0")


def main():
    out = sys.argv[1]
    super_size = int(sys.argv[2], 0)
    items = [a.split("=", 1) for a in sys.argv[3:]]
    parts = []
    cur = ALIGN
    for name, img in items:
        size = os.path.getsize(img)
        assert size % 4096 == 0, f"{img} not 4K aligned"
        parts.append((name, size, cur // SECTOR))
        cur = up(cur + size, ALIGN)
    assert cur <= super_size, f"super too small: need {cur}, have {super_size}"
    geo = geometry()
    meta = metadata(parts, super_size)
    with open(out, "wb") as f:
        f.truncate(super_size)
        f.seek(4096); f.write(geo)
        f.seek(8192); f.write(geo)
        base = 12288
        for s in range(SLOTS):
            f.seek(base + s * META_MAX); f.write(meta)
            f.seek(base + (SLOTS + s) * META_MAX); f.write(meta)
        for (name, size, first), (_, img) in zip(parts, items):
            f.seek(first * SECTOR)
            with open(img, "rb") as src:
                while True:
                    c = src.read(1 << 24)
                    if not c:
                        break
                    f.write(c)
    used = cur
    print(f"super {out}: {super_size} bytes, used {used} ({used >> 20} MiB)")
    for name, size, first in parts:
        print(f"  {name:12s} sector {first:>9d} size {size}")


if __name__ == "__main__":
    main()
