package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** Recordings, decoded once to stereo floats at the engine rate (Sfx.SR) and cached by name. Where the audio comes
 *  from is the host's business: the authoring GUI installs a loader that reads samples/ (ffmpeg for anything but
 *  PCM), the mod one that reads its resources. A failed load caches as silence. */
public final class Samples {
    private Samples() {}
    /** Decodes a named recording to {left, right} floats at Sfx.SR. */
    public interface Loader { float[][] load(String name) throws Exception; }
    public static volatile Loader loader = name -> { throw new IOException("no sample loader installed"); };
    public static final java.util.concurrent.ConcurrentHashMap<String, float[][]> SAMPLES = new java.util.concurrent.ConcurrentHashMap<>();

    public static float[][] sample(String name) {
        return SAMPLES.computeIfAbsent(name, nm -> {
            try { return loader.load(nm); }
            catch (Exception e) {
                System.err.println("sample load failed: " + nm + " — " + e);
                return new float[2][1];
            }
        });
    }
}
