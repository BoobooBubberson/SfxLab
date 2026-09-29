package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;

public class Bind {
    public String sig, layer, param; public double lo, hi; public boolean rel;   // lo/hi NaN = auto: the layer's marked range, else the full spec range
    public int steps;      // > 0: the value is quantised to this many steps across lo..hi (a sweep becomes a staircase)
    public String scale;   // a chord name (CHORD_NAMES, spaces as _): the value, in semitones, snaps to that scale's nearest degree
    public boolean mute;   // switched off (kept in the table for A/B comparison); `off` on the line, which older readers ignore
    public Bind(String sig, String layer, String param, double lo, double hi, boolean rel) { this.sig = sig; this.layer = layer; this.param = param; this.lo = lo; this.hi = hi; this.rel = rel; }
    public boolean auto() { return Double.isNaN(lo) || Double.isNaN(hi); }
    public String map() { return steps > 0 ? "steps=" + steps : scale != null ? "scale=" + scale : ""; }
    /** Sets the mapping from its text form ("steps=5", "scale=penta", or nothing). False if unknown. */
    public boolean setMap(String m) {
        m = m == null ? "" : m.trim();
        if (m.isEmpty()) { steps = 0; scale = null; return true; }
        if (m.startsWith("steps=")) { try { steps = Math.max(0, Integer.parseInt(m.substring(6).trim())); scale = null; return true; } catch (NumberFormatException e) { return false; } }
        if (m.startsWith("scale=")) { String n = m.substring(6).trim().replace(' ', '_'); if (scaleDegrees(n) == null) return false; scale = n; steps = 0; return true; }
        return false;
    }
    /** The mapping applied to a raw bind value across lo..hi. */
    public double map(double v, double lo, double hi) {
        if (steps > 0 && hi != lo) v = lo + Math.round((v - lo) / (hi - lo) * steps) / (double) steps * (hi - lo);
        if (scale != null) {
            double[] deg = scaleDegrees(scale);
            if (deg != null) {
                double best = v, bd = Double.MAX_VALUE;
                for (double d : deg) for (int k = (int) Math.floor((v - d) / 12) - 1; k <= (int) Math.floor((v - d) / 12) + 1; k++) {
                    double c = d + 12 * k, dist = Math.abs(c - v);
                    if (dist < bd) { bd = dist; best = c; }
                }
                v = best;
            }
        }
        return v;
    }
    public String line() { return "bind " + sig + " " + layer + " " + param + (auto() ? "" : " " + fmtNum5(lo) + " " + fmtNum5(hi)) + (rel ? " rel" : "") + (map().isEmpty() ? "" : " " + map()) + (mute ? " off" : ""); }
}
