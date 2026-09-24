import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SynthLab — a minimal spellcasting-audio playground. (v5: the conjuring game)
 *
 * Channel A — the voice cloud: 14 wavetable voices plus a sine sub-bass,
 * THX-style. "Gather" pulls wandering voices onto a chord; per-voice detune
 * persists at full gather; timbre morphs gritty -> saw.
 *
 * Channel B — the binding: two clean tones (morphing all the way to pure sine)
 * mixed AFTER the lowpass so they stay glassy over the void. Drawn as their own
 * gold Lissajous overlay: when B's interval passes a simple ratio the gold
 * figure knots into a stable curve — that's your moment to bind it.
 *
 * The conjuring game (V + mouse):
 *   V            begin conjuring: channel A's parameters orbit through state
 *                space on slow incommensurate LFOs — it drifts through power
 *                and garbage on its own; you cannot steer, only choose when
 *   LEFT CLICK   bind channel A where it is; channel B begins seeking
 *   wheel        (while B is up) shift channel B's own pitch — the two channels
 *                can sit at different frequencies; combos live in that space
 *   RIGHT CLICK  bind channel B (aim for a stable gold figure / consonant ring)
 *   ENTER        (once bound) inscribe the combo — appends both channels'
 *                settings to ~/synthlab/combos.txt
 *   , / .        step through inscribed combos: loads both channels live,
 *                armed, so V replays the release
 *   V again      release the spell: score = how well both channels were bound.
 *                Releasing early leaves channels unbound — wild, half credit.
 *
 * Manual play (everything from v4 still works when not conjuring):
 *   Q/A gather   W/S stability   E/D purity   R/F focus
 *   T/G resonance (the filter's whistle)   Y/H echo (feedback delay mix)
 *   Z/X/C chord | wheel pitch | Shift+1..8 save preset | 1..8 sweep to preset
 *   [ ] sweep time | P play preset chain | SPACE release
 *
 * Gestures (v6) — one-shot spell impacts, layered over whatever is playing:
 *   I riser   K downer   J zap   U woosh   O sparkle
 *   Shift+key fires the modulation version instead: the same gesture warps the
 *   drone itself — riser drags the cloud's pitch up, downer dives it and back,
 *   zap jolts the cast out of alignment, woosh sweeps the drone's own filter,
 *   sparkle shimmers its pitch. All warps are transient offsets that decay to
 *   zero — the underlying parameters are never changed. Fire both flavors
 *   together for impact + warp.
 *   Precise releases auto-fire a riser into a sparkle shower at the peak;
 *   wild surges crack a zap into a downer.
 *
 * M toggles recording; stopping writes ~/synthlab/rec-<millis>.wav.
 *
 * Run:  java SynthLab.java
 */
public class SynthLab extends JPanel {

    static final int SR = 44100;
    static final int BLOCK = 256;
    static final double BASE_FREQ = 55.0;
    static final int SCOPE_N = 4096;
    static final int NV = 14;
    static final int NSLOTS = 8;
    static final Path PRESET_FILE = Paths.get(System.getProperty("user.home"), "synthlab", "presets.txt");
    static final Path COMBO_FILE = Paths.get(System.getProperty("user.home"), "synthlab", "combos.txt");

    // =====================================================================
    // Wavetables: 0 = gritty void gnarl, 1 = bright saw, 2 = pure sine.
    // Cloud voices morph 0 -> 1; channel B and the sub reach the sine.
    // =====================================================================
    static final int WT = 2048;
    static final float[][] TABLES = new float[3][WT + 1];
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
    static double osc(double phase, double pos) {
        int ti = Math.min((int) pos, TABLES.length - 2);
        double tf = pos - ti;
        double idx = phase * WT;
        int i0 = (int) idx;
        double f = idx - i0;
        double a = TABLES[ti][i0] * (1 - f) + TABLES[ti][i0 + 1] * f;
        double b = TABLES[ti + 1][i0] * (1 - f) + TABLES[ti + 1][i0 + 1] * f;
        return a * (1 - tf) + b * tf;
    }

    static final double[][] CHORDS = {
        {0.5, 0.5, 1, 1, 1, 2, 2, 2, 4, 4, 4, 8, 8, 16},
        {0.5, 0.75, 1, 1, 1.5, 2, 2, 3, 3, 4, 4, 6, 8, 12},
        {0.5, 1, 1, 1.25, 1.5, 2, 2, 2.5, 3, 4, 4, 5, 6, 8},
    };
    static final String[] CHORD_NAMES = {"octaves", "power (5ths)", "major"};

    static double[] chordLog(int c) {
        double[] out = new double[NV];
        for (int v = 0; v < NV; v++) out[v] = Math.log(CHORDS[c][v]);
        return out;
    }

    // Simple-ratio targets for channel B, with consonance weights: binding near
    // one of these scores by a Gaussian on distance in log-pitch (sigma ~0.4 st).
    static final double[] R_LOG = {0, Math.log(6.0/5), Math.log(5.0/4), Math.log(4.0/3),
                                   Math.log(3.0/2), Math.log(5.0/3), Math.log(2)};
    static final double[] R_WEIGHT = {0.85, 0.70, 0.85, 0.80, 1.00, 0.70, 0.95};
    static final String[] R_NAMES = {"unison", "min3", "maj3", "4th", "5th", "maj6", "octave"};

    static int nearestRatio(double lr) {
        int best = 0;
        for (int i = 1; i < R_LOG.length; i++)
            if (Math.abs(lr - R_LOG[i]) < Math.abs(lr - R_LOG[best])) best = i;
        return best;
    }
    static double ratioQuality(double lr) {
        double q = 0;
        for (int i = 0; i < R_LOG.length; i++) {
            double d = (lr - R_LOG[i]) / 0.025;
            q = Math.max(q, R_WEIGHT[i] * Math.exp(-d * d));
        }
        return q;
    }

    // ---- channel A parameters (UI thread writes, audio thread reads)
    volatile double gather = 0, stability = 0, purity = 0, focus = 0;
    volatile double reso = 0, echo = 0;     // filter resonance & delay mix (manual FX, not orbited)
    volatile double pitchSemis = 0;
    volatile int chord = 1;
    volatile double[] targetLog = chordLog(1);

    // ---- channel B parameters
    volatile boolean bOn = false;
    volatile double bLevel = 0;             // target mix level; audio smooths it
    volatile double bLogRatio = Math.log(1.5); // interval between B's two tones (log)
    volatile double bMorph = 1.0;           // table pos 0..2 (saw -> sine range in play)
    volatile double bWobble = 0;            // 1 while seeking: pitch shimmer on B
    volatile double bPitchSemis = 0;        // B's own pitch offset from channel A (semitones)

    /** Snapshot of the whole instrument at the moment of release. */
    record Cast(double gather, double stability, double purity, double focus,
                double pitchSemis, double[] targetLog,
                boolean bOn, double bLevel, double bLogRatio, double bMorph, double bSnapLog,
                double bPitchSemis, double reso, double echo, double score) {}
    volatile Cast pendingRelease = null;
    volatile boolean inRelease = false;

    // ---- gestures: one-shot spell impacts; UI enqueues a type, audio realizes it
    static final int G_RISER = 0, G_DOWNER = 1, G_ZAP = 2, G_WOOSH = 3, G_SPARKLE = 4;
    final ConcurrentLinkedQueue<Integer> gestQ = new ConcurrentLinkedQueue<>(); // additive voices
    final ConcurrentLinkedQueue<Integer> modQ = new ConcurrentLinkedQueue<>();  // drone-warping versions

    // ---- recording: M toggles; the audio thread captures and writes the WAV
    volatile boolean recording = false;

    // =====================================================================
    // The conjuring game (EDT-only state except the volatiles above)
    // =====================================================================
    static final int IDLE = 0, SWEEP_A = 1, SWEEP_B = 2, ARMED = 3;
    volatile int conjState = IDLE;
    double conjT = 0;
    // The orbit table — fiddle freely. One row per orbiting parameter: {min, max, period s}.
    // Keep periods mutually irrational-ish so the path never quite repeats.
    // A-pitch is in semitones (the cloud's fundamental glides with the orbit and is
    // frozen by the LEFT CLICK bind, so pitch is part of the roulette).
    // B-ratio is the fraction of an octave (0 = unison, 1 = octave); B-morph is
    // wavetable position (1 = saw, 2 = pure sine).
    static final double[][] ORBIT = {
        /* 0 gather     */ {0.0, 1.0, 1.5},
        /* 1 stability  */ {0.0, 1.0, 3.3},
        /* 2 purity     */ {0.3, 1.0, 2.1},
        /* 3 focus      */ {0.3, 0.8, 2.3},
        /* 4 A pitch st */ {-12, 12, 20.0},
        /* 5 B ratio    */ {0.0, 1.0, 4.0},
        /* 6 B morph    */ {1.0, 2.0, 4.3},
    };
    final double[] lfoPhase = new double[ORBIT.length]; // randomized at conjure start
    double qALock = 0, qBLock = 0;
    final Random uiRng = new Random();

    void startConjure() {
        cancelSweep();
        conjState = SWEEP_A;
        conjT = 0;
        qALock = qBLock = 0;
        for (int i = 0; i < lfoPhase.length; i++) lfoPhase[i] = uiRng.nextDouble() * 2 * Math.PI;
        bOn = false; bLevel = 0; bPitchSemis = 0;
    }

    void cancelConjure() { conjState = IDLE; bOn = false; bLevel = 0; bWobble = 0; }

    /** Advance the conjure orbits; called from the 33 ms UI timer. */
    void tickConjure() {
        if (conjState == IDLE || conjState == ARMED) return;
        conjT += 0.033;
        if (conjState == SWEEP_A) {
            // Channel A orbits state space: one slow sine per parameter, periods
            // incommensurate, so the path visits power and garbage without repeating.
            gather     = orbit(0);
            stability  = orbit(1);
            purity     = orbit(2);
            focus      = Math.min(orbit(3), purity + 0.1); // still avoids focus >> purity screech | was purity + 0.35
            pitchSemis = orbit(4);                          // the fundamental rides the orbit too
        } else { // SWEEP_B
            bLogRatio = Math.log(2) * orbit(5);          // glides unison..octave
            bMorph    = orbit(6);                        // saw..pure sine
        }
    }
    /** Orbit parameter i: a sine between its ORBIT min and max at its own period. */
    double orbit(int i) {
        double u = 0.5 + 0.5 * Math.sin(2 * Math.PI * conjT / ORBIT[i][2] + lfoPhase[i]);
        return ORBIT[i][0] + (ORBIT[i][1] - ORBIT[i][0]) * u;
    }

    double qualityA() { return 0.40 * gather + 0.25 * stability + 0.20 * purity + 0.15 * focus; }

    void bindA() {
        if (conjState != SWEEP_A) return;
        qALock = qualityA();          // A's volatiles simply stop being driven — frozen where bound
        conjState = SWEEP_B;
        bOn = true; bLevel = 0.30; bWobble = 1;
    }

    void bindB() {
        if (conjState != SWEEP_B) return;
        qBLock = ratioQuality(bLogRatio);
        bWobble = 0;                  // the binding rings true
        conjState = ARMED;
    }

    void releaseConjured() {
        double qA = conjState >= SWEEP_B ? qALock : qualityA() * 0.5;   // unbound = half credit
        double qB = conjState == ARMED ? qBLock
                  : conjState == SWEEP_B ? ratioQuality(bLogRatio) * 0.5 : 0;
        double score = bOn ? 0.5 * qA + 0.5 * qB : qA;
        String verdict = score > 0.85 ? "PRECISE CAST"
                       : score > 0.60 ? "controlled release"
                       : score > 0.35 ? "unstable discharge"
                       : "WILD SURGE FROM THE VOID";
        String detail = bOn
                ? String.format("A %.0f%% · B %.0f%% (%s)", qA * 100, qB * 100, R_NAMES[nearestRatio(bLogRatio)])
                : String.format("A %.0f%%, B never summoned", qA * 100);
        fireRelease(score, verdict + "  [" + detail + "]");
        cancelConjure();
    }

    // =====================================================================
    // Combos: an inscribed pair of bound channels — the spell library.
    // ENTER appends the armed state to combos.txt; , / . step through them,
    // loading both channels live and armed so V replays the release.
    // =====================================================================
    record Combo(double gather, double stability, double purity, double focus,
                 double pitchSemis, int chord, double bLogRatio, double bMorph,
                 double bPitchSemis, double qA, double qB, double reso, double echo) {}
    final ArrayList<Combo> combos = new ArrayList<>();
    int comboIdx = -1;

    void saveCombo() {
        if (conjState != ARMED) return; // only a fully bound spell can be inscribed
        Combo c = new Combo(gather, stability, purity, focus, pitchSemis, chord,
                bLogRatio, bMorph, bPitchSemis, qALock, qBLock, reso, echo);
        combos.add(c);
        comboIdx = combos.size() - 1;
        try {
            Files.createDirectories(COMBO_FILE.getParent());
            String line = String.format("%.4f %.4f %.4f %.4f %.2f %d %.5f %.4f %.2f %.4f %.4f %.4f %.4f%n",
                    c.gather(), c.stability(), c.purity(), c.focus(), c.pitchSemis(), c.chord(),
                    c.bLogRatio(), c.bMorph(), c.bPitchSemis(), c.qA(), c.qB(), c.reso(), c.echo());
            Files.writeString(COMBO_FILE, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { System.err.println("combo save failed: " + e); }
        castMsg = String.format("combo %d inscribed  (A %.0f%% · B %.0f%%)", combos.size(), qALock * 100, qBLock * 100);
        castMsgAt = System.currentTimeMillis();
    }

    void loadCombo(int dir) {
        if (combos.isEmpty() || inRelease) return;
        cancelSweep();
        comboIdx = comboIdx < 0 ? (dir > 0 ? 0 : combos.size() - 1)
                 : ((comboIdx + dir) % combos.size() + combos.size()) % combos.size();
        Combo c = combos.get(comboIdx);
        gather = c.gather(); stability = c.stability(); purity = c.purity(); focus = c.focus();
        pitchSemis = c.pitchSemis(); chord = c.chord(); targetLog = chordLog(c.chord());
        bLogRatio = c.bLogRatio(); bMorph = c.bMorph(); bPitchSemis = c.bPitchSemis();
        reso = c.reso(); echo = c.echo();
        bOn = true; bLevel = 0.30; bWobble = 0;
        qALock = c.qA(); qBLock = c.qB();
        conjState = ARMED; // loaded fully bound: V releases it
        castMsg = String.format("combo %d/%d loaded", comboIdx + 1, combos.size());
        castMsgAt = System.currentTimeMillis();
    }

    void loadCombos() {
        try {
            if (!Files.exists(COMBO_FILE)) return;
            for (String line : Files.readAllLines(COMBO_FILE)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                combos.add(new Combo(Double.parseDouble(t[0]), Double.parseDouble(t[1]),
                        Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]),
                        Math.min(CHORDS.length - 1, Math.max(0, Integer.parseInt(t[5]))),
                        Double.parseDouble(t[6]), Double.parseDouble(t[7]), Double.parseDouble(t[8]),
                        Double.parseDouble(t[9]), Double.parseDouble(t[10]),
                        t.length > 12 ? Double.parseDouble(t[11]) : 0,  // older files lack reso/echo
                        t.length > 12 ? Double.parseDouble(t[12]) : 0));
            }
        } catch (Exception e) { System.err.println("combo load failed: " + e); }
    }

    void fireRelease(double score, String msg) {
        castMsg = msg;
        castMsgAt = System.currentTimeMillis();
        pendingRelease = new Cast(gather, stability, purity, focus, pitchSemis, targetLog,
                bOn, bLevel, bLogRatio, bMorph, R_LOG[nearestRatio(bLogRatio)], bPitchSemis, reso, echo, score);
        gather = stability = purity = focus = 0;
        bOn = false; bLevel = 0;
    }

    // =====================================================================
    // Presets & timed sweeps (v4, unchanged behavior)
    // =====================================================================
    record Preset(double gather, double stability, double purity, double focus,
                  double pitchSemis, int chord, double time, double reso, double echo) {}
    final Preset[] slots = new Preset[NSLOTS];
    double sweepTime = 2.0;
    double[] swFrom, swTo, swFromLog, swToLog;
    double swDur, swT;
    boolean sweeping = false;
    int sweepTarget = -1;
    int playIdx = -1;

    void startSweep(int slot) {
        Preset ps = slots[slot];
        if (ps == null) return;
        cancelConjure();
        swFrom = new double[]{gather, stability, purity, focus, pitchSemis, reso, echo};
        swTo = new double[]{ps.gather(), ps.stability(), ps.purity(), ps.focus(), ps.pitchSemis(),
                            ps.reso(), ps.echo()};
        swFromLog = targetLog.clone();
        swToLog = chordLog(ps.chord());
        swDur = Math.max(0.05, ps.time());
        swT = 0;
        sweeping = true;
        sweepTarget = slot;
        chord = ps.chord();
    }

    void cancelSweep() { sweeping = false; sweepTarget = -1; playIdx = -1; }

    void tickSweep() {
        if (!sweeping) return;
        swT += 0.033;
        double u = Math.min(1, swT / swDur);
        u = u * u * (3 - 2 * u);
        gather     = swFrom[0] + (swTo[0] - swFrom[0]) * u;
        stability  = swFrom[1] + (swTo[1] - swFrom[1]) * u;
        purity     = swFrom[2] + (swTo[2] - swFrom[2]) * u;
        focus      = swFrom[3] + (swTo[3] - swFrom[3]) * u;
        pitchSemis = swFrom[4] + (swTo[4] - swFrom[4]) * u;
        reso       = swFrom[5] + (swTo[5] - swFrom[5]) * u;
        echo       = swFrom[6] + (swTo[6] - swFrom[6]) * u;
        double[] tl = new double[NV];
        for (int v = 0; v < NV; v++) tl[v] = swFromLog[v] + (swToLog[v] - swFromLog[v]) * u;
        targetLog = tl;
        if (swT >= swDur) {
            sweeping = false;
            sweepTarget = -1;
            if (playIdx >= 0) advancePlay();
        }
    }

    void advancePlay() {
        ArrayList<Integer> defined = new ArrayList<>();
        for (int i = 0; i < NSLOTS; i++) if (slots[i] != null) defined.add(i);
        if (defined.isEmpty()) { playIdx = -1; return; }
        playIdx = (playIdx + 1) % defined.size();
        startSweep(defined.get(playIdx));
    }

    void savePreset(int slot) {
        slots[slot] = new Preset(gather, stability, purity, focus, pitchSemis, chord, sweepTime, reso, echo);
        writePresets();
    }

    void writePresets() {
        try {
            StringBuilder sb = new StringBuilder("# slot gather stability purity focus pitchSemis chord time reso echo\n");
            for (int i = 0; i < NSLOTS; i++) {
                Preset p = slots[i];
                if (p != null) sb.append(String.format("%d %.4f %.4f %.4f %.4f %.2f %d %.2f %.4f %.4f%n",
                        i, p.gather(), p.stability(), p.purity(), p.focus(), p.pitchSemis(), p.chord(), p.time(),
                        p.reso(), p.echo()));
            }
            Files.createDirectories(PRESET_FILE.getParent());
            Files.writeString(PRESET_FILE, sb.toString());
        } catch (Exception e) { System.err.println("preset save failed: " + e); }
    }

    void loadPresets() {
        try {
            if (!Files.exists(PRESET_FILE)) return;
            for (String line : Files.readAllLines(PRESET_FILE)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                int i = Integer.parseInt(t[0]);
                if (i < 0 || i >= NSLOTS) continue;
                slots[i] = new Preset(Double.parseDouble(t[1]), Double.parseDouble(t[2]),
                        Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]),
                        Math.min(CHORDS.length - 1, Math.max(0, Integer.parseInt(t[6]))), Double.parseDouble(t[7]),
                        t.length > 9 ? Double.parseDouble(t[8]) : 0,   // older files lack reso/echo
                        t.length > 9 ? Double.parseDouble(t[9]) : 0);
            }
        } catch (Exception e) { System.err.println("preset load failed: " + e); }
    }

    // ---- audio state (audio thread only)
    final double[] phase = new double[NV];
    final double[] wander = new double[NV];
    final double[] detune = new double[NV];
    final double[] pan = new double[NV];
    double phaseSub = 0, phB1 = 0, phB2 = 0;
    double jB = 0;                      // channel B seek-shimmer random walk
    double bLevAct = 0;                 // smoothed B level
    double svLowL = 0, svBandL = 0, svLowR = 0, svBandR = 0; // state-variable filter memories
    final float[] dlyL = new float[(int) (0.31 * SR)];       // ping-pong delay lines,
    final float[] dlyR = new float[(int) (0.37 * SR)];       // unequal lengths for width
    int dlyPosL = 0, dlyPosR = 0;
    ByteArrayOutputStream recBuf = null; // recording capture (audio thread only)
    double env = 0;

    /** One active gesture voice (audio thread only). */
    static class Gest {
        int type; double t, dur;
        double ph, ph2;                 // oscillator phases
        double svLow, svBand;           // woosh bandpass state
        double nextPing;                // sparkle spawn clock
        final double[] pf = new double[12], pp = new double[12], pa = new double[12], ppan = new double[12];
        Gest(int type, double dur, double startDelay) { this.type = type; this.dur = dur; this.t = -startDelay; }
    }
    final ArrayList<Gest> gests = new ArrayList<>();
    final ArrayList<Gest> modGests = new ArrayList<>();
    static final double[] PENTA = {1, 9.0 / 8, 5.0 / 4, 3.0 / 2, 5.0 / 3}; // sparkle ping scale
    void addGest(Gest g) { if (gests.size() < 8) gests.add(g); }
    Cast rel = null;
    double relT = 0, relStartEnv = 0;
    final Random rng = new Random();
    static final double W_LO = Math.log(0.35), W_HI = Math.log(12);

    {
        Random r = new Random(7);
        for (int v = 0; v < NV; v++) {
            phase[v] = r.nextDouble();
            wander[v] = W_LO + r.nextDouble() * (W_HI - W_LO);
            detune[v] = (r.nextDouble() * 2 - 1) * 0.006;
            pan[v] = Math.sin(v * 2.399963);
        }
    }

    // ---- scope ring buffers: cloud+mix (green) and channel B alone (gold)
    final float[] scopeL = new float[SCOPE_N], scopeR = new float[SCOPE_N];
    final float[] scopeBL = new float[SCOPE_N], scopeBR = new float[SCOPE_N];
    int scopePos = 0;

    volatile String castMsg = "";
    volatile long castMsgAt = 0;

    // =====================================================================
    // Audio
    // =====================================================================

    void audioLoop() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, BLOCK * 4 * 4);
        line.start();
        byte[] buf = new byte[BLOCK * 4];

        while (true) {
            for (Integer gt; (gt = gestQ.poll()) != null; )
                addGest(new Gest(gt, gt == G_RISER ? 1.8 : gt == G_DOWNER ? 1.4 : gt == G_ZAP ? 0.25
                        : gt == G_WOOSH ? 1.2 : 1.6, 0));
            for (Integer gt; (gt = modQ.poll()) != null; )
                if (modGests.size() < 8) modGests.add(new Gest(gt,
                        gt == G_RISER ? 1.8 : gt == G_DOWNER ? 1.4 : gt == G_ZAP ? 0.6
                      : gt == G_WOOSH ? 1.2 : 1.6, 0));
            for (int i = 0; i < BLOCK; i++) {
                Cast p = pendingRelease;
                if (p != null) {
                    pendingRelease = null;
                    rel = p; relT = 0; relStartEnv = env; inRelease = true;
                    // Release impacts: a precise cast rides a riser into a sparkle
                    // shower at the peak; a wild surge cracks a zap into a downer.
                    if (p.score() > 0.6) {
                        double swell = 0.15 + 1.1 * p.score();
                        addGest(new Gest(G_RISER, swell, 0));
                        addGest(new Gest(G_SPARKLE, 1.6, swell));
                    } else if (p.score() < 0.35) {
                        addGest(new Gest(G_ZAP, 0.25, 0));
                        addGest(new Gest(G_DOWNER, 1.4, 0.1));
                    }
                }

                // Effective parameters: live during casting, shaped during the bloom.
                double pGather, pStab, pPurity, pFocus, pPitch;
                double[] tl;
                double bLog, bMor, bLevTarget, bWob, bPitch, pReso, pEcho;
                double freqSweep = 1, score = 0, attack = 0;
                if (rel != null) {
                    relT += 1.0 / SR;
                    score = rel.score();
                    attack = 0.15 + 1.1 * score;
                    pPitch = rel.pitchSemis(); tl = rel.targetLog();
                    bPitch = rel.bPitchSemis(); pReso = rel.reso(); pEcho = rel.echo();
                    double prog = Math.min(1, relT / attack);
                    bLevTarget = rel.bOn() ? rel.bLevel() : 0;
                    bWob = 0;
                    if (score > 0.6) {
                        pGather = rel.gather() + (1 - rel.gather()) * prog;
                        pFocus  = rel.focus() + (1 - rel.focus()) * prog;
                        pPurity = Math.max(rel.purity(), prog);
                        pStab = 1;
                        // The binding perfects itself: ratio snaps to the nearest pure
                        // interval, timbre crystallizes to sine.
                        bLog = rel.bLogRatio() + (rel.bSnapLog() - rel.bLogRatio()) * prog;
                        bMor = rel.bMorph() + (2 - rel.bMorph()) * prog;
                    } else {
                        pGather = rel.gather() * Math.max(0, 1 - relT * 3);
                        pFocus = rel.focus();
                        pPurity = rel.purity() * (0.3 + 0.7 * score);
                        pStab = rel.stability() * 0.5;
                        freqSweep = 1 - (1 - score) * 0.4 * Math.min(1, relT * 2.5);
                        // The binding shatters: interval slides away, timbre curdles.
                        bLog = rel.bLogRatio() - relT * 0.5;
                        bMor = Math.max(0.2, rel.bMorph() - relT * 2);
                    }
                } else {
                    pGather = gather; pStab = stability; pPurity = purity; pFocus = focus;
                    pPitch = pitchSemis; tl = targetLog;
                    bLog = bLogRatio; bMor = bMorph; bLevTarget = bOn ? bLevel : 0; bWob = bWobble;
                    bPitch = bPitchSemis; pReso = reso; pEcho = echo;
                }

                // ---- modulation gestures: transient offsets that warp the drone
                // itself, then decay to zero — the stored parameters are untouched.
                double oPitch = 0, oGather = 0, oStab = 0, oFocus = 0, oReso = 0;
                for (int gi = modGests.size() - 1; gi >= 0; gi--) {
                    Gest g = modGests.get(gi);
                    g.t += 1.0 / SR;
                    if (g.t < 0) continue;
                    double prog = Math.min(1, g.t / g.dur);
                    switch (g.type) {
                        case G_RISER -> { // drags the whole cloud up an octave, filter opening
                            double fade = Math.min(1, (1 - prog) / 0.12); // eases back at the very end
                            oPitch += 12 * prog * prog * fade;
                            oFocus += 0.3 * prog * fade;
                        }
                        case G_DOWNER -> // bends the drone into a dive and back
                            oPitch += -12 * Math.sin(Math.PI * prog);
                        case G_ZAP -> { // a jolt: knocks the cast out of alignment, recovering
                            double kj = Math.exp(-4 * prog) * (1 - prog);
                            oGather -= 0.8 * kj; oStab -= 0.8 * kj; oPitch -= 4 * kj;
                        }
                        case G_WOOSH -> { // sweeps the drone's own filter up and back
                            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * prog);
                            oFocus += 0.6 * hann; oReso += 0.5 * hann;
                        }
                        case G_SPARKLE -> { // glitter: a decaying random pitch shimmer.
                            // Sample-and-hold: a fresh +/-3 st target every 30 ms, glided to
                            // quickly. (Smoothing per-sample noise instead averages to ~zero.)
                            if (g.t >= g.nextPing) {
                                g.nextPing = g.t + 0.03;
                                g.ph = (rng.nextDouble() * 2 - 1) * 3;
                            }
                            g.svBand += (g.ph - g.svBand) * 0.002;
                            oPitch += g.svBand * (1 - prog);
                        }
                    }
                    if (g.t > g.dur) modGests.remove(gi);
                }
                pPitch += oPitch;
                pGather = clamp(pGather + oGather);
                pStab   = clamp(pStab + oStab);
                pFocus  = clamp(pFocus + oFocus);
                pReso   = clamp(pReso + oReso);

                double morph = (pGather + pStab + pPurity) / 3;
                double base = BASE_FREQ * Math.pow(2, pPitch / 12.0) * freqSweep;
                double wanderRate = 0.003 * (1 - pStab) * (1 - pStab);

                // ---- channel A: the voice cloud
                double sumL = 0, sumR = 0;
                for (int v = 0; v < NV; v++) {
                    wander[v] += (rng.nextDouble() - 0.5) * wanderRate;
                    if (wander[v] < W_LO) wander[v] = 2 * W_LO - wander[v];
                    if (wander[v] > W_HI) wander[v] = 2 * W_HI - wander[v];
                    double logRatio = wander[v] + (tl[v] - wander[v]) * pGather;
                    double f = base * Math.exp(logRatio) * (1 + detune[v]);
                    phase[v] += f / SR;
                    if (phase[v] >= 1) phase[v] -= 1;
                    double s = osc(phase[v], morph) * (1.0 - 0.55 * v / (NV - 1.0));
                    double pp = (pan[v] + 1) * Math.PI / 4;
                    sumL += s * Math.cos(pp);
                    sumR += s * Math.sin(pp);
                }
                sumL /= NV * 0.5;
                sumR /= NV * 0.5;

                phaseSub += base * 0.5 / SR;
                if (phaseSub >= 1) phaseSub -= 1;
                double sub = osc(phaseSub, 2.0) * 0.35;

                // The void: white noise, faded by purity AND loudness-compensated
                // against the filter — an open filter no longer makes noise screech.
                double nAmt = (1 - pPurity) * 0.7 * (1 - 0.45 * pFocus);
                double nL = (rng.nextDouble() * 2 - 1) * nAmt;
                double nR = (rng.nextDouble() * 2 - 1) * nAmt;

                double tAmt = 0.2 + 0.8 * pPurity;
                double l = sumL * tAmt + sub + nL;
                double r = sumR * tAmt + sub + nR;

                // Focus + resonance: a state-variable lowpass. Resonance raises Q,
                // adding the whistle-at-the-cutoff that synthetic magic rides on.
                // The tanh on the band state keeps high-Q settings bounded (and
                // adds a little filter grit when pushed — a feature, not a bug).
                double fc = 150 * Math.pow(9000.0 / 150.0, pFocus);
                double fS = 2 * Math.sin(Math.PI * fc / SR);
                double q1 = 1.0 / (0.5 + 7.5 * pReso);
                svLowL += fS * svBandL;
                svBandL += fS * (l - svLowL - q1 * svBandL);
                svBandL = Math.tanh(svBandL * 0.6) / 0.6;
                svLowR += fS * svBandR;
                svBandR += fS * (r - svLowR - q1 * svBandR);
                svBandR = Math.tanh(svBandR * 0.6) / 0.6;
                double lpL = svLowL, lpR = svLowR;

                // ---- channel B: the binding — two clean tones, mixed AFTER the
                // lowpass so they stay glassy over a muffled void. Tone 1 left,
                // tone 2 (the swept interval) right: they draw the gold Lissajous.
                bLevAct += (bLevTarget - bLevAct) * 0.0008;
                double bL = 0, bR = 0;
                if (bLevAct > 0.001) {
                    jB = jB * 0.9995 + (rng.nextDouble() - 0.5) * 0.004;
                    double wob = 1 + jB * bWob * 1.5; // seek-shimmer; silent once bound
                    double bBase = base * 2 * Math.pow(2, bPitch / 12.0); // B rides its own pitch offset
                    double f1 = bBase * wob;
                    double f2 = bBase * Math.exp(bLog) * wob;
                    phB1 += f1 / SR; if (phB1 >= 1) phB1 -= 1;
                    phB2 += f2 / SR; if (phB2 >= 1) phB2 -= 1; if (phB2 < 0) phB2 += 1;
                    bL = osc(phB1, bMor) * bLevAct;
                    bR = osc(phB2, bMor) * bLevAct;
                }

                // Envelope before tanh: blooms saturate. Casting sits at 0.7 headroom.
                if (rel != null) {
                    double peak = 1.2 + 1.0 * score;
                    double release = 0.3 + 2.2 * score * score;
                    if (relT < attack) {
                        env = relStartEnv + (peak - relStartEnv) * (relT / attack);
                    } else {
                        env = peak * Math.exp(-(relT - attack) / release * 4);
                        if (env < 0.002) { rel = null; env = 0; inRelease = false; }
                    }
                } else {
                    env += (0.7 - env) * 0.0008;
                }

                // ---- gestures: one-shot impacts, independent of the master envelope
                double gL = 0, gR = 0;
                for (int gi = gests.size() - 1; gi >= 0; gi--) {
                    Gest g = gests.get(gi);
                    g.t += 1.0 / SR;
                    if (g.t < 0) continue;                       // scheduled but not started yet
                    double prog = Math.min(1, g.t / g.dur);
                    switch (g.type) {
                        case G_RISER -> { // two detuned saws climbing three octaves
                            double f = 110 * Math.pow(2, 3 * prog);
                            g.ph += f / SR; if (g.ph >= 1) g.ph -= 1;
                            g.ph2 += f * 1.006 / SR; if (g.ph2 >= 1) g.ph2 -= 1;
                            double s = (osc(g.ph, 1) + osc(g.ph2, 1)) * prog * prog * 0.3;
                            gL += s; gR += s;
                        }
                        case G_DOWNER -> { // saw + sub-octave sine falling away
                            double f = 700 * Math.pow(2, -2.5 * prog);
                            g.ph += f / SR; if (g.ph >= 1) g.ph -= 1;
                            g.ph2 += f * 0.5 / SR; if (g.ph2 >= 1) g.ph2 -= 1;
                            double a = Math.pow(1 - prog, 1.5) * 0.5;
                            double s = (osc(g.ph, 1) * 0.7 + osc(g.ph2, 2) * 0.6) * a;
                            gL += s; gR += s;
                        }
                        case G_ZAP -> { // gritty tone dropping ~4 octaves in a blink
                            double f = 2200 * Math.exp(-18 * g.t);
                            g.ph += f / SR; if (g.ph >= 1) g.ph -= 1;
                            double s = osc(g.ph, 0.3) * Math.exp(-20 * g.t) * 0.8;
                            gL += s; gR += s;
                        }
                        case G_WOOSH -> { // noise through a sweeping bandpass, panning L -> R
                            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * prog);
                            double wf = 2 * Math.sin(Math.PI * (250 + 2600 * hann) / SR);
                            double n = (rng.nextDouble() * 2 - 1) * 0.8;
                            g.svLow += wf * g.svBand;
                            g.svBand += wf * (n - g.svLow - 0.35 * g.svBand);
                            double s = g.svBand * hann * 0.9;
                            double pw = ((-0.7 + 1.4 * prog) + 1) * Math.PI / 4;
                            gL += s * Math.cos(pw); gR += s * Math.sin(pw);
                        }
                        case G_SPARKLE -> { // a stochastic shower of decaying sine pings
                            if (g.t >= g.nextPing && prog < 1) {
                                g.nextPing = g.t + 1.0 / (30 * (1 - prog) + 3);
                                for (int k2 = 0; k2 < g.pf.length; k2++) if (g.pa[k2] < 0.01) {
                                    g.pf[k2] = 1046 * PENTA[rng.nextInt(PENTA.length)] * (rng.nextBoolean() ? 1 : 2);
                                    g.pp[k2] = 0; g.pa[k2] = 0.5;
                                    g.ppan[k2] = rng.nextDouble() * 1.6 - 0.8;
                                    break;
                                }
                            }
                            for (int k2 = 0; k2 < g.pf.length; k2++) if (g.pa[k2] >= 0.01) {
                                g.pp[k2] += g.pf[k2] / SR; if (g.pp[k2] >= 1) g.pp[k2] -= 1;
                                g.pa[k2] *= 0.999748; // ~90 ms exponential decay
                                double s = Math.sin(2 * Math.PI * g.pp[k2]) * g.pa[k2];
                                double sp = (g.ppan[k2] + 1) * Math.PI / 4;
                                gL += s * Math.cos(sp); gR += s * Math.sin(sp);
                            }
                        }
                    }
                    if (g.t > g.dur + (g.type == G_SPARKLE ? 0.5 : 0)) gests.remove(gi);
                }

                double mixL = Math.tanh((lpL + bL) * 1.4 * env + gL * 1.2) * 0.6;
                double mixR = Math.tanh((lpR + bR) * 1.4 * env + gR * 1.2) * 0.6;

                // ---- echo: a cross-fed (ping-pong) feedback delay. The lines are
                // always written so a rising echo knob reveals an already-live tail.
                double dOutL = dlyL[dlyPosL], dOutR = dlyR[dlyPosR];
                double fb = 0.3 + 0.4 * pEcho;
                dlyL[dlyPosL] = (float) (mixL + dOutR * fb);
                dlyR[dlyPosR] = (float) (mixR + dOutL * fb);
                dlyPosL = (dlyPosL + 1) % dlyL.length;
                dlyPosR = (dlyPosR + 1) % dlyR.length;
                double outL = Math.max(-1, Math.min(1, mixL + dOutL * pEcho));
                double outR = Math.max(-1, Math.min(1, mixR + dOutR * pEcho));

                scopeL[scopePos] = (float) outL;
                scopeR[scopePos] = (float) outR;
                scopeBL[scopePos] = (float) (bL * env * 0.8);
                scopeBR[scopePos] = (float) (bR * env * 0.8);
                scopePos = (scopePos + 1) % SCOPE_N;

                int sl = (int) (outL * 32767), sr = (int) (outR * 32767);
                buf[i * 4] = (byte) sl; buf[i * 4 + 1] = (byte) (sl >> 8);
                buf[i * 4 + 2] = (byte) sr; buf[i * 4 + 3] = (byte) (sr >> 8);
            }
            // ---- recording: capture while armed; on stop, hand off to a writer thread
            if (recording) {
                if (recBuf == null) recBuf = new ByteArrayOutputStream();
                recBuf.write(buf, 0, buf.length);
            } else if (recBuf != null) {
                final ByteArrayOutputStream done = recBuf;
                recBuf = null;
                final AudioFormat wfmt = fmt;
                new Thread(() -> {
                    try {
                        byte[] data = done.toByteArray();
                        Path out = PRESET_FILE.getParent().resolve("rec-" + System.currentTimeMillis() + ".wav");
                        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), wfmt, data.length / 4),
                                AudioFileFormat.Type.WAVE, out.toFile());
                        castMsg = "saved " + out.getFileName();
                        castMsgAt = System.currentTimeMillis();
                    } catch (Exception ex) { System.err.println("wav save failed: " + ex); }
                }, "wav-writer").start();
            }
            line.write(buf, 0, buf.length);
        }
    }

    // =====================================================================
    // Manual release (SPACE outside the conjuring game)
    // =====================================================================

    void releaseManual() {
        cancelSweep();
        double score = qualityA();
        String verdict = score > 0.85 ? "PRECISE CAST"
                       : score > 0.60 ? "controlled release"
                       : score > 0.35 ? "unstable discharge"
                       : "WILD SURGE FROM THE VOID";
        fireRelease(score, String.format("%s — %s  (alignment %.0f%%)", verdict, CHORD_NAMES[chord], score * 100));
    }

    // =====================================================================
    // UI
    // =====================================================================

    SynthLab() {
        setPreferredSize(new Dimension(760, 760));
        setBackground(Color.BLACK);
        setFocusable(true);
        loadPresets();
        loadCombos();

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                double step = 0.03;
                int kc = e.getKeyCode();
                if (kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_8) {
                    int slot = kc - KeyEvent.VK_1;
                    if (e.isShiftDown()) savePreset(slot);
                    else { playIdx = -1; startSweep(slot); }
                    return;
                }
                switch (kc) {
                    case KeyEvent.VK_Q -> { manual(); gather = clamp(gather + step); }
                    case KeyEvent.VK_A -> { manual(); gather = clamp(gather - step); }
                    case KeyEvent.VK_W -> { manual(); stability = clamp(stability + step); }
                    case KeyEvent.VK_S -> { manual(); stability = clamp(stability - step); }
                    case KeyEvent.VK_E -> { manual(); purity = clamp(purity + step); }
                    case KeyEvent.VK_D -> { manual(); purity = clamp(purity - step); }
                    case KeyEvent.VK_R -> { manual(); focus = clamp(focus + step); }
                    case KeyEvent.VK_F -> { manual(); focus = clamp(focus - step); }
                    case KeyEvent.VK_T -> reso = clamp(reso + step);
                    case KeyEvent.VK_G -> reso = clamp(reso - step);
                    case KeyEvent.VK_Y -> echo = clamp(echo + step);
                    case KeyEvent.VK_H -> echo = clamp(echo - step);
                    case KeyEvent.VK_I -> (e.isShiftDown() ? modQ : gestQ).add(G_RISER);
                    case KeyEvent.VK_K -> (e.isShiftDown() ? modQ : gestQ).add(G_DOWNER);
                    case KeyEvent.VK_J -> (e.isShiftDown() ? modQ : gestQ).add(G_ZAP);
                    case KeyEvent.VK_U -> (e.isShiftDown() ? modQ : gestQ).add(G_WOOSH);
                    case KeyEvent.VK_O -> (e.isShiftDown() ? modQ : gestQ).add(G_SPARKLE);
                    case KeyEvent.VK_M -> {
                        recording = !recording;
                        if (recording) { castMsg = "recording…"; castMsgAt = System.currentTimeMillis(); }
                    }
                    case KeyEvent.VK_Z -> { chord = 0; if (!sweeping) targetLog = chordLog(0); }
                    case KeyEvent.VK_X -> { chord = 1; if (!sweeping) targetLog = chordLog(1); }
                    case KeyEvent.VK_C -> { chord = 2; if (!sweeping) targetLog = chordLog(2); }
                    case KeyEvent.VK_OPEN_BRACKET  -> sweepTime = Math.max(0.05, sweepTime * 0.75);
                    case KeyEvent.VK_CLOSE_BRACKET -> sweepTime = Math.min(15, sweepTime * 1.333);
                    case KeyEvent.VK_P -> {
                        if (playIdx >= 0) cancelSweep();
                        else { cancelConjure(); playIdx = -1; advancePlay(); }
                    }
                    case KeyEvent.VK_ENTER -> saveCombo();
                    case KeyEvent.VK_COMMA -> loadCombo(-1);
                    case KeyEvent.VK_PERIOD -> loadCombo(1);
                    case KeyEvent.VK_V -> {
                        if (inRelease) return;
                        if (conjState == IDLE) startConjure();
                        else releaseConjured();
                    }
                    case KeyEvent.VK_SPACE -> {
                        if (inRelease) return;
                        if (conjState != IDLE) releaseConjured();
                        else releaseManual();
                    }
                }
            }
            void manual() { cancelSweep(); cancelConjure(); }
        });
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isLeftMouseButton(e)) bindA();
                else if (SwingUtilities.isRightMouseButton(e)) bindB();
            }
        });
        addMouseWheelListener(e -> {
            // Once channel B is up, the wheel is B's own pitch — the two channels
            // can sit at different frequencies; that offset is part of the combo.
            if (conjState == SWEEP_B || conjState == ARMED)
                bPitchSemis = Math.max(-24, Math.min(24, bPitchSemis - e.getWheelRotation()));
            else
                pitchSemis = Math.max(-24, Math.min(24, pitchSemis - e.getWheelRotation()));
        });

        new Timer(33, e -> { tickSweep(); tickConjure(); repaint(); }).start();
    }

    static double clamp(double v) { return Math.max(0, Math.min(1, v)); }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        int scopeSize = Math.min(w, h - 260);
        int cx = w / 2, cy = scopeSize / 2 + 20;

        // Cloud Lissajous (green), then channel B's own figure (gold) on top.
        drawTrail(g, scopeL, scopeR, cx, cy, scopeSize, 90, 255, 190, 180);
        if (bLevAct > 0.002) drawTrail(g, scopeBL, scopeBR, cx, cy, (int) (scopeSize * 2.2), 255, 215, 120, 230);

        // Parameter bars.
        String[] names = {"Q/A gather", "W/S stability", "E/D purity", "R/F focus",
                          "T/G resonance", "Y/H echo"};
        double[] vals = {gather, stability, purity, focus, reso, echo};
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        int barY = h - 218;
        for (int i = 0; i < 6; i++) {
            int y = barY + i * 24;
            g.setColor(Color.GRAY);
            g.drawString(names[i], 20, y + 12);
            g.drawRect(150, y, 400, 14);
            g.setColor(new Color(90, 255, 190));
            g.fillRect(151, y + 1, (int) (vals[i] * 398), 12);
        }

        // Preset slots.
        int py = h - 66;
        g.setColor(Color.GRAY);
        g.drawString("presets [shift+n save, n sweep]", 20, py + 12);
        for (int i = 0; i < NSLOTS; i++) {
            int x = 290 + i * 34;
            if (slots[i] != null) {
                g.setColor(new Color(90, 255, 190, 160));
                g.fillRect(x, py, 24, 16);
            }
            g.setColor(i == sweepTarget ? new Color(255, 235, 130) : Color.GRAY);
            g.drawRect(x, py, 24, 16);
            g.setColor(slots[i] != null ? Color.BLACK : Color.GRAY);
            g.drawString(String.valueOf(i + 1), x + 8, py + 13);
        }

        g.setColor(Color.GRAY);
        g.drawString("gestures: I riser  K downer  J zap  U woosh  O sparkle   (+Shift warps the drone)   M record", 20, h - 28);
        double hz = BASE_FREQ * Math.pow(2, pitchSemis / 12.0);
        g.drawString(String.format(
                "chord[Z/X/C]: %s  pitch[wheel]: %.0f Hz  sweep[ ]: %.2fs  P chain  V conjure  SPACE release  combos[,/.]: %s",
                CHORD_NAMES[chord], hz, sweepTime,
                combos.isEmpty() ? "none" : (comboIdx + 1) + "/" + combos.size()), 20, h - 12);

        // Conjure prompts.
        if (conjState != IDLE) {
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 15));
            g.setColor(new Color(150, 220, 255));
            String prompt = switch (conjState) {
                case SWEEP_A -> "channel A is drifting -- LEFT CLICK to bind it";
                case SWEEP_B -> String.format(
                        "A bound at %.0f%% -- B seeks (near %s, %+d st) -- wheel shifts B pitch, RIGHT CLICK binds",
                        qALock * 100, R_NAMES[nearestRatio(bLogRatio)], (int) bPitchSemis);
                default -> String.format(
                        "bound: A %.0f%% · B %.0f%% (%+d st) -- V releases · ENTER inscribes combo",
                        qALock * 100, qBLock * 100, (int) bPitchSemis);
            };
            g.drawString(prompt, cx - g.getFontMetrics().stringWidth(prompt) / 2, 78);
        }

        if (recording) {
            g.setColor(new Color(255, 80, 80));
            g.fillOval(w - 72, 14, 10, 10);
            g.drawString("REC", w - 56, 24);
        }

        // Cast result overlay, fades after 3 s.
        long dt = System.currentTimeMillis() - castMsgAt;
        if (!castMsg.isEmpty() && dt < 3000) {
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 20));
            g.setColor(new Color(255, 235, 130, (int) (255 * (1 - dt / 3000.0))));
            g.drawString(castMsg, cx - g.getFontMetrics().stringWidth(castMsg) / 2, 50);
        }
    }

    void drawTrail(Graphics2D g, float[] xs, float[] ys, int cx, int cy, int size,
                   int cr, int cg, int cb, int maxAlpha) {
        int pos = scopePos;
        for (int i = 0; i < SCOPE_N - 1; i++) {
            int idx = (pos + i) % SCOPE_N;
            int idx2 = (idx + 1) % SCOPE_N;
            float age = i / (float) SCOPE_N;
            int alpha = (int) (age * age * maxAlpha) + 8;
            g.setColor(new Color(cr, cg, cb, alpha));
            int x1 = cx + (int) (xs[idx] * size * 0.9);
            int y1 = cy - (int) (ys[idx] * size * 0.9);
            int x2 = cx + (int) (xs[idx2] * size * 0.9);
            int y2 = cy - (int) (ys[idx2] * size * 0.9);
            g.drawLine(x1, y1, x2, y2);
        }
    }

    public static void main(String[] args) {
        SynthLab lab = new SynthLab();
        Thread audio = new Thread(() -> {
            try { lab.audioLoop(); } catch (LineUnavailableException e) { e.printStackTrace(); System.exit(1); }
        }, "audio");
        audio.setDaemon(true);
        audio.setPriority(Thread.MAX_PRIORITY);
        audio.start();

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("SynthLab — bring the energy into alignment");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(lab);
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            lab.requestFocusInWindow();
        });
    }
}
