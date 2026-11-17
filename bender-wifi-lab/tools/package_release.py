"""Package a verified local build; refuse a stale APK or incomplete test set."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_archive_entries(root=ROOT):
    root = root.resolve(strict=True)
    excluded = {'.git', '.gradle', 'build', 'dist', 'validation', '__pycache__', '.pytest_cache'}
    def walk(directory, ancestors):
        for source in sorted(directory.iterdir()):
            relative = source.relative_to(root)
            if (any(part in excluded for part in relative.parts)
                    or source.name == 'local.properties'
                    or source.suffix in {'.pyc', '.log', '.pcap', '.cap', '.bin', '.elf'}):
                continue
            resolved = source.resolve(strict=True)
            if not resolved.is_relative_to(root):
                raise ValueError('Source symlink escapes project: ' + relative.as_posix())
            if source.is_dir():
                if resolved in ancestors:
                    raise ValueError('Source directory alias cycle: ' + relative.as_posix())
                yield from walk(source, ancestors | {resolved})
            elif source.is_file():
                # Dereference internal aliases at their original include paths.
                yield source, Path('bender-wifi-lab') / relative
    yield from walk(root, {root})


def verify_archive_sources(path, built):
    with zipfile.ZipFile(path) as archive:
        for name, digest in built['sha256'].items():
            entry = 'bender-wifi-lab/' + name
            assert entry in archive.namelist(), 'Source archive missing build input: ' + name
            assert hashlib.sha256(archive.read(entry)).hexdigest() == digest, 'Archived build input differs: ' + name


def source_inputs():
    files = []
    for name in ('app/src', 'runtime', 'engine/bender_engine', 'tests', 'gradle/wrapper', 'third_party/pixiewps/src'):
        files.extend((ROOT / name).rglob('*'))
    files.extend((ROOT / 'research').glob('*.spec.json'))
    files.extend(ROOT / name for name in ('third_party/pixiewps/LICENSE.md','third_party/pixiewps-build.json','tools/build_pixie.py','tools/pixie_stdin.c','tools/build_device_vendors.py'))
    for name in ('third_party/hostap/src', 'third_party/hostap/wpa_supplicant', 'third_party/libnl-tiny/include'):
        files.extend((ROOT/name).rglob('*'))
    files.extend((ROOT/'third_party/libnl-tiny').glob('*.c'))
    files.extend(ROOT/name for name in ('third_party/hostap/COPYING','third_party/LGPL-2.1.txt','third_party/wps-build.json','tools/build_wps.py','tools/wps_hooks.c'))
    files.extend(ROOT / name for name in ('app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'runtime-bundle/manifest.json', 'tools/package_runtime.py'))
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in files
            if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc' and p.name != '.gitignore'}


def verified_sources(apk):
    with zipfile.ZipFile(apk) as archive:
        built = json.loads(archive.read('assets/build-source-manifest.json'))
        assert built['schema_version'] == 1
        current = source_inputs()
        assert built['sha256'] == current, 'APK source manifest differs from current code/tests; rebuild'
        assets = []
        for directory, prefix in ((ROOT/'runtime', 'assets/runtime/'), (ROOT/'engine/bender_engine', 'assets/runtime/bender_engine/')):
            for source in directory.rglob('*'):
                if not source.is_file() or '__pycache__' in source.parts or source.suffix == '.pyc':
                    continue
                entry = prefix + source.relative_to(directory).as_posix()
                assert archive.read(entry) == source.read_bytes(), f'Packaged asset differs: {entry}'
                assets.append(entry)
        for source in (ROOT/'app/src/main/jniLibs').rglob('*.so'):
            entry='lib/'+source.relative_to(ROOT/'app/src/main/jniLibs').as_posix()
            assert archive.read(entry)==source.read_bytes(), f'Packaged native executable differs: {entry}'
            assets.append(entry)
        runtime = json.loads(archive.read('assets/runtime-image/manifest.json'))
        assert archive.read('assets/runtime-image/manifest.json') == (ROOT/'runtime-bundle/manifest.json').read_bytes()
        image = archive.getinfo('assets/runtime-image/rootfs.bundle')
        assert image.file_size == runtime['archive_bytes']
        digest = hashlib.sha256()
        with archive.open(image) as stream:
            while block := stream.read(1024*1024):
                digest.update(block)
        assert digest.hexdigest() == runtime['sha256'], 'Packaged runtime hash mismatch'
        assets.extend(('assets/runtime-image/manifest.json', 'assets/runtime-image/rootfs.bundle'))
        return built, assets


def python_tests(path):
    text = path.read_text()
    match = re.search(r'Ran (\d+) tests? in [\d.]+s\s+OK\s*$', text)
    assert match, f'Missing complete, skip-free Python pass: {path}'
    return int(match[1])


def emulator_tests(path):
    text = path.read_text()
    match = re.search(r'OK \((\d+) tests?\)', text)
    assert match and 'FAILURES' not in text and 'INSTRUMENTATION_STATUS_CODE: -3' not in text and 'INSTRUMENTATION_STATUS_CODE: -4' not in text, f'Incomplete or skipped emulator tests: {path}'
    return int(match[1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-dir', type=Path, required=True)
    parser.add_argument('--handset-report', type=Path)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    apk = ROOT/'app/build/outputs/apk/debug/app-debug.apk'
    built, assets = verified_sources(apk)
    if args.check_only:
        print(f'APK matches {len(built["sha256"])} source inputs and {len(assets)} executable assets')
        return
    validation = args.validation_dir.resolve()
    build_text = (validation/'android.log').read_text()
    assert 'BUILD SUCCESSFUL' in build_text and 'BUILD FAILED' not in build_text
    suites = [ET.parse(p).getroot() for p in (ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml')]
    assert suites and all(all(int(s.get(k, 0)) == 0 for k in ('failures', 'errors', 'skipped')) for s in suites)
    counts = {'engine_tests': python_tests(validation/'engine.log'),
              'runtime_tests': python_tests(validation/'runtime.log'),
              'android_unit_tests': sum(int(s.get('tests')) for s in suites),
              'emulator_tests': emulator_tests(validation/'emulator-data-ui.log') + emulator_tests(validation/'emulator-crypto.log')}
    tested = json.loads((validation/'tested-build.json').read_text())
    assert tested['apk_sha256'] == sha(apk), 'Emulator result belongs to another APK'
    retained_path = validation/'retained-checks.json'
    retained = json.loads(retained_path.read_text()) if retained_path.is_file() else []
    for check in retained:
        assert check['rerun'] is False
        assert (validation/check['log']).read_bytes() == (ROOT/check['retained_from']).read_bytes(), 'Retained log differs from its source'
        for name, expected in check['unchanged_input_hashes'].items():
            path = (ROOT/name).resolve()
            assert path.is_relative_to(ROOT.resolve()) and sha(path) == expected, f'Retained test input changed: {name}'
    handset = None
    if args.handset_report:
        handset = json.loads(args.handset_report.read_text(encoding='utf-8'))
        assert handset.get('status') == 'completed', 'Handset validation is incomplete'
        assert handset.get('apk_sha256') == sha(apk), 'Handset result belongs to another APK'
        assert type(handset.get('radio_tested')) is bool, 'Handset report must declare radio-test scope'
    metadata = json.loads((apk.parent/'output-metadata.json').read_text())
    version = metadata['elements'][0]['versionName']
    dist = ROOT/'dist'
    dist.mkdir(exist_ok=True)
    destination = dist/f'Bender-WiFi-Lab-{version}.apk'
    source_zip = dist/f'Bender-WiFi-Lab-{version}-source.zip'
    shutil.copyfile(apk, destination)
    with zipfile.ZipFile(source_zip, 'w', zipfile.ZIP_DEFLATED) as archive:
        for source, name in source_archive_entries():
            archive.write(source, name)
    verify_archive_sources(source_zip, built)
    def entry(path):
        return {'file': path.name, 'bytes': path.stat().st_size, 'sha256': sha(path)}
    manifest = {'package': metadata['applicationId'], 'version': version,
                'apk': entry(destination), 'source': entry(source_zip),
                'verified_packaged_assets': assets, 'build_source_manifest': built,
                'validation': {**counts, 'arm64_engine_tests': python_tests(validation/'arm64-engine.log'),
                               'linux_installer_tests': python_tests(validation/'linux-installer.log'), 'failures': 0, 'skips_final_set': 0,
                               'retained_checks': retained,
                               'handset_tested': handset is not None,
                               'radio_tested_this_pass': handset['radio_tested'] if handset else False},
                'signing': 'Android debug key; personal research preview'}
    if handset is not None:
        manifest['handset_validation'] = handset
    content = json.dumps(manifest, indent=2)+'\n'
    (dist/f'manifest-{version}.json').write_text(content)
    (dist/'manifest.json').write_text(content)
    print(json.dumps({'apk': manifest['apk'], 'source': manifest['source'], 'tests': counts}, indent=2))


if __name__ == '__main__':
    main()
