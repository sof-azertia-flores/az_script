#!/usr/bin/env python3
"""Build and export a relocatable AzScript compiler, runner, C++ SDK and JNI SDK.

Requires Python 3.9+, CMake 3.20+, a C++20 compiler and JDK 17+ (with jlink
unless --system-java is selected). Builds for the current OS and architecture.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile

import build_and_test as build_support

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tools/distribution'
FORMAT = 'AzScriptDistribution'
EXE = '.exe' if os.name == 'nt' else ''


def run(command, **kwargs):
    build_support.run(command, **kwargs)


def java_home(bundle_runtime):
    """Resolve the actual JDK behind launchers such as macOS /usr/bin/java."""
    candidate = (Path(os.environ['JAVA_HOME']) / 'bin' / ('java' + EXE)
                 if os.environ.get('JAVA_HOME') else shutil.which('java'))
    if not candidate:
        raise RuntimeError('JDK 17+ is required; set JAVA_HOME or add Java to PATH')
    result = subprocess.run([str(candidate), '-XshowSettings:properties', '-version'],
                            text=True, capture_output=True, check=True)
    settings = result.stdout + result.stderr
    home_match = re.search(r'^\s*java.home\s*=\s*(.+)$', settings, re.MULTILINE)
    version = re.search(r'^\s*java.specification.version\s*=\s*(\d+)', settings, re.MULTILINE)
    if not home_match or not version or int(version[1]) < 17:
        raise RuntimeError('The selected Java must be JDK 17 or newer')
    home = Path(home_match[1].strip()).resolve()
    required = ['java', 'javac', 'jar'] + (['jlink'] if bundle_runtime else [])
    for name in required:
        if not (home / 'bin' / (name + EXE)).is_file():
            raise RuntimeError(f'Missing JDK tool: {home / "bin" / (name + EXE)}')
    version_details = {}
    for key in ('java.vendor', 'java.runtime.version', 'java.vm.name', 'os.arch'):
        match = re.search(r'^\s*' + re.escape(key) + r'\s*=\s*(.+)$', settings, re.MULTILINE)
        if match:
            version_details[key] = match[1].strip()
    return home, version_details


def check_destination(raw, force):
    destination = Path(raw).expanduser().absolute()
    if destination.is_symlink():
        raise RuntimeError('Output directory must not be a symbolic link')
    destination = destination.resolve()
    if destination == ROOT or destination in ROOT.parents:
        raise RuntimeError('Output must not replace the source tree or one of its parents')
    if destination.exists():
        if not destination.is_dir():
            raise RuntimeError(f'Output already exists and is not a directory: {destination}')
        if any(destination.iterdir()):
            if not force:
                raise RuntimeError(f'Output is not empty: {destination}; use --force to replace a previous export')
            try:
                previous = json.loads((destination / 'manifest.json').read_text(encoding='utf-8'))
            except (OSError, ValueError) as error:
                raise RuntimeError('--force only replaces a previous AzScript export containing manifest.json') from error
            if not isinstance(previous, dict) or previous.get('format') != FORMAT:
                raise RuntimeError('--force only replaces a previous AzScript export')
    return destination


def copy_assets(package):
    (package / 'docs').mkdir()
    for name in ('USAGE.md', 'QUICKSTART.md'):
        shutil.copy2(TEMPLATES / name, package / 'docs' / name)
    for name in ('EXEC_FORMAT.md', 'HINT_LINKING.md', 'EXTERN_LIBRARY.md'):
        shutil.copy2(ROOT / 'docs' / name, package / 'docs' / name)
    for name in ('compile.sh', 'compile.cmd', 'run.sh', 'run.cmd',
                 'compile_extern_lib.sh', 'compile_extern_lib.cmd',
                 'sign_extern_lib.sh', 'sign_extern_lib.cmd'):
        shutil.copy2(TEMPLATES / name, package / name)
        if name.endswith('.sh'):
            (package / name).chmod(0o755)
    shutil.copytree(TEMPLATES / 'examples', package / 'examples')
    for example in (ROOT / 'compiler/examples').glob('*.azs'):
        shutil.copy2(example, package / 'examples' / example.name)
    shutil.copytree(ROOT / 'compiler/examples/multifile', package / 'examples/multifile',
                    ignore=shutil.ignore_patterns('*.abd', '*.json', '.DS_Store'))
    shutil.copytree(ROOT / 'compiler/stdlib', package / 'stdlib',
                    ignore=shutil.ignore_patterns('.DS_Store', '*.exec.abd', '*.json'))
    language = (ROOT / 'compiler/LANGUAGE.md').read_text(encoding='utf-8')
    start = language.index('在仓库根目录')
    end = language.index('CLI 的 `compile-json`', start)
    language = language[:start] + (
        '在发行包根目录执行（Windows 使用 `compile.cmd` / `run.cmd`）：\n\n'
        '```sh\n./compile.sh examples/parser-regressions.azs -o example.exec.abd\n'
        './run.sh example.exec.abd\n```\n\n'
        '默认同时输出 `example.ast.json` 和 `example.exec.json`。'
        '完整参数及 C++ / JNI 接入见 [使用说明](USAGE.md)。\n\n') + language[end:]
    language = language.replace('](stdlib/', '](../stdlib/').replace('](examples/', '](../examples/')
    language = language.replace('](../docs/EXEC_FORMAT.md)', '](EXEC_FORMAT.md)').replace('](../docs/HINT_LINKING.md)', '](HINT_LINKING.md)').replace('](../docs/EXTERN_LIBRARY.md)', '](EXTERN_LIBRARY.md)')
    (package / 'docs/LANGUAGE.md').write_text(language, encoding='utf-8')
    shutil.copytree(TEMPLATES / 'licenses', package / 'licenses')
    shutil.copy2(TEMPLATES / 'THIRD_PARTY.md', package / 'THIRD_PARTY.md')
    (package / 'README.md').write_text(
        '# AzScript\n\n在此目录执行 `./compile.sh examples/hello.azs`，'
        '然后执行 `./run.sh examples/hello.exec.abd`。Windows 使用 `compile.cmd` / `run.cmd`。'
        '编译默认生成 ABD、AST JSON 和执行 JSON。\n\n'
        '[使用与 C++ / JNI 接入](docs/USAGE.md) · [基本语法](docs/QUICKSTART.md) · '
        '[完整语言说明](docs/LANGUAGE.md)\n\n'
        '本包针对当前构建系统、架构和原生工具链。构建信息、Java 要求、文件校验值及验收结果见 '
        '`manifest.json`；Java 运行环境存在时优先使用包内 `runtime/`。\n', encoding='utf-8')


def build_package(package, work, args, jdk):
    # Reuse the ordinary cache, but keep Release builds and Java outputs separate.
    gson = build_support.dependency(build_support.DEPS[0], args.offline)
    build_support.BUILD = work
    data = build_support.compile_java('abdJava', [gson])
    compiler = build_support.compile_java('compiler', [data, gson])
    bridge = build_support.compile_java('abdJavaInvoker', [])
    launcher_classes = work / 'launcher-classes'
    launcher_classes.mkdir()
    compiler_cp = os.pathsep.join(map(str, [compiler, data, gson]))
    run([jdk / 'bin' / ('javac' + EXE), '--release', '17', '-encoding', 'UTF-8',
         '-cp', compiler_cp, '-d', launcher_classes, TEMPLATES / 'launcher/Compile.java'])
    launcher = work / 'launcher.jar'
    run([jdk / 'bin' / ('jar' + EXE), '--create', '--file', launcher, '-C', launcher_classes, '.'])
    native = work / 'native'
    run(['cmake', '-S', ROOT, '-B', native, *args.cmake_arg,
         '-DCMAKE_BUILD_TYPE=Release', '-DBUILD_TESTING=OFF',
         '-DAZSCRIPT_BUILD_JNI=ON', '-DAZSCRIPT_BUILD_EXAMPLES=OFF',
         '-DAZSCRIPT_SANITIZE=OFF', '-DCMAKE_INSTALL_BINDIR=bin',
         '-DCMAKE_INSTALL_LIBDIR=lib', '-DCMAKE_INSTALL_INCLUDEDIR=include'])
    run(['cmake', '--build', native, '--config', 'Release', '--parallel', args.jobs])
    run(['cmake', '--install', native, '--config', 'Release', '--prefix', package])
    (package / 'compiler/lib').mkdir(parents=True)
    for artifact in (launcher, compiler, data, gson):
        shutil.copy2(artifact, package / 'compiler/lib' / artifact.name)
    (package / 'java').mkdir()
    shutil.copy2(bridge, package / 'java' / bridge.name)
    if not args.system_java:
        # jdk.compiler permits running the included Java host as a source file.
        run([jdk / 'bin' / ('jlink' + EXE), '--add-modules',
             'java.base,java.sql,jdk.unsupported,jdk.compiler', '--strip-debug',
             '--no-header-files', '--no-man-pages', '--output', package / 'runtime'])
    copy_assets(package)
    # Standard libraries are independently compiled hint modules. Ship the
    # executable and both inspection views alongside their shared declarations.
    math = package / 'stdlib/math'
    run([jdk / 'bin' / ('java' + EXE), '-cp', compiler_cp, 'azertia.Main',
         'compile', math.with_suffix('.azs'), '-o', math.with_suffix('.exec.abd'),
         '--ast', math.with_suffix('.ast.json'), '--exec-json', math.with_suffix('.exec.json')])
    cache = (native / 'CMakeCache.txt').read_text(encoding='utf-8')
    details = {}
    for key in ('CMAKE_CXX_COMPILER', 'CMAKE_CXX_COMPILER_TARGET', 'CMAKE_OSX_DEPLOYMENT_TARGET',
                'CMAKE_OSX_ARCHITECTURES', 'CMAKE_GENERATOR', 'CMAKE_MSVC_RUNTIME_LIBRARY'):
        match = re.search(r'^' + key + r':[^=]*=(.*)$', cache, re.MULTILINE)
        if match and match[1]:
            details[key] = match[1]
    return details


def inspect_relocation(package):
    """Installed link/load metadata must not refer back to this checkout."""
    for config in (package / 'lib/cmake/AzScript').glob('*.cmake'):
        if str(ROOT) in config.read_text(encoding='utf-8'):
            raise RuntimeError(f'Non-relocatable CMake package: {config}')
    if sys.platform == 'darwin':
        for binary in [package / 'bin/azscript-run', *sorted((package / 'lib').glob('*.dylib'))]:
            metadata = subprocess.check_output(['otool', '-l', str(binary)], text=True)
            # otool prints the inspected filename as its first line; only load
            # commands, not that heading, describe embedded dependency paths.
            paths = '\n'.join(re.findall(r'^\s*(?:name|path) (.+)$', metadata, re.MULTILINE))
            if str(ROOT) in paths or str(package.parent) in paths:
                raise RuntimeError(f'Absolute build/install path in Mach-O metadata: {binary}')


def write_manifest(package, args, java_version, native_details, tests):
    files = {}
    for file in sorted(package.rglob('*')):
        if file.is_file():
            files[file.relative_to(package).as_posix()] = {
                'sha256': hashlib.sha256(file.read_bytes()).hexdigest(),
                'bytes': file.stat().st_size,
            }
    version = re.search(r'project\(AzScript VERSION ([\d.]+)',
                        (ROOT / 'CMakeLists.txt').read_text(encoding='utf-8'))[1]
    manifest = {
        'format': FORMAT, 'formatVersion': 1, 'azscriptVersion': version,
        'createdUtc': datetime.now(timezone.utc).isoformat(),
        'platform': {'system': platform.system(), 'architecture': platform.machine(),
                     'buildOsVersion': platform.platform(), 'nativeToolchain': native_details},
        'entryFunction': '0x0fff0000', 'execFormatVersion': 7, 'jniSnapshotVersion': 7,
        'standardLibraries': {'AZSCRIPT_MATH': {
            'executable': 'stdlib/math.exec.abd', 'declarations': 'stdlib/math.include.azs'}},
        'java': {'bundled': not args.system_java, 'minimumSystemVersion': 17,
                 'buildVersion': java_version,
                 'bundledModules': [] if args.system_java else
                     ['java.base', 'java.sql', 'jdk.unsupported', 'jdk.compiler']},
        'cmakeArguments': args.cmake_arg,
        'validation': tests, 'files': files,
    }
    (package / 'manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n',
                                          encoding='utf-8')


def publish(package, destination, force):
    # Recheck after the build; a failed build or smoke test leaves the old package intact.
    check_destination(destination, force)
    if not destination.exists():
        package.rename(destination)
        return
    backup = Path(tempfile.mkdtemp(prefix='.azscript-previous-', dir=destination.parent))
    previous = backup / 'package'
    try:
        destination.rename(previous)
    except BaseException:
        backup.rmdir()
        raise
    try:
        package.rename(destination)
    except BaseException as publication_error:
        try:
            previous.rename(destination)
        except BaseException as restoration_error:
            # A concurrent writer may have occupied destination. Never clean up
            # the only remaining copy of the user's previous distribution.
            raise RuntimeError(f'Publish failed: {publication_error}; could not restore output: '
                               f'{restoration_error}. Previous export is preserved at {previous}') from publication_error
        backup.rmdir()
        raise
    shutil.rmtree(backup)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', help='destination directory for the complete distribution')
    parser.add_argument('--offline', action='store_true', help='use cached, checksum-verified Gson only')
    parser.add_argument('--system-java', action='store_true', help='omit bundled runtime; destination needs Java 17+')
    parser.add_argument('--force', action='store_true', help='replace a previous AzScript export after validation')
    parser.add_argument('--jobs', type=int, default=min(os.cpu_count() or 2, 8))
    parser.add_argument('--cmake-arg', action='append', default=[],
                        help='extra native configuration, e.g. --cmake-arg=-DCMAKE_OSX_DEPLOYMENT_TARGET=13.0')
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    destination = check_destination(args.output, args.force)
    jdk, java_version = java_home(not args.system_java)
    os.environ['JAVA_HOME'] = str(jdk)
    os.environ['PATH'] = str(jdk / 'bin') + os.pathsep + os.environ.get('PATH', '')
    (ROOT / 'build').mkdir(exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='distribution-', dir=ROOT / 'build') as build_dir, \
         tempfile.TemporaryDirectory(prefix='.azscript-export-', dir=destination.parent) as export_dir:
        package = Path(export_dir) / 'initial install'
        native_details = build_package(package, Path(build_dir), args, jdk)
        moved = Path(export_dir) / 'relocated package'
        package.rename(moved)
        inspect_relocation(moved)
        from test_distribution import verify
        tests = verify(moved, jdk / 'bin' / ('java' + EXE))
        write_manifest(moved, args, java_version, native_details, tests)
        publish(moved, destination, args.force)
    print(f'Exported and verified: {destination}')
    print('Compile: ./compile.sh examples/hello.azs (Windows: compile.cmd)')
    print('Run:     ./run.sh examples/hello.exec.abd (Windows: run.cmd)')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f'Export failed: {error}', file=sys.stderr)
        sys.exit(1)
