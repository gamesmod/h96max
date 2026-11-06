#!/usr/bin/env python3
"""Minimal APK v1 (JAR) signer using openssl: v1sign.py in.apk out.apk key.pem cert.pem"""
import base64
import hashlib
import subprocess
import sys
import tempfile
import zipfile


def b64(d):
    return base64.b64encode(d).decode()


def main():
    src, dst, key, cert = sys.argv[1:5]
    zin = zipfile.ZipFile(src)
    names = [n for n in zin.namelist() if not n.startswith("META-INF/")]
    mf = "Manifest-Version: 1.0\r\nCreated-By: tvbox\r\n\r\n"
    sections = []
    for n in names:
        sec = f"Name: {n}\r\nSHA-256-Digest: {b64(hashlib.sha256(zin.read(n)).digest())}\r\n\r\n"
        sections.append((n, sec))
        mf += sec
    sf = (f"Signature-Version: 1.0\r\nCreated-By: tvbox\r\n"
          f"SHA-256-Digest-Manifest: {b64(hashlib.sha256(mf.encode()).digest())}\r\n\r\n")
    for n, sec in sections:
        sf += f"Name: {n}\r\nSHA-256-Digest: {b64(hashlib.sha256(sec.encode()).digest())}\r\n\r\n"
    with tempfile.NamedTemporaryFile() as t:
        t.write(sf.encode()); t.flush()
        rsa = subprocess.run(["openssl", "cms", "-sign", "-binary", "-noattr", "-outform", "DER",
                              "-md", "sha256", "-signer", cert, "-inkey", key, "-in", t.name],
                             check=True, capture_output=True).stdout
    with zipfile.ZipFile(dst, "w") as zout:
        zout.writestr("META-INF/MANIFEST.MF", mf)
        zout.writestr("META-INF/CERT.SF", sf)
        zout.writestr("META-INF/CERT.RSA", rsa)
        for n in names:
            info = zin.getinfo(n)
            zout.writestr(info, zin.read(n), compress_type=zipfile.ZIP_STORED if n == "resources.arsc" else info.compress_type)


if __name__ == "__main__":
    main()
