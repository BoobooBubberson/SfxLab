package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.SfxFormat.*;

/** A sines + residual model of a recording, and the static machinery that makes and caches them. */
public class Partials {
    public int nFrames, nTracks; public float[][] res; public PTrack[] tracks; public int[][] active; public double f0, share;   // f0: fundamental estimate (0 = none); share: sines / (sines + residual) energy

    /** Where analyses come from: by default computed here; the GUI puts its disk cache in front, the mod can load baked ones. */
    public interface Source { Partials analyze(String file, double floorDb, double minLenMs); }
    public static volatile Source source = Partials::analyzePartials;

    // ---- partials: a sines + residual model of a recording (spectral modeling
    // synthesis, Serra & Smith). Every stable sinusoid is tracked through a
    // 2048/256 Hann STFT; the tracked peaks are notched out of the spectrogram
    // to leave the residual (noise, breath, crackle); an oscillator bank replays
    // the partials at any transposition over the untouched residual. Same
    // algorithm and constants as tools/partials.py. Analysis runs once per
    // (file, floor, min len) on a worker thread — synchronously for --render —
    // and is cached for the session.
    public static final int PA_N = 2048, PA_HOP = 256, PA_MAXPK = 40, PA_GAP = 3;
    public static final double PA_FMIN = 40, PA_FMAX = 12000, PA_TOL = 0.06, PA_RANGE = 60;
    public static final java.util.concurrent.ConcurrentHashMap<String, Partials> PARTS = new java.util.concurrent.ConcurrentHashMap<>();
    public static final Set<String> PARTS_PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Any recording-based clip can be analysed (sample / choir clips use the
    // default thresholds) — that is where their pitch estimate comes from.
    public static double paFloor(Clip c) { return c.type == PARTIALS ? Math.round(c.p[NCOMMON + PA_FLOOR]) : 14; }
    public static double paMinLen(Clip c) { return c.type == PARTIALS ? Math.round(c.p[NCOMMON + PA_MINLEN]) : 46; }
    public static String partKey(String file, double fl, double ml) { return file + "|" + Math.round(fl) + "|" + Math.round(ml); }
    public static String partKey(Clip c) { return partKey(c.file, paFloor(c), paMinLen(c)); }
    public static Partials partials(Clip c, boolean sync) { return partials(c.file, paFloor(c), paMinLen(c), sync); }

    public static final int PARTS_MAGIC = 0x50415254;
    /** The .parts format (version 1): header, the stereo residual as floats, then every track. */
    public static void write(DataOutputStream o, Partials pa) throws IOException {
        o.writeInt(PARTS_MAGIC); o.writeInt(1);
        o.writeInt(pa.nFrames); o.writeInt(pa.nTracks); o.writeDouble(pa.f0); o.writeDouble(pa.share);
        o.writeInt(pa.res[0].length);
        for (int ch = 0; ch < 2; ch++) for (float x : pa.res[ch]) o.writeFloat(x);
        o.writeInt(pa.tracks.length);
        for (PTrack t : pa.tracks) {
            o.writeInt(t.start); o.writeInt(t.len); o.writeFloat(t.fmed); o.writeFloat(t.ratio); o.writeInt(t.harm);
            o.writeInt(t.freq.length); for (float x : t.freq) o.writeFloat(x);
            o.writeInt(t.amp.length); for (float x : t.amp) o.writeFloat(x);
        }
    }
    public static Partials read(DataInputStream in) throws IOException {
        if (in.readInt() != PARTS_MAGIC || in.readInt() != 1) throw new IOException("not a partials cache");
        Partials pa = new Partials();
        pa.nFrames = in.readInt(); pa.nTracks = in.readInt(); pa.f0 = in.readDouble(); pa.share = in.readDouble();
        int n = in.readInt();
        pa.res = new float[2][n];
        for (int ch = 0; ch < 2; ch++) for (int i = 0; i < n; i++) pa.res[ch][i] = in.readFloat();
        int nt = in.readInt();
        pa.tracks = new PTrack[nt];
        List<List<Integer>> act = new ArrayList<>();
        for (int f = 0; f < pa.nFrames; f++) act.add(new ArrayList<>());
        for (int k = 0; k < nt; k++) {
            PTrack t = new PTrack();
            t.start = in.readInt(); t.len = in.readInt(); t.fmed = in.readFloat(); t.ratio = in.readFloat(); t.harm = in.readInt();
            t.freq = new float[in.readInt()]; for (int i = 0; i < t.freq.length; i++) t.freq[i] = in.readFloat();
            t.amp = new float[in.readInt()]; for (int i = 0; i < t.amp.length; i++) t.amp[i] = in.readFloat();
            pa.tracks[k] = t;
            for (int f = Math.max(0, t.start); f < Math.min(pa.nFrames, t.start + t.len); f++) act.get(f).add(k);
        }
        pa.active = new int[pa.nFrames][];
        for (int f = 0; f < pa.nFrames; f++) pa.active[f] = act.get(f).stream().mapToInt(Integer::intValue).toArray();
        return pa;
    }

    // ---- the baked form, for shipping: the tracks, quantised far below hearing and deflated (a .ptk file), beside the residual as ordinary audio (a mono
    // ogg the host decodes, as it decodes any recording). The residual is noise, breath and crackle, so lossy coding
    // costs nothing audible, and it is ~85 % of an analysis stored raw.
    static final int BAKED_MAGIC = 0x50544B32;   // "PTK2"
    /** Writes the tracks and header, then closes out; resLen is the residual's length in samples, resGain what its audio was divided by. */
    public static void writeBaked(OutputStream out, Partials pa, int resLen, float resGain) throws IOException {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new java.util.zip.DeflaterOutputStream(out, new java.util.zip.Deflater(9)), 1 << 16));
        o.writeInt(BAKED_MAGIC); o.writeInt(1);
        o.writeInt(pa.nFrames); o.writeInt(pa.nTracks); o.writeDouble(pa.f0); o.writeDouble(pa.share);
        o.writeInt(resLen); o.writeFloat(resGain);
        o.writeInt(pa.tracks.length);
        for (PTrack t : pa.tracks) {   // frequencies as 0.1-cent steps from the track's median, amplitudes as 0.01 dB, each delta-coded
            o.writeInt(t.start); o.writeInt(t.len); o.writeFloat(t.fmed); o.writeFloat(t.ratio); o.writeInt(t.harm);
            short prev = 0;
            for (float x : t.freq) { short c = (short) centsQ(x, t.fmed); o.writeShort((short) (c - prev)); prev = c; }
            prev = 0;
            for (float x : t.amp) { short q = ampQ(x); o.writeShort((short) (q - prev)); prev = q; }   // 16-bit wraparound: every step exact
        }
        o.close();   // finishes the deflate stream (and closes out)
    }
    /** Reads a .ptk; residual is its .res.ogg decoded at Sfx.SR as the plain mono signal it was encoded as (not through
     *  the Samples.Loader contract's centre pan), trimmed or padded to the baked length and scaled back by the baked gain.
     *  The residual plays on both channels. */
    public static Partials readBaked(InputStream ptk, float[] residual) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new java.util.zip.InflaterInputStream(ptk), 1 << 16));
        if (in.readInt() != BAKED_MAGIC || in.readInt() != 1) throw new IOException("not a baked partials file");
        Partials pa = new Partials();
        pa.nFrames = in.readInt(); pa.nTracks = in.readInt(); pa.f0 = in.readDouble(); pa.share = in.readDouble();
        int n = in.readInt(); float g = in.readFloat();
        float[] m = new float[n];
        int have = Math.min(n, residual.length);
        for (int i = 0; i < have; i++) m[i] = residual[i] * g;
        pa.res = new float[][]{m, m};
        int nt = in.readInt();
        pa.tracks = new PTrack[nt];
        List<List<Integer>> act = new ArrayList<>();
        for (int f = 0; f < pa.nFrames; f++) act.add(new ArrayList<>());
        for (int k = 0; k < nt; k++) {
            PTrack t = new PTrack();
            t.start = in.readInt(); t.len = in.readInt(); t.fmed = in.readFloat(); t.ratio = in.readFloat(); t.harm = in.readInt();
            t.freq = new float[t.len]; short c = 0;
            for (int i = 0; i < t.len; i++) { c = (short) (c + in.readShort()); t.freq[i] = (float) (t.fmed * Math.pow(2, c / 12000.0)); }
            t.amp = new float[t.len]; short q = 0;
            for (int i = 0; i < t.len; i++) { q = (short) (q + in.readShort()); t.amp[i] = q == Short.MIN_VALUE ? 0 : (float) Math.pow(10, q / 2000.0); }
            pa.tracks[k] = t;
            for (int f = Math.max(0, t.start); f < Math.min(pa.nFrames, t.start + t.len); f++) act.get(f).add(k);
        }
        pa.active = new int[pa.nFrames][];
        for (int f = 0; f < pa.nFrames; f++) pa.active[f] = act.get(f).stream().mapToInt(Integer::intValue).toArray();
        return pa;
    }
    /** A frequency as tenths of a cent from the track's median (±27 semitones; tracks move ≤ 6 % a frame). */
    static int centsQ(float f, float fmed) { return f <= 0 || fmed <= 0 ? 0 : (int) Math.max(-32000, Math.min(32000, Math.round(12000 * Math.log(f / fmed) / Math.log(2)))); }
    /** An amplitude in hundredths of a dB; Short.MIN_VALUE is silence (the fade frame at each end). */
    static short ampQ(float a) { return a <= 0 ? Short.MIN_VALUE : (short) Math.max(-32000, Math.min(32000, Math.round(2000 * Math.log10(a)))); }
    /** The file stem a baked analysis goes by: the recording's path under samples/ with / as __, then the thresholds. */
    public static String bakedName(String file, double floorDb, double minLenMs) {
        return file.replaceFirst("\\.[^./]+$", "").replace("/", "__").replace("\\", "__").replaceAll("[^A-Za-z0-9_.-]", "_")
               + ".f" + Math.round(floorDb) + "m" + Math.round(minLenMs);
    }

    /** The analysis, or null while a worker computes it (sync = block instead). */
    public static Partials partials(String file, double fl, double ml, boolean sync) {
        String k = partKey(file, fl, ml);
        Partials pa = PARTS.get(k);
        if (pa != null) return pa;
        if (sync) { pa = source.analyze(file, fl, ml); PARTS.put(k, pa); return pa; }
        if (PARTS_PENDING.add(k)) {
            // one analysis at a time, newest request first: dragging a slider on a
            // 20 s clip used to launch a thread per value (25 analyses at once,
            // each allocating tens of MB) — that starved the audio thread
            PARTS_QUEUE.addFirst(new String[]{k, file, String.valueOf(fl), String.valueOf(ml)});
            startPartsWorker();
        }
        return null;
    }
    public static void startPartsWorker() {
        synchronized (PARTS_QUEUE) {
            if (partsWorker != null) return;
            partsWorker = new Thread(() -> {
                String[] job;
                while ((job = PARTS_QUEUE.pollFirst()) != null) {
                    try { PARTS.put(job[0], source.analyze(job[1], Double.parseDouble(job[2]), Double.parseDouble(job[3]))); }
                    catch (Exception e) { e.printStackTrace(); }
                    finally { PARTS_PENDING.remove(job[0]); }
                }
                synchronized (PARTS_QUEUE) { partsWorker = null; }
                if (!PARTS_QUEUE.isEmpty()) startPartsWorker();   // a request slipped in as we were exiting
            }, "partials");
            partsWorker.setDaemon(true);
            partsWorker.setPriority(Thread.MIN_PRIORITY);
            partsWorker.start();
        }
    }
    public static final java.util.concurrent.ConcurrentLinkedDeque<String[]> PARTS_QUEUE = new java.util.concurrent.ConcurrentLinkedDeque<>();
    public static Thread partsWorker;

    public static Partials analyzePartials(String file, double floorDb, double minLenMs) {
        float[][] smp = sample(file);
        int n = smp[0].length, half = PA_N / 2, nFrames = n / PA_HOP + 1;
        double[] win = new double[PA_N];
        for (int i = 0; i < PA_N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / PA_N);
        double[] re = new double[PA_N], im = new double[PA_N];
        // pass 1: mono magnitude spectra, frames centred on i·hop
        float[][] mag = new float[nFrames][half + 1];
        double gmax = 0;
        for (int f = 0; f < nFrames; f++) {
            int o = f * PA_HOP - half;
            for (int i = 0; i < PA_N; i++) {
                int j = o + i;
                re[i] = j >= 0 && j < n ? 0.5 * (smp[0][j] + smp[1][j]) * win[i] : 0;
                im[i] = 0;
            }
            fft(re, im, false);
            for (int b = 0; b <= half; b++) { float m = (float) Math.hypot(re[b], im[b]); mag[f][b] = m; if (m > gmax) gmax = m; }
        }
        double absFloor = 20 * Math.log10(gmax + 1e-12) - PA_RANGE;
        // interpolated peaks per frame, loud first, capped: a peak must rise
        // `floor` dB over the frame's median and sit within PA_RANGE of the loudest
        // bin in the file, so quiet tails don't sprout tracks out of the noise floor
        List<List<double[]>> frames = new ArrayList<>(nFrames);   // {bin, freq, amp}
        int lo = (int) (PA_FMIN * PA_N / SR), hi = (int) (PA_FMAX * PA_N / SR);
        double[] db = new double[half + 1], srt = new double[half + 1];
        for (int f = 0; f < nFrames; f++) {
            for (int b = 0; b <= half; b++) db[b] = 20 * Math.log10(mag[f][b] + 1e-12);
            System.arraycopy(db, 0, srt, 0, db.length);
            Arrays.sort(srt);
            double ref = Math.max(srt[srt.length / 2] + floorDb, absFloor);
            List<double[]> pk = new ArrayList<>();
            for (int b = lo + 1; b < hi - 1 && b < half; b++) {
                if (db[b] > db[b - 1] && db[b] >= db[b + 1] && db[b] > ref) {
                    double a = db[b - 1], bb = db[b], cc = db[b + 1], den = a - 2 * bb + cc;
                    double pp = den != 0 ? 0.5 * (a - cc) / den : 0;
                    double bin = b + pp, amp = Math.pow(10, (bb - 0.25 * (a - cc) * pp) / 20);
                    pk.add(new double[]{bin, bin * SR / (double) PA_N, amp});
                }
            }
            pk.sort((x, y) -> Double.compare(y[2], x[2]));
            if (pk.size() > PA_MAXPK) pk = new ArrayList<>(pk.subList(0, PA_MAXPK));
            frames.add(pk);
        }
        // greedy nearest-frequency continuation; loud tracks pick first, a
        // track survives PA_GAP missing frames, unmatched peaks start new ones
        class T { public final List<double[]> pts = new ArrayList<>(); public int gap; }   // {bin, freq, amp, frame}
        List<T> active = new ArrayList<>(), done = new ArrayList<>();
        for (int f = 0; f < nFrames; f++) {
            List<double[]> pk = frames.get(f);
            boolean[] used = new boolean[pk.size()];
            active.sort((x, y) -> Double.compare(y.pts.get(y.pts.size() - 1)[2], x.pts.get(x.pts.size() - 1)[2]));
            for (T tr : active) {
                double f0 = tr.pts.get(tr.pts.size() - 1)[1];
                int best = -1; double bd = PA_TOL;
                for (int j = 0; j < pk.size(); j++) {
                    if (used[j]) continue;
                    double d = Math.abs(pk.get(j)[1] - f0) / f0;
                    if (d < bd) { best = j; bd = d; }
                }
                if (best >= 0) { used[best] = true; double[] q = pk.get(best); tr.pts.add(new double[]{q[0], q[1], q[2], f}); tr.gap = 0; }
                else tr.gap++;
            }
            List<T> still = new ArrayList<>();
            for (T tr : active) (tr.gap > PA_GAP ? done : still).add(tr);
            active = still;
            for (int j = 0; j < pk.size(); j++)
                if (!used[j]) { T t = new T(); double[] q = pk.get(j); t.pts.add(new double[]{q[0], q[1], q[2], f}); active.add(t); }
        }
        done.addAll(active);
        int minLen = (int) Math.max(1, Math.round(minLenMs / 1000.0 * SR / PA_HOP));
        // per-frame arrays per track (gaps interpolated, one zero-amp fade frame
        // at each end), plus which bins to notch out of each frame
        List<PTrack> tracks = new ArrayList<>();
        List<List<Integer>> notch = new ArrayList<>(nFrames);
        for (int f = 0; f < nFrames; f++) notch.add(new ArrayList<>());
        double ascale = 4.0 / PA_N;   // Hann-windowed FFT magnitude -> sinusoid amplitude
        for (T tr : done) {
            if (tr.pts.size() < minLen) continue;
            int f0 = (int) tr.pts.get(0)[3], f1 = (int) tr.pts.get(tr.pts.size() - 1)[3];
            PTrack t = new PTrack();
            t.start = f0 - 1; t.len = f1 - f0 + 3;
            t.freq = new float[t.len]; t.amp = new float[t.len];
            int prev = -1;
            for (double[] q : tr.pts) {
                int fi = (int) q[3] - t.start;
                t.freq[fi] = (float) q[1]; t.amp[fi] = (float) (q[2] * ascale);
                for (int g = prev + 1; prev >= 0 && g < fi; g++) {
                    double u = (g - prev) / (double) (fi - prev);
                    t.freq[g] = (float) (t.freq[prev] + (t.freq[fi] - t.freq[prev]) * u);
                    t.amp[g] = (float) (t.amp[prev] + (t.amp[fi] - t.amp[prev]) * u);
                }
                prev = fi;
                notch.get((int) q[3]).add((int) Math.round(q[0]));
            }
            t.freq[0] = t.freq[1]; t.amp[0] = 0;
            t.freq[t.len - 1] = t.freq[t.len - 2]; t.amp[t.len - 1] = 0;
            tracks.add(t);
        }
        List<List<Integer>> act = new ArrayList<>(nFrames);
        for (int f = 0; f < nFrames; f++) act.add(new ArrayList<>());
        for (int k = 0; k < tracks.size(); k++) {
            PTrack t = tracks.get(k);
            for (int f = Math.max(0, t.start); f < Math.min(nFrames, t.start + t.len); f++) act.get(f).add(k);
        }
        // pass 2: residual = the stereo STFT with every tracked peak notched out
        // (raised cosine, zero at the peak, untouched 4 bins away), overlap-added
        float[][] res = new float[2][n];
        double[] norm = new double[n];
        double[] reR = new double[PA_N], imR = new double[PA_N];
        for (int f = 0; f < nFrames; f++) {
            int o = f * PA_HOP - half;
            for (int i = 0; i < PA_N; i++) {
                int j = o + i;
                boolean in = j >= 0 && j < n;
                re[i] = in ? smp[0][j] * win[i] : 0; im[i] = 0;
                reR[i] = in ? smp[1][j] * win[i] : 0; imR[i] = 0;
            }
            fft(re, im, false); fft(reR, imR, false);
            for (int b : notch.get(f))
                for (int d = -4; d <= 4; d++) {
                    int kk = b + d;
                    if (kk < 0 || kk > half) continue;
                    double w = 0.5 - 0.5 * Math.cos(Math.PI * Math.min(1.0, Math.abs(d) / 4.0));
                    re[kk] *= w; im[kk] *= w; reR[kk] *= w; imR[kk] *= w;
                    if (kk > 0 && kk < half) { int m = PA_N - kk; re[m] *= w; im[m] *= w; reR[m] *= w; imR[m] *= w; }
                }
            fft(re, im, true); fft(reR, imR, true);
            for (int i = 0; i < PA_N; i++) {
                int j = o + i;
                if (j < 0 || j >= n) continue;
                res[0][j] += (float) (re[i] * win[i]); res[1][j] += (float) (reR[i] * win[i]);
                norm[j] += win[i] * win[i];
            }
        }
        for (int j = 0; j < n; j++) if (norm[j] > 1e-6) { res[0][j] /= (float) norm[j]; res[1][j] /= (float) norm[j]; }
        Partials pa = new Partials();
        pa.nFrames = nFrames; pa.nTracks = tracks.size(); pa.res = res; pa.f0 = estimateF0(tracks);
        for (PTrack t : tracks) { t.fmed = t.freq[t.len / 2]; t.ratio = pa.f0 > 0 ? (float) (t.fmed / pa.f0) : 0; t.harm = harmNum(t.ratio); }
        double eS = 0, eR = 0;   // how tonal the recording is: a sinusoid of amplitude a carries a²/2 per sample
        for (PTrack t : tracks) for (float a : t.amp) eS += 0.5 * a * a * PA_HOP;
        for (int j = 0; j < n; j++) eR += 0.5 * (res[0][j] * res[0][j] + res[1][j] * res[1][j]);
        pa.share = eS + eR > 0 ? eS / (eS + eR) : 0;
        pa.tracks = tracks.toArray(new PTrack[0]);
        pa.active = new int[nFrames][];
        for (int f = 0; f < nFrames; f++) pa.active[f] = act.get(f).stream().mapToInt(Integer::intValue).toArray();
        return pa;
    }
}
