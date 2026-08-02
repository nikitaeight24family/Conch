"""
List the interesting ELF sections of each 64-bit .so inside an APK/AAB.

Point of this: decide whether Play's "upload native debug symbols" warning is
actually actionable. AGP's debugSymbolLevel runs objcopy over OUR .so files — if
every one of them is a prebuilt that the upstream AAR already stripped, there is
no .symtab and no .debug_* to extract, and installing a ~1 GB NDK buys a
metadata entry with nothing in it.
"""
import struct
import sys
import zipfile

WANT = (".symtab", ".debug_info", ".debug_line", ".dynsym", ".gnu_debuglink")


def sections(data: bytes):
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return {}
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    if e_shoff == 0 or e_shnum == 0:
        return {}
    str_off = struct.unpack_from("<Q", data, e_shoff + e_shstrndx * e_shentsize + 0x18)[0]
    out = {}
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name_off = struct.unpack_from("<I", data, off)[0]
        end = data.index(b"\0", str_off + name_off)
        name = data[str_off + name_off:end].decode("ascii", "replace")
        out[name] = struct.unpack_from("<Q", data, off + 0x20)[0]   # sh_size
    return out


def main():
    with zipfile.ZipFile(sys.argv[1]) as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".so") or "arm64-v8a" not in name.split("/"):
                continue
            secs = sections(z.read(name))
            have = {k: v for k, v in secs.items() if k in WANT}
            print(f"{name.split('/')[-1]:>40}  " +
                  (", ".join(f"{k}={v}" for k, v in have.items()) or "(nothing)"))


if __name__ == "__main__":
    main()
