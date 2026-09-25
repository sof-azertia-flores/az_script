package azertia;

import java.io.File;

/** Minimal host example: Main path/to/script.exec.abd [function-id]. */
public final class Main {
    public static void main(String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Usage: Main <script.exec.abd> [function-id]");
        try {
            AbdInvoker.loadScript(new File(args[0]));
            if (args.length > 1) System.out.println(AbdInvoker.invoke(Integer.decode(args[1])));
        } finally { AbdInvoker.close(); }
    }
}
