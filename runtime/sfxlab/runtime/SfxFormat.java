package sfxlab.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;

/** The .sfx line format: timeline clip lines, bench / family files (layer, range, bind, recipe, spell sections). */
public final class SfxFormat {
    private SfxFormat() {}

    public static String clipLine(Clip c) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "clip %s %d %d %.4f %.4f %d",
                c.name, c.type, c.track, c.start, c.dur, c.seed));
        for (int i = 0; i < c.p.length; i++)
            sb.append(String.format(Locale.ROOT, " %s=%.5f", key(c.type, i), c.p[i]));
        if (c.file != null) sb.append(" file=").append(c.file);
        if (c.vlink) sb.append(" vlink=1");
        sb.append(" keyed=").append(c.keyed);
        if (c.id != null) sb.append(" id=").append(c.id);   // only bench-born clips carry one: old files re-save byte-identical
        if (c.on != ON_NONE) sb.append(" on=").append(ON_NAMES[c.on]);
        return sb.append('\n').toString();
    }

    /** The clips of a timeline project's lines (loop / track lines fill the optional outs). */
    public static List<Clip> parseProject(List<String> lines, boolean[] loopOut, double[] tvolOut, boolean[] muteOut) {
        ArrayList<Clip> out = new ArrayList<>();
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length == 0 || t[0].isEmpty() || t[0].startsWith("#")) continue;
            if (t[0].equals("loop")) { if (loopOut != null && t.length > 1) loopOut[0] = t[1].equals("1"); continue; }
            if (t[0].equals("track") && t.length > 3) {
                int i = Integer.parseInt(t[1]);
                if (i >= 0 && i < TRACKS) {
                    if (tvolOut != null) tvolOut[i] = Double.parseDouble(t[2]);
                    if (muteOut != null) muteOut[i] = t[3].equals("1");
                }
                continue;
            }
            if (!t[0].equals("clip") || t.length < 7) continue;
            int type = Math.max(0, Math.min(TYPE_NAMES.length - 1, Integer.parseInt(t[2])));
            int track = Math.max(0, Math.min(TRACKS - 1, Integer.parseInt(t[3])));
            Clip c = new Clip(t[1], type, track,
                    Double.parseDouble(t[4]), Double.parseDouble(t[5]), Long.parseLong(t[6]));
            parseTokens(c, t, 7);
            out.add(c);
        }
        return out;
    }


    /** The key=value tail of a clip or layer line (bare numbers are legacy
     *  positional params). Unknown keys are skipped, so newer files load in
     *  older builds. */
    public static void parseTokens(Clip c, String[] t, int from) {
        int type = c.type;
        for (int i = from; i < t.length; i++) {
            int eq = t[i].indexOf('=');
            String k = eq >= 0 ? t[i].substring(0, eq) : legacyName(type, i - from);
            if (k == null) continue;
            String val = eq >= 0 ? t[i].substring(eq + 1) : t[i];
            switch (k) {
                case "file" -> c.file = val;
                case "vlink" -> c.vlink = val.equals("1");
                case "keyed" -> c.keyed = Integer.parseInt(val) & KEY_BOTH;
                case "id" -> c.id = val;
                case "on" -> c.on = val.equals("lock") ? ON_LOCK : val.equals("unlock") ? ON_UNLOCK : val.equals("accept") ? ON_ACCEPT : ON_NONE;
                case "mute" -> c.lmute = val.equals("1");
                default -> {
                    int pi = idxOf(type, k);
                    if (pi >= 0) c.p[pi] = Double.parseDouble(val);
                }
            }
        }
    }

    public static Bench parseBench(List<String> lines) {
        Bench b = new Bench();
        for (String line : lines) {
            String[] t = line.trim().split("\\s+");
            if (t.length == 0 || t[0].isEmpty()) continue;
            if (t[0].startsWith("#")) { if (!line.trim().startsWith("# SfxLab ")) b.comments.add(line.trim()); continue; }
            switch (t[0]) {
                case "root" -> { if (t.length > 1) b.root = Double.parseDouble(t[1]); }
                case "palette" -> { if (t.length > 1) b.palette = t[1]; }
                case "name" -> b.name = line.trim().length() > 5 ? line.trim().substring(5) : null;
                case "recipe" -> {   // recipe tier=1 [secret=1] [rtol=0.1] X3p1r0.7 Y2p0 ...   (axis, integer ratio, phase in quarters, reach target; @amp is the old spelling of r)
                    ArrayList<RegulatorCore.Comp> cs = new ArrayList<>();
                    for (int i = 1; i < t.length; i++) {
                        if (t[i].startsWith("tier=")) b.tier = Integer.parseInt(t[i].substring(5));
                        else if (t[i].startsWith("secret=")) b.secret = t[i].endsWith("1");
                        else if (t[i].startsWith("rtol=")) b.rtol = Double.parseDouble(t[i].substring(5));
                        else {
                            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("([XYZxyz])(\\d+)p(\\d)(?:[r@]([0-9.]+))?").matcher(t[i]);
                            if (mm.matches()) cs.add(new RegulatorCore.Comp("xyz".indexOf(Character.toLowerCase(mm.group(1).charAt(0))), Integer.parseInt(mm.group(2)), Integer.parseInt(mm.group(3)) % 4, mm.group(4) != null ? Math.max(0.05, Math.min(1, Double.parseDouble(mm.group(4)))) : 1));
                        }
                    }
                    if (b.tier < 1 || b.tier > 3) b.tier = 1;
                    b.comps = cs.toArray(new RegulatorCore.Comp[0]);
                }
                case "note" -> b.notes.add(line.trim().length() > 5 ? line.trim().substring(5) : "");
                case "layer" -> {
                    if (t.length < 6) continue;
                    int type = Math.max(0, Math.min(TYPE_NAMES.length - 1, Integer.parseInt(t[3])));
                    double dur = Double.parseDouble(t[4]);
                    Clip c = new Clip(t[2], type, 0, 0, dur > 0 ? dur : ENDLESS, Long.parseLong(t[5]));
                    c.id = t[1];
                    parseTokens(c, t, 6);
                    if (c.on == ON_NONE) c.dur = ENDLESS;
                    b.layers.add(c);
                }
                case "range" -> {
                    if (t.length < 5) continue;
                    Clip c = b.byId(t[1]);
                    if (c == null) continue;
                    int pi = idxOf(c.type, t[2]);
                    if (pi < 0) continue;
                    if (c.range == null) c.range = new HashMap<>();
                    c.range.put(pi, new double[]{Double.parseDouble(t[3]), Double.parseDouble(t[4])});
                    if (t.length > 5) { if (c.rnote == null) c.rnote = new HashMap<>(); c.rnote.put(pi, String.join(" ", Arrays.copyOfRange(t, 5, t.length))); }
                }
                case "bind" -> {   // bind signal id|* param [lo hi] [rel] [steps=N | scale=name]
                    if (t.length < 4) continue;
                    Bind bd = new Bind(t[1], t[2], t[3], Double.NaN, Double.NaN, false);
                    int nums = 0;
                    for (int i = 4; i < t.length; i++) {
                        if (t[i].equals("rel")) bd.rel = true;
                        else if (t[i].equals("off")) bd.mute = true;
                        else if (t[i].contains("=")) bd.setMap(t[i]);
                        else { try { double v = Double.parseDouble(t[i]); if (nums == 0) bd.lo = v; else if (nums == 1) bd.hi = v; nums++; } catch (NumberFormatException ignored) {} }
                    }
                    if (nums < 2) { bd.lo = Double.NaN; bd.hi = Double.NaN; }
                    b.binds.add(bd);
                }
                default -> {}
            }
        }
        return b;
    }

    public static String benchText(Bench b, double rootHz) { return benchText(b, rootHz, false); }
    /** A bench's lines; as a `section` (a spell inside a family file) without the header, `bench` and `root` lines. */
    public static String benchText(Bench b, double rootHz, boolean section) {
        StringBuilder sb = new StringBuilder();
        if (!section) {
            sb.append("# SfxLab family v2: layer id name type dur seed key=value... (dur 0 = endless) · range id param lo hi [note] · bind signal id|* param [lo hi] [rel] [steps=N | scale=<chord>] · note text · then `spell <id>` sections: name, recipe, the spell's layers at their lock values, its binds and notes\n");
            sb.append("bench 1\n");
            sb.append(String.format(Locale.ROOT, "root %.4f%n", rootHz));
        }
        for (String c : b.comments) sb.append(c).append('\n');
        if (b.name != null) sb.append("name ").append(b.name).append('\n');
        if (b.comps != null) sb.append(recipeLine(b)).append('\n');
        for (Clip c : b.layers) sb.append(layerLine(c));
        for (Clip c : b.layers)
            if (c.range != null)
                for (int pi : new TreeSet<>(c.range.keySet())) {
                    double[] r = c.range.get(pi);
                    sb.append("range ").append(c.id).append(' ').append(key(c.type, pi)).append(' ').append(fmtNum5(r[0])).append(' ').append(fmtNum5(r[1]));
                    String nt = c.rnote != null ? c.rnote.get(pi) : null;
                    if (nt != null && !nt.isBlank()) sb.append(' ').append(nt.trim());
                    sb.append('\n');
                }
        for (Bind bd : b.binds) sb.append(bd.line()).append('\n');
        for (String n : b.notes) sb.append("note ").append(n).append('\n');
        return sb.toString();
    }

    public static Family parseFamily(List<String> lines) {
        Bench pal = null; ArrayList<Spell> sps = new ArrayList<>();
        ArrayList<String> cur = new ArrayList<>(); String id = null;
        for (int i = 0; i <= lines.size(); i++) {
            String line = i < lines.size() ? lines.get(i) : null, t = line != null ? line.trim() : null;
            if (line == null || t.startsWith("spell ")) {
                Bench b = parseBench(cur);
                if (id == null) pal = b; else sps.add(makeSpell(id, b));
                cur = new ArrayList<>();
                if (line != null) id = t.substring(6).trim();
            } else cur.add(line);
        }
        return new Family(pal, sps);
    }
    public static Spell makeSpell(String id, Bench b) {
        Spell sp = new Spell();
        sp.id = id; sp.bench = b;
        if (b.name == null) b.name = id.replace('_', ' ');
        sp.name = b.name;
        if (b.comps != null && b.comps.length > 0) { sp.recipe = new RegulatorCore.Recipe(id, sp.name, b.tier, "", b.secret, b.comps); sp.recipe.rtol = b.rtol; }
        return sp;
    }
    public static String familyText(Bench pal, java.util.List<Spell> sps, double rootHz) {
        StringBuilder sb = new StringBuilder(benchText(pal, rootHz, false));
        for (Spell sp : sps) sb.append("\nspell ").append(sp.id).append('\n').append(benchText(sp.bench, rootHz, true));
        return sb.toString();
    }

    public static String recipeLine(Bench b) {
        StringBuilder sb = new StringBuilder("recipe tier=" + b.tier + (b.secret ? " secret=1" : "") + (b.rtol != RegulatorCore.DEFAULT_RTOL ? " rtol=" + fmtNum5(b.rtol) : ""));
        for (RegulatorCore.Comp c : b.comps) sb.append(' ').append("XYZ".charAt(c.axis())).append(c.n()).append('p').append(c.phase()).append(c.amp() != 1 ? "r" + fmtNum5(c.amp()) : "");
        return sb.toString();
    }
    public static String layerLine(Clip c) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "layer %s %s %d %.4f %d", c.id, c.name, c.type, c.on == ON_NONE ? 0 : c.dur, c.seed));
        for (int i = 0; i < c.p.length; i++) sb.append(String.format(Locale.ROOT, " %s=%.5f", key(c.type, i), c.p[i]));
        if (c.file != null) sb.append(" file=").append(c.file);
        sb.append(" keyed=").append(c.keyed);
        if (c.on != ON_NONE) sb.append(" on=").append(ON_NAMES[c.on]);
        if (c.lmute) sb.append(" mute=1");
        return sb.append('\n').toString();
    }
}
