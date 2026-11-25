#!/usr/bin/env python3
"""Analyse / convert .snd files.  A .snd is a plain Ogg Vorbis stream (no XOR, no extra header).
  snd.py info  X.snd
  snd.py ogg   X.snd [out.ogg]    (byte-identical copy with .ogg name)
  snd.py mp3   X.snd [out.mp3]    (needs ffmpeg)
"""
import struct, sys, os, subprocess

def info(path):
    d = open(path, 'rb').read()
    assert d[:4] == b'OggS', 'not an Ogg stream'
    p = d.find(b'\x01vorbis')
    ver, ch, rate, bmax, bnom, bmin = struct.unpack('<IBIiii', d[p+7:p+28])
    last = d.rfind(b'OggS')
    granule = struct.unpack('<q', d[last+6:last+14])[0]
    return dict(size=len(d), channels=ch, rate=rate, bitrate_nominal=bnom,
                samples=granule, seconds=round(granule/rate, 2))

if __name__ == '__main__':
    a = sys.argv[1:]
    cmd, src = a[0], a[1]
    base = os.path.splitext(src)[0]
    if cmd == 'info':
        for k, v in info(src).items(): print(f'{k}: {v}')
    elif cmd == 'ogg':
        open(a[2] if len(a) > 2 else base + '.ogg', 'wb').write(open(src, 'rb').read())
    elif cmd == 'mp3':
        subprocess.check_call(['ffmpeg', '-y', '-i', src, '-codec:a', 'libmp3lame', '-b:a', '192k',
                               a[2] if len(a) > 2 else base + '.mp3'])
