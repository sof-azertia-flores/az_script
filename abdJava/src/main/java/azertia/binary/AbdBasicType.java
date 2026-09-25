package azertia.binary;

import azertia.binary.util.Utils;
import java.nio.charset.StandardCharsets;

public final class AbdBasicType {
    private AbdBasicType() {}
    public static AbdValue int2Abd(int value) { return new AbdValue(Utils.i2b(value)); }
    public static int abd2int(AbdValue value) {
        requireSize(value, 4);
        return Utils.b2i(value.data);
    }
    public static AbdValue string2Abd(String str) { return new AbdValue(str.getBytes(StandardCharsets.UTF_8)); }
    public static String abd2str(AbdValue value) { return new String(value.data, StandardCharsets.UTF_8); }
    public static boolean abd2bol(AbdValue value) {
        requireSize(value, 1);
        if (value.data[0] != 0 && value.data[0] != 1) throw new IllegalArgumentException("ABD boolean must be 0 or 1");
        return value.data[0] == 1;
    }
    public static AbdValue bol2Abd(boolean value) { return new AbdValue(new byte[]{value ? (byte)1 : (byte)0}); }
    public static void requireSize(AbdValue value, int size) {
        if (value == null || value.data.length != size) throw new IllegalArgumentException("invalid ABD scalar payload length");
    }
}
