package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static azertia.script.ExecOpcodes.*;

class ExecV11CodecTest {
    private AcsObject program(boolean debug) throws Exception {return ExecO3Test.compile("int main(){int x=0;return x;}",debug,Set.of());}
    private AcsObject node(int op) {var node=new AcsObject();node.put("t",0);node.put("c",op);return node;}
    private AcsArray array(AcsElement... items) {var array=new AcsArray();array.acsa.addAll(List.of(items));return array;}
    private AcsObject seq(AcsElement statement) {var seq=node(SEQUENCE);seq.put("statements",array(statement));seq.put("value",1);return seq;}
    @Test void sequenceRejectsDeclarationsControlFlowAndNonlocalAssignments() throws Exception {
        for(int op:List.of(DEFINE,RETURN,BREAK,CONTINUE)) {
            var p=program(false);var statement=node(op);
            if(op==DEFINE)statement.put("v",0);
            ExecO3Test.main(p).put("script",array(seq(statement)));
            assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
        }
        var p=program(false);p.put("gvs",1);var global=node(SET);global.put("v",-1);global.put("val",2);
        ExecO3Test.main(p).put("script",array(seq(global)));
        assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
    }
    @Test void newDebugRecordsRoundTripAndValidateInlineChains() throws Exception {
        var p=program(true);var debug=ExecO3Test.main(p).getAsAcsObject("debug");
        debug.put("locations",array(new AcsStringElement("0:0:1:1:0"),new AcsStringElement("1:0:2:1:1")));
        var inlined=new AcsObject();inlined.put("name","leaf");inlined.put("file",0);inlined.put("line",2);inlined.put("column",1);inlined.put("call-site",0);
        debug.put("inlined",array(inlined));
        byte[] bytes=ExecCodec.encode(p).toAbdFormat();assertArrayEquals(bytes,ExecCodec.encode(ExecCodec.decode(bytes)).toAbdFormat());
        inlined.put("call-site",1);assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
        inlined.put("call-site",4);assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
        inlined.put("call-site",0);debug.put("locations",array(new AcsStringElement("0:0:1:1:2")));
        assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
    }
    @Test void privateEntryKindRoundTripsWithoutChangingVersion() throws Exception {
        var p=program(false);var function=ExecO3Test.main(p);function.put("entry-kind",2);
        var decoded=ExecCodec.decode(ExecCodec.encode(p));assertEquals(10,decoded.getAsInt("exec-version"));
        assertEquals(2,ExecO3Test.main(decoded).getAsInt("entry-kind"));
        function.put("entry-kind",3);assertThrows(IllegalArgumentException.class,()->ExecCodec.encode(p));
    }
}
