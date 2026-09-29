package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

/** The regulator's live mix: the signals, the palette's binds and every spell's blend, turned into each layer's
 *  modulation once per audio block (live), plus the one-shots fired by the machine's events. Everything the audio
 *  thread reads is here, so the authoring GUI and the mod run the same mix; the GUI only adds auditioning (solo,
 *  binds off) on top. */
public class BenchMixer {
    /** The reserved layer id and param a spell binds to set its own blend curve. */
    public static final String SPELL_LAYER = "spell", BLEND_PARAM = "blend";
    public final Object lock;                               // guards bench.layers / bench.binds against the editor
    public final Bench bench = new Bench();                  // the family's palette: the searching mix
    public volatile java.util.List<Spell> spells = new ArrayList<>();
    public final double[] sigVal = new double[SIGNALS.length];
    public final ConcurrentHashMap<String, Double> spellScore = new ConcurrentHashMap<>();   // score.<id>: each spell's own match
    public final Set<String> mutedSigs = ConcurrentHashMap.newKeySet();   // signals switched off (authoring A/B): their binds hold still
    public final ConcurrentLinkedQueue<Clip> fireQ = new ConcurrentLinkedQueue<>();
    public final ArrayList<Clip> transients = new ArrayList<>();   // one-shots in flight (audio thread only)
    public volatile boolean bindsOn = true;   // off = every layer at its saved params (auditioning)
    public volatile Clip solo;                // authoring: one layer alone
    public volatile Spell soloSpell;          // set when the soloed layer is a spell's: it plays alone at its target values
    public volatile boolean driven;           // the machine writes the signals: a solo hears the binds

    public BenchMixer(Object lock) { this.lock = lock; }
    public BenchMixer() { this(new Object()); }

    /** Whether a bind moves anything right now: not switched off itself, and its signal not muted. */
    public boolean bindLive(Bind b) { return !b.mute && !mutedSigs.contains(b.sig); }

    public double signal(String name) {
        int i = sigIdx(name);
        if (i >= 0) return sigVal[i];
        if (name.startsWith("score.")) return spellScore.getOrDefault(name.substring(6), 0.0);
        if (name.endsWith(".pitch")) {
            int a = sigIdx(name.substring(0, name.length() - 6) + ".ratio");
            if (a < 0) return 0;
            double r = sigVal[a];
            if (r <= 0.05) return 0;
            double st = 12 * Math.log(r) / Math.log(2);
            return ((st % 12) + 12) % 12;
        }
        return 0;
    }
    public double signalMax(String name) { int i = sigIdx(name); return i >= 0 ? SIG_MAX[i] : name.endsWith(".pitch") ? 12 : 1; }
    public double signalNorm(String name) { return Math.max(0, Math.min(1, signal(name) / signalMax(name))); }

    /** How far the signature has blended in: 0 below score 0.55, 1 at 1. */
    public double blendW() { return bindsOn ? smoothstep(0.55, 1.0, sigVal[SIG_SCORE]) : 0; }

    /** Audio thread, once per block: the clips that sound now, with each
     *  layer's modulation target computed from the signals, the binds and the
     *  signature. Bound params are absolute targets (the modulation is the
     *  difference from the saved value) unless the bind is `rel`; the blend
     *  then moves everything toward the signature's values by w. */
    /** A clip's live modulation array, zeroed and ready to fill. */
    public static double[] modOf(Clip c) {
        double[] m = c.mod;
        if (m == null || m.length != c.p.length) m = new double[c.p.length]; else Arrays.fill(m, 0);
        return m;
    }
    public void live(double now, List<Clip> out) {
        // every spell's signature blends in by its weight (its own score through smoothstep(0.55, 1) unless the spell
        // binds `spell blend` to something else); when the weights add past 1 they share
        java.util.List<Spell> sps = spells;
        double sumW = 0;
        for (Spell sp : sps) { sp.w = spellWeight(sp); sumW += sp.w; }
        double norm = sumW > 1 ? 1 / sumW : 1;
        Clip so = solo; Spell soSp = soloSpell;
        // a solo while the machine drives the signals hears the layer through its binds and the blend, wherever the
        // machine has them; with the machine off, closed or not driving, a solo is the layer exactly as authored
        boolean audition = so != null && !driven;
        // No endless layer ever leaves the mix while it is on the bench: one that isn't heard (muted, not the solo,
        // a spell's extra at zero weight) stays in `out` with its level driven to 0, so it fades over the engine's
        // param smoother instead of stopping mid-cycle, and comes back the same way. A silent layer costs nothing.
        if (so != null && soSp != null) {   // a spell's layer alone, at its target values, with that spell's own binds
            double[] m = modOf(so);
            if (bindsOn && !audition) applyBinds(soSp.bench.binds, so, m);
            so.mod = m;
            if (so.on == ON_NONE) out.add(so);
        }
        List<Clip> ls; List<Bind> bs;
        synchronized (lock) { ls = new ArrayList<>(bench.layers); bs = new ArrayList<>(bench.binds); }
        for (Clip c : ls) {
            if (c.on != ON_NONE) continue;                      // one-shots only sound when fired
            double[] m = modOf(c);
            boolean heard = so != null ? c == so : !c.lmute;
            if (!heard) { m[P_LEVEL] = -c.p[P_LEVEL]; c.mod = m; out.add(c); continue; }
            if (bindsOn && !audition) applyBinds(bs, c, m);
            if (sumW > 0 && !audition) {
                double wl = 0; double[] acc = null;
                for (Spell sp : sps) {
                    if (sp.w <= 0) continue;
                    Clip s = sp.bench.byId(c.id);
                    if (s == null || s.type != c.type || s.lmute) continue;   // a spell that omits (or mutes) the layer leaves it at its searching value
                    double w = sp.w * norm;
                    if (acc == null) acc = new double[m.length];
                    wl += w;
                    double[] sm = spellBindMod(sp, s);   // the spell's own binds move its targets
                    for (int i = 0; i < m.length; i++) acc[i] += w * (s.p[i] + (sm != null ? sm[i] : 0) - c.p[i]);
                }
                if (acc != null) for (int i = 0; i < m.length; i++) m[i] = (1 - wl) * m[i] + acc[i];
            }
            c.mod = m;
            out.add(c);
        }
        for (Spell sp : sps)
            for (Clip s : sp.bench.layers) {   // layers only this spell has fade in with its weight (and out again at 0)
                if (s.on != ON_NONE || s == so) continue;
                boolean inPalette = false;
                for (Clip c : ls) if (s.id != null && s.id.equals(c.id)) { inPalette = true; break; }
                if (inPalette) continue;
                double w = so == null && !s.lmute ? sp.w * norm : 0;
                double[] m = modOf(s);
                if (w > 0) {
                    if (bindsOn) applyBinds(sp.bench.binds, s, m);
                    m[P_LEVEL] = w * (s.p[P_LEVEL] + m[P_LEVEL]) - s.p[P_LEVEL];   // its own binds, then faded in by the blend
                } else m[P_LEVEL] = -s.p[P_LEVEL];
                s.mod = m;
                out.add(s);
            }
        for (Clip f; (f = fireQ.poll()) != null; ) { f.start = now; transients.add(f); }
        transients.removeIf(f -> now >= f.end());
        out.addAll(transients);
    }

