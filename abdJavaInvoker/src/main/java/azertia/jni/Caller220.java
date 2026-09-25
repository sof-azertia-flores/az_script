package azertia.jni;

public class Caller220 {
    public native static void ACputMem(int pointer, int x);
    public native static void ACputMem(int pointer, float x);
    public native static void ACputMem(int pointer, double x);
    public native static void ACputMem(int pointer, boolean x);
    public native static void ACputMem(int pointer, String x);
    public native static void ACputMemNull(int pointer);
}
