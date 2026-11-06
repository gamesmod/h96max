#!/usr/bin/env python3
"""Unpack Rockchip update.img (RKFW wrapper + RKAF archive).

usage: rkunpack.py update.img outdir [--list]
"""
import os
import struct
import sys


def rkaf_items(f, base):
    f.seek(base)
    hdr = f.read(0x800)
    if hdr[:4] != b"RKAF":
        raise SystemExit(f"no RKAF at {base:#x}")
    model = hdr[0x08:0x08 + 34].split(b"\0")[0].decode(errors="replace")
    count = struct.unpack_from("<I", hdr, 0x88)[0]
    items = []
    for i in range(count):
        off = 0x8C + i * 112
        name = hdr[off:off + 32].split(b"\0")[0].decode(errors="replace")
        path = hdr[off + 32:off + 92].split(b"\0")[0].decode(errors="replace")
        nand_size, pos, nand_addr, pad, size = struct.unpack_from("<5I", hdr, off + 92)
        items.append((name, path, pos, size, nand_addr))
    return model, items


def main():
    img, out = sys.argv[1], sys.argv[2]
    list_only = "--list" in sys.argv
    with open(img, "rb") as f:
        head = f.read(0x66)
        if head[:4] == b"RKFW":
            boot_off, boot_size = struct.unpack_from("<II", head, 0x19)
            fw_off, fw_size = struct.unpack_from("<II", head, 0x21)
            print(f"RKFW: loader @{boot_off:#x} size {boot_size}, RKAF @{fw_off:#x} size {fw_size}")
            if not list_only:
                os.makedirs(out, exist_ok=True)
                f.seek(boot_off)
                open(os.path.join(out, "MiniLoaderAll.bin"), "wb").write(f.read(boot_size))
        elif head[:4] == b"RKAF":
            fw_off = 0
        else:
            raise SystemExit("unknown format")
        model, items = rkaf_items(f, fw_off)
        print(f"model: {model}")
        for name, path, pos, size, addr in items:
            print(f"  {name:20s} {path:40s} pos={pos:#x} size={size} nand_addr={addr:#x}")
            if list_only or path in ("SELF", "RESERVED") or size == 0:
                continue
            dst = os.path.join(out, path)
            os.makedirs(os.path.dirname(dst) or out, exist_ok=True)
            f.seek(fw_off + pos)
            with open(dst, "wb") as o:
                left = size
                while left:
                    chunk = f.read(min(left, 1 << 24))
                    if not chunk:
                        raise SystemExit(f"truncated reading {path}")
                    o.write(chunk)
                    left -= len(chunk)


if __name__ == "__main__":
    main()
