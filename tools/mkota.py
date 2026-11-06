#!/usr/bin/env python3
"""Build and sign a recovery OTA package for rk3318-android.

mkota.py OUT_DIR(build/out-emmc|build/out) RELEASE CHANGELOG.txt pkg.zip key.pem cert.pem
The boot media (emmc|sd) is taken from OUT_DIR/release.txt and stored as tvbox/media.txt: packages are
media specific (boot_devices differs), update-binary and tvboxd refuse a package for the other media.
Partitions from the build manifest (super, boot, dtbo, vbmeta, recovery) are stored as their .gz regions;
ota/update-binary writes them in recovery with ota/tvbox-flash. The zip is signed like `signapk -w`
(whole-file CMS signature in the zip comment) so the stock recovery verifier and
RecoverySystem.verifyPackage accept it against otacerts.zip.
"""
import base64, os, time, struct, subprocess, sys, tempfile, zipfile

PARTS = ["super", "boot", "dtbo", "vbmeta", "recovery"]   # order of writing: recovery last
OTA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ota")


def build(out_dir, release, changelog, pkg):
    rows = {}
    for line in open(os.path.join(out_dir, "manifest.txt")):
        off, size, name, md5 = line.split()
        rows[name.split("-", 1)[1].rsplit(".", 1)[0]] = (name, int(size), md5)
    total = sum(rows[p][1] for p in PARTS)
    done, lines = 0, []
    for p in PARTS:
        name, size, md5 = rows[p]
        done += size
        lines.append(f"{p} {size} {md5} {name}.gz {done / total:.3f}")
    with zipfile.ZipFile(pkg, "w", zipfile.ZIP_STORED, allowZip64=False) as z:
        # update-binary carries tvbox-flash as base64 after its final "exit 0": with an uncrypt block map the zip is
        # only readable after tvbox-flash --dm-map has joined the package's block ranges (see update-binary)
        ub = zipfile.ZipInfo("META-INF/com/google/android/update-binary", date_time=(2026, 1, 1, 0, 0, 0))
        ub.external_attr = 0o100755 << 16
        ub.compress_type = zipfile.ZIP_DEFLATED
        body = open(os.path.join(OTA, "update-binary"), "rb").read()
        body += b"\n__TVBOX_FLASH_B64__\n" + base64.encodebytes(open(os.path.join(OTA, "tvbox-flash"), "rb").read())
        z.writestr(ub, body)
        z.writestr("META-INF/com/google/android/updater-script", "# rk3318-android: see update-binary\n")
        # A12 recovery refuses a package without META-INF/com/android/metadata (install.cpp CheckPackageMetadata):
        # non-A/B needs ota-type=BLOCK, pre-device = recovery's ro.product.device (rk3328_box; the fingerprint
        # says "redfin" but the device props do not), post-timestamp (anti-downgrade; 1.2 -> 0.3.x is allowed)
        z.writestr("META-INF/com/android/metadata", "ota-type=BLOCK\npre-device=rk3328_box\npost-timestamp=%d\n"
                   "ota-downgrade=yes\nota-required-cache=0\n" % int(time.time()))
        z.writestr("tvbox/release.txt", release + "\n")
        z.writestr("tvbox/media.txt", open(os.path.join(out_dir, "release.txt")).read().split()[2] + "\n")
        z.writestr("tvbox/changelog.txt", open(changelog, encoding="utf-8").read())
        z.writestr("tvbox/parts.txt", "\n".join(lines) + "\n")
        z.write(os.path.join(OTA, "tvbox-flash"), "tvbox/tvbox-flash")
        for p in PARTS:
            z.write(os.path.join(out_dir, "regions", rows[p][0] + ".gz"), "images/" + rows[p][0] + ".gz")


def sign(pkg, key, cert):
    data = open(pkg, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    assert eocd == len(data) - 22, "zip must end with an EOCD without comment"
    signed = data[:-2]                        # everything up to the EOCD comment-length field
    with tempfile.TemporaryDirectory() as t:
        open(os.path.join(t, "d"), "wb").write(signed)
        subprocess.run(["openssl", "cms", "-sign", "-binary", "-noattr", "-nosmimecap", "-md", "sha256",
                        "-outform", "DER", "-signer", cert, "-inkey", key,
                        "-in", os.path.join(t, "d"), "-out", os.path.join(t, "s")], check=True)
        sig = open(os.path.join(t, "s"), "rb").read()
    msg = b"signed by tvbox mkota\x00"
    comment_len = len(msg) + len(sig) + 6
    assert comment_len < 0x10000
    sig_start = len(sig) + 6                  # distance from the end of file to the signature
    footer = struct.pack("<HHH", sig_start, 0xffff, comment_len)
    open(pkg, "wb").write(signed + struct.pack("<H", comment_len) + msg + sig + footer)


def verify(pkg, cert):
    """Host-side check with the same rules as recovery/verifier.cpp."""
    data = open(pkg, "rb").read()
    sig_start, magic, comment_len = struct.unpack("<HHH", data[-6:])
    assert magic == 0xffff
    eocd = len(data) - comment_len - 22
    assert data[eocd:eocd + 4] == b"PK\x05\x06" and struct.unpack("<H", data[eocd + 20:eocd + 22])[0] == comment_len
    assert data.find(b"PK\x05\x06", eocd + 1) == -1, "second EOCD in the comment"
    sig = data[len(data) - sig_start:len(data) - 6]
    with tempfile.TemporaryDirectory() as t:
        open(os.path.join(t, "d"), "wb").write(data[:len(data) - comment_len - 2])
        open(os.path.join(t, "s"), "wb").write(sig)
        r = subprocess.run(["openssl", "cms", "-verify", "-binary", "-inform", "DER", "-in", os.path.join(t, "s"),
                            "-content", os.path.join(t, "d"), "-certfile", cert, "-CAfile", cert, "-purpose", "any",
                            "-out", os.devnull], capture_output=True, text=True)
    return r.returncode == 0, r.stderr.strip()


if __name__ == "__main__":
    out_dir, release, changelog, pkg, key, cert = sys.argv[1:7]
    build(out_dir, release, changelog, pkg)
    sign(pkg, key, cert)
    ok, msg = verify(pkg, cert)
    print(f"{pkg}: {os.path.getsize(pkg)} bytes, signature {'OK' if ok else 'FAIL'} {msg}")
    sys.exit(0 if ok else 1)
