#!/usr/bin/env python3
"""Rockchip IDB (ID block) tool, logic ported from rkdeveloptool (main.cpp/crc.cpp).

  rkidb.py make MiniLoaderAll.bin out.idb [--sdboot-tag N]
      Build an IDB (written at sector 64).  --sdboot-tag sets the u32 at sector1+104
      (read by u-boot rkimgtest: 0 => "sdfwupdate" i.e. SD *upgrade* card!).  Default 1.
  rkidb.py info file [sector_offset]
      Decode IDB header found at sector_offset (default 64 for a disk image, 0 for .idb).
"""
import struct
import sys

SECTOR = 512
KEY = bytes([124, 78, 3, 4, 85, 5, 9, 7, 45, 44, 123, 56, 23, 13, 23, 17])


def rc4(buf):
    s = list(range(256))
    k = [KEY[i & 15] for i in range(256)]
    j = 0
    for i in range(256):
        j = (j + s[i] + k[i]) % 256
        s[i], s[j] = s[j], s[i]
    out = bytearray(buf)
    i = j = 0
    for x in range(len(out)):
        i = (i + 1) % 256
        j = (j + s[i]) % 256
        s[i], s[j] = s[j], s[i]
        out[x] ^= s[(s[i] + s[j]) % 256]
    return bytes(out)


def rc4_sectors(data):
    return b"".join(rc4(data[i:i + SECTOR]) for i in range(0, len(data), SECTOR))


def crc16(data):
    # CRC-CCITT as in rkdeveloptool CRC_16 (poly 0x1021, init 0)
    crc = 0
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else crc << 1
            crc &= 0xFFFF
    return crc


def _crc32_rk_table():
    tab = []
    for i in range(256):
        c = i << 24
        for _ in range(8):
            c = ((c << 1) ^ 0x04C10DB7) if c & 0x80000000 else c << 1
            c &= 0xFFFFFFFF
        tab.append(c)
    return tab


_T = _crc32_rk_table()


def crc32_rk(data):
    crc = 0
    for b in data:
        crc = ((crc << 8) ^ _T[((crc >> 24) ^ b) & 0xFF]) & 0xFFFFFFFF
    return crc


def parse_rkboot(path):
    b = open(path, "rb").read()
    if b[:4] != b"BOOT" and b[:4] != b"LDR ":
        raise SystemExit("not an RKBOOT loader")
    (tag, size, ver, mver) = struct.unpack_from("<IHII", b, 0)
    off = 4 + 2 + 4 + 4 + 7 + 4  # tag,size,ver,mver,time,chip
    c471, o471, s471, c472, o472, s472, cl, ol, sl, sign, rc4flag = struct.unpack_from(
        "<BIBBIBBIBBB", b, off)
    entries = {}
    for i in range(cl):
        e = ol + i * sl
        esz, etype = struct.unpack_from("<BI", b, e)
        name = b[e + 5:e + 45].decode("utf-16le").split("\0")[0]
        doff, dsize, ddelay = struct.unpack_from("<III", b, e + 45)
        entries[name] = b[doff:doff + dsize]
    return entries, rc4flag


def align(n, a):
    return (n + a - 1) // a * a


