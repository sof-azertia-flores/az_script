package azertia.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile work grows linearly with the size of a program. The deterministic
 * {@link CompilerMetrics} counters compare a program with one eight times its size:
 * linear work grows about 8 times, where a scan inside a per-item loop would grow
 * about 64 times. Generous time limits guard the largest programs as well.
 */
class CompilerScalingTest {
    /** One scope holding {@code n} locals, each read once and written once. */
    static String locals(int n) {
        StringBuilder source = new StringBuilder("int main() {\n  int acc = 0;\n");
        for (int i = 0; i < n; i++) source.append("  int v").append(i).append(" = acc + ").append(i % 7).append("; acc = v").append(i).append(" % 1000;\n");
        return source.append("  return acc;\n}\n").toString();
    }
    static String statements(int n) {
        StringBuilder source = new StringBuilder("int main() {\n  int acc = 0;\n");
        for (int i = 0; i < n; i++) source.append("  acc = (acc + ").append(i % 7).append(") % 1000;\n");
        return source.append("  return acc;\n}\n").toString();
    }
    /** A chain of functions, each with a local, an if, a while and a call of the previous one. */
    static String functions(int n) {
        StringBuilder source = new StringBuilder("int f0(int a, int b) { return a + b; }\n");
        for (int i = 1; i < n; i++)
            source.append("int f").append(i).append("(int a, int b) { int c = a + b * ").append(i % 7)
                    .append("; if (c > 10) { c = c - 1; } while (c > 100) { c = c / 2; } return c + f").append(i - 1).append("(a, b); }\n");
        return source.append("int main() { return f").append(n - 1).append("(1, 2) % 100; }\n").toString();
    }
    static String globals(int n) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < n; i++) source.append("#gvar g").append(i).append('\n');
        source.append("int main() {\n");
        for (int i = 0; i < n; i++) source.append("  g").append(i).append(" = ").append(i % 7).append(";\n");
        return source.append("  return 0;\n}\n").toString();
    }
    static String classes(int n) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < n; i++)
            source.append("class C").append(i).append(" { int x; C").append(i).append("(int v) { x = v; } int get() { return x; } }\n");
        source.append("int main() {\n  int total = 0;\n");
        for (int i = 0; i < n; i++)
            source.append("  C").append(i).append(" c").append(i).append("(").append(i % 7).append("); total = total + c").append(i).append(".get();\n");
        return source.append("  return total;\n}\n").toString();
    }
    /** One class with {@code n} fields, each written and read through a literal object. */
    static String fields(int n) {
        StringBuilder source = new StringBuilder("class Wide {\n");
        for (int i = 0; i < n; i++) source.append("  int f").append(i).append(";\n");
        source.append("  Wide() { }\n}\nint main() {\n  Wide w;\n  int total = 0;\n");
        for (int i = 0; i < n; i++) source.append("  w.f").append(i).append(" = ").append(i % 7).append("; total = total + w.f").append(i).append(";\n");
        return source.append("  return total;\n}\n").toString();
    }
    /** Externs, each called by a function of its own: every automatic function ID avoids every extern ID. */
    static String externs(int n) {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < n; i++) source.append(String.format("extern int host%d(int):1234%04x;\n", i, i));
        for (int i = 0; i < n; i++) source.append("int use").append(i).append("(int v) { return host").append(i).append("(v); }\n");
        source.append("int main() {\n  int total = 0;\n");
        for (int i = 0; i < n; i++) source.append("  total = total + use").append(i).append("(").append(i % 7).append(");\n");
        return source.append("  return total;\n}\n").toString();
    }
    /** One function with {@code n} parameters. */
    static String parameters(int n) {
        StringBuilder source = new StringBuilder("int f(");
        for (int i = 0; i < n; i++) source.append(i == 0 ? "" : ", ").append("int p").append(i);
        return source.append(") { return p0; }\nint main() { return 0; }\n").toString();
    }

    private static JsonObject tree(String source, Diagnostics diagnostics) throws Exception {
        var script = new GeneraterJson.AzScript(); script.execute(source, diagnostics); return script.toObj();
    }
    /** Parses, compiles and encodes a program, and returns the work it counted. */
    private static CompilerMetrics metrics(String source) throws Exception {
        Diagnostics diagnostics = new Diagnostics();
        Compiler.compile(tree(source, diagnostics), diagnostics).toValue();
        return diagnostics.metrics();
    }
    private static void assertGrowth(String what, long small, long large) {
        assertTrue(large <= 10 * small, what + " grew from " + small + " to " + large + " for 8 times the input");
    }
    private static void assertLinear(IntFunction<String> program, int size) throws Exception {
        CompilerMetrics small = metrics(program.apply(size)), large = metrics(program.apply(8 * size));
        assertTrue(large.compiledNodes >= 7 * small.compiledNodes, "the larger program compiles about 8 times the nodes: " + small + " / " + large);
        assertGrowth("total work", small.total(), large.total());
        assertGrowth("scope probes", small.scopeProbes, large.scopeProbes);
        assertGrowth("type evaluations", small.typeEvaluations, large.typeEvaluations);
        assertGrowth("ID probes", small.idProbes, large.idProbes);
        assertGrowth("table probes", small.tableProbes, large.tableProbes);
        // Memoized types and hashed scopes keep the work per node bounded, whatever the program size.
        assertTrue(large.typeEvaluations <= 8 * large.compiledNodes, "type evaluations per node: " + large);
        assertTrue(large.scopeProbes <= 12 * large.compiledNodes, "scope probes per node: " + large);
    }

    @Test void localsOfOneScope() throws Exception { assertLinear(CompilerScalingTest::locals, 2_000); }
    @Test void statementsOfOneFunction() throws Exception { assertLinear(CompilerScalingTest::statements, 2_500); }
    @Test void functions() throws Exception { assertLinear(CompilerScalingTest::functions, 1_000); }
    @Test void globalVariables() throws Exception { assertLinear(CompilerScalingTest::globals, 1_000); }
    @Test void classesAndTheirFactories() throws Exception { assertLinear(CompilerScalingTest::classes, 250); }
    @Test void fieldsOfOneClass() throws Exception { assertLinear(CompilerScalingTest::fields, 500); }
    @Test void externDeclarations() throws Exception { assertLinear(CompilerScalingTest::externs, 500); }
    @Test void parametersOfOneFunction() throws Exception {
        CompilerMetrics small = metrics(parameters(1_000)), large = metrics(parameters(8_000));
        assertGrowth("total work", small.total(), large.total());
        assertGrowth("table probes", small.tableProbes, large.tableProbes);
    }

    /** A hand-written AST may omit definition names: each takes the first abstract name mapped to its ID. */
    @Test void unnamedDefinitionsTakeTheirAbstractNames() throws Exception {
        IntFunction<JsonObject> unnamed = n -> {
            try {
                JsonObject tree = tree(functions(n), new Diagnostics());
                for (JsonElement namespace : tree.getAsJsonObject("body").asMap().values())
                    for (JsonElement function : namespace.getAsJsonObject().asMap().values())
                        function.getAsJsonObject().getAsJsonObject("metadata").remove("name");
                return tree;
            } catch (Exception e) { throw new AssertionError(e); }
        };
        JsonObject named = tree(functions(50), new Diagnostics());
        assertEquals(Compiler.compile(named).toJson(), Compiler.compile(unnamed.apply(50)).toJson());
        Diagnostics small = new Diagnostics(), large = new Diagnostics();
        Compiler.compile(unnamed.apply(1_000), small); Compiler.compile(unnamed.apply(8_000), large);
        assertGrowth("total work", small.metrics().total(), large.metrics().total());
        assertGrowth("table probes", small.metrics().tableProbes, large.metrics().tableProbes);
    }

    // Each limit is about five times the compile time measured on the development machine.
    @Test void thirtyTwoThousandLocalsCompileInSeconds() {
        String source = locals(32_000);
        CompilerMetrics work = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> metrics(source));
        assertTrue(work.scopeProbes <= 12 * work.compiledNodes, work.toString());
    }
    @Test void twentyThousandFunctionsCompileInSeconds() {
        // The AST and the lowered program of 20,000 functions need a few hundred MiB together.
        // The validation run passes -Xmx1g; some collectors report a little less as the maximum.
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= 768L << 20, "needs a 1 GiB heap");
        String source = functions(20_000);
        CompilerMetrics work = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> metrics(source));
        assertEquals(20_000, work.idProbes, "each automatic ID is found at its first candidate");
    }
    @Test void twentyThousandStatementsCompileInSeconds() {
        String source = statements(20_000);
        CompilerMetrics work = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> metrics(source));
        assertTrue(work.typeEvaluations <= 8 * work.compiledNodes, work.toString());
    }
}
