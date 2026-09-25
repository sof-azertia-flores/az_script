package azertia.achieve;

import azertia.binary.structures.AsStructIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class AchieveTest {
    @TempDir Path temporary;
    @Test void binaryFilesRoundTripIncludingEmptyAndRedundantSignBytes() throws Exception {
        Path input = Files.createDirectory(temporary.resolve("input"));
        byte[][] values = {new byte[0], {0}, {0, 0, 1}, {(byte) 0xff, (byte) 0xff, (byte) 0x80}, {0, (byte) 0x80}, {1, 2, 3}};
        for (int i = 0; i < values.length; i++) Files.write(input.resolve(i + ".bin"), values[i]);
        Path archive = temporary.resolve("test.abd"), extracted = temporary.resolve("extracted");
        MakeAchieve.execute(new String[]{input.toString()}, archive.toString());
        MakeAchieve.extract(archive.toString(), extracted.toString());
        for (int i = 0; i < values.length; i++) assertArrayEquals(values[i], Files.readAllBytes(extracted.resolve("input/" + i + ".bin")));
        assertThrows(java.io.IOException.class, () -> MakeAchieve.extract(archive.toString(), extracted.toString()));
    }
    @Test void extractionRejectsTraversalBeforeWritingAnyFile() throws Exception {
        AchievePack pack = new AchievePack(); pack.getMetadata().put("format", "2");
        pack.getFiles().put("../escape.txt", new AchieveFile(new byte[]{1}));
        Path archive = temporary.resolve("bad.abd");
        Files.write(archive, AsStructIO.getAbdStructure(pack, false).toAbdFormat());
        assertThrows(java.io.IOException.class, () -> MakeAchieve.extract(archive.toString(), temporary.resolve("out").toString()));
        assertFalse(Files.exists(temporary.resolve("escape.txt")));
    }
    @Test void fileDirectoryConflictsAreRejectedBeforeAnyWrite() throws Exception {
        AchievePack pack = new AchievePack(); pack.getMetadata().put("format", "2");
        pack.getFiles().put("a", new AchieveFile(new byte[]{1}));
        pack.getFiles().put("a/b", new AchieveFile(new byte[]{2}));
        Path archive = temporary.resolve("conflict.abd");
        Files.write(archive, AsStructIO.getAbdStructure(pack, false).toAbdFormat());
        Path out = temporary.resolve("conflict-out");
        assertThrows(java.io.IOException.class, () -> MakeAchieve.extract(archive.toString(), out.toString()));
        assertFalse(Files.exists(out));
        Path first = Files.createDirectory(temporary.resolve("first")), second = Files.createDirectory(temporary.resolve("second"));
        Files.write(first.resolve("x"), new byte[]{1});
        Files.createDirectories(second.resolve("x")); Files.write(second.resolve("x/y"), new byte[]{2});
        assertThrows(java.io.IOException.class, () -> MakeAchieve.execute(
                new String[]{first.resolve("x").toString(), second.resolve("x").toString()}, temporary.resolve("pack.abd").toString()));
        assertFalse(Files.exists(temporary.resolve("pack.abd")));
        AchievePack empty = new AchievePack(); empty.getMetadata().put("format", "2");
        empty.getFiles().put("missing", null);
        Path missing = temporary.resolve("missing.abd");
        Files.write(missing, AsStructIO.getAbdStructure(empty, false).toAbdFormat());
        assertThrows(java.io.IOException.class, () -> MakeAchieve.extract(missing.toString(), temporary.resolve("missing-out").toString()));
    }
}
