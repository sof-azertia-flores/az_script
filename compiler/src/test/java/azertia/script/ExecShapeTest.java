package azertia.script;

import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The independent codec supplies the depth oracle, including metadata and empty wire lists. */
class ExecShapeTest {
    private static AcsArray array(AcsElement... values) {
        AcsArray result = new AcsArray(); result.acsa.addAll(List.of(values)); return result;
    }
    private static AcsObject node(int opcode) {
        AcsObject result = new AcsObject(); result.put("t", 0); result.put("c", opcode); return result;
    }
    private static AcsObject variable() { AcsObject result = node(3); result.put("v", 1); return result; }
    private static AcsObject reference() { AcsObject result = new AcsObject(); result.put("ref", 0); return result; }
    private static AcsObject fixed(int abi, int kind) {
        AcsObject result = new AcsObject(); result.put("abi", abi); result.put("kind", kind); return result;
    }
    private static AcsObject call(AcsElement... arguments) {
        AcsObject result = new AcsObject(); result.put("t", 1); result.put("id", 0x22220003);
        result.put("param", array(arguments)); result.put("contexts", array(reference())); return result;
    }
    private static AcsObject method(AcsElement... contexts) {
        AcsObject result = new AcsObject(); result.put("id", contexts.length == 0 ? 0x22220006 : 0x22220003); result.put("contexts", array(contexts)); return result;
    }
    private static AcsObject unary(AcsElement value) { AcsObject result = node(26); result.put("v", value); return result; }
    private static AcsElement pad(AcsElement value, int count) {
        for (int i = 0; i < count; i++) value = unary(value);
        return value;
    }
    private static AcsElement specimen(int opcode) {
        if (opcode == 0) return new AcsIntegerElement(1);
        if (opcode == 1) return array(new AcsIntegerElement(1));
        if (opcode == 2) return call(new AcsAddress(0));
        AcsObject result = node(opcode);
        switch (opcode) {
            case 3, 34 -> result.put("v", 1);
            case 4, 5 -> { result.put("v", 1); result.put("val", 1); }
            case 6 -> { result.put("v1", variable()); result.put("v2", 1); }
            case 7, 8 -> result.put("r", 1);
            case 9 -> { result.put("v", new AcsAddress(0)); result.put("offset", 0); }
            case 10 -> {
                result.put("v", new AcsAddress(0)); result.put("manual", false); result.put("destructor", 0x22220007);
                result.put("contexts", array(reference()));
            }
            case 11, 25, 26, 33 -> result.put("v", 1);
            case 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 43, 44, 45, 46, 47, 48, 55, 56, 57 -> {
                result.put("v1", 1); result.put("v2", 1);
            }
            case 27 -> { result.put("v", true); result.put("val", array()); result.put("else", array()); }
            case 28 -> { result.put("v", true); result.put("val", array()); }
            case 29, 30 -> {}
            case 31 -> { result.put("v", array()); result.put("val", array()); result.put("on-error", true); }
            case 32 -> result.put("size", 1);
            case 35 -> result.put("v", variable());
            case 36, 37, 40 -> result.put("context", fixed(0, 0));
            case 38, 39 -> { result.put("v", 1); result.put("context", reference()); }
            case 41 -> { result.put("op", 4); result.put("v", specimen(40)); result.put("args", array(new AcsIntegerElement(1), new AcsIntegerElement(2))); }
            case 42 -> { result.put("context", fixed(0, 0)); result.put("v1", 1); result.put("v2", 2); }
            case 49 -> { result.put("type", 0); result.put("v", 1); }
            case 50 -> { result.put("v", true); result.put("then", 1); result.put("else", 2); }
            case 51 -> { result.put("v", new AcsAddress(0)); result.put("context", fixed(7, 2)); result.put("slot-types", array(new AcsIntegerElement(0))); }
            case 52 -> { result.put("v", new AcsAddress(0)); result.put("entries", array(method(reference()))); }
            case 53 -> { result.put("slot", 0); result.put("type", 0); result.put("param", array(new AcsAddress(0))); }
            case 54 -> { result.put("statements", array(new AcsIntegerElement(1), array())); result.put("v", 2); }
            default -> throw new AssertionError("Missing specimen for " + opcode);
        }
        return result;
    }
    private static AcsObject function(int id, int type, int hidden, AcsArray params, AcsElement body) {
        AcsObject result = new AcsObject(); result.put("id", id); result.put("return-type", type);
        result.put("param-count", params.acsa.size()); result.put("param-types", params); result.put("local-count", 2);
        result.put("hidden-count", hidden); result.put("entry-kind", 0); result.put("script", body); return result;
    }
    private static AcsObject placementFunction() {
        AcsObject result = function(0x22220005, 5, 1, array(new AcsIntegerElement(7)), array());
        result.put("entry-kind", 1); return result;
    }
    private static ExecProgram program(AcsElement body) {
        ExecProgram result = new ExecProgram(); result.put("author", "shape boundary"); result.put("version", 2);
        result.put("exec-version", ExecCodec.VERSION); result.put("gvs", 0); result.put("ext", new AcsObject());
        result.put("extern-signatures", array()); result.put("namespace-hint", ""); result.put("assume-hints", array()); result.put("debug-files", array());
        result.put("f", array(
                function(0x22220002, 5, 1, array(new AcsIntegerElement(7)), body),
                function(0x22220003, 0, 1, array(new AcsIntegerElement(7)), new AcsIntegerElement(0)),
                function(0x22220004, 8, 1, array(), nodeWithSize()),
                placementFunction(),
                function(0x22220006, 0, 0, array(new AcsIntegerElement(7)), new AcsIntegerElement(0)),
                function(0x22220008, 5, 0, array(), array()),
                function(0x22220007, 5, 1, array(new AcsIntegerElement(7)), array())));
        return result;
    }
    private static AcsObject nodeWithSize() { AcsObject result = node(32); result.put("size", 1); return result; }
    private static AbdValue encoded(AcsElement body) { return ExecCodec.encode(program(body)); }
    private static void boundary(AcsElement expression, String label) {
        assertDoesNotThrow(() -> encoded(expression), label + " baseline must be codec-valid");
        int height = ExecShape.height(expression);
        for (int limit : List.of(128, 96, 64, 48, 32)) {
            int padding = limit - 4 - height;
            assertTrue(padding >= 0, label);
            AcsElement at = pad(expression, padding), past = unary(at);
            assertDoesNotThrow(() -> ExecShape.requireFits(at, 4, limit), label + " at " + limit);
            String expected = "Exec ABD nesting exceeds " + limit + " levels";
            assertEquals(expected, assertThrows(IllegalArgumentException.class,
                    () -> ExecShape.requireFits(past, 4, limit), label).getMessage());
            // The codec has a fixed128 budget. Extra outer padding makes the
            // lower Compiler budget's boundary the same physical wire boundary.
            AcsElement codecAt = pad(at, 128 - limit), codecPast = unary(codecAt);
            AbdValue wire = assertDoesNotThrow(() -> encoded(codecAt), label + " codec at " + limit);
            assertDoesNotThrow(() -> ExecCodec.decode(wire), label + " decode at " + limit);
            String failure = assertThrows(IllegalArgumentException.class,
                    () -> encoded(codecPast), label + " codec past " + limit).getMessage();
            assertTrue(failure.contains("nesting"), label + ": " + failure);
        }
    }

