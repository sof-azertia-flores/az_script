package azertia.script;

import azertia.binary.complexBinary.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrivateFunctionCodecTest {
    private static AcsObject program() throws Exception {
        var parser = new GeneraterJson.AzScript(); parser.execute("int helper(){return 1;}void main(){helper();}");
        return Compiler.compile(parser.toObj());
    }
    private static AcsObject function(AcsObject program, int index) {
        return (AcsObject) program.getAsAcsArray("f").acsa.get(index);
    }
    private static int indexOf(AcsObject program, int id) {
        var functions = program.getAsAcsArray("f").acsa;
        for (int i = 0; i < functions.size(); i++) if (((AcsObject) functions.get(i)).getAsInt("id") == id) return i;
        throw new AssertionError("missing function " + id);
    }

    @Test void definitionsMayBePrivateAndRoundTrip() throws Exception {
        AcsObject program = program();
        AcsObject helper = function(program, indexOf(program, ((AcsObject) program.getAsAcsArray("f").acsa.stream()
                .filter(item -> ((AcsObject) item).getAsInt("id") != 0x0fff0000).findFirst().orElseThrow()).getAsInt("id")));
        helper.put("entry-kind", 2);
        byte[] wire = program.toValue().toAbdFormat();
        assertArrayEquals(wire, ExecCodec.decode(wire).toValue().toAbdFormat());
        helper.put("entry-kind", 3);
        assertThrows(IllegalArgumentException.class, program::toValue);
    }

    @Test void mainAndImportsCannotBePrivate() throws Exception {
        AcsObject program = program();
        function(program, indexOf(program, 0x0fff0000)).put("entry-kind", 2);
        assertThrows(IllegalArgumentException.class, program::toValue);
        AcsObject imported = program();
        AcsObject signature = new AcsObject(); signature.put("id", 0x12340001); signature.put("return-type", 0);
        signature.put("param-types", new AcsArray()); signature.put("entry-kind", 2);
        imported.getAsAcsArray("extern-signatures").acsa.add(signature);
        assertThrows(IllegalArgumentException.class, imported::toValue);
        signature.put("entry-kind", 0);
        assertDoesNotThrow(imported::toValue);
    }
}
