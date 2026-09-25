package azertia.script;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdSimpleStack;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressCodecTest {
    private ExecProgram program() {
        ExecProgram program = new ExecProgram();
        program.put("author", "address codec regression"); program.put("version", 1);
        program.put("exec-version", ExecCodec.VERSION); program.put("gvs", 0);
        program.put("namespace-hint", ""); program.put("assume-hints", new AcsArray());
        AcsObject ext = new AcsObject(); ext.put("address", new AcsAddress(-1L)); program.put("ext", ext);
        AcsArray types = new AcsArray(); types.acsa.add(new AcsIntegerElement(7));
        AcsObject signature = new AcsObject(); signature.put("id", 0x12340002);
        signature.put("return-type", 7); signature.put("param-types", types);
        AcsArray signatures = new AcsArray(); signatures.acsa.add(signature); program.put("extern-signatures", signatures);
        AcsObject function = new AcsObject(); function.put("id", 0x00020002); function.put("return-type", 7);
        function.put("param-count", 1); function.put("local-count", 1); function.put("param-types", types);
        AcsArray body = new AcsArray();
        AcsObject local = new AcsObject(); local.put("t", 0); local.put("c", ExecOpcodes.DEFINE);
        local.put("v", 1); local.put("declared-type", 7); local.put("val", new AcsAddress(0L)); body.acsa.add(local);
        body.acsa.add(new AcsAddress(-1L)); function.put("script", body);
        AcsArray functions = new AcsArray(); functions.acsa.add(function); program.put("f", functions);
        return program;
    }
    private AbdValue raw(AbdValue... values) {
        AbdSimpleStack stack = new AbdSimpleStack();
        java.util.Collections.addAll(stack.values, values); return stack.toAbd();
    }
    private AbdValue body(AbdValue program, AbdValue expression) {
        var root = program.getAsAss(); var functions = root.values.get(7).getAsAss();
        var function = functions.values.get(0).getAsAss(); function.values.set(5, expression);
        functions.values.set(0, function.toAbd()); root.values.set(7, functions.toAbd()); return root.toAbd();
    }

    @Test void addressesKeepTheirDistinctTypeAndAllUnsignedBits() {
        ExecProgram program = program(); AbdValue payload = program.toValue();
        assertEquals(6, AbdBasicType.abd2int(payload.getAsAss().values.get(1)));
        AcsObject decoded = ExecCodec.decode(payload);
        assertEquals(program.toJson(), decoded.toJson());
        assertArrayEquals(payload.toAbdFormat(), decoded.toValue().toAbdFormat());
        assertEquals("18446744073709551615", decoded.getAsAcsObject("ext").getAsAddress("address").toUnsignedString());
        AcsObject function = (AcsObject)decoded.getAsAcsArray("f").acsa.get(0);
        assertEquals(7, function.getAsInt("return-type"));
        assertEquals(7, ((AcsIntegerElement)function.getAsAcsArray("param-types").acsa.get(0)).s);
        AcsObject local = (AcsObject)function.getAsAcsArray("script").acsa.get(0);
        assertEquals(7, local.getAsInt("declared-type"));
        assertEquals(new AcsAddress(0L), local.mmp.get("val"));
        AcsAddress maximum = assertInstanceOf(AcsAddress.class, function.getAsAcsArray("script").acsa.get(1));
        assertEquals(-1L, maximum.rawBits());
        assertArrayEquals(new byte[]{-1,-1,-1,-1,-1,-1,-1,-1}, maximum.toValue().getData());
    }

    @Test void addressesHaveAnExactEightBytePayloadAndRequireVersionSix() {
        AbdValue valid = program().toValue();
        for (int width = 0; width <= 9; ++width) {
            if (width == 8) continue;
            AbdValue scalar = raw(AbdBasicType.int2Abd(ExecOpcodes.CONSTANT),
                    raw(AbdBasicType.int2Abd(AcsAddress.TYPE), new AbdValue(new byte[width])));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(body(valid, scalar)));
            var malformedExtension = valid.getAsAss();
            malformedExtension.values.set(5, raw(AbdBasicType.string2Abd("address"),
                    AbdBasicType.int2Abd(AcsAddress.TYPE), new AbdValue(new byte[width])));
            assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(malformedExtension.toAbd()));
        }
        var old = valid.getAsAss(); old.values.set(1, AbdBasicType.int2Abd(5));
        assertThrows(IllegalArgumentException.class, () -> ExecCodec.decode(old.toAbd()));
    }

    @Test void addressesNeverReplaceIntegerSlotsCountsOrTypeCodes() {
        ExecProgram counts = program(); counts.put("gvs", new AcsAddress(0L));
        assertThrows(IllegalArgumentException.class, counts::toValue);
        ExecProgram slots = program();
        AcsObject function = (AcsObject)slots.getAsAcsArray("f").acsa.get(0);
        ((AcsObject)function.getAsAcsArray("script").acsa.get(0)).put("v", new AcsAddress(1L));
        assertThrows(IllegalArgumentException.class, slots::toValue);
        ExecProgram types = program();
        ((AcsObject)types.getAsAcsArray("f").acsa.get(0)).put("return-type", 8);
        assertThrows(IllegalArgumentException.class, types::toValue);
    }
}
