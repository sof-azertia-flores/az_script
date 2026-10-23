package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.ExecOpcodes.*;

class ExecGenericSpecializationTest {
    private List<AcsObject> privateFunctions(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast).filter(f->f.getAsInt("entry-kind")==2).toList();
    }
    @Test void fixesSignaturesLocalsAndHiddenContextsForIntegerAndBooleanBindings() throws Exception {
        String source="<T>T copy(T x){T y=x;return y;}int main(){int a=3;boolean b=true;return copy<boolean>(b)?copy<int>(a):0;}";
        var p=ExecO3Test.compile(source,true,Set.of(CONTEXT_SPECIALIZATION));
        var versions=privateFunctions(p);assertEquals(2,versions.size());
        for(var clone:versions) {
            assertEquals(0,clone.getAsInt("hidden-count"));assertEquals(0,ExecO3Test.count(clone.mmp.get("script"),RETURN_TYPED));
            int type=clone.getAsInt("return-type");assertTrue(type==0||type==4);
            assertEquals(type,((AcsIntegerElement)clone.getAsAcsArray("param-types").acsa.get(0)).s);
            assertEquals("copy",clone.getAsAcsObject("debug").getAsString("name"));
        }
        assertEquals(0,ExecO3Test.count(ExecO3Test.main(p).mmp.get("script"),CHECK_TYPE));
    }
    @Test void completeBindingsPreventUnusedContextKeyCollisions() throws Exception {
        var p=ExecO3Test.compile("<T,U>T copy(T x){return x;}int main(){return copy<int,boolean>(7)+copy<int,int>(7);}",false,Set.of(CONTEXT_SPECIALIZATION));
        var versions=privateFunctions(p);assertEquals(2,versions.size());
        assertNotEquals(versions.get(0).getAsInt("id"),versions.get(1).getAsInt("id"));
    }
    @Test void constantBranchVersionsRetainOriginalPublicEntry() throws Exception {
        var p=ExecO3Test.compile("<T>T choose(T a,T b,boolean flag){if(flag)return a;return b;}int main(){return choose<int>(3,4,true);}",false,Set.of(CONTEXT_SPECIALIZATION,CONSTANT_SPECIALIZATION));
        var versions=privateFunctions(p);assertEquals(1,versions.size());assertEquals(0,ExecO3Test.count(versions.get(0).mmp.get("script"),IF));
        assertTrue(p.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast).anyMatch(f->f.getAsInt("hidden-count")==1&&f.getAsInt("entry-kind")==0));
    }
    @Test void unknownArgumentTypesAndObjectBindingsKeepGenericChecks() throws Exception {
        for(String source:List.of(
                "extern int host():0x12340001;<T>T copy(T x){return x;}int main(){return copy<int>(host());}",
                "class A{int x;}<T>T copy(T x){return x;}int main(){A a;A b=copy<A>(a);return b.x;}")) {
            var p=ExecO3Test.compile(source,false,Set.of(CONTEXT_SPECIALIZATION));assertTrue(privateFunctions(p).isEmpty());
        }
    }
    @Test void sixteenArgumentsInlineAtNestedCallSites() throws Exception {
        String parameters=String.join(",",java.util.stream.IntStream.range(0,16).mapToObj(i->"int a"+i).toList());
        String values=String.join(",",Collections.nCopies(16,"1"));
        var p=ExecO3Test.compile("int sum("+parameters+"){return a0+a15;}int main(){return 2+sum("+values+");}",true,Set.of(SEQUENCE_INLINING));
        assertEquals(0,ExecO3Test.count(ExecO3Test.main(p).mmp.get("script"),CALL));assertEquals(1,ExecO3Test.count(ExecO3Test.main(p).mmp.get("script"),SEQUENCE));
    }
    @Test void concreteGenericCallsRetainLocationsForSequenceInlining() throws Exception {
        String source="<T>T identity(T value){return value;}"
                +"int bench(int n):0002{int total=0;int i=0;while(i<n){total+=identity<int>(i&127);i=i+1;}return total;}"
                +"int main(){return bench(512);}";
        for(boolean debug:List.of(false,true)) {
            var program=ExecO3Test.compile(source,debug,Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
            var bench=program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                    .filter(f->f.getAsInt("id")==0x0fff0002).findFirst().orElseThrow();
            assertEquals(0,ExecO3Test.count(bench.mmp.get("script"),CALL));
            assertEquals(0,ExecO3Test.count(bench.mmp.get("script"),SEQUENCE));
            assertEquals(2,bench.getAsInt("local-count"));
        }
    }
}
