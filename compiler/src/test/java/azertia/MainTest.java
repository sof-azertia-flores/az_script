package azertia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Command-line include options: {@code -I}, {@code --include-dir}, {@code --unrestricted-includes} and default directories. */
class MainTest {
    @TempDir Path temporary;

    private record Result(int status, String out, String err) {}
    private static Result run(List<Path> defaults, Object... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        String[] strings = new String[args.length];
        for (int i = 0; i < args.length; i++) strings[i] = args[i].toString();
        int status = Main.run(strings, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8), defaults);
        return new Result(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
    private static Result run(Object... args) { return run(List.of(), args); }
    private Path write(String name, String content) throws Exception {
        Path path = temporary.resolve(name); Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8); return path;
    }

    @Test void includeOptionsMayAppearAnywhereAndRepeat() throws Exception {
        write("inc/a.azs", "int a() { return 1; }\n");
        write("lib/b.azs", "int b() { return 2; }\n");
        Path source = write("app/main.azs", "#include \"a.azs\"\n#include \"b.azs\"\nint main() { return a() + b(); }\n");
        Path output = temporary.resolve("out/main.abd");
        Result result = run("compile", "-I", temporary.resolve("inc"), source, "--include-dir", temporary.resolve("lib"), "-o", output);
        assertEquals(0, result.status(), result.err());
        assertEquals("", result.err());
        assertTrue(Files.size(output) > 0);
        Main.CompileArguments parsed = Main.parseCompileArguments(List.of("compile", "-I", "first", "x.azs", "--include-dir", "second", "-I", "first"));
        assertEquals(List.of(Path.of("first"), Path.of("second"), Path.of("first")), parsed.includeDirectories());
        assertFalse(parsed.unrestrictedIncludes());
        // Without the directories the headers are not found.
        Result missing = run("compile", source, "-o", output);
        assertEquals(1, missing.status());
        assertTrue(missing.err().startsWith("main.azs:1:1: error: Include not found: \"a.azs\" (searched "), missing.err());
    }

    @Test void unrestrictedIncludesMayBeTheLastArgument() throws Exception {
        Path header = write("elsewhere/h.azs", "int h() { return 3; }\n");
        Path source = write("app/main.azs", "#include \"" + header.toRealPath() + "\"\nint main() { return h(); }\n");
        Path output = temporary.resolve("app/main.abd");
        Result rejected = run("compile", source, "-o", output);
        assertEquals(1, rejected.status());
        assertTrue(rejected.err().startsWith("main.azs:1:1: error: Absolute include path \"" + header.toRealPath() + "\" is not allowed;"), rejected.err());
        assertTrue(rejected.err().endsWith("Error: compilation failed: 1 error\n"), rejected.err());
        assertFalse(Files.exists(output));
        assertEquals(0, run("compile", source, "-o", output, "--unrestricted-includes").status());
        assertTrue(Main.parseCompileArguments(List.of("compile", "x.azs", "--unrestricted-includes")).unrestrictedIncludes());
    }

    @Test void includeDirectoriesNeedAnExistingDirectory() throws Exception {
        Path source = write("main.azs", "int main() { return 0; }\n");
        for (String option : List.of("-I", "--include-dir")) {
            Result missing = run("compile", source, option);
            assertEquals(1, missing.status());
            assertEquals("Error: Missing value for " + option + "\n", missing.err());
        }
        Result absent = run("compile", source, "-I", temporary.resolve("absent"), "-o", temporary.resolve("main.abd"));
        assertEquals(1, absent.status());
        assertEquals("Error: Include directory not found: " + temporary.resolve("absent") + "\n", absent.err());
        Result file = run("compile", source, "-I", source, "-o", temporary.resolve("main.abd"));
        assertEquals("Error: Include directory is not a directory: " + source + "\n", file.err());
        // An empty value, e.g. from an unset variable, does not stand for the working directory.
        for (String option : List.of("-I", "--include-dir")) {
            Result empty = run("compile", source, option, "", "-o", temporary.resolve("main.abd"));
            assertEquals(1, empty.status());
            assertEquals("Error: Empty directory for " + option + "; use . for the working directory\n", empty.err());
        }
        assertFalse(Files.exists(temporary.resolve("main.abd")));
    }

