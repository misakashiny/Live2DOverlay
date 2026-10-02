#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提取指定类 code_item 中引用的字符串常量。
用法: strdump.py <dex> <类名子串> [--all]
"""
import struct, sys
from dexparse import Dex, uleb128


def read_code_strings(d, code_off):
    """解析 code_item，返回其中引用的 const-string 字符串列表。"""
    d.f.seek(code_off)
    header = d.f.read(16)
    registers_size, ins_size, outs_size, tries_size = struct.unpack(
        '<HHHH', header[:8])
    insns_size = struct.unpack('<I', header[12:16])[0]
    if insns_size == 0:
        return []
    d.f.seek(code_off + 16)
    insns = d.f.read(insns_size * 2)
    out = []
    i = 0
    while i < insns_size:
        op = insns[i * 2]
        if op == 0x00:
            # nop / payload
            if i + 1 < insns_size and insns[i * 2 + 1] == 0x00:
                pseudo = insns[(i + 1) * 2]
                if pseudo == 0x00:
                    size = struct.unpack('<H', insns[(i + 2) * 2:(i + 3) * 2])[0]
                    i = i + 4 + size * 4
                    continue
                elif pseudo == 0x01:
                    size = struct.unpack('<H', insns[(i + 2) * 2:(i + 3) * 2])[0]
                    i = i + 4 + size * 2
                    continue
                elif pseudo == 0x02:
                    size = struct.unpack('<H', insns[(i + 2) * 2:(i + 3) * 2])[0]
                    i = i + 4 + size * 2
                    continue
                elif pseudo == 0x03:
                    size = struct.unpack('<H', insns[(i + 2) * 2:(i + 3) * 2])[0]
                    i = i + 2 + size
                    continue
            i += 1
        elif op == 0x1a:  # const-string
            idx = struct.unpack('<H', insns[(i + 1) * 2:i * 2 + 4])[0]
            out.append(d.string(idx))
            i += 2
        elif op == 0x1b:  # const-string/jumbo
            raw = insns[(i + 1) * 2:i * 2 + 6]
            idx = struct.unpack('<I', raw[:4])[0]
            out.append(d.string(idx))
            i += 3
        elif op in (0x26, 0x27):  # fill-array-data / packed-switch
            i += 3 if op == 0x27 else 3
        elif 0x02 <= op <= 0x08:
            i += 1
        elif 0x09 <= op <= 0x0a:
            i += 2
        elif 0x0b <= op <= 0x11:
            i += 1
        elif 0x12 <= op <= 0x12:
            i += 1
        elif 0x13 <= op <= 0x19:
            i += 2
        elif 0x1c <= op <= 0x22:
            i += 2
        elif op in (0x23, 0x24):
            i += 2
        elif op == 0x25:
            i += 3
        elif op == 0x28:
            i += 1
        elif 0x29 <= op <= 0x2a:
            i += 1
        elif 0x2b <= op <= 0x2c:
            i += 2
        elif 0x32 <= op <= 0x37:
            i += 2
        elif 0x38 <= op <= 0x3d:
            i += 1
        elif 0x44 <= op <= 0x51:
            i += 2
        elif 0x52 <= op <= 0x5f:
            i += 2
        elif 0x60 <= op <= 0x6d:
            i += 1
        elif 0x6e <= op <= 0x72:
            i += 3
        elif 0x74 <= op <= 0x78:
            i += 3
        elif 0x90 <= op <= 0xaf:
            i += 2
        elif 0xd0 <= op <= 0xd7:
            i += 2
        elif op in (0xd8, 0xd9, 0xda, 0xdb, 0xdc, 0xdd, 0xde, 0xdf, 0xe0, 0xe1):
            i += 2
        elif 0xe3 <= op <= 0xf9:
            i += 2
        else:
            i += 1
    return out


def main():
    path = sys.argv[1]
    needle = sys.argv[2]
    d = Dex(path)
    for cd in d.class_defs():
        name = d.type_str(cd[0])
        if needle not in name:
            continue
        print("=" * 70)
        print("CLASS", name)
        dm, vm = d.methods_of(cd)
        for midx, code_off in dm + vm:
            if code_off == 0:
                continue
            mn, cn = d.method_name(midx)
            try:
                strs = read_code_strings(d, code_off)
            except Exception as e:
                strs = ['<err %s>' % e]
            # 只保留看起来有价值的字符串
            keep = [s for s in strs if len(s) > 2 and (
                any(c in s for c in '/._:') or len(s) > 5)]
            if keep:
                print("\n  --", mn)
                for s in dict.fromkeys(keep):
                    print("       ", repr(s))


if __name__ == '__main__':
    main()
