package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericContextCodecTest {
    private AcsArray array(AcsElement... values) {
        AcsArray result=new AcsArray();result.acsa.addAll(List.of(values));return result;
    }
    private AcsObject reference(int index) {
        AcsObject value=new AcsObject();value.put("ref",index);return value;
    }
    private AcsObject fixed(int abi,int kind) {
        AcsObject value=new AcsObject();value.put("abi",abi);value.put("kind",kind);value.put("width",1);value.put("contexts",array());value.put("placement-contexts",array());return value;
    }
    private AcsObject operation(int opcode,AcsElement context) {
        AcsObject result=new AcsObject();result.put("t",0);result.put("c",opcode);result.put("context",context);return result;
    }
    private ExecProgram program() {
        ExecProgram program=new ExecProgram();program.put("author","context codec");program.put("version",1);
        program.put("exec-version",9);program.put("gvs",0);program.put("ext",new AcsObject());
        program.put("namespace-hint","");program.put("assume-hints",array());
        AcsObject external=new AcsObject();external.put("id",0x12340002);external.put("return-type",6);
        external.put("param-types",array(new AcsIntegerElement(6)));external.put("hidden-count",1);external.put("entry-kind",0);
        program.put("extern-signatures",array(external));
        AcsObject function=new AcsObject();function.put("id",0x22220002);function.put("return-type",6);
        function.put("param-count",1);function.put("local-count",0);function.put("param-types",array(new AcsIntegerElement(6)));
        function.put("hidden-count",2);function.put("entry-kind",1);function.put("script",array());program.put("f",array(function));
        return program;
    }
    private AcsObject function(ExecProgram program) {return (AcsObject)program.getAsAcsArray("f").acsa.get(0);}
    private AcsObject factory(AcsElement... contexts) {
        AcsObject context=fixed(8,3);context.put("factory",0x22220003);context.put("contexts",array(contexts));return context;
    }
    private AbdValue replaceFunction(AbdValue program,AbdValue replacement) {
        var root=program.getAsAss();var functions=root.values.get(7).getAsAss();functions.values.set(0,replacement);
        root.values.set(7,functions.toAbd());return root.toAbd();
    }

    @Test void fixedRecordsPreserveGenericSignaturesAndContextOperations() {
        ExecProgram program=program();AcsArray body=function(program).getAsAcsArray("script");
        body.acsa.add(operation(ExecOpcodes.CONTEXT_ABI,reference(0)));
        body.acsa.add(operation(ExecOpcodes.CONTEXT_DEFAULT,factory(reference(1))));
        for(int opcode:List.of(ExecOpcodes.RETURN_TYPED,ExecOpcodes.CHECK_TYPE)) {
            AcsObject op=operation(opcode,reference(0));op.put("v",42);body.acsa.add(op);
        }
        AcsObject call=new AcsObject();call.put("t",1);call.put("id",0x12340002);call.put("param",array(new AcsIntegerElement(3)));
        call.put("contexts",array(reference(0),fixed(0,0),fixed(7,1),fixed(7,2),fixed(5,0)));body.acsa.add(call);
        AcsObject bind=new AcsObject();bind.put("t",0);bind.put("c",ExecOpcodes.OBJECT_BIND);bind.put("v",new AcsAddress(0));
        bind.put("manual",false);bind.put("destructor",0x22220004);bind.put("contexts",array(reference(1)));body.acsa.add(bind);
        AbdValue encoded=program.toValue();AcsObject decoded=ExecCodec.decode(encoded);
        assertEquals(program.toJson(),decoded.toJson());assertArrayEquals(encoded.toAbdFormat(),decoded.toValue().toAbdFormat());
        var root=encoded.getAsAss();assertEquals(9,AbdBasicType.abd2int(root.values.get(1)));
        assertEquals(5,root.values.get(6).getAsAss().values.get(0).getAsAss().values.size());
        var fields=root.values.get(7).getAsAss().values.get(0).getAsAss().values;
        assertEquals(8,fields.size());assertEquals(2,AbdBasicType.abd2int(fields.get(6)));assertEquals(1,AbdBasicType.abd2int(fields.get(7)));
        var expressions=fields.get(5).getAsAss().values.get(1).getAsAss().values;
        assertEquals(4,expressions.get(4).getAsAss().values.size());assertEquals(6,expressions.get(5).getAsAss().values.size());
        for(int opcode=36;opcode<=39;++opcode)assertEquals(opcode,ExecOpcodes.code(ExecOpcodes.name(opcode)));
    }

    @Test void contextKindsFactoriesAndReferencesAreStrictlyValidated() {
        AcsObject extra=reference(0);extra.put("abi",0);
        AcsObject scalarFactory=fixed(0,0);scalarFactory.put("factory",0x22220003);
        AcsObject unusedChildren=fixed(8,3);unusedChildren.put("contexts",array(reference(0)));
        AcsObject reservedFactory=factory();reservedFactory.put("factory",0x0abd0001);
        for(AcsObject bad:List.of(reference(-1),reference(2),fixed(6,0),fixed(7,0),fixed(8,1),fixed(0,2),fixed(7,3),fixed(8,4),extra,scalarFactory,unusedChildren,reservedFactory)) {
            ExecProgram program=program();function(program).put("script",array(operation(ExecOpcodes.CONTEXT_ABI,bad)));
            assertThrows(IllegalArgumentException.class,program::toValue,bad.toJson().toString());
        }
        ExecProgram program=program();AcsObject bind=new AcsObject();bind.put("t",0);bind.put("c",ExecOpcodes.OBJECT_BIND);
        bind.put("v",new AcsAddress(0));bind.put("manual",true);bind.put("contexts",array(reference(0)));
        function(program).put("script",array(bind));assertThrows(IllegalArgumentException.class,program::toValue);
    }

    @Test void v9WireRequiresMetadataAndContextsEvenWhenDebugViewOmitsDefaults() {
        ExecProgram program=program();AcsObject function=function(program);function.put("return-type",5);
        function.mmp.remove("hidden-count");function.mmp.remove("entry-kind");
        AcsObject call=new AcsObject();call.put("t",1);call.put("id",0x0abd0000);call.put("param",array());function.put("script",call);
        AbdValue encoded=program.toValue();AcsObject decoded=ExecCodec.decode(encoded);
        AcsObject view=(AcsObject)decoded.getAsAcsArray("f").acsa.get(0);
        assertEquals(0,view.getAsInt("hidden-count"));assertEquals(0,view.getAsInt("entry-kind"));
        assertTrue(view.getAsAcsObject("script").getAsAcsArray("contexts").acsa.isEmpty());
        var wire=encoded.getAsAss().values.get(7).getAsAss().values.get(0).getAsAss();
        wire.values.remove(7);AbdValue missingEntry=replaceFunction(encoded,wire.toAbd());
        assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(missingEntry));
        wire=encoded.getAsAss().values.get(7).getAsAss().values.get(0).getAsAss();var callWire=wire.values.get(5).getAsAss();callWire.values.remove(3);
        wire.values.set(5,callWire.toAbd());AbdValue missingContexts=replaceFunction(encoded,wire.toAbd());
        assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(missingContexts));
        var old=encoded.getAsAss();old.values.set(1,AbdBasicType.int2Abd(7));assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(old.toAbd()));
    }

    @Test void contextMetadataWidthsAndPhysicalNestingRemainBounded() {
        for(String name:List.of("hidden-count","entry-kind")) {
            ExecProgram program=program();function(program).put(name,-1);assertThrows(IllegalArgumentException.class,program::toValue);
        }
        ExecProgram tooMany=program();function(tooMany).put("hidden-count",ExecCodec.MAX_SLOTS+1);assertThrows(IllegalArgumentException.class,tooMany::toValue);
        ExecProgram tooDeep=program();AcsObject context=fixed(0,0);
        for(int i=0;i<70;++i)context=factory(context);
        function(tooDeep).put("script",operation(ExecOpcodes.CONTEXT_ABI,context));assertThrows(IllegalArgumentException.class,tooDeep::toValue);
        ExecProgram cycle=program();AcsObject cyclic=factory();cyclic.getAsAcsArray("contexts").acsa.add(cyclic);
        function(cycle).put("script",operation(ExecOpcodes.CONTEXT_ABI,cyclic));assertThrows(IllegalArgumentException.class,cycle::toValue);
        ExecProgram wrongWidth=program();function(wrongWidth).put("hidden-count",new AcsAddress(1));assertThrows(IllegalArgumentException.class,wrongWidth::toValue);
    }
}
