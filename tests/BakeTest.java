import java.io.*;
import java.util.*;
import sfxlab.runtime.*;

/** The baked partials format (Partials.writeBaked / readBaked), runtime only: tracks survive within their quantisation
 *  (0.1 cent, 0.01 dB), silent fade frames stay silent, the residual comes back scaled by its gain, and the active lists
 *  are rebuilt. Run by tests/run.sh. */
public class BakeTest {
    static int fails = 0;
    static void check(String what, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + what); if (!ok) fails++; }

    public static void main(String[] a) throws Exception {
        Random r = new Random(5);
        Partials pa = new Partials();
        pa.nFrames = 400; pa.f0 = 220; pa.share = 0.6;
        PTrack[] ts = new PTrack[60];
        for (int k = 0; k < ts.length; k++) {
            PTrack t = new PTrack();
            t.start = r.nextInt(300) - 1; t.len = 3 + r.nextInt(90);
            t.freq = new float[t.len]; t.amp = new float[t.len];
            double f = 60 + r.nextDouble() * 9000;
            for (int i = 0; i < t.len; i++) { f *= 1 + (r.nextDouble() - 0.5) * 0.08; t.freq[i] = (float) f; t.amp[i] = (float) (Math.pow(10, -r.nextDouble() * 4) * 0.8); }
            t.amp[0] = 0; t.amp[t.len - 1] = 0;
            t.fmed = t.freq[t.len / 2]; t.ratio = (float) (t.fmed / pa.f0); t.harm = Sfx.harmNum(t.ratio);
            ts[k] = t;
        }
        pa.tracks = ts; pa.nTracks = ts.length;
        int n = pa.nFrames * Partials.PA_HOP;
        float[] res = new float[n];
        for (int i = 0; i < n; i++) res[i] = (float) ((r.nextDouble() - 0.5) * 1.6);
        pa.res = new float[][]{res, res};
        float g = 1.6f / 0.99f;
        float[] stored = new float[n];
        for (int i = 0; i < n; i++) stored[i] = res[i] / g;   // what the bake encodes (the lossy codec is the host's business)

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Partials.writeBaked(bo, pa, n, g);
        Partials q = Partials.readBaked(new ByteArrayInputStream(bo.toByteArray()), stored);
        check("header: frames, tracks, f0, share", q.nFrames == pa.nFrames && q.nTracks == pa.nTracks && q.f0 == pa.f0 && q.share == pa.share);
        double worstCents = 0, worstDb = 0; boolean zeros = true, shape = true;
        for (int k = 0; k < ts.length; k++) {
            PTrack x = ts[k], y = q.tracks[k];
            shape &= x.start == y.start && x.len == y.len && x.fmed == y.fmed && x.ratio == y.ratio && x.harm == y.harm;
            for (int i = 0; i < x.len; i++) {
                worstCents = Math.max(worstCents, Math.abs(1200 * Math.log(y.freq[i] / x.freq[i]) / Math.log(2)));
                if (x.amp[i] == 0) zeros &= y.amp[i] == 0;
                else worstDb = Math.max(worstDb, Math.abs(20 * Math.log10(y.amp[i] / x.amp[i])));
            }
        }
        check("track shape and medians exact", shape);
        check(String.format(Locale.ROOT, "frequencies within 0.06 cent (worst %.4f)", worstCents), worstCents < 0.06);
        check(String.format(Locale.ROOT, "amplitudes within 0.006 dB (worst %.4f)", worstDb), worstDb < 0.006);
        check("silent fade frames stay silent", zeros);
        double worstRes = 0;
        for (int i = 0; i < n; i++) worstRes = Math.max(worstRes, Math.abs(q.res[0][i] - res[i]));
        check("residual scaled back by its gain, on both channels", worstRes < 1e-5 && q.res[0] == q.res[1]);
        boolean act = true;
        for (int f = 0; f < pa.nFrames; f++) for (int k : q.active[f]) act &= f >= q.tracks[k].start && f < q.tracks[k].start + q.tracks[k].len;
        check("active lists rebuilt", act);
        check("baked name: path, thresholds", Partials.bakedName("luminal/luminal_synth_chorus_00003.ogg", 14, 46).equals("luminal__luminal_synth_chorus_00003.f14m46"));
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
