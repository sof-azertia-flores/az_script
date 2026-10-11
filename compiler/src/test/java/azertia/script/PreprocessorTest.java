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
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Source reading, {@code #pragma once} and the preprocessing limits. */
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
