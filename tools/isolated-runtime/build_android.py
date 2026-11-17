"""Offline ARM64 Android build; requires an already installed Android NDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--record', required=True, type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().with_name('isolated-runtime.c')
    host = 'windows-x86_64' if os.name == 'nt' else 'linux-x86_64'
    clang = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('clang.exe' if os.name == 'nt' else 'clang')
    flags = ['--target=aarch64-linux-android30', '-std=c11', '-O2', '-Wall', '-Wextra', '-Werror',
             '-fPIE', '-pie', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384',
             '-ffile-prefix-map=' + str(source.parent) + '=.']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(clang), *flags, str(source), '-o', str(args.output)], check=True)
    data = dict(schema_version=1, ndk=args.ndk.name, target='aarch64-linux-android30',
                source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                binary_sha256=hashlib.sha256(args.output.read_bytes()).hexdigest(),
                binary_bytes=args.output.stat().st_size,
                handset_execution='not tested', flags=flags[:-1]+['-ffile-prefix-map=<source directory>=.'])
    args.record.write_bytes((json.dumps(data, indent=2)+'\n').encode())
    print(json.dumps(data))


if __name__ == '__main__':
    main()
