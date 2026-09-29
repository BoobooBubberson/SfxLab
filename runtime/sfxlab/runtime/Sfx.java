package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** SfxLab's shared runtime vocabulary: engine constants, wavetables, chord tables, the parameter model, the
 *  harmonic controls, grain helpers, notes, the signal contract and small text helpers. Everything here is
 *  static, Swing-free and file-free; the authoring GUI (SfxLab.java) and the mod both import it. */
public final class Sfx {
    private Sfx() {}

    public static final int SR = 44100, BLOCK = 256;
    public static final int PH_MAX = 12;   // phaser all-pass stage limit (each pair of stages adds a notch)
    public static final int TRACKS = 6, NV = 14;

    // =====================================================================
    // Wavetables (unchanged from SynthLab): 0 gritty gnarl, 1 saw, 2 sine
    // =====================================================================
    public static final int WT = 2048;
    public static final float[][] TABLES = new float[3][WT + 1];
    static {
        Random r = new Random(42);
        for (int i = 0; i < WT; i++) {
            double ph = 2 * Math.PI * i / WT;
            TABLES[2][i] = (float) Math.sin(ph);
            double saw = 0;
            for (int n = 1; n <= 12; n++) saw += Math.sin(n * ph) / n;
            TABLES[1][i] = (float) saw;
        }
        double[] amp = new double[17], off = new double[17];
        for (int n = 1; n <= 16; n++) { amp[n] = (r.nextDouble() * 0.8 + 0.2) / n; off[n] = r.nextDouble() * 2 * Math.PI; }
        for (int i = 0; i < WT; i++) {
            double ph = 2 * Math.PI * i / WT, v = 0;
            for (int n = 1; n <= 16; n++) v += amp[n] * Math.sin(n * ph + off[n]);
            TABLES[0][i] = (float) v;
        }
        for (float[] t : TABLES) {
            float peak = 0;
            for (int i = 0; i < WT; i++) peak = Math.max(peak, Math.abs(t[i]));
            for (int i = 0; i < WT; i++) t[i] /= peak;
            t[WT] = t[0];
        }
    }

    /** Wavetable lookup: phase 0..1, pos in table units 0..2. Linear interp both ways. */
    public static double osc(double phase, double pos) {
        int ti = Math.min((int) pos, TABLES.length - 2);
        double tf = pos - ti;
        double idx = phase * WT;
        int i0 = (int) idx;
        double f = idx - i0;
        double a = TABLES[ti][i0] * (1 - f) + TABLES[ti][i0 + 1] * f;
        double b = TABLES[ti + 1][i0] * (1 - f) + TABLES[ti + 1][i0 + 1] * f;
        return a * (1 - tf) + b * tf;
    }

