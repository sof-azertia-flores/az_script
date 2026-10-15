package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VirtualCodecTest {
    private static AcsObject reference(int index) {
        AcsObject result = new AcsObject(); result.put("ref", index); return result;
    }
    private static AcsObject entry(int id, AcsObject... contexts) {
        AcsObject result = new AcsObject(); result.put("id", id);
        AcsArray values = new AcsArray(); values.acsa.addAll(List.of(contexts)); result.put("contexts", values); return result;
    }
    private static AcsObject setMethods(AcsObject... entries) {
        AcsObject result = new AcsObject(); result.put("t", 0); result.put("c", ExecOpcodes.SET_METHODS);
        result.put("v", new AcsAddress(0L)); AcsArray values = new AcsArray(); values.acsa.addAll(List.of(entries));
        result.put("entries", values); return result;
    }
    private static AcsObject call(int slot, int type) {
        AcsObject result = new AcsObject(); result.put("t", 0); result.put("c", ExecOpcodes.CALL_VIRTUAL);
        result.put("slot", slot); result.put("type", type);
        AcsArray arguments = new AcsArray(); arguments.acsa.add(new AcsAddress(0L)); result.put("param", arguments); return result;
    }
    private static AcsObject program(AcsElement body) throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute("void main(){}"); AcsObject result = Compiler.compile(parser.toObj());
        AcsObject function = (AcsObject) result.getAsAcsArray("f").acsa.get(0);
        function.put("hidden-count", 1); function.put("script", body); return result;
    }
    private static AbdValue body(AbdValue wire) {
        return wire.getAsAss().values.get(7).getAsAss().values.get(0).getAsAss().values.get(5);
    }
    private static AbdValue replaceBody(AbdValue wire, AbdValue body) {
        var root = wire.getAsAss(); var functions = root.values.get(7).getAsAss(); var function = functions.values.get(0).getAsAss();
        function.values.set(5, body); functions.values.set(0, function.toAbd()); root.values.set(7, functions.toAbd()); return root.toAbd();
    }
    private static AbdValue raw(AbdValue... values) {
        AbdSimpleStack result = new AbdSimpleStack(); result.values.addAll(List.of(values)); return result.toAbd();
    }
    private static void roundTrip(AcsElement node) throws Exception {
        byte[] wire = program(node).toValue().toAbdFormat();
        assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat());
    }

    @Test void methodTablesAndVirtualCallsHaveStableNumbersAndRoundTrip() throws Exception {
        assertEquals(52, ExecOpcodes.code("set_methods")); assertEquals("set_methods", ExecOpcodes.name(52));
        assertEquals(53, ExecOpcodes.code("call_virtual")); assertEquals("call_virtual", ExecOpcodes.name(53));
        roundTrip(setMethods());
        for (int id : List.of(2, 0x0fff0002, Integer.MIN_VALUE, -1)) roundTrip(setMethods(entry(id), entry(id, reference(0))));
        for (int type = 0; type <= 8; type++) roundTrip(call(0, type));
        roundTrip(call(ExecCodec.MAX_SLOTS - 1, 6));
        AcsObject invoked = call(1, 5); invoked.getAsAcsArray("param").acsa.add(new AcsIntegerElement(7)); roundTrip(invoked);
    }

    @Test void inspectionKeysEntryIdsContextsAndCallBoundsAreStrict() throws Exception {
        for (String key : List.of("v", "entries")) {
            AcsObject node = setMethods(); node.mmp.remove(key); AcsObject program = program(node);
            assertThrows(IllegalArgumentException.class, program::toValue, key);
        }
        for (String key : List.of("slot", "type", "param")) {
            AcsObject node = call(0, 0); node.mmp.remove(key); AcsObject program = program(node);
            assertThrows(IllegalArgumentException.class, program::toValue, key);
        }
        for (int id : List.of(0, 1, 0x0abd0000, 0x0abd0014, 0x0abdffff)) {
            AcsObject program = program(setMethods(entry(id)));
            assertThrows(IllegalArgumentException.class, program::toValue);
        }
        for (String key : List.of("id", "contexts")) {
            AcsObject entry = entry(2); entry.mmp.remove(key); AcsObject program = program(setMethods(entry));
            assertThrows(IllegalArgumentException.class, program::toValue, key);
        }
        AcsObject unknown = entry(2); unknown.put("slot", 0); AcsObject extraEntry = program(setMethods(unknown));
        assertThrows(IllegalArgumentException.class, extraEntry::toValue);
        AcsObject invalidReference = program(setMethods(entry(2, reference(1))));
        assertThrows(IllegalArgumentException.class, invalidReference::toValue);
        AcsObject malformedContexts = entry(2); malformedContexts.put("contexts", "wrong"); AcsObject badContexts = program(setMethods(malformedContexts));
        assertThrows(IllegalArgumentException.class, badContexts::toValue);
        for (int slot : List.of(-1, ExecCodec.MAX_SLOTS, Integer.MAX_VALUE)) {
            AcsObject program = program(call(slot, 0)); assertThrows(IllegalArgumentException.class, program::toValue);
        }
        for (int type : List.of(-1, 9, Integer.MAX_VALUE)) {
            AcsObject program = program(call(0, type)); assertThrows(IllegalArgumentException.class, program::toValue);
        }
        AcsObject emptyCall = call(0, 0); emptyCall.getAsAcsArray("param").acsa.clear(); AcsObject absentReceiver = program(emptyCall);
        assertThrows(IllegalArgumentException.class, absentReceiver::toValue);
        AcsObject wrongType = call(0, 0); wrongType.put("type", "any"); AcsObject nonInteger = program(wrongType);
        assertThrows(IllegalArgumentException.class, nonInteger::toValue);
        AcsObject extra = call(0, 0); extra.put("id", 2); AcsObject extraCall = program(extra);
        assertThrows(IllegalArgumentException.class, extraCall::toValue);
    }

    @Test void methodAndArgumentCountsAreBoundedBeforeTheirContentsAreWalked() throws Exception {
        AcsObject setter = setMethods();
        setter.getAsAcsArray("entries").acsa.addAll(Collections.nCopies(ExecCodec.MAX_SLOTS + 1, entry(2)));
        AcsObject table = program(setter); assertThrows(IllegalArgumentException.class, table::toValue);
        AcsObject invoked = call(0, 0);
        invoked.getAsAcsArray("param").acsa.addAll(Collections.nCopies(ExecCodec.MAX_SLOTS, new AcsIntegerElement(1)));
        AcsObject arguments = program(invoked); assertThrows(IllegalArgumentException.class, arguments::toValue);
    }

    @Test void methodTableWireRequiresOneAddressAndExactIdContextEntries() throws Exception {
        AbdValue wire = program(setMethods(entry(-1, reference(0)))).toValue();
        var fields = body(wire).getAsAss().values; assertEquals(3, fields.size());
        assertEquals(52, AbdBasicType.abd2int(fields.get(0)));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, raw(fields.get(0), fields.get(1)))));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, raw(fields.get(0), fields.get(1), fields.get(2), fields.get(2)))));
        var entry = fields.get(2).getAsAss().values.get(0).getAsAss().values; assertEquals(2, entry.size());
        assertEquals(-1, AbdBasicType.abd2int(entry.get(0)));
        for (AbdValue bad : List.of(raw(entry.get(0)), raw(entry.get(0), entry.get(1), entry.get(1)),
                raw(AbdBasicType.int2Abd(0), entry.get(1)), raw(AbdBasicType.int2Abd(0x0abd0000), entry.get(1)),
                raw(new AbdValue(new byte[]{0}), entry.get(1)))) {
            AbdValue record = raw(fields.get(0), fields.get(1), raw(bad));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, record)));
        }
    }

    @Test void virtualCallWireRequiresSlotReturnTypeAndAReceiverList() throws Exception {
        AbdValue wire = program(call(0, 6)).toValue(); var fields = body(wire).getAsAss().values;
        assertEquals(4, fields.size()); assertEquals(53, AbdBasicType.abd2int(fields.get(0)));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, raw(fields.get(0), fields.get(1), fields.get(2)))));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, raw(fields.get(0), fields.get(1), fields.get(2), fields.get(3), fields.get(3)))));
        for (AbdValue bad : List.of(
                raw(fields.get(0), AbdBasicType.int2Abd(-1), fields.get(2), fields.get(3)),
                raw(fields.get(0), AbdBasicType.int2Abd(ExecCodec.MAX_SLOTS), fields.get(2), fields.get(3)),
                raw(fields.get(0), fields.get(1), AbdBasicType.int2Abd(9), fields.get(3)),
                raw(fields.get(0), fields.get(1), new AbdValue(new byte[]{0}), fields.get(3)),
                raw(fields.get(0), fields.get(1), fields.get(2), raw()))) {
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(replaceBody(wire, bad)));
        }
    }
}
