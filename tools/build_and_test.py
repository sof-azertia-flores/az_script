#!/usr/bin/env python3
"""Reproducible build with JDK 17+, CMake 3.20+ and Python 3.9+.
Only pinned Maven dependencies are fetched, with SHA-256 verification. Existing
Gradle/Maven caches are reused read-only; all output stays under the project build directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'
DEPS = [
 ('com.google.code.gson','gson','2.11.0','57928d6e5a6edeb2abd3770a8f95ba44dce45f3b23b7a9dc2b309c581552a78b'),
 ('org.junit.jupiter','junit-jupiter-api','5.10.5','42251c2f1c29658c156ca0f3d9670588a051e9b6dd04f52a82de19ea32343e4c'),
 ('org.junit.jupiter','junit-jupiter-engine','5.10.5','11af62d3b0806b5de4550b1ec384e9b97b08757c468fcbdcb8d56d88e7e18470'),
 ('org.junit.platform','junit-platform-commons','1.10.5','abdaeaaf4cebd121ab9a7498ed7861693d2bb0ac5976b26c0060a308922ef49d'),
 ('org.junit.platform','junit-platform-engine','1.10.5','bfd71f57dffefee94e3f0cfd553be5e5dd7b8f9f273aba2515075e28b8c8e76a'),
 ('org.junit.platform','junit-platform-launcher','1.10.5','b90521d0414948797e15b62043045ffd52d79137bd565d632075ba5fd0ff3466'),
 ('org.opentest4j','opentest4j','1.3.0','48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b'),
 ('org.apiguardian','apiguardian-api','1.1.2','b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38'),
]

def run(args, **kwargs):
    print('+', ' '.join(map(str,args)), flush=True)
    subprocess.run(list(map(str,args)), cwd=kwargs.pop("cwd", ROOT), check=True, **kwargs)

def dependency(spec, offline):
    group, name, version, digest = spec
    filename = f'{name}-{version}.jar'
    dest = BUILD / 'deps' / filename
    path = f'{group.replace(".","/")}/{name}/{version}/{filename}'
    candidates = [dest]
    cache = Path.home()/'.gradle/caches/modules-2/files-2.1'/group/name/version
    candidates += sorted(cache.glob(f'*/{filename}'))
    candidates += [Path.home()/'.m2/repository'/path]
    for candidate in candidates:
        if candidate.is_file() and hashlib.sha256(candidate.read_bytes()).hexdigest() == digest:
            if candidate != dest:
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(candidate, dest)
            return dest
    if offline:
        raise RuntimeError(f'Missing cached {filename}; rerun without --offline or put the verified jar in {dest.parent}')
    print(f'Downloading {filename} from Maven Central', flush=True)
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+path, timeout=60) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != digest:
        raise RuntimeError(f'Checksum mismatch for {filename}')
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return dest

def compile_java(module, deps):
    classes = BUILD/'java'/module/'classes'
    if classes.exists(): shutil.rmtree(classes)
    classes.mkdir(parents=True)
    sources = sorted((ROOT/module/'src/main/java').rglob('*.java'))
    command = ['javac','--release','17','-encoding','UTF-8','-d',classes]
    if deps: command += ['-cp',os.pathsep.join(map(str,deps))]
    # Argument files avoid command length limits and correctly preserve spaces.
    argfile = classes.parent/'sources.txt'
    argfile.write_text('\n'.join('"'+str(p).replace('\\','\\\\').replace('"','\\"')+'"' for p in sources), encoding='utf-8')
    run(command+['@'+str(argfile)])
    jar = BUILD/'java'/f'{module}.jar'
    run(['jar','--create','--file',jar,'-C',classes,'.'])
    return jar

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline',action='store_true')
    parser.add_argument('--skip-jni',action='store_true')
    parser.add_argument('--skip-tests',action='store_true')
    parser.add_argument('--sanitize',action='store_true',help='Address/UB sanitizers on native tests (builds without JNI)')
    args=parser.parse_args()
    BUILD.mkdir(exist_ok=True)
    (BUILD/'tmp').mkdir(exist_ok=True)
    jars=[dependency(s,args.offline) for s in (DEPS[:1] if args.skip_tests else DEPS)]
    data=compile_java('abdJava',[jars[0]])
    compiler=compile_java('compiler',[data,jars[0]])
    bridge=compile_java('abdJavaInvoker',[])
    classpath=os.pathsep.join(map(str,[compiler,data,jars[0]]))
    (BUILD/'java/classpath.txt').write_text(classpath,encoding='utf-8')
    native=BUILD/('sanitize' if args.sanitize else 'native')
    jni=not (args.skip_jni or args.sanitize)
    run(['cmake','-S',ROOT,'-B',native,'-DCMAKE_BUILD_TYPE=Debug',f'-DAZSCRIPT_BUILD_JNI={"ON" if jni else "OFF"}',f'-DAZSCRIPT_SANITIZE={"ON" if args.sanitize else "OFF"}'])
    run(['cmake','--build',native,'--parallel',min(os.cpu_count() or 2,8)])
    if args.skip_tests:
        print('Build completed. Use ./azscript --help.');return
    run([sys.executable, ROOT/'tests/test_export_distribution.py'])
    test_classes=BUILD/'java/test-classes'
    if test_classes.exists():shutil.rmtree(test_classes)
    test_classes.mkdir()
    test_sources=[]
    for module in ('abdJava','compiler'):
        test_sources += sorted((ROOT/module/'src/test/java').rglob('*.java'))
    test_cp=os.pathsep.join(map(str,[compiler,data,bridge,*jars,test_classes]))
    if test_sources:
        run(['javac','--release','17','-encoding','UTF-8','-cp',test_cp,'-d',test_classes,ROOT/'tools/JunitRunner.java',*test_sources])
        junit_tests=[]
        for p in test_sources:
            if '@Test' in p.read_text(encoding='utf-8'):
                rel=p.relative_to(next(parent for parent in p.parents if parent.name=='java'))
                junit_tests.append('.'.join(rel.with_suffix('').parts))
        if junit_tests:run(['java','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-cp',test_cp,'JunitRunner',*junit_tests],cwd=ROOT/'compiler')
        run(['java','-cp',test_cp,'azertia.binary.AbdRegression'])
    run(['ctest','--test-dir',native,'--output-on-failure'])
    cpp_fixture=BUILD/'cpp-fixture.abd';java_fixture=BUILD/'java-fixture.abd'
    run([native/'abdC/abd_regression','--write',cpp_fixture])
    run(['java','-cp',test_cp,'azertia.binary.AbdRegression','--read',cpp_fixture])
    run(['java','-cp',test_cp,'azertia.binary.AbdRegression','--write',java_fixture])
    run([native/'abdC/abd_regression','--read',java_fixture])
    if cpp_fixture.read_bytes()!=java_fixture.read_bytes():raise RuntimeError('C++ and Java ABD wire formats differ')
    print('Cross-language ABD fixtures are byte-for-byte identical.')
    libname='abdJ.dll' if sys.platform=='win32' else ('libabdJ.dylib' if sys.platform=='darwin' else 'libabdJ.so')
    math_test=[sys.executable,ROOT/'tests/math_library.py','--classpath',classpath,
               '--runner',native/'interpreter/azscript-run']
    if jni:math_test += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(math_test)
    run([sys.executable, ROOT/'tests/containers_library.py', '--classpath', classpath,
         '--runner', native/'interpreter/azscript-run'])
    e2e=[sys.executable,ROOT/'tests/end_to_end.py','--classpath',classpath,'--runner',native/'interpreter/azscript-run','--bridge',bridge]
    if jni:e2e += ['--library',native/'abdjni'/libname]
    run(e2e)
    classes=[sys.executable,ROOT/'tests/classes_end_to_end.py','--classpath',classpath,
             '--runner',native/'interpreter/azscript-run']
    if jni:classes += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(classes)
    literal=[sys.executable,ROOT/'tests/literal_objects_end_to_end.py','--classpath',classpath,
             '--runner',native/'interpreter/azscript-run','--host',native/'interpreter/numeric-module-host']
    if jni:literal += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(literal)
    generics=[sys.executable,ROOT/'tests/generics_end_to_end.py','--classpath',classpath,
              '--runner',native/'interpreter/azscript-run','--host',native/'interpreter/numeric-module-host']
    if jni:generics += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(generics)
    run([sys.executable, ROOT/'tests/loops_end_to_end.py', '--classpath', classpath,
         '--runner', native/'interpreter/azscript-run'])
    inheritance=[sys.executable,ROOT/'tests/inheritance_end_to_end.py','--classpath',classpath,
                 '--runner',native/'interpreter/azscript-run','--host',native/'interpreter/numeric-module-host']
    if jni:inheritance += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(inheritance)
    run([sys.executable, ROOT/'tests/address_end_to_end.py', '--classpath', classpath,
         '--runner', native/'interpreter/azscript-run', '--host', native/'interpreter/numeric-module-host'])
    run([sys.executable, ROOT/'tests/numeric_slots_end_to_end.py', '--classpath', classpath,
         '--host', native/'interpreter/numeric-module-host'])
    compact=[sys.executable, ROOT/'tests/compact_exec_end_to_end.py', '--classpath', classpath,
             '--runner', native/'interpreter/azscript-run', '--host', native/'interpreter/numeric-module-host']
    if jni:compact += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(compact)
    hints=[sys.executable, ROOT/'tests/hint_linking_end_to_end.py', '--classpath', classpath,
           '--runner', native/'interpreter/azscript-run', '--host', native/'interpreter/numeric-module-host']
    if jni:hints += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(hints)
    extern=[sys.executable, ROOT/'tests/extern_library_end_to_end.py', '--classpath', classpath,
            '--runner', native/'interpreter/azscript-run',
            '--signer', native/'interpreter/azscript-sign-extern',
            '--libdir', native/'interpreter']
    if jni:extern += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(extern)
    print('Build and all selected tests passed. Use ./azscript --help.')

if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError,subprocess.CalledProcessError) as e:
        print(f'Build failed: {e}',file=sys.stderr);sys.exit(1)
