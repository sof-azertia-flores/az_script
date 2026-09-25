package azertia.achieve;

import azertia.binary.structures.*;
import java.util.HashMap;

@AsStructure
public class AchievePack {
    @AsColum @AsMap(key = String.class, value = AchieveFile.class)
    private HashMap<String,AchieveFile> files = new HashMap<>();
    @AsColum @AsMap(key = String.class, value = String.class)
    private HashMap<String,String> metadata = new HashMap<>();
    public HashMap<String, AchieveFile> getFiles() { return files; }
    public HashMap<String, String> getMetadata() { return metadata; }
}
