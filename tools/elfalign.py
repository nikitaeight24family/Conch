"""
Report the ELF load-segment alignment of every 64-bit .so in an APK/AAB.

Google Play (Nov 2025) rejects apps whose 64-bit native libraries are not
16 KB-page-safe: every PT_LOAD segment must have p_align >= 16384. The check
is on the ELF program headers of the prebuilt .so, so it cannot be fixed by
zipalign — the producing dependency has to be built with
`-Wl,-z,max-page-size=16384`, i.e. upgraded.

Pure-Python on purpose: no NDK / llvm-readelf needed on this machine.
"""
import struct
import sys
import zipfile

PT_LOAD = 1
NEED = 16384


def loads(data: bytes):
    """Yield (p_align,) for every PT_LOAD in a 64-bit little-endian ELF."""
    if data[:4] != b"\x7fELF":
        return
    is64 = data[4] == 2
    if not is64:
        return
    e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
    e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
    e_phnum = struct.unpack_from("<H", data, 0x38)[0]
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", data, off)[0]
        if p_type == PT_LOAD:
            yield struct.unpack_from("<Q", data, off + 0x30)[0]


def main():
    path = sys.argv[1]
    bad = 0
    with zipfile.ZipFile(path) as z:
        for name in sorted(z.namelist()):
            if not name.endswith(".so"):
                continue
            # Works for both an AAB (base/lib/<abi>/) and a raw AAR (jni/<abi>/).
            parts = name.split("/")
            if not any(a in parts for a in ("arm64-v8a", "x86_64")):
                continue          # 16 KB pages are a 64-bit concern only
            aligns = list(loads(z.read(name)))
            if not aligns:
                continue
            worst = min(aligns)
            ok = worst >= NEED
            if not ok:
                bad += 1
            print(f"{'OK ' if ok else 'BAD'}  {worst:>6}  {name}")
    print(f"\n{bad} library(ies) below {NEED}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
