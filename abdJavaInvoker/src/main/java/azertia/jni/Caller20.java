package azertia.jni;

public class Caller20 {
    public native static int memAlloc(int size);
    public native static void memFree(int pointer);
    public native static void destroyScript();
}
