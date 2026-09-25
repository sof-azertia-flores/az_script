package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.Objects;

/** Raw bytes have their own tag; BigInteger canonicalization must not alter them. */
public final class AcsByteArray implements AcsElement {
    private final byte[] bytes;
    public AcsByteArray(byte[] value) { bytes = Objects.requireNonNull(value, "bytes").clone(); }
    public AcsByteArray(AbdValue value) { this(value.getData()); }
    public byte[] getBytes() { return bytes.clone(); }
    @Override public AbdValue toValue() { return new AbdValue(bytes); }
    @Override public AbdValue typeValue() { return AbdBasicType.int2Abd(0xce200a); }
    @Override public JsonElement toJson() {
        JsonArray result = new JsonArray();
        for (byte value : bytes) result.add(value & 0xff);
        return result;
    }
}
