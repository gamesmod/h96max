#!/usr/bin/env python3
# minimal lpunpack for raw (non-sparse) super.img: lpunpack.py super.raw outdir [name...]
import struct,sys,os
f=open(sys.argv[1],'rb'); out=sys.argv[2]; want=set(sys.argv[3:])
f.seek(4096); g=f.read(4096)
assert struct.unpack_from('<I',g,0)[0]==0x616c4467,'no LP geometry'
f.seek(12288); h=f.read(4096)
magic,maj,mnr,hsz=struct.unpack_from('<IHHI',h,0); assert magic==0x414c5030
tsz=struct.unpack_from('<I',h,44)[0]
p_off,p_n,p_sz,e_off,e_n,e_sz=struct.unpack_from('<6I',h,80)
t=h[hsz:]+f.read(max(0,tsz-(4096-hsz)))
ext=[struct.unpack_from('<QIQI',t,e_off+i*e_sz) for i in range(e_n)]
os.makedirs(out,exist_ok=True)
for i in range(p_n):
    o=p_off+i*p_sz
    name=t[o:o+36].split(b'\0')[0].decode(); attr,fe,ne,gi=struct.unpack_from('<4I',t,o+36)
    tot=sum(ext[j][0] for j in range(fe,fe+ne))*512
    print(name,tot)
    if want and name not in want or tot==0: continue
    with open(os.path.join(out,name+'.img'),'wb') as w:
        for j in range(fe,fe+ne):
            ns,tt,td,ts=ext[j]
            if tt!=0: continue
            f.seek(td*512); left=ns*512
            while left:
                c=f.read(min(left,1<<24)); w.write(c); left-=len(c)
