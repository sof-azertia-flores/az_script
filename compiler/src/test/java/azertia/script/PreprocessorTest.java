package azertia.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Source reading, include search paths and roots, {@code #pragma once} and the preprocessing limits. */
class PreprocessorTest {
    @TempDir Path temporary;

    private Path write(String name, String content) throws Exception {
        Path path = temporary.resolve(name); Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8); return path;
    }
    private static GeneraterJson.AzScript parse(Path source) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script;
    }
    private static CompileException failure(Path source) {
        return assertThrows(CompileException.class, () -> Compiler.compile(parse(source).toObj()));
    }
    private static CompileException failure(String source) {
        return assertThrows(CompileException.class, () -> {
            var script = new GeneraterJson.AzScript(); script.execute(source); Compiler.compile(script.toObj());
        });
    }
    private static String names(GeneraterJson.AzScript script) {
        return String.join(",", script.functions.stream().map(function -> function.name).toList());
    }
    private static GeneraterJson.AzScript parse(Path source, Path... directories) throws Exception {
        var script = new GeneraterJson.AzScript(); script.includeDirectories.addAll(List.of(directories));
        script.execute(source); return script;
    }
    private static CompileException failure(Path source, Path... directories) {
        return assertThrows(CompileException.class, () -> Compiler.compile(parse(source, directories).toObj()));
    }
    /** The names of the AST {@code sources} table. */
    private static List<String> sources(GeneraterJson.AzScript script) {
        return script.toObj().getAsJsonArray("sources").asList().stream()
                .map(source -> source.getAsJsonObject().get("name").getAsString()).toList();
    }
    private Path directory(String name) throws Exception { return Files.createDirectories(temporary.resolve(name)).toRealPath(); }
    private static final String ESCAPE_REMEDY = "; add a directory with -I/--include-dir or compile with --unrestricted-includes";

    @Test void pragmaOnceExpandsAFileOnce() throws Exception {
        write("inc/once.azs", "#pragma once\nint once() { return 1; }\n");
        Path main = write("main.azs", "#include \"inc/once.azs\"\n#include \"inc/once.azs\"\nint main() { return once(); }\n");
        GeneraterJson.AzScript script = parse(main);
        assertEquals("once,main", names(script));
        // The skipped include adds no source unit.
        assertEquals(2, script.toObj().getAsJsonArray("sources").size());
        assertDoesNotThrow(() -> Compiler.compile(script.toObj()));
        // Identity is the real path, so another spelling of the same file is skipped too.
        Path spelled = write("spelled.azs", "#include \"inc/once.azs\"\n#include \"inc/../inc/./once.azs\"\nint main() { return once(); }\n");
        assertEquals("once,main", names(parse(spelled)));
    }

    @Test void pragmaOnceFilesMayIncludeThemselvesBack() throws Exception {
        // The once check precedes the cycle check, as in C.
        Path self = write("self.azs", "#pragma once\n#include \"self.azs\"\nint main() { return 0; }\n");
        assertEquals("main", names(parse(self)));
        write("a.azs", "#pragma once\n#include \"b.azs\"\nint a() { return b(); }\n");
        write("b.azs", "#include \"a.azs\"\nint b() { return 2; }\n");
        Path main = write("pair.azs", "#include \"a.azs\"\nint main() { return a(); }\n");
        assertEquals("b,a,main", names(parse(main)));
        // Before its #pragma once a file is not yet marked, so including itself is still a cycle.
        Path early = write("early.azs", "#include \"early.azs\"\n#pragma once\nint main() { return 0; }\n");
        assertEquals("early.azs:1:1: error: Include cycle: early.azs -> early.azs", failure(early).getMessage());
    }

    @Test void pragmaOnceInAnInactiveBranchIsIgnored() throws Exception {
        write("guarded.azs", "#ifdef NEVER\n#pragma once\n#pragma anything at all\n#fi\nint once() { return 1; }\n");
        Path twice = write("twice.azs", "#include \"guarded.azs\"\n#include \"guarded.azs\"\nint main() { return once(); }\n");
        assertEquals("guarded.azs:5:9: error: Duplicate function definition: once (the header was included twice; add #pragma once or an include guard)\n"
                + "twice.azs:2:1: note: in file included from here\n"
                + "guarded.azs:5:9: note: previous definition is here\n"
                + "twice.azs:1:1: note: in file included from here", failure(twice).getMessage());
    }

    @Test void onlyPragmaOnceIsAccepted() throws Exception {
        assertEquals("<source>:2:3: error: Missing pragma name; only #pragma once is supported",
                failure("int main() { return 0; }\n  #pragma\n").getMessage());
        assertEquals("<source>:1:1: error: Unknown pragma: twice; only #pragma once is supported",
                failure("#pragma twice\nint main() { return 0; }\n").getMessage());
        assertEquals("<source>:1:1: error: Unexpected text after #pragma once",
                failure("#pragma once now\nint main() { return 0; }\n").getMessage());
        // Unknown names are escaped like other printed source text.
        assertEquals("<source>:1:1: error: Unknown pragma: on\\u202ece; only #pragma once is supported",
                failure("#pragma on\u202ece\nint main() { return 0; }\n").getMessage());
        // A string source cannot be included, so the pragma has nothing to record; a comment may follow it.
        var script = new GeneraterJson.AzScript();
        script.execute("#pragma once // header\nint main() { return 0; }\n");
        assertEquals("main", names(script));
    }

    @Test void includeCountIsBounded() throws Exception {
        // Each of 13 headers includes the next twice: 2^14 - 2 expansions.
        for (int level = 1; level <= 13; level++)
            write("chain/h" + level + ".azs", level == 13 ? "// leaf\n" : "#include \"h" + (level + 1) + ".azs\"\n#include \"h" + (level + 1) + ".azs\"\n");
        Path main = write("chain/main.azs", "#include \"h1.azs\"\n#include \"h1.azs\"\nint main() { return 0; }\n");
        CompileException error = assertTimeout(Duration.ofSeconds(30), () -> failure(main));
        assertEquals(1, error.errors().size(), error.getMessage());
        assertTrue(error.getMessage().contains(": error: Include count exceeds 4096; add #pragma once to headers that are included repeatedly\n"),
                error.getMessage());
        // With #pragma once every header expands once, however often it is included.
        for (int level = 1; level <= 13; level++)
            write("once/h" + level + ".azs", "#pragma once\n" + (level == 13 ? "int leaf() { return 13; }\n"
                    : "#include \"h" + (level + 1) + ".azs\"\n#include \"h" + (level + 1) + ".azs\"\n"));
        Path onceMain = write("once/main.azs", "#include \"h1.azs\"\n".repeat(5000) + "int main() { return leaf(); }\n");
        GeneraterJson.AzScript script = parse(onceMain);
        assertEquals(14, script.toObj().getAsJsonArray("sources").size());
        assertEquals("leaf,main", names(script));
    }

    @Test void aGuardedHeaderMayBeIncluded4000Times() throws Exception {
        write("guard.azs", "#ifndef GUARD\n#define GUARD\nint guarded() { return 4; }\n#fi\n");
        Path main = write("many.azs", "#include \"guard.azs\"\n".repeat(4000) + "int main() { return guarded(); }\n");
        GeneraterJson.AzScript script = assertTimeout(Duration.ofSeconds(30), () -> parse(main));
        JsonObject tree = script.toObj();
        assertEquals(4001, tree.getAsJsonArray("sources").size());
        assertDoesNotThrow(() -> Compiler.compile(tree));
        // compile-json accepts as many sources as the preprocessor can produce, and no more.
        JsonArray sources = tree.getAsJsonArray("sources");
        JsonObject extra = sources.get(1).getAsJsonObject().deepCopy();
        while (sources.size() <= TreeLimits.MAX_INCLUDES) sources.add(extra.deepCopy());
        assertDoesNotThrow(() -> Compiler.compile(tree));
        sources.add(extra);
        assertEquals("<source>: error: Malformed script JSON at /sources: expected at most 4097 sources, got 4098",
                assertThrows(CompileException.class, () -> Compiler.compile(tree)).getMessage());
    }

    @Test void includeNestingIsStillBounded() throws Exception {
        for (int level = 0; level <= 65; level++) write("deep/d" + level + ".azs", "#include \"d" + (level + 1) + ".azs\"\n");
        write("deep/d66.azs", "\n");
        CompileException error = failure(temporary.resolve("deep/d0.azs"));
        assertTrue(error.getMessage().startsWith("d64.azs:1:1: error: Include nesting exceeds 64 levels\n"), error.getMessage());
        assertEquals(1, error.errors().size());
    }

    @Test void preprocessedTextIsBounded() throws Exception {
        // C expands to 1,000,999 characters, below the per-line limit; 17 such lines exceed 16 Mi.
        String source = "#define B " + "x".repeat(1000) + "\n#define C " + "B ".repeat(999) + "B\n"
                + "C\n".repeat(20) + "int main() { return 0; }\n";
        CompileException error = assertTimeout(Duration.ofSeconds(30), () -> failure(source));
        assertEquals("<source>:19:1: error: Preprocessed source exceeds 16777216 characters after includes and macro expansion",
                error.getMessage());
    }

    @Test void tokenCountIsBounded() {
        // main has 9 tokens; the semicolons follow, 1000 per line.
        String source = "int main(){return 0;}\n" + (";".repeat(1000) + "\n").repeat(TreeLimits.MAX_TOKENS / 1000 + 1);
        CompileException error = assertTimeout(Duration.ofSeconds(60), () -> failure(source));
        int semicolon = TreeLimits.MAX_TOKENS - 9;
        assertEquals("<source>:" + (2 + semicolon / 1000) + ":" + (1 + semicolon % 1000) + ": error: Program exceeds 4194304 tokens",
                error.getMessage());
    }

    @Test void sourcesMustBeRegularFiles() throws Exception {
        // A missing main file is a located error like the others.
        assertEquals("missing.azs: error: Cannot open source file \"missing.azs\": file not found",
                failure(temporary.resolve("missing.azs")).getMessage());
        Path directory = Files.createDirectories(temporary.resolve("folder.azs"));
        assertEquals("folder.azs: error: Source file \"folder.azs\" is not a regular file", failure(directory).getMessage());
        Path includesDirectory = write("includes-dir.azs", "#include \"folder.azs\"\nint main() { return 0; }\n");
        assertEquals("includes-dir.azs:1:1: error: Source file \"folder.azs\" is not a regular file", failure(includesDirectory).getMessage());
        // A FIFO would block when opened; it is rejected without being opened. The preemptive
        // timeout fails a regression that opens it instead of hanging the test run.
        Path fifo = temporary.resolve("pipe.azs");
        boolean created;
        try {
            Process process = new ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).start();
            created = process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (java.io.IOException unavailable) { created = false; }
        Assumptions.assumeTrue(created, "mkfifo is not available");
        assertEquals("pipe.azs: error: Source file \"pipe.azs\" is not a regular file",
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> failure(fifo)).getMessage());
        Path includesFifo = write("includes-pipe.azs", "#include \"pipe.azs\"\nint main() { return 0; }\n");
        assertEquals("includes-pipe.azs:1:1: error: Source file \"pipe.azs\" is not a regular file",
                assertTimeoutPreemptively(Duration.ofSeconds(10), () -> failure(includesFifo)).getMessage());
    }

    @Test void sourceFilesAreBoundedInSize() throws Exception {
        // A file of exactly the limit compiles, even though its last line adds one more newline.
        String head = "int main() { return 0; }\n", line = " ".repeat(4095) + "\n";
        int remaining = TreeLimits.MAX_SOURCE_BYTES - head.length();
        StringBuilder text = new StringBuilder(TreeLimits.MAX_SOURCE_BYTES).append(head).append(line.repeat(remaining / line.length()));
        text.append(" ".repeat(remaining % line.length() - 1)).append('\n');
        assertEquals(TreeLimits.MAX_SOURCE_BYTES, text.length());
        Path largest = write("largest.azs", text.toString());
        assertEquals("main", names(parse(largest)));
        Path tooLarge = write("large.azs", text.append(' ').toString());
        assertEquals("large.azs: error: Source file \"large.azs\" exceeds 16777216 bytes", failure(tooLarge).getMessage());
        Path includesLarge = write("includes-large.azs", "int main() { return 0; }\n#include \"large.azs\"\n");
        assertEquals("includes-large.azs:2:1: error: Source file \"large.azs\" exceeds 16777216 bytes", failure(includesLarge).getMessage());
    }

    @Test void sourceBytesReadByOneCompilationAreBounded() throws Exception {
        // Four distinct files at the size limit, all text inactive, exceed the total with the main file.
        byte[] inactive = new byte[TreeLimits.MAX_SOURCE_BYTES];
        Arrays.fill(inactive, (byte) 'x');
        byte[] start = "#ifdef NEVER\n".getBytes(StandardCharsets.US_ASCII), end = "\n#fi\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(start, 0, inactive, 0, start.length);
        System.arraycopy(end, 0, inactive, inactive.length - end.length, end.length);
        for (int i = start.length + 1000; i < inactive.length - end.length; i += 1000) inactive[i] = '\n';
        StringBuilder main = new StringBuilder();
        for (int i = 1; i <= 4; i++) { Files.write(temporary.resolve("big" + i + ".azs"), inactive); main.append("#include \"big").append(i).append(".azs\"\n"); }
        // Repeated includes reuse the text read first and do not count again: read twice, three files would exceed the total.
        Path fits = write("fits.azs", main.toString().replace("#include \"big4.azs\"\n", "").repeat(2) + "int main() { return 0; }\n");
        assertEquals("main", names(assertTimeout(Duration.ofSeconds(60), () -> parse(fits))));
        Path total = write("total.azs", main + "int main() { return 0; }\n");
        CompileException error = assertTimeout(Duration.ofSeconds(60), () -> failure(total));
        assertEquals("total.azs:4:1: error: Source files read by one compilation exceed 67108864 bytes", error.getMessage());
    }

    @Test void aFileThatIsNotValidUtf8IsReadOnce() throws Exception {
        // Read again at every include, this file would exceed the 64 MiB total at the fourth.
        byte[] bad = new byte[TreeLimits.MAX_SOURCE_BYTES];
        Arrays.fill(bad, (byte) 'x');
        bad[bad.length - 1] = (byte) 0xff;
        Files.write(temporary.resolve("bad.azs"), bad);
        Path main = write("bad-main.azs", "#include \"bad.azs\"\n".repeat(5) + "int main() { return 0; }\n");
        CompileException error = assertTimeout(Duration.ofSeconds(60), () -> failure(main));
        StringBuilder expected = new StringBuilder();
        for (int line = 1; line <= 5; line++)
            expected.append(line == 1 ? "" : "\n").append("bad-main.azs:").append(line)
                    .append(":1: error: Source file \"bad.azs\" is not valid UTF-8 (line 1, column 16777216)");
        assertEquals(expected.toString(), error.getMessage());
    }

    @Test void invalidUtf8IsLocated() throws Exception {
        Path main = temporary.resolve("latin1.azs");
        Files.write(main, concat("int main() {\r\n  return 0; // caf".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xe9}, "\n}\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("latin1.azs: error: Source file \"latin1.azs\" is not valid UTF-8 (line 2, column 19)", failure(main).getMessage());
        // A byte order mark is not a column; other characters before the error are counted as UTF-16 units.
        Path marked = temporary.resolve("marked.azs");
        Files.write(marked, concat(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, "\u00e9\uD83D\uDE00".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xc3}));
        Path includes = write("includes-marked.azs", "\n#include \"marked.azs\"\n");
        assertEquals("includes-marked.azs:2:1: error: Source file \"marked.azs\" is not valid UTF-8 (line 1, column 4)",
                failure(includes).getMessage());
        // Lone surrogates encoded as UTF-8 are not valid UTF-8 either.
        Path surrogate = temporary.resolve("surrogate.azs");
        Files.write(surrogate, concat("\u2028\u000b\n".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80}));
        assertEquals("surrogate.azs: error: Source file \"surrogate.azs\" is not valid UTF-8 (line 4, column 1)", failure(surrogate).getMessage());
    }
    private static byte[] concat(byte[]... parts) {
        int length = 0; for (byte[] part : parts) length += part.length;
        byte[] result = new byte[length]; int offset = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, result, offset, part.length); offset += part.length; }
        return result;
    }

    @Test void overlongLinesAreReportedAsSuch() {
        String wide = "x".repeat(2 * 1024 * 1024);
        assertEquals("<source>:2:1048577: error: Source line exceeds 1048576 characters",
                failure("int main() { return 0; }\n" + " ".repeat(wide.length()) + "\n").getMessage());
        // Comments and string literals count toward the length.
        assertEquals("<source>:1:1048577: error: Source line exceeds 1048576 characters",
                failure("int main() { return 0; } // " + wide + "\n").getMessage());
        // A long string literal is reported where it starts.
        assertEquals("<source>:1:12: error: Source line exceeds 1048576 characters",
                failure("string s = \"" + wide + "\";\nint main() { return 0; }\n").getMessage());
        // A line that macros make too long names the expansion; WIDE adds 1001 characters, and the 1048th overflows.
        String expanded = "#define WIDE " + "x".repeat(1000) + "\nint main() { return 0; }\n" + "WIDE ".repeat(2000) + "\n";
        assertEquals("<source>:3:" + (5 * 1047 + 1) + ": error: Macro expansion exceeds 1048576 characters on one line",
                failure(expanded).getMessage());
    }

    @Test void functionLikeMacrosAreDiagnosed() throws Exception {
        String hint = "; write a function, or put a space before '(' for an object-like macro";
        assertEquals("<source>:2:3: error: Function-like macros are not supported: TWICE" + hint,
                failure("int main() { return 0; }\n  #define TWICE(x) ((x) + (x))\n").getMessage());
        assertEquals("<source>:1:1: error: Function-like macros are not supported: NONE" + hint,
                failure("#define NONE() 1\nint main() { return 0; }\n").getMessage());
        // A space before the parenthesis makes it part of an object-like macro's value.
        var script = new GeneraterJson.AzScript();
        script.execute("#define VALUE (2)\nint main() { return VALUE; }\n");
        JsonObject value = Compiler.compile(script.toObj()).toJson().getAsJsonObject().getAsJsonArray("f")
                .get(0).getAsJsonObject().getAsJsonArray("script").get(0).getAsJsonObject();
        assertEquals(2, value.get("r").getAsInt(), value.toString());
        // Other malformed names keep their message.
        assertEquals("<source>:1:1: error: Invalid identifier: 1A(x)", failure("#define 1A(x) x\nint main() { return 0; }\n").getMessage());
    }

    @Test void includesSearchTheIncludingDirectoryThenEachIncludeDirectoryInOrder() throws Exception {
        Path app = directory("app"), first = directory("first"), second = directory("second");
        write("app/x.azs", "int x_app() { return 1; }\n");
        write("second/x.azs", "int x_second() { return 2; }\n");
        // Only the second -I directory has y.azs; its own include then looks beside it first.
        write("second/y.azs", "#include \"sibling.azs\"\n");
        write("second/sibling.azs", "int sibling_second() { return 3; }\n");
        write("first/sibling.azs", "int sibling_first() { return 4; }\n");
        write("first/z.azs", "int z_first() { return 5; }\n");
        write("second/z.azs", "int z_second() { return 6; }\n");
        Path main = write("app/main.azs", "#include \"x.azs\"\n#include \"y.azs\"\n#include \"z.azs\"\nint main() { return 0; }\n");
        GeneraterJson.AzScript script = parse(main, first, second);
        assertEquals("x_app,sibling_second,z_first,main", names(script));
        // Each file is named relative to the first include root that contains it, unless an
        // earlier root holds another file of that name: first/sibling.azs keeps "sibling.azs".
        assertEquals(List.of("main.azs", "x.azs", "y.azs", "../second/sibling.azs", "z.azs"), sources(script));
        assertEquals(List.of(app.resolve("x.azs"), second.resolve("y.azs"), second.resolve("sibling.azs"), first.resolve("z.azs")),
                script.includedFiles());
        // Relative include directories resolve against the working directory; duplicates are searched once.
        GeneraterJson.AzScript relative = parse(main, Path.of(first.toString()), first, second.resolve("../second"));
        assertEquals("x_app,sibling_second,z_first,main", names(relative));
        // A directory of the name does not end the search: the first regular file wins.
        Files.createDirectories(temporary.resolve("app/w.azs"));
        write("first/w.azs", "int w_first() { return 7; }\n");
        Path directoryFirst = write("app/dir-first.azs", "#include \"w.azs\"\nint main() { return 0; }\n");
        assertEquals("w_first,main", names(parse(directoryFirst, first)));
        // The execution only reads the include directories; they are kept for the next one.
        assertEquals(List.of(first, second), parse(main, first, second).includeDirectories);
    }

    @Test void filesWithTheSameRelativePathInTwoRootsKeepDistinctNames() throws Exception {
        directory("app"); Path lib = directory("lib");
        write("app/util.azs", "int util_app() { return 1; }\n");
        write("lib/other.azs", "#include \"util.azs\"\n");
        write("lib/util.azs", "int util_lib() { return \"s\"; }\n");
        Path main = write("app/main.azs", "#include \"util.azs\"\n#include \"other.azs\"\nint main() { return 0; }\n");
        assertEquals(List.of("main.azs", "util.azs", "other.azs", "../lib/util.azs"), sources(parse(main, lib)));
        assertEquals("../lib/util.azs:1:18: error: in function util_lib: return expected int, got string\n"
                + "other.azs:1:1: note: in file included from here\nmain.azs:2:1: note: in file included from here",
                failure(main, lib).getMessage());
        // The names do not depend on the include order: a file of the main directory keeps its name.
        Path reversed = write("app/reversed.azs", "#include \"other.azs\"\n#include \"util.azs\"\nint main() { return 0; }\n");
        assertEquals(List.of("reversed.azs", "other.azs", "../lib/util.azs", "util.azs"), sources(parse(reversed, lib)));
        // An earlier include directory keeps the name over a later one, also when included last.
        Path second = directory("second");
        write("second/shared.azs", "int shared_second() { return 2; }\n");
        write("second/via.azs", "#include \"shared.azs\"\n");
        write("lib/shared.azs", "int shared_lib() { return 1; }\n");
        Path both = write("app/both.azs", "#include \"via.azs\"\n#include \"shared.azs\"\nint main() { return 0; }\n");
        GeneraterJson.AzScript ordered = parse(both, lib, second);
        assertEquals("shared_second,shared_lib,main", names(ordered));
        assertEquals(List.of("both.azs", "via.azs", "../second/shared.azs", "shared.azs"), sources(ordered));
    }

    @Test void absoluteIncludesAreRejectedWithoutReadingTheFile() throws Exception {
        Path secret = write("outside/secret.azs", "SECRET_MARKER_42 x;\n");
        Path main = write("root/main.azs", "#include \"" + secret.toRealPath() + "\"\nint main() { return 0; }\n");
        String rejected = "main.azs:1:1: error: Absolute include path \"" + secret.toRealPath() + "\" is not allowed; add its directory with "
                + "-I/--include-dir and include the file by a relative path, or compile with --unrestricted-includes";
        CompileException error = failure(main);
        assertEquals(rejected, error.getMessage());
        assertFalse(error.getMessage().contains("SECRET_MARKER_42"));
        // Not even the existence of the file is checked.
        Path missing = write("root/missing.azs", "#include \"" + secret.toRealPath().resolveSibling("none.azs") + "\"\nint main() { return 0; }\n");
        assertTrue(failure(missing).getMessage().contains(": error: Absolute include path \""), failure(missing).getMessage());
        // An include directory does not allow absolute includes either.
        assertEquals(rejected, failure(main, secret.toRealPath().getParent()).getMessage());
        // --unrestricted-includes reads the file, whose name is then relative to the main directory.
        var script = new GeneraterJson.AzScript(); script.unrestrictedIncludes = true;
        CompileException opened = assertThrows(CompileException.class, () -> script.execute(main));
        assertTrue(opened.getMessage().startsWith("../outside/secret.azs:1:1: error: ") && opened.getMessage().contains("SECRET_MARKER_42"),
                opened.getMessage());
        // A string source is restricted the same way.
        assertEquals("<source>:1:1: error: Absolute include path \"" + secret.toRealPath() + "\" is not allowed; add its directory with "
                        + "-I/--include-dir and include the file by a relative path, or compile with --unrestricted-includes",
                assertThrows(CompileException.class, () -> new GeneraterJson.AzScript().toPureSource("#include \"" + secret.toRealPath() + "\"\n")).getMessage());
    }

    @Test void includesCannotEscapeTheIncludeRoots() throws Exception {
        Path root = directory("root"), outside = directory("outside");
        write("outside/x.azs", "int outside_x() { return 1; }\n");
        Path main = write("root/main.azs", "#include \"../outside/x.azs\"\nint main() { return outside_x(); }\n");
        assertEquals("main.azs:1:1: error: Include \"../outside/x.azs\" escapes the allowed include roots (" + root + ")" + ESCAPE_REMEDY,
                failure(main).getMessage());
        // The same path is allowed once its directory is an include root, and is named relative to it.
        GeneraterJson.AzScript allowed = parse(main, outside);
        assertEquals("outside_x,main", names(allowed));
        assertEquals(List.of("main.azs", "x.azs"), sources(allowed));
        // A path that leaves every root is rejected before the file system is consulted, existing or not.
        Path deep = write("root/deep.azs", "#include \"" + "../".repeat(40) + "etc/hostname\"\nint main() { return 0; }\n");
        assertEquals("deep.azs:1:1: error: Include \"" + "../".repeat(40) + "etc/hostname\" escapes the allowed include roots (" + root + ", " + outside + ")"
                + ESCAPE_REMEDY, failure(deep, outside).getMessage());
        // Candidates outside the roots are counted, not listed, when another one was searched.
        Path project = directory("project");
        write("lib/x.azs", "int lib_x() { return 2; }\n");
        Path nested = write("project/app/main.azs", "#include \"../lib/x.azs\"\nint main() { return 0; }\n");
        assertEquals("main.azs:1:1: error: Include not found: \"../lib/x.azs\" (searched " + project.resolve("lib/x.azs")
                + "; 1 candidate outside the allowed include roots was not searched)", failure(nested, project).getMessage());
        // ".." is resolved lexically and may stay inside the root.
        write("root/sub/inner.azs", "int inner() { return 3; }\n");
        Path lexical = write("root/lexical.azs", "#include \"sub/../sub/./inner.azs\"\nint main() { return inner(); }\n");
        assertEquals(List.of("lexical.azs", "sub/inner.azs"), sources(parse(lexical)));
    }

    @Test void symbolicLinksCannotLeaveTheIncludeRoots() throws Exception {
        Path root = directory("root"), outside = directory("outside");
        Path target = write("outside/real.azs", "int linked() { return 1; }\n");
        try {
            Files.createSymbolicLink(root.resolve("link.azs"), target);
            Files.createSymbolicLink(root.resolve("linked-dir"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            Assumptions.abort("Symbolic links are not available: " + unsupported); return;
        }
        String escape = " resolves through a symbolic link to a file outside the allowed include roots (" + root
                + "); add its directory with -I/--include-dir or compile with --unrestricted-includes";
        Path file = write("root/file.azs", "#include \"link.azs\"\nint main() { return linked(); }\n");
        assertEquals("file.azs:1:1: error: Include \"link.azs\"" + escape, failure(file).getMessage());
        Path directory = write("root/directory.azs", "#include \"linked-dir/real.azs\"\nint main() { return linked(); }\n");
        assertEquals("directory.azs:1:1: error: Include \"linked-dir/real.azs\"" + escape, failure(directory).getMessage());
        // An include root containing the target, or --unrestricted-includes, allows the link.
        assertEquals(List.of("file.azs", "real.azs"), sources(parse(file, outside)));
        var unrestricted = new GeneraterJson.AzScript(); unrestricted.unrestrictedIncludes = true; unrestricted.execute(directory);
        assertEquals("linked,main", names(unrestricted));
        // A link that stays inside the roots is named after its target.
        Path inner = write("root/sub/inner.azs", "int inner() { return 2; }\n");
        Files.createSymbolicLink(root.resolve("inner-link.azs"), inner);
        Path inside = write("root/inside.azs", "#include \"inner-link.azs\"\nint main() { return inner(); }\n");
        assertEquals(List.of("inside.azs", "sub/inner.azs"), sources(parse(inside)));
        // A link to a directory outside the roots is an escape, not an irregular file.
        Files.createDirectories(outside.resolve("adir"));
        Path linkedDirectory = write("root/linked-directory.azs", "#include \"linked-dir/adir\"\nint main() { return 0; }\n");
        assertEquals("linked-directory.azs:1:1: error: Include \"linked-dir/adir\"" + escape, failure(linkedDirectory).getMessage());
        // A link loop cannot be opened, while a dangling link is missing like an absent file.
        Files.createSymbolicLink(root.resolve("loop.azs"), root.resolve("loop.azs"));
        Path loop = write("root/loop-main.azs", "#include \"loop.azs\"\nint main() { return 0; }\n");
        String looped = failure(loop).getMessage();
        assertTrue(looped.startsWith("loop-main.azs:1:1: error: Cannot open include file \"loop.azs\": ") && !looped.contains("not found"), looped);
        Files.createSymbolicLink(root.resolve("dangling.azs"), root.resolve("absent.azs"));
        Path dangling = write("root/dangling-main.azs", "#include \"dangling.azs\"\nint main() { return 0; }\n");
        assertEquals("dangling-main.azs:1:1: error: Include not found: \"dangling.azs\" (searched " + root.resolve("dangling.azs") + ")",
                failure(dangling).getMessage());
        // An include directory given through a link also contains the paths spelled through that link.
        Path lib = directory("lib");
        write("lib/h.azs", "int h() { return 3; }\n");
        Files.createSymbolicLink(temporary.resolve("link-lib"), lib);
        Path spelled = write("root/spelled.azs", "#include \"../link-lib/h.azs\"\nint main() { return h(); }\n");
        GeneraterJson.AzScript throughLink = parse(spelled, temporary.toRealPath().resolve("link-lib"));
        assertEquals("h,main", names(throughLink));
        assertEquals(List.of("spelled.azs", "h.azs"), sources(throughLink));
        assertEquals(List.of(lib.resolve("h.azs")), throughLink.includedFiles());
    }

    @Test void missingIncludesListEverySearchedPath() throws Exception {
        Path root = directory("root"), lib = directory("lib");
        Path main = write("root/main.azs", "int main() { return 0; }\n#include \"nope.azs\"\n");
        assertEquals("main.azs:2:1: error: Include not found: \"nope.azs\" (searched " + root.resolve("nope.azs") + ", " + lib.resolve("nope.azs") + ")",
                failure(main, lib).getMessage());
        // A nested include searches its own directory first, and its error carries the include chain.
        write("lib/header.azs", "\n#include \"deeper.azs\"\n");
        Path nested = write("root/nested.azs", "#include \"header.azs\"\nint main() { return 0; }\n");
        assertEquals("header.azs:2:1: error: Include not found: \"deeper.azs\" (searched " + lib.resolve("deeper.azs") + ")\n"
                + "nested.azs:1:1: note: in file included from here", failure(nested, lib).getMessage());
        // Include directories must exist; the error is a usage error, before any diagnostic.
        var script = new GeneraterJson.AzScript(); script.includeDirectories.add(temporary.resolve("absent"));
        IllegalArgumentException absent = assertThrows(IllegalArgumentException.class, () -> script.execute(main));
        assertEquals(IllegalArgumentException.class, absent.getClass());
        assertEquals("Include directory not found: " + temporary.resolve("absent"), absent.getMessage());
        script.includeDirectories.set(0, main);
        assertEquals("Include directory is not a directory: " + main,
                assertThrows(IllegalArgumentException.class, () -> script.execute(main)).getMessage());
        // An empty path would stand for the working directory.
        script.includeDirectories.set(0, Path.of(""));
        assertEquals("Empty include directory; use \".\" for the working directory",
                assertThrows(IllegalArgumentException.class, () -> script.execute(main)).getMessage());
    }

    @Test void includeSyntaxIsStrict() throws Exception {
        write("lib/x.azs", "int x() { return 1; }\n");
        String program = "\nint main() { return 0; }\n";
        assertEquals("<source>:1:1: error: Unterminated include path; expected #include \"path\"", failure("#include \"lib/x.azs" + program).getMessage());
        assertEquals("<source>:1:1: error: Unexpected text after include path", failure("#include \"lib/x.azs\" extra" + program).getMessage());
        assertEquals("<source>:1:1: error: Angle-bracket includes are not supported; use #include \"path\" with -I/--include-dir",
                failure("#include <lib/x.azs>" + program).getMessage());
        assertEquals("<source>:1:1: error: Missing include path", failure("#include \"\"" + program).getMessage());
        assertEquals("<source>:1:1: error: Missing include path", failure("#include" + program).getMessage());
        // A comment may follow a quoted path; the unquoted form names the rest of the line.
        var script = new GeneraterJson.AzScript();
        script.execute("#include \"lib/x.azs\" // header\nint main() { return x(); }\n", temporary);
        assertEquals("x,main", names(script));
        script.execute("#include lib/x.azs\nint main() { return x(); }\n", temporary);
        assertEquals("x,main", names(script));
    }

    @Test void stringSourcesIncludeRelativeToTheirBaseDirectory() throws Exception {
        Path base = directory("base"), lib = directory("lib");
        write("base/h.azs", "int h() { return 1; }\n");
        write("lib/l.azs", "int l() { return 2; }\n");
        var script = new GeneraterJson.AzScript(); script.includeDirectories.add(lib);
        script.execute("#include \"h.azs\"\n#include \"l.azs\"\nint main() { return h() + l(); }\n", base);
        assertEquals("h,l,main", names(script));
        assertEquals(List.of("<source>", "h.azs", "l.azs"), sources(script));
        assertEquals(List.of(base.resolve("h.azs"), lib.resolve("l.azs")), script.includedFiles());
        // The base directory is the root of a string source.
        assertEquals("<source>:1:1: error: Include \"../lib/l.azs\" escapes the allowed include roots (" + base + ")" + ESCAPE_REMEDY,
                assertThrows(CompileException.class, () -> new GeneraterJson.AzScript().execute("#include \"../lib/l.azs\"\n", base)).getMessage());
        assertEquals("Include base directory not found: " + temporary.resolve("none"), assertThrows(IllegalArgumentException.class,
                () -> new GeneraterJson.AzScript().execute("int main() { return 0; }\n", temporary.resolve("none"))).getMessage());
        // Without one, the working directory is the base and root.
        Path working = Path.of(".").toRealPath();
        assertEquals("<source>:1:1: error: Include \"../outside.azs\" escapes the allowed include roots (" + working + ")" + ESCAPE_REMEDY,
                assertThrows(CompileException.class, () -> new GeneraterJson.AzScript().execute("#include \"../outside.azs\"\n")).getMessage());
        // A later execution forgets the included files of the previous one.
        script.execute("int main() { return 0; }\n", base);
        assertEquals(List.of(), script.includedFiles());
        // An included file cannot take the name of the string source.
        Path named;
        try { named = write("base/<source>", "int named() { return 3; }\n"); }
        catch (InvalidPathException | java.io.IOException unsupported) { return; }
        script.execute("#include \"<source>\"\nint main() { return named(); }\n", base);
        assertEquals(List.of("<source>", named.toRealPath().toString().replace(java.io.File.separatorChar, '/')), sources(script));
    }

    @Test void eachRunStartsFromAFreshPreprocessorState() throws Exception {
        var script = new GeneraterJson.AzScript();
        String first = script.toPureSource("#define A 1\nint a = A;\n");
        assertEquals(first, script.toPureSource("#define A 1\nint a = A;\n"));
        assertEquals("\nint a = 1;\n\n", first);
        // Macros of an earlier run are gone.
        assertEquals("\n\n\nA\n\n", script.toPureSource("#ifdef A\nyes\n#fi\nA\n"));
        // So are the files marked by #pragma once.
        write("lib.azs", "#pragma once\nint lib() { return 1; }\n");
        Path main = write("uses.azs", "#include \"lib.azs\"\nint main() { return lib(); }\n");
        script.execute(main);
        assertEquals("lib,main", names(script));
        script.execute(main);
        assertEquals("lib,main", names(script));
    }
}
