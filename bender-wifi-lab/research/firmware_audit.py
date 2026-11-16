"""Read-only pinned artifact audit; never patches or loads firmware."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

ROM_ADDRESSES = [0x1f468ec,0x1e1951c,0x1e2d7b8,0x1e19844,0x1e1eba8,0x1e19200]
CHECKS = {
 "nested_context": [("19ef5cc", "r24 = #0x0"),("19ef5dc", "if (!p0.new) r24 = memw(r21+#0x20)")],
 "bit_zero_reject": [("1998030", "r2 = memub(r1+#0x56)"),("1998034", "tstbit(r2,#0); if (p0.new) jump:t 0x1998144")],
 "second_allocation_zeroed": [("18e15e4", "r17 = r0"),("18e160c", "call 0x1f468ec"),("18e161c", "memw(r17+#0x0) = r0"),("18e1620", "call 0x1310ec0"),("18e1624", "r1 = #0x0"),("18e1628", "r2 = #0x23c")]
}
def file_backed_addresses(data):
    if data[:6] != b"\x7fELF\x01\x01":
        raise ValueError("Expected ELF32 little-endian")
    offset=struct.unpack_from("<I",data,28)[0]
    entsize,count=struct.unpack_from("<HH",data,42)
    if entsize < 32 or offset+entsize*count > len(data):
        raise ValueError("Invalid program headers")
    spans=[]
    for i in range(count):
        kind,off,va,pa,filesz,memsz,flags,align=struct.unpack_from("<8I",data,offset+i*entsize)
        if kind == 1 and filesz:
            if off+filesz > len(data): raise ValueError("Invalid file-backed segment")
            spans.append((va,va+filesz))
    return spans

def audit(manifest):
    verified={}; contents={}
    for name,record in manifest["artifacts"].items():
        data=Path(record["path"]).read_bytes()
        actual=hashlib.sha256(data).hexdigest()
        verified[name]={"sha256":actual,"match":actual == record["sha256"]}
        contents[name]=data
    if not all(v["match"] for v in verified.values()):
        return {"status":"rejected_hash_mismatch","artifacts":verified}
    lines={}
    for line in contents["disassembly"].decode("utf-8").splitlines():
        m=re.match(r"\s*([0-9a-f]+):\s*(.*)",line)
        if m: lines[m[1]]=re.sub(r"\s+"," ",m[2])
    predicates={key:all(addr in lines and fragment in lines[addr] for addr,fragment in terms) for key,terms in CHECKS.items()}
    spans=file_backed_addresses(contents["elf"])
    missing=[hex(addr) for addr in ROM_ADDRESSES if not any(lo<=addr<hi for lo,hi in spans)]
    return {"status":"static_checks_passed" if all(predicates.values()) and len(missing)==len(ROM_ADDRESSES) else "static_checks_failed",
      "artifacts":verified,"predicates":predicates,"rom_not_file_backed":missing,
      "limits":["Retained pinned disassembly; this run does not regenerate instructions.","C=*(P+0x20). C+0x56 bit0 set rejects; live value and failing branch remain unknown.","The explicit zero of 0x23c bytes applies to the second allocation, not C.","Static predicates do not authorize patching, loading or another unchanged live retry."]}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--manifest",type=Path,default=Path(__file__).with_name("firmware-artifacts.json"))
    a=p.parse_args()
    try: result=audit(json.loads(a.manifest.read_text()))
    except (OSError,ValueError,KeyError,struct.error) as exc:
        result={"status":"unavailable","error_type":type(exc).__name__}
    print(json.dumps(result,indent=2))
    return 0 if result["status"] == "static_checks_passed" else 1
if __name__ == "__main__": raise SystemExit(main())
