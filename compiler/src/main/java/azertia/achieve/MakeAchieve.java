package azertia.achieve;

import azertia.binary.AbdValue;
import azertia.binary.structures.AsStructIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class MakeAchieve {
    static Map<String,AchieveFile> getFiles(File file, String parent) throws IOException {
        Path source = file.toPath();
        if (Files.isSymbolicLink(source)) throw new IOException("Symbolic links are not supported in archives: " + source);
        if (!Files.exists(source)) throw new FileNotFoundException(source.toString());
        String name = parent + file.getName(); Map<String,AchieveFile> files = new LinkedHashMap<>();
        if (Files.isDirectory(source)) {
            try (var children = Files.list(source)) {
                for (Path child : children.sorted().toList()) files.putAll(getFiles(child.toFile(), name + "/"));
            }
        } else if (Files.isRegularFile(source)) files.put(name, new AchieveFile(Files.readAllBytes(source)));
        else throw new IOException("Unsupported archive input: " + source);
        return files;
    }
    /** An entry path must not also be a directory prefix of another entry. */
    static void rejectPathConflicts(Set<String> names) throws IOException {
        for (String name : names) {
            int slash = name.lastIndexOf('/');
            while (slash > 0) {
                String directory = name.substring(0, slash);
                if (names.contains(directory))
                    throw new IOException("Archive path " + directory + " is both a file and a directory of " + name);
                slash = directory.lastIndexOf('/');
            }
        }
    }
    public static void execute(String[] inputs, String target) throws IOException {
        AchievePack pack = new AchievePack(); Path output = Path.of(target).toAbsolutePath().normalize();
        for (String input : inputs) {
            Path source = Path.of(input).toRealPath();
            if (output.equals(source) || (Files.isDirectory(source) && output.startsWith(source)))
                throw new IOException("Archive output cannot be one of its inputs: " + target);
            for (var entry : getFiles(source.toFile(), "").entrySet())
                if (pack.getFiles().putIfAbsent(entry.getKey(), entry.getValue()) != null)
                    throw new IOException("Duplicate archive path: " + entry.getKey());
        }
        rejectPathConflicts(pack.getFiles().keySet());
        pack.getMetadata().put("format", "2");
        Files.createDirectories(output.getParent());
        Files.write(output, AsStructIO.getAbdStructure(pack, false).toAbdFormat(), StandardOpenOption.CREATE_NEW);
    }
    public static void extract(String archive, String destination) throws IOException {
        AchievePack pack = (AchievePack) AsStructIO.readAbdStructure(
                AbdValue.fromAbd(Files.readAllBytes(Path.of(archive))), AchievePack.class, false);
        Path requestedRoot = Path.of(destination).toAbsolutePath().normalize();
        Path ancestor = requestedRoot;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        Path root = ancestor.toRealPath().resolve(ancestor.relativize(requestedRoot)).normalize();
        // Check every path before writing the first file, including pre-existing symlink
        // parents, parents that already exist as files, and file/directory conflicts
        // between entries, so a rejected archive never leaves a partial extraction.
        rejectPathConflicts(pack.getFiles().keySet());
        List<Map.Entry<Path,byte[]>> entries = new ArrayList<>();
        for (var entry : pack.getFiles().entrySet()) {
            if (entry.getValue() == null) throw new IOException("Archive entry has no data: " + entry.getKey());
            Path relative = Path.of(entry.getKey());
            if (relative.isAbsolute() || entry.getKey().contains("\\") || relative.getNameCount() == 0)
                throw new IOException("Unsafe archive path: " + entry.getKey());
            for (Path segment : relative) if (segment.toString().equals(".."))
                throw new IOException("Unsafe archive path: " + entry.getKey());
            Path output = root.resolve(relative).normalize();
            if (!output.startsWith(root) || output.equals(root)) throw new IOException("Unsafe archive path: " + entry.getKey());
            for (Path parent = output; parent != null && parent.startsWith(root); parent = parent.getParent()) {
                if (Files.isSymbolicLink(parent)) throw new IOException("Archive destination traverses a symbolic link: " + parent);
                if (parent != output && Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Archive destination parent is not a directory: " + parent);
            }
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive extraction would overwrite: " + output);
            entries.add(Map.entry(output, "2".equals(pack.getMetadata().get("format"))
                    ? entry.getValue().getBytes() : entry.getValue().getLegacyBytes()));
        }
        for (var entry : entries) {
            Files.createDirectories(entry.getKey().getParent());
            Files.write(entry.getKey(), entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
    }
}
