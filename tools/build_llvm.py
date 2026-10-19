#!/usr/bin/env python3
"""Build the pinned LLVM release the JIT module links statically.

Only what the JIT needs is built: the X86 and AArch64 code generators and the
ORC JIT libraries, as static Release libraries with RTTI, without tools,
tests, examples or optional system libraries. The source archive is checked
against its pinned SHA-256 (the release's signature was verified when the
digest was pinned); --offline uses the cached archive only. The result is
installed into build/deps/llvm-<version>-<triple>/ and rebuilt only when the
archive or the configuration changes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
DEPS = ROOT / 'build' / 'deps'
VERSION = '18.1.8'
ARCHIVE = f'llvm-project-{VERSION}.src.tar.xz'
URL = f'https://github.com/llvm/llvm-project/releases/download/llvmorg-{VERSION}/{ARCHIVE}'
SHA256 = '0b58557a6d32ceee97c8d533a59b9212d87e0fc4d2833924eb6c611247db2f2a'
OPTIONS = {
    'CMAKE_BUILD_TYPE': 'Release',
    'LLVM_TARGETS_TO_BUILD': 'X86;AArch64',
    'LLVM_ENABLE_PROJECTS': '',
    'LLVM_ENABLE_RTTI': 'ON',
    'LLVM_ENABLE_PIC': 'ON',
    'LLVM_ENABLE_ASSERTIONS': 'OFF',
    'LLVM_BUILD_TOOLS': 'OFF',
    'LLVM_BUILD_UTILS': 'OFF',
    'LLVM_INCLUDE_TOOLS': 'OFF',
    'LLVM_INCLUDE_UTILS': 'OFF',
    'LLVM_INCLUDE_TESTS': 'OFF',
    'LLVM_INCLUDE_EXAMPLES': 'OFF',
    'LLVM_INCLUDE_BENCHMARKS': 'OFF',
    'LLVM_INCLUDE_DOCS': 'OFF',
    'LLVM_ENABLE_BINDINGS': 'OFF',
    'LLVM_ENABLE_ZLIB': 'OFF',
    'LLVM_ENABLE_ZSTD': 'OFF',
    'LLVM_ENABLE_LIBXML2': 'OFF',
    'LLVM_ENABLE_TERMINFO': 'OFF',
    'LLVM_ENABLE_LIBEDIT': 'OFF',
    'LLVM_ENABLE_LIBPFM': 'OFF',
    'LLVM_ENABLE_CURL': 'OFF',
    'LLVM_ENABLE_HTTPLIB': 'OFF',
    'LLVM_BUILD_LLVM_DYLIB': 'OFF',
}


def archive(offline):
    path = DEPS / ARCHIVE
    if path.is_file() and digest(path) == SHA256:
        return path
    if offline:
        raise RuntimeError(f'Missing cached {ARCHIVE}; rerun without --offline or put the verified archive in {DEPS}')
    print(f'Downloading {ARCHIVE}', flush=True)
    DEPS.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix('.partial')
    with urllib.request.urlopen(URL, timeout=120) as response, partial.open('wb') as output:
        shutil.copyfileobj(response, output)
    if digest(partial) != SHA256:
        partial.unlink()
        raise RuntimeError(f'Checksum mismatch for {ARCHIVE}')
    partial.replace(path)
    return path


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as data:
        for block in iter(lambda: data.read(1 << 20), b''):
            hasher.update(block)
    return hasher.hexdigest()


def extract(path):
    # The llvm/ and cmake/ trees are all a library build reads.
    source = DEPS / f'llvm-project-{VERSION}.src'
    marker = source / '.extracted'
    if marker.is_file() and marker.read_text() == SHA256:
        return source
    if source.exists():
        shutil.rmtree(source)
    prefix = f'llvm-project-{VERSION}.src/'
    with tarfile.open(path) as bundle:
        members = [member for member in bundle.getmembers()
                   if member.name.startswith((prefix + 'llvm/', prefix + 'cmake/', prefix + 'third-party/'))
                   and not member.name.startswith((prefix + 'llvm/test/', prefix + 'llvm/unittests/', prefix + 'llvm/docs/'))]
        if sys.version_info >= (3, 12):
            bundle.extractall(DEPS, members=members, filter='data')
        else:
            bundle.extractall(DEPS, members=members)
    marker.write_text(SHA256)
    return source


def host_triple():
    machine = os.uname().machine
    system = sys.platform
    arch = {'x86_64': 'x86_64', 'amd64': 'x86_64', 'aarch64': 'aarch64', 'arm64': 'aarch64'}.get(machine, machine)
    return f'{arch}-{"apple-darwin" if system == "darwin" else "linux-gnu"}'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--triple', default=host_triple(), help='target triple (default: this host)')
    parser.add_argument('--toolchain', type=Path, help='CMake toolchain file for a cross build')
    parser.add_argument('--tablegen', type=Path, help="host llvm-tblgen for a cross build")
    parser.add_argument('--jobs', type=int, default=os.cpu_count() or 2)
    args = parser.parse_args()
    prefix = DEPS / f'llvm-{VERSION}-{args.triple}'
    options = dict(OPTIONS)
    if args.toolchain:
        options['CMAKE_TOOLCHAIN_FILE'] = str(args.toolchain.resolve())
        options['LLVM_HOST_TRIPLE'] = args.triple
        options['LLVM_DEFAULT_TARGET_TRIPLE'] = args.triple
    if args.tablegen:
        options['LLVM_TABLEGEN'] = str(args.tablegen.resolve())
    stamp = {'sha256': SHA256, 'triple': args.triple, 'options': options}
    stamp_file = prefix / 'azscript-llvm.json'
    if stamp_file.is_file() and json.loads(stamp_file.read_text()) == stamp:
        print(f'LLVM {VERSION} for {args.triple} is current: {prefix}')
        return 0
    source = extract(archive(args.offline))
    build = DEPS / f'llvm-build-{args.triple}'
    if build.exists():
        shutil.rmtree(build)
    if prefix.exists():
        shutil.rmtree(prefix)
    generator = ['-G', 'Ninja'] if shutil.which('ninja') else []
    configure = ['cmake', *generator, '-S', source / 'llvm', '-B', build, f'-DCMAKE_INSTALL_PREFIX={prefix}',
                 *(f'-D{key}={value}' for key, value in options.items())]
    subprocess.run(list(map(str, configure)), check=True)
    subprocess.run(['cmake', '--build', str(build), '--target', 'install', '--parallel', str(args.jobs)], check=True)
    # The host build's tablegen serves later cross builds.
    tblgen = build / 'bin' / 'llvm-tblgen'
    if tblgen.is_file():
        (prefix / 'bin').mkdir(exist_ok=True)
        shutil.copy2(tblgen, prefix / 'bin' / 'llvm-tblgen')
    stamp_file.write_text(json.dumps(stamp, indent=1) + '\n')
    shutil.rmtree(build)
    print(f'LLVM {VERSION} for {args.triple} installed: {prefix}')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f'LLVM build failed: {error}', file=sys.stderr)
        sys.exit(1)
