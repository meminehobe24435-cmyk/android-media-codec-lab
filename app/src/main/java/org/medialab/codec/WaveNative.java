package org.medialab.codec;
final class WaveNative {
    static { System.loadLibrary("media_wave"); }
    private WaveNative() {}
    static native int[] parse(byte[] wav);
}
