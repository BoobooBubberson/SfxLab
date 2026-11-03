package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** Recordings, decoded once to stereo floats at the engine rate (Sfx.SR) and cached by name. Where the audio comes
 *  from is the host's business: the authoring GUI installs a loader that reads samples/ (ffmpeg for anything but
 *  PCM), the mod one that reads its resources. A failed load caches as silence. Each channel's mean is taken out on
 *  the way in (2026-11-03): a recording with a DC offset (resonant/singing-bowl_00002_pad.ogg had 14 % of its RMS as
 *  DC) is summed coherently by a choir's voices, where the audio is not, and the offset then eats the output stage's
 *  headroom one-sided; the engine's high-pass would take it too, but only after charging on the step at the start. */
public final class Samples {
    private Samples() {}
    /** Decodes a named recording to {left, right} floats at Sfx.SR. A mono recording arrives on both channels at 1/√2
     *  (a constant-power centre pan, what the lab's ffmpeg upmix has always given): every family was balanced that way,
     *  so a host decoding mono itself must scale it the same, or those layers play 3 dB hot. */
    public interface Loader { float[][] load(String name) throws Exception; }
    public static volatile Loader loader = name -> { throw new IOException("no sample loader installed"); };
    public static final java.util.concurrent.ConcurrentHashMap<String, float[][]> SAMPLES = new java.util.concurrent.ConcurrentHashMap<>();

    public static float[][] sample(String name) {
        return SAMPLES.computeIfAbsent(name, nm -> {
            try {
                float[][] s = loader.load(nm);
                for (float[] ch : s) {
                    double m = 0; for (float x : ch) m += x; m /= Math.max(1, ch.length);
                    if (Math.abs(m) > 1e-5) for (int i = 0; i < ch.length; i++) ch[i] -= (float) m;
                }
                return s;
            }
            catch (Exception e) {
                System.err.println("sample load failed: " + nm + " — " + e);
                return new float[2][1];
            }
        });
    }
}
