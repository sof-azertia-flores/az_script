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
            _require(decoded.get("exec-version") == 5, "Expected exec v5 output")
            def inspect(value):
                if isinstance(value, dict):
                    if value.get("t") == 0:
                        _require(type(value.get("c")) is int and 3 <= value["c"] <= 29,
                                 "Control instruction must use a numeric opcode")
                    for child in value.values():
                        inspect(child)
                elif isinstance(value, list):
                    for child in value:
                        inspect(child)
            inspect(decoded["f"])


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
                package / "docs/HINT_LINKING.md", package / "examples/multifile/point.include.azs",
                package / "stdlib/math.azs", package / "stdlib/math.include.azs", package / "stdlib/MATH.md",
                package / "stdlib/math.exec.abd", package / "stdlib/math.ast.json", package / "stdlib/math.exec.json",
                package / "examples/hello.azs", package / "examples/classes.azs",
                package / "examples/cpp/CMakeLists.txt", package / "examples/cpp/main.cpp",
                package / "examples/java/RunScript.java"]
    for path in required:
        _require(path.is_file(), f"Missing distribution component: {path}")
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
        passed("explicit AST and executable JSON locations")

        ast_bytes = default_ast.read_bytes()
        _run([compiler, "compile-json", default_ast, "-o", "roundtrip/from ast.abd"], cwd,
             compiler_environment)
        _require((cwd / "roundtrip/from ast.abd").read_bytes() == default_abd.read_bytes(),
                 "Source to AST to ABD roundtrip changed executable bytes")
        _require((cwd / "roundtrip/from ast.exec.json").is_file(), "compile-json omitted exec JSON")
        _require(default_ast.read_bytes() == ast_bytes, "compile-json overwrote the input AST")
        passed("AST roundtrip is byte-identical and preserves its input")

        broken_source = source_directory / "invalid source.azs"
        broken_source.write_text("int main() { return missing_name; }\n", encoding="utf-8")
        _run([compiler, broken_source], cwd, compiler_environment, succeeds=False)
        _run([compiler, source, "-o"], cwd, compiler_environment, succeeds=False)
        _run([compiler, source, "-o", source], cwd, compiler_environment, succeeds=False)
        _require(source.read_bytes() == original_source, "Rejected output alias changed source")
        _run([runner, cwd / "missing script.abd"], cwd, environment, succeeds=False)
        passed("compile/runner failures return nonzero and preserve input")

        result = _run([runner, default_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["Hello, AzScript!", "42"],
                 f"Default main execution produced unexpected output: {result.stdout!r}")
        passed("run wrapper starts main 0x0fff0000")

        classes_abd = cwd / "class output/classes.exec.abd"
        _run([compiler, package / "examples/classes.azs", "-o", classes_abd], cwd,
             compiler_environment)
        result = _run([runner, classes_abd], cwd, environment)
        _require(result.stdout.splitlines() == ["7", "5", "destroy Point(5)", "destroy Point(7)", "0"],
                 f"Class example or destructor order failed: {result.stdout!r}")
        passed("class construction, references and destructor execution")

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

        standalone = work / "standalone runner"
        standalone.mkdir()
        standalone_runner = standalone / ("azscript-run" + EXECUTABLE_SUFFIX)
        shutil.copy2(package / "bin" / standalone_runner.name, standalone_runner)
        standalone_environment = environment.copy()
        if WINDOWS:
            for library in (package / "bin").glob("*.dll"):
                if not library.name.lower().startswith("abd") and "azertian" not in library.name.lower():
                    shutil.copy2(library, standalone / library.name)
            standalone_environment["PATH"] = os.pathsep.join(
                entry for entry in environment.get("PATH", "").split(os.pathsep)
                if entry and not Path(entry).resolve().is_relative_to(package))
        result = _run([standalone_runner, default_abd], cwd, standalone_environment)
        _require(result.stdout.splitlines() == ["Hello, AzScript!", "42"],
                 "Runner copied without AzScript libraries could not execute main")
        passed("standalone runner works without separate AzScript shared libraries")

        common_cmake = [f"-DCMAKE_PREFIX_PATH={package}", "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_PACKAGE_NO_PACKAGE_REGISTRY=ON"]
        static_build = work / "static consumer build"
        _run([cmake, "-S", package / "examples/cpp", "-B", static_build, *common_cmake], cwd, environment)
        _run([cmake, "--build", static_build, "--config", "Release", "--parallel", "2"], cwd, environment)
        result = _run([_host_executable(static_build), default_abd], cwd, environment)
        _require("Host received: 42" in result.stdout, "Static C++ consumer returned an unexpected value")
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
        passed("relocated CMake package and shared C++ consumer")

        result = _run([java_executable, "-Xcheck:jni", "--class-path", package / "java/abdJavaInvoker.jar",
                       package / "examples/java/RunScript.java", package, default_abd], cwd, environment)
        _require("Host received: 42" in result.stdout, "Java JNI consumer returned an unexpected value")
        _require("WARNING in native method" not in result.stdout + result.stderr,
                 "JVM reported a JNI contract warning")
        passed("real Java source-file JNI consumer with -Xcheck:jni")

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
