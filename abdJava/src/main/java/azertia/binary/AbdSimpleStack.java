package azertia.binary;

import java.util.ArrayList;
import java.util.List;

public class AbdSimpleStack {
    public List<AbdValue> values = new ArrayList<>();
    public AbdValue toAbd() {
        long total = 0;
        for (AbdValue value : values) {
            if (value == null) throw new IllegalArgumentException("null ABD stack member");
            total += (long)value.data.length + 4;
            if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("ABD stack too large");
        }
        // Each member is framed in place: a little-endian length prefix, then its payload.
        byte[] result = new byte[(int)total];
        int offset = 0;
        for (AbdValue value : values) {
            int length = value.data.length;
            result[offset] = (byte)length; result[offset + 1] = (byte)(length >>> 8);
            result[offset + 2] = (byte)(length >>> 16); result[offset + 3] = (byte)(length >>> 24);
            System.arraycopy(value.data, 0, result, offset + 4, length);
            offset += length + 4;
        }
        return AbdValue.wrap(result);
    }
}
