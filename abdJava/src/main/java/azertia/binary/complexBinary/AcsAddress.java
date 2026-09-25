package azertia.binary.complexBinary;

import azertia.binary.AbdBasicType;
import azertia.binary.AbdValue;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** An unsigned 64-bit slot address, distinct from every integer ABD scalar. */
public final class AcsAddress implements AcsElement {
    public static final int TYPE = 0xce200b;
    private final long bits;

    /** Constructs an address from all 64 raw bits, including negative Java longs. */
    public AcsAddress(long rawBits) { bits = rawBits; }

    /** Constructs an address from its unsigned decimal spelling. */
    public AcsAddress(String unsignedDecimal) {
        Objects.requireNonNull(unsignedDecimal, "address");
        if (!unsignedDecimal.matches("[0-9]+"))
            throw new IllegalArgumentException("address must be an unsigned decimal integer");
        bits = Long.parseUnsignedLong(unsignedDecimal);
    }

    public AcsAddress(BigInteger value) {
        Objects.requireNonNull(value, "address");
        if (value.signum() < 0 || value.bitLength() > 64)
            throw new IllegalArgumentException("address is outside uint64");
        bits = value.longValue();
    }

    public AcsAddress(AbdValue value) {
        AbdBasicType.requireSize(value, Long.BYTES);
        bits = ByteBuffer.wrap(value.getData()).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    public long rawBits() { return bits; }
    public String toUnsignedString() { return Long.toUnsignedString(bits); }
    public BigInteger toBigInteger() { return new BigInteger(toUnsignedString()); }

    @Override public AbdValue toValue() {
        return new AbdValue(ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(bits).array());
    }
    @Override public AbdValue typeValue() { return AbdBasicType.int2Abd(TYPE); }
    @Override public JsonElement toJson() {
        JsonObject value = new JsonObject();
        value.addProperty("address", toUnsignedString());
        return value;
    }
    @Override public boolean equals(Object other) {
        return other instanceof AcsAddress address && bits == address.bits;
    }
    @Override public int hashCode() { return Long.hashCode(bits); }
    @Override public String toString() { return toUnsignedString(); }
}