    @Test void everyWireOpcodeAgreesWithTheCodecAtItsExactBoundary() {
        for (int opcode = 0; opcode <= 57; opcode++) {
            final int code = opcode;
            assertDoesNotThrow(() -> ExecOpcodes.name(code));
            boundary(specimen(opcode), "opcode " + opcode);
        }
        assertThrows(IllegalArgumentException.class, () -> ExecOpcodes.name(58));
    }

    @Test void emptyListsAndOptionalFieldsStillCountTheirEncodedRecords() {
        boundary(array(), "empty block");
        AcsObject emptyCall = call(); emptyCall.mmp.remove("contexts"); emptyCall.put("id", 0x22220008); boundary(emptyCall, "empty call and omitted contexts");
        AcsObject definition = node(4); definition.put("v", 1); boundary(definition, "uninitialized define");
        boundary(node(7), "void return");
        AcsObject bind = node(10); bind.put("v", variable()); bind.put("manual", false); boundary(bind, "bind without contexts");
        AcsObject bufferLength = node(41); bufferLength.put("op", 0); bufferLength.put("v", specimen(40)); bufferLength.put("args", array());
        boundary(bufferLength, "empty buffer arguments");
        AcsObject methods = node(52); methods.put("v", variable()); methods.put("entries", array()); boundary(methods, "empty method entries");
        methods.put("entries", array(method())); boundary(methods, "method entry empty contexts");
        methods.put("entries", array(method(fixed(0, 0)))); boundary(methods, "method entry fixed context");
        assertEquals(1, ExecShape.height(new AcsIntegerElement(1)));
        assertEquals(0, ExecShape.height(variable()));
        assertEquals(1, ExecShape.height(array()));
        assertEquals(3, ExecShape.height(methodsWith(method())));
        assertEquals(4, ExecShape.height(methodsWith(method(reference()))));
        assertEquals(5, ExecShape.height(methodsWith(method(fixed(0, 0)))));
    }

