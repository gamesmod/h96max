#!/usr/bin/env python3
# carve FDT blobs (magic d00dfeed) from a file: fdtcarve.py in prefix
import struct,sys
b=open(sys.argv[1],'rb').read(); i=0; n=0
while True:
    i=b.find(b'\xd0\x0d\xfe\xed',i)
    if i<0: break
    sz=struct.unpack_from('>I',b,i+4)[0]
    if 1000<sz<2_000_000:
        open(f'{sys.argv[2]}{n}.dtb','wb').write(b[i:i+sz]); print(f'{sys.argv[2]}{n}.dtb @{i:#x} {sz}'); n+=1; i+=sz
    else: i+=4
