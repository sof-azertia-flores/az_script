package azertia;

import java.io.File;

/** Executed in its own JVM with -Xss256k to catch recursive native link walks. */
public final class JniDeepLinkRegression {
    public static void main(String[] args) {
        int count = Integer.parseInt(args[1]);
        try {
            for (int index = 0; index < count; index++) {
                File module = new File(args[0], index + ".exec.abd");
                if (index == 0) AbdInvoker.loadScript(module);
                else AbdInvoker.insertScript(module);
            }
            AbdInvoker.flush();
            AbdInvoker.flush();
            if (AbdInvoker.namespaceForHint("Deep0") != 1
                    || AbdInvoker.namespaceForHint("Deep" + (count - 1)) != count)
                throw new AssertionError("Unexpected namespace assignment");
            System.out.println("JNI deep linking passed: " + count + " modules on a 256 KiB stack");
        } finally { AbdInvoker.close(); }
    }
}
