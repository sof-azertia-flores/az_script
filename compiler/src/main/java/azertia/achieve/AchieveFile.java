package azertia.achieve;

import azertia.binary.structures.AsColum;
import azertia.binary.structures.AsStructure;
import java.math.BigInteger;
import java.util.Arrays;

@AsStructure
public class AchieveFile {
    @AsColum private BigInteger data;
    @AsColum private boolean isEmpty;
    public AchieveFile() {}
    public AchieveFile(byte[] bytes) {
        isEmpty = bytes.length == 0;
        // A positive sentinel preserves empty files and every leading 00/ff byte through BigInteger.
        byte[] framed = new byte[bytes.length + 1]; framed[0] = 1;
        System.arraycopy(bytes, 0, framed, 1, bytes.length);
        data = new BigInteger(framed);
    }
    public byte[] getBytes() {
        if (data == null) throw new IllegalArgumentException("Archive entry has no data");
        byte[] framed = data.toByteArray();
        if (framed.length == 0 || framed[0] != 1 || (isEmpty && framed.length != 1))
            throw new IllegalArgumentException("Invalid archive entry byte framing");
        return Arrays.copyOfRange(framed, 1, framed.length);
    }
    byte[] getLegacyBytes() {
        if (isEmpty) return new byte[0];
        if (data == null) throw new IllegalArgumentException("Archive entry has no data");
        return data.toByteArray();
    }
}
