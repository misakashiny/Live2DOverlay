#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""绕过 CRC 校验从 APK 提取文件（重打包 APK 常见 CRC 字段与实际数据不符）。
用法: rawzip.py <apk> <entry> <outfile>
"""
import struct, sys, zlib


def extract(apk, entry, out):
    with open(apk, 'rb') as f:
        data = f.read()
    # 定位 End of Central Directory
    eocd = data.rfind(b'PK\x05\x06')
    if eocd < 0:
        raise SystemExit('EOCD not found')
    cd_size, cd_off = struct.unpack('<II', data[eocd + 12:eocd + 20])
    p = cd_off
    while p < cd_off + cd_size:
        if data[p:p + 4] != b'PK\x01\x02':
            break
        (sig, vermade, verneed, flags, method, mtime, mdate, crc,
         csize, usize, nlen, elen, clen, disk, iattr, eattr,
         local_off) = struct.unpack('<IHHHHHHIIIHHHHHII', data[p:p + 46])
        name = data[p + 46:p + 46 + nlen].decode('utf-8', 'replace')
        if name == entry:
            lp = local_off
            if data[lp:lp + 4] != b'PK\x03\x04':
                raise SystemExit('bad local header')
            (lsig, lver, lflags, lmethod, lt, ld, lcrc, lcsize, lusize,
             lnlen, lelen) = struct.unpack('<IHHHHHIIIHH', data[lp:lp + 30])
            start = lp + 30 + lnlen + lelen
            raw = data[start:start + csize]
            if method == 0:
                content = raw
            elif method == 8:
                content = zlib.decompressobj(-15).decompress(raw)
            else:
                raise SystemExit('unsupported method %d' % method)
            with open(out, 'wb') as g:
                g.write(content)
            print('extracted %s -> %s (%d bytes, method=%d, declared_crc=0x%08x actual_crc=0x%08x)'
                  % (entry, out, len(content), method, crc, zlib.crc32(content) & 0xffffffff))
            return
        p += 46 + nlen + elen + clen
    raise SystemExit('entry not found: ' + entry)


def list_entries(apk, pat=None):
    with open(apk, 'rb') as f:
        data = f.read()
    eocd = data.rfind(b'PK\x05\x06')
    cd_size, cd_off = struct.unpack('<II', data[eocd + 12:eocd + 20])
    p = cd_off
    n = 0
    while p < cd_off + cd_size:
        if data[p:p + 4] != b'PK\x01\x02':
            break
        (sig, vermade, verneed, flags, method, mtime, mdate, crc,
         csize, usize, nlen, elen, clen, disk, iattr, eattr,
         local_off) = struct.unpack('<IHHHHHHIIIHHHHHII', data[p:p + 46])
        name = data[p + 46:p + 46 + nlen].decode('utf-8', 'replace')
        if pat is None or pat in name:
            print(name, csize, usize)
            n += 1
        p += 46 + nlen + elen + clen
    print('# matched', n)


if __name__ == '__main__':
    if sys.argv[1] == '--list':
        list_entries(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    else:
        extract(sys.argv[1], sys.argv[2], sys.argv[3])
