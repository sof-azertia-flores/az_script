package azertia.jni;

public class Caller220 {
    public native static void ACputMem(long pointer, int x);
    public native static void ACputMem(long pointer, float x);
    public native static void ACputMem(long pointer, double x);
    public native static void ACputMem(long pointer, boolean x);
    public native static void ACputMem(long pointer, String x);
    public native static void ACputMemAddress(long pointer, long bits);
    public native static void ACputMemNull(long pointer);
}
