package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.ExecOpcodes.*;

class ExecScalarReplacementTest {
    private static final String SIMPLE="class A{int x;boolean b;A(int n){x=n;b=true;}int get(){return x;}~A(){}}";
    private static AcsObject compile(String source) throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute(source);
        return Compiler.compile(parser.toObj(),new Diagnostics(),false,Set.of());
    }
    private static AcsObject main(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("id")==0x0fff0000).findFirst().orElseThrow();
    }
    private static void optimize(AcsObject program) {
        ExecScalarReplacement.optimize(program.getAsAcsArray("f"),Set.of(SCALAR_REPLACEMENT),new IdentityHashMap<>(),256);
        assertArrayEquals(ExecCodec.encode(program).toAbdFormat(),ExecCodec.encode(ExecCodec.decode(ExecCodec.encode(program))).toAbdFormat());
    }
    private static long count(AcsElement value,int opcode) {
        long result=ExecScalarAnalysis.opcode(value)==opcode?1:0;
        for(var child:ExecShape.children(value))result+=count(child.value(),opcode);
        return result;
    }
    @Test void automaticPointerRetainsAllocationAndUsesScalarFields() throws Exception {
        var program=compile(SIMPLE+"int main(){A *a(3);a.x=4;return a.get();}");optimize(program);
        var body=main(program).mmp.get("script");
        assertEquals(1,count(body,CALL));assertEquals(0,count(body,OBJECT_ADDRESS));assertEquals(0,count(body,BLOCK_ADDRESS));
        assertTrue(count(body,DEFINE)>=3);
    }
    @Test void literalAndCopiesKeepTheObservableBlockIdSequence() throws Exception {
        for(String declaration:List.of("A a(3);", "A a(3);A b=a;")) {
            var program=compile(SIMPLE+"class B{int x;B *addr(){return this;}}int f(){"+declaration+
                    "return a.x;}int main(){int n=f();B b;print(b.addr());return n;}");
            optimize(program);
            var target=program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                    .filter(f->f.getAsInt("entry-kind")==0&&f.getAsInt("param-count")==0&&f.getAsInt("id")!=0x0fff0000)
                    .findFirst().orElseThrow();
            assertEquals(1,count(target.mmp.get("script"),CALL));
            assertEquals(0,count(target.mmp.get("script"),OBJECT_ADDRESS));
            long objectDefinitions=((AcsArray)target.mmp.get("script")).acsa.stream()
                    .filter(s->ExecScalarAnalysis.opcode(s)==DEFINE)
                    .filter(s->((AcsObject)s).getAsInt("declared-type")==Compiler.AsTypes.OBJECT_VALUE).count();
            assertEquals(declaration.contains("A b")?2:1,objectDefinitions,
                    "each literal/copy retains its original allocation and cleanup");
        }
    }
    @Test void nestedScopeAndLoopDeclarationsStayAtTheirOriginalSite() throws Exception {
        var program=compile(SIMPLE+"int main(){int total=0;for(int i=0;i<3;i++){A *a(i);total=total+a.x;}return total;}");optimize(program);
        var body=main(program).mmp.get("script");assertEquals(1,count(body,CALL));assertEquals(1,count(body,WHILE));
    }
    @Test void heapObservingCallerKeepsItsAllocationOperations() throws Exception {
        var program=compile(SIMPLE+"int f(){A *a(3);return a.x;}int main(){address left=alloc(2);address hole=alloc(2);"
                +"address right=alloc(2);mem_free(hole);int n=f();address next=alloc(2);print(next);"
                +"mem_free(next);mem_free(left);mem_free(right);return n;}");
        String caller=main(program).mmp.get("script").toJson().toString();optimize(program);
        assertEquals(caller,main(program).mmp.get("script").toJson().toString());
        var target=program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("entry-kind")==0&&f.getAsInt("param-count")==0&&f.getAsInt("id")!=0x0fff0000)
                .findFirst().orElseThrow();
        assertEquals(1,count(target.mmp.get("script"),CALL));
        assertEquals(0,count(target.mmp.get("script"),OBJECT_ADDRESS));
    }
    @Test void effectsEscapesAndUnprovenShapesLeaveTheWholeFunctionUntouched() throws Exception {
        for(String source:List.of(
                SIMPLE+"int main(){A *a=new A(3);int n=a.x;delete a;return n;}",
                SIMPLE+"int main(){A *a(3);A *b=a;return b.x;}",
                SIMPLE+"int main(){def(x);A *a(3);return a.x;}",
                SIMPLE+"int main(){def(x,true);A *a(3);return a.x;}",
                SIMPLE+"int main(){def(x,3);A *a(3);return a.x;}",
                SIMPLE+"int main(){A a(3);print(1);return a.x;}",
                "class A{int x;A(){x=1;}~A(){print(x);}}int main(){A a;return a.x;}",
                "class A{int x;A(int n){x=1/n;}~A(){}}int main(){A a(0);return a.x;}",
                "class A{int x;A(){x=3;}int get(){return 1/x;}~A(){}}int main(){A a;return a.get();}",
                "class A{int x;virtual int get(){return x;}~A(){}}int main(){A a;return a.get();}")) {
            var program=compile(source);String before=main(program).mmp.get("script").toJson().toString();optimize(program);
            assertEquals(before,main(program).mmp.get("script").toJson().toString(),source);
        }
    }
    @Test void disabledPassAndLimitsDoNotPublishPartialChanges() throws Exception {
        var program=compile(SIMPLE+"int main(){A *a(3);return a.x;}");
        String before=main(program).mmp.get("script").toJson().toString();
        ExecScalarReplacement.optimize(program.getAsAcsArray("f"),Set.of(),new IdentityHashMap<>(),256);
        assertEquals(before,main(program).mmp.get("script").toJson().toString());
        ExecScalarReplacement.optimize(program.getAsAcsArray("f"),Set.of(SCALAR_REPLACEMENT),new IdentityHashMap<>(),1);
        assertEquals(before,main(program).mmp.get("script").toJson().toString());
    }
}
