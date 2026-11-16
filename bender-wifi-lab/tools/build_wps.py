"""Build minimal managed WPS supplicant with fixed memory-only protocol hooks.

Requires Windows Android NDK and WSL Ubuntu make/Python. No downloaded packages
or changes to the upstream checkout. Sources and patches ship with the app.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import shlex
import subprocess
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1]
HOSTAP_REV = 'ab49c33aef291e4d9cabe9a16f535bca40569a10'
NL_REV = '40493a655d8caa2ccf5206dde1e733abe2920432'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def wsl(path):
    s = Path(path).resolve().as_posix()
    return '/mnt/' + s[0].lower() + s[2:]


def replace_once(path, old, new):
    text = path.read_text(encoding='utf-8')
    if text.count(old) != 1:
        raise ValueError(f'Patch anchor changed: {path.name}')
    path.write_text(text.replace(old, new), encoding='utf-8', newline='\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--abi', choices=('arm64-v8a', 'x86_64'), action='append')
    args = parser.parse_args()
    vendor = ROOT / 'third_party/hostap'
    nl = ROOT / 'third_party/libnl-tiny'
    for source, expected in ((vendor, HOSTAP_REV), (nl, NL_REV)):
        if (source / '.git').exists():
            revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
            if revision != expected: raise ValueError(f'Unexpected upstream revision: {source.name}')
            if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'], text=True).strip():
                raise ValueError(f'Upstream checkout modified: {source.name}')
    bin_dir = args.ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    prefix_maps = ['-ffile-prefix-map=' + prefix + '=.' for prefix in (str(ROOT), ROOT.as_posix())]
    outputs = []
    for abi in args.abi or ['arm64-v8a', 'x86_64']:
        target = {'arm64-v8a': 'aarch64-linux-android30', 'x86_64': 'x86_64-linux-android30'}[abi]
        build = ROOT / 'build/wps' / abi
        build.mkdir(parents=True, exist_ok=True)
        objects = []
        for name in re.search(r'SET\(SOURCES(.*?)\)', (nl / 'CMakeLists.txt').read_text(), re.S)[1].split():
            obj = build / (name + '.o')
            subprocess.run([str(bin_dir / 'clang.exe'), '--target=' + target, *prefix_maps, '-O2', '-fPIE', '-D_GNU_SOURCE',
                            '-D__LINUX_NETLINK_H', '-include', 'linux/netlink.h',
                            '-I' + str(nl / 'include'), '-c', str(nl / name), '-o', str(obj)], check=True)
            objects.append(str(obj))
        subprocess.run([str(bin_dir / 'llvm-ar.exe'), 'rcs', str(build / 'libnl-tiny.a'), *objects], check=True)
        host = build / 'hostap'
        for name in ('src', 'wpa_supplicant'):
            shutil.copytree(vendor / name, host / name, dirs_exist_ok=True)
        # Bionic already provides these UAPI types. The upstream legacy copy
        # otherwise redefines sockaddr_nl/nlmsghdr after the NDK includes them.
        (host / 'src/drivers/priv_netlink.h').write_text(
            '#include <linux/rtnetlink.h>\n#include <linux/if.h>\n', encoding='utf-8')
        reg = host / 'src/wps/wps_registrar.c'
        replace_once(reg, '\twps->state = SEND_M4;\n\treturn WPS_CONTINUE;',
                     '\tbender_wps_m3(wps);\n\twps->state = SEND_M4;\n\treturn WPS_CONTINUE;')
        replace_once(reg, 'static enum wps_process_res wps_process_m3(',
                     'void bender_wps_m3(struct wps_data *wps);\n\nstatic enum wps_process_res wps_process_m3(')
        replace_once(reg, 'static int wps_process_e_snonce1(struct wps_data *wps, const u8 *e_snonce1)',
                     'void bender_wps_bad_pin(int half);\n\nstatic int wps_process_e_snonce1(struct wps_data *wps, const u8 *e_snonce1)')
        replace_once(reg, '\t\twps_pwd_auth_fail_event(wps->wps, 0, 1, wps->mac_addr_e);',
                     '\t\tbender_wps_bad_pin(1);\n\t\twps_pwd_auth_fail_event(wps->wps, 0, 1, wps->mac_addr_e);')
        replace_once(reg, '\t\twps_pwd_auth_fail_event(wps->wps, 0, 2, wps->mac_addr_e);',
                     '\t\tbender_wps_bad_pin(2);\n\t\twps_pwd_auth_fail_event(wps->wps, 0, 2, wps->mac_addr_e);')
        supp = host / 'wpa_supplicant/wps_supplicant.c'
        replace_once(supp, 'static int wpa_supplicant_wps_cred(void *ctx,',
                     'void bender_wps_credential(const struct wps_credential *cred);\n\nstatic int wpa_supplicant_wps_cred(void *ctx,')
        replace_once(supp, '\tbool add_sae;\n', '\tbool add_sae;\n\n\tbender_wps_credential(cred);\n')
        replace_once(host / 'wpa_supplicant/main.c', 'int main(int argc, char *argv[])', 'int bender_wpa_main(int argc, char *argv[])')
        replace_once(host / 'src/eap_peer/eap.c',
                     '\ttls_register_cert_callback(&tls_certificate_callback);\n\ttls_register_openssl_failure_callback(&tls_openssl_failure_callback);',
                     '/* Android OpenSSL callbacks are unused in this WSC-only internal-crypto build. */')
        replace_once(host / 'src/wps/wps.c', 'struct wps_data * wps_init(const struct wps_config *cfg)',
                     'void bender_wps_begin(void);\n\nstruct wps_data * wps_init(const struct wps_config *cfg)')
        replace_once(host / 'src/wps/wps.c', '\tstruct wps_data *data = os_zalloc(sizeof(*data));',
                     '\tstruct wps_data *data;\n\tbender_wps_begin();\n\tdata = os_zalloc(sizeof(*data));')
        shutil.copyfile(ROOT / 'tools/wps_hooks.c', host / 'wpa_supplicant/bender_hooks.c')
        replace_once(host / 'wpa_supplicant/Makefile', 'OBJS = config.o', 'OBJS = config.o bender_hooks.o')
        config = '''CONFIG_DRIVER_NL80211=y
CONFIG_LIBNL_TINY=y
CONFIG_WPS=y
CONFIG_CTRL_IFACE=y
CONFIG_BACKEND=file
CONFIG_TLS=internal
CONFIG_CRYPTO=internal
CONFIG_INTERNAL_LIBTOMMATH=y
CONFIG_NO_GITVER=y
CONFIG_NO_CONFIG_WRITE=y
'''
        # CFLAGS in the config may be appended by the upstream makefile.
        config += f'CFLAGS = -O2 -fPIE -D_GNU_SOURCE -D__LINUX_NETLINK_H -include linux/netlink.h -I{wsl(nl / "include")}\n'
        config += f'CFLAGS += -I{wsl(host / "src/common")} -I{wsl(host / "wpa_supplicant")}\n'
        config += f'LDFLAGS += -pie -Wl,-z,relro,-z,now -Wl,-z,max-page-size=16384 -L{wsl(build)}\n'
        config_path = build / 'bender.config'
        config_path.write_text(config, encoding='utf-8', newline='\n')
        # Use GNU make only to resolve upstream source lists and defines. The
        # actual compiler runs on Windows; WSL interop is not required.
        plan = subprocess.check_output(['wsl.exe', '-d', 'Ubuntu', '--cd', wsl(host / 'wpa_supplicant'), '--', 'make', '-Bn',
                        'CONFIG_FILE=' + wsl(config_path), 'BUILDDIR=' + wsl(build / 'obj'),
                        'CC=BENDERCC', 'LDO=BENDERCC', 'wpa_supplicant'], text=True, encoding='utf-8')
        commands = [[re.sub(r'/mnt/([a-z])/', lambda m: m[1].upper() + ':/', a)
                     for a in shlex.split(line)[1:] if a != '-lrt'] for line in plan.splitlines() if line.startswith('BENDERCC ')]
        if not commands or '-c' in commands[-1]: raise ValueError('Unexpected upstream build plan')
        executable = build / 'bender-wpa'
        commands[-1][commands[-1].index('-o') + 1] = executable.as_posix()
        def compile_one(item):
            number, command = item
            output = Path(command[command.index('-o') + 1])
            output.parent.mkdir(parents=True, exist_ok=True)
            rsp = build / f'command-{number}.rsp'
            rsp.write_text(subprocess.list2cmdline(['--target=' + target, *prefix_maps, *command]), encoding='utf-8')
            subprocess.run([str(bin_dir / 'clang.exe'), '@' + str(rsp)], cwd=host / 'wpa_supplicant', check=True)
        with ThreadPoolExecutor(max_workers=6) as pool:
            list(pool.map(compile_one, enumerate(commands[:-1])))
        compile_one((len(commands) - 1, commands[-1]))
        destination = ROOT / 'app/src/main/jniLibs' / abi / 'libbender_wpa.so'
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(executable, destination)
        subprocess.run([str(bin_dir / 'llvm-strip.exe'), str(destination)], check=True)
        outputs.append({'abi': abi, 'sha256': digest(destination), 'bytes': destination.stat().st_size})
    result = {'schema_version': 1, 'hostap_revision': HOSTAP_REV, 'libnl_tiny_revision': NL_REV,
              'hostap_source': 'https://android.googlesource.com/platform/external/wpa_supplicant_8',
              'libnl_tiny_source': 'https://git.openwrt.org/project/libnl-tiny.git',
              'ndk': args.ndk.name, 'hooks_sha256': digest(ROOT / 'tools/wps_hooks.c'),
              'source_path_mapping': 'project root replaced by . in compiler-generated file strings',
              'builder_sha256': digest(Path(__file__)), 'outputs': outputs}
    path = ROOT / 'third_party/wps-build.json'
    if args.abi and path.exists():
        previous = json.loads(path.read_text(encoding='utf-8'))
        result['outputs'] += [r for r in previous['outputs'] if r['abi'] not in args.abi]
    path.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