    @Test void scalarEnvelopesAndStorageAddressCallsAgreeWithTheCodec() {
        for (AcsElement scalar : List.of(new AcsStringElement("shape"), new AcsBooleanElement(true),
                new AcsAddress(-1L), new AcsFloat(1.5f), new AcsDouble(2.5),
                new AcsByteArray(new byte[]{(byte) 0xff})))
            boundary(scalar, "scalar " + scalar.getClass().getSimpleName());
        AcsObject addressRead = new AcsObject(); addressRead.put("t", 1); addressRead.put("id", 0x0abd0006);
        addressRead.put("param", array(new AcsAddress(0)));
        AcsObject assignment = node(6); assignment.put("v1", addressRead); assignment.put("v2", 1);
        boundary(assignment, "mem_get storage address");
        assertEquals(ExecShape.Role.STORAGE_TARGET, ExecShape.children(assignment).get(0).role());
        AcsObject drop = node(35); drop.put("v", addressRead); boundary(drop, "mem_get drop target");
    }
    private static AcsObject methodsWith(AcsObject method) {
        AcsObject result = node(52); result.put("v", variable()); result.put("entries", array(method)); return result;
    }

    @Test void contextListEdgesAndElementEdgesHaveDifferentDepthCosts() {
        AcsObject context = fixed(0, 0);
        for (int i = 0; i < 9; i++) {
            AcsObject buffer = fixed(8, 4); buffer.put("element", context); context = buffer;
        }
        AcsObject operation = node(40); operation.put("context", context);
        boundary(operation, "nested buffer element contexts");
        context = reference();
        for (int i = 0; i < 9; i++) {
            AcsObject factory = fixed(8, 3); factory.put("factory", 0x22220004); factory.put("contexts", array(context)); context = factory;
        }
        operation = node(37); operation.put("context", context); boundary(operation, "factory context list edges");
        AcsObject placement = fixed(8, 3); placement.put("placement", 0x22220005); placement.put("placement-contexts", array(reference()));
        operation = node(37); operation.put("context", placement); boundary(operation, "placement context list edge");
    }

    @Test void rolesSeparateStorageLazyControlAndMetadataChildren() {
        List<ExecShape.Child> assignment = ExecShape.children(specimen(6));
        assertEquals(ExecShape.Role.STORAGE_TARGET, assignment.get(0).role());
        assertEquals("v1", assignment.get(0).field()); assertEquals(-1, assignment.get(0).index());
        assertEquals(ExecShape.Role.LAZY_VALUE, ExecShape.children(specimen(23)).get(1).role());
        assertEquals(ExecShape.Role.LAZY_VALUE, ExecShape.children(specimen(24)).get(1).role());
        assertEquals(List.of(ExecShape.Role.EAGER_VALUE, ExecShape.Role.LAZY_VALUE, ExecShape.Role.LAZY_VALUE),
                ExecShape.children(specimen(50)).stream().map(ExecShape.Child::role).toList());
        assertEquals(ExecShape.Role.LOOP_CONDITION, ExecShape.children(specimen(28)).get(0).role());
        ExecShape.Child argument = ExecShape.children(specimen(2)).get(0);
        assertEquals("param", argument.field()); assertEquals(0, argument.index()); assertEquals(2, argument.relativeLevel());
        ExecShape.Child entry = ExecShape.children(specimen(52)).get(1);
        assertEquals(ExecShape.Role.METHOD_ENTRY, entry.role()); assertEquals(2, entry.relativeLevel());
        assertEquals(ExecShape.Role.CONTEXT, ExecShape.children(specimen(51)).get(1).role());
        ExecShape.Child statement = ExecShape.children(array(variable())).get(0);
        assertNull(statement.field()); assertEquals(0, statement.index()); assertEquals(2, statement.relativeLevel());
    }

    @Test void unknownRolesCyclesAndUnexpandedSequencesFailClosedWithoutRecursiveMeasurement() {
        for (int opcode : List.of(-2, 0, 1, 2, 54, Integer.MAX_VALUE))
            assertThrows(IllegalArgumentException.class, () -> ExecShape.height(node(opcode)));
        AcsObject sequence = node(ExecShape.INTERNAL_SEQUENCE); sequence.put("statements", array(specimen(4))); sequence.put("v", variable());
        assertEquals(2, ExecShape.children(sequence).size());
        assertThrows(IllegalArgumentException.class, () -> ExecShape.height(sequence));
        assertThrows(IllegalArgumentException.class, () -> ExecShape.requireFits(unary(sequence), 4, 128));
        AcsObject brokenContext = fixed(0, 0); brokenContext.put("unmodeled-child", array(variable()));
        AcsObject operation = node(36); operation.put("context", brokenContext);
        assertThrows(IllegalArgumentException.class, () -> ExecShape.height(operation));
        AcsArray cycle = array(); cycle.acsa.add(cycle);
        assertThrows(IllegalArgumentException.class, () -> ExecShape.height(cycle));
        AcsElement deep = pad(new AcsIntegerElement(1), 10_000);
        assertEquals(10_001, ExecShape.height(deep));
        assertThrows(IllegalArgumentException.class, () -> ExecShape.requireFits(deep, 4, 128));
    }
}
