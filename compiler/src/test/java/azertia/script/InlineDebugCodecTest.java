package azertia.script;

import azertia.binary.complexBinary.*;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class InlineDebugCodecTest {
    private static AcsObject program() throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute("int main(){int a=1;int b=0;return a/b+a;}");
        return Compiler.compile(parser.toObj(), true);
    }
    private static AcsObject debug(AcsObject program) {
        return ((AcsObject) program.getAsAcsArray("f").acsa.get(0)).getAsAcsObject("debug");
    }
    private static AcsObject inlined(int id, String name, int site) {
        AcsObject result = new AcsObject(); result.put("id", id); result.put("name", name); result.put("call-site", site); return result;
    }
    /** Converts the plain record of main into the extended form: every location gets inline index 0. */
    private static AcsObject extended(Consumer<AcsObject> change) throws Exception {
        AcsObject program = program(), debug = debug(program);
        AcsArray locations = debug.getAsAcsArray("locations"), widened = new AcsArray();
        for (AcsElement item : locations.acsa) widened.acsa.add(new AcsStringElement(((AcsStringElement) item).s + ":0"));
        debug.put("locations", widened); debug.put("inlined", new AcsArray()); debug.put("display-id", 0x0fff0000);
        change.accept(debug); return program;
    }
    private static void setInline(AcsObject debug, int location, int index) {
        AcsArray locations = debug.getAsAcsArray("locations"); String text = ((AcsStringElement) locations.acsa.get(location)).s;
        locations.acsa.set(location, new AcsStringElement(text.substring(0, text.lastIndexOf(':') + 1) + index));
    }

    @Test void extendedRecordsRoundTripAndPlainRecordsStayPlain() throws Exception {
        AcsObject plain = program();
        byte[] wire = plain.toValue().toAbdFormat();
        assertFalse(ExecCodec.decode(wire).getAsAcsArray("f").acsa.stream().anyMatch(f -> ((AcsObject) f).getAsAcsObject("debug").mmp.containsKey("inlined")));
        AcsObject program = extended(debug -> {
            assertTrue(debug.getAsAcsArray("locations").acsa.size() >= 2);
            debug.getAsAcsArray("inlined").acsa.add(inlined(0x0fff0005, "outer", 1));
            setInline(debug, 1, 1);
        });
        byte[] extendedWire = program.toValue().toAbdFormat();
        AcsObject decoded = ExecCodec.decode(extendedWire);
        assertEquals(1, debug(decoded).getAsAcsArray("inlined").acsa.size());
        assertArrayEquals(extendedWire, decoded.toValue().toAbdFormat());
    }

    @Test void inlineChainsMustEndAndStayInTheirNamespace() throws Exception {
        for (Consumer<AcsObject> invalid : java.util.List.<Consumer<AcsObject>>of(
                debug -> debug.mmp.remove("display-id"),
                debug -> debug.put("display-id", 0x12340000),
                debug -> debug.getAsAcsArray("inlined").acsa.add(inlined(0x12340005, "outer", 1)),
                debug -> debug.getAsAcsArray("inlined").acsa.add(inlined(0x0fff0005, "outer", 0)),
                debug -> { debug.getAsAcsArray("inlined").acsa.add(inlined(0x0fff0005, "outer", 1)); setInline(debug, 0, 1); },
                debug -> setInline(debug, 0, 1),
                debug -> debug.getAsAcsArray("locations").acsa.set(0, new AcsStringElement("0:0:1:1")))) {
            AcsObject program = extended(invalid);
            assertThrows(IllegalArgumentException.class, program::toValue);
        }
    }
}
