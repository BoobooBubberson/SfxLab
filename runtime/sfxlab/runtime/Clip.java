package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** A clip: a timeline region or a bench layer. Its params follow the type's PSpecs (Sfx). */
public class Clip {
    public String name; public int type, track; public double start, dur; public long seed; public double[] p;
    public String file;   // SAMPLE clips: filename inside samples/
    public boolean vlink; // moves with the video (its own audio track, by default)
    public int keyed;     // which of this clip's params follow the global key (KEY_* bits)
    public Partials pa; public long paFloor = Long.MIN_VALUE, paMin; public int paRetry;   // PARTIALS: analysis cached for the current floor / min len
    // ---- bench (regulator palette) state; null / 0 on ordinary timeline clips
    public String id;                 // stable handle for binds and signature blending
    public int on;                    // ON_NONE = endless layer; ON_LOCK / ON_UNLOCK = one-shot fired by that event
    public boolean lmute;             // layer muted on the bench
    public volatile double[] mod;     // live modulation, added to p by the engine (smoothed); written by the audio thread
    public HashMap<Integer, double[]> range;   // param index -> {lo, hi}: the span that sounded good (authoring notes, default bind range)
    public HashMap<Integer, String> rnote;     // param index -> free note
    public Clip(String name, int type, int track, double start, double dur, long seed) {
        this.name = name; this.type = type; this.track = track;
        this.start = start; this.dur = dur; this.seed = seed;
        keyed = defaultKeyed(type);
        p = new double[nParams(type)];
        for (int i = 0; i < p.length; i++) p[i] = spec(type, i).def();
    }
    public double end() { return start + dur; }
}
