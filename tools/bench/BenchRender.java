import java.io.*;
import java.nio.file.*;
import java.util.*;
import sfxlab.runtime.*;

/** Headless bench render (an authoring aid: measure a palette instead of guessing).
 *    javac -d /tmp/bench $(find runtime -name "*.java") SfxLab.java tools/bench/BenchRender.java
 *    java -Djava.awt.headless=true -cp /tmp/bench BenchRender regulator/pyretic.sfx pyretic tools/bench/firebolt.txt renders/bench/out.wav --layers
 *  Prints, per second: RMS, peak, % of blocks the master limiter was squashing (sat), energy share in five bands,
 *  the live signals and scores, and (with --layers) each layer's contribution in dB (full mix minus the mix without it).
 *  Rough targets that read as "clear" rather than "mud": sat 0, no band over ~60 %, the notes within ~6 dB of the bed.
 *  Drives a RegulatorCore through a scripted approach and renders the bench through SfxLab's own benchLive + Engine.
 *  usage: BenchRender <palette.sfx> <family> <script> <out.wav> [--layers] [--warm] [--lazy] [--prime]   (--warm: time a second, JIT-warm pass; --lazy: analyses load when
 *  their layer first sounds; --prime: Engine.prime the family first, as the GUI does;
 *  run with java -XX:ActiveProcessorCount=1 for the one-thread cost a game sound thread pays)
 *  script lines (times absolute, seconds):
 *    @t pin <spellId>              the machine's target
 *    @t motion <arm> <axis> <n> <phase> <amp>    engage a motion at ratio n (driven = detuned allowed)
 *    @t ramp <arm> <axis> <nTo> <dur>            glide that motion's ratio to nTo over dur s, then hold exactly
 *    @t off <arm> <axis>
 *    @t crank <ratio> <dur>       the crank turns at this ratio for dur s
 *    @t end */
