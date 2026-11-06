package android.view;
// compile-only stub of the API 29+ public SurfaceControl surface (android.jar here is API 23)
public final class SurfaceControl {
    public static class Builder {
        public Builder() { }
        public Builder setName(String n) { return this; }
        public Builder setBufferSize(int w, int h) { return this; }
        public Builder setFormat(int f) { return this; }
        public SurfaceControl build() { return null; }
    }
    public static class Transaction {
        public Transaction setLayer(SurfaceControl s, int z) { return this; }
        public Transaction show(SurfaceControl s) { return this; }
        public Transaction hide(SurfaceControl s) { return this; }
        public void apply() { }
    }
    public void release() { }
}
