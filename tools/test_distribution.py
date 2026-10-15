#!/usr/bin/env python3
"""Exercise an installed AzScript distribution without using repository artifacts."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from typing import Dict, List, Optional


WINDOWS = sys.platform == "win32"
EXECUTABLE_SUFFIX = ".exe" if WINDOWS else ""
EXEC_VERSION = 10
# Constants, blocks and calls use other JSON representations (wire opcodes 0–2).
# The unit suite checks this set against the compiler's opcode registry.
CONTROL_OPCODES = frozenset(range(3, 54))


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def _run(arguments, cwd: Path, environment: dict, *, succeeds: bool = True,
         timeout: int = 180) -> subprocess.CompletedProcess:
    command = [str(value) for value in arguments]
    # Windows cmd scripts require its native command processor. subprocess adds
    # the outer cmd /c quoting; list2cmdline preserves paths containing spaces.
    is_batch = WINDOWS and Path(command[0]).suffix.lower() in (".cmd", ".bat")
    try:
        result = subprocess.run(command, cwd=str(cwd), env=environment, shell=is_batch,
                                capture_output=True, text=True, encoding="utf-8",
                                errors="replace", timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f"Could not run {command[0]}: {error}") from error
    if (result.returncode == 0) != succeeds:
        expectation = "success" if succeeds else "a nonzero exit code"
        output = (result.stdout + result.stderr)[-12000:]
        raise RuntimeError(
            f"Expected {expectation}, got {result.returncode}: {command!r}\n{output}")
    return result


def _clean_environment(package: Path) -> dict:
    environment = os.environ.copy()
    for name in ("CLASSPATH", "LD_LIBRARY_PATH", "LD_PRELOAD", "DYLD_LIBRARY_PATH",
                 "DYLD_FALLBACK_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES", "JAVA_TOOL_OPTIONS",
                 "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CMAKE_PREFIX_PATH", "AzScript_DIR"):
        environment.pop(name, None)
    if WINDOWS:
        environment["PATH"] = str(package / "bin") + os.pathsep + environment.get("PATH", "")
    return environment


def _control_opcodes(value, location: str) -> set:
    found = set()
    if isinstance(value, dict):
        if value.get("t") == 0:
            opcode = value.get("c")
            _require(type(opcode) is int,
                     f"{location}: control instruction must use a numeric opcode, got {opcode!r}")
            _require(opcode in CONTROL_OPCODES,
                     f"{location}: unsupported control opcode {opcode!r} for exec v{EXEC_VERSION}")
            found.add(opcode)
        for key, child in value.items():
            found.update(_control_opcodes(child, f"{location}.{key}"))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found.update(_control_opcodes(child, f"{location}[{index}]"))
    return found


def _outputs(abd: Path, ast: Path, executable_json: Path) -> None:
    for path in (abd, ast, executable_json):
        _require(path.is_file() and path.stat().st_size > 0, f"Missing output: {path}")
    for path in (ast, executable_json):
        try:
            decoded = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise RuntimeError(f"Invalid JSON output {path}: {error}") from error
        _require(isinstance(decoded, dict), f"Expected a JSON object in {path}")
        if path == executable_json:
            _require(decoded.get("exec-version") == EXEC_VERSION,
                     f"Expected exec v{EXEC_VERSION} output in {path}")
            _require(isinstance(decoded.get("debug-files"), list), f"Missing debug-files table in {path}")
            _control_opcodes(decoded["f"], f"{path}:f")


def _host_executable(build: Path) -> Path:
    for path in (build / ("azscript-host" + EXECUTABLE_SUFFIX),
                 build / "Release" / ("azscript-host" + EXECUTABLE_SUFFIX)):
        if path.is_file():
            return path
    raise RuntimeError(f"CMake consumer executable is missing in {build}")


def verify(package: Path, java: Optional[Path] = None) -> Dict[str, object]:
    """Verify a relocated package in temporary working/build directories.

    The package is only read. Returns a JSON-serializable test summary and raises
    RuntimeError on any failure. For a --system-java package, java should point to
    a JDK 17+ executable so the real JNI source-file example can also run.
    """
    package = Path(package).resolve()
    _require(package.is_dir(), f"Not a distribution directory: {package}")
    environment = _clean_environment(package)
    extension = ".cmd" if WINDOWS else ".sh"
    compiler = package / ("compile" + extension)
    runner = package / ("run" + extension)
    bundled_java = package / "runtime" / "bin" / ("java" + EXECUTABLE_SUFFIX)
    java_candidate = bundled_java if bundled_java.is_file() else java
    if java_candidate is None:
        discovered = shutil.which("java", path=environment.get("PATH"))
        java_candidate = Path(discovered) if discovered else None
    _require(java_candidate is not None, "Java is required to verify the JNI example")
    java_executable = Path(java_candidate).resolve()
    _require(java_executable.is_file(), f"Java executable not found: {java_executable}")
    cmake = shutil.which("cmake", path=environment.get("PATH"))
    _require(cmake is not None, "CMake is required to verify the C++ consumer")

    checks: List[str] = []

    def passed(name: str) -> None:
        checks.append(name)
        print(f"Distribution check: {name}", flush=True)

    required = [compiler, runner, package / "bin" / ("azscript-run" + EXECUTABLE_SUFFIX),
                package / "compiler/lib/compiler.jar", package / "compiler/lib/abdJava.jar",
                package / "compiler/lib/gson-2.11.0.jar", package / "compiler/lib/launcher.jar",
                package / "java/abdJavaInvoker.jar", package / "include/azscript/runtime.hpp",
                package / "lib/cmake/AzScript/AzScriptConfig.cmake", package / "docs/USAGE.md",
                package / "docs/QUICKSTART.md", package / "docs/LANGUAGE.md", package / "docs/EXEC_FORMAT.md",
                package / "docs/HINT_LINKING.md", package / "docs/EXTERN_LIBRARY.md",
                package / "examples/multifile/point.include.azs",
                package / "stdlib/math.azs", package / "stdlib/math.include.azs", package / "stdlib/MATH.md",
                package / "stdlib/math.exec.abd", package / "stdlib/math.ast.json", package / "stdlib/math.exec.json",
                package / "stdlib/containers.azs", package / "stdlib/containers.include.azs",
                package / "stdlib/CONTAINERS.md", package / "stdlib/CONTAINERS.en.md",
                package / "stdlib/containers.exec.abd", package / "stdlib/containers.ast.json",
                package / "stdlib/containers.exec.json",
                package / "examples/hello.azs", package / "examples/classes.azs",
                package / "examples/loops-regressions.azs", package / "examples/inheritance-regressions.azs",
                package / "examples/address-regressions.azs", package / "examples/operators-regressions.azs", package / "examples/virtual-regressions.azs",
                package / "include/azscript/detail/abdC/address.h",
                package / "include/azscript/extern_library.hpp",
                package / "include/azscript/detail/interpreter/library.h",
                package / "include/azscript/detail/interpreter/internelFunctions.h",
                package / "include/azscript/detail/interpreter/blocks.h",
                package / "include/azscript/detail/interpreter/heepalloc.h",
                package / "include/azscript/detail/interpreter/acs.h",
                package / "compile_extern_lib.sh", package / "sign_extern_lib.sh",
                package / "bin" / ("azscript-sign-extern" + EXECUTABLE_SUFFIX),
                package / "examples/cpp/CMakeLists.txt", package / "examples/cpp/main.cpp",
                package / "examples/java/RunScript.java"]
    for path in required:
        _require(path.is_file(), f"Missing distribution component: {path}")
    for relative in ("README", "THIRD_PARTY", "docs/USAGE", "docs/QUICKSTART",
                     "docs/LANGUAGE", "docs/EXEC_FORMAT", "docs/HINT_LINKING",
                     "docs/EXTERN_LIBRARY", "stdlib/MATH", "stdlib/CONTAINERS"):
        for suffix in (".md", ".en.md"):
            path = package / (relative + suffix)
            _require(path.is_file(), f"Missing distribution documentation: {path}")
    passed("required compiler, runtime, SDK, JNI, documentation and examples")

    with tempfile.TemporaryDirectory(prefix="azscript distribution verification ") as temporary:
        work = Path(temporary)
        cwd = work / "unrelated current directory"
        cwd.mkdir()
        source_directory = work / "source files"
        source_directory.mkdir()
        source = source_directory / "hello world.azs"
        shutil.copyfile(package / "examples/hello.azs", source)
        original_source = source.read_bytes()

        compiler_environment = environment.copy()
        if bundled_java.is_file():
            compiler_environment["JAVA_HOME"] = str(work / "missing JDK")
            if not WINDOWS:
                # The POSIX wrapper needs dirname, but must not find any host Java.
                command_directory = work / "minimal shell tools"
                command_directory.mkdir()
                dirname = shutil.which("dirname")
                _require(dirname is not None, "dirname is needed by the POSIX wrapper")
                (command_directory / "dirname").symlink_to(dirname)
                compiler_environment["PATH"] = str(command_directory)
        else:
            compiler_environment["JAVA_HOME"] = str(java_executable.parent.parent)
            compiler_environment["PATH"] = str(java_executable.parent) + os.pathsep + environment.get("PATH", "")

        _run([compiler, source], cwd, compiler_environment)
        default_abd = source_directory / "hello world.exec.abd"
        default_ast = source_directory / "hello world.ast.json"
        default_exec = source_directory / "hello world.exec.json"
        _outputs(default_abd, default_ast, default_exec)
        _require(source.read_bytes() == original_source, "Compiler changed its source file")
        passed("source paths with spaces and three default outputs" +
               (" without system Java" if bundled_java.is_file() and not WINDOWS else ""))

        _run([compiler, source, "-o", "output directory/custom.abd"], cwd, compiler_environment)
        custom = cwd / "output directory"
        _outputs(custom / "custom.abd", custom / "custom.ast.json", custom / "custom.exec.json")
        _require((custom / "custom.abd").read_bytes() == default_abd.read_bytes(),
                 "Changing the output location changed executable bytes")
        _run([compiler, source, "-o", "output directory/alternate.exec.abd"], cwd, compiler_environment)
        _outputs(custom / "alternate.exec.abd", custom / "alternate.ast.json", custom / "alternate.exec.json")
        passed("relative -o paths and .abd/.exec.abd JSON naming")

        _run([compiler, source, "-o", "chosen/program.abd", "--ast", "metadata/readable tree.json",
              "--exec-json", "metadata/instruction tree.json"], cwd, compiler_environment)
        _outputs(cwd / "chosen/program.abd", cwd / "metadata/readable tree.json",
                 cwd / "metadata/instruction tree.json")
        _require(not (cwd / "chosen/program.ast.json").exists(), "Explicit AST path was ignored")
        _require(not (cwd / "chosen/program.exec.json").exists(), "Explicit exec JSON path was ignored")
        # Options may precede the source; the launcher still derives the default JSON paths.
        _run([compiler, "-o", "leading options/first.abd", source], cwd, compiler_environment)
        _outputs(cwd / "leading options/first.abd", cwd / "leading options/first.ast.json",
                 cwd / "leading options/first.exec.json")
        # "--" ends the options, so a relative source may begin with '-'; defaults still apply.
        (cwd / "-dash.azs").write_bytes(original_source)
        _run([compiler, "-dash.azs"], cwd, compiler_environment, succeeds=False)
        _run([compiler, "--", "-dash.azs"], cwd, compiler_environment)
        _outputs(cwd / "-dash.exec.abd", cwd / "-dash.ast.json", cwd / "-dash.exec.json")
        # Trailing diagnostic and warning options keep the default outputs.
        _run([compiler, source, "-o", "flags/last.abd", "--max-errors", "5", "--debug-compiler"], cwd,
             compiler_environment)
        _outputs(cwd / "flags/last.abd", cwd / "flags/last.ast.json", cwd / "flags/last.exec.json")
        _run([compiler, source, "-o", "flags/warnings.abd", "-Wall"], cwd, compiler_environment)
        _outputs(cwd / "flags/warnings.abd", cwd / "flags/warnings.ast.json", cwd / "flags/warnings.exec.json")
        passed("explicit AST and executable JSON locations, options before the source, -- and diagnostic and warning flags")

        ast_bytes = default_ast.read_bytes()
        _run([compiler, "compile-json", default_ast, "-o", "roundtrip/from ast.abd"], cwd,
             compiler_environment)
        _require((cwd / "roundtrip/from ast.abd").read_bytes() == default_abd.read_bytes(),
                 "Source to AST to ABD roundtrip changed executable bytes")
        _require((cwd / "roundtrip/from ast.exec.json").is_file(), "compile-json omitted exec JSON")
        _require(default_ast.read_bytes() == ast_bytes, "compile-json overwrote the input AST")
        passed("AST roundtrip is byte-identical and preserves its input")

        # Debug records are opt-in: -g adds names and positions without changing instructions.
        default_view = json.loads(default_exec.read_text(encoding="utf-8"))
        _require(default_view["debug-files"] == [] and all("debug" not in f for f in default_view["f"]),
                 "The default executable carries debug records")
        _run([compiler, source, "-o", "debug output/hello.abd", "-g"], cwd, compiler_environment)
        debug_abd = cwd / "debug output/hello.abd"
        _outputs(debug_abd, cwd / "debug output/hello.ast.json", cwd / "debug output/hello.exec.json")
        debug_view = json.loads((cwd / "debug output/hello.exec.json").read_text(encoding="utf-8"))
        _require(debug_view["debug-files"] == ["hello world.azs"], f"Unexpected debug files: {debug_view['debug-files']!r}")
        _require([f["debug"]["name"] for f in debug_view["f"]] == ["main"] and debug_view["f"][0]["debug"]["line"] > 0,
                 "-g did not add a located debug record for main")
        _require([{k: v for k, v in f.items() if k != "debug"} for f in debug_view["f"]] == default_view["f"],
                 "-g changed the instructions")
        _run([compiler, "compile-json", cwd / "debug output/hello.ast.json", "-o", "debug output/roundtrip.abd", "-g"], cwd,
             compiler_environment)
        _require((cwd / "debug output/roundtrip.abd").read_bytes() == debug_abd.read_bytes(),
                 "Source to AST to ABD roundtrip with -g changed executable bytes")
        result = _run([runner, debug_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Hello, AzScript!", "42"], f"-g changed the program output: {result.stdout!r}")
        passed("opt-in -g debug records keep instructions, outputs and the AST roundtrip")

        trace_source = source_directory / "runtime trace.azs"
        trace_text = "int fail():0002{return 1 / 0;}\nint main(){return fail();}\n"
        trace_source.write_text(trace_text, encoding="utf-8")
        trace_abd = cwd / "debug output/failure.abd"
        _run([compiler, trace_source, "-o", trace_abd, "-g"], cwd, compiler_environment)
        failure = _run([runner, trace_abd], cwd, environment, succeeds=False)
        _require(failure.returncode == 1 and not failure.stdout and failure.stderr == (
                     "AzScript error: Division by zero\n"
                     f"  at fail (runtime trace.azs:1:{trace_text.splitlines()[0].index('/') + 1})\n"
                     f"  at main (runtime trace.azs:2:{trace_text.splitlines()[1].index('fail(') + 1})\n"),
                 f"Packaged runtime lost debug error locations: {failure.stderr!r}")
        _run([compiler, trace_source, "-o", trace_abd], cwd, compiler_environment)
        failure = _run([runner, trace_abd], cwd, environment, succeeds=False)
        _require(failure.returncode == 1 and not failure.stdout and failure.stderr == (
                     "AzScript error: Division by zero\n"
                     "  at function 0x0fff0002\n  at function 0x0fff0000\n"),
                 f"Packaged runtime lost stripped error frames: {failure.stderr!r}")
        passed("packaged debug and stripped runtime traces preserve exact error reports")

        strict_source = source_directory / "strict conditions.azs"
        strict_source.write_text("void main(){if(1)print(99);}\n", encoding="utf-8")
        failure = _run([compiler, strict_source], cwd, compiler_environment, succeeds=False)
        _require(failure.returncode == 1 and "if condition must be boolean, got int" in failure.stderr,
                 f"Packaged compiler accepted a numeric condition: {failure.stderr!r}")
        strict_abd = cwd / "strict runtime/program.abd"
        for text, message in [
                ("void main(){def(value,1);if(value)print(99);}\n", "Condition must be boolean, got int"),
                ('boolean main(){def(value,"1");return value==1;}\n',
                 "Equality requires compatible operand types, got string and int")]:
            strict_source.write_text(text, encoding="utf-8")
            _run([compiler, strict_source, "-o", strict_abd], cwd, compiler_environment)
            failure = _run([runner, strict_abd], cwd, environment, succeeds=False)
            _require(failure.returncode == 1 and not failure.stdout and failure.stderr == (
                         f"AzScript error: {message}\n  at function 0x0fff0000\n"),
                     f"Packaged runtime lost strict condition/equality checks: {failure.stderr!r}")
        passed("boolean-only conditions and compatible equality in compiler and runtime")

        broken_source = source_directory / "invalid source.azs"
        broken_source.write_text("int main() { return missing_name; }\n", encoding="utf-8")
        broken = _run([compiler, broken_source], cwd, compiler_environment, succeeds=False)
        _require(broken.stderr.splitlines() == [
                     "invalid source.azs:1:21: error: in function main: Unknown variable: missing_name",
                     "Error: compilation failed: 1 error"],
                 f"Compile errors are not reported as located diagnostics: {broken.stderr!r}")
        _require(broken.returncode == 1, f"A compile error must exit with status 1, got {broken.returncode}")
        # Independent errors are reported together, up to --max-errors.
        broken_source.write_text("int f() { return 1 +; }\nint main() { return 0 +; }\n", encoding="utf-8")
        several = _run([compiler, broken_source], cwd, compiler_environment, succeeds=False)
        _require(several.stderr.splitlines() == [
                     "invalid source.azs:1:21: error: Expected expression, found ';'",
                     "invalid source.azs:2:24: error: Expected expression, found ';'",
                     "Error: compilation failed: 2 errors"],
                 f"Independent compile errors are not reported together: {several.stderr!r}")
        limited = _run([compiler, broken_source, "--max-errors", "1"], cwd, compiler_environment, succeeds=False)
        _require(limited.stderr.splitlines() == [
                     "invalid source.azs:1:21: error: Expected expression, found ';'",
                     "Error: compilation failed: 1 error"],
                 f"--max-errors 1 did not stop at the first error: {limited.stderr!r}")
        # Warnings keep the status and the outputs; -Werror turns them into errors.
        warned_source = source_directory / "warned source.azs"
        warned_source.write_text("void main() {\n  return;\n  print(1);\n}\n", encoding="utf-8")
        warning = "warned source.azs:3:3: {}: in function main: unreachable statement after return [-W{}unreachable-code]"
        warned = _run([compiler, warned_source, "-o", "warned/program.abd"], cwd, compiler_environment)
        _require(warned.stderr.splitlines() == [warning.format("warning", "")],
                 f"A warning was not reported on a successful compilation: {warned.stderr!r}")
        _outputs(cwd / "warned/program.abd", cwd / "warned/program.ast.json", cwd / "warned/program.exec.json")
        strict = _run([compiler, warned_source, "-o", "warned/strict.abd", "-Werror"], cwd, compiler_environment,
                      succeeds=False)
        _require(strict.returncode == 1 and strict.stderr.splitlines() == [
                     warning.format("error", "error="), "Error: compilation failed: 1 error"],
                 f"-Werror did not fail the compilation: {strict.returncode} {strict.stderr!r}")
        for name in ("strict.abd", "strict.ast.json", "strict.exec.json"):
            _require(not (cwd / "warned" / name).exists(), f"-Werror wrote {name}")
        _run([compiler, source, "-o"], cwd, compiler_environment, succeeds=False)
        _run([compiler, source, "-o", source], cwd, compiler_environment, succeeds=False)
        _require(source.read_bytes() == original_source, "Rejected output alias changed source")
        _run([runner, cwd / "missing script.abd"], cwd, environment, succeeds=False)
        passed("warnings keep the outputs; -Werror and compile/runner failures return nonzero and preserve input")

        result = _run([runner, default_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Hello, AzScript!", "42"],
                 f"Default main execution produced unexpected output: {result.stdout!r}")
        passed("run wrapper starts main 0x0fff0000")

        classes_abd = cwd / "class output/classes.exec.abd"
        _run([compiler, package / "examples/classes.azs", "-o", classes_abd], cwd,
             compiler_environment)
        result = _run([runner, classes_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["3", "7", "5", "destroy Point(5)", "destroy Point(4)",
                                               "destroy Point(7)", "destroy Point(3)", "0"],
                 f"Class example or destructor order failed: {result.stdout!r}")
        passed("literal objects, pointer objects and destructor execution")

        for name, expected in (("loops-regressions", "9"), ("inheritance-regressions", "0"), ("address-regressions", "0"), ("operators-regressions", "0"), ("virtual-regressions", "0")):
            output = cwd / "language output" / (name + ".exec.abd")
            _run([compiler, package / "examples" / (name + ".azs"), "-o", output], cwd, compiler_environment)
            ast = output.with_name(name + ".ast.json")
            _outputs(output, ast, output.with_name(name + ".exec.json"))
            result = _run([runner, output], cwd, environment)
            _require(result.stdout.splitlines() == [expected], f"{name} failed: {result.stdout!r}")
            rebuilt = output.with_name(name + ".roundtrip.abd")
            _run([compiler, "compile-json", ast, "-o", rebuilt], cwd, compiler_environment)
            _require(rebuilt.read_bytes() == output.read_bytes(), f"{name} AST roundtrip differs")
            passed(name + " source, AST and standalone execution")

        main_abd = cwd / "multifile output/main.exec.abd"
        library_abd = cwd / "multifile output/point.exec.abd"
        for source_name, target in (("main", main_abd), ("point", library_abd)):
            _run([compiler, package / f"examples/multifile/{source_name}.azs", "-o", target],
                 cwd, compiler_environment)
        result = _run([runner, main_abd, "--insert", library_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["library-load", "main-load", "42", "44",
                                               "point:42", "point:41", "42"],
                 f"Multi-file class library failed: {result.stdout!r}")
        passed("independent class library compilation, hint linking and destructors")

        math_output = cwd / "math output"
        math_abd = math_output / "math.exec.abd"
        _run([compiler, package / "stdlib/math.azs", "-o", math_abd], cwd, compiler_environment)
        _outputs(math_abd, math_output / "math.ast.json", math_output / "math.exec.json")
        _require(math_abd.read_bytes() == (package / "stdlib/math.exec.abd").read_bytes(),
                 "Rebuilding relocated math library changed its executable bytes")
        math_view = json.loads((math_output / "math.exec.json").read_text(encoding="utf-8"))
        _require(math_view["namespace-hint"] == "AZSCRIPT_MATH", "Math library lost its hint")
        math_client = math_output / "client.exec.abd"
        _run([compiler, package / "examples/math-regressions.azs", "-o", math_client],
             cwd, compiler_environment)
        client_view = json.loads((math_output / "client.exec.json").read_text(encoding="utf-8"))
        _require(len(client_view["f"]) == 2, "Math header copied implementation into the consumer")
        missing = _run([runner, math_client], cwd, environment, succeeds=False)
        _require("AZSCRIPT_MATH" in missing.stderr, "Missing math library did not identify its hint")
        result = _run([runner, math_client, "--insert", package / "stdlib/math.exec.abd"], cwd, environment)
        _require(result.stdout.splitlines() == ["0"], f"Packaged math library regression failed: {result.stdout!r}")
        passed("precompiled math hint library, relocated rebuild and separate consumer")

        # The launcher searches the package's stdlib/ after every -I directory, so a client
        # outside examples/ includes the math header by name, with no -I at all.
        plain_client = source_directory / "plain math client.azs"
        plain_client.write_text('#include "math.include.azs"\n'
                                'int main() { return math_round(math_hypot(3.0, 4.0)); }\n', encoding="utf-8")
        _run([compiler, plain_client], cwd, compiler_environment)
        plain_abd = source_directory / "plain math client.exec.abd"
        _outputs(plain_abd, source_directory / "plain math client.ast.json", source_directory / "plain math client.exec.json")
        result = _run([runner, plain_abd, "--insert", package / "stdlib/math.exec.abd"], cwd, environment)
        _require(result.stdout.splitlines() == ["5"], f"Client of the default include directory failed: {result.stdout!r}")
        include_directory = work / "include dir with spaces"
        include_directory.mkdir()
        (include_directory / "offset.azs").write_text("int offset() { return 1; }\n", encoding="utf-8")
        named_client = source_directory / "math client.azs"
        named_client.write_text('#include "math.include.azs"\n#include "offset.azs"\n'
                                'int main() { return math_round(math_hypot(3.0, 4.0)) + offset(); }\n', encoding="utf-8")
        _run([compiler, named_client, "-I", include_directory], cwd, compiler_environment)
        named_abd = source_directory / "math client.exec.abd"
        named_ast = source_directory / "math client.ast.json"
        _outputs(named_abd, named_ast, source_directory / "math client.exec.json")
        # File names are relative to their include root, independent of the package location.
        names = [entry["name"] for entry in json.loads(named_ast.read_text(encoding="utf-8"))["sources"]]
        _require(names == ["math client.azs", "math.include.azs", "offset.azs"], f"Unexpected include names: {names!r}")
        result = _run([runner, named_abd, "--insert", package / "stdlib/math.exec.abd"], cwd, environment)
        _require(result.stdout.splitlines() == ["6"], f"Client of the default include directory failed: {result.stdout!r}")
        missing = _run([compiler, named_client, "-o", "missing include/client.abd"], cwd, compiler_environment, succeeds=False)
        _require('error: Include not found: "offset.azs"' in missing.stderr and str((package / "stdlib").resolve()) in missing.stderr,
                 f"A missing include did not list the package stdlib/: {missing.stderr!r}")
        absolute_client = source_directory / "absolute client.azs"
        absolute_client.write_text(f'#include "{(package / "stdlib/math.include.azs").as_posix()}"\n'
                                   'int main() { return math_round(math_hypot(3.0, 4.0)); }\n', encoding="utf-8")
        rejected = _run([compiler, absolute_client, "-o", "absolute/client.abd"], cwd, compiler_environment, succeeds=False)
        _require("error: Absolute include path" in rejected.stderr and "--unrestricted-includes" in rejected.stderr,
                 f"An absolute include was not rejected: {rejected.stderr!r}")
        _require(not (cwd / "absolute").exists(), "A rejected include wrote outputs")
        _run([compiler, absolute_client, "-o", "absolute/client.abd", "--unrestricted-includes"], cwd, compiler_environment)
        _outputs(cwd / "absolute/client.abd", cwd / "absolute/client.ast.json", cwd / "absolute/client.exec.json")
        passed("default stdlib include directory, -I with spaces and the absolute include policy")

        containers_output = cwd / "containers output"
        containers_abd = containers_output / "containers.exec.abd"
        _run([compiler, package / "stdlib/containers.azs", "-o", containers_abd], cwd, compiler_environment)
        _outputs(containers_abd, containers_output / "containers.ast.json", containers_output / "containers.exec.json")
        _require(containers_abd.read_bytes() == (package / "stdlib/containers.exec.abd").read_bytes(),
                 "Rebuilding relocated container library changed its executable bytes")
        containers_view = json.loads((containers_output / "containers.exec.json").read_text(encoding="utf-8"))
        _require(containers_view["namespace-hint"] == "AZSCRIPT_CONTAINERS", "Container library lost its hint")
        containers_client = containers_output / "client.exec.abd"
        _run([compiler, package / "examples/containers-regressions.azs", "-o", containers_client],
             cwd, compiler_environment)
        containers_client_view = json.loads((containers_output / "client.exec.json").read_text(encoding="utf-8"))
        containers_client_ast = json.loads((containers_output / "client.ast.json").read_text(encoding="utf-8"))
        client_names = {
            function["metadata"]["name"]
            for group in containers_client_ast["body"].values() for function in group.values()
        }
        _require(client_names == {"main", "containers_check", "Point::<ctor>"},
                 "Container header copied implementation into the consumer")
        _require(containers_client_view["assume-hints"] == [{"hint": "AZSCRIPT_CONTAINERS", "namespace": 0xc071}],
                 "Container client lost its assumed namespace")
        missing = _run([runner, containers_client], cwd, environment, succeeds=False)
        _require("AZSCRIPT_CONTAINERS" in missing.stderr, "Missing container library did not identify its hint")
        result = _run([runner, containers_client, "--insert", package / "stdlib/containers.exec.abd"], cwd, environment)
        _require(result.stdout.splitlines() == ["0"], f"Packaged container library regression failed: {result.stdout!r}")
        passed("precompiled container hint library, relocated rebuild and separate consumer")

        # Exercise every post-literal-object opcode through the exported tools.
        # Merely bumping the root version misses stale opcode ranges and SDKs.
        modern_source = source_directory / "generic buffer bitwise operators.azs"
        modern_source.write_text('''#namespace 1234
#gvar drops
class Item { int value = 7; ~Item() { drops = drops + 1; } }
class VirtualBase { int field = 7; virtual int read() { return field; } }
class VirtualChild : VirtualBase { override int read() { return VirtualBase::read() + 100; } }
<T extends VirtualBase*> int virtual_read(T value) { return value.read(); }
class Box<T> { T value; T get() { return value; } }
class Indexed<T> {
    buffer<T> data;
    T operator[](int index) { return data.get(index); }
    void operator[]=(int index, T value) { data.set(index, value); }
    int operator()(int extra) { return data.length() + extra; }
}
class Number {
    int value;
    Number(int n) { value = n; }
    Number operator+(Number rhs) { Number result(value + rhs.value); return result; }
}
<T> T invoke(int id, T value) { return reflect_invoke_function<T>(id, value); }
int increment(int value):0042 { return value + 1; }
Item* checked_item():0070 { return new Item(); }
int main() {
    drops = 0;
    {
        buffer<Item> things; things.reserve(2); things.resize(1);
        buffer<Item> clone = things; clone.resize(0);
        if (things.get(0).value != 7) return -1;
    }
    if (drops < 3) return -2;
    Box<int> zero; if (zero.get() != 0) return -3;
    Indexed<int> values; values.data.reserve(2); values.data.push(7); values[0] = 40;
    buffer<int> copy = values.data; copy.set(0, 9);
    if (values[0] != 40 || values(3) != 4) return -4;
    buffer<buffer<int>> nested; nested.reserve(1); nested.push(copy);
    if (nested.get(0).get(0) != 9) return -5;
    Number a(1); Number b(2); if ((a + b).value != 3) return -6;
    int bits = 10; int mask = 12; int shift = 1; int negative = -8;
    if ((bits & mask) != 8 || (bits | mask) != 14 || (bits ^ mask) != 6) return -7;
    if (~bits != -11 || (negative >> shift) != -4 || (negative >>> shift) != 2147483644) return -8;
    if ((bits << shift) != 20 || (1 << 31) != (-2147483647 - 1)) return -9;
    bits &= mask; bits |= 2; bits ^= 4;
    bits <<= 2; bits >>= 1; bits >>>= 1;
    if (bits != 14) return -10;
    int truncated = int(-7.75); float narrowed = float(3.5); double widened = double(6.25f);
    if (truncated != -7 || narrowed != 3.5f || widened != 6.25) return -11;
    if (int("42") != 42 || float("1.5") != 1.5f || double("2.5e1") != 25.0) return -12;
    if (string(42) != "42" || string(1.5f) != "1.5" || string(2.5) != "2.5") return -13;
    address raw = alloc(1); address same = address(raw);
    if (same != raw || string(raw) != "" + raw) return -14;
    def(dynamic_raw, raw); def(dynamic_int, 42); def(dynamic_string, "same");
    if (address(dynamic_raw) != raw || int(dynamic_int) != 42 || string(dynamic_string) != "same") return -15;
    int(bits);
    mem_free(raw);
    def(choose, true);
    int first = choose ? 42 : 1 / 0; int second = false ? 1 / 0 : 42;
    int associated = false ? 1 : true ? 42 : 2;
    if (first != 42 || second != 42 || associated != 42) return -16;
    float floatChoice = true ? 7 : 1.5f; double doubleChoice = false ? 1.5 : 7;
    double floatToDoubleChoice = true ? 1.25f : 2.5;
    if (floatChoice != 7.0f || doubleChoice != 7.0 || floatToDoubleChoice != 1.25) return -17;
    int switched = 0;
    for (int index = 0; index < 4; index++) {
        switch (index) {
            case 0: case 1: switched += index + 1; break;
            default: switched += 10; break;
            case 2: continue;
        }
        switched += 100;
    }
    if (switched != 313) return -18;
    string label = "ready";
    switch (label) {
        case "other": return -19;
        default: return -20;
        case "ready": switched += 1;
    }
    if (switched != 314) return -21;
    string text = "A中😀\\u0000Z";
    if (str_length(text) != 5 || str_byte_length(text) != 10) return -40;
    if (str_substring(text, 1, 3) != "中😀" || str_char_at(text, 2) != "😀") return -41;
    if (str_code_at(text, 2) != 128512 || str_from_code(128512) != "😀") return -42;
    if (str_index_of(text, "😀") != 2 || str_index_of(text, "", 5) != 5) return -43;
    if (!str_starts_with(text, "A中") || !str_ends_with(text, "Z")) return -44;
    Item* closed = new Item(); closed.value = 9;
    if (closed.value != 9 || address(closed) != address(closed)) return -45;
    delete closed;
    Item* checked = reflect_invoke_function<Item*>(305397872);
    if (checked.value != 7) return -46;
    delete checked;
    VirtualBase* virtual_item = new VirtualChild();
    if (virtual_read(virtual_item) != 107) return -47;
    delete virtual_item;
    return invoke<int>(305397826, values[0]) + value_compare<string>("b", "a");
}
''', encoding="utf-8")
        modern_abd = cwd / "modern output/features.exec.abd"
        modern_ast = modern_abd.with_name("features.ast.json")
        modern_json = modern_abd.with_name("features.exec.json")
        _run([compiler, modern_source, "-o", modern_abd], cwd, compiler_environment)
        _outputs(modern_abd, modern_ast, modern_json)
        modern_view = json.loads(modern_json.read_text(encoding="utf-8"))
        modern_opcodes = _control_opcodes(modern_view["f"], str(modern_json))
        _require(set(range(36, 43)) <= modern_opcodes,
                 f"Generic/buffer probe must exercise operation contexts and all exec v{EXEC_VERSION} buffer instructions")
        _require(set(range(43, 49)) <= modern_opcodes,
                 f"Bitwise/shift probe must exercise all exec v{EXEC_VERSION} bitwise and shift instructions")
        _require(49 in modern_opcodes,
                 f"Conversion probe must exercise the exec v{EXEC_VERSION} convert instruction")
        _require(50 in modern_opcodes,
                 f"Conditional expression probe must exercise the exec v{EXEC_VERSION} select instruction")

        def builtin_calls(value):
            if isinstance(value, dict):
                if value.get("t") == 1:
                    yield value["id"]
                for child in value.values():
                    yield from builtin_calls(child)
            elif isinstance(value, list):
                for child in value:
                    yield from builtin_calls(child)

        _require(set(range(0x0abd000b, 0x0abd0014)) <= set(builtin_calls(modern_view["f"])),
                 "String probe must exercise all nine Unicode string builtins")
        _require(0x0abd0014 in set(builtin_calls(modern_view["f"])),
                 "Heap object construction must use the tagged internal object allocator")
        _require(51 in modern_opcodes, "Class reflection must emit checked references")
        _require({52, 53} <= modern_opcodes, "Virtual construction and dispatch must remain in the packaged feature probe")
        modern_roundtrip = modern_abd.with_name("features.roundtrip.abd")
        _run([compiler, "compile-json", modern_ast, "-o", modern_roundtrip], cwd, compiler_environment)
        _require(modern_abd.read_bytes() == modern_roundtrip.read_bytes(),
                 "Packaged feature probe AST roundtrip changed executable bytes")
        result = _run([runner, modern_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["42"],
                 f"Packaged feature probe failed: {result.stdout!r}")
        passed("generic contexts, owning buffers, class operators, reflection, bitwise, shifts, conversions, conditional expressions, switch and Unicode strings through relocated tools")

        standalone = work / "standalone runner"
        standalone_bin = standalone / "bin"
        standalone_bin.mkdir(parents=True)
        standalone_runner = standalone_bin / ("azscript-run" + EXECUTABLE_SUFFIX)
        shutil.copy2(package / "bin" / standalone_runner.name, standalone_runner)
        # The runner and native plugins now share abdInvoker's registry. Copy
        # that runtime and its platform runtime dependencies, without the JARs,
        # Java runtime, JNI bridge, headers or compiler from the distribution.
        if WINDOWS:
            runtime_names = {"abdinvoker.dll", "libabdinvoker.dll"}
            runtime_libraries = [library for library in (package / "bin").glob("*.dll")
                                 if library.name.lower() in runtime_names]
            standalone_lib = standalone_bin
            for library in (package / "bin").glob("*.dll"):
                name = library.name.lower()
                if not name.startswith(("abd", "libabd")) and "azertian" not in name:
                    shutil.copy2(library, standalone_bin / library.name)
        else:
            standalone_lib = standalone / "lib"
            standalone_lib.mkdir()
            pattern = "libabdInvoker*.dylib" if sys.platform == "darwin" else "libabdInvoker.so*"
            runtime_libraries = list((package / "lib").glob(pattern))
        _require(bool(runtime_libraries), "Missing shared AzScript runtime for standalone runner")
        for library in runtime_libraries:
            shutil.copy2(library, standalone_lib / library.name)
        standalone_environment = environment.copy()
        standalone_environment["JAVA_HOME"] = str(work / "missing standalone JDK")
        standalone_environment["PATH"] = str(standalone_bin)
        result = _run([standalone_runner, default_abd], cwd, standalone_environment)
        _require(result.stdout.splitlines() == ["Hello, AzScript!", "42"],
                 "Runner with its shared runtime could not execute main without Java")
        passed("standalone bin/lib runtime works without Java or compiler artifacts")

        common_cmake = [f"-DCMAKE_PREFIX_PATH={package}", "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_PACKAGE_NO_PACKAGE_REGISTRY=ON"]
        static_build = work / "static consumer build"
        _run([cmake, "-S", package / "examples/cpp", "-B", static_build, *common_cmake], cwd, environment)
        _run([cmake, "--build", static_build, "--config", "Release", "--parallel", "2"], cwd, environment)
        result = _run([_host_executable(static_build), default_abd], cwd, environment)
        _require("Host received: 42" in result.stdout, "Static C++ consumer returned an unexpected value")
        result = _run([_host_executable(static_build), modern_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Host received: 42"],
                 "Static C++ consumer could not execute packaged language features")
        passed("relocated CMake package and static C++ consumer")

        shared_source = work / "shared consumer source"
        shared_source.mkdir()
        shutil.copyfile(package / "examples/cpp/main.cpp", shared_source / "main.cpp")
        cmake_template = (package / "examples/cpp/CMakeLists.txt").read_text(encoding="utf-8")
        _require("AzScript::Runtime)" in cmake_template, "Unexpected C++ example target")
        (shared_source / "CMakeLists.txt").write_text(
            cmake_template.replace("AzScript::Runtime)", "AzScript::RuntimeShared)"), encoding="utf-8")
        shared_build = work / "shared consumer build"
        _run([cmake, "-S", shared_source, "-B", shared_build, *common_cmake], cwd, environment)
        _run([cmake, "--build", shared_build, "--config", "Release", "--parallel", "2"], cwd, environment)
        result = _run([_host_executable(shared_build), default_abd], cwd, environment)
        _require("Host received: 42" in result.stdout, "Shared C++ consumer returned an unexpected value")
        result = _run([_host_executable(shared_build), modern_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Host received: 42"],
                 "Shared C++ consumer could not execute packaged language features")
        passed("relocated CMake package and shared C++ consumer")

        result = _run([java_executable, "-Xcheck:jni", "--class-path", package / "java/abdJavaInvoker.jar",
                       package / "examples/java/RunScript.java", package, default_abd], cwd, environment)
        _require("Host received: 42" in result.stdout, "Java JNI consumer returned an unexpected value")
        _require("WARNING in native method" not in result.stdout + result.stderr,
                 "JVM reported a JNI contract warning")
        passed("real Java source-file JNI consumer with -Xcheck:jni")

        result = _run([java_executable, "-Xcheck:jni", "--class-path", package / "java/abdJavaInvoker.jar",
                       package / "examples/java/RunScript.java", package, modern_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Host received: 42"],
                 "Java JNI consumer could not execute packaged language features")
        _require("WARNING in native method" not in result.stdout + result.stderr,
                 "JVM reported a JNI contract warning for packaged language features")
        passed("real JNI execution of generic contexts, buffers, class operators, bitwise, shifts, conversions, conditional expressions and switch")

        native_name = "abdJ.dll" if WINDOWS else ("libabdJ.dylib" if sys.platform == "darwin" else "libabdJ.so")
        native_library = package / ("bin" if WINDOWS else "lib") / native_name
        result = _run([java_executable, "-Xcheck:jni", f"-Dazertia.native.library={native_library}",
                       "--class-path", package / "java/abdJavaInvoker.jar", "azertia.Main",
                       modern_abd, "0x0fff0000"], cwd, environment)
        _require(result.stdout.splitlines() == ["42"], "Packaged minimal Java host did not flush and invoke")
        _require("WARNING in native method" not in result.stdout + result.stderr,
                 "JVM reported a JNI contract warning for the minimal host")
        passed("packaged minimal Java host links and initializes before invocation")

    return {"passed": len(checks), "checks": checks, "bundled_java": bundled_java.is_file()}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package", type=Path, help="Exported package directory")
    parser.add_argument("--java", type=Path, help="JDK java executable for a package without runtime/")
    arguments = parser.parse_args()
    try:
        summary = verify(arguments.package, arguments.java)
    except (RuntimeError, OSError, ValueError) as error:
        print(f"Distribution verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
