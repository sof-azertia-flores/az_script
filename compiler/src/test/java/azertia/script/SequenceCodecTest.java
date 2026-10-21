package azertia.script;

import azertia.binary.complexBinary.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SequenceCodecTest {
    private static AcsObject node(int opcode) {
        AcsObject result = new AcsObject(); result.put("t", 0); result.put("c", opcode); return result;
    }
    private static AcsArray array(AcsElement... values) {
        AcsArray result = new AcsArray(); result.acsa.addAll(List.of(values)); return result;
    }
    private static AcsObject sequence(AcsElement value, AcsElement... statements) {
        AcsObject result = node(ExecOpcodes.SEQ); result.put("statements", array(statements)); result.put("v", value); return result;
    }
    private static AcsObject print(String text) {
        AcsObject result = new AcsObject(); result.put("t", 1); result.put("id", 0x0abd0000);
        result.put("param", array(new AcsStringElement(text))); return result;
    }
    private static AcsObject define(int slot) {
        AcsObject result = node(ExecOpcodes.DEFINE); result.put("v", slot); result.put("val", new AcsIntegerElement(1)); return result;
    }
    private static AcsObject loop(AcsElement... body) {
        AcsObject result = node(ExecOpcodes.WHILE); result.put("v", new AcsBooleanElement(true)); result.put("val", array(body)); return result;
    }
    private static AcsObject binary(int opcode) {
        AcsObject result = node(opcode); result.put("v1", new AcsIntegerElement(1)); result.put("v2", new AcsIntegerElement(2)); return result;
    }
    private static AcsObject program(AcsElement... body) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute("void main(){}"); AcsObject result = Compiler.compile(parser.toObj());
        AcsObject function = (AcsObject) result.getAsAcsArray("f").acsa.get(0);
        function.put("local-count", 1); function.put("script", array(body)); return result;
    }
    private static void roundTrip(AcsElement... body) throws Exception {
        byte[] wire = program(body).toValue().toAbdFormat();
        assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat());
    }

    @Test void sequencesAndWrappingOperatorsHaveStableNumbersAndRoundTrip() throws Exception {
        assertEquals(54, ExecOpcodes.code("seq"));
        assertEquals(List.of(55, 56, 57), List.of(ExecOpcodes.code("wrap_add"), ExecOpcodes.code("wrap_minus"), ExecOpcodes.code("wrap_multiply")));
        roundTrip(sequence(new AcsIntegerElement(1)));
        roundTrip(sequence(new AcsIntegerElement(1), print("a"), array(define(0))));
        roundTrip(sequence(sequence(new AcsIntegerElement(2), print("b")), loop(node(ExecOpcodes.BREAK)), loop(node(ExecOpcodes.CONTINUE))));
        for (int opcode : List.of(ExecOpcodes.WRAP_ADD, ExecOpcodes.WRAP_SUBTRACT, ExecOpcodes.WRAP_MULTIPLY)) roundTrip(binary(opcode));
    }

    @Test void sequencesKeepTheEnclosingScopeAndControlFlow() throws Exception {
        AcsObject badReturn = node(ExecOpcodes.RETURN);
        AcsObject allocation = node(ExecOpcodes.NEW_BLOCK); allocation.put("size", 1);
        for (AcsObject invalid : List.of(sequence(new AcsIntegerElement(1), define(0)),
                sequence(define(0)), sequence(new AcsIntegerElement(1), node(ExecOpcodes.BREAK)),
                sequence(new AcsIntegerElement(1), node(ExecOpcodes.CONTINUE)), sequence(new AcsIntegerElement(1), badReturn),
                sequence(new AcsIntegerElement(1), array(allocation)))) {
            AcsObject program = program(invalid);
            assertThrows(IllegalArgumentException.class, program::toValue);
            AcsObject looped = program(loop(invalid));
            assertThrows(IllegalArgumentException.class, looped::toValue);
        }
        AcsObject missing = sequence(new AcsIntegerElement(1)); missing.mmp.remove("statements");
        AcsObject program = program(missing);
        assertThrows(IllegalArgumentException.class, program::toValue);
    }
}
