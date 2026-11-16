"""Build the fixed-interface Pixiewps executable for Android, without Gradle downloads."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ndk', type=Path, required=True)
    args = parser.parse_args()
    vendor = ROOT / 'third_party/pixiewps'
    source = vendor / 'src'
    clang = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
    tfm = re.findall(r'\./([A-Za-z0-9_]+\.c)', (source / 'crypto/tfm/sources.mak').read_text())
    prefix_maps = ['-ffile-prefix-map=' + prefix + '=.' for prefix in (str(ROOT), ROOT.as_posix())]
    records = []
    for abi, target in [('arm64-v8a', 'aarch64-linux-android30'), ('x86_64', 'x86_64-linux-android30')]:
        build = ROOT / 'build/pixie' / abi
        build.mkdir(parents=True, exist_ok=True)
        destination = ROOT / 'app/src/main/jniLibs' / abi / 'libbender_pixie.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        flags = [str(clang), '--target=' + target, *prefix_maps, '-O2', '-std=gnu99', '-fPIE', '-D_GNU_SOURCE',
                 '-I' + str(source / 'crypto/tfm'), '-I' + str(source / 'crypto/tc')]
        obj = build / 'pixiewps.o'
        subprocess.run(flags + ['-Dmain=pixie_main', '-c', str(source / 'pixiewps.c'), '-o', str(obj)], check=True)
        sources = [str(source / 'crypto/tfm' / name) for name in tfm]
        sources += [str(source / 'crypto/tc' / name) for name in ('aes.c', 'aes_cbc.c')]
        subprocess.run(flags + ['-pie', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
                                str(ROOT / 'tools/pixie_stdin.c'), str(obj), *sources, '-o', str(destination)], check=True)
        records.append({'abi': abi, 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(), 'bytes': destination.stat().st_size})
    revision = subprocess.check_output(['git', '-C', str(vendor), 'rev-parse', 'HEAD'], text=True).strip()
    result = {'source': 'https://github.com/wiire-a/pixiewps', 'revision': revision,
              'ndk': args.ndk.name, 'outputs': records, 'license': 'GPL-3.0-or-later',
              'source_path_mapping': 'project root replaced by . in compiler-generated file strings',
              'interface': 'six hex fields on stdin; 1 worker; native CPU and app wall deadlines'}
    (ROOT / 'third_party/pixiewps-build.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
