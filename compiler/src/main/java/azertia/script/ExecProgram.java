package azertia.script;

import azertia.binary.AbdValue;
import azertia.binary.complexBinary.AcsObject;

/** A root executable. The inherited map is an inspection view, not its binary representation. */
public final class ExecProgram extends AcsObject {
    @Override public AbdValue toValue() { return ExecCodec.encode(this); }
    @Override public AbdValue typeValue() {
        throw new IllegalArgumentException("ExecProgram is a root-only executable, not a typed ABD map value");
    }
}