public class BenchRender {
    record Ev(double t, String[] a) {}
    static final double[] COST = new double[600];
    static final double[] SLOW = new double[2];   // the slowest block of the full mix (s) and when it came   // seconds of work per second of audio (the full mix)
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sfxlab.noautosave", "true");
        Path pal = Paths.get(args[0]); String family = args[1]; Path script = Paths.get(args[2]); Path out = Paths.get(args[3]);
        boolean perLayer = Arrays.asList(args).contains("--layers");
        SfxLab lab = new SfxLab();
        lab.benchOn = true;
        lab.family = family;
        lab.loadFamily(family);   // regulator/<family>.sfx: the palette and its spells (pal names the same file)
        lab.mix.driven = true; lab.benchPlaying = true; lab.mix.bindsOn = true;
        long t0 = System.currentTimeMillis();
        boolean lazy = Arrays.asList(args).contains("--lazy");   // as the GUI once did: each analysis loads when its layer first sounds
        if (!lazy) {
            for (Clip c : lab.bench.layers) if (c.type == Sfx.PARTIALS && c.file != null) Partials.partials(c, true);
            for (Spell sp : lab.mix.spells) for (Clip c : sp.bench.layers) if (c.type == Sfx.PARTIALS && c.file != null) Partials.partials(c, true);
        }
        System.err.printf(Locale.ROOT, "partials analysed / loaded in %.1f s%n", (System.currentTimeMillis() - t0) / 1000.0);
        List<Ev> evs = new ArrayList<>();
        double endT = 20;
        for (String line : Files.readAllLines(script)) {
            line = line.trim(); if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            double at = Double.parseDouble(t[0].substring(1));
            if (t[1].equals("end")) endT = at; else evs.add(new Ev(at, Arrays.copyOfRange(t, 1, t.length)));
        }
        evs.sort(Comparator.comparingDouble(Ev::t));
        List<String> ids = new ArrayList<>();
        for (Clip c : lab.bench.layers) ids.add(c.id);
        for (Spell sp : lab.mix.spells) for (Clip c : sp.bench.layers) if (c.on == Sfx.ON_NONE && !ids.contains(c.id)) ids.add(c.id + "@" + sp.id);
        // full mix, then optionally each endless layer alone (live, through its binds)
        if (Arrays.asList(args).contains("--prime")) {   // as the GUI does on loading a family
            long p0 = System.nanoTime();
            ArrayList<Clip> all = new ArrayList<>(lab.bench.layers);
            for (Spell sp : lab.mix.spells) all.addAll(sp.bench.layers);
            Engine.prime(all, 0.5);
            System.err.printf(Locale.ROOT, "primed %d clips in %.1f s%n", all.size(), (System.nanoTime() - p0) / 1e9);
        }
        if (Arrays.asList(args).contains("--warm")) {   // for timing: one pass first, so the JIT has compiled the engine before the measured one
            PrintStream so = System.out; System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            render(lab, evs, endT, null, null, true);
            System.setOut(so); Arrays.fill(COST, 0); Arrays.fill(SLOW, 0);
        }
        long r0 = System.nanoTime();
        double[][] mix = render(lab, evs, endT, null, null, true);
        double took = (System.nanoTime() - r0) / 1e9, len = mix[0].length / (double) Sfx.SR;
        int worst = 0; for (int k = 1; k < Math.min(COST.length, (int) len); k++) if (COST[k] > COST[worst]) worst = k;
        System.err.printf(Locale.ROOT, "rendered %.1f s of audio in %.2f s: %.3f of realtime on %d render thread%s; worst second %.3f (at %d s); slowest block %.1f ms (at %.2f s; a block is %.1f ms, the live buffer ~70)%n", len, took, took / len,
                          Engine.POOL_N, Engine.POOL_N == 1 ? "" : "s", COST[worst], worst, SLOW[0] * 1000, SLOW[1], Sfx.BLOCK * 1000.0 / Sfx.SR);
        writeWav(out, mix);
        int win = Sfx.SR;   // 1 s windows
        int nw = mix[0].length / win;
        double[][] layerRms = new double[ids.size()][nw];
        if (perLayer) {
            for (int li = 0; li < ids.size(); li++) {
                String id = ids.get(li); String spId = null;
                if (id.contains("@")) { spId = id.substring(id.indexOf('@') + 1); id = id.substring(0, id.indexOf('@')); }
                // contribution = full mix minus the mix with every clip of that id muted (palette and spells alike)
                List<Clip> muted = new ArrayList<>();
                for (Clip c : lab.bench.layers) if (c.id.equals(id)) muted.add(c);
                for (Spell sp : lab.mix.spells) for (Clip c : sp.bench.layers) if (c.id.equals(id) && (spId == null || sp.id.equals(spId))) muted.add(c);
                for (Clip c : muted) c.lmute = true;
                double[][] m = render(lab, evs, endT, null, null, false);
                for (Clip c : muted) c.lmute = false;
                double[][] d = new double[2][m[0].length];
                for (int i = 0; i < m[0].length; i++) { d[0][i] = mix[0][i] - m[0][i]; d[1][i] = mix[1][i] - m[1][i]; }
                for (int w = 0; w < nw; w++) layerRms[li][w] = rms(d, w * win, win);
            }
        }
        // report
        System.out.println("sec   rms dB  peak   sat%   <100 100-300 300-1k 1k-4k >4k   signals / top layers");
        double[] sat = SAT;
        for (int w = 0; w < nw; w++) {
            double r = rms(mix, w * win, win), pk = 0;
            for (int i = w * win; i < (w + 1) * win; i++) pk = Math.max(pk, Math.max(Math.abs(mix[0][i]), Math.abs(mix[1][i])));
            double[] b = bands(mix, w * win, win);
            StringBuilder top = new StringBuilder();
            if (perLayer) {
                Integer[] ord = new Integer[ids.size()]; for (int i = 0; i < ord.length; i++) ord[i] = i;
                final int ww = w;
                Arrays.sort(ord, (x, y) -> Double.compare(layerRms[y][ww], layerRms[x][ww]));
                for (int k = 0; k < Math.min(5, ord.length); k++) { double v = layerRms[ord[k]][w]; if (v < 1e-4) break; top.append(String.format(Locale.ROOT, " %s %.0f", ids.get(ord[k]), db(v))); }
            }
            System.out.printf(Locale.ROOT, "%3d  %6.1f  %5.2f  %4.0f   %4.0f  %4.0f   %4.0f   %4.0f  %4.0f   %s |%s%n", w, db(r), pk, 100 * sat[w],
                    100 * b[0], 100 * b[1], 100 * b[2], 100 * b[3], 100 * b[4], SIGLOG[w], top);
        }
        System.out.flush();
        System.exit(0);   // the lab's Swing timer and the engine's pool are not daemons
    }
    static double[] SAT; static String[] SIGLOG;
    static double db(double v) { return 20 * Math.log10(Math.max(v, 1e-6)); }
    static double rms(double[][] m, int from, int n) { double s = 0; for (int i = from; i < from + n && i < m[0].length; i++) s += m[0][i] * m[0][i] + m[1][i] * m[1][i]; return Math.sqrt(s / (2 * n)); }
    /** Renders the bench along the script. solo: that layer alone (live). */
    static double[][] render(SfxLab lab, List<Ev> evs, double endT, Clip so, Spell soSp, boolean log) throws Exception {
        RegulatorCore core = new RegulatorCore(lab.familyRecipes());
        core.power(true);
        lab.mix.solo = so; lab.mix.soloSpell = soSp;
        lab.spellScore.clear(); Arrays.fill(lab.sigVal, 0);
        lab.transients.clear(); lab.fireQ.clear();
        for (Clip c : lab.bench.layers) c.mod = null;
        for (Spell sp : lab.mix.spells) for (Clip c : sp.bench.layers) c.mod = null;
        Engine eng = new Engine();
        int total = (int) (endT * Sfx.SR);
        double[][] mix = new double[2][total];
        double[] bl = new double[Sfx.BLOCK], br = new double[Sfx.BLOCK];
        List<Clip> snap = new ArrayList<>();
        int ei = 0; double dt = Sfx.BLOCK / (double) Sfx.SR;
        // ramps / crank in flight
        class Ramp { int arm, axis; double from, to, t0, t1; }
        List<Ramp> ramps = new ArrayList<>(); double crankR = 0, crankUntil = -1;
        int nw = total / Sfx.SR; if (log) { SAT = new double[nw + 1]; SIGLOG = new String[nw + 1]; Arrays.fill(SIGLOG, ""); }
        int satBlocks = 0, blocksInWin = 0; int lastW = -1;
        for (int i = 0; i < total; i += Sfx.BLOCK) {
            double now = i / (double) Sfx.SR;
            while (ei < evs.size() && evs.get(ei).t <= now) {
                String[] a = evs.get(ei++).a;
                switch (a[0]) {
                    case "pin" -> { for (RegulatorCore.Recipe r : core.recipes) if (r.id.equals(a[1])) core.setTarget(r); }
                    case "motion" -> { RegulatorCore.Motion m = core.comps[Integer.parseInt(a[1])][Integer.parseInt(a[2])]; m.eng = true; m.act = true; m.r = Double.parseDouble(a[3]); m.ph = Integer.parseInt(a[4]); m.amp = Double.parseDouble(a[5]); m.drv = m.r != Math.rint(m.r); }
                    case "ramp" -> { Ramp r = new Ramp(); r.arm = Integer.parseInt(a[1]); r.axis = Integer.parseInt(a[2]); r.from = core.comps[r.arm][r.axis].r; r.to = Double.parseDouble(a[3]); r.t0 = now; r.t1 = now + Double.parseDouble(a[4]); ramps.add(r); }
                    case "off" -> { RegulatorCore.Motion m = core.comps[Integer.parseInt(a[1])][Integer.parseInt(a[2])]; m.eng = false; m.act = false; m.drv = false; }
                    case "crank" -> { crankR = Double.parseDouble(a[1]); crankUntil = now + Double.parseDouble(a[2]); }
                    default -> throw new IllegalArgumentException("unknown command " + a[0]);
                }
            }
            for (Iterator<Ramp> it = ramps.iterator(); it.hasNext(); ) {
                Ramp r = it.next(); RegulatorCore.Motion m = core.comps[r.arm][r.axis];
                double u = Math.min(1, (now - r.t0) / (r.t1 - r.t0));
                m.r = r.from + (r.to - r.from) * u; m.drv = true;
                if (u >= 1) { m.r = r.to; m.drv = false; it.remove(); }
            }
            core.vel = now < crankUntil ? crankR / 2 : 0;
            core.tick(dt);
            for (int k = 0; k < RegulatorCore.SIGNALS.length; k++) { int s = Sfx.sigIdx(RegulatorCore.SIGNALS[k]); if (s >= 0) lab.sigVal[s] = core.signals[k]; }
            for (RegulatorCore.Recipe r : core.recipes) lab.spellScore.put(r.id, core.eval.get(r.id).score);
            for (String ev : core.events()) {
                if (ev.equals("lock")) lab.fireEvent(Sfx.ON_LOCK, false, true);
                else if (ev.equals("unlock")) lab.fireEvent(Sfx.ON_UNLOCK, false, true);
                else if (ev.startsWith("match:")) lab.fireSpell(ev.substring(6), Sfx.ON_LOCK, true);
                else if (ev.startsWith("unmatch:")) lab.fireSpell(ev.substring(8), Sfx.ON_UNLOCK, true);
                else if (ev.startsWith("accept:")) lab.fireEvent(Sfx.ON_ACCEPT, false, true);
                if (log) System.out.printf(Locale.ROOT, "  %.2fs event %s%n", now, ev);
            }
            long b0 = System.nanoTime();   // the audio thread's share: the live mix and the engine
            snap.clear();
            lab.benchLive(eng.t, snap);
            eng.voices.keySet().removeIf(c -> !snap.contains(c) || eng.t < c.start || eng.t >= c.end());
            int n = Math.min(Sfx.BLOCK, total - i);
            eng.renderBlock(snap, null, null, null, bl, br, n);
            double bt = (System.nanoTime() - b0) / 1e9;
            if (log && (int) now < COST.length) COST[(int) now] += bt;
            if (log && now >= 2 && bt > SLOW[0]) { SLOW[0] = bt; SLOW[1] = now; }   // past start-up (the JIT's first compiles)
            if (log && now >= 2 && bt > 0.010) System.err.printf(Locale.ROOT, "  slow block %.1f ms at %.2f s [uptime %.3f s]%n", bt * 1000, now, java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
            for (int k = 0; k < n; k++) { mix[0][i + k] = bl[k]; mix[1][i + k] = br[k]; }
            int w = (int) now;
            if (log) {
                if (w != lastW) {
                    if (lastW >= 0 && lastW < SAT.length) SAT[lastW] = blocksInWin > 0 ? satBlocks / (double) blocksInWin : 0;
                    satBlocks = 0; blocksInWin = 0; lastW = w;
                    StringBuilder sb = new StringBuilder();
                    String[] show = {"arm1.ratio", "arm2.ratio", "arm3.ratio", "tone.root", "tone.third", "tone.fifth", "tone.seventh", "stack", "fit", "stir", "coherence", "orb.radius", "orb.curl"};
                    for (String s : show) { double v = lab.signal(s); if (v > 0.005) sb.append(s.replace("arm", "a").replace(".ratio", "r").replace("orb.", "o.").replace("tone.", "t.")).append(String.format(Locale.ROOT, "=%.2f ", v)); }
                    for (Spell sp : lab.mix.spells) { double sc = lab.spellScore.getOrDefault(sp.id, 0.0); if (sc > 0.2) sb.append(String.format(Locale.ROOT, "%s=%.2f ", sp.id, sc)); }
                    if (w < SIGLOG.length) SIGLOG[w] = sb.toString();
                }
                blocksInWin++; if (eng.inPeak > 1) satBlocks++; eng.inPeak = 0;
            }
        }
        if (log && lastW >= 0 && lastW < SAT.length) SAT[lastW] = blocksInWin > 0 ? satBlocks / (double) blocksInWin : 0;
        return mix;
    }
    /** Energy share in five bands over a window (Goertzel-free: a plain DFT on 4096-sample frames, hop 2048). */
    static double[] bands(double[][] m, int from, int n) {
        int N = 4096; double[] e = new double[5];
        double[] re = new double[N], im = new double[N];
        for (int f0 = from; f0 + N <= from + n; f0 += 2048) {
            for (int i = 0; i < N; i++) { double wnd = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / N); re[i] = (m[0][f0 + i] + m[1][f0 + i]) * 0.5 * wnd; im[i] = 0; }
            fft(re, im);
            for (int k = 1; k < N / 2; k++) {
                double hz = k * Sfx.SR / (double) N, p = re[k] * re[k] + im[k] * im[k];
                e[hz < 100 ? 0 : hz < 300 ? 1 : hz < 1000 ? 2 : hz < 4000 ? 3 : 4] += p;
            }
        }
        double s = e[0] + e[1] + e[2] + e[3] + e[4];
        if (s > 0) for (int i = 0; i < 5; i++) e[i] /= s;
        return e;
    }
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) { int bit = n >> 1; for (; (j & bit) != 0; bit >>= 1) j ^= bit; j ^= bit; if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; } }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int j = 0; j < len / 2; j++) {
                    double ur = re[i + j], ui = im[i + j], vr = re[i + j + len / 2] * cr - im[i + j + len / 2] * ci, vi = re[i + j + len / 2] * ci + im[i + j + len / 2] * cr;
                    re[i + j] = ur + vr; im[i + j] = ui + vi; re[i + j + len / 2] = ur - vr; im[i + j + len / 2] = ui - vi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
    static void writeWav(Path out, double[][] m) throws Exception {
        int n = m[0].length; byte[] d = new byte[n * 4];
        for (int i = 0; i < n; i++) { int l = (int) Math.max(-32768, Math.min(32767, m[0][i] * 32767)), r = (int) Math.max(-32768, Math.min(32767, m[1][i] * 32767)); d[i * 4] = (byte) l; d[i * 4 + 1] = (byte) (l >> 8); d[i * 4 + 2] = (byte) r; d[i * 4 + 3] = (byte) (r >> 8); }
        javax.sound.sampled.AudioFormat f = new javax.sound.sampled.AudioFormat(Sfx.SR, 16, 2, true, false);
        try (var in = new javax.sound.sampled.AudioInputStream(new java.io.ByteArrayInputStream(d), f, n)) { javax.sound.sampled.AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, out.toFile()); }
    }
}
