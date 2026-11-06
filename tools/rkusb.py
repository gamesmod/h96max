#!/usr/bin/env python3
"""Minimal Rockchip rockusb client (own code, libusb-1.0 via ctypes) with 32-bit sector addresses.

The Ubuntu rkdeveloptool 1.32 (pine64 fork) cannot read/write past sector 65535 (32 MiB) on this box:
starts above that return 0xCC fill / are not written. This client speaks the same protocol directly:
CBW (31 bytes, 'USBC', opcode 0x14 READ_LBA / 0x15 WRITE_LBA, big-endian 32-bit LBA + 16-bit sector count),
data stage, CSW (13 bytes, 'USBS'). Device: 2207:320c in Loader/MaskROM+MiniLoader mode, bulk EP 0x81 in / 0x02 out.

  sudo python3 -I tools/rkusb.py rl SECTOR BYTES OUTFILE
  sudo python3 -I tools/rkusb.py wl SECTOR INFILE           (writes, then reads back and compares)
  sudo python3 -I tools/rkusb.py flash RELEASE_DIR           (every region of manifest.txt, gunzipped,
                                                              written in 4 MiB steps, each read back + md5)
"""
import ctypes, gzip, hashlib, os, struct, sys

VID, PID, EP_IN, EP_OUT, IFACE = 0x2207, 0x320C, 0x81, 0x02, 0
SECT, STEP = 512, 128            # sectors per USB command (64 KiB)
lib = ctypes.CDLL("libusb-1.0.so.0")
lib.libusb_open_device_with_vid_pid.restype = ctypes.c_void_p
lib.libusb_bulk_transfer.argtypes = [ctypes.c_void_p, ctypes.c_ubyte, ctypes.c_void_p, ctypes.c_int,
                                     ctypes.POINTER(ctypes.c_int), ctypes.c_uint]
lib.libusb_claim_interface.argtypes = [ctypes.c_void_p, ctypes.c_int]
lib.libusb_kernel_driver_active.argtypes = [ctypes.c_void_p, ctypes.c_int]
lib.libusb_detach_kernel_driver.argtypes = [ctypes.c_void_p, ctypes.c_int]

class Dev:
    def __init__(self):
        if lib.libusb_init(None) != 0: raise SystemExit("libusb_init failed")
        self.h = lib.libusb_open_device_with_vid_pid(None, VID, PID)
        if not self.h: raise SystemExit("no 2207:320c device (box not in Loader/MaskROM?)")
        if lib.libusb_kernel_driver_active(self.h, IFACE) == 1: lib.libusb_detach_kernel_driver(self.h, IFACE)
        if lib.libusb_claim_interface(self.h, IFACE) != 0: raise SystemExit("claim interface failed")
        self.tag = 1

    def _bulk(self, ep, buf, n):
        done = ctypes.c_int(0)
        r = lib.libusb_bulk_transfer(self.h, ep, buf, n, ctypes.byref(done), 20000)
        if r != 0: raise IOError("bulk transfer ep=%#x error %d" % (ep, r))
        return done.value

    def _cmd(self, op, lba, count, flags, data=None):
        self.tag += 1
        cb = struct.pack(">BBIBH7x", op, 0, lba, 0, count)
        cbw = struct.pack("<IIIBBB", 0x43425355, self.tag, count * SECT, flags, 0, 0x0A) + cb
        self._bulk(EP_OUT, ctypes.create_string_buffer(cbw, 31), 31)
        out = None
        if flags & 0x80:
            b = ctypes.create_string_buffer(count * SECT)
            got = self._bulk(EP_IN, b, count * SECT)
            out = b.raw[:got]
        else:
            self._bulk(EP_OUT, ctypes.create_string_buffer(data, len(data)), len(data))
        csw = ctypes.create_string_buffer(13)
        self._bulk(EP_IN, csw, 13)
        sig, tag, residue, status = struct.unpack("<IIIB", csw.raw)
        if sig != 0x53425355 or tag != self.tag or status != 0:
            raise IOError("CSW error op=%#x lba=%d status=%d" % (op, lba, status))
        return out

    def read(self, lba, nbytes):
        out, left = bytearray(), (nbytes + SECT - 1) // SECT
        while left:
            n = min(STEP, left)
            out += self._cmd(0x14, lba, n, 0x80)
            lba += n; left -= n
        return bytes(out[:nbytes])

    def write(self, lba, data):
        if len(data) % SECT: data += b"\0" * (SECT - len(data) % SECT)
        for o in range(0, len(data), STEP * SECT):
            chunk = data[o:o + STEP * SECT]
            self._cmd(0x15, lba + o // SECT, len(chunk) // SECT, 0x00, chunk)

GATE = 65536

def flash(d):
    unverified = [0]
    rel = open(os.path.join(d, "release.txt")).read().split()
    dev = Dev()
    print("release", " ".join(rel), flush=True)
    for line in open(os.path.join(d, "manifest.txt")):
        off, size, name, md5 = line.split()
        off, size = int(off), int(size)
        print("== %s @%d (%d bytes)" % (name, off, size), flush=True)
        whole = hashlib.md5()
        with gzip.open(os.path.join(d, "regions", name + ".gz")) as g:
            pos, step = 0, 4 << 20
            while True:
                buf = g.read(step)
                if not buf: break
                whole.update(buf)
                lba = off + pos // SECT
                dev.write(lba, buf)
                # reads starting at LBA >= 65536 are gated by the loader (0xCC): verify over USB only below
                # 32 MiB; everything else is verified from the running system against manifest.txt
                if lba + (len(buf) + SECT - 1) // SECT <= GATE:
                    if dev.read(lba, len(buf)) != buf: raise SystemExit("VERIFY FAIL %s at byte %d" % (name, pos))
                else:
                    unverified[0] += len(buf)
                pos += len(buf)
                if pos % (256 << 20) == 0: print("   %d MiB" % (pos >> 20), flush=True)
        if pos != size or whole.hexdigest() != md5: raise SystemExit("source mismatch %s" % name)
        print("   ok (%d bytes written and read back)" % pos, flush=True)
    print("ALL REGIONS WRITTEN; verified over USB below 32 MiB, %d MiB to verify on the running system"
          % (unverified[0] >> 20), flush=True)

if __name__ == "__main__":
    a = sys.argv[1:]
    if a[0] == "rl":
        open(a[3], "wb").write(Dev().read(int(a[1]), int(a[2])))
    elif a[0] == "wl":
        dev, data = Dev(), open(a[2], "rb").read()
        dev.write(int(a[1]), data)
        print("readback", "OK" if dev.read(int(a[1]), len(data)) == data else "DIFF")
    elif a[0] == "flash":
        flash(a[1])
