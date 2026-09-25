package azertia.binary.complexBinary;

import azertia.binary.AbdValue;

/** Shared type dispatch and bounded nesting for typed ABD containers. */
final class AcsCodec {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    static Guard enter() {
        int depth = DEPTH.get();
        if (depth >= 128) throw new IllegalArgumentException("ABD nesting exceeds 128 levels or contains a cycle");
        DEPTH.set(depth + 1);
        return new Guard();
    }
    static final class Guard implements AutoCloseable {
        @Override public void close() {
            int depth = DEPTH.get() - 1;
            if (depth == 0) DEPTH.remove(); else DEPTH.set(depth);
        }
    }
    static AcsElement decode(int type, AbdValue value) throws ClassNotFoundException {
        switch (type) {
            case 1: return new AcsStringElement(value);
            case 2: return new AcsObject(value);
            case 3: return new AcsIntegerElement(value);
            case 0xad: return AcsArray.aoa(value);
            case 0x0d00: return new AcsBooleanElement(value);
            case 0xce2009: return new AcsBigInteger(value);
            case 0xce200a: return new AcsByteArray(value.getData());
            case AcsAddress.TYPE: return new AcsAddress(value);
            case 0xface: return new AcsWrappedObject<>(value);
            case 0xce1066: return new AcsDouble(value);
            case 0xce867: return new AcsFloat(value);
            default: throw new IllegalArgumentException("unknown ABD type: " + type);
        }
    }
}
