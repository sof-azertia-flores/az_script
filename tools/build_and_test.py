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

def check_error_storage():
    # Cleanup must retain both exception identity and its trace. Raw exception
    # pointers outside pending_error would silently lose the latter on unwind.
    # Every runtime source of both engines and the shared core is checked.
    interpreter = ROOT/'interpreter'
    sources = sorted(path for pattern in ('*.h', '*.cpp', 'core/*.h', 'core/*.cpp', 'tree/*.h', 'tree/*.cpp',
                                          'vm/*.h', 'vm/*.cpp', 'jit/*.h', 'jit/*.cpp')
                     for path in interpreter.glob(pattern) if not path.stem.endswith('_tests'))
    for path in sources:
        text = path.read_text(encoding='utf-8')
        if path.relative_to(interpreter).as_posix() == 'core/errors.h':
            start = text.index('class pending_error {')
            end = text.index('\n};', start) + len('\n};')
            text = text[:start] + text[end:]
        if 'std::exception_ptr' in text:
            raise RuntimeError(f'Raw exception storage outside pending_error in {path.relative_to(ROOT)}')
    print(f'Error trace storage check passed ({len(sources)} runtime sources).', flush=True)

def check_jit_stats(mode,stats):
    # A pass where native code never ran would prove nothing.
    sessions=[json.loads(line) for line in stats.read_text(encoding='utf-8').splitlines()] if stats.exists() else []
    entries=sum(item['entries'] for item in sessions)
    if not entries:raise RuntimeError(f'AZSCRIPT_JIT={mode} ran no native code')
    # Only analysis refusals are expected; a compiler error or a value
    # outside the analysis' tags is a defect.
    failures=sorted({reason for item in sessions for reason in item['refusals'] if reason.startswith('compiler error')})
    if failures:raise RuntimeError(f'AZSCRIPT_JIT={mode} failed to compile functions: {failures[:3]}')
    if sum(item['violations'] for item in sessions):raise RuntimeError(f'AZSCRIPT_JIT={mode} saw values outside the analysis')
    print(f'JIT {mode}: {len(sessions)} sessions, {sum(item["compiled"] for item in sessions)} functions compiled, '
          f'{entries} native entries, {sum(item["operations"] for item in sessions)} operations', flush=True)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--offline',action='store_true')
    parser.add_argument('--skip-jni',action='store_true')
    parser.add_argument('--skip-tests',action='store_true')
    parser.add_argument('--sanitize',action='store_true',help='Address/UB sanitizers on native tests (builds without JNI)')
    parser.add_argument('--jit',action='store_true',help='build the pinned LLVM first (tools/build_llvm.py), so the JIT module is built and tested')
    args=parser.parse_args()
    check_error_storage()
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
    if args.jit and not args.sanitize:
        run([sys.executable,ROOT/'tools/build_llvm.py',*(['--offline'] if args.offline else [])])
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
        # The 20,000-function scaling guard needs a 1 GiB heap, more than the default on small machines.
        if junit_tests:run(['java','-Xmx1g','-Djava.io.tmpdir='+str(BUILD/'tmp'),'-cp',test_cp,'JunitRunner',*junit_tests],cwd=ROOT/'compiler')
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
    # Under the sanitizers a script run to the default step limit takes about
    # 5 s on the tree, too close to the 10 s watchdog on a busy machine.
    if args.sanitize:e2e += ['--runner-timeout',60]
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
    buffers=[sys.executable,ROOT/'tests/buffers_end_to_end.py','--classpath',classpath,
             '--runner',native/'interpreter/azscript-run','--host',native/'interpreter/numeric-module-host']
    if jni:buffers += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(buffers)
    run([sys.executable, ROOT/'tests/loops_end_to_end.py', '--classpath', classpath,
         '--runner', native/'interpreter/azscript-run'])
    inheritance=[sys.executable,ROOT/'tests/inheritance_end_to_end.py','--classpath',classpath,
                 '--runner',native/'interpreter/azscript-run','--host',native/'interpreter/numeric-module-host']
    if jni:inheritance += ['--bridge',bridge,'--library',native/'abdjni'/libname]
    run(inheritance)
    virtuals=[sys.executable, ROOT/'tests/virtual_methods_end_to_end.py', '--classpath', classpath,
              '--runner', native/'interpreter/azscript-run', '--host', native/'interpreter/numeric-module-host']
    if jni:virtuals += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(virtuals)
    run([sys.executable, ROOT/'tests/limits_end_to_end.py', '--classpath', classpath,
         '--runner', native/'interpreter/azscript-run', '--host', native/'interpreter/numeric-module-host'])
    types=[sys.executable, ROOT/'tests/type_safety_end_to_end.py', '--classpath', classpath,
           '--runner', native/'interpreter/azscript-run']
    if jni:types += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(types)
    traces=[sys.executable, ROOT/'tests/runtime_trace_end_to_end.py', '--classpath', classpath,
            '--runner', native/'interpreter/azscript-run']
    if jni:traces += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(traces)
    language_ops=[sys.executable, ROOT/'tests/language_ops_end_to_end.py', '--classpath', classpath,
                  '--runner', native/'interpreter/azscript-run']
    if jni:language_ops += ['--bridge', bridge, '--library', native/'abdjni'/libname]
    run(language_ops)
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
    runtime_abi=[sys.executable, ROOT/'tests/runtime_abi_end_to_end.py', '--classpath', classpath,
                 '--runner', native/'interpreter/azscript-run',
                 '--signer', native/'interpreter/azscript-sign-extern',
                 '--libdir', native/'interpreter']
    if args.sanitize:runtime_abi += ['--sanitize']
    run(runtime_abi)
    # Migration VM gates: lowering of real modules, then independent-process
    # scalar/call execution compared field by field with the default backend.
    run([sys.executable, ROOT/'tests/compiler_optimization_end_to_end.py', '--classpath', classpath,
         '--host', native/'interpreter/observation-host',
         '--report', BUILD/('compiler-optimization-sanitize.json' if args.sanitize else 'compiler-optimization.json')])
    # This compiler branch also runs against the independently maintained JIT
    # integration. Discover that checkout's existing tiers without enabling or
    # configuring a runtime backend in the compiler.
    o2_replay=[]
    if (ROOT/'interpreter/jit').is_dir():
        module=native/'interpreter'/('libazscript-jit.dylib' if sys.platform=='darwin' else 'libazscript-jit.so')
        modes=['probe:7','eager','stress','on:2+interp']
        if module.exists():modes+=['eager+llvm','stress+llvm','on:3+llvm']
        o2_replay=['--jit-modes',','.join(modes)]
    run([sys.executable, ROOT/'tests/compiler_o2_end_to_end.py', '--classpath', classpath,
         '--host', native/'interpreter/observation-host', '--o3', *o2_replay,
         '--report', BUILD/('compiler-o2-sanitize.json' if args.sanitize else 'compiler-o2.json')])
    compiler_corpus=BUILD/('compiler-optimization-corpus-sanitize' if args.sanitize else 'compiler-optimization-corpus')
    if compiler_corpus.exists():shutil.rmtree(compiler_corpus)
    run([sys.executable, ROOT/'tests/compiler_optimization_corpus.py', '--classpath', classpath,
         '--host', native/'interpreter/observation-host', '--work', compiler_corpus, '--o2', '--o3', *o2_replay])
    run([sys.executable, ROOT/'tests/vm_program_end_to_end.py', '--classpath', classpath,
         '--host', native/'interpreter/vm-loader-host'])
    vm_work=BUILD/'runtime-perf'/('vm-execution-sanitize' if args.sanitize else 'vm-execution')
    if vm_work.exists():shutil.rmtree(vm_work)
    vm_work.parent.mkdir(parents=True,exist_ok=True)
    run([sys.executable, ROOT/'tests/runtime_differential.py', '--self-test'])
    run([sys.executable, ROOT/'tests/runtime_differences.py', '--self-test'])
    fuzz_work=BUILD/'runtime-perf'/('fuzz-selftest-sanitize' if args.sanitize else 'fuzz-selftest')
    if fuzz_work.exists():shutil.rmtree(fuzz_work)
    run([sys.executable, ROOT/'tests/lifecycle_fuzz.py', '--self-test', '--classpath', classpath,
         '--reference-host', native/'interpreter/observation-host',
         '--candidate-host', native/'interpreter/observation-host', '--candidate-engine', 'vm', '--work', fuzz_work])
    run([sys.executable, ROOT/'tests/vm_execution_end_to_end.py', '--classpath', classpath,
         '--reference-host', native/'interpreter/observation-host', '--reference-engine', 'tree',
         '--candidate-host', native/'interpreter/observation-host', '--candidate-engine', 'vm', '--work', vm_work])
    # The JIT tier, replayed against the same reference: probing moves the
    # points where frames consult the tier, eager runs eligible functions
    # natively, stress one instruction per entry. None may change anything.
    # The LLVM backend runs too when this build has the JIT module.
    jit_module=native/'interpreter'/('libazscript-jit.dylib' if sys.platform=='darwin' else 'libazscript-jit.so')
    # on:N compiles after N entries or back-edges, so replays cross tier-up
    # and on-stack replacement at loop heads.
    jit_modes=('probe:7', 'eager', 'stress', 'on:2+interp')+(('eager+llvm', 'stress+llvm', 'on:3+llvm') if jit_module.exists() else ())
    print(f'JIT module: {jit_module if jit_module.exists() else "not built"}', flush=True)
    for mode in jit_modes:
        jit_work=BUILD/'runtime-perf'/(f'vm-execution-jit-{mode.replace(":","").replace("+","-")}'+('-sanitize' if args.sanitize else ''))
        if jit_work.exists():shutil.rmtree(jit_work)
        stats=jit_work.with_name(jit_work.name+'.stats')
        stats.unlink(missing_ok=True)
        run([sys.executable, ROOT/'tests/vm_execution_end_to_end.py', '--reference-work', vm_work,
             '--candidate-host', native/'interpreter/observation-host', '--candidate-engine', 'vm', '--work', jit_work],
            env={**os.environ, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)})
        if mode != 'probe:7':check_jit_stats(mode,stats)
    # The optimized (O1) corpus has code shapes the compiler emits only when
    # optimizing; every instruction runs natively and must match the tree.
    if not args.sanitize:
        mode='stress+llvm' if jit_module.exists() else 'stress'
        optimized_work=BUILD/f'compiler-optimization-corpus-jit-{mode.replace("+","-")}'
        if optimized_work.exists():shutil.rmtree(optimized_work)
        stats=optimized_work.with_name(optimized_work.name+'.stats')
        stats.unlink(missing_ok=True)
        run([sys.executable, ROOT/'tests/compiler_optimization_corpus.py', '--classpath', classpath,
             '--host', native/'interpreter/observation-host', '--work', optimized_work],
            env={**os.environ, 'AZSCRIPT_JIT': mode, 'AZSCRIPT_JIT_STATS': str(stats)})
        check_jit_stats(f'{mode} (compiler corpus)',stats)
    # Every end-to-end runner invocation, run again with the VM forced: outputs
    # must be byte-identical or a refusal listed with its exact count.
    # One JIT mode per pass keeps each invocation (tree, VM, JIT) within the
    # suites' own timeouts. The slower sanitizer build runs stress (every
    # entry and exit point) and the policy; the replays above cover the rest.
    align_modes=('stress', 'on:2+interp') if args.sanitize else \
        ('probe:11', 'eager', 'stress', 'on:2+interp')+(('eager+llvm', 'stress+llvm', 'on:3+llvm') if jit_module.exists() else ())
    for mode in align_modes:
        align_work=BUILD/'runtime-perf'/(f'alignment-{mode.replace(":","").replace("+","-")}'+('-sanitize' if args.sanitize else ''))
        if align_work.exists():shutil.rmtree(align_work)
        run([sys.executable, ROOT/'tools/backend_alignment.py', '--native', native, '--classpath', classpath, '--work', align_work,
             '--jit', mode])
    print('Build and all selected tests passed. Use ./azscript --help.')

if __name__=='__main__':
    try:main()
    except (RuntimeError,OSError,subprocess.CalledProcessError) as e:
        print(f'Build failed: {e}',file=sys.stderr);sys.exit(1)
