package azertia.script;

import azertia.Main;
import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecO3Test {
    static AcsObject compile(String source,boolean debug,Set<Compiler.OptimizationPass> passes) throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute(source);
        var result=Compiler.compile(parser.toObj(),new Diagnostics(),debug,passes);
        assertArrayEquals(result.toValue().toAbdFormat(),ExecCodec.encode(ExecCodec.decode(result.toValue())).toAbdFormat());
        return result;
    }
    static AcsObject main(AcsObject program) {
        return program.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("id")==0x0fff0000).findFirst().orElseThrow();
    }
    static long count(AcsElement value,int opcode) {return ExecO2OptimizerTest.count(value,opcode);}
    @Test void o3FlagsAreIndependentAndO2HasAFrozenPassSet() {
        assertEquals(10,Compiler.optimizationPasses(Compiler.Optimization.ADVANCED).size());
        assertFalse(Compiler.optimizationPasses(Compiler.Optimization.ADVANCED).contains(SEQUENCE_INLINING));
        var args=Main.parseCompileArguments(List.of("compile","x.azs","-O2","-O3","--no-inline","--no-unchecked-arithmetic"));
        assertEquals(Compiler.Optimization.AGGRESSIVE,args.optimization());
        assertFalse(args.optimizationPasses().contains(SEQUENCE_INLINING));assertFalse(args.optimizationPasses().contains(UNCHECKED_ARITHMETIC));
        assertEquals(Compiler.Optimization.STANDARD,Main.parseCompileArguments(List.of("compile","x.azs","-O3","-O1")).optimization());
    }
    @Test void disablingEveryO3StageNeverRestoresOlderInlining() throws Exception {
        var args=Main.parseCompileArguments(List.of("compile","x.azs","-O3","--no-sequence-inline",
                "--no-slot-compaction","--no-specialization","--no-context-specialization",
                "--no-scalar-replacement","--no-field-cache","--no-devirtualization","--no-unchecked-arithmetic"));
        assertFalse(ExecO3.enabled(args.optimizationPasses()));
        assertFalse(args.optimizationPasses().contains(INLINING));
        assertFalse(args.optimizationPasses().contains(SCALAR_INLINING));
        String source="extern int probe(int n):0x12340001;int scalar(int x){return x&7;}"
                +"int helper(){int n=scalar(1);return probe(n);}int main(){return helper();}";
        var p=compile(source,true,args.optimizationPasses());
        var helper=p.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsAcsObject("debug").getAsString("name").equals("helper")).findFirst().orElseThrow();
        assertEquals(2,count(helper.mmp.get("script"),CALL),"successful scalar call must still clear the published trace before probe");
    }
    @Test void inlinesNestedLazyAndLoopCallsWithoutMovingTheirSequences() throws Exception {
        var p=compile("int f(int x){int y=x*x;return y+1;}boolean test(int x){return x<3;}int main(){int x=0;while(test(x)){x=x+1;}return (false&&test(x))?f(x):3+f(x);}",false,Set.of(SEQUENCE_INLINING));
        var body=main(p).mmp.get("script");assertEquals(0,count(body,CALL));assertEquals(4,count(body,SEQUENCE));
        var loop=((AcsArray)body).acsa.stream().filter(n->ExecScalarAnalysis.opcode(n)==WHILE).map(AcsObject.class::cast).findFirst().orElseThrow();
        assertEquals(SEQUENCE,loop.getAsAcsObject("v").getAsInt("c"));
    }
    @Test void scalarForwardersNeedNoSequenceOrSnapshotSlots() throws Exception {
        String source="int identity(int x){return x;}boolean flag(boolean b){return b;}"
                +"int main(){int x=7;boolean b=true;return flag(b)?identity(x):0;}";
        for(boolean debug:List.of(false,true)) {
            var program=compile(source,debug,Set.of(SEQUENCE_INLINING));
            var main=main(program);assertEquals(2,main.getAsInt("local-count"));
            assertEquals(0,count(main.mmp.get("script"),CALL));
            assertEquals(0,count(main.mmp.get("script"),SEQUENCE));
        }
    }
    @Test void readOnlyLeafArgumentsNeedNoSnapshotSlots() throws Exception {
        String source="int sum(int a,int b,int c){return a+b+c;}int main(){int x=3;return sum(x,x,7);}";
        var program=compile(source,true,Set.of(SEQUENCE_INLINING));
        var main=main(program);assertEquals(1,main.getAsInt("local-count"));
        assertEquals(0,count(main.mmp.get("script"),CALL));
        assertEquals(1,count(main.mmp.get("script"),SEQUENCE));
    }
    @Test void assignedParametersStillHaveIndependentSnapshots() throws Exception {
        String source="int sum(int a,int b){a=a+1;return a+b;}int main(){int x=3;return sum(x,x)*10+x;}";
        var main=main(compile(source,true,Set.of(SEQUENCE_INLINING)));
        assertEquals(2,main.getAsInt("local-count"));
        assertEquals(1,count(main.mmp.get("script"),SEQUENCE));
    }
    @Test void complexArgumentsOnlySnapshotVariablesWrittenLater() throws Exception {
        for(String arguments:List.of("x,x+1","x+1,x")) {
            var main=main(compile("int pair(int a,int b){return a*10+b;}int main(){int x=3;return pair("+arguments+");}",true,Set.of(SEQUENCE_INLINING)));
            assertEquals(2,main.getAsInt("local-count"),arguments);
            assertEquals(1,count(main.mmp.get("script"),SEQUENCE));
        }
        var closed=main(compile("int other(int n){if(n>0)return n;return 0;}int pair(int a,int b){return a*10+b;}int main(){int x=3;return pair(x,other(x));}",true,Set.of(SEQUENCE_INLINING)));
        assertEquals(2,closed.getAsInt("local-count"));
        assertEquals(1,count(closed.mmp.get("script"),CALL));
    }
    @Test void sequenceCleanupFoldsNewConstantsAndRemovesCopyTemporaries() throws Exception {
        String source="int fold(int x){int a=x+2;int b=a;return b*3;}int main(){return fold(4);}";
        for(boolean debug:List.of(false,true)) {
            var main=main(compile(source,debug,Set.of(SEQUENCE_INLINING)));
            assertEquals(0,main.getAsInt("local-count"));
            assertEquals(0,count(main.mmp.get("script"),ADD));
            assertEquals(0,count(main.mmp.get("script"),MULTIPLY));
            var returned=((AcsObject)main.getAsAcsArray("script").acsa.get(0)).getAsAcsObject("r");
            assertEquals(18,returned.getAsInt("value"));
        }
        var copy=main(compile("int copy(int x){int a=x;int b=a;return b;}int main(){int n=7;return copy(n);}",false,Set.of(SEQUENCE_INLINING)));
        assertEquals(1,copy.getAsInt("local-count"));
    }
    @Test void sequenceCleanupKeepsFailingUnusedWorkAndLazyEvaluation() throws Exception {
        for(String body:List.of("int a=1/x;return 7;","int a=x+1;return 7;")) {
            var main=main(compile("int fail(int x){"+body+"}int main(){return fail(0);}",true,Set.of(SEQUENCE_INLINING)));
            if(body.contains("1/x")) {
                assertEquals(1,count(main.mmp.get("script"),DIVIDE));
                assertEquals(1,main.getAsInt("local-count"));
            } else assertEquals(0,main.getAsInt("local-count"));
        }
        var lazy=main(compile("int choose(int x,boolean b){int a=x;return b?a:1/x;}int main(){int n=0;return choose(n,true);}",true,Set.of(SEQUENCE_INLINING)));
        assertEquals(1,count(lazy.mmp.get("script"),SELECT));
        assertEquals(1,count(lazy.mmp.get("script"),DIVIDE));
    }
    @Test void aLaterArgumentAssignmentPreventsBorrowingAnEarlierVariable() throws Exception {
        var program=compile("int pair(int a,int b){return a*10+b;}int main(){int x=3;return pair(x,x);}",true,Set.of());
        var main=main(program);var body=main.getAsAcsArray("script");
        var call=((AcsObject)body.acsa.get(body.acsa.size()-1)).getAsAcsObject("r");
        var arguments=call.getAsAcsArray("param");int slot=((AcsObject)arguments.acsa.get(0)).getAsInt("v");
        var set=new AcsObject();set.put("t",0);set.put("c",SET);set.put("v",slot);set.put("val",4);
        var statements=new AcsArray();statements.acsa.add(set);
        var sequence=new AcsObject();sequence.put("t",0);sequence.put("c",SEQUENCE);
        sequence.put("statements",statements);sequence.put("value",arguments.acsa.get(1));arguments.acsa.set(1,sequence);
        var positions=new IdentityHashMap<AcsElement,int[]>();locate(body,positions);
        var returns=new HashMap<Integer,Integer>();
        for(var item:program.getAsAcsArray("f").acsa) {
            var function=(AcsObject)item;returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        ExecSequenceInliner.optimize(program.getAsAcsArray("f"),returns,positions,128);
        assertEquals(3,main.getAsInt("local-count"));
        var inlined=((AcsObject)main.getAsAcsArray("script").acsa.get(main.getAsAcsArray("script").acsa.size()-1)).getAsAcsObject("r");
        assertEquals(2,inlined.getAsAcsArray("statements").acsa.size());
    }
    private static void locate(AcsElement value,Map<AcsElement,int[]> positions) {
        positions.put(value,new int[]{0,1,1});
        for(var child:ExecShape.children(value))locate(child.value(),positions);
    }
    @Test void observableCallersRetainCallsAndArgumentsAreNotDuplicated() throws Exception {
        var p=compile("extern int event(int n):0x12340001;class C{int x;}int f(int x,int y){return (x&255)+(y&255);}int main(){C c;return 2+f(event(1),event(2));}",false,Set.of(SEQUENCE_INLINING));
        var body=main(p).mmp.get("script");assertEquals(0,count(body,SEQUENCE));
        long events=ExecIr.read(body,null).children().stream().count();assertTrue(events>0);
        assertEquals(2,countCalls(body,0x12340001));
        assertEquals(1,countCalls(body,((AcsObject)p.getAsAcsArray("f").acsa.stream().filter(n->((AcsObject)n).getAsInt("param-count")==2).findFirst().orElseThrow()).getAsInt("id")));
    }
    private long countCalls(AcsElement value,int id) {
        long count=ExecScalarAnalysis.opcode(value)==CALL&&((AcsObject)value).getAsInt("id")==id?1:0;
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)count+=countCalls(child.value(),id);
        return count;
    }
    @Test void observingCleanupKeepsFailingAndSuccessfulCallBoundaries() throws Exception {
        String prefix="extern int probe(int n):0x12340001;class P{int x;~P(){probe(1);}}";
        for(String body:List.of("return 1/x;","return x+1;","return x*x;")) {
            var p=compile(prefix+"int f(int x){"+body+"}int main(){P p;return f(0);}",true,Set.of(SEQUENCE_INLINING));
            assertEquals(0,count(main(p).mmp.get("script"),SEQUENCE));
        }
        var proven=compile(prefix+"int f(int x){return (x&255)+1;}int main(){P p;return f(0);}",true,Set.of(SEQUENCE_INLINING));
        assertEquals(0,count(main(proven).mmp.get("script"),SEQUENCE));
        var helper=compile(prefix+"int scalar(int x){return x&255;}int helper(){int n=scalar(1);return probe(n);}int main(){return helper();}",true,Set.of(SEQUENCE_INLINING));
        var observing=helper.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsAcsObject("debug").getAsString("name").equals("helper")).findFirst().orElseThrow();
        assertEquals(2,count(observing.mmp.get("script"),CALL));
    }
    @Test void recordsDebugFramesAndKeepsRecursiveOrControlFlowCallees() throws Exception {
        var debug=compile("int f(int x){return 1/x;}int main(){return f(0);}",true,Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
        assertEquals(0,count(main(debug).mmp.get("script"),CALL));
        assertEquals("f",((AcsObject)main(debug).getAsAcsObject("debug").getAsAcsArray("inlined").acsa.get(0)).getAsString("name"));
        for(String source:List.of("int f(int x){return f(x);}int main(){return 1+f(3);}","int f(int x){if(x>0)return 1;return 2;}int main(){return 1+f(3);}"))
            assertEquals(1,count(main(compile(source,false,Set.of(SEQUENCE_INLINING))).mmp.get("script"),CALL));
    }
    @Test void reusesOnlySameTypeSlotsFromExitedScopes() throws Exception {
        String source="int main(){int n=1;{int a=n+1;n=a;}{int b=n+2;n=b;}return n;}";
        var plain=main(compile(source,false,Set.of()));var packed=main(compile(source,false,Set.of(SLOT_COMPACTION)));
        assertEquals(3,plain.getAsInt("local-count"));assertEquals(2,packed.getAsInt("local-count"));
        var nested=main(compile("int main(){int a=1;{int b=a;{int c=b;a=c;}}return a;}",false,Set.of(SLOT_COMPACTION)));
        assertEquals(3,nested.getAsInt("local-count"));
    }
    @Test void uncheckedArithmeticRequiresProofIncludingShiftCount() throws Exception {
        var proven=compile("int f(int n){int x=n&15;return x*3+(x<<2);}int main(){return f(4);}",false,Set.of(UNCHECKED_ARITHMETIC));
        var body=((AcsObject)proven.getAsAcsArray("f").acsa.get(0)).mmp.get("script");
        assertEquals(1,count(body,ADD_I32_UNCHECKED));assertEquals(1,count(body,MULTIPLY_I32_UNCHECKED));assertEquals(1,count(body,SHIFT_LEFT_I32_UNCHECKED));
        var unknown=compile("int f(int n,int shift){return (n+1)+(n<<shift);}int main(){return f(4,32);}",false,Set.of(UNCHECKED_ARITHMETIC));
        var checked=((AcsObject)unknown.getAsAcsArray("f").acsa.get(0)).mmp.get("script");
        assertEquals(0,count(checked,ADD_I32_UNCHECKED));assertEquals(0,count(checked,SHIFT_LEFT_I32_UNCHECKED));
    }
    @Test void specializesConstantBranchesPrivatelyWithBoundedVersions() throws Exception {
        var p=compile("int pick(int x,boolean flag){if(flag){return x;}return x*x+1;}int main(){return pick(3,true)+pick(4,true);}",false,Set.of(CONSTANT_SPECIALIZATION));
        var versions=p.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast).filter(f->f.getAsInt("entry-kind")==2).toList();
        assertEquals(2,versions.size());assertEquals(0xffff,versions.get(0).getAsInt("id")&0xffff);
        assertTrue(versions.stream().allMatch(f->count(f.mmp.get("script"),IF)==0));
        assertEquals(4,p.getAsAcsArray("f").acsa.size(),"public original remains alongside private versions");
        String calls=String.join("+",java.util.stream.IntStream.range(1,9).mapToObj(i->"pick("+i+",true)").toList());
        var bounded=compile("int pick(int x,boolean flag){if(flag){return x;}return x*x+1;}int main(){return "+calls+";}",false,Set.of(CONSTANT_SPECIALIZATION));
        assertEquals(4,bounded.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast).filter(f->f.getAsInt("entry-kind")==2).count());
    }

    @Test void debugDoesNotChangeExecutableShapesIncludingPrivateVersions() throws Exception {
        String source="int leaf(int x){return 1/x;}int choose(int x,boolean b){if(b)return leaf(x);return x*x+1;}int main(){return 3+choose(0,true);}";
        var passes=Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE);
        var plain=compile(source,false,passes);var debug=compile(source,true,passes);
        assertEquals(plain.getAsAcsArray("f").acsa.size(),debug.getAsAcsArray("f").acsa.size());
        for(int i=0;i<plain.getAsAcsArray("f").acsa.size();i++) {
            var a=(AcsObject)plain.getAsAcsArray("f").acsa.get(i);var b=(AcsObject)ExecO2.copy(debug.getAsAcsArray("f").acsa.get(i),null);
            assertFalse(a.mmp.containsKey("debug"));b.mmp.remove("debug");assertEquals(a.toJson(),b.toJson());
        }
        assertTrue(plain.getAsAcsArray("debug-files").acsa.isEmpty());
    }
    @Test void nestedInlineRecordsChainThroughTheirCallerLocations() throws Exception {
        var p=compile("int leaf(int x){return 1/x;}int middle(int x){return 3+leaf(x);}int main(){return 4+middle(0);}",true,Set.of(SEQUENCE_INLINING));
        var debug=main(p).getAsAcsObject("debug");var records=debug.getAsAcsArray("inlined").acsa;
        assertEquals(2,records.size());assertEquals("middle",((AcsObject)records.get(0)).getAsString("name"));
        assertEquals("leaf",((AcsObject)records.get(1)).getAsString("name"));
        var location=debug.getAsAcsArray("locations").acsa.get(((AcsObject)records.get(1)).getAsInt("call-site"));
        assertEquals("1",location.toJson().getAsString().split(":")[4]);
    }

    @Test void untypedUninitializedLocalsRetainTheirRuntimeReturnError() throws Exception {
        String source="int f(){def(x);return x;}int main(){return f();}";
        var p=compile(source,true,Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE));
        assertEquals(1,count(main(p).mmp.get("script"),CALL));
        var f=(AcsObject)p.getAsAcsArray("f").acsa.get(0);
        assertEquals(1,f.getAsInt("local-count"));assertEquals(1,count(f.mmp.get("script"),DEFINE));
        for(String definition:List.of("def(x,true);return x;","def(x,3);return 7;"))
            assertDoesNotThrow(()->compile("int f(){"+definition+"}int main(){return f()+1;}",true,Compiler.optimizationPasses(Compiler.Optimization.AGGRESSIVE)));
    }

    @Test void unknownCalleeLocationKeepsTheOriginalFrame() throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute("int f(int x){return 1/x;}int main(){return f(0);}");
        var source=parser.toObj();
        erasePositions(source.getAsJsonObject("body").getAsJsonObject("fff").getAsJsonObject("_funcfff0001"));
        var p=Compiler.compile(source,new Diagnostics(),true,Set.of(SEQUENCE_INLINING));
        assertEquals(1,count(main(p).mmp.get("script"),CALL));
        var f=(AcsObject)p.getAsAcsArray("f").acsa.get(0);assertEquals(0,f.getAsAcsObject("debug").getAsInt("line"));
    }
    private static void erasePositions(com.google.gson.JsonElement value) {
        if(value.isJsonObject()) {
            var object=value.getAsJsonObject();
            object.keySet().removeIf(key->key.equals("_line")||key.equals("_column")||key.equals("_end_line")||key.equals("_end_column"));
            for(var child:object.entrySet())erasePositions(child.getValue());
        } else if(value.isJsonArray())for(var child:value.getAsJsonArray())erasePositions(child);
    }

}
