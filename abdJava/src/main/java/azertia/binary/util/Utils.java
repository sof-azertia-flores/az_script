package azertia.binary.util;

public final class Utils {
    private Utils() {}
    public static byte[] i2b(int i) {
        return new byte[]{(byte)i, (byte)(i >>> 8), (byte)(i >>> 16), (byte)(i >>> 24)};
    }
    public static int b2i(byte[] b) { return b2i(b, 0); }
    public static int b2i(byte[] b, int offset) {
        if (b == null || offset < 0 || offset > b.length || b.length - offset < 4)
            throw new IllegalArgumentException("integer requires four bytes");
        return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8)
                | ((b[offset + 2] & 0xff) << 16) | ((b[offset + 3] & 0xff) << 24);
    }
}
