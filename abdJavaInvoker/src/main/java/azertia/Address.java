package azertia;

/** A distinct unsigned 64-bit AzScript address value, never a numeric integer. */
public final class Address {
    public static final Address NULL = new Address(0);
    private final long bits;

    private Address(long bits) { this.bits = bits; }

    /** Preserve all 64 bits, including values whose signed Java long is negative. */
    public static Address of(long bits) { return bits == 0 ? NULL : new Address(bits); }
    public long bits() { return bits; }
    public boolean isNull() { return bits == 0; }

    @Override public boolean equals(Object other) {
        return other instanceof Address && bits == ((Address) other).bits;
    }
    @Override public int hashCode() { return Long.hashCode(bits); }
    @Override public String toString() { return Long.toUnsignedString(bits); }
}
