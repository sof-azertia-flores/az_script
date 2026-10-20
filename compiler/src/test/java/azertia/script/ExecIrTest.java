package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;

class ExecIrTest {
    private AcsObject program(String source) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute(source);
        return Compiler.compile(parser.toObj());
    }
    private ExecIr.Function main(String source) throws Exception {
        for (var value : program(source).getAsAcsArray("f").acsa) {
            var function = (AcsObject) value;
            if (function.getAsInt("id") == 0x0fff0000) return ExecIr.analyze(function.mmp.get("script"), null);
        }
        throw new AssertionError();
    }
    private List<ExecIr.Node> nodes(ExecIr.Node root) {
        List<ExecIr.Node> result = new ArrayList<>(); result.add(root);
        for (var edge : root.children()) result.addAll(nodes(edge.value()));
        return result;
    }
    @Test void typedEffectsAndSourceIdentity() throws Exception {
        var program = program("int main(){int x=1+2;print(x);return x;}");
        byte[] before = program.toValue().toAbdFormat();
        for (var value : program.getAsAcsArray("f").acsa) {
            var body = ((AcsObject)value).mmp.get("script");
            var function = ExecIr.analyze(body, Map.of(body,new int[]{1,2,3}));
            assertSame(body, function.body().source());
            assertEquals(List.of(1,2,3),function.body().position());
            for (var node : nodes(function.body())) {
                if (node.opcode()==ADD) assertEquals(INT_VALUE,node.type());
                if (node.opcode()==VARIABLE) assertNotNull(node.slot());
                if (node.opcode()==CALL) assertTrue(node.invalidatesSlots());
            }
        }
        assertArrayEquals(before,program.toValue().toAbdFormat());
    }
    @Test void controlFlowHasLoopAndExitEdges() throws Exception {
        var function = main("int main(){int i=0;while(i<8){i++;if(i==2){continue;}if(i==7){break;}}return i;}");
        var flows = EnumSet.noneOf(ExecIr.Flow.class);
        for (var block : function.blocks()) for (var successor : block.successors()) {
            assertTrue(successor.target()>=0 && successor.target()<function.blocks().size());
            flows.add(successor.flow());
        }
        assertTrue(flows.containsAll(EnumSet.of(ExecIr.Flow.TRUE,ExecIr.Flow.FALSE,ExecIr.Flow.BACK,
                ExecIr.Flow.BREAK,ExecIr.Flow.CONTINUE,ExecIr.Flow.RETURN,ExecIr.Flow.EXCEPTION)));
    }
    @Test void ownedStorageIsAnObjectValueRatherThanItsAddress() {
        var allocation=new AcsObject();allocation.put("t",0);allocation.put("c",NEW_BLOCK);allocation.put("size",1);
        var owned=ExecIr.read(allocation,null);assertEquals(OBJECT_VALUE,owned.type());
        var address=new AcsObject();address.put("t",0);address.put("c",BLOCK_ADDRESS);address.put("v",allocation);
        assertEquals(ADDRESS_VALUE,ExecIr.read(address,null).type());
        assertEquals(ExecLoopMotion.Escape.LOCAL_IDENTITY,ExecLoopMotion.ownership(owned).escape());
    }
    @Test void returnsAndErrorsPassThroughScopeCleanupRegions() throws Exception {
        var function=main("class A{int n;~A(){print(n);}}int main(){A a;return 7;}");
        var returned=function.blocks().stream().filter(b->b.region()!=null&&b.region().opcode()==RETURN).findFirst().orElseThrow();
        var target=returned.successors().stream().filter(s->s.flow()==ExecIr.Flow.RETURN).findFirst().orElseThrow();
        assertNotEquals(function.exit(),target.target());
        assertEquals(BLOCK,function.blocks().get(target.target()).region().opcode());
    }
    @Test void ownershipAndLazyRegionsRemainOpaque() throws Exception {
        var function=main("class A{int n;~A(){print(n);}}int main(){A a;boolean b=false&&(1/0==2);return 3;}");
        assertTrue(function.body().effects().contains(ExecIr.Effect.CLEANUP));
        assertTrue(function.body().effects().contains(ExecIr.Effect.CALL));
        var lazy=nodes(function.body()).stream().filter(n->n.opcode()==AND).findFirst().orElseThrow();
        assertEquals(ExecShape.Role.LAZY_VALUE,lazy.children().get(1).location().role());
        assertTrue(lazy.effects().contains(ExecIr.Effect.THROW));
    }
}
