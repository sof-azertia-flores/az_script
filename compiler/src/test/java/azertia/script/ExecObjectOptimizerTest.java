package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;

class ExecObjectOptimizerTest {
    private static AcsObject compile(String source,Compiler.OptimizationPass pass) throws Exception {
        return ExecO3Test.main(program(source,pass));
    }
    private static AcsObject program(String source,Compiler.OptimizationPass pass) throws Exception {
        var parser=new GeneraterJson.AzScript();parser.execute(source);
        var program=Compiler.compile(parser.toObj(),new Diagnostics(),false,Compiler.Optimization.NONE);
        ExecObjectOptimizer.optimize(program.getAsAcsArray("f"),Set.of(pass),null,128);
        assertArrayEquals(program.toValue().toAbdFormat(),ExecCodec.encode(ExecCodec.decode(program.toValue())).toAbdFormat());
        return program;
    }
    private static long calls(AcsElement value,int id) {
        long result=ExecScalarAnalysis.opcode(value)==CALL&&((AcsObject)value).getAsInt("id")==id?1:0;
        for(var child:ExecShape.children(value))
            if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY)result+=calls(child.value(),id);
        return result;
    }
    @Test void cachesOnlySuccessfullyCheckedScalarFieldReads() throws Exception {
        String prefix="class Cell{int value=7;}int main(){Cell cell;";
        var checked=compile("class Cell{double value=7.0;}int main(){Cell cell;int a=int(cell.value);int b=int(cell.value);return a+b;}",FIELD_CACHING);
        assertEquals(1,calls(checked.mmp.get("script"),0x0abd0006));
        var nominal=compile(prefix+"int a=cell.value;int b=cell.value;return a+b;}",FIELD_CACHING);
        assertEquals(2,calls(nominal.mmp.get("script"),0x0abd0006));
    }
    @Test void mutationsCallsCleanupAndLoopsInvalidateTheCache() throws Exception {
        String prefix="class Cell{double value=7.0;void change(){value=9.0;}}int main(){Cell cell;int a=int(cell.value);";
        for(String barrier:List.of("cell.value=9.0;","cell.change();","{Cell other;}",
                "for(int i=0;i<2;i++){cell.value=double(i);}","a=0;")) {
            var function=compile(prefix+barrier+"int b=int(cell.value);return a+b;}",FIELD_CACHING);
            assertTrue(calls(function.mmp.get("script"),0x0abd0006)>=2,barrier);
        }
    }
    @Test void closedScalarCallsPreserveOnlyProvenPrimitiveFieldFacts() throws Exception {
        String prefix="class Cell{double value=7.0;}int pure(int n){return n+1;}int main(){Cell cell;int a=int(cell.value);";
        for(String call:List.of("pure(3);","pure(a);","pure(a+1);")) {
            var function=compile(prefix+call+"int b=int(cell.value);return a+b;}",FIELD_CACHING);
            assertEquals(1,calls(function.mmp.get("script"),0x0abd0006),call);
        }
        var nominal=compile("class Cell{int value=7;}int pure(int n){return n+1;}int main(){Cell cell;int a=int(cell.value);pure(cell.value);int b=int(cell.value);return a+b;}",FIELD_CACHING);
        assertEquals(3,calls(nominal.mmp.get("script"),0x0abd0006),"nominal field arguments may carry an owning host value");
        var callback=compile("extern void touch():0x12340001;class Cell{double value=7.0;}int impure(int n){touch();return n;}int main(){Cell cell;int a=int(cell.value);impure(a);int b=int(cell.value);return a+b;}",FIELD_CACHING);
        assertEquals(2,calls(callback.mmp.get("script"),0x0abd0006));
    }
    @Test void methodGuardKeepsTheOriginalVirtualFallback() throws Exception {
        var function=compile("extern void barrier():0x12340001;class Cell{int v;virtual int read(){return v;}}int main(){Cell * c=new Cell();barrier();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
        assertEquals(1,ExecO3Test.count(function.mmp.get("script"),GUARD_METHOD));
        assertEquals(1,ExecO3Test.count(function.mmp.get("script"),CALL_VIRTUAL));
    }
    @Test void freshFactoryCallsUseTheActualCompletedConstructorTable() throws Exception {
        var plain=compile("class Cell{int v;virtual int read(){return v;}}int main(){Cell * c=new Cell();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
        assertEquals(0,ExecO3Test.count(plain.mmp.get("script"),GUARD_METHOD));
        assertEquals(0,ExecO3Test.count(plain.mmp.get("script"),CALL_VIRTUAL));
        var inherited=program("class Base{int v;virtual int read(){return 3;}}class Child:Base{override int read(){return 9;}}int main(){Base * c=new Child();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
        var body=ExecO3Test.main(inherited).mmp.get("script");
        assertEquals(0,ExecO3Test.count(body,CALL_VIRTUAL));
        var child=inherited.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .filter(f->f.getAsInt("return-type")==Compiler.AsTypes.INT_VALUE&&f.getAsInt("param-count")==1)
                .filter(f->literalReturn(f.mmp.get("script"),9)).findFirst().orElseThrow();
        assertEquals(1,calls(body,child.getAsInt("id")),"the last table belongs to Child, despite Base's earlier binding");
    }
    private static boolean literalReturn(AcsElement value,int expected) {
        if(ExecScalarAnalysis.opcode(value)==RETURN&&ExecScalarAnalysis.field(value,"r") instanceof AcsIntegerElement n&&n.s==expected)return true;
        for(var child:ExecShape.children(value))if(child.role()!=ExecShape.Role.CONTEXT&&child.role()!=ExecShape.Role.METHOD_ENTRY
                &&literalReturn(child.value(),expected))return true;
        return false;
    }
    @Test void closedScalarLoopRetainsFreshReceiverProof() throws Exception {
        String prefix="class Base{int marker;virtual int value(int n){return n%17+1;}}class First:Base{override int value(int n){return n%17+3;}}";
        var function=compile(prefix+"int main(){Base * first=new First();int total=0;int i=0;while(i<20){total=total+first.value(i);i=i+1;}delete first;return total;}",DEVIRTUALIZATION);
        assertEquals(0,ExecO3Test.count(function.mmp.get("script"),CALL_VIRTUAL));
        assertEquals(0,ExecO3Test.count(function.mmp.get("script"),GUARD_METHOD));
        var fields=compile("class Cell{int v;virtual int value(int n){return v+n;}}int main(){Cell * c=new Cell();int total=0;int i=0;while(i<20){total=total+c.value(i);i=i+1;}delete c;return total;}",DEVIRTUALIZATION);
        assertEquals(1,ExecO3Test.count(fields.mmp.get("script"),CALL_VIRTUAL),"a nominal scalar field does not prove a callback-free loop");
    }
    @Test void aliasesHostCallsWritesAndCleanupDiscardFreshness() throws Exception {
        String prefix="extern void barrier():0x12340001;class Cell{int v;virtual int read(){return 7;}}int main(){Cell * c=new Cell();";
        for(String barrier:List.of("barrier();","Cell * alias=c;","c.v=9;","{Cell other;}","if(true){barrier();}")) {
            var function=compile(prefix+barrier+"int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
            assertEquals(1,ExecO3Test.count(function.mmp.get("script"),CALL_VIRTUAL),barrier);
        }
        var condition=compile("extern boolean mutate():0x12340001;class Cell{int v;virtual int read(){return 7;}}int main(){Cell * c=new Cell();int r=mutate()?c.read():0;delete c;return r;}",DEVIRTUALIZATION);
        assertEquals(1,ExecO3Test.count(condition.mmp.get("script"),CALL_VIRTUAL),"evaluate a condition before using either arm's proof");
    }
    @Test void constructorCallbacksAndPhaseCallsPreventFactoryProof() throws Exception {
        for(String constructor:List.of("Cell(){touch();}","Cell(){int n=read();}")) {
            var function=compile("extern void touch():0x12340001;class Cell{int v;"+constructor+"virtual int read(){return 7;}}int main(){Cell * c=new Cell();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
            assertEquals(1,ExecO3Test.count(function.mmp.get("script"),CALL_VIRTUAL),constructor);
        }
        var phases=program("class Base{int v;Base(){int n=read();}~Base(){int n=read();}virtual int read(){return 3;}}class Child:Base{override int read(){return 9;}}int main(){Base * c=new Child();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
        assertTrue(phases.getAsAcsArray("f").acsa.stream().map(AcsObject.class::cast)
                .mapToLong(f->ExecO3Test.count(f.mmp.get("script"),CALL_VIRTUAL)).sum()>=3);
    }
    @Test void visibleOverridesAndEffectfulArgumentsPreventGuarding() throws Exception {
        var overrides=compile("class Base{int v;virtual int read(){return v;}}class Child:Base{override int read(){return 9;}}int main(){Base * c=new Child();int r=c.read();delete c;return r;}",DEVIRTUALIZATION);
        assertEquals(0,ExecO3Test.count(overrides.mmp.get("script"),GUARD_METHOD));
        var arguments=compile("extern int host():0x12340001;class Cell{int v;virtual int read(int n){return v+n;}}int main(){Cell * c=new Cell();int r=c.read(host());delete c;return r;}",DEVIRTUALIZATION);
        assertEquals(0,ExecO3Test.count(arguments.mmp.get("script"),GUARD_METHOD));
    }
    @Test void derivingAnObjectAddressIsAnIdentityUse() {
        var variable=new AcsObject();variable.put("t",0);variable.put("c",VARIABLE);variable.put("v",0);
        for(int opcode:List.of(OBJECT_ADDRESS,BLOCK_ADDRESS)) {
            var address=new AcsObject();address.put("t",0);address.put("c",opcode);address.put("v",variable);
            if(opcode==OBJECT_ADDRESS)address.put("offset",0);
            assertEquals(ExecLoopMotion.Escape.LOCAL_IDENTITY,ExecLoopMotion.ownership(ExecIr.read(address,null)).escape());
        }
    }
}
