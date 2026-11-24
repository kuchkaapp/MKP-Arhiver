#!/usr/bin/env python3
"""Unpack / pack .mkh + .mkp archives (XOR 0xA7). Fixed for 'v100' headers.
  mkp.py unpack X.mkh X.mkp outdir
  mkp.py pack   outdir X.mkh X.mkp   (order/names from outdir/_index.txt)
"""
import struct, sys, os
K = 0xA7
x = lambda b: bytes(c ^ K for c in b)

def unpack(mkh, mkp, out):
    h = open(mkh, 'rb').read(); p = x(open(mkp, 'rb').read())
    assert h[:5] == b'mkp\0v'
    _, total, n = struct.unpack('>III', h[8:20])
    os.makedirs(out, exist_ok=True); names = []
    for i in range(n):
        r = x(h[32+i*32:64+i*32])
        off, size = struct.unpack('>II', r[:8]); ln = r[8]
        name = r[9:9+ln].decode('ascii')
        open(os.path.join(out, name), 'wb').write(p[off:off+size])
        names.append(name); print(f'{name}: off={off} size={size}')
    open(os.path.join(out, '_index.txt'), 'w').write('\n'.join(names))

def pack(src, mkh, mkp):
    names = open(os.path.join(src, '_index.txt')).read().split('\n')
    data = b''; ents = b''
    for nm in names:
        d = open(os.path.join(src, nm), 'rb').read()
        ents += x(struct.pack('>II', len(data), len(d)) + (bytes([len(nm)]) + nm.encode()).ljust(24, b'\0'))
        data += d + b'\0' * (-len(d) % 4)
    hdr = b'mkp\0v100' + struct.pack('>III', 32 + 32*len(names), len(data), len(names)) + b'\0'*12
    open(mkh, 'wb').write(hdr + ents); open(mkp, 'wb').write(x(data))

if __name__ == '__main__':
    a = sys.argv[1:]
    unpack(*a[1:4]) if a[0] == 'unpack' else pack(*a[1:4])
