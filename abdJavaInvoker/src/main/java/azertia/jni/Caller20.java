package azertia.jni;

public class Caller20 {
    public native static long memAlloc(int size);
    public native static void memFree(long pointer);
    public native static void destroyScript();
}