    // Chord tables: just-intonation ratios per voice slot (root = 1). The
    // cloud tables spread over several octaves; the first three are the
    // originals and new chords are appended so old files keep their index.
    //   unison     no chord, only the per-voice detune — a pure thickener
    //   sus4       1 4/3 3/2: open, medieval, stone-and-bell
    //   minor      1 6/5 3/2
    //   harm 7     1 5/4 3/2 7/4: the natural-overtone seventh — locked-in, organ-like
    //   overtones  1 2 3 4 5 …: one resonating object
    //   major 7    1 5/4 3/2 15/8: shimmery, floating
    //   penta      1 9/8 5/4 3/2 5/3: never wrong at any gather
    //   augment    1 5/4 25/16: uncanny, no resolution
    //   harm 11    1 11/8 13/8: the upper overtones nobody tunes to — alien, not harsh
    //   tritone    1 45/32 2: beats against everything — the corrupt state
    public static final double[][] CHORDS = {
        {0.5, 0.5, 1, 1, 1, 2, 2, 2, 4, 4, 4, 8, 8, 16},                       // octaves
        {0.5, 0.75, 1, 1, 1.5, 2, 2, 3, 3, 4, 4, 6, 8, 12},                    // power
        {0.5, 1, 1, 1.25, 1.5, 2, 2, 2.5, 3, 4, 4, 5, 6, 8},                   // major
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},                            // unison
        {0.5, 1, 1, 4 / 3.0, 1.5, 2, 2, 8 / 3.0, 3, 4, 4, 16 / 3.0, 6, 8},     // sus4
        {0.5, 1, 1, 1.2, 1.5, 2, 2, 2.4, 3, 4, 4, 4.8, 6, 8},                  // minor
        {0.5, 1, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 3.5, 4, 5, 6, 7},              // harm 7
        {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},                       // overtones
        {0.5, 1, 1, 1.25, 1.5, 1.875, 2, 2.5, 3, 3.75, 4, 5, 6, 7.5},          // major 7
        {0.5, 1, 1.125, 1.25, 1.5, 5 / 3.0, 2, 2.25, 2.5, 3, 10 / 3.0, 4, 5, 6},   // penta
        {0.5, 1, 1, 1.25, 1.5625, 2, 2, 2.5, 3.125, 4, 4, 5, 6.25, 8},         // augment
        {0.5, 1, 1, 1.375, 1.625, 2, 2, 2.75, 3.25, 4, 4, 5.5, 6.5, 8},        // harm 11
        {0.5, 1, 1, 1.40625, 2, 2, 2.8125, 4, 4, 5.625, 8, 8, 11.25, 16},      // tritone
    };
    public static final String[] CHORD_NAMES = {"octaves", "power", "major", "unison", "sus4", "minor", "harm 7",
                                         "overtones", "major 7", "penta", "augment", "harm 11", "tritone"};
    public static final int NCHORD = CHORDS.length;
    public static final double[][] CHORD_LOGS = new double[NCHORD][NV];
    static {
        for (int c = 0; c < NCHORD; c++)
            for (int v = 0; v < NV; v++) CHORD_LOGS[c][v] = Math.log(CHORDS[c][v]);
    }
    // choir chord slots: the same chords, but every ratio kept within an
    // octave of the source (a recording shifted further stops sounding like
    // itself). Slot order matters — the first voices are the loudest, so the
    // root leads and the colour tones follow.
    public static final double[][] CHOIR_CHORDS = {
        {1, 1, 2, 0.5, 1, 1, 2, 0.5, 1, 1, 2, 0.5, 1, 1},                                    // octaves
        {1, 1.5, 1, 0.75, 1.5, 2, 1, 1.5, 0.5, 1, 0.75, 1.5, 2, 1},                          // power
        {1, 1.25, 1.5, 1, 0.75, 1.25, 1.5, 2, 1, 0.625, 1.25, 1.5, 0.5, 1},                  // major
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},                                          // unison
        {1, 4 / 3.0, 1.5, 1, 2 / 3.0, 4 / 3.0, 1.5, 2, 1, 0.75, 4 / 3.0, 1.5, 0.5, 1},        // sus4
        {1, 1.2, 1.5, 1, 0.75, 1.2, 1.5, 2, 1, 0.6, 1.2, 1.5, 0.5, 1},                       // minor
        {1, 1.5, 1.25, 1.75, 1, 0.75, 1.5, 1.25, 2, 0.875, 1.75, 1, 0.5, 1.5},               // harm 7
        {1, 1.5, 1.25, 1.75, 1.125, 1.375, 1.625, 2, 1, 1.5, 0.5, 1.25, 0.75, 1},            // overtones (folded)
        {1, 1.25, 1.5, 1.875, 1, 0.75, 1.25, 1.5, 0.9375, 2, 1, 0.625, 1.5, 0.5},            // major 7
        {1, 1.5, 1.25, 1.125, 5 / 3.0, 1, 0.75, 1.5, 1.25, 2, 0.5625, 0.625, 1, 0.5},        // penta
        {1, 1.25, 1.5625, 1, 0.78125, 1.25, 1.5625, 2, 1, 0.625, 1.25, 1.5625, 0.5, 1},      // augment
        {1, 1.375, 1.625, 1, 0.6875, 1.375, 1.625, 2, 1, 0.8125, 1.375, 1.625, 0.5, 1},      // harm 11
        {1, 1.40625, 1, 2, 0.703125, 1.40625, 1, 0.5, 1.40625, 2, 1, 0.703125, 1.40625, 1},  // tritone
    };
    public static final double[][] CHOIR_LOGS = new double[NCHORD][NV];
    static {
        for (int c = 0; c < NCHORD; c++)
            for (int v = 0; v < NV; v++) CHOIR_LOGS[c][v] = Math.log(CHOIR_CHORDS[c][v]);
    }
    public static int chordIdx(double v) { return (int) Math.max(0, Math.min(NCHORD - 1, Math.round(v))); }
    public static final double[] PENTA = {1, 9.0 / 8, 5.0 / 4, 3.0 / 2, 5.0 / 3};
    public static final double W_LO = Math.log(0.35), W_HI = Math.log(12);

    // =====================================================================
    // Parameter model: every clip carries COMMON params + its type's extras.
    // The UI, serialization, and DSP all read from the same specs.
    // =====================================================================
    public record PSpec(String name, double min, double max, double def) {}

    public static final int P_LEVEL = 0, P_ATT = 1, P_REL = 2, P_PITCH = 3, P_PSWP = 4, P_CUT = 5,
                     P_CSWP = 6, P_RES = 7, P_MODE = 8, P_PAN = 9, P_PANSWP = 10, P_ECHO = 11,
                     P_DRIVE = 12, NCOMMON = 13;
    public static final PSpec[] COMMON = {
        new PSpec("level",      0,   1,   0.8),
        new PSpec("attack",     0,   2,   0.02),
        new PSpec("release",    0,   3,   0.25),
        new PSpec("pitch",    -36,  54,   0),
        new PSpec("pitch swp",-60,  60,   0),
        new PSpec("cutoff",     0,   1,   1),
        new PSpec("cutoff swp",-1,   1,   0),
        new PSpec("resonance",  0,   1,   0),
        new PSpec("filter",     0,   2,   0),      // LP / BP / HP
        new PSpec("pan",       -1,   1,   0),
        new PSpec("pan swp",   -2,   2,   0),
        new PSpec("echo",       0,   1,   0),
        new PSpec("drive",      0,   1,   0),   // added post-v2: exists by name only
    };

    public static final int CLOUD = 0, TONE = 1, NOISE = 2, SPARKLE = 3, PLUCK = 4, SAMPLE = 5, CHOIR = 6, PARTIALS = 7;
    // KEY: a global transposition in semitones (GUI: < > keys; --render --key N)
    // applied non-destructively at render time to every clip that opts in.
    // Pitch-keyed clips move their `pitch` (sweeps and LFO ride on top, so a
    // zap still lands on the key); filter-keyed clips move `cutoff` by the same
    // ratio, so a resonant bandpass on a recorded whoosh sings the key. Synth
    // clips default to both, recordings to filter only (their pitch is usually
    // the recording's own). Zero key is bit-exact with the old renderer.
    public static final int KEY_OFF = 0, KEY_PITCH = 1, KEY_FILTER = 2, KEY_BOTH = 3;
    public static final String[] KEY_NAMES = {"off", "pitch", "filter", "pitch+filter"};
    public static final double KEY_U = Math.log(2) / Math.log(250) / 12;   // cutoff units per semitone (fc = 40·250^u)
    public static int defaultKeyed(int type) { return type == SAMPLE || type == CHOIR ? KEY_FILTER : KEY_BOTH; }
    public static final String[] TYPE_NAMES = {"cloud", "tones", "noise", "sparkle", "pluck", "sample", "choir", "partials"};
    // choir extras (offsets from NCOMMON). 13 common + 9 + 14 tail = 36: exactly three slider columns.
    public static final int CH_START = 0, CH_SPEED = 1, CH_VOICES = 2, CH_GATHER = 3, CH_DRIFT = 4, CH_CHORD = 5,
                     CH_WANDER = 6, CH_SCATTER = 7, CH_GSWP = 8;
    // partials extras (offsets from NCOMMON); start/speed sit where choir's do
    public static final int PA_START = 0, PA_SPEED = 1, PA_SINES = 2, PA_RESID = 3, PA_FLOOR = 4, PA_MINLEN = 5, PA_LOOP = 6,
                     PA_ODD = 7, PA_TILT = 8, PA_PURITY = 9, PA_STRETCH = 10, PA_GATHER = 11, PA_CHORD = 12, PA_SHIM = 13,
                     PA_ROOT = 14, PA_HTOL = 15;
    // tones bank extras (offsets from NCOMMON) and its size
    public static final int TB_BANK = 6, TB_ODD = 7, TB_TILT = 8, TB_STRETCH = 9, TB_GATHER = 10, TB_CHORD = 11, TB_SHIM = 12, TB_HARM = 24;
    /** Clips that read a recording: sample and choir. */
    public static boolean sampled(Clip c) { return (c.type == SAMPLE || c.type == CHOIR || c.type == PARTIALS) && c.file != null; }
    public static int startIdx(int type) { return type == CHOIR || type == PARTIALS ? NCOMMON + CH_START : NCOMMON + 1; }
    public static int speedIdx(int type) { return type == CHOIR || type == PARTIALS ? NCOMMON + CH_SPEED : NCOMMON + 3; }
    public static boolean looping(Clip c) { return c.type == CHOIR || c.p[c.type == PARTIALS ? NCOMMON + PA_LOOP : NCOMMON] >= 0.5; }
    public static boolean keepLen(Clip c) { return c.type == CHOIR || c.type == PARTIALS || c.p[NCOMMON + 2] >= 0.5; }
    // Every clip ends with this shared tail block (appended after the type
    // extras so older project files still parse positionally — missing values
    // get defaults). Access it as the LAST N_TAIL params of any clip; new
    // shared params get APPENDED here to keep old files loading.
    public static final PSpec[] TAIL_SPECS = {
        new PSpec("lfo rate", 0.1, 20, 4),
        new PSpec("lfo>pitch", 0, 12, 0),
        new PSpec("lfo>cut", 0, 0.5, 0),
        new PSpec("lfo>amp", 0, 1, 0),
        new PSpec("lfo shape", 0, 3, 0),   // sine, tri, square, random S&H
        new PSpec("env curve", -1, 1, 0),  // linear .. percussive / swelling
        new PSpec("reverb", 0, 1, 0),      // send into the shared room
        new PSpec("flange", 0, 1, 0),      // swept-comb mix; sweep rides the clip LFO
        new PSpec("flange fb", -0.95, 0.95, 0.5),
        new PSpec("duck", 0, 1, 0),           // sidechain depth: level dips when `duck from` is loud
        new PSpec("duck from", 0, TRACKS, 0), // key track (1-based); 0 = off
        new PSpec("phaser", 0, 1, 0),         // wet mix of the swept all-pass chain
        new PSpec("phaser rate", 0.05, 8, 0.4),
        new PSpec("ph stages", 1, PH_MAX, 4),   // more stages = more notches, thicker swirl
        new PSpec("ph pos", -1, 1, -1),         // < 0: the LFO sweeps; 0..1: the notch is parked / steered here (bind a signal: the orb steers the sweep)
        new PSpec("lfo pos", -1, 1, -1),        // < 0: the LFO runs at its rate; 0..1: its phase in cycles is parked / steered here
        new PSpec("flange pos", -1, 1, -1),     // < 0: the comb rides the LFO; 0..1: its delay is parked / steered here (1.2 .. 5 ms)
    };
    public static final int N_TAIL = TAIL_SPECS.length;
    /** Index of a tail param for a given type (j = offset within TAIL_SPECS). */
    public static int tailIdx(int type, int j) { return nParams(type) - N_TAIL + j; }
    public static PSpec[] withLfo(PSpec... a) {
        PSpec[] r = Arrays.copyOf(a, a.length + N_TAIL);
        System.arraycopy(TAIL_SPECS, 0, r, a.length, N_TAIL);
        return r;
    }
    public static final PSpec[][] EXTRAS = {
        // gather swp is appended last so older project files still parse positionally
        withLfo(new PSpec("voices", 1, 14, 10), new PSpec("gather", 0, 1, 0.85), new PSpec("drift", 0, 1, 0.25),
                new PSpec("timbre", 0, 2, 0.6), new PSpec("chord", 0, NCHORD - 1, 1), new PSpec("sub", 0, 1, 0.3),
                new PSpec("gather swp", -1, 1, 0)),
        withLfo(new PSpec("interval", -24, 24, 12), new PSpec("timbre", 0, 2, 2),
                new PSpec("detune", 0, 1, 0),       new PSpec("shimmer", 0, 1, 0),
                new PSpec("fm ratio", 0.25, 8, 2),  new PSpec("fm depth", 0, 8, 0),
                // additive harmonic bank (TB_HARM harmonics of the base pitch) with the
                // same harmonic controls as a partials clip; `bank` 0 = off (old sound)
                new PSpec("bank", 0, 1, 0), new PSpec("odd/even", -1, 1, 0), new PSpec("tilt", -1, 1, 0),
                new PSpec("stretch", -0.3, 0.3, 0), new PSpec("gather", 0, 1, 0), new PSpec("chord", 0, NCHORD - 1, 1),
                new PSpec("bank shimmer", 0, 1, 0)),
        withLfo(new PSpec("color", 0, 1, 0)),
        withLfo(new PSpec("density", 2, 40, 14), new PSpec("ping decay", 0.02, 0.4, 0.09),
                new PSpec("spread", 0, 1, 0.8),  new PSpec("range", 0, 2, 1),
                new PSpec("fm ratio", 0.25, 8, 2), new PSpec("fm depth", 0, 8, 0)),
        withLfo(new PSpec("damp", 0, 1, 0.5), new PSpec("sustain", 0, 1, 0.7),
                new PSpec("pick", 0, 1, 0.8), new PSpec("interval", -12, 12, 0)),
        withLfo(new PSpec("loop", 0, 1, 0), new PSpec("start", 0, 1, 0),
                new PSpec("pitch mode", 0, 1, 0),   // 0 tape (pitch = speed), 1 keep len (granular)
                new PSpec("speed", 0, 3, 1)),        // keep len: time-stretch (0 freezes); tape: extra rate
        withLfo(new PSpec("start", 0, 1, 0), new PSpec("speed", 0, 3, 1),
                new PSpec("voices", 1, 14, 8), new PSpec("gather", 0, 1, 0.85), new PSpec("drift", 0, 1, 0.2),
                new PSpec("chord", 0, NCHORD - 1, 1), new PSpec("wander", 0, 24, 7),   // ± semitones the ungathered voices roam
                new PSpec("scatter", 0, 1, 0.3),                              // read-position spread, as a fraction of the recording
                new PSpec("gather swp", -1, 1, 0)),
        withLfo(new PSpec("start", 0, 1, 0), new PSpec("speed", 0, 3, 1),   // keep-len time (0 freezes: partials hold, residual grain-freezes)
                new PSpec("sines", 0, 2, 1), new PSpec("residual", 0, 2, 1),   // the two halves of the model, mixed separately
                new PSpec("floor", 6, 42, 14),      // dB a peak must rise above the frame's median to count as a partial
                new PSpec("min len", 20, 200, 46),  // ms a partial must persist; shorter = noise, stays in the residual
                new PSpec("loop", 0, 1, 0),
                // harmonic controls (shared with the tones bank, see harmGain / harmFreq)
                new PSpec("odd/even", -1, 1, 0),     // <0 fades the even harmonics (hollow), >0 fades the odd ones above the fundamental
                new PSpec("tilt", -1, 1, 0),         // gain ∝ ratio^tilt: dark .. bright
                new PSpec("purity", 0, 2, 1),        // gain of the partials that are NOT on the harmonic series: 0 clean, 2 alien
                new PSpec("stretch", -0.3, 0.3, 0),  // harmonic h lands at h^(1+stretch): bells / metal
                new PSpec("gather", 0, 1, 0),        // pull every partial toward the nearest tone of `chord` (any octave)
                new PSpec("chord", 0, NCHORD - 1, 1),
                new PSpec("shimmer", 0, 1, 0),       // slow random detune per partial, up to ~±20 cents
                new PSpec("root shift", -36, 36, 0), // semitones added to the estimated fundamental before classifying (fix octave errors)
                new PSpec("harm tol", 1, 10, 3)),    // % a partial may miss an integer ratio and still count as a harmonic
    };
    public static int nParams(int type) { return NCOMMON + EXTRAS[type].length; }
    public static PSpec spec(int type, int i) { return i < NCOMMON ? COMMON[i] : EXTRAS[type][i - NCOMMON]; }
    public static String key(int type, int i) { return spec(type, i).name().replace(' ', '_'); }
    public static int idxOf(int type, String key) {
        for (int i = 0; i < nParams(type); i++) if (key(type, i).equals(key)) return i;
        return -1;
    }

    // Frozen positional layout of pre-v2 project files (v2 saves key=value
    // pairs instead). New params never go in here — they only exist by name.
    public static final String[] LEGACY_COMMON = {"level", "attack", "release", "pitch", "pitch_swp", "cutoff",
                                           "cutoff_swp", "resonance", "filter", "pan", "pan_swp", "echo"};
    public static final String[][] LEGACY_EXTRA = {
        {"voices", "gather", "drift", "timbre", "chord", "sub", "gather_swp"},
        {"interval", "timbre", "detune", "shimmer"},
        {},
        {"density", "ping_decay", "spread", "range"},
        {},   // pluck is post-v2: named params only
        {},   // sample too
        {},   // choir too
        {},   // partials too
    };
    public static final String[] LEGACY_TAIL = {"lfo_rate", "lfo>pitch", "lfo>cut", "lfo>amp", "lfo_shape", "env_curve"};
    public static String legacyName(int type, int i) {
        if (i < LEGACY_COMMON.length) return LEGACY_COMMON[i];
        i -= LEGACY_COMMON.length;
        if (i < LEGACY_EXTRA[type].length) return LEGACY_EXTRA[type][i];
        i -= LEGACY_EXTRA[type].length;
        return i < LEGACY_TAIL.length ? LEGACY_TAIL[i] : null;
    }
    public static boolean discrete(int type, int i) {
        String n = spec(type, i).name();
        return n.equals("filter") || n.equals("chord") || n.equals("voices") || n.equals("range")
            || n.equals("lfo shape") || n.equals("loop") || n.equals("pitch mode") || n.equals("duck from")
            || n.equals("ph stages") || n.equals("floor") || n.equals("min len") || n.equals("root shift");
    }

    public static final double MOD_K = 1 - Math.exp(-1.0 / (0.03 * SR));   // live-param smoother: ~30 ms
    public static final double FADE_S = 0.02;                                // master fade around start / pause / seek (s)
    public static final boolean[][] QUANT = new boolean[EXTRAS.length][];
    /** Which params of a type are expensive to move continuously (they rebuild per-partial caches). */
    public static boolean[] quantised(int type) {
        boolean[] q = QUANT[type];
        if (q == null) {
            q = new boolean[nParams(type)];
            for (int i = 0; i < q.length; i++) {
                String n = spec(type, i).name();
                q[i] = n.equals("odd/even") || n.equals("tilt") || n.equals("purity") || n.equals("stretch")
                    || n.equals("gather") && type != CLOUD && type != CHOIR || n.equals("root shift") || n.equals("harm tol");
            }
            QUANT[type] = q;
        }
        return q;
    }
    public static double wrap01(double x) { x = x % 1; return x < 0 ? x + 1 : x; }
    public static final int KSN = 4096;   // string delay-line size; floors pitch at ~11 Hz
    public static final int FLN = 256;    // flanger delay-line size (max ~5.8 ms)

    // ---- harmonic controls, shared by partials clips (per tracked partial) and
    // the tones bank (per synthesized harmonic). r = frequency / fundamental.
    public static final double LN2 = Math.log(2);
    /** Harmonic number of ratio r (within 3 %), 0 = not on the series. */
    public static int harmNum(double r) { return harmNum(r, 0.03); }
    public static int harmNum(double r, double tol) {
        int h = (int) Math.round(r);
        return h >= 1 && h <= 32 && Math.abs(r / h - 1) < tol ? h : 0;
    }
    /** The root a partials clip classifies against: the estimate shifted by `root shift` (110 Hz stands in when there is no estimate). */
    public static double partialsRoot(Partials pa, double shiftSemis) {
        double base = pa.f0 > 0 ? pa.f0 : 110;
        return shiftSemis == 0 && pa.f0 <= 0 ? 0 : base * Math.pow(2, shiftSemis / 12);
    }
    /** Gain for a partial: tilt brightens or darkens by ratio, odd/even fades one
     *  side of the series above the fundamental, purity scales the inharmonic ones. */
    public static double harmGain(double r, int h, double oddEven, double tilt, double purity) {
        double g = 1;
        if (tilt != 0 && r > 0) g = Math.pow(Math.max(r, 0.25), tilt);
        if (h == 0) g *= purity;
        else if (h >= 2) {
            if (oddEven < 0 && (h & 1) == 0) g *= 1 + oddEven;
            else if (oddEven > 0 && (h & 1) == 1) g *= 1 - oddEven;
        }
        return g;
    }
    /** Frequency multiplier: stretch warps the spacing (h -> h^(1+s)), gather pulls
     *  toward the nearest pitch class of the chord, keeping the octave. */
    public static double harmFreq(double r, double stretch, double gather, int chord) {
        if (r <= 0) return 1;
        double m = 1;
        if (stretch != 0) m = Math.pow(r, stretch);
        if (gather > 0) {
            double lr = Math.log(r * m), frac = lr - Math.floor(lr / LN2) * LN2, bestD = 1e9;
            for (double tl : CHORD_LOGS[chord]) {
                double tf = tl - Math.floor(tl / LN2) * LN2;
                for (double cand : new double[]{tf - LN2, tf, tf + LN2}) { double d = cand - frac; if (Math.abs(d) < Math.abs(bestD)) bestD = d; }
            }
            m *= Math.exp(gather * bestD);
        }
        return m;
    }
    /** Deterministic per-partial shimmer LFO rate (Hz) and start phase. */
    public static double hash01(long seed, int k, int salt) { double h = Math.sin(k * 12.9898 + salt * 78.233 + (seed & 4095)) * 43758.5453; return h - Math.floor(h); }

    /** Fundamental estimate from the tracked partials: candidates are the
     *  strongest tracks' median frequencies (and their halves, for a missing
     *  fundamental); the one whose harmonic series collects the most partial
     *  energy wins. Effects rarely have a textbook series, so this lands on the
     *  dominant ring when nothing better exists. 0 when there are no partials. */
    public static double estimateF0(List<PTrack> tracks) {
        int nt = tracks.size();
        if (nt == 0) return 0;
        double[] fm = new double[nt], en = new double[nt];
        Integer[] idx = new Integer[nt];
        for (int k = 0; k < nt; k++) {
            PTrack t = tracks.get(k);
            fm[k] = t.freq[t.len / 2];
            double e = 0;
            for (float a : t.amp) e += a * a;
            en[k] = e; idx[k] = k;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(en[b], en[a]));
        double best = 0, bestScore = -1;
        for (int i = 0; i < Math.min(16, nt); i++)
            for (double cand : new double[]{fm[idx[i]], fm[idx[i]] / 2}) {
                if (cand < PA_FMIN) continue;
                double score = 0;
                for (int k = 0; k < nt; k++) {
                    double r = fm[k] / cand;
                    int h = (int) Math.round(r);
                    if (h >= 1 && h <= 16 && Math.abs(r / h - 1) < 0.03) score += en[k] / Math.sqrt(h);
                }
                if (score > bestScore) { bestScore = score; best = cand; }
            }
        return best;
    }

    // ---- notes: names are concert pitch (A4 = 440, C4 = 60); the project root
    // is where the crystal's degree I sits, and the key is measured from it
    public static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    /** "F#6 -4c" style name for a frequency (no cents when within half a cent). */
    public static String noteName(double hz) {
        if (hz <= 0) return "-";
        double m = 69 + 12 * Math.log(hz / 440) / Math.log(2);
        int r = (int) Math.round(m), cents = (int) Math.round((m - r) * 100);
        String n = NOTE_NAMES[((r % 12) + 12) % 12] + (int) Math.floor(r / 12.0 - 1);
        return cents == 0 ? n : String.format(Locale.ROOT, "%s %+dc", n, cents);
    }
    /** Parses "c2", "F#4", "bb3", "65.4" or "65.4hz" into Hz; NaN if it won't parse. */
    public static double parseNote(String in) {
        String t = in.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-g])([#b]?)(-?\\d)$").matcher(t);
        if (m.matches()) {
            int semi = "c d ef g a b".indexOf(m.group(1));
            if (m.group(2).equals("#")) semi++; else if (m.group(2).equals("b")) semi--;
            int midi = 12 * (Integer.parseInt(m.group(3)) + 1) + semi;
            return 440 * Math.pow(2, (midi - 69) / 12.0);
        }
        try { return Double.parseDouble(t.endsWith("hz") ? t.substring(0, t.length() - 2).trim() : t); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    /** In-place iterative radix-2 FFT (inverse is scaled by 1/n). */
    public static void fft(double[] re, double[] im, boolean inv) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1), wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
    }

    /** True (and records them) when the harmonic params differ from the voice's cache. */
    public static boolean harmChanged(Voice v, double odd, double tilt, double pur, double str, double gat, int chord, double shift, double tol) {
        if (v.cOdd == odd && v.cTilt == tilt && v.cPur == pur && v.cStr == str && v.cGat == gat && v.cChord == chord && v.cShift == shift && v.cTol == tol) return false;
        v.cOdd = odd; v.cTilt = tilt; v.cPur = pur; v.cStr = str; v.cGat = gat; v.cChord = chord; v.cShift = shift; v.cTol = tol;
        return true;
    }
    /** Linear-interpolated sample read. One-shot callers keep pos < n-1;
     *  the wrap on the second tap is for loops. */
    public static double smpAt(float[] a, double pos, int n) {
        int i0 = (int) pos;
        double fr = pos - i0;
        return a[i0] * (1 - fr) + a[(i0 + 1) % n] * fr;
    }

    // ---- keep-len granular sampler: 50 ms Hann grains at 50 % overlap (the
    // windows sum to 1), each new grain WSOLA-aligned to the sounding one
    public static final int GLEN = (int) (0.05 * SR), GHALF = GLEN / 2;
    public static final int GK = 200, GSTEP = 3, GSRCH = SR / 80 / GSTEP * GSTEP;   // 4.5 ms match, ±12.5 ms search (0 is a candidate)
    public static double mono(float[][] s, double pos, int n, boolean loop) {
        int i = (int) Math.floor(pos);
        if (loop) i = ((i % n) + n) % n;
        else if (i < 0 || i >= n) return 0;
        return s[0][i] + s[1][i];
    }
    /** Where should a grain start so that it continues, in phase, what the
     *  other grain is about to play? Nominal start is `src`; slide it by up
     *  to ±GSRCH samples to maximize normalized correlation with the sounding
     *  grain's next GK reads (a small bias prefers the nominal spot). */
    public static double alignGrain(float[][] s, int n, double src, double other, double ratio, boolean loop, double[] ref) {
        if (other < 0) return src;
        double eRef = 1e-9;
        for (int k = 0; k < GK; k++) { ref[k] = mono(s, other + k * ratio, n, loop); eRef += ref[k] * ref[k]; }
        double best = -1e9;
        int bestD = 0;
        for (int d = -GSRCH; d <= GSRCH; d += GSTEP) {   // coarse pass
            double sc = grainScore(s, n, src + d, ratio, loop, ref, eRef) - 0.02 * Math.abs(d) / GSRCH;
            if (sc > best) { best = sc; bestD = d; }
        }
        int coarse = bestD;
        for (int d = coarse - GSTEP + 1; d < coarse + GSTEP; d++) {   // fine pass around it
            if (d == coarse) continue;
            double sc = grainScore(s, n, src + d, ratio, loop, ref, eRef) - 0.02 * Math.abs(d) / GSRCH;
            if (sc > best) { best = sc; bestD = d; }
        }
        return src + bestD;
    }
    public static double grainScore(float[][] s, int n, double p0, double ratio, boolean loop, double[] ref, double eRef) {
        if (!loop && (p0 < 0 || p0 + GK * ratio >= n)) return -1e9;
        double dot = 0, e = 1e-9;
        for (int k = 0; k < GK; k++) {
            double x = mono(s, p0 + k * ratio, n, loop);
            dot += x * ref[k]; e += x * x;
        }
        return dot / Math.sqrt(e * eRef);
    }

    public static double timelineEnd(List<Clip> cs) {
        double e = 1;
        for (Clip c : cs) e = Math.max(e, c.end());
        return e;
    }

    public static final double ROOT_DEFAULT = 65.4064;   // C2: the crystal's degree I in the mod

    public static Clip copyClip(Clip c) {
        Clip n = new Clip(c.name, c.type, c.track, c.start, c.dur, c.seed);
        System.arraycopy(c.p, 0, n.p, 0, c.p.length);
        n.file = c.file; n.vlink = c.vlink; n.keyed = c.keyed;
        n.id = c.id; n.on = c.on; n.lmute = c.lmute;
        if (c.range != null) { n.range = new HashMap<>(); for (var e : c.range.entrySet()) n.range.put(e.getKey(), e.getValue().clone()); }
        if (c.rnote != null) n.rnote = new HashMap<>(c.rnote);
        return n;
    }

    public static final int ON_NONE = 0, ON_LOCK = 1, ON_UNLOCK = 2;
    public static final int ON_ACCEPT = 3;
    public static final String[] ON_NAMES = {"none", "lock", "unlock", "accept"};   // accept: the crystal took a latched motion (the chime)
    public static final double ENDLESS = 1e9;   // dur of a layer that loops for ever

    /** The regulator's signal contract (RegulatorCore owns the list and its order). arm{n}.pitch is derived from
     *  arm{n}.ratio (12·log2 of the ratio folded into one octave), so it has no slot. tone.* are arm-agnostic: how much
     *  reach sits on each chord tone of the harmonic series, whichever arm carries it (RegulatorCore.computeSignals). */
    public static final String[] SIGNALS = RegulatorCore.SIGNALS;
    public static final double[] SIG_MAX = {8, 8, 8, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    public static final int SIG_SCORE = 11;
    public static final String[] SIGNAL_CHOICES = {"arm1.ratio", "arm1.pitch", "arm1.reach", "arm2.ratio", "arm2.pitch", "arm2.reach",
                                            "arm3.ratio", "arm3.pitch", "arm3.reach", "radiance", "consonance", "tension", "drive", "coherence", "score",
                                            "orb.speed", "orb.accel", "orb.curl", "orb.radius", "stir", "tone.root", "tone.third", "tone.fifth", "tone.seventh", "stack", "fit"};
    public static int sigIdx(String name) { for (int i = 0; i < SIGNALS.length; i++) if (SIGNALS[i].equals(name)) return i; return -1; }

    /** A chord's pitch classes in semitones (0..12), from its just ratios; null for an unknown name. */
    public static final HashMap<String, double[]> SCALES = new HashMap<>();
    public static double[] scaleDegrees(String name) {
        synchronized (SCALES) {
            if (SCALES.containsKey(name)) return SCALES.get(name);
            double[] out = null;
            for (int i = 0; i < NCHORD; i++) if (CHORD_NAMES[i].replace(' ', '_').equals(name)) {
                TreeSet<Long> pcs = new TreeSet<>();
                for (double r : CHORDS[i]) { double st = 12 * Math.log(r) / Math.log(2); pcs.add(Math.round((((st % 12) + 12) % 12) * 1000)); }
                out = pcs.stream().mapToDouble(x -> x / 1000.0).toArray();
            }
            SCALES.put(name, out);
            return out;
        }
    }
    public static String fmtNum5(double v) {
        String s = String.format(Locale.ROOT, "%.5f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }

    public static double smoothstep(double a, double b, double x) { double u = Math.max(0, Math.min(1, (x - a) / (b - a))); return u * u * (3 - 2 * u); }

    /** How long a one-shot of this clip lasts when fired: a recording's length at its playback rate, else 1.5 s. */
    public static double naturalDur(Clip c) {
        if (sampled(c)) {
            float[][] s = sample(c.file);
            double rate = c.p[speedIdx(c.type)] * (keepLen(c) ? 1 : Math.pow(2, c.p[P_PITCH] / 12.0));
            if (rate < 0.01) rate = 1;
            return Math.max(0.05, s[0].length / (double) SR / rate);
        }
        return 1.5;
    }
}
