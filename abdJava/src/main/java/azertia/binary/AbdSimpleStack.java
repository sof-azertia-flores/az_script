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
        byte[] result = new byte[(int)total];
        int offset = 0;
        for (AbdValue value : values) {
            byte[] frame = value.toAbdFormat();
            System.arraycopy(frame, 0, result, offset, frame.length);
            offset += frame.length;
        }
        return new AbdValue(result);
    }
}
