#!/usr/bin/env python3
"""Load a signed native plugin through load_extern_library."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PLUGIN = r'''
#include <azscript/extern_library.hpp>
class Add final : public azertian::function {
public:
    int return_type() override { return azertian::INT_VALUE; }
    std::shared_ptr<azertian::variable> invoke(std::shared_ptr<azertian::environment>,
        std::vector<std::shared_ptr<azertian::variable>> args) override {
        int value = *static_cast<int*>(args.at(0)->value) + *static_cast<int*>(args.at(1)->value);
        return std::make_shared<azertian::variable>(value);
    }
};
class Plugin final : public azertian::executor {
public:
    int namespace_name() override { return 0x1234; }
    std::shared_ptr<azertian::function> getiFunction(int id) override {
        if (id == 0x12340001) return std::make_shared<Add>();
        return nullptr;
    }
};
AZSCRIPT_EXTERN_ENTRY {
    azertian::registerExecutor(std::make_shared<Plugin>());
}
'''
EMPTY = 'extern "C" void azscript_unused() {}\n'
SOURCE = '''
extern int add(int, int):0x12340001;
int main() {
    load_extern_library("%s");
    load_extern_library("%s");
    return add(20, 22);
}
'''


def run(command, *, cwd, env=None, timeout=60):
    result = subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--runner', type=Path, required=True)
    parser.add_argument('--signer', type=Path, required=True)
    parser.add_argument('--libdir', type=Path, required=True)
    parser.add_argument('--bridge', type=Path)
    parser.add_argument('--library', type=Path)
    args = parser.parse_args()
    checks = 0
    compile_script = ROOT / 'tools/distribution/compile_extern_lib.sh'
    env_base = os.environ.copy()
    env_base['AZSCRIPT_INCLUDE'] = str(ROOT / 'include')
    env_base['AZSCRIPT_LIBDIR'] = str(args.libdir)
    env_base['AZSCRIPT_SIGN_EXTERN'] = str(args.signer)
    lib_path = str(args.libdir)
    if env_base.get('LD_LIBRARY_PATH'):
        lib_path += os.pathsep + env_base['LD_LIBRARY_PATH']
    env_base['LD_LIBRARY_PATH'] = lib_path

    def check(condition, detail):
        nonlocal checks
        if not condition:
            raise AssertionError(detail)
        checks += 1

    with tempfile.TemporaryDirectory(prefix='extern-e2e-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)
        plugin = work / 'demo.cpp'
        plugin.write_text(PLUGIN, encoding='utf-8')
        compiled = run([str(compile_script), '-o', str(work / 'demo'), str(plugin)], cwd=work, env=env_base)
        check(compiled.returncode == 0, compiled.stderr)
        library = work / 'demo.so'
        check(library.is_file(), f'missing {library}')
        generated = run([str(args.signer), 'genkey', '--private', str(work / 'private.pem'),
                         '--public', str(work / 'trusted_key.pem')], cwd=work, env=env_base)
        check(generated.returncode == 0, generated.stderr)
        signed = run([str(args.signer), 'sign', '--key', str(work / 'private.pem'),
                      '--library', str(library)], cwd=work, env=env_base)
        check(signed.returncode == 0 and (work / 'demo.signature').is_file(), signed.stderr)
        stem = str(work / 'demo')
        source = work / 'main.azs'
        source.write_text(SOURCE % (stem, stem), encoding='utf-8')
        binary = work / 'main.exec.abd'
        compiled_script = run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', str(source), '-o', str(binary)],
                              cwd=ROOT, env=env_base)
        check(compiled_script.returncode == 0, compiled_script.stderr)

        def execute(extra_env, expected_error=None):
            env = env_base.copy()
            env.update(extra_env)
            result = run([str(args.runner), str(binary)], cwd=work / 'empty' if expected_error == 'nokey' else work, env=env)
            return result

        (work / 'empty').mkdir()
        by_file = execute({})
        check(by_file.returncode == 0 and by_file.stdout == '42\n', (by_file.stdout, by_file.stderr))
        by_env = execute({'AZSCRIPT_TRUSTED_KEY': (work / 'trusted_key.pem').read_text(encoding='utf-8')}, expected_error='nokey')
        # The empty directory has no trusted_key.pem; the variable carries the PEM text.
        check(by_env.returncode == 0 and by_env.stdout == '42\n', (by_env.stdout, by_env.stderr))
        missing = execute({}, expected_error='nokey')
        check(missing.returncode != 0 and 'No trusted public key' in missing.stderr, missing.stderr)
        original = library.read_bytes()
        library.write_bytes(original[:1] + bytes([original[0] ^ 1]) + original[1:] if original else b'x')
        # The first successful load cached the canonical path inside that process only.
        # A new process must reject the changed bytes.
        tampered = execute({})
        check(tampered.returncode != 0 and 'signature' in tampered.stderr, tampered.stderr)
        library.write_bytes(original)
        empty = work / 'empty.cpp'
        empty.write_text(EMPTY, encoding='utf-8')
        empty_lib = work / 'empty.so'
        built_empty = run([str(compile_script), '-o', str(work / 'empty'), str(empty)], cwd=work, env=env_base)
        check(built_empty.returncode == 0 and empty_lib.is_file(), built_empty.stderr)
        signed_empty = run([str(args.signer), 'sign', '--key', str(work / 'private.pem'), '--library', str(empty_lib)],
                           cwd=work, env=env_base)
        check(signed_empty.returncode == 0, signed_empty.stderr)
        nosym = work / 'nosym.azs'
        nosym.write_text('void main(){load_extern_library("%s");}\n' % (work / 'empty'), encoding='utf-8')
        nosym_bin = work / 'nosym.exec.abd'
        compiled_nosym = run(['java', '-cp', args.classpath, 'azertia.Main', 'compile', str(nosym), '-o', str(nosym_bin)],
                             cwd=ROOT, env=env_base)
        check(compiled_nosym.returncode == 0, compiled_nosym.stderr)
        missing_symbol = run([str(args.runner), str(nosym_bin)], cwd=work, env=env_base)
        check(missing_symbol.returncode != 0 and 'azscript_load_extern' in missing_symbol.stderr, missing_symbol.stderr)

        if args.library and args.bridge:
            java = work / 'RunExtern.java'
            java.write_text('''
import azertia.AbdInvoker;
import java.io.File;
public class RunExtern {
    public static void main(String[] args) throws Exception {
        System.setProperty("azertia.native.library", args[0]);
        AbdInvoker.addTrustedPublicKey(new File(args[1]));
        AbdInvoker.loadScript(new File(args[2]));
        AbdInvoker.flush();
        Object value = AbdInvoker.invoke(0x0fff0000);
        if (!(value instanceof Integer) || ((Integer) value).intValue() != 42) throw new AssertionError(String.valueOf(value));
        AbdInvoker.clearTrustedPublicKeys();
        AbdInvoker.close();
    }
}
''', encoding='utf-8')
            javac = run(['javac', '--release', '17', '-cp', str(args.bridge), '-d', str(work), str(java)], cwd=work, env=env_base)
            check(javac.returncode == 0, javac.stderr)
            java_run = run(['java', '-cp', str(work) + os.pathsep + str(args.bridge), 'RunExtern',
                            str(args.library.resolve()), str(work / 'trusted_key.pem'), str(binary)],
                           cwd=work, env=env_base)
            check(java_run.returncode == 0, java_run.stdout + java_run.stderr)
    print(f'Extern library checks passed: {checks}')


if __name__ == '__main__':
    main()