def make(loader, out, tag=1):
    entries, rc4_disable = parse_rkboot(loader)
    data = entries["FlashData"]
    boot = entries["FlashBoot"]
    if "FlashHead" in entries:
        raise SystemExit("new-style IDB (FlashHead) not supported")
    data_sec = align(len(data), 2048) // SECTOR
    boot_sec = align(len(boot), 2048) // SECTOR
    data = data.ljust(data_sec * SECTOR, b"\0")
    boot = boot.ljust(boot_sec * SECTOR, b"\0")

    sec0 = bytearray(SECTOR)
    struct.pack_into("<I", sec0, 0, 0x0FF0AA55)
    struct.pack_into("<I", sec0, 8, 1 if rc4_disable else 0)  # uiRc4Flag
    struct.pack_into("<HH", sec0, 12, 4, 4)                     # BootCode1/2 offset
    struct.pack_into("<HH", sec0, 506, data_sec, data_sec + boot_sec)
    sec1 = bytearray(SECTOR)
    struct.pack_into("<HH", sec1, 0, 0xC, 0xFFFF)               # SysReservedBlock, Disk0Size
    struct.pack_into("<I", sec1, 10, 0x38324B52)                # uiChipTag "RK28"
    struct.pack_into("<I", sec1, 104, tag)                      # SD boot (!=0) vs upgrade (0)
    sec3 = bytearray(SECTOR)

    if rc4_disable:
        data = rc4_sectors(data)
        boot = rc4_sectors(boot)
    code = data + boot
    sec2 = bytearray(SECTOR)
    sec2[0:3] = b"VC\0"   # szVcTag[2]? layout below
    # RK28_IDB_SEC2: reserved[...]; we follow rkdeveloptool struct layout:
    # USHORT usInfoSize; BYTE bChipInfo[16]; BYTE reserved[473]; CHAR szVcTag[3];
    # USHORT usSec0Crc; USHORT usSec1Crc; UINT uiBootCodeCrc; USHORT usSec3CustomDataOffset;
    # USHORT usSec3CustomDataSize; CHAR szCrcTag[4]; USHORT usSec3Crc;
    sec2 = bytearray(SECTOR)
    o = 2 + 16 + 473
    sec2[o:o + 3] = b"VC\0"
    o += 3
    struct.pack_into("<HHI", sec2, o, crc16(sec0), crc16(sec1), crc32_rk(code))
    o += 8 + 4
    sec2[o:o + 4] = b"CRC\0"
    o += 4
    struct.pack_into("<H", sec2, o, crc16(sec3))
    idb = rc4(bytes(sec0)) + bytes(sec1) + rc4(bytes(sec2)) + rc4(bytes(sec3)) + code
    open(out, "wb").write(idb)
    print(f"IDB: {len(idb)} bytes ({len(idb)//SECTOR} sectors), FlashData {data_sec} sec, "
          f"FlashBoot {boot_sec} sec, rc4_disable={rc4_disable}, sdboot_tag={tag}")


def info(path, sec_off):
    f = open(path, "rb")
    f.seek(sec_off * SECTOR)
    h = f.read(4 * SECTOR)
    s0 = rc4(h[:SECTOR])
    s1 = h[SECTOR:2 * SECTOR]
    s2 = rc4(h[2 * SECTOR:3 * SECTOR])
    tag, = struct.unpack_from("<I", s0, 0)
    rc4f, = struct.unpack_from("<I", s0, 8)
    o1, o2 = struct.unpack_from("<HH", s0, 12)
    dsz, csz = struct.unpack_from("<HH", s0, 506)
    print(f"raw word0={struct.unpack_from('<I', h, 0)[0]:#x} sec0 tag={tag:#x} rc4flag={rc4f} "
          f"code_off={o1},{o2} data_sec={dsz} code_sec={csz}")
    print(f"sec1: sysres={struct.unpack_from('<H', s1, 0)[0]:#x} chiptag={struct.unpack_from('<I', s1, 10)[0]:#x} "
          f"word@104={struct.unpack_from('<I', s1, 104)[0]:#x}")
    o = 2 + 16 + 473
    vc = s2[o:o + 3]
    c0, c1, cc = struct.unpack_from("<HHI", s2, o + 3)
    print(f"sec2: vctag={vc} sec0crc={c0:#x}(calc {crc16(s0):#x}) sec1crc={c1:#x}(calc {crc16(s1):#x}) codecrc={cc:#x}")
    code = f.read(csz * SECTOR)
    print(f"code crc calc={crc32_rk(code):#x}")


if __name__ == "__main__":
    if sys.argv[1] == "make":
        t = 1
        if "--sdboot-tag" in sys.argv:
            t = int(sys.argv[sys.argv.index("--sdboot-tag") + 1], 0)
        make(sys.argv[2], sys.argv[3], t)
    elif sys.argv[1] == "info":
        info(sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 64)
