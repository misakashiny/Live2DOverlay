#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 DEX 解析：列出类名、方法名、字符串中出现的关键词。
用法: dexparse.py <dex> [filter]
"""
import struct, sys, io

def uleb128(b, off):
    result = 0
    shift = 0
    while True:
        byte = b[off]
        off += 1
        result |= (byte & 0x7f) << shift
        if not (byte & 0x80):
            break
        shift += 7
    return result, off

class Dex:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.f.seek(0)
        magic = self.f.read(8)
        assert magic[:4] in (b'dex\n', b'dex\r'), magic[:8]
        self.f.seek(56)
        head = self.f.read(4*20)
        vals = struct.unpack('<20I', head)
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off,
         self.data_size, self.data_off, self.map_off) = vals[:15]
        self._strings = None

    def string(self, idx):
        self.f.seek(self.string_ids_off + idx*4)
        off = struct.unpack('<I', self.f.read(4))[0]
        self.f.seek(off)
        n, p = uleb128(self.f.read(5), 0)
        self.f.seek(off+p)
        return self.f.read(n).decode('utf-8', 'replace')

    def type_str(self, idx):
        self.f.seek(self.type_ids_off + idx*4)
        si = struct.unpack('<I', self.f.read(4))[0]
        return self.string(si)

    def all_strings(self):
        for i in range(self.string_ids_size):
            yield self.string(i)

    def class_defs(self):
        out = []
        for i in range(self.class_defs_size):
            self.f.seek(self.class_defs_off + i*32)
            d = struct.unpack('<8I', self.f.read(32))
            out.append(d)
        return out

    def methods_of(self, class_def):
        # class_def: class_idx, access, superclass_idx, interfaces_off,
        #            source_file_idx, annotations_off, class_data_off, static_values_off
        class_data_off = class_def[6]
        if class_data_off == 0:
            return [], []
        self.f.seek(class_data_off)
        buf = self.f.read(1 << 20)
        p = 0
        static_fields_size, p = uleb128(buf, p)
        instance_fields_size, p = uleb128(buf, p)
        direct_methods_size, p = uleb128(buf, p)
        virtual_methods_size, p = uleb128(buf, p)
        # skip fields
        for _ in range(static_fields_size + instance_fields_size):
            _, p = uleb128(buf, p)
            _, p = uleb128(buf, p)
        def read_methods(count, p):
            mids = []
            midx = 0
            for _ in range(count):
                diff, p = uleb128(buf, p)
                midx += diff
                _, p = uleb128(buf, p)  # access
                code_off, p = uleb128(buf, p)
                mids.append((midx, code_off))
            return mids, p
        d, p = read_methods(direct_methods_size, p)
        v, p = read_methods(virtual_methods_size, p)
        return d, v

    def method_name(self, idx):
        self.f.seek(self.method_ids_off + idx*8)
        class_idx, proto_idx, name_idx = struct.unpack('<HHI', self.f.read(8))
        return self.string(name_idx), self.type_str(class_idx)


def main():
    path = sys.argv[1]
    flt = sys.argv[2] if len(sys.argv) > 2 else None
    d = Dex(path)
    print("# strings:", d.string_ids_size, "types:", d.type_ids_size,
          "methods:", d.method_ids_size, "classes:", d.class_defs_size)
    for cd in d.class_defs():
        name = d.type_str(cd[0])
        if flt and flt.lower() not in name.lower():
            continue
        print("\n=== CLASS", name, "access=0x%x" % cd[1])

if __name__ == '__main__':
    main()
