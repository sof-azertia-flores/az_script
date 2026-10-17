#!/usr/bin/env python3
"""Check the runtime ABI at plugin registration, linking, and package selection."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

GOOD_PLUGIN = r'''
#include <azscript/extern_library.hpp>
#include <cstdio>
#include <cstdlib>
class Plugin final : public azertian::executor {
public:
    int namespace_name() override { return 0x1234; }
    std::shared_ptr<azertian::function> getiFunction(int) override { return nullptr; }
};
AZSCRIPT_EXTERN_ENTRY {
    if (auto file = std::fopen(std::getenv("AZSCRIPT_ABI_MARKER"), "w")) {
        std::fputs("registered", file); std::fclose(file);
    }
    azertian::registerExecutor(std::make_shared<Plugin>());
}
'''

LEGACY_PLUGIN = r'''
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#if defined(_WIN32)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif
extern "C" EXPORT void azscript_load_extern() {
    if (auto file = std::fopen(std::getenv("AZSCRIPT_ABI_MARKER"), "w")) {
        std::fputs("must not register", file); std::fclose(file);
    }
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--signer', type=Path, required=True)
    parser.add_argument('--libdir', type=Path, required=True)
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    windows = sys.platform == 'win32'
    suffix = '.dll' if windows else '.dylib' if sys.platform == 'darwin' else '.so'
    helper = ROOT / ('tools/distribution/compile_extern_lib.cmd' if windows
                     else 'tools/distribution/compile_extern_lib.sh')
    checks = 0

    def check(condition, detail):
        nonlocal checks
        if not condition:
            raise AssertionError(detail)
        checks += 1

    env = os.environ.copy()
    env['AZSCRIPT_INCLUDE'] = str(ROOT / 'include')
    env['AZSCRIPT_LIBDIR'] = str(args.libdir.resolve())
    for name in ('LD_LIBRARY_PATH', 'DYLD_LIBRARY_PATH', 'PATH'):
        env[name] = str(args.libdir.resolve()) + os.pathsep + env.get(name, '')

    with tempfile.TemporaryDirectory(prefix='runtime-abi-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)
        marker = work / 'registration.marker'
        env['AZSCRIPT_ABI_MARKER'] = str(marker)

        def run(command):
            return subprocess.run(list(map(str, command)), cwd=work, env=env,
                                  capture_output=True, text=True, timeout=60)

        def success(command):
            result = run(command)
            check(result.returncode == 0, (command, result.stdout, result.stderr))
            return result

        success([args.signer, 'genkey', '--private', work / 'private.pem',
                 '--public', work / 'trusted_key.pem'])

        for name, content, expected_error in (
                ('current', GOOD_PLUGIN, None),
                ('legacy', LEGACY_PLUGIN, 'missing azscript_extern_runtime_abi'),
                ('wrong', LEGACY_PLUGIN + '\nextern "C" EXPORT std::uint32_t '
                 'azscript_extern_runtime_abi() noexcept { return 2; }\n',
                 'runtime ABI mismatch: expected 1, got 2')):
            source = work / (name + '.cpp')
            source.write_text(content, encoding='utf-8')
            command = [helper, '-o', work / name, source]
            if windows:
                command = ['cmd', '/c', *command]
            success(command)
            library = work / (name + suffix)
            success([args.signer, 'sign', '--key', work / 'private.pem', '--library', library])
            script = work / (name + '.azs')
            script.write_text('void main(){load_extern_library("' + name + '");}\n', encoding='utf-8')
            binary = work / (name + '.exec.abd')
            success(['java', '-cp', args.classpath, 'azertia.Main', 'compile', script, '-o', binary])
            marker.unlink(missing_ok=True)
            result = run([args.runner, binary])
            if expected_error is None:
                check(result.returncode == 0, result.stderr)
                check(marker.read_text(encoding='utf-8') == 'registered', 'current ABI entry was not invoked')
            else:
                check(result.returncode != 0 and expected_error in result.stderr, result.stderr)
                check(not marker.exists(), 'ABI rejection happened after plugin registration')
                # ABI metadata is part of the signed bytes; an invalid signature
                # must still fail before native mapping or the ABI check.
                signature = work / (name + '.signature')
                signature.write_bytes(b'invalid signature')
                unsigned = run([args.runner, binary])
                check(unsigned.returncode != 0 and 'signature verification failed' in unsigned.stderr,
                      unsigned.stderr)
                check(not marker.exists(), 'invalid signature reached plugin registration')

        # The header's ABI guard is automatic: even a consumer that never calls
        # require_runtime_abi explicitly must reference the versioned link symbol.
        host = work / 'host.cpp'
        host.write_text('#include <azscript/runtime_abi.hpp>\n'
                        'int main(){return azscript_runtime_abi()==AZSCRIPT_RUNTIME_ABI?0:1;}\n',
                        encoding='utf-8')
        fake = work / 'legacy_runtime.cpp'
        fake.write_text('extern "C" unsigned azscript_runtime_abi() noexcept {return 0;}\n',
                        encoding='utf-8')
        executable = work / ('host.exe' if windows else 'host')
        if windows:
            base = ['cl', '/nologo', '/std:c++20', '/EHsc', '/I' + str(ROOT / 'include')]
            rejected = run([*base, host, fake, '/Fe:' + str(executable)])
            current = [*base, host, '/Fe:' + str(executable), '/link',
                       '/LIBPATH:' + str(args.libdir), 'abdInvoker-1.lib']
        else:
            base = [os.environ.get('CXX', 'c++'), '-std=c++20', '-I', ROOT / 'include']
            rejected = run([*base, host, fake, '-o', executable])
            current = [*base, host, '-L', args.libdir, '-labdInvoker-1',
                       '-Wl,-rpath,' + str(args.libdir.resolve()), '-o', executable]
            if args.sanitize:
                current += ['-fsanitize=address,undefined']
        check(rejected.returncode != 0 and 'azscript_require_runtime_abi_1' in rejected.stdout + rejected.stderr,
              ('legacy runtime linked to ABI 1 headers', rejected.stdout, rejected.stderr))
        success(current)
        success([executable])

        # Deliberately falsified runtime metadata is rejected before main, even
        # if the library exposes the versioned symbol. This uses only test code.
        fake.write_text('extern "C" unsigned azscript_runtime_abi() noexcept {return 2;}\n'
                        'extern "C" void azscript_require_runtime_abi_1() noexcept {}\n',
                        encoding='utf-8')
        if windows:
            success([*base, host, fake, '/Fe:' + str(executable)])
        else:
            success([*base, host, fake, '-o', executable])
        mismatch = run([executable])
        check(mismatch.returncode != 0 and 'runtime ABI mismatch' in mismatch.stderr,
              ('wrong runtime ABI reached main', mismatch.stdout, mismatch.stderr))

        version_file = args.libdir.resolve().parent / 'AzScriptConfigVersion.cmake'
        check(version_file.is_file(), f'missing {version_file}')
        for minor, compatible in ((2, False), (3, True), (4, False)):
            cmake = work / 'package-version.cmake'
            cmake.write_text(
                f'set(PACKAGE_FIND_VERSION "0.{minor}.0")\n'
                'set(PACKAGE_FIND_VERSION_MAJOR 0)\n'
                f'set(PACKAGE_FIND_VERSION_MINOR {minor})\n'
                f'include("{version_file.as_posix()}")\n'
                f'if({"NOT " if compatible else ""}PACKAGE_VERSION_COMPATIBLE)\n'
                '  message(FATAL_ERROR "Incorrect runtime package compatibility")\n'
                'endif()\n', encoding='utf-8')
            success(['cmake', '-P', cmake])
    print(f'Runtime ABI checks passed: {checks}')


if __name__ == '__main__':
    main()
