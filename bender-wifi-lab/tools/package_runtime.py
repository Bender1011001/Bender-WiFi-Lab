"""Package a configured, authenticated Debian ARM64 rootfs for offline app setup.

Run on Linux after debootstrap and security updates. Never package host mounts,
device nodes, private research data or build caches. Retain package copyrights.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile


def package(root, output):
    root = root.resolve(strict=True)
    if root.parent != Path('/var/tmp') or not root.name.startswith('bender-runtime-'):
        raise ValueError('Use a dedicated /var/tmp/bender-runtime-* build root')
    mountpoints = [line.split()[4] for line in Path('/proc/self/mountinfo').read_text().splitlines()]
    if any(point == str(root) or point.startswith(str(root) + '/') for point in mountpoints):
        raise ValueError('Unmount build-root filesystems before packaging')
    arch = subprocess.check_output(['chroot', str(root), 'dpkg', '--print-architecture'], text=True).strip()
    if arch != 'arm64':
        raise ValueError('Runtime must be ARM64')
    packages = subprocess.check_output(['chroot', str(root), 'dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${Architecture}\n'], text=True)
    output.mkdir(parents=True, exist_ok=True)
    provenance = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (root/'var/lib/apt/lists').glob('*InRelease')}
    # Exact fixed child paths beneath the verified, unmounted build root.
    for relative in ('var/cache/apt/archives', 'var/lib/apt/lists', 'tmp', 'var/log', 'dev', 'proc', 'sys'):
        path = root / relative
        if path.is_symlink():
            raise ValueError('Unexpected symlink at cleanup path: ' + relative)
        if path.exists():
            shutil.rmtree(path)
        path.mkdir(parents=True, mode=0o755)
    (root/'tmp').chmod(0o1777)
    (root/'etc/resolv.conf').unlink(missing_ok=True)
    (root/'etc/resolv.conf').write_text('# Bender runtime operates offline.\n')
    (root/'etc/machine-id').write_text('')
    (root/'etc/hostname').write_text('bender-runtime\n')
    (root/'etc/hosts').write_text('127.0.0.1 localhost bender-runtime\n::1 localhost\n')
    for path in root.rglob('*'):
        if path.is_symlink():
            link = os.readlink(path)
            if link.startswith('/'):
                # Ensure extraction cannot follow a rootfs symlink into Android.
                destination = root / link.lstrip('/')
                path.unlink()
                path.symlink_to(os.path.relpath(destination, path.parent))
        elif path.is_file():
            path.chmod(path.stat().st_mode & 0o777)  # No setuid/setgid binaries.
    (root/'usr/share/bender').mkdir(parents=True, exist_ok=True)
    (root/'usr/share/bender/packages.tsv').write_text(packages)
    (root/'usr/share/bender/NOTICE').write_text('Debian ARM64 runtime for Bender Wi-Fi Lab.\nPackage copyrights and licenses are retained under /usr/share/doc/<package>/copyright.\nSources: https://deb.debian.org/debian and https://security.debian.org/debian-security.\nExact binary package versions are in packages.tsv; Debian publishes matching source packages.\n')
    entries = sorted(root.rglob('*'))
    installed = sum(p.stat().st_size for p in entries if p.is_file() and not p.is_symlink())
    archive = output/'rootfs.tar.gz'
    with tarfile.open(archive, 'w:gz', format=tarfile.GNU_FORMAT, compresslevel=6) as tar:
        for path in entries:
            member = tar.gettarinfo(str(path), arcname=path.relative_to(root).as_posix())
            if member.islnk():
                member.type = tarfile.REGTYPE
                member.linkname = ''
                member.size = path.stat().st_size
            if not (member.isfile() or member.isdir() or member.issym()):
                raise ValueError('Unsupported archive entry: ' + member.name)
            if member.name.startswith('/') or '..' in Path(member.name).parts:
                raise ValueError('Unsafe archive path')
            if member.issym():
                target = (path.parent/member.linkname).resolve(strict=False)
                if not target.is_relative_to(root):
                    raise ValueError('Symlink escapes runtime: ' + member.name)
            member.uid = member.gid = 0
            member.uname = member.gname = 'root'
            member.mtime = 0
            member.mode &= 0o777
            if member.isfile():
                with path.open('rb') as data:
                    tar.addfile(member, data)
            else:
                tar.addfile(member)
    manifest = dict(schema_version=1, id='debian12-arm64-v1', arch='arm64', archive_bytes=archive.stat().st_size,
                    installed_bytes=installed, sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),
                    signed_repository_metadata_sha256=provenance,
                    packages=[dict(zip(('name','version','arch'), line.split('\t'))) for line in packages.splitlines()],
                    licenses='Included at usr/share/doc/*/copyright inside the runtime')
    (output/'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(json.dumps({key: manifest[key] for key in ('id','archive_bytes','installed_bytes','sha256')}))


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--rootfs',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    package(args.rootfs,args.output)
