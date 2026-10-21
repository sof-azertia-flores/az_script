package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecO3InliningTest {
    private static AcsObject compile(String source) throws Exception {
        return ExecO2OptimizerTest.compile(source, Set.of(SEQUENCE_INLINING));
    }
    private static AcsObject function(AcsObject program, int id) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f -> f.getAsInt("id") == id).findFirst().orElseThrow();
    }
    private static AcsObject main(AcsObject program) { return function(program, 0x0fff0000); }
    private static long count(AcsObject function, int opcode) {
        return ExecO2OptimizerTest.count(function.mmp.get("script"), opcode);
    }

    @Test void inlinesCallsInOperandsConditionsAndLazyBranches() throws Exception {
        var program = compile("int f(int x){return x*3+1;}boolean even(int x){return x%2==0;}"
                + "int main(){int t=0;int i=0;while(even(i)||i<5){t=t+f(i)*2;if(i>1&&even(t)){t=t+f(t);}i++;}return t;}");
        var function = main(program);
        assertEquals(0, count(function, CALL));
        assertEquals(4, count(function, SEQ));
        assertEquals(3, program.getAsAcsArray("f").acsa.size(), "callees remain public");
        // The program still encodes: sequences obey the exec v11 reader rules.
        assertDoesNotThrow(program::toValue);
    }
    @Test void earlyReturnsBecomeAResultSlotWrittenAtEachTail() throws Exception {
        var function = main(compile("int sign(int x){if(x<0){return -1;}if(x==0){return 0;}return 1;}int main(){int y=3;return sign(y);}"));
        assertEquals(0, count(function, CALL));
        assertEquals(0, count(function, RETURN) - 1, "only main's own return remains");
        assertEquals(3, count(function, SET), "one result store per tail; y stands for x");
    }
    @Test void writtenParametersGetSnapshotsAndOthersAreSubstituted() throws Exception {
        var written = main(compile("int f(int x){x=x*2;return x+1;}int main(){int y=4;return f(y);}"));
        assertEquals(1, count(written, SEQ));
        assertEquals(2, written.getAsInt("local-count"), "y and the snapshot of x");
        var substituted = main(compile("int f(int x){return x+1;}int main(){int y=4;return f(y);}"));
        assertEquals(1, substituted.getAsInt("local-count"), "y stands for x");
    }
    @Test void siblingSequencesShareSlotsAndNestedOnesDoNot() throws Exception {
        var siblings = main(compile("int f(int a,int b){int c=a*b;return c-a;}int main(){int x=f(3,4)+f(5,6)+f(7,8);return x;}"));
        assertEquals(3, count(siblings, SEQ));
        assertEquals(2, siblings.getAsInt("local-count"), "x and one shared c");
        var nested = main(compile("int f(int a,int b){int c=a*b;return c-a;}int main(){int x=f(f(1,2),3);return x;}"));
        assertEquals(2, count(nested, SEQ));
        assertEquals(4, nested.getAsInt("local-count"), "x, the outer snapshot of a, and two distinct c");
    }
    @Test void expansionStopsAtDepthFour() throws Exception {
        var program = compile("int f6(int x){return x+1;}int f5(int x){return f6(x)+1;}int f4(int x){return f5(x)+1;}"
                + "int f3(int x){return f4(x)+1;}int f2(int x){return f3(x)+1;}int f1(int x){return f2(x)+1;}int main(){return f1(0);}");
        var function = main(program);
        assertEquals(4, count(function, SEQ));
        assertEquals(1, count(function, CALL));
    }
    @Test void unprovenRecursiveOrObservingCalleesKeepTheirCalls() throws Exception {
        for (String source : List.of(
                "int f(int x){return f(x);}int main(){return f(3);}",
                "int f(int x){return str_length(\"ab\");}int main(){return f(3);}",
                "int f(int x){return x;}int main(){return f(str_length(\"a\"));}",
                "int f(int x){int s=0;while(x>0){return 1;}return s;}int main(){return f(3);}")) {
            var function = main(compile(source));
            assertEquals(0, count(function, SEQ), source);
            assertTrue(count(function, CALL) >= 1, source);
        }
    }
    @Test void callersWithObjectsAndCleanupStillInlineScalarCalls() throws Exception {
        // f cannot fail, so no trace published by its entry is lost while run unwinds.
        String total = "class P{int v;P(int n){v=n;}~P(){}}int f(int x){return (x&15)+1;}"
                + "int run(int k){P p(3);P* q=new P(k);int r=f(k)*p.v+q.v;delete q;return r;}int main(){return run(4);}";
        var failing = compile(total.replace("(x&15)+1", "x+1"));
        var program = compile(total);
        var run = program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f -> f.getAsAcsObject("debug").getAsString("name").equals("run")).findFirst().orElseThrow();
        assertEquals(1, count(run, SEQ));
        assertTrue(count(run, CALL) > 0, "object operations stay");
        var failingRun = failing.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f -> f.getAsAcsObject("debug").getAsString("name").equals("run")).findFirst().orElseThrow();
        assertEquals(0, count(failingRun, SEQ), "a callee that may fail keeps its entry in a caller holding objects");
    }
    @Test void debugRecordsNameInlinedFramesAtTheirCallSites() throws Exception {
        var function = main(compile("int g(int x){\n return 100/x;\n}\nint f(int x){\n return g(x-1)+1;\n}\nint main(){\n return f(1)*2;\n}"));
        var debug = function.getAsAcsObject("debug");
        var inlined = debug.getAsAcsArray("inlined").acsa;
        assertEquals(2, inlined.size());
        assertEquals("f", ((AcsObject) inlined.get(0)).getAsString("name"));
        assertEquals("g", ((AcsObject) inlined.get(1)).getAsString("name"));
        assertEquals(0x0fff0000, debug.getAsInt("display-id"));
        assertTrue(debug.getAsAcsArray("locations").acsa.stream().allMatch(item -> item.toJson().getAsString().split(":").length == 5));
    }
    @Test void siblingScopesShareSlotsOfTheirType() throws Exception {
        String source = "int run(int n){int s=0;for(int i=0;i<n;i++){int a=i*2;s+=a;}for(int j=0;j<n;j++){int b=j*3;s+=b;}"
                + "{boolean f=n>2;if(f){s+=1;}}return s;}int main(){return run(4);}";
        var plain = function(ExecO2OptimizerTest.compile(source, Set.of()), 0x0fff0001);
        var compacted = function(ExecO2OptimizerTest.compile(source, Set.of(SLOT_COMPACTION)), 0x0fff0001);
        assertEquals(8, plain.getAsInt("local-count"));
        assertTrue(compacted.getAsInt("local-count") < plain.getAsInt("local-count"));
        var args = azertia.Main.parseCompileArguments(List.of("compile", "x.azs", "-O3", "--no-slot-compaction"));
        assertFalse(args.optimizationPasses().contains(SLOT_COMPACTION));
    }
    @Test void plainCompilationHasTheSameInstructions() throws Exception {
        String source = "int f(int x){return x*3+1;}int main(){int t=0;for(int i=0;i<5;i++){t=t+f(i);}return t;}";
        var parser = new GeneraterJson.AzScript(); parser.execute(source);
        var plain = Compiler.compile(parser.toObj(), new Diagnostics(), false, Set.of(SEQUENCE_INLINING));
        assertEquals(main(plain).mmp.get("script").toJson(), main(compile(source)).mmp.get("script").toJson());
    }
}
