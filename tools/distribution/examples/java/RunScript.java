import azertia.AbdInvoker;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RunScript {
    private RunScript() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: RunScript <distribution-directory> <script.exec.abd>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String nativeDirectory = System.getProperty("os.name").startsWith("Windows")
                ? "bin" : "lib";
        Path library = root.resolve(nativeDirectory).resolve(System.mapLibraryName("abdJ"));
        if (!Files.isRegularFile(library)) {
            throw new IllegalArgumentException("JNI library not found: " + library);
        }
        System.setProperty("azertia.native.library", library.toString());
        try {
            AbdInvoker.loadScript(Path.of(args[1]).toFile());
            AbdInvoker.flush();
            Object result = AbdInvoker.invoke(0x0fff0000);
            System.out.println("Host received: " + result);
        } finally {
            AbdInvoker.close();
        }
    }
}
