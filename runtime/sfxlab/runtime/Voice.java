package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

// =====================================================================
// Engine: renders any clip list sample by sample. The realtime loop and
// the offline exporter each own one, so exporting never glitches playback.
// Per-clip Random is seeded from the clip, so renders are reproducible.
// =====================================================================
public class Voice {
    public final Random rng;
    public double[] ph, wander, det, vpan;                   // cloud
    public double phSub, ph1, ph2, ph3, ph4, j;              // sub / tones / shimmer walk
    public double phM1, phM2;                                // tones FM modulator phases
    public double nl1, nl2;                                  // noise color filter state
    public float[] ks, ks2; public int kp; public double ex, dcp;          // Karplus-Strong string state
    public double sp = -1;                                   // sampler source position (init on first render)
    public final double[] gpos = new double[2]; public final int[] gage = new int[2];   // keep-len grains
    public boolean gFirst; public double[] gref;                    // first-grain flag, alignment scratch
    public double[] coff; public double[][] cgpos; public int[][] cgage; public boolean[] cgFirst;   // choir: per-voice read offset + grains
    public double[] pph;                                     // partials: per-track oscillator phases
    public double[] hg, hf, hsh, hsr;                        // harmonic controls: cached gain / freq multiplier, shimmer phase / rate per partial
    public double cOdd = Double.NaN, cTilt, cPur, cStr, cGat, cShift, cTol; public int cChord = -1;   // the params those caches were built for
    public double[] bph;                                     // tones bank: per-harmonic phases
    public final float[] fl1 = new float[FLN], fl2 = new float[FLN]; public int fp;   // flanger lines
    public final double[] apx = new double[2 * PH_MAX], apy = new double[2 * PH_MAX];   // phaser all-pass states
    public double phFbL, phFbR; public double phAng, lfoAng;   // accumulated phaser / clip-LFO angles (cycles) for live clips                              // phaser feedback
    public double lo1, b1, lo2, b2;                          // stereo SVF state
    public double nextPing;                                  // sparkle spawn clock
    public final double[] pf = new double[12], pp = new double[12], pa = new double[12], ppan = new double[12];
    public final double[] pm = new double[12];               // sparkle per-ping FM phases
    public double[] pe, sm;                                  // bench: effective params (p + smoothed mod) and the smoother state
    /** The params the engine reads this sample: the clip's own plus its live
     *  modulation through a one-pole smoother (~30 ms), clamped to each spec.
     *  Params that rebuild per-partial caches when they move (the harmonic
     *  controls) are quantised so a glide costs a few rebuilds, not one per
     *  sample. Clips without modulation never come here, so old renders are
     *  untouched. */
    public double[] effective(Clip c) {
        double[] m = c.mod, p = c.p;
        if (pe == null || pe.length != p.length) { pe = new double[p.length]; sm = m.clone(); }   // born where the modulation already is: no glide in from the saved value
        double lv = p[P_LEVEL] + m[P_LEVEL];
        if (lv < 1e-4 && p[P_LEVEL] + sm[P_LEVEL] < 1e-4) {   // silent and staying silent (a bench layer waiting its turn): track the targets, skip the rest
            System.arraycopy(m, 0, sm, 0, m.length);
            pe[P_LEVEL] = Math.max(0, lv);
            return pe;
        }
        boolean[] q = quantised(c.type);
        for (int i = 0; i < p.length; i++) {
            sm[i] += (m[i] - sm[i]) * MOD_K;
            PSpec s = spec(c.type, i);
            double v = p[i] + sm[i];
            if (q[i]) v = p[i] + Math.rint(sm[i] / (s.max() - s.min()) * 100) * (s.max() - s.min()) / 100;
            pe[i] = v < s.min() ? s.min() : v > s.max() ? s.max() : v;
        }
        return pe;
    }
    public Voice(Clip c) {
        rng = new Random(c.seed);
        if (c.type == PLUCK) { ks = new float[KSN]; ks2 = new float[KSN]; }
        if (c.type == SAMPLE || c.type == CHOIR || c.type == PARTIALS) gref = new double[GK];
        if (c.type == CHOIR) {
            coff = new double[NV]; cgpos = new double[NV][2]; cgage = new int[NV][2]; cgFirst = new boolean[NV];
            wander = new double[NV]; det = new double[NV]; vpan = new double[NV];
            double R = c.p[NCOMMON + CH_WANDER] * Math.log(2) / 12;
            for (int v = 0; v < NV; v++) {
                coff[v] = rng.nextDouble();                      // scaled by scatter × length at render time
                wander[v] = (rng.nextDouble() * 2 - 1) * R;
                det[v] = (rng.nextDouble() * 2 - 1) * 0.006;
                vpan[v] = Math.sin(v * 2.399963);
            }
        }
        if (c.type == CLOUD) {
            ph = new double[NV]; wander = new double[NV]; det = new double[NV]; vpan = new double[NV];
            for (int v = 0; v < NV; v++) {
                ph[v] = rng.nextDouble();
                wander[v] = W_LO + rng.nextDouble() * (W_HI - W_LO);
                det[v] = (rng.nextDouble() * 2 - 1) * 0.006;
                vpan[v] = Math.sin(v * 2.399963);
            }
        }
    }
}
