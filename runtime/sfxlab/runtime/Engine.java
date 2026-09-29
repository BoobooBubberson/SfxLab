package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** Renders any clip list sample by sample (or a block at a time, clips in parallel), with the shared echo, room
 *  and limiter. The realtime loop and the offline exporter each own one; the mod's streams own theirs. */
public class Engine {
    public double t = 0;
    public double key = 0;   // global transposition, semitones (see KEY_*)
    public double inPeak = 0;   // loudest master input since last read (>1 = the tanh limiter is squashing)
    public final HashMap<Clip, Voice> voices = new HashMap<>();
    // sidechain: per-track peak envelope of the previous sample's output
    public final double[] trackEnv = new double[TRACKS], trackAbs = new double[TRACKS];
    public static final double DK_ATT = 1 - Math.exp(-1.0 / (0.002 * SR)), DK_REL = 1 - Math.exp(-1.0 / (0.15 * SR));
    public final float[] dlyL = new float[(int) (0.31 * SR)], dlyR = new float[(int) (0.37 * SR)];
    public int dpL, dpR;
    // reverb: freeverb-lite — 6 damped combs + 2 allpasses per channel,
    // right channel offset for width. Fed by each clip's reverb send.
    public static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491};
    public static final int[] ALLP = {556, 441};
    public static final double RV_FB = 0.84, RV_DAMP = 0.3;
    public final float[][] cvL = new float[COMB.length][], cvR = new float[COMB.length][];
    public final float[][] avL = new float[ALLP.length][], avR = new float[ALLP.length][];
    public final int[] cpL = new int[COMB.length], cpR = new int[COMB.length],
                apL = new int[ALLP.length], apR = new int[ALLP.length];
    public final double[] cfL = new double[COMB.length], cfR = new double[COMB.length];
    {
        for (int i = 0; i < COMB.length; i++) { cvL[i] = new float[COMB[i]]; cvR[i] = new float[COMB[i] + 23]; }
        for (int i = 0; i < ALLP.length; i++) { avL[i] = new float[ALLP[i]]; avR[i] = new float[ALLP[i] + 23]; }
    }

    /** One sample of a Karplus-Strong string: a noise burst (one period
     *  long, lowpassed by pick softness) circulates in a fractional delay
     *  line; each pass loses highs (damp) and level (fb). Both strings of
     *  a clip share the write clock; the caller advances v.kp. */
    public double ksString(Voice v, float[] buf, double f, double lt, double k, double fb, double pick) {
        double N = Math.max(2, Math.min(KSN - 3, SR / f));
        double ex = 0;
        if (lt * f < 1.0) {
            v.ex += (0.05 + 0.95 * pick) * ((v.rng.nextDouble() * 2 - 1) - v.ex);
            ex = v.ex;
        }
        double rp = v.kp - N;
        while (rp < 0) rp += KSN;
        int i0 = (int) rp;
        double fr = rp - i0;
        int i1 = (i0 + 1) % KSN, im = (i0 + KSN - 1) % KSN;
        double a = buf[i0] * (1 - fr) + buf[i1] * fr;        // tap at N
        double b = buf[im] * (1 - fr) + buf[i0] * fr;        // tap at N+1
        double s = ex + fb * ((1 - k) * a + k * 0.5 * (a + b));
        buf[v.kp] = (float) s;
        return s;
    }

    public final double[] o1 = new double[6];
    /** Render one sample of the mix into out[0..1] and advance time. */
    public void render(List<Clip> cs, Clip solo, boolean[] mute, double[] tvol, double[] out) {
        double mixL = 0, mixR = 0, sendL = 0, sendR = 0, rvInL = 0, rvInR = 0;
        for (int ci = 0; ci < cs.size(); ci++) {
            Clip c = cs.get(ci);
            if (solo != null ? c != solo : (mute != null && mute[c.track])) continue;
            double lt = t - c.start;
            if (lt < 0 || lt >= c.dur) continue;
            Voice v = voices.computeIfAbsent(c, Voice::new);
            if (!clipSample(c, v, lt, tvol, o1)) continue;
            double sL = o1[0], sR = o1[1];
            trackAbs[c.track] = Math.max(trackAbs[c.track], Math.max(Math.abs(sL), Math.abs(sR)));
            mixL += sL; mixR += sR; sendL += o1[2]; sendR += o1[3]; rvInL += o1[4]; rvInR += o1[5];
        }
        post(mixL, mixR, sendL, sendR, rvInL, rvInR, out);
    }

    /** Renders n samples of the mix into outL / outR. With several clips and no sidechain in play (the
     *  duck couples clips within a sample), every clip's block is rendered on its own thread and the
     *  per-sample mix, sends and effects run afterwards in clip order, so the output is bit-identical
     *  to the sample-by-sample path; otherwise it falls back to that path. */
    public void renderBlock(List<Clip> cs, Clip solo, boolean[] mute, double[] tvol, double[] outL, double[] outR, int n) {
        boolean duck = false; int live = 0;
        for (Clip c : cs) {
            if (solo != null ? c != solo : (mute != null && mute[c.track])) continue;
            live++;
            int di = c.p.length - N_TAIL + 9;
            if (c.p[di] + (c.mod != null ? c.mod[di] : 0) > 0.005) duck = true;
        }
        if (duck || live < 2 || POOL_N < 2) {
            for (int i = 0; i < n; i++) { render(cs, solo, mute, tvol, o1); outL[i] = o1[0]; outR[i] = o1[1]; }
            return;
        }
        if (ts == null || ts.length < n + 1) ts = new double[n + 1];
        ts[0] = t;
        for (int i = 0; i < n; i++) ts[i + 1] = ts[i] + 1.0 / SR;   // the same accumulation post() does
        final ArrayList<Clip> act = new ArrayList<>();
        for (Clip c : cs) {
            if (solo != null ? c != solo : (mute != null && mute[c.track])) continue;
            if (c.start >= ts[n] || c.end() <= ts[0]) continue;
            voices.computeIfAbsent(c, Voice::new);
            act.add(c);
        }
        int m = act.size();
        if (cbuf == null || cbuf.length < m || cbuf[0].length < 6 * n) { cbuf = new double[Math.max(m, 8)][6 * n]; cact = new boolean[Math.max(m, 8)][n]; }
        ArrayList<java.util.concurrent.Callable<Void>> tasks = new ArrayList<>(m);
        final int nn = n;
        for (int ci = 0; ci < m; ci++) {
            final int k = ci; final Clip c = act.get(ci); final Voice v = voices.get(c);
            final double[] buf = cbuf[k]; final boolean[] on = cact[k];
            tasks.add(() -> {
                double[] o = new double[6];
                for (int i = 0; i < nn; i++) {
                    double lt = ts[i] - c.start;
                    if (lt < 0 || lt >= c.dur || !clipSample(c, v, lt, tvol, o)) { on[i] = false; continue; }
                    on[i] = true;
                    System.arraycopy(o, 0, buf, 6 * i, 6);
                }
                return null;
            });
        }
        try { for (var f : POOL.invokeAll(tasks)) f.get(); }
        catch (Exception e) { throw new RuntimeException(e); }
        double[] o = new double[2];
        for (int i = 0; i < n; i++) {
            double mixL = 0, mixR = 0, sendL = 0, sendR = 0, rvInL = 0, rvInR = 0;
            for (int ci = 0; ci < m; ci++) {
                if (!cact[ci][i]) continue;
                double[] b = cbuf[ci]; int j = 6 * i;
                double sL = b[j], sR = b[j + 1];
                int tr = act.get(ci).track;
                trackAbs[tr] = Math.max(trackAbs[tr], Math.max(Math.abs(sL), Math.abs(sR)));
                mixL += sL; mixR += sR; sendL += b[j + 2]; sendR += b[j + 3]; rvInL += b[j + 4]; rvInR += b[j + 5];
            }
            post(mixL, mixR, sendL, sendR, rvInL, rvInR, o);
            outL[i] = o[0]; outR[i] = o[1];
        }
    }
    public double[] ts; public double[][] cbuf; public boolean[][] cact;

    /** Pays a set of clips' first-use costs before anyone hears them: loads their partials analyses and recordings, and
     *  renders a copy of each through the live-modulation path for `seconds` in a throwaway engine, alone and then all
     *  together, audible, then faded to silence, then back (a layer going quiet takes its own branch, and a branch
     *  the JIT has never seen costs a recompile the first time it is taken), so every clip type's path is compiled. Blocking and CPU-heavy for a moment:
     *  call it off the audio thread when a family loads. Without it the first lock of a session stalls the audio thread
     *  while each path runs interpreted, and each analysis loads only when its layer first sounds. */
    static void primeLevel(Clip c, int i, int n) { c.mod[P_LEVEL] = i >= n / 2 && i < 3 * n / 4 ? -c.p[P_LEVEL] : 0; }   // audible, silent for a quarter, audible
    public static void prime(java.util.Collection<Clip> clips, double seconds) {
        ArrayList<Clip> cs = new ArrayList<>();
        for (Clip src : clips) {
            if (src.type == PARTIALS && src.file != null) Partials.partials(src, true);
            if (sampled(src)) Samples.sample(src.file);
            Clip c = copyClip(src);
            c.start = 0; c.dur = seconds; c.id = null; c.on = ON_NONE; c.lmute = false;
            if (c.p[P_LEVEL] < 0.01) c.p[P_LEVEL] = 0.5;
            c.mod = new double[c.p.length];
            cs.add(c);
        }
        int n = (int) (seconds * SR);
        double[] l = new double[BLOCK], r = new double[BLOCK];
        for (Clip c : cs) {   // one at a time: the sequential path
            Engine e = new Engine();
            for (int i = 0; i < n; i += BLOCK) { primeLevel(c, i, n); e.renderBlock(List.of(c), null, null, null, l, r, Math.min(BLOCK, n - i)); }
        }
        Engine e = new Engine();   // all together: the parallel path
        for (int i = 0; i < n; i += BLOCK) { for (Clip c : cs) primeLevel(c, i, n); e.renderBlock(cs, null, null, null, l, r, Math.min(BLOCK, n - i)); }
    }
    public static final int POOL_N = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
    public static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(POOL_N, r -> {
        Thread th = new Thread(r, "engine-worker"); th.setDaemon(true); th.setPriority(Thread.MAX_PRIORITY - 1); return th; });

    /** One sample of one clip: its stereo output and its echo / reverb sends into o[0..5]. False when it is silent. */
    public boolean clipSample(Clip c, Voice v, double lt, double[] tvol, double[] o) {
            double[] p = c.mod != null ? v.effective(c) : c.p;
            if (p[P_LEVEL] < 1e-4) return false;   // silent (a bench layer waiting for its cue): costs nothing
            double prog = lt / c.dur;
            int lb = p.length - N_TAIL;
            double kp = (c.keyed & KEY_PITCH) != 0 ? key : 0, ku = (c.keyed & KEY_FILTER) != 0 ? key * KEY_U : 0;
            double env = 1;
            if (p[P_ATT] > 1e-4) env = Math.min(1, lt / p[P_ATT]);
            if (p[P_REL] > 1e-4) env = Math.min(env, (c.dur - lt) / p[P_REL]);
            if (env <= 0) return false;
            double curve = p[lb + 5];
            if (curve != 0) env = Math.pow(env, Math.pow(8, curve));
            // per-clip LFO: phase runs in clip-local time; random shape is
            // hashed from the cycle count + seed so renders stay deterministic
            double lfo = 0, lfoAmp = p[lb + 3];
            if (p[lb + 1] != 0 || p[lb + 2] != 0 || lfoAmp != 0 || p[lb + 7] > 0.005) {
                // the LFO's phase: parked / steered by `lfo pos` when set; else an accumulated angle for live clips (a moving
                // rate glides) and the closed form for timeline clips (old renders stay byte-identical)
                double lpos = p[lb + 15], cyc;
                if (lpos >= 0) cyc = Math.min(1, lpos);
                else if (c.mod != null) { v.lfoAng += p[lb] / SR; cyc = v.lfoAng; }
                else cyc = lt * p[lb];
                double frac = cyc - Math.floor(cyc);
                lfo = switch ((int) Math.round(p[lb + 4])) {
                    case 1 -> 1 - 4 * Math.abs(frac - 0.5);
                    case 2 -> frac < 0.5 ? 1 : -1;
                    case 3 -> {
                        double h = Math.sin((Math.floor(cyc) + 1) * 12.9898 + (c.seed & 1023)) * 43758.5453;
                        yield 2 * (h - Math.floor(h)) - 1;
                    }
                    default -> Math.sin(2 * Math.PI * frac);
                };
            }
            double f0 = 110 * Math.pow(2, (p[P_PITCH] + kp + p[P_PSWP] * prog + lfo * p[lb + 1]) / 12.0);
            double sL = 0, sR = 0;

            switch (c.type) {
                case CLOUD -> {
                    int n = (int) Math.max(1, Math.min(NV, Math.round(p[NCOMMON])));
                    double gather = Math.max(0, Math.min(1, p[NCOMMON + 1] + p[NCOMMON + 6] * prog));
                    double drift = p[NCOMMON + 2], timbre = p[NCOMMON + 3];
                    int chord = chordIdx(p[NCOMMON + 4]);
                    double[] tl = CHORD_LOGS[chord];
                    double wr = 0.004 * drift * drift;
                    for (int vi = 0; vi < n; vi++) {
                        v.wander[vi] += (v.rng.nextDouble() - 0.5) * wr;
                        if (v.wander[vi] < W_LO) v.wander[vi] = 2 * W_LO - v.wander[vi];
                        if (v.wander[vi] > W_HI) v.wander[vi] = 2 * W_HI - v.wander[vi];
                        double f = f0 * Math.exp(v.wander[vi] + (tl[vi] - v.wander[vi]) * gather) * (1 + v.det[vi]);
                        v.ph[vi] += f / SR; if (v.ph[vi] >= 1) v.ph[vi] -= 1;
                        double s = osc(v.ph[vi], timbre) * (n == 1 ? 1 : 1 - 0.55 * vi / (n - 1.0));
                        double pv = (v.vpan[vi] + 1) * Math.PI / 4;
                        sL += s * Math.cos(pv); sR += s * Math.sin(pv);
                    }
                    sL /= n * 0.5; sR /= n * 0.5;
                    v.phSub += f0 * 0.5 / SR; if (v.phSub >= 1) v.phSub -= 1;
                    double sub = osc(v.phSub, 2.0) * p[NCOMMON + 5];
                    sL += sub; sR += sub;
                }
                case TONE -> {
                    double interval = p[NCOMMON], timbre = p[NCOMMON + 1],
                           det = p[NCOMMON + 2] * 0.01, shim = p[NCOMMON + 3];
                    v.j = v.j * 0.9995 + (v.rng.nextDouble() - 0.5) * 0.004;
                    double wob = 1 + v.j * shim * 1.5;
                    double f1 = f0 * wob, f2 = f0 * Math.pow(2, interval / 12.0) * wob;
                    v.ph1 += f1 / SR; if (v.ph1 >= 1) v.ph1 -= 1;
                    v.ph2 += f2 / SR; if (v.ph2 >= 1) v.ph2 -= 1;
                    // FM: phase-modulate the pair; depth rides the envelope so
                    // percussive clips strike bright and mellow into the ring
                    double m1 = 0, m2 = 0, fmDepth = p[NCOMMON + 5] * env;
                    if (fmDepth > 1e-4) {
                        double fmRatio = p[NCOMMON + 4];
                        v.phM1 += f1 * fmRatio / SR; if (v.phM1 >= 1) v.phM1 -= 1;
                        v.phM2 += f2 * fmRatio / SR; if (v.phM2 >= 1) v.phM2 -= 1;
                        m1 = fmDepth * Math.sin(2 * Math.PI * v.phM1) / (2 * Math.PI);
                        m2 = fmDepth * Math.sin(2 * Math.PI * v.phM2) / (2 * Math.PI);
                    }
                    double a = osc(wrap01(v.ph1 + m1), timbre), b = osc(wrap01(v.ph2 + m2), timbre);
                    if (det > 1e-5) {
                        v.ph3 += f1 * (1 + det) / SR; if (v.ph3 >= 1) v.ph3 -= 1;
                        v.ph4 += f2 * (1 - det) / SR; if (v.ph4 >= 1) v.ph4 -= 1;
                        a = (a + osc(v.ph3, timbre)) * 0.7;
                        b = (b + osc(v.ph4, timbre)) * 0.7;
                    }
                    // tone 1 left, tone 2 right — they draw the Lissajous
                    sL = a * 0.6; sR = b * 0.6;
                    // additive harmonic bank on the base pitch, centred, with the
                    // partials clip's harmonic controls — a stand-in "bed" when a
                    // recording has run out of range (bank 0 = exactly the old tone)
                    double bank = p[NCOMMON + TB_BANK];
                    if (bank > 0) {
                        boolean ch = harmChanged(v, p[NCOMMON + TB_ODD], p[NCOMMON + TB_TILT], 1,
                                                 p[NCOMMON + TB_STRETCH], p[NCOMMON + TB_GATHER], chordIdx(p[NCOMMON + TB_CHORD]), 0, 0.03);
                        if (v.hg == null || v.hg.length != TB_HARM || ch) {
                            if (v.hg == null || v.hg.length != TB_HARM) {
                                v.hg = new double[TB_HARM]; v.hf = new double[TB_HARM]; v.bph = new double[TB_HARM];
                                v.hsh = new double[TB_HARM]; v.hsr = new double[TB_HARM];
                                for (int h = 0; h < TB_HARM; h++) { v.bph[h] = hash01(c.seed, h, 3); v.hsh[h] = hash01(c.seed, h, 1); v.hsr[h] = 0.05 + 0.35 * hash01(c.seed, h, 2); }
                            }
                            for (int h = 1; h <= TB_HARM; h++) {
                                v.hg[h - 1] = harmGain(h, h, v.cOdd, v.cTilt, 1) / h;   // 1/h: a saw-like series before the controls
                                v.hf[h - 1] = harmFreq(h, v.cStr, v.cGat, v.cChord) * h;
                            }
                        }
                        double bshim = p[NCOMMON + TB_SHIM], sum = 0;
                        for (int h = 0; h < TB_HARM; h++) {
                            double fh = f0 * v.hf[h];
                            if (bshim > 0) { v.hsh[h] += v.hsr[h] / SR; fh *= 1 + bshim * 0.012 * Math.sin(2 * Math.PI * v.hsh[h]); }
                            if (fh >= SR * 0.5) continue;
                            v.bph[h] += fh / SR; if (v.bph[h] >= 1) v.bph[h] -= 1;
                            sum += v.hg[h] * Math.sin(2 * Math.PI * v.bph[h]);
                        }
                        double bs = bank * 0.6 * sum * 0.45;
                        sL += bs; sR += bs;
                    }
                }
                case NOISE -> {
                    double w1 = (v.rng.nextDouble() * 2 - 1) * 0.8;
                    double w2 = (v.rng.nextDouble() * 2 - 1) * 0.8;
                    double color = p[NCOMMON];
                    if (color > 0.01) {
                        // one-pole lowpass with RMS makeup: white -> pink-ish -> brown
                        double k = Math.pow(10, -3.2 * color);
                        double mk = Math.sqrt((2 - k) / k);
                        v.nl1 += k * (w1 - v.nl1);
                        v.nl2 += k * (w2 - v.nl2);
                        sL = v.nl1 * mk; sR = v.nl2 * mk;
                    } else { sL = w1; sR = w2; }
                }
                case SPARKLE -> {
                    double density = p[NCOMMON], dec = p[NCOMMON + 1], spread = p[NCOMMON + 2];
                    int range = (int) Math.max(0, Math.round(p[NCOMMON + 3]));
                    if (lt >= v.nextPing) {
                        v.nextPing = lt + 1.0 / density;
                        for (int k = 0; k < v.pf.length; k++) if (v.pa[k] < 0.01) {
                            v.pf[k] = f0 * PENTA[v.rng.nextInt(PENTA.length)] * (1 << v.rng.nextInt(range + 1));
                            v.pp[k] = 0; v.pm[k] = 0; v.pa[k] = 0.5;
                            v.ppan[k] = (v.rng.nextDouble() * 2 - 1) * spread;
                            break;
                        }
                    }
                    double kDec = Math.exp(-1.0 / (dec * SR));
                    double fmRatio = p[NCOMMON + 4], fmDepth = p[NCOMMON + 5];
                    for (int k = 0; k < v.pf.length; k++) if (v.pa[k] >= 0.01) {
                        v.pp[k] += v.pf[k] / SR; if (v.pp[k] >= 1) v.pp[k] -= 1;
                        v.pa[k] *= kDec;
                        double mm = 0;
                        if (fmDepth > 1e-4) {   // each ping's FM brightness decays with its own ring-out
                            v.pm[k] += v.pf[k] * fmRatio / SR; if (v.pm[k] >= 1) v.pm[k] -= 1;
                            mm = fmDepth * (v.pa[k] / 0.5) * Math.sin(2 * Math.PI * v.pm[k]);
                        }
                        double s = Math.sin(2 * Math.PI * v.pp[k] + mm) * v.pa[k];
                        double sp = (v.ppan[k] + 1) * Math.PI / 4;
                        sL += s * Math.cos(sp); sR += s * Math.sin(sp);
                    }
                }
                case PLUCK -> {
                    double damp = p[NCOMMON], sus = p[NCOMMON + 1], pick = p[NCOMMON + 2],
                           itv = p[NCOMMON + 3];
                    double k = 0.05 + 0.95 * damp;      // per-cycle high-freq loss
                    double fb = 0.9 + 0.099 * sus;      // overall ring length
                    double s = ksString(v, v.ks, f0, lt, k, fb, pick);
                    if (itv != 0)   // second string: summed BEFORE drive, so a
                                    // power chord distorts as one (intermodulation)
                        s += 0.9 * ksString(v, v.ks2, f0 * Math.pow(2, itv / 12.0) * 1.0012, lt, k, fb, pick);
                    v.kp = (v.kp + 1) % KSN;
                    // DC blocker (~7 Hz): the excitation burst's random DC
                    // component would otherwise circulate and bias the drive
                    v.dcp += 0.001 * (s - v.dcp);
                    sL = (s - v.dcp) * 0.9; sR = sL;
                }
                case SAMPLE -> {
                    if (c.file == null) break;
                    float[][] smp = sample(c.file);
                    int n = smp[0].length;
                    boolean loop = p[NCOMMON] >= 0.5;
                    double base = p[NCOMMON + 1] * n, ratio = f0 / 110.0, speed = p[NCOMMON + 3];
                    if (p[NCOMMON + 2] < 0.5) {
                        // tape: pitch IS playback speed, like a classic sampler
                        double rate = ratio * speed;
                        if (v.sp < 0) v.sp = lt * SR * rate;   // seek landed mid-clip
                        double pos = base + v.sp;
                        v.sp += rate;
                        if (loop) pos = pos % n;
                        else if (pos >= n - 1) break;          // one-shot: silent after the end
                        sL = smpAt(smp[0], pos, n); sR = smpAt(smp[1], pos, n);
                    } else {
                        // keep len: the source position advances at `speed` no matter
                        // the pitch; two overlapping grains read from around it at
                        // the pitch ratio (see alignGrain for why it doesn't flutter)
                        if (v.sp < 0) {
                            v.sp = lt * SR * speed;
                            v.gpos[0] = base + v.sp; v.gage[0] = 0;
                            v.gpos[1] = -1; v.gage[1] = GHALF;   // idle until grain 0 is half-way
                            v.gFirst = true;
                        }
                        double src = base + v.sp;
                        v.sp += speed;
                        if (!loop && src >= n - 1) break;
                        for (int g = 0; g < 2; g++)   // spawn first, so the alignment sees
                            if (v.gage[g] >= GLEN) {  // the other grain's NEXT read position
                                v.gage[g] = 0;
                                v.gpos[g] = alignGrain(smp, n, src, v.gpos[1 - g], ratio, loop, v.gref);
                                v.gFirst = false;
                            }
                        for (int g = 0; g < 2; g++) {
                            double pos = v.gpos[g];
                            if (pos >= 0) {
                                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * v.gage[g] / GLEN);
                                if (v.gFirst && v.gage[g] < GHALF) w = 1;   // the very first grain doesn't fade in
                                if (loop) pos = ((pos % n) + n) % n;
                                if (loop || pos < n - 1) {
                                    sL += w * smpAt(smp[0], pos, n);
                                    sR += w * smpAt(smp[1], pos, n);
                                }
                                v.gpos[g] += ratio;
                            }
                            v.gage[g]++;
                        }
                    }
                }
                case PARTIALS -> {
                    if (c.file == null) break;
                    long fl = Math.round(p[NCOMMON + PA_FLOOR]), ml = Math.round(p[NCOMMON + PA_MINLEN]);
                    if (c.pa == null || fl != c.paFloor || ml != c.paMin) {
                        if (c.pa == null && fl == c.paFloor && ml == c.paMin && (c.paRetry++ & 2047) != 0) break;   // pending: poll, don't churn
                        c.pa = partials(c, false); c.paFloor = fl; c.paMin = ml;
                    }
                    Partials pa = c.pa;
                    if (pa == null) break;                 // still analyzing: silent until ready
                    float[][] smp = pa.res;
                    int n = smp[0].length;
                    boolean loop = p[NCOMMON + PA_LOOP] >= 0.5;
                    double base = p[NCOMMON + PA_START] * n, ratio = f0 / 110.0, speed = p[NCOMMON + PA_SPEED];
                    double gS = p[NCOMMON + PA_SINES], gR = p[NCOMMON + PA_RESID];
                    if (v.sp < 0) {
                        v.sp = lt * SR * speed;
                        v.gpos[0] = base + v.sp; v.gage[0] = 0;
                        v.gpos[1] = -1; v.gage[1] = GHALF;
                        v.gFirst = true;
                    }
                    double src = base + v.sp;
                    v.sp += speed;
                    if (!loop && src >= n - 1) break;
                    // residual — the recording's noise, breath and crackle — is NEVER
                    // transposed. At speed 1 it is read straight (the grain aligner finds
                    // spurious matches in noise and would sum two grains incoherently:
                    // -3 dB and a smear); time-stretching goes through the grains at ratio 1.
                    if (speed == 1.0) {
                        double pos = loop ? ((src % n) + n) % n : src;
                        if (gR > 0 && (loop || pos < n - 1) && pos >= 0) {
                            sL += gR * smpAt(smp[0], pos, n);
                            sR += gR * smpAt(smp[1], pos, n);
                        }
                    } else {
                    for (int g = 0; g < 2; g++)
                        if (v.gage[g] >= GLEN) {
                            v.gage[g] = 0;
                            v.gpos[g] = alignGrain(smp, n, src, v.gpos[1 - g], 1.0, loop, v.gref);
                            v.gFirst = false;
                        }
                    for (int g = 0; g < 2; g++) {
                        double pos = v.gpos[g];
                        if (pos >= 0) {
                            double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * v.gage[g] / GLEN);
                            if (v.gFirst && v.gage[g] < GHALF) w = 1;
                            if (loop) pos = ((pos % n) + n) % n;
                            if (gR > 0 && (loop || pos < n - 1)) {
                                sL += gR * w * smpAt(smp[0], pos, n);
                                sR += gR * w * smpAt(smp[1], pos, n);
                            }
                            v.gpos[g] += 1.0;
                        }
                        v.gage[g]++;
                    }
                    }
                    // partials: an oscillator bank reads the tracked sinusoids at the same
                    // source position, transposed by the clip's pitch (and the key)
                    if (gS > 0 && pa.nTracks > 0) {
                        double fr = src / PA_HOP;
                        if (loop) fr = ((fr % pa.nFrames) + pa.nFrames) % pa.nFrames;
                        int fi = (int) fr;
                        if (fi >= 0 && fi < pa.active.length) {
                            int nt = pa.nTracks;
                            if (v.pph == null || v.pph.length != nt) {
                                v.pph = new double[nt];
                                for (int k = 0; k < nt; k++) v.pph[k] = v.rng.nextDouble() * 2 * Math.PI;
                            }
                            // harmonic controls: per-track gain and frequency multipliers, invalidated when a control moves (or
                            // the analysis changes) and recomputed per track only when that track next sounds: a dense layer has
                            // thousands of tracks but a few hundred active, and a gliding control used to rebuild them all up to a
                            // dozen times a block. Same values, same moment of use, so the output is unchanged (neutral = 1.0)
                            boolean ch = harmChanged(v, p[NCOMMON + PA_ODD], p[NCOMMON + PA_TILT], p[NCOMMON + PA_PURITY],
                                                     p[NCOMMON + PA_STRETCH], p[NCOMMON + PA_GATHER], chordIdx(p[NCOMMON + PA_CHORD]),
                                                     p[NCOMMON + PA_ROOT], p[NCOMMON + PA_HTOL] * 0.01);
                            if (v.hg == null || v.hg.length != nt || ch || v.hpa != pa) {
                                if (v.hg == null || v.hg.length != nt) { v.hg = new double[nt]; v.hf = new double[nt]; v.hgen = new int[nt]; }
                                v.hpa = pa;
                                v.hroot = partialsRoot(pa, v.cShift);   // classification root: the estimate, shifted
                                v.gen++;                                // every track's entry is stale now
                            }
                            double shim = p[NCOMMON + PA_SHIM];
                            if (shim > 0 && (v.hsh == null || v.hsh.length != nt)) {
                                v.hsh = new double[nt]; v.hsr = new double[nt];
                                for (int k = 0; k < nt; k++) { v.hsh[k] = hash01(c.seed, k, 1); v.hsr[k] = 0.05 + 0.35 * hash01(c.seed, k, 2); }
                            }
                            double ps = 0;
                            for (int k : pa.active[fi]) {
                                PTrack tr = pa.tracks[k];
                                double tp = fr - tr.start;
                                if (tp < 0 || tp > tr.len - 1) continue;
                                int i = Math.min((int) tp, tr.len - 2);
                                double q = tp - i;
                                if (v.hgen[k] != v.gen) {
                                    double r = v.hroot > 0 ? tr.fmed / v.hroot : 0;
                                    int h = harmNum(r, v.cTol);
                                    v.hg[k] = harmGain(r, h, v.cOdd, v.cTilt, v.cPur);
                                    v.hf[k] = harmFreq(r, v.cStr, v.cGat, v.cChord);
                                    v.hgen[k] = v.gen;
                                }
                                double mult = ratio * v.hf[k];
                                if (shim > 0) { v.hsh[k] += v.hsr[k] / SR; mult *= 1 + shim * 0.012 * Math.sin(2 * Math.PI * v.hsh[k]); }
                                double f = (tr.freq[i] * (1 - q) + tr.freq[i + 1] * q) * mult;
                                if (f >= SR * 0.5) continue;
                                double a = (tr.amp[i] * (1 - q) + tr.amp[i + 1] * q) * v.hg[k];
                                double ph = v.pph[k] + 2 * Math.PI * f / SR;
                                if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
                                v.pph[k] = ph;
                                ps += a * Math.cos(ph);
                            }
                            sL += gS * ps; sR += gS * ps;
                        }
                    }
                }
                case CHOIR -> {
                    if (c.file == null) break;
                    float[][] smp = sample(c.file);
                    int n = smp[0].length;
                    int nv = (int) Math.max(1, Math.min(NV, Math.round(p[NCOMMON + CH_VOICES])));
                    double gather = Math.max(0, Math.min(1, p[NCOMMON + CH_GATHER] + p[NCOMMON + CH_GSWP] * prog));
                    double drift = p[NCOMMON + CH_DRIFT], speed = p[NCOMMON + CH_SPEED];
                    double R = p[NCOMMON + CH_WANDER] * Math.log(2) / 12;
                    double base = p[NCOMMON + CH_START] * n, scat = p[NCOMMON + CH_SCATTER] * n;
                    int chord = chordIdx(p[NCOMMON + CH_CHORD]);
                    double[] tl = CHOIR_LOGS[chord];
                    double ratio0 = f0 / 110.0;
                    // the walk rate scales with the wander band so drift feels the same at any width
                    double wr = 0.004 * drift * drift * (2 * R / (W_HI - W_LO));
                    if (v.sp < 0) {
                        v.sp = lt * SR * speed;   // seek landed mid-clip
                        for (int vi = 0; vi < NV; vi++) {
                            v.cgpos[vi][0] = base + v.coff[vi] * scat + v.sp; v.cgage[vi][0] = 0;
                            v.cgpos[vi][1] = -1; v.cgage[vi][1] = GHALF;
                            v.cgFirst[vi] = true;
                        }
                    }
                    double sp = v.sp;
                    v.sp += speed;
                    double gain = 1 / Math.sqrt(nv);
                    for (int vi = 0; vi < nv; vi++) {
                        if (R > 1e-9) {
                            v.wander[vi] += (v.rng.nextDouble() - 0.5) * wr;
                            if (v.wander[vi] < -R) v.wander[vi] = -2 * R - v.wander[vi];
                            if (v.wander[vi] > R) v.wander[vi] = 2 * R - v.wander[vi];
                        } else v.wander[vi] = 0;
                        double ratio = ratio0 * Math.exp(v.wander[vi] + (tl[vi] - v.wander[vi]) * gather) * (1 + v.det[vi]);
                        double src = base + v.coff[vi] * scat + sp;
                        double[] gpos = v.cgpos[vi]; int[] gage = v.cgage[vi];
                        double a = 0, b = 0;
                        for (int g = 0; g < 2; g++)
                            if (gage[g] >= GLEN) {
                                gage[g] = 0;
                                gpos[g] = alignGrain(smp, n, src, gpos[1 - g], ratio, true, v.gref);
                                v.cgFirst[vi] = false;
                            }
                        for (int g = 0; g < 2; g++) {
                            double pos = gpos[g];
                            if (pos >= 0) {
                                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * gage[g] / GLEN);
                                if (v.cgFirst[vi] && gage[g] < GHALF) w = 1;
                                pos = ((pos % n) + n) % n;
                                a += w * smpAt(smp[0], pos, n);
                                b += w * smpAt(smp[1], pos, n);
                                gpos[g] += ratio;
                            }
                            gage[g]++;
                        }
                        double amp = gain * (nv == 1 ? 1 : 1 - 0.55 * vi / (nv - 1.0));
                        double pv = (v.vpan[vi] + 1) * Math.PI / 4;   // balance, like cloud's voice placement
                        sL += a * amp * Math.cos(pv) * 1.3; sR += b * amp * Math.sin(pv) * 1.3;
                    }
                }
            }

            // drive: waveshaping distortion ahead of the filter (which then
            // plays the cabinet). Bypassed at 0 so clean clips stay clean.
            double drive = p[P_DRIVE];
            if (drive > 0.01) {
                double dg = 1 + 24 * drive, mk = 1 - 0.45 * drive;
                sL = Math.tanh(sL * dg) * mk;
                sR = Math.tanh(sR * dg) * mk;
            }

            // flanger: the signal plus itself a few swept milliseconds ago —
            // a comb filter whose notches ride the clip's LFO
            double flMix = p[lb + 7];
            if (flMix > 0.005) {
                double flFb = p[lb + 8];
                double fpos = p[lb + 16];
                double d = (0.0012 + 0.0038 * (fpos >= 0 ? Math.min(1, fpos) : 0.5 + 0.5 * lfo)) * SR;   // 1.2 .. 5 ms: `flange pos` parks it, else the LFO
                double rp = v.fp - d;
                while (rp < 0) rp += FLN;
                int i0 = (int) rp;
                double fr = rp - i0;
                int i1 = (i0 + 1) % FLN;
                double d1 = v.fl1[i0] * (1 - fr) + v.fl1[i1] * fr;
                double d2 = v.fl2[i0] * (1 - fr) + v.fl2[i1] * fr;
                v.fl1[v.fp] = (float) (sL + d1 * flFb);
                v.fl2[v.fp] = (float) (sR + d2 * flFb);
                v.fp = (v.fp + 1) % FLN;
                sL += d1 * flMix;
                sR += d2 * flMix;
            }

            // phaser: `ph stages` first-order all-passes in series, their turnover
            // frequency swept 200 Hz .. 3.2 kHz by a slow sine; mixed with the
            // dry signal the phase cancellations become moving notches. A
            // little feedback gives the notches a resonant, vocal edge.
            double phMix = p[lb + 11];
            if (phMix > 0.005) {
                // the sweep position: steered directly by `ph pos` when it is set (a bound signal moves the notches, smoothed
                // like every live param); else the LFO — an accumulated angle for live clips so a moving rate glides, the
                // closed form for plain timeline clips so old renders stay byte-identical
                double pos = p[lb + 14], sw;
                if (pos >= 0) sw = Math.min(1, pos);
                else if (c.mod != null) { v.phAng += p[lb + 12] / SR; sw = 0.5 + 0.5 * Math.sin(2 * Math.PI * v.phAng); }
                else sw = 0.5 + 0.5 * Math.sin(2 * Math.PI * lt * p[lb + 12]);
                double fc = 200 * Math.pow(16, sw);
                double tn = Math.tan(Math.PI * fc / SR);
                double a = (tn - 1) / (tn + 1);
                double xL = sL + v.phFbL * 0.45, xR = sR + v.phFbR * 0.45;
                int stages = (int) Math.max(1, Math.min(PH_MAX, Math.round(p[lb + 13])));
                for (int st = 0; st < stages; st++) {
                    double yL = a * xL + v.apx[st] - a * v.apy[st];
                    v.apx[st] = xL; v.apy[st] = yL; xL = yL;
                    int s2 = st + PH_MAX;
                    double yR = a * xR + v.apx[s2] - a * v.apy[s2];
                    v.apx[s2] = xR; v.apy[s2] = yR; xR = yR;
                }
                v.phFbL = xL; v.phFbR = xR;
                sL = (sL + phMix * xL) / (1 + 0.5 * phMix);
                sR = (sR + phMix * xR) / (1 + 0.5 * phMix);
            }

            // Per-clip state-variable filter, cutoff swept over the clip.
            // Runs at TWO half-steps per sample: the plain Chamberlin SVF
            // goes unstable above ~6 kHz at low resonance (fS² + 2·fS·q1
            // must stay < 4) and sprays Nyquist junk; half-stepping doubles
            // the stable range past our 10 kHz max. The tanh on the band
            // state still bounds high-Q settings.
            double u = Math.max(0, Math.min(1, p[P_CUT] + ku + p[P_CSWP] * prog + lfo * p[lb + 2]));
            double fc = 40 * Math.pow(250, u);   // 40 Hz .. 10 kHz
            double fS = 2 * Math.sin(Math.PI * fc / (2 * SR));
            double q1 = 1.0 / (0.5 + 7.5 * p[P_RES]);
            int mode = (int) Math.round(p[P_MODE]);
            double hi1 = 0, hi2 = 0;
            for (int os = 0; os < 2; os++) {
                v.lo1 += fS * v.b1;
                hi1 = sL - v.lo1 - q1 * v.b1;
                v.b1 += fS * hi1; v.b1 = Math.tanh(v.b1 * 0.6) / 0.6;
                v.lo2 += fS * v.b2;
                hi2 = sR - v.lo2 - q1 * v.b2;
                v.b2 += fS * hi2; v.b2 = Math.tanh(v.b2 * 0.6) / 0.6;
            }
            sL = mode == 0 ? v.lo1 : mode == 1 ? v.b1 : hi1;
            sR = mode == 0 ? v.lo2 : mode == 1 ? v.b2 : hi2;

            double pan = Math.max(-1, Math.min(1, p[P_PAN] + p[P_PANSWP] * prog));
            double gL = pan <= 0 ? 1 : 1 - pan, gR = pan >= 0 ? 1 : 1 + pan;
            double amp = env * p[P_LEVEL] * (tvol == null ? 1 : tvol[c.track])
                       * (1 - lfoAmp * 0.5 * (1 - lfo));   // tremolo: depth 1 gates fully
            int duckFrom = (int) Math.round(p[lb + 10]);
            if (duckFrom > 0 && p[lb + 9] > 0.005)   // sidechain: full dip once the key track passes -12 dBFS
                amp *= 1 - p[lb + 9] * Math.min(1, trackEnv[duckFrom - 1] * 4);
            sL *= amp * gL; sR *= amp * gR;
            o[0] = sL; o[1] = sR; o[2] = sL * p[P_ECHO]; o[3] = sR * p[P_ECHO]; o[4] = sL * p[lb + 6]; o[5] = sR * p[lb + 6];
            return true;
    }

    /** The shared tail of a sample: sidechain envelopes, the ping-pong delay, the room, the limiter; advances time. */
    public void post(double mixL, double mixR, double sendL, double sendR, double rvInL, double rvInR, double[] out) {
        // sidechain envelopes: fast up, slow down, ready for the next sample
        for (int tr = 0; tr < TRACKS; tr++) {
            double a = trackAbs[tr];
            trackEnv[tr] += (a - trackEnv[tr]) * (a > trackEnv[tr] ? DK_ATT : DK_REL);
            trackAbs[tr] = 0;
        }
        // shared ping-pong delay; clips feed it via their echo send
        double dl = dlyL[dpL], dr = dlyR[dpR];
        dlyL[dpL] = (float) (sendL + dr * 0.5);
        dlyR[dpR] = (float) (sendR + dl * 0.5);
        dpL = (dpL + 1) % dlyL.length; dpR = (dpR + 1) % dlyR.length;
        // shared reverb, fed by the clips' reverb sends
        double wetL = 0, wetR = 0;
        for (int i = 0; i < COMB.length; i++) {
            float[] b = cvL[i]; int cp = cpL[i];
            double o = b[cp];
            cfL[i] = o * (1 - RV_DAMP) + cfL[i] * RV_DAMP;
            b[cp] = (float) (rvInL + cfL[i] * RV_FB);
            cpL[i] = (cp + 1) % b.length;
            wetL += o;
            b = cvR[i]; cp = cpR[i];
            o = b[cp];
            cfR[i] = o * (1 - RV_DAMP) + cfR[i] * RV_DAMP;
            b[cp] = (float) (rvInR + cfR[i] * RV_FB);
            cpR[i] = (cp + 1) % b.length;
            wetR += o;
        }
        wetL /= COMB.length; wetR /= COMB.length;
        for (int i = 0; i < ALLP.length; i++) {
            float[] b = avL[i]; int ap = apL[i];
            double o = b[ap];
            b[ap] = (float) (wetL + o * 0.5);
            apL[i] = (ap + 1) % b.length;
            wetL = o - wetL * 0.5;
            b = avR[i]; ap = apR[i];
            o = b[ap];
            b[ap] = (float) (wetR + o * 0.5);
            apR[i] = (ap + 1) % b.length;
            wetR = o - wetR * 0.5;
        }
        inPeak = Math.max(inPeak, Math.max(Math.abs(mixL + dl + wetL * 0.9), Math.abs(mixR + dr + wetR * 0.9)));   // pre-limiter level, for the SAT meter
        out[0] = Math.tanh((mixL + dl + wetL * 0.9) * 1.1) * 0.85;
        out[1] = Math.tanh((mixR + dr + wetR * 0.9) * 1.1) * 0.85;
        t += 1.0 / SR;
    }
}
