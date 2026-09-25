package azertia.binary;

import azertia.binary.util.Utils;
import java.util.Arrays;
import java.util.Objects;

/** One ABD payload. Framed data has a 32-bit little-endian length prefix. */
public class AbdValue {
    byte[] data;
    public byte[] getData() { return data.clone(); }
    public AbdValue() { this(new byte[0]); }
    public AbdValue(byte[] dat) { data = Objects.requireNonNull(dat, "ABD payload").clone(); }
    public byte[] toAbdFormat() {
        if (data.length > Integer.MAX_VALUE - 4) throw new IllegalArgumentException("ABD frame too large");
        byte[] frame = new byte[data.length + 4];
        System.arraycopy(Utils.i2b(data.length), 0, frame, 0, 4);
        System.arraycopy(data, 0, frame, 4, data.length);
        return frame;
    }
    /** Decode exactly one frame. Trailing bytes are rejected. */
    public static AbdValue fromAbd(byte[] dat) {
        Objects.requireNonNull(dat, "ABD frame");
        AbdValue value = fromAbd(dat, 0, dat.length);
        if (value.data.length != dat.length - 4) throw new IllegalArgumentException("trailing bytes after ABD frame");
        return value;
    }
    /** Decode the first frame in a bounded region of a larger byte array. */
    public static AbdValue fromAbd(byte[] dat, int offset, int length) {
        Objects.requireNonNull(dat, "ABD frame");
        if (offset < 0 || length < 4 || offset > dat.length || length > dat.length - offset)
            throw new IllegalArgumentException("truncated ABD length prefix or invalid region");
        int size = Utils.b2i(dat, offset);
        if (size < 0 || size > length - 4) throw new IllegalArgumentException("negative or truncated ABD payload");
        return new AbdValue(Arrays.copyOfRange(dat, offset + 4, offset + 4 + size));
    }
    public AbdSimpleStack getAsAss() {
        AbdSimpleStack stack = new AbdSimpleStack();
        int offset = 0;
        while (offset < data.length) {
            AbdValue item = fromAbd(data, offset, data.length - offset);
            stack.values.add(item);
            offset += item.data.length + 4;
        }
        return stack;
    }
}
