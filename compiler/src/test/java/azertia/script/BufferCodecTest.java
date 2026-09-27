package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BufferCodecTest {
    private AcsArray array(AcsElement... values) {
        AcsArray result=new AcsArray();result.acsa.addAll(List.of(values));return result;
    }
    private AcsObject context(int abi,int kind,int width) {
        AcsObject value=new AcsObject();value.put("abi",abi);value.put("kind",kind);value.put("width",width);
        value.put("contexts",array());value.put("placement-contexts",array());return value;
    }
    private AcsObject buffer(AcsObject element) {
        AcsObject value=context(8,4,1);value.put("element",element);return value;
    }
    private AcsObject reference(int index) {AcsObject value=new AcsObject();value.put("ref",index);return value;}
    private AcsObject operation(int opcode,AcsObject context) {
        AcsObject value=new AcsObject();value.put("t",0);value.put("c",opcode);value.put("context",context);return value;
    }
    private AcsObject bufferOperation(int opcode,int arguments) {
        AcsObject value=new AcsObject();value.put("t",0);value.put("c",ExecOpcodes.BUFFER_OP);value.put("op",opcode);
        value.put("v",operation(ExecOpcodes.BUFFER_NEW,context(0,0,1)));AcsArray args=array();
        for(int i=0;i<arguments;++i)args.acsa.add(new AcsIntegerElement(i));value.put("args",args);return value;
    }
    private ExecProgram program(AcsElement body) {
        ExecProgram program=new ExecProgram();program.put("author","buffer codec");program.put("version",1);
        program.put("exec-version",9);program.put("gvs",0);program.put("ext",new AcsObject());
        program.put("namespace-hint","");program.put("assume-hints",array());program.put("extern-signatures",array());
        AcsObject function=new AcsObject();function.put("id",0x22220002);function.put("return-type",5);
        function.put("param-count",0);function.put("local-count",0);function.put("param-types",array());
        function.put("hidden-count",1);function.put("entry-kind",0);function.put("script",body);program.put("f",array(function));return program;
    }
    private AbdValue raw(AbdValue... fields) {AbdSimpleStack result=new AbdSimpleStack();result.values.addAll(List.of(fields));return result.toAbd();}
    private AbdValue replaceBody(AbdValue program,AbdValue body) {
        var root=program.getAsAss();var functions=root.values.get(7).getAsAss();var function=functions.values.get(0).getAsAss();
        function.values.set(5,body);functions.values.set(0,function.toAbd());root.values.set(7,functions.toAbd());return root.toAbd();
    }
    private AbdValue wireContext(AbdValue program) {
        return program.getAsAss().values.get(7).getAsAss().values.get(0).getAsAss().values.get(5).getAsAss().values.get(1);
    }

    @Test void v9ContextsAndBufferOpcodesRoundTripExactWidths() {
        AcsObject object=context(8,3,3);object.put("factory",0x22220003);object.put("contexts",array(reference(0)));
        object.put("placement",0x22220004);object.put("placement-contexts",array(buffer(context(7,2,1))));
        AcsArray body=array(operation(ExecOpcodes.BUFFER_NEW,object));
        for(int op=0;op<=6;++op)body.acsa.add(bufferOperation(op,op<2?0:op==4?2:1));
        AcsObject compare=operation(ExecOpcodes.VALUE_COMPARE,buffer(object));compare.put("v1",1);compare.put("v2",2);body.acsa.add(compare);
        ExecProgram original=program(body);AbdValue encoded=original.toValue();AcsObject decoded=ExecCodec.decode(encoded);
        assertEquals(original.toJson(),decoded.toJson());assertArrayEquals(encoded.toAbdFormat(),decoded.toValue().toAbdFormat());
        assertEquals(9,AbdBasicType.abd2int(encoded.getAsAss().values.get(1)));
        assertEquals(11,wireContext(program(operation(ExecOpcodes.BUFFER_NEW,object)).toValue()).getAsAss().values.size());
        assertEquals(10,wireContext(program(operation(ExecOpcodes.BUFFER_NEW,buffer(context(0,0,1)))).toValue()).getAsAss().values.size());
        for(int op=40;op<=42;++op)assertEquals(op,ExecOpcodes.code(ExecOpcodes.name(op)));
    }

    @Test void contextWidthsPlacementsAndElementRulesAreStrict() {
        AcsObject scalarPlacement=context(0,0,1);scalarPlacement.put("placement",0x22220004);
        AcsObject absentPlacement=context(8,3,2);absentPlacement.put("placement-contexts",array(reference(0)));
        AcsObject illegalFactory=buffer(context(0,0,1));illegalFactory.put("factory",0x22220003);
        AcsObject illegalElement=context(8,3,2);illegalElement.put("element",context(0,0,1));
        AcsObject missingElement=context(8,4,1);AcsObject badReference=buffer(reference(1));
        AcsObject reservedPlacement=context(8,3,2);reservedPlacement.put("placement",0x0abd0001);
        for(AcsObject invalid:List.of(context(8,3,0),context(8,3,ExecCodec.MAX_SLOTS+1),context(0,0,2),
                context(7,1,2),context(7,2,2),context(8,4,2),scalarPlacement,absentPlacement,illegalFactory,
                illegalElement,missingElement,badReference,reservedPlacement,buffer(context(5,0,1))))
            assertThrows(IllegalArgumentException.class,()->program(operation(ExecOpcodes.CONTEXT_ABI,invalid)).toValue());
        assertThrows(IllegalArgumentException.class,()->program(operation(ExecOpcodes.BUFFER_NEW,context(5,0,1))).toValue());
        AcsObject compare=operation(ExecOpcodes.VALUE_COMPARE,context(5,0,1));compare.put("v1",1);compare.put("v2",2);
        assertThrows(IllegalArgumentException.class,()->program(compare).toValue());
    }

    @Test void bufferOperationRangeAndArityAreChecked() {
        for(int op=-1;op<=7;++op)for(int args=0;args<=3;++args) {
            boolean valid=op>=0&&op<=6&&args==(op<2?0:op==4?2:1);ExecProgram program=program(bufferOperation(op,args));
            if(valid)assertDoesNotThrow(program::toValue);else assertThrows(IllegalArgumentException.class,program::toValue);
        }
    }

    @Test void fixedContextWireFlagsFieldsAndOldVersionsAreRejected() {
        AbdValue encoded=program(operation(ExecOpcodes.BUFFER_NEW,context(0,0,1))).toValue();
        var context=wireContext(encoded).getAsAss();assertEquals(9,context.values.size());
        for(int index:List.of(3,4,6,8)) {
            var changed=wireContext(encoded).getAsAss();changed.values.set(index,new AbdValue(new byte[]{2}));
            AbdValue malformed=replaceBody(encoded,raw(AbdBasicType.int2Abd(40),changed.toAbd()));
            assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(malformed));
        }
        var missing=context.toAbd().getAsAss();missing.values.remove(8);
        assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(replaceBody(encoded,raw(AbdBasicType.int2Abd(40),missing.toAbd()))));
        for(int version:List.of(7,8)) {
            var old=encoded.getAsAss();old.values.set(1,AbdBasicType.int2Abd(version));assertThrows(IllegalArgumentException.class,()->ExecCodec.decode(old.toAbd()));
        }
    }

    @Test void nestedBufferContextsRespectPhysicalDepth() {
        AcsObject valid=context(0,0,1);for(int i=0;i<100;++i)valid=buffer(valid);
        ExecProgram bounded=program(operation(ExecOpcodes.BUFFER_NEW,valid));assertDoesNotThrow(bounded::toValue);
        AcsObject excessive=valid;for(int i=0;i<28;++i)excessive=buffer(excessive);
        ExecProgram deep=program(operation(ExecOpcodes.BUFFER_NEW,excessive));assertThrows(IllegalArgumentException.class,deep::toValue);
        AcsObject cyclic=buffer(context(0,0,1));cyclic.put("element",cyclic);
        assertThrows(IllegalArgumentException.class,()->program(operation(ExecOpcodes.BUFFER_NEW,cyclic)).toValue());
    }
}
