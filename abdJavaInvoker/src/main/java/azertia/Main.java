package azertia;

import java.io.File;

/** Minimal host example: Main path/to/script.exec.abd [function-id]. */
public final class Main {
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: Main <script.exec.abd> [function-id]");
        Integer functionId = args.length == 2 ? functionId(args[1]) : null;
        try {
            AbdInvoker.loadScript(new File(args[0]));
            AbdInvoker.flush();
            if (functionId != null) System.out.println(AbdInvoker.invoke(functionId));
        } finally { AbdInvoker.close(); }
    }

    private static int functionId(String text) {
        final long value;
        try { value = Long.decode(text); }
        catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid function ID: " + text, error);
        }
        if (value < Integer.MIN_VALUE || value > 0xffffffffL)
            throw new IllegalArgumentException("Function ID is outside the 32-bit range: " + text);
        return (int) value;
    }
}