    /** A spell's blend rule, if its file binds `spell blend`. */
    public Bind blendBind(Spell sp) { for (Bind b : sp.bench.binds) if (SPELL_LAYER.equals(b.layer) && BLEND_PARAM.equals(b.param) && bindLive(b)) return b; return null; }
    /** How far a spell is blended in: smoothstep over the bound signal's lo..hi (default score.<id> over 0.55..1),
     *  with the bind's map (steps=1 makes a gate). Nothing while binds are off: that is the audition mode, where every
     *  layer plays exactly as saved. */
    public double spellWeight(Spell sp) {
        if (!bindsOn) return 0;
        Bind b = blendBind(sp);
        if (b == null) return smoothstep(0.55, 1.0, spellScore.getOrDefault(sp.id, 0.0));
        double lo = b.auto() ? 0.55 : b.lo, hi = b.auto() ? 1.0 : b.hi;
        double w = smoothstep(Math.min(lo, hi), Math.max(lo, hi), signal(b.sig));
        if (lo > hi) w = 1 - w;
        return Math.max(0, Math.min(1, b.map(w, 0, 1)));
    }
    /** Adds a bind list's modulation of clip c into m (absolute binds as the difference from the saved value). */
    public void applyBinds(List<Bind> bs, Clip c, double[] m) {
        for (Bind b : bs) {
            if (!(b.layer.equals("*") || b.layer.equals(c.id)) || !bindLive(b)) continue;
            int pi = idxOf(c.type, b.param);
            if (pi < 0) continue;
            m[pi] += bindTerm(b, c, pi);
        }
    }
    /** A bind's lo..hi on a layer: as written, or for auto the marked range, else the spec range (rel: 0 .. the spec's width). */
    public double[] bindRange(Bind b, Clip c, int pi) {
        if (!b.auto()) return new double[]{b.lo, b.hi};
        double[] r = c.range != null ? c.range.get(pi) : null;
        PSpec s = spec(c.type, pi);
        return new double[]{r != null ? r[0] : b.rel ? 0 : s.min(), r != null ? r[1] : b.rel ? s.max() - s.min() : s.max()};
    }
    /** What one bind adds to a param's modulation right now: rel adds its mapped value, absolute the difference from the saved value. */
    public double bindTerm(Bind b, Clip c, int pi) {
        double[] lh = bindRange(b, c, pi);
        double v = b.map(lh[0] + (lh[1] - lh[0]) * signalNorm(b.sig), lh[0], lh[1]);
        return b.rel ? v : v - c.p[pi];
    }

    /** A spell's binds applied to one of its layers' targets; null when it has none that touch it. */
    public double[] spellBindMod(Spell sp, Clip s) {
        if (!bindsOn || sp.bench.binds.isEmpty()) return null;
        double[] t = new double[s.p.length];
        applyBinds(sp.bench.binds, s, t);
        return t;
    }

    /** Queues a copy of a one-shot layer to start at the next block (a layer can overlap itself). */
    public void fire(Clip c) {
        Clip f = copyClip(c);
        f.id = null; f.on = ON_NONE; f.lmute = false; f.range = null; f.rnote = null;
        if (f.dur >= ENDLESS / 2) f.dur = naturalDur(c);
        fireQ.add(f);
    }
    /** A spell's own event: its one-shots marked for it fire. Returns how many. */
    public int fireSpell(String id, int on) {
        int n = 0;
        for (Spell sp : spells) if (sp.id.equals(id)) for (Clip c : sp.bench.layers) if (c.on == on) { fire(c); n++; }
        return n;
    }
    /** The palette's own one-shots marked for an event (lock / unlock / accept). Returns how many. */
    public int fireEvent(int on) {
        int n = 0;
        List<Clip> ls;
        synchronized (lock) { ls = new ArrayList<>(bench.layers); }
        for (Clip c : ls) if (c.on == on) { fire(c); n++; }
        return n;
    }
}