    @Test void compileJsonRejectsIncludeOptions() throws Exception {
        Path source = write("main.azs", "int main() { return 0; }\n");
        Path tree = temporary.resolve("main.ast.json");
        assertEquals(0, run("compile", source, "-o", temporary.resolve("main.abd"), "--ast", tree).status());
        for (List<String> options : List.of(List.of("-I", "inc"), List.of("--include-dir", "inc"), List.of("--unrestricted-includes"))) {
            Object[] args = new Object[4 + options.size()];
            args[0] = "compile-json"; args[1] = tree; args[2] = "-o"; args[3] = temporary.resolve("again.abd");
            for (int i = 0; i < options.size(); i++) args[4 + i] = options.get(i);
            Result result = run(args);
            assertEquals(1, result.status(), options.toString());
            assertEquals("Error: -I, --include-dir and --unrestricted-includes apply only to compile\n", result.err());
        }
        assertFalse(Files.exists(temporary.resolve("again.abd")));
    }

    @Test void defaultDirectoriesAreSearchedAfterEveryIncludeDirectory() throws Exception {
        Path stdlib = Files.createDirectories(temporary.resolve("stdlib")).toRealPath();
        Path lib = Files.createDirectories(temporary.resolve("lib")).toRealPath();
        write("stdlib/shared.azs", "int from_stdlib() { return 1; }\n");
        Path source = write("app/main.azs", "#include \"shared.azs\"\nint main() { return 0; }\n");
        Path tree = temporary.resolve("app/main.ast.json");
        Result found = run(List.of(stdlib), "compile", source, "-o", temporary.resolve("app/main.abd"), "--ast", tree);
        assertEquals(0, found.status(), found.err());
        assertTrue(Files.readString(tree).contains("\"from_stdlib\""));
        // An -I directory comes first.
        write("lib/shared.azs", "int from_lib() { return 2; }\n");
        assertEquals(0, run(List.of(stdlib), "compile", source, "-I", lib, "-o", temporary.resolve("app/main.abd"), "--ast", tree).status());
        assertTrue(Files.readString(tree).contains("\"from_lib\"") && !Files.readString(tree).contains("from_stdlib"));
        // A missing default directory is skipped silently, and searched paths list the defaults last.
        Path missing = write("app/missing.azs", "#include \"none.azs\"\nint main() { return 0; }\n");
        Result notFound = run(List.of(temporary.resolve("absent"), stdlib), "compile", missing, "-I", lib, "-o", temporary.resolve("app/m.abd"));
        assertEquals(1, notFound.status());
        assertEquals("missing.azs:1:1: error: Include not found: \"none.azs\" (searched " + source.toRealPath().resolveSibling("none.azs")
                + ", " + lib.resolve("none.azs") + ", " + stdlib.resolve("none.azs") + ")\nError: compilation failed: 1 error\n", notFound.err());
        // The plain overload has no defaults.
        assertEquals(1, run("compile", source, "-o", temporary.resolve("app/main.abd")).status());
    }

    @Test void defaultDirectoryComesFromTheStdlibProperty() throws Exception {
        String previous = System.getProperty(Main.STDLIB_PROPERTY);
        try {
            System.clearProperty(Main.STDLIB_PROPERTY);
            assertEquals(List.of(), Main.defaultIncludeDirectories());
            // One path, not a list: a path separator is part of the name.
            Path directory = Files.createDirectories(temporary.resolve("std" + java.io.File.pathSeparator + "lib"));
            System.setProperty(Main.STDLIB_PROPERTY, directory.toString());
            assertEquals(List.of(directory), Main.defaultIncludeDirectories());
            System.setProperty(Main.STDLIB_PROPERTY, temporary.resolve("absent").toString());
            assertEquals(List.of(), Main.defaultIncludeDirectories());
        } finally {
            if (previous == null) System.clearProperty(Main.STDLIB_PROPERTY); else System.setProperty(Main.STDLIB_PROPERTY, previous);
        }
    }

    @Test void outputsMustNotOverwriteAnIncludedSource() throws Exception {
        Path header = write("lib/header.azs", "int h() { return 1; }\n");
        Path source = write("main.azs", "#include \"lib/header.azs\"\nint main() { return h(); }\n");
        String original = Files.readString(header);
        for (String option : List.of("-o", "--ast", "--exec-json")) {
            Result result = option.equals("-o") ? run("compile", source, "-o", header)
                    : run("compile", source, "-o", temporary.resolve("main.abd"), option, temporary.resolve("lib/../lib/header.azs"));
            assertEquals(1, result.status(), option);
            assertTrue(result.err().startsWith("Error: Output must not overwrite an included source: "), result.err());
            assertEquals(original, Files.readString(header));
        }
        assertFalse(Files.exists(temporary.resolve("main.abd")));
    }

    @Test void helpNamesTheIncludeOptions() {
        String help = run("--help").out();
        assertTrue(help.contains("-I dir, --include-dir dir") && help.contains("--unrestricted-includes"), help);
        assertTrue(help.contains("Includes are searched in the including file's directory, then in each -I directory"), help);
    }
}
