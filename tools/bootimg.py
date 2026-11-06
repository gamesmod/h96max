#!/usr/bin/env python3
"""Minimal Android boot image (header v0/v1/v2) editor.

  bootimg.py verify boot.img
  bootimg.py edit in.img out.img [--cmd-sub "OLD|NEW"]... [--cmd-append STR]
                 [--rd-put PATH=LOCALFILE]... [--rd-get PATH=LOCALFILE]...
Ramdisk must be gzip'd newc cpio; entries are edited in place (owners/modes kept).
"""
import gzip
import hashlib
import struct
import sys

PAGE_FIELDS = "<8s10I16s512s32s1024s"


def up(n, p):
    return (n + p - 1) // p * p


def load(path):
    b = open(path, "rb").read()
    assert b[:8] == b"ANDROID!"
    f = list(struct.unpack_from("<10I", b, 8))
    ks, ka, rs, ra, ss, sa, ta, ps, hv, osv = f
    name = b[48:64]
    cmd = b[64:576].split(b"\0")[0]
    hid = b[576:608]
    extra = b[608:1632].split(b"\0")[0]
    rdo_size = rdo_off = hsize = 0
    if hv >= 1:
        rdo_size, rdo_off, hsize = struct.unpack_from("<IQI", b, 1632)
    o = ps
    kernel = b[o:o + ks]; o += up(ks, ps)
    ramdisk = b[o:o + rs]; o += up(rs, ps)
    second = b[o:o + ss]; o += up(ss, ps)
    rdo = b[o:o + rdo_size]; o += up(rdo_size, ps)
    dtb, dtb_addr = b"", 0
    if hv >= 2:
        dtb_size, dtb_addr = struct.unpack_from("<IQ", b, 1648)
        dtb = b[o:o + dtb_size]; o += up(dtb_size, ps)
    return dict(raw=b, f=f, name=name, cmd=cmd, id=hid, extra=extra, rdo_off=rdo_off,
                hsize=hsize, kernel=kernel, ramdisk=ramdisk, second=second, rdo=rdo,
                dtb=dtb, dtb_addr=dtb_addr)


def calc_id(d):
    h = hashlib.sha1()
    for part in (d["kernel"], d["ramdisk"], d["second"]):
        h.update(part)
        h.update(struct.pack("<I", len(part)))
    if d["f"][8] >= 1:
        h.update(d["rdo"])
        h.update(struct.pack("<I", len(d["rdo"])))
    if d["f"][8] >= 2:
        h.update(d["dtb"])
        h.update(struct.pack("<I", len(d["dtb"])))
    return h.digest().ljust(32, b"\0")


def save(d, path):
    ks, ka, rs, ra, ss, sa, ta, ps, hv, osv = d["f"]
    ks, rs, ss = len(d["kernel"]), len(d["ramdisk"]), len(d["second"])
    d["f"] = [ks, ka, rs, ra, ss, sa, ta, ps, hv, osv]
    hdr = bytearray(ps)
    hdr[0:8] = b"ANDROID!"
    struct.pack_into("<10I", hdr, 8, *d["f"])
    hdr[48:64] = d["name"]
    assert len(d["cmd"]) < 512, "cmdline too long"
    hdr[64:64 + len(d["cmd"])] = d["cmd"]
    hdr[576:608] = calc_id(d)
    hdr[608:608 + len(d["extra"])] = d["extra"]
    if hv >= 1:
        struct.pack_into("<IQI", hdr, 1632, len(d["rdo"]), d["rdo_off"], d["hsize"])
    if hv >= 2:
        struct.pack_into("<IQ", hdr, 1648, len(d["dtb"]), d["dtb_addr"])
    out = bytes(hdr)
    for part in (d["kernel"], d["ramdisk"], d["second"], d["rdo"], d["dtb"]):
        out += part + b"\0" * (up(len(part), ps) - len(part))
    open(path, "wb").write(out)


def cpio_entries(data):
    i = 0
    while True:
        h = data[i:i + 110]
        assert h[:6] in (b"070701", b"070702"), f"bad cpio magic at {i}"
        fields = [int(h[6 + 8 * k:14 + 8 * k], 16) for k in range(13)]
        namesize, filesize = fields[11], fields[6]
        name = data[i + 110:i + 110 + namesize - 1].decode()
        nend = up(i + 110 + namesize, 4)
        fdata = data[nend:nend + filesize]
        nxt = up(nend + filesize, 4)
        yield h, name, fdata
        i = nxt
        if name == "TRAILER!!!":
            break


def cpio_edit(data, puts, gets, adds=None):
    adds = adds or {}
    out = bytearray()
    seen = set()
    tmpl = None
    for h, name, fdata in cpio_entries(data):
        if name == "init":
            tmpl = h
        if name == "TRAILER!!!" and adds:
            assert tmpl, "no init entry to clone header from"
            for k, (aname, afile) in enumerate(adds.items()):
                ad = open(afile, "rb").read()
                ah = tmpl[:6] + b"%08X" % (0x7f000 + k) + tmpl[14:54] + b"%08X" % len(ad) + tmpl[62:94] + b"%08X" % (len(aname) + 1) + tmpl[102:]
                start = len(out)
                out += ah + aname.encode() + b"\0"
                out += b"\0" * (up(len(out) - start, 4) - (len(out) - start))
                out += ad
                out += b"\0" * (up(len(out) - start, 4) - (len(out) - start))
        if name in gets:
            open(gets[name], "wb").write(fdata)
        if name in puts:
            fdata = open(puts[name], "rb").read()
            h = h[:54] + b"%08X" % len(fdata) + h[62:]
            seen.add(name)
        start = len(out)
        out += h + name.encode() + b"\0"
        out += b"\0" * (up(len(out) - start, 4) - (len(out) - start))
        out += fdata
        out += b"\0" * (up(len(out) - start, 4) - (len(out) - start))
    missing = set(puts) - seen
    assert not missing, f"not in ramdisk: {missing}"
    return bytes(out)


def main():
    if sys.argv[1] == "verify":
        d = load(sys.argv[2])
        ok = calc_id(d) == d["id"]
        print("hdr_v", d["f"][8], "id ok" if ok else "id MISMATCH", "cmdline:", d["cmd"].decode())
        sys.exit(0 if ok else 1)
    d = load(sys.argv[2])
    out = sys.argv[3]
    args = sys.argv[4:]
    puts, gets, adds = {}, {}, {}
    cmd = d["cmd"].decode()
    for k in range(0, len(args), 2):
        opt, val = args[k], args[k + 1]
        if opt == "--cmd-sub":
            old, new = val.split("|", 1)
            assert old in cmd, f"{old!r} not in cmdline"
            cmd = cmd.replace(old, new)
        elif opt == "--cmd-append":
            cmd = cmd + " " + val
        elif opt == "--rd-put":
            p, f = val.split("=", 1); puts[p] = f
        elif opt == "--rd-add":
            p, f = val.split("=", 1); adds[p] = f
        elif opt == "--rd-get":
            p, f = val.split("=", 1); gets[p] = f
    d["cmd"] = cmd.encode()
    if puts or gets or adds:
        rd = gzip.decompress(d["ramdisk"])
        rd2 = cpio_edit(rd, puts, gets, adds)
        if puts or adds:
            d["ramdisk"] = gzip.compress(rd2, 9, mtime=0)
    save(d, out)
    print("written", out, "cmdline:", cmd)


if __name__ == "__main__":
    main()
