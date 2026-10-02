import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import sfxlab.runtime.*;
import sfxlab.runtime.Clip;   // over javax.sound.sampled.Clip
import static sfxlab.runtime.Sfx.*;
import static sfxlab.runtime.Samples.*;
import static sfxlab.runtime.Partials.*;
import static sfxlab.runtime.SfxFormat.*;
import static sfxlab.runtime.BenchMixer.*;

/**
 * SfxLab — SynthLab reworked into a timeline sound-effect workbench.
 *
 * The spellcasting game is gone; what remains is the sound engine, pulled
 * apart into orthogonal pieces and laid on a timeline:
 *
 *   GENERATORS (a clip is one generator playing for a span of time)
 *     cloud    the SynthLab voice cloud: N wavetable voices wandering around
 *              a chord, plus a sine sub. gather/drift/timbre are now
 *              independent knobs (timbre was derived from gather+stab+purity;
 *              drift is what stability used to hide).
 *     tones    two clean tones at an interval (the old channel B), with
 *              optional detune pair, seek-shimmer, and an FM pair (fm ratio /
 *              fm depth) for bells, metal, and robot chirps. FM depth rides
 *              the envelope, so percussive clips get the classic bright
 *              strike that mellows into the ring.
 *     noise    noise with a color knob: 0 white (hiss) → pink (wind) →
 *              1 brown (rumble). No longer welded to "purity".
 *     sparkle  the stochastic ping shower, with density/decay/spread and its
 *              own FM (each ping's brightness decays with its own ring-out).
 *     pluck    a Karplus-Strong string: a noise burst circulating in a tuned
 *              delay line — guitars, harps, koto. damp = string warmth,
 *              sustain = ring length, pick = attack brightness, interval
 *              adds a second string (root+7 = a power chord that distorts
 *              as one, which is THE overdriven-chord sound).
 *     sample   a recorded audio file (W imports it into samples/
 *              and drops a clip; anything ffmpeg can read is accepted —
 *              mp3/ogg/video files are decoded to .wav on the way in).
 *              Two pitch modes:
 *                tape      pitch = playback speed (+12 = double speed and an
 *                          octave up, like any classic sampler)
 *                keep len  granular: pitch shifts while the length stays put
 *                          (two overlapping 50 ms grains; each new grain is
 *                          slid to line up with the one still sounding, so
 *                          the crossfades don't flutter), and `speed` then
 *                          time-stretches without touching pitch — 0 freezes
 *                          the moment under the read head
 *              pitch sweeps/LFO bend it in either mode; the whole effect
 *              chain (drive, flange, filter, sends) applies as usual. Sample
 *              clips draw their waveform, so where the recording actually
 *              ends inside the clip is visible.
 *     choir    a sample turned into a voice cloud: N copies of the recording,
 *              each pitched through its own keep-len grain shifter by a
 *              ratio that (like cloud) sits somewhere random when gather is
 *              0 and on its chord slot when gather is 1 — so `gather swp`
 *              from 0 to 1 is a THX-style convergence of a real recording.
 *              wander bounds the random spread (±semitones: a shifted vocal
 *              stops sounding human past ~7), chord slots stay within an
 *              octave of the source, scatter spreads the voices' read
 *              positions across the recording so unison voices are different
 *              moments of it rather than one moment doubled, drift lets the
 *              ungathered voices keep wandering. Always loops. C converts a
 *              selected sample clip to choir (and back); the file, start and
 *              speed carry over.
 *
 *   COMMON PER-CLIP PARAMS (every clip gets its own, nothing is global)
 *     level, attack, release          envelope inside the clip
 *     pitch, pitch swp                base pitch + linear sweep over the clip
 *     cutoff, cutoff swp, resonance   per-clip state-variable filter
 *     filter                          LP / BP / HP mode
 *     pan, pan swp                    stereo placement + sweep
 *     echo                            send into the shared ping-pong delay
 *     reverb                          send into the shared room (freeverb-
 *                                     style combs+allpasses) — space & bloom
 *     drive                           waveshaping distortion ahead of the
 *                                     filter (the filter then acts as the
 *                                     cabinet) — overdrive, growl, grit
 *     flange, flange fb               swept comb filter (a few ms of delay
 *                                     mixed back in, swept by the clip's LFO:
 *                                     sine = jet whoosh, random = arc jitter;
 *                                     feedback sharpens it metallic)
 *     phaser, phaser rate, ph stages, ph pos  a chain of all-pass stages swept by their
 *                                     own slow LFO and mixed with the dry signal:
 *                                     a few moving notches — the swirl on
 *                                     magic, jets, and anything "alive" (the
 *                                     flanger is many evenly spaced notches;
 *                                     the phaser is few, unevenly spaced)
 *     duck, duck from                 sidechain: this clip's level dips by
 *                                     `duck` whenever track `duck from` gets
 *                                     loud (2 ms attack, 150 ms release) —
 *                                     a rumble that steps aside for the hit
 *                                     reads clearer and the recovery feels
 *                                     like a swell
 *     lfo rate/shape/pos, lfo>pitch/cut/amp   per-clip LFO (sine / triangle /
 *                                     square / random S&H) — vibrato, sirens,
 *                                     tremolo, filter wobble. Depths default
 *                                     to 0, i.e. off.
 *     env curve                       0 = linear ramps; positive = percussive
 *                                     (exponential-ish decay — coins, pings,
 *                                     impacts); negative = swelling
 *
 *   The old one-shot gestures are now just palette presets built from these
 *   primitives — riser = tones + pitch sweep up, zap = short gritty tones with
 *   a -54 st sweep, woosh = noise through a swept bandpass with a pan sweep —
 *   so every one of them is editable after you drop it.
 *
 * TIMELINE: 6 tracks, drag clips to move (vertically to change track), drag
 * the right edge to resize. Click a track number to mute it; drag the little
 * bar under the number for that track's volume (saved with the project,
 * honored by export). The playhead is the seeker: click/drag in the ruler or
 * empty track space to scrub it.
 *
 * VIDEO REFERENCE: V attaches a screen recording (anything ffmpeg reads).
 * Its frames are extracted once into video-cache/ and shown as a
 * filmstrip lane under the ruler (ctrl-drag slides it in time, negative
 * starts are fine) and in the monitor window (M), which follows the
 * playhead. [ and ] step one frame. The video's own audio track lands on
 * the last track as a reference sample clip LINKED to the picture: dragging
 * either one moves both, so they never drift apart (the video menu links or
 * unlinks any selected clip; linked clips get a blue left edge).
 *
 * CUTTING UP RECORDINGS: X splits the selected clip at the playhead — the
 * right half resumes from the right spot in the recording and any sweeps
 * stay continuous across the cut. Dragging a clip's LEFT edge trims it (the
 * audio stays put, like a DAW). Clips and the video may start before 0:
 * whatever hangs off the left is simply never heard, so a long recording can
 * be shoved left until the moment you want sits at 0.
 *
 * PARTIALS: C cycles a sample clip -> choir -> partials -> sample. A partials
 * clip is a sines + residual model of the recording: every stable sinusoid is
 * tracked through the spectrogram and replayed by an oscillator bank, and what
 * is left after notching those peaks out (noise, crackle, breath) plays as the
 * residual through the keep-len grain shifter at ratio 1. `pitch` (and the
 * global key) transposes ONLY the partials, so an arc, a hum or an ice ring
 * sings a new note while its texture stays exactly as recorded. `sines` and
 * `residual` mix the two halves; `floor` / `min len` are the analysis
 * thresholds (re-analysed in the background when changed); `speed` 0 freezes.
 * Same algorithm as tools/partials.py.
 *
 * ROOT & TUNING: a project has a root note (`root` line, default C2 =
 * 65.41 Hz, the mod's crystal degree I; ctrl+R changes it). Key 0 means "as
 * authored" and the convention is that every pitched layer is authored ON
 * the root, so the mod can transpose by (scale degree + 12·octave + cents)
 * with --key. Synth clips are absolute (pitch 0 = 110 Hz). Recordings are
 * relative to whatever they were recorded at, so the panel shows each
 * sample/choir/partials clip's estimated fundamental (from its partial
 * analysis) after the clip's pitch offset, as Hz and a note name. R moves
 * the selected clip's pitch to the nearest octave of the root; shift+R to a
 * chosen degree above it (7 = a fifth) for chord-tone layers.
 *
 * SAMPLE BROWSER: A docks a panel on the right listing everything under
 * samples with a filter box and tonal badges (share, note, length,
 * computed in the background and cached), preview / stop / play-on-select,
 * and one-click adds: as a sample clip at the playhead (double-click or
 * ENTER) or as a partials clip already tuned to the root. ESC hands the
 * keyboard back to the timeline. Its state is remembered in lab.cfg.
 *
 * HARMONIC CONTROLS (partials clips, and the tones `bank`): each tracked
 * partial knows its ratio to the recording's fundamental and whether it sits
 * on the harmonic series. `odd/even` fades one side of the series (hollow ↔
 * odd-less), `tilt` brightens or darkens by ratio, `purity` scales the
 * inharmonic partials (0 = a clean series, 2 = more alien), `stretch` warps
 * the spacing toward bells and metal, `gather` pulls every partial to the
 * nearest pitch class of `chord`, `shimmer` is a slow random detune per
 * partial. `root shift` moves the classification root by semitones when the
 * estimate picked the wrong octave or a harmonic (the value shows the note it
 * lands on); `harm tol` is how far a partial may miss an integer ratio and
 * still count (detuned unisons want 5-8 %). The residual is untouched by all
 * of them, so they only bite on tonal material (see the browser badges). A tones clip gets the same knobs
 * on an additive bank of 24 harmonics (`bank` level, 0 = the old tone), so
 * a synth bed that has run out of range can be replaced by a tone with the
 * same treatment. Everything needs the fundamental to be right: check the
 * panel readout, R pins it.
 *
 * BENCH (H): the regulator palette view, for the Harmonic Regulator's
 * sound (docs/HARMONIC-REGULATOR.md). The timeline gives way to a list of
 * LAYERS: clips with no position that all sound at once while the bench
 * plays, looping for ever; a layer marked one-shot instead fires on the
 * lock or unlock event (right-click its row). SIGNALS from the machine
 * (arm ratios / reach, radiance, consonance, tension, drive, coherence,
 * score) drive layer params through BINDS: `bind tension synth1 drive` moves
 * synth1's drive over its marked range as tension goes 0..1; `rel` binds add
 * to the layer's own value (pitch). Right-click a slider to mark the RANGE
 * that sounded good (with a note), or to bind a signal to it. A SPELL is a
 * `spell <id>` section of the family file holding the same layer ids at
 * their lock values (plus one-shots): as its score rises past 0.55 every
 * shared param blends from its searching value to the spell's, and layers
 * only the spell has fade in. Bound params are smoothed (~30 ms) so signals
 * never zipper. The regulator panel (J) docks the signal sliders (the
 * scrubber), the family picker, a score slider per spell with lock / unlock
 * buttons, and the bind tables: a table per bench (the palette's, then one
 * per spell) with an `on` box per bind and one per signal, so a bind or a
 * whole signal can be switched off and compared live instead of deleted
 * (`off` on the bind line); a filter box, sortable columns, and ctrl+C /
 * ctrl+V to carry bind lines between tables, layers and families. The
 * signal sliders and the spell rows fold. A FAMILY is one file, regulator/<family>.sfx:
 * the palette, then one section per spell. Edits to the loaded family
 * autosave to its working copy, regulator/.working/<family>.sfx, which
 * reopens with the family until S saves it into the family file (shift+S
 * saves as a new family, shift+O reverts to the saved file); a scratch
 * bench autosaves to bench.sfx and S saves it as a family. The `new spell` button adds a spell.
 * shift+H sends a timeline clip over as a layer; a layer's menu copies it back.
 *
 * MARKERS: K drops a named marker at the playhead (shift+K removes the
 * nearest; right-click one in the ruler to delete). Clip drags snap to
 * markers, so: step to the frame where the cue happens, K, drag the clip.
 *
 * LIBRARY: bank any clip you've dialed in for reuse across projects.
 *   B (or the "+ lib" button)  save the selected clip to the library under a
 *                              chosen name (library.sfx — same
 *                              format as projects; clip lines can even be
 *                              hand-copied between the two)
 *   Q (or the "library" button)  browse the library: click an entry to drop
 *                              a copy at the playhead; "manage" renames or
 *                              deletes entries
 *
 * KEYS
 *   1..9, 0     add palette clip at the playhead on the selected track
 *   SPACE       play/pause      ENTER rewind      L loop
 *   P           preview the selected clip solo
 *   E           export the mix as .wav or .ogg, with mono (Minecraft
 *               positional sounds must be mono), normalize and trim-tail
 *               options. The folder and options are remembered in
 *               lab.cfg — point it at the mod's sounds dir once
 *   V / M       attach a video / toggle the monitor window
 *   shift+M     hear the mix in mono, as the game plays it (one positional source)
 *   [ ]         step the playhead one video frame (50 ms without a video)
 *   K           add a marker at the playhead (shift+K removes the nearest)
 *   W           import a sample file (wav/aiff, or mp3/ogg/video via ffmpeg)
 *   C           convert the selected sample clip to a choir clip, or back
 *   S           save: stamp the timeline into a named .sfx. The working
 *               timeline itself always autosaves to project.sfx, so you name
 *               a sound once it exists — not up front. Re-stamping the same
 *               name doesn't re-confirm; a new name asks before overwriting.
 *   O           open a .sfx into the workspace (ctrl+Z brings back what was
 *               on the timeline before)
 *   N           clear the workspace (undoable)
 *   H           bench view (the regulator palette); shift+H sends the selected clip there
 *   J           regulator panel: signal sliders, signature, lock / unlock, binds, ranges, notes
 *   U           the machine: conduct the regulator as the game does (hold left = crescendo, right = diminuendo,
 *               middle or T = toggle, on the stage) and hear the bench answer
 *
 * FILE FORMATS: .sfx is the lab's editable source (the workspace autosaves
 * to project.sfx; S stamps named copies); exported .wav is what the mod
 * consumes. Family files (regulator/<family>.sfx) use the same line style
 * with `layer`, `range`, `bind`, `note` and `spell` lines, which the
 * timeline parser skips. parseProject/renderWav are static and headless, so the mod's
 * build can also batch-render .sfx files via --render, or embed this class
 * and synthesize at runtime.
 *   DEL         delete clip     D duplicate       arrows nudge / change track
 *   X           split the selected clip at the playhead
 *   ctrl+Z      undo            ctrl+shift+Z / ctrl+Y  redo
 *   G           toggle snap-to-grid (shift-drag inverts it while held)
 *   , / .       browse combos.txt entries   I  insert combo as clips
 *   ctrl+wheel  zoom            wheel scroll
 *
 * Run:  ./sfxlab                from the checkout (it compiles runtime/ into .build/runtime.jar when a source
 *       there changed, then runs this file against it). The folder holding SfxLab.java
 *       is the workspace (samples/, projects/, forge/, renders/, lab.cfg live in it).
 *       $SFXLAB_DIR overrides that; ~/synthlab is the fallback when run elsewhere.
 * Headless render:  ./sfxlab --render [project.sfx] [out.wav|out.ogg] [--mono] [--normalize] [--no-trim] [--key N]
 * Headless check:   ./sfxlab --check [family ...]   format problems in the family files (exit 1 if any)
 * Headless bake:    ./sfxlab --bake [family ...] [--out dir] [--quality 0-10]   what the mod ships: forge/baked/
 * Headless forge:   ./sfxlab --forge <sound.ogg|project.sfx> [--name n] [--root C2] [--register nearest|0|1|2] [--keys 0,2,4,...] [--wav] [--stereo]
 */
public class SfxLab extends JPanel {

    /** The workspace: $SFXLAB_DIR if set; else the current directory when it is
     *  a checkout (SfxLab.java or lab.cfg beside it); else ~/synthlab. samples/,
     *  projects/, forge/, renders/ and lab.cfg all live inside it. */
    static final Path DIR = workspaceDir();
    static Path workspaceDir() {
        String env = System.getenv("SFXLAB_DIR");
        if (env != null && !env.isBlank()) return Paths.get(env).toAbsolutePath().normalize();
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve("SfxLab.java")) || Files.exists(cwd.resolve("lab.cfg"))) return cwd;
        return Paths.get(System.getProperty("user.home"), "synthlab");
    }
    static final Path PROJECT_FILE = DIR.resolve("project.sfx");
    static final Path PROJECTS_DIR = DIR.resolve("projects");   // named .sfx stamps (S) and the open dialog (O)
    static final Path COMBO_FILE = DIR.resolve("combos.txt");
    static final Path LIB_FILE = DIR.resolve("library.sfx");


    /** Per-file analysis badges {f0, sines share, seconds} for a folder, computed
     *  on one low-priority thread (analysis only — nothing is kept in PARTS, so
     *  a big library doesn't pin its residuals in memory) and cached on disk. */
    static class BadgeCache {
        static final java.util.concurrent.ExecutorService SCAN = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "badge-scan"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t; });
        final Path root;
        final Map<String, double[]> map = new java.util.concurrent.ConcurrentHashMap<>();
        volatile int gen;
        BadgeCache(Path root) { this.root = root; load(); }
        Path file() { return FORGE_DIR.resolve(".badges-" + Integer.toHexString(root.toString().hashCode()) + ".txt"); }
        void load() {
            try {
                if (!Files.exists(file())) return;
                for (String l : Files.readAllLines(file())) {
                    String[] t = l.split("\\t");
                    if (t.length < 4) continue;
                    Path f = root.resolve(t[0]);
                    if (Files.exists(f) && Files.size(f) == Long.parseLong(t[1]))
                        map.put(t[0], new double[]{Double.parseDouble(t[2]), Double.parseDouble(t[3]), t.length > 4 ? Double.parseDouble(t[4]) : 0});
                }
            } catch (Exception e) { /* badges are a convenience */ }
        }
        void save() {
            try {
                Files.createDirectories(FORGE_DIR);
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, double[]> e : map.entrySet()) {
                    Path f = root.resolve(e.getKey());
                    if (Files.exists(f)) sb.append(String.format(Locale.ROOT, "%s\t%d\t%.3f\t%.4f\t%.3f%n", e.getKey(), Files.size(f), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
                }
                Files.writeString(file(), sb.toString());
            } catch (IOException e) { /* ditto */ }
        }
        /** Analyses whatever isn't cached yet; onProgress runs on the EDT after each file. */
        void scan(List<String> files, Runnable onProgress) {
            int g = ++gen;
            SCAN.submit(() -> {
                for (String f : files) {
                    if (g != gen) return;   // a rescan superseded us
                    if (map.containsKey(f)) continue;
                    try {
                        Partials pa = analyzePartials(root.resolve(f).toString(), 14, 46);
                        map.put(f, new double[]{pa.f0, pa.share, pa.res[0].length / (double) SR});
                    } catch (Exception e) { map.put(f, new double[]{0, 0, 0}); }
                    SwingUtilities.invokeLater(onProgress);
                }
                save();
            });
        }
        /** One-off analysis for a file (cached afterwards). */
        double[] get(String f) {
            double[] b = map.get(f);
            if (b == null) {
                try { Partials pa = analyzePartials(root.resolve(f).toString(), 14, 46); b = new double[]{pa.f0, pa.share, pa.res[0].length / (double) SR}; }
                catch (Exception e) { b = new double[]{0, 0, 0}; }
                map.put(f, b);
            }
            return b;
        }
    }
    /** List cell: "share% note  dur  path", coloured by how tonal the file is. */
    static Component badgeCell(String v, double[] b, Font font, boolean sel) {
        String tag = b == null ? "" : String.format(Locale.ROOT, "%3.0f%% %-4s %4.1fs", 100 * b[1], b[0] > 0 ? noteName(b[0]).split(" ")[0] : "-", b[2]);
        JLabel l = new JLabel(String.format("%-16s %s", tag, v));
        l.setFont(font); l.setOpaque(true);
        l.setBackground(sel ? new Color(60, 70, 90) : Color.BLACK);
        l.setForeground(b == null ? Color.GRAY : b[1] >= 0.5 ? new Color(90, 255, 190) : b[1] >= 0.2 ? new Color(255, 215, 120) : new Color(170, 170, 170));
        return l;
    }
    /** Plays a wav through javax.sound, stopping `prev` first; returns the new clip. */
    static javax.sound.sampled.Clip playWav(Path wav, javax.sound.sampled.Clip prev) throws Exception {
        if (prev != null) { try { prev.stop(); prev.close(); } catch (Exception ignored) {} }
        AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile());
        javax.sound.sampled.Clip c = AudioSystem.getClip();
        c.open(in);
        c.start();
        return c;
    }

    /** The sample browser, docked on the right of the workbench (A toggles it):
     *  a filtered list of everything under samples with tonal badges,
     *  preview, and one-click adds to the timeline. */
    static class SampleBrowser extends JPanel {
        final SfxLab lab;
        final DefaultListModel<String> model = new DefaultListModel<>();
        final List<String> all = new ArrayList<>();
        final JList<String> list = new JList<>(model);
        final JTextField filter = new JTextField();
        final JLabel countL = new JLabel();
        final JCheckBox autoB = new JCheckBox("play on select", false);
        BadgeCache badges = new BadgeCache(SAMPLE_DIR);
        javax.sound.sampled.Clip player;
        final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "browser-bg"); t.setDaemon(true); return t; });

        SampleBrowser(SfxLab lab) {
            this.lab = lab;
            setLayout(new BorderLayout(4, 4));
            setPreferredSize(new Dimension(400, 100));
            setBackground(Color.BLACK);
            JPanel top = new JPanel(new BorderLayout(4, 2));
            top.add(new JLabel("samples/  "), BorderLayout.WEST);
            top.add(filter, BorderLayout.CENTER);
            top.add(countL, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            list.setBackground(Color.BLACK);
            list.setCellRenderer((jl, v, i, sel, foc) -> badgeCell(v, badges.map.get(v), list.getFont(), sel));
            list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting() && autoB.isSelected()) preview(); });
            list.addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) addSample(); } });
            list.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "back");
            list.getActionMap().put("back", new AbstractAction() { public void actionPerformed(ActionEvent e) { if (lab.swapTarget != null) { lab.swapTarget = null; lab.toast("swap cancelled"); } lab.requestFocusInWindow(); } });
            list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "add");
            list.getActionMap().put("add", new AbstractAction() { public void actionPerformed(ActionEvent e) { addSample(); } });
            list.getInputMap().put(KeyStroke.getKeyStroke("SPACE"), "play");
            list.getActionMap().put("play", new AbstractAction() { public void actionPerformed(ActionEvent e) { preview(); } });
            add(new JScrollPane(list), BorderLayout.CENTER);
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
            });
            filter.addActionListener(e -> { list.requestFocusInWindow(); if (model.size() > 0 && list.getSelectedIndex() < 0) list.setSelectedIndex(0); });
            JPanel bottom = new JPanel(new GridLayout(0, 1, 2, 2));
            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)), row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (Object[] b : new Object[][]{{"▶ preview", (Runnable) this::preview}, {"stop", (Runnable) this::stop}, {"add at playhead", (Runnable) this::addSample},
                                             {"add as partials", (Runnable) this::addPartials}}) {
                JButton jb = new JButton((String) b[0]);
                jb.addActionListener(e -> ((Runnable) b[1]).run());
                (b[0].equals("▶ preview") || b[0].equals("stop") ? row1 : row2).add(jb);
            }
            row1.add(autoB);
            JButton imp = new JButton("import file… (W)"), rescan = new JButton("rescan");
            imp.addActionListener(e -> { lab.importSample(); rescan(); });
            rescan.addActionListener(e -> rescan());
            row2.add(imp); row1.add(rescan);
            bottom.add(row1); bottom.add(row2);
            bottom.add(new JLabel("  dbl-click / ENTER: add · SPACE: play · ESC: back to timeline"));
            add(bottom, BorderLayout.SOUTH);
            rescan();
        }
        void rescan() {
            all.clear();
            try (var st = Files.walk(SAMPLE_DIR)) {
                st.filter(Files::isRegularFile).map(f -> SAMPLE_DIR.relativize(f).toString().replace('\\', '/'))
                  .filter(f -> !f.startsWith(".") && !f.contains("/.") && f.matches("(?i).*\\.(wav|ogg|mp3|flac|aiff?|m4a|au)$"))
                  .sorted(Comparator.comparing((String f) -> f.startsWith("_")).thenComparing(f -> f)).forEach(all::add);
            } catch (IOException e) { lab.toast("samples scan failed: " + e); }
            badges = new BadgeCache(SAMPLE_DIR);
            refilter();
        }
        void refilter() {
            String q = filter.getText().trim().toLowerCase(Locale.ROOT);
            model.clear();
            for (String f : all) if (q.isEmpty() || f.toLowerCase(Locale.ROOT).contains(q)) model.addElement(f);
            countL.setText(model.size() + (q.isEmpty() ? " files" : " / " + all.size()));
            // badge the files on screen first, then the rest of the library
            List<String> order = new ArrayList<>();
            for (int i = 0; i < model.size(); i++) order.add(model.get(i));
            for (String f : all) if (!order.contains(f)) order.add(f);
            badges.scan(order, list::repaint);
        }
        String sel() { return list.getSelectedValue(); }
        void preview() {
            String f = sel();
            if (f == null) return;
            bg.submit(() -> { try { player = playWav(decodedPath(samplePath(f)), player); } catch (Exception e) { lab.toast("preview failed: " + e); } });
        }
        void stop() { if (player != null) { try { player.stop(); player.close(); } catch (Exception ignored) {} player = null; } }
        void addSample() {
            String f = sel();
            if (f == null) return;
            Clip t = lab.swapTarget;
            if (t != null) {   // armed from a bench row's menu: this pick replaces that layer's recording
                lab.swapTarget = null;
                if (lab.benchOn && lab.bench.layers.contains(t)) { lab.swapSource(t, f); lab.requestFocusInWindow(); return; }
                lab.toast("the layer to swap is gone; " + f + " added instead");
            }
            lab.pushUndo("");
            Clip c = lab.addSampleClip(f, lab.selTrack, lab.playPos);
            if (c != null) { lab.toast(f + " on track " + (lab.selTrack + 1)); lab.requestFocusInWindow(); }
        }
        /** Adds the file as a partials clip tuned to the nearest octave of the root. */
        void addPartials() {
            String f = sel();
            if (f == null) return;
            bg.submit(() -> {
                double[] b = badges.get(f);
                double tune = 0;
                if (b[0] > 0) {
                    double target = lab.rootHz * Math.pow(2, Math.round(Math.log(b[0] / lab.rootHz) / Math.log(2)));
                    tune = 12 * Math.log(target / b[0]) / Math.log(2);
                }
                double t = tune;
                SwingUtilities.invokeLater(() -> {
                    try {
                        double dur = sample(f)[0].length / (double) SR;
                        if (!lab.benchOn) lab.pushUndo("");
                        Clip c = new Clip(forgeName(Paths.get(f).getFileName().toString()), PARTIALS, lab.selTrack, lab.playPos, dur, lab.uiRng.nextLong());
                        c.file = f; c.p[P_PITCH] = t; c.p[P_ATT] = 0.005; c.p[P_REL] = 0.05;
                        if (lab.benchOn) c.p[NCOMMON + PA_LOOP] = 1;
                        lab.place(c);
                        partials(c, false);
                        lab.toast(String.format(Locale.ROOT, "%s as partials %s, tuned %+.2f st%s", f, lab.benchOn ? "layer " + c.id : "on track " + (lab.selTrack + 1), t, b[0] > 0 ? " -> " + noteName(b[0] * Math.pow(2, t / 12)) : " (no clear pitch)"));
                        lab.requestFocusInWindow();
                    } catch (Exception e) { lab.toast("add failed: " + e); }
                });
            });
        }
    }

    /** The forge window: a read-only mirror of the mod's sounds folder on the
     *  left, the selected sound's analysis in the middle, the promotion form
     *  on the right. Everything it does goes through forge() and is staged in
     *  forge/<name>/. */
    static class Forge extends JPanel {
        final SfxLab lab;
        JFrame frame;
        final DefaultListModel<String> model = new DefaultListModel<>();
        final List<String> all = new ArrayList<>();
        final JList<String> list = new JList<>(model);
        final JTextField filter = new JTextField(), rootF, keysF, nsF, epF, ppF;
        final JLabel folderL = new JLabel();
        final JComboBox<String> regBox = new JComboBox<>(new String[]{"nearest octave", "root (C2)", "+1 oct (C3)", "+2 oct (C4)", "+3 oct (C5)"});
        final JCheckBox oggB = new JCheckBox("ogg", true), monoB = new JCheckBox("mono", true);
        final JSpinner keySpin = new JSpinner(new SpinnerNumberModel(7, -24, 36, 1));
        final JTextArea log = new JTextArea(8, 30);
        final JButton runB = new JButton("Run promotion"), openB = new JButton("open in workbench"), tlB = new JButton("promote current timeline…");
        BadgeCache badges;
        final java.util.concurrent.ExecutorService bg = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "forge-bg"); t.setDaemon(true); return t; });
        String selected;            // rel path in the mirror
        Partials selPa; BufferedImage selImg; double selDur;
        javax.sound.sampled.Clip player;
        final JPanel card = new JPanel() {
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setColor(Color.BLACK); g.fillRect(0, 0, getWidth(), getHeight());
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
                if (selected == null) { g.setColor(Color.GRAY); g.drawString("select a sound on the left, or promote the current timeline", 14, 24); return; }
                g.setColor(new Color(245, 235, 215));
                g.drawString(selected, 14, 24);
                g.setColor(Color.GRAY);
                if (selPa == null) g.drawString("analysing…", 14, 44);
                else {
                    double f0 = selPa.f0;
                    g.drawString(String.format(Locale.ROOT, "%.2f s   %d partials   %.0f%% sines   fundamental %s%s", selDur, selPa.nTracks, 100 * selPa.share,
                            f0 > 0 ? String.format(Locale.ROOT, "~%.1f Hz = %s", f0, noteName(f0)) : "none",
                            f0 > 0 && selPa.share < 0.2 ? " (faint)" : ""), 14, 44);
                    double tune = tuneFor(selPa.f0);
                    if (f0 > 0) g.drawString(String.format(Locale.ROOT, "promotion tunes it %+.2f st -> %s, then bakes %d keys", tune, noteName(f0 * Math.pow(2, tune / 12)), keys().length), 14, 62);
                }
                if (selImg != null) {
                    int w = getWidth() - 28, h = Math.max(60, getHeight() - 90);
                    g.drawImage(selImg, 14, 76, w, h, null);
                    g.setColor(new Color(255, 255, 255, 90));
                    g.drawString("0–6 kHz, cyan = tracked partials", 18, 76 + h - 6);
                }
            }
        };

        Forge(SfxLab lab) {
            this.lab = lab;
            badges = new BadgeCache(lab.mirrorRoot());
            setLayout(new BorderLayout(6, 6));
            setBackground(Color.BLACK);
            // left: mirror
            JPanel left = new JPanel(new BorderLayout(4, 4));
            JPanel top = new JPanel(new BorderLayout(4, 4));
            JButton folderB = new JButton("folder…");
            folderB.addActionListener(e -> pickFolder());
            top.add(folderL, BorderLayout.CENTER); top.add(folderB, BorderLayout.EAST);
            top.add(filter, BorderLayout.SOUTH);
            left.add(top, BorderLayout.NORTH);
            list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            list.setCellRenderer((jl, v, i, sel, foc) -> badgeCell(v, badges.map.get(v), list.getFont(), sel));
            list.setBackground(Color.BLACK);
            list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) select(list.getSelectedValue()); });
            left.add(new JScrollPane(list), BorderLayout.CENTER);
            left.add(tlB, BorderLayout.SOUTH);
            left.setPreferredSize(new Dimension(360, 100));
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
            });
            add(left, BorderLayout.WEST);
            // middle: card + preview buttons
            JPanel mid = new JPanel(new BorderLayout(4, 4));
            mid.add(card, BorderLayout.CENTER);
            JPanel play = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            for (String[] b : new String[][]{{"play original", "orig"}, {"sines", "sines"}, {"residual", "resid"}, {"stop", "stop"}}) {
                JButton jb = new JButton(b[0]);
                jb.addActionListener(e -> preview(b[1]));
                play.add(jb);
            }
            JButton atKey = new JButton("at key");
            atKey.addActionListener(e -> preview("key"));
            play.add(atKey); play.add(keySpin); play.add(new JLabel("st"));
            openB.addActionListener(e -> openInWorkbench());
            mid.add(play, BorderLayout.SOUTH);
            add(mid, BorderLayout.CENTER);
            // right: form + log
            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(2, 4, 2, 4); gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL;
            rootF = new JTextField(noteName(lab.rootHz).split(" ")[0], 8);
            keysF = new JTextField(Arrays.toString(FORGE_KEYS).replaceAll("[\\[\\] ]", ""), 22);
            nsF = new JTextField("bubbys_world", 12); epF = new JTextField("spell_", 8); ppF = new JTextField("spells/", 8);
            int row = 0;
            for (Object[] r : new Object[][]{{"root", rootF}, {"register", regBox}, {"keys (st above root)", keysF},
                                             {"namespace", nsF}, {"event prefix", epF}, {"path prefix", ppF}}) {
                gc.gridx = 0; gc.gridy = row; gc.weightx = 0; form.add(new JLabel((String) r[0]), gc);
                gc.gridx = 1; gc.weightx = 1; form.add((Component) r[1], gc);
                row++;
            }
            JPanel fmt = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); fmt.add(oggB); fmt.add(monoB);
            gc.gridx = 0; gc.gridy = row; form.add(new JLabel("format"), gc); gc.gridx = 1; form.add(fmt, gc); row++;
            gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; form.add(runB, gc); row++;
            gc.gridx = 0; gc.gridy = row; gc.gridwidth = 2; form.add(openB, gc); row++;
            runB.addActionListener(e -> run());
            JPanel right = new JPanel(new BorderLayout(4, 4));
            right.add(form, BorderLayout.NORTH);
            log.setEditable(false); log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            log.setBackground(Color.BLACK); log.setForeground(new Color(180, 180, 180));
            right.add(new JScrollPane(log), BorderLayout.CENTER);
            right.setPreferredSize(new Dimension(400, 100));
            add(right, BorderLayout.EAST);
            tlB.addActionListener(e -> promoteTimeline());
            rootF.addActionListener(e -> card.repaint());
            regBox.addActionListener(e -> card.repaint());
            rescan();
        }

        void open() {
            if (frame == null) {
                frame = new JFrame("SfxLab forge — promote sounds for the mod");
                frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                frame.add(this);
                frame.setSize(1400, 720);
                frame.setLocationRelativeTo(lab);
            }
            frame.setVisible(true);
            frame.toFront();
        }
        void logLine(String s) { SwingUtilities.invokeLater(() -> { log.append(s + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }
        double rootHz() { double r = parseNote(rootF.getText()); return Double.isNaN(r) || r <= 0 ? lab.rootHz : r; }
        Integer register() { int i = regBox.getSelectedIndex(); return i == 0 ? null : i - 1; }
        double tuneFor(double f0) {
            if (f0 <= 0) return 0;
            double root = rootHz();
            Integer reg = register();
            double target = reg == null ? root * Math.pow(2, Math.round(Math.log(f0 / root) / Math.log(2))) : root * Math.pow(2, reg);
            return 12 * Math.log(target / f0) / Math.log(2);
        }
        int[] keys() {
            try { return Arrays.stream(keysF.getText().split("[,\\s]+")).filter(x -> !x.isEmpty()).mapToInt(x -> Integer.parseInt(x.trim())).toArray(); }
            catch (NumberFormatException e) { logLine("bad key list, using the default pentatonic set"); return FORGE_KEYS; }
        }

        void pickFolder() {
            JFileChooser fc = new JFileChooser(lab.mirrorRoot().toFile());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) { lab.forgeMirror = fc.getSelectedFile().toString(); lab.saveCfg(); rescan(); }
        }
        void rescan() {
            all.clear();
            if (lab.forgeMirror.isBlank()) {
                badges = new BadgeCache(FORGE_DIR);
                folderL.setText("(no folder yet)");
                folderL.setToolTipText("folder… picks the mod's sounds directory; remembered in lab.cfg as forge_mirror");
                logLine("no mirror folder set — folder… to pick the mod's sounds directory");
                refilter();
                return;
            }
            Path root = lab.mirrorRoot();
            badges = new BadgeCache(root);
            folderL.setText(root.getFileName() == null ? root.toString() : ".../" + root.getParent().getFileName() + "/" + root.getFileName());
            folderL.setToolTipText(root.toString());
            if (Files.isDirectory(root)) {
                try (var st = Files.walk(root)) {
                    st.filter(Files::isRegularFile).map(f -> root.relativize(f).toString().replace('\\', '/'))
                      .filter(f -> f.matches("(?i).*\\.(wav|ogg|mp3|flac|aiff?|m4a)$"))
                      .sorted(Comparator.comparing((String f) -> f.startsWith("_")).thenComparing(f -> f)).forEach(all::add);   // _originals & co. last
                } catch (IOException e) { logLine("scan failed: " + e); }
            } else logLine("mirror folder not found: " + root + "  (folder… to pick one)");
            refilter();
            badges.scan(new ArrayList<>(all), list::repaint);
        }
        void refilter() {
            String q = filter.getText().trim().toLowerCase(Locale.ROOT);
            model.clear();
            for (String f : all) if (q.isEmpty() || f.toLowerCase(Locale.ROOT).contains(q)) model.addElement(f);
        }
        void select(String rel) {
            selected = rel; selPa = null; selImg = null;
            card.repaint();
            if (rel == null) return;
            Path f = lab.mirrorRoot().resolve(rel);
            bg.submit(() -> {
                try {
                    Partials pa = partials(f.toString(), 14, 46, true);
                    BufferedImage img = spectrogram(f.toString(), pa, 900, 300);
                    SwingUtilities.invokeLater(() -> { if (rel.equals(selected)) { selPa = pa; selImg = img; selDur = pa.res[0].length / (double) SR; card.repaint(); } });
                } catch (Exception e) { logLine("analysis failed: " + e); }
            });
        }

        void stopPlayer() { if (player != null) { try { player.stop(); player.close(); } catch (Exception ignored) {} player = null; } }
        void playFile(Path wav) throws Exception { player = playWav(wav, player); }
        /** Renders the selected sound's model (or the file itself) to a temp wav and plays it. */
        void preview(String what) {
            if (what.equals("stop")) { stopPlayer(); return; }
            if (selected == null || (selPa == null && !what.equals("orig"))) return;
            Path f = lab.mirrorRoot().resolve(selected);
            bg.submit(() -> {
                try {
                    if (what.equals("orig")) { playFile(decodedPath(f)); return; }
                    double tune = tuneFor(selPa.f0), key = what.equals("key") ? ((Number) keySpin.getValue()).doubleValue() : 0;
                    Clip c = forgeClip("preview", f.toString(), selDur, tune, what.equals("resid") ? 0 : 1, what.equals("sines") ? 0 : 1);
                    double[] tv = new double[TRACKS]; Arrays.fill(tv, 1.0);
                    Path tmp = Files.createTempFile("forge-preview-", ".wav");
                    tmp.toFile().deleteOnExit();
                    renderFile(List.of(c), tv, new boolean[TRACKS], tmp, false, false, false, true, key);
                    playFile(tmp);
                    logLine(String.format(Locale.ROOT, "preview %s%s", what, what.equals("key") ? String.format(Locale.ROOT, " %+.0f st (%s)", key, noteName(rootHz() * Math.pow(2, key / 12))) : ""));
                } catch (Exception e) { logLine("preview failed: " + e); }
            });
        }

        void run() {
            if (selected == null) { logLine("select a sound first"); return; }
            Path f = lab.mirrorRoot().resolve(selected);
            runOn(f, forgeName(f.getFileName().toString()));
        }
        void runOn(Path src, String name) {
            runB.setEnabled(false);
            double root = rootHz(); Integer reg = register(); int[] keys = keys();
            boolean ogg = oggB.isSelected(), mono = monoB.isSelected();
            String ns = nsF.getText().trim(), ep = epF.getText().trim(), pp = ppF.getText().trim();
            logLine("== promoting " + src.getFileName() + " as \"" + name + "\" (root " + noteName(root) + ")");
            bg.submit(() -> {
                try { forge(src, name, root, reg, keys, ogg, mono, ns, ep, pp, this::logLine); }
                catch (Exception e) { logLine("FAILED: " + e); }
                finally { SwingUtilities.invokeLater(() -> runB.setEnabled(true)); }
            });
        }
        /** Renders what is on the workbench right now and promotes that. */
        void promoteTimeline() {
            String def = lab.lastStampName != null ? forgeName(lab.lastStampName) : "";
            String name = (String) JOptionPane.showInputDialog(this, "Promote the current timeline as (folder name under forge/):",
                    "Promote timeline", JOptionPane.PLAIN_MESSAGE, null, null, def);
            if (name == null || name.trim().isEmpty()) return;
            try {
                Files.createDirectories(FORGE_DIR);
                Path tmp = FORGE_DIR.resolve(forgeName(name) + ".timeline.sfx");
                Files.writeString(tmp, lab.projectText());
                runOn(tmp, forgeName(name));
            } catch (IOException e) { logLine("couldn't stage the timeline: " + e); }
        }
        void openInWorkbench() {
            if (selected == null) return;
            Path f = lab.mirrorRoot().resolve(selected);
            double tune = selPa != null ? tuneFor(selPa.f0) : 0;
            lab.importAsPartials(f, forgeName(f.getFileName().toString()), tune);
            Window w = SwingUtilities.getWindowAncestor(lab);
            if (w != null) w.toFront();
            lab.requestFocusInWindow();
        }
    }


    // ---- palette: presets built from the primitives above. The old gestures
    // live here now — as starting points, not hardcoded behavior.
    record Pal(String label, int type, double dur, int[] pi, double[] pv) {}
    static final Pal[] PALETTE = {
        new Pal("cloud",   CLOUD,   4.0, new int[]{P_PITCH}, new double[]{-12}),
        new Pal("tones",   TONE,    2.0, new int[]{P_PITCH}, new double[]{12}),
        new Pal("sub",     TONE,    2.0, new int[]{P_PITCH, NCOMMON, NCOMMON + 1}, new double[]{-12, 0, 2}),
        new Pal("noise",   NOISE,   1.5, new int[]{P_LEVEL, P_CUT}, new double[]{0.5, 0.7}),
        new Pal("riser",   TONE,    1.8, new int[]{P_ATT, P_REL, P_PSWP, NCOMMON + 1, NCOMMON + 2},
                                         new double[]{1.4, 0.3, 36, 1, 0.6}),
        new Pal("downer",  TONE,    1.4, new int[]{P_REL, P_PITCH, P_PSWP, NCOMMON, NCOMMON + 1, tailIdx(TONE, 5)},
                                         new double[]{0.8, 32, -30, -12, 1.3, 0.3}),
        new Pal("zap",     TONE,    0.3, new int[]{P_LEVEL, P_ATT, P_REL, P_PITCH, P_PSWP, NCOMMON + 1, tailIdx(TONE, 5)},
                                         new double[]{0.9, 0.005, 0.25, 52, -54, 0.25, 0.5}),
        new Pal("woosh",   NOISE,   1.2, new int[]{P_LEVEL, P_ATT, P_REL, P_CUT, P_CSWP, P_RES, P_MODE, P_PAN, P_PANSWP},
                                         new double[]{0.7, 0.45, 0.5, 0.28, 0.5, 0.6, 1, -0.7, 1.4}),
        new Pal("sparkle", SPARKLE, 1.6, new int[]{P_PITCH, P_REL}, new double[]{39, 0.5}),
        new Pal("pluck",   PLUCK,   1.2, new int[]{P_PITCH, P_REL}, new double[]{-5, 0.15}),
    };

    Clip fromPal(Pal pal, int track, double start) {
        Clip c = new Clip(pal.label(), pal.type(), track, start, pal.dur(), uiRng.nextLong());
        for (int k = 0; k < pal.pi().length; k++) c.p[pal.pi()[k]] = pal.pv()[k];
        return c;
    }



    // ---- sample store: files from samples/, decoded once to
    // stereo floats at engine rate. A failed load caches as silence.
    static final Path SAMPLE_DIR = DIR.resolve("samples");
    static final java.util.concurrent.ConcurrentHashMap<String, Path> SAMPLE_PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /** samples/<name> — or, when that file has since been sorted into another
     *  subfolder, the first file under samples/ with the same basename, so old
     *  projects keep resolving after the library is reorganised. */
    static Path samplePath(String name) {
        Path p = SAMPLE_DIR.resolve(name);
        if (Files.exists(p)) return p;
        return SAMPLE_PATHS.computeIfAbsent(name, nm -> {
            String base = Paths.get(nm).getFileName().toString();
            try (var st = Files.walk(SAMPLE_DIR, FileVisitOption.FOLLOW_LINKS)) {   // samples/ may itself be a link (the test workspace)
                Optional<Path> hit = st.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().equals(base)
                                                 && !SAMPLE_DIR.relativize(f).toString().startsWith(".decoded")).sorted().findFirst();
                if (hit.isPresent()) {
                    System.err.println("sample " + nm + " not found; using " + SAMPLE_DIR.relativize(hit.get()));
                    return hit.get();
                }
            } catch (IOException e) { /* no samples/ at all: fall through to the plain path */ }
            return p;
        });
    }

    /** The GUI's sample loader (installed into sfxlab.runtime.Samples): samples/<name>, decoded by javax.sound
     *  (ffmpeg first for anything but PCM), linearly resampled to the engine rate. */
    static float[][] decodeSample(String nm) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(decodedPath(samplePath(nm)).toFile());
        AudioFormat f = in.getFormat();
        AudioFormat target = new AudioFormat(f.getSampleRate(), 16, 2, true, false);
        byte[] data = AudioSystem.getAudioInputStream(target, in).readAllBytes();
        int frames = data.length / 4;
        double ratio = f.getSampleRate() / (double) SR;
        int outN = Math.max(1, (int) (frames / ratio));
        float[][] out = new float[2][outN];
        for (int i = 0; i < outN; i++) {
            double sp = i * ratio;
            int j = Math.min(frames - 1, (int) sp);
            int j2 = Math.min(frames - 1, j + 1);
            double fr = sp - (int) sp;
            for (int ch = 0; ch < 2; ch++) {
                double a = ((short) ((data[j * 4 + ch * 2] & 0xff) | (data[j * 4 + ch * 2 + 1] << 8))) / 32768.0;
                double b = ((short) ((data[j2 * 4 + ch * 2] & 0xff) | (data[j2 * 4 + ch * 2 + 1] << 8))) / 32768.0;
                out[ch][i] = (float) (a * (1 - fr) + b * fr);
            }
        }
        return out;
    }
    static {
        Samples.loader = SfxLab::decodeSample;
        Partials.source = SfxLab::analyzeCached;
    }

    static boolean isPcmName(String nm) {
        String l = nm.toLowerCase(Locale.ROOT);
        return l.endsWith(".wav") || l.endsWith(".aif") || l.endsWith(".aiff") || l.endsWith(".au");
    }
    /** Anything javax.sound can't read (mp3, ogg, a video's audio track…) is
     *  decoded by ffmpeg into samples/.decoded/, one wav per source file: named by its folder as well as its name
     *  (two folders may hold a file of the same name), and decoded again whenever the source is newer than the
     *  copy. (Until 2026-10-01 the copy was named by the file name alone and never refreshed: a recording replaced
     *  under its old name went on playing as the old one.) */
    static Path decodedPath(Path f) throws IOException, InterruptedException {
        if (isPcmName(f.getFileName().toString())) return f;
        Path abs = f.toAbsolutePath();
        String tag = abs.getParent() == null ? ""
                   : abs.startsWith(SAMPLE_DIR) ? SAMPLE_DIR.relativize(abs.getParent()).toString().replaceAll("[^A-Za-z0-9_.-]+", "__") + "__"
                   : Integer.toHexString(abs.getParent().toString().hashCode()) + "-";   // outside samples/
        Path out = SAMPLE_DIR.resolve(".decoded").resolve(tag + f.getFileName() + ".wav");
        if (!Files.exists(out) || Files.getLastModifiedTime(out).compareTo(Files.getLastModifiedTime(f)) < 0) {
            Files.createDirectories(out.getParent());
            run("ffmpeg", "-v", "error", "-y", "-i", f.toString(), "-vn", "-ac", "2", "-ar", String.valueOf(SR),
                "-c:a", "pcm_s16le", out.toString());
        }
        return out;
    }

    // ---- ffmpeg/ffprobe: the lab shells out for anything beyond PCM wav
    static Boolean ffmpegOk;
    static boolean haveFfmpeg() {
        if (ffmpegOk == null) { try { run("ffmpeg", "-version"); ffmpegOk = true; } catch (Exception e) { ffmpegOk = false; } }
        return ffmpegOk;
    }
    static String run(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) throw new IOException(cmd[0] + " failed (" + rc + "): " + out.trim());
        return out;
    }

    /** Peak envelope per PBIN samples (both channels), for drawing clip waveforms. */
    static final int PBIN = 128;
    static final java.util.concurrent.ConcurrentHashMap<String, float[]> PEAKS = new java.util.concurrent.ConcurrentHashMap<>();
    static float[] peaks(String name) {
        return PEAKS.computeIfAbsent(name, nm -> {
            float[][] s = sample(nm);
            float[] pk = new float[(s[0].length + PBIN - 1) / PBIN];
            for (int i = 0; i < s[0].length; i++) {
                float a = Math.max(Math.abs(s[0][i]), Math.abs(s[1][i]));
                if (a > pk[i / PBIN]) pk[i / PBIN] = a;
            }
            return pk;
        });
    }

    // Analyses are kept on disk (forge/.parts/, git-ignored), keyed by file, thresholds, size and mtime:
    // a 20 s recording takes ~2 s to analyse and ~7 MB to store, and a palette has half a dozen of them.
    static final Path PARTS_DIR = DIR.resolve("forge").resolve(".parts");
    static Path partsCachePath(String file, double fl, double ml) {
        try {
            Path f = samplePath(file);
            // "|2": analyses made before the decoded copies were kept fresh (see decodedPath) may be of the wrong audio
            String key = file + "|" + Math.round(fl) + "|" + Math.round(ml) + "|" + Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis() + "|2";
            String base = f.getFileName().toString().replaceAll("[^A-Za-z0-9_.-]", "_");
            return PARTS_DIR.resolve(String.format("%08x-%s.parts", key.hashCode(), base));
        } catch (Exception e) { return null; }
    }
    static Partials analyzeCached(String file, double fl, double ml) {
        Path cp = partsCachePath(file, fl, ml);
        if (cp != null && Files.exists(cp)) { try { return loadPartials(cp); } catch (Exception e) { System.err.println("partials cache read failed: " + e); } }
        Partials pa = analyzePartials(file, fl, ml);
        if (cp != null) { try { savePartials(cp, pa); } catch (Exception e) { System.err.println("partials cache write failed: " + e); } }
        return pa;
    }
    static void savePartials(Path cp, Partials pa) throws IOException {
        Files.createDirectories(cp.getParent());
        Path tmp = cp.resolveSibling(cp.getFileName() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) { Partials.write(o, pa); }
        Files.move(tmp, cp, StandardCopyOption.REPLACE_EXISTING);
    }
    static Partials loadPartials(Path cp) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(cp), 1 << 16))) { return Partials.read(in); }
    }

    // ---- FORGE: promotes a finished sound (a recording, or a workbench project
    // rendered first) into a root-tuned, keyed package for the mod, staged in
    // forge/<name>/ and never touching the source. Output:
    //   <name>_residual.ogg, <name>_sines.ogg   the two halves of the model
    //   <name>_k00.ogg …                        one per key (semitones above the root; kn05 = -5)
    //   <name>.partials.json                    the partial tracks + tuning, for a runtime resynth
    //   <name>.sounds.json                      a sounds.json fragment, ready to paste
    //   <name>.png                              spectrogram with the tracked partials
    static final Path FORGE_DIR = DIR.resolve("forge");
    static final int[] FORGE_KEYS = {0, 2, 4, 7, 9, 12, 14, 16, 19, 21, 24, 26, 28, 31, 33};   // major pentatonic, 3 octaves
    static String forgeName(String s) {
        String n = s.replaceFirst("\\.[^.]+$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        return n.isEmpty() ? "sound" : n;
    }
    static String keyTag(int k) { return (k < 0 ? "kn" : "k") + String.format(Locale.ROOT, "%02d", Math.abs(k)); }
    record ForgeOut(Path dir, double f0, double share, double tune, int nTracks, double dur, List<String> files) {}

    /** The whole promotion. register: null = nearest octave of the root, else octaves above the root. */
    static ForgeOut forge(Path src, String name, double rootHz, Integer register, int[] keys, boolean ogg, boolean mono,
                          String ns, String eventPrefix, String pathPrefix, java.util.function.Consumer<String> log) throws Exception {
        Path dir = FORGE_DIR.resolve(name);
        Files.createDirectories(dir);
        Path audio = src.toAbsolutePath();
        if (src.toString().toLowerCase(Locale.ROOT).endsWith(".sfx")) {
            audio = dir.resolve(name + "_source.wav");
            double[] tv = new double[TRACKS]; Arrays.fill(tv, 1.0);
            boolean[] mu = new boolean[TRACKS];
            List<Clip> cs = parseProject(src, new boolean[1], tv, mu);
            renderFile(cs, tv, mu, audio, false, false, false, true, 0);
            log.accept("rendered project " + src.getFileName() + " -> " + audio.getFileName());
        }
        String af = audio.toString();
        Partials pa = partials(af, 14, 46, true);
        double dur = pa.res[0].length / (double) SR;
        log.accept(String.format(Locale.ROOT, "analysed: %d partials, %.0f%% sines, %.2f s", pa.nTracks, 100 * pa.share, dur));
        double tune = 0;
        if (pa.f0 > 0) {
            double target = register == null ? rootHz * Math.pow(2, Math.round(Math.log(pa.f0 / rootHz) / Math.log(2)))
                                             : rootHz * Math.pow(2, register);
            tune = Math.max(COMMON[P_PITCH].min(), Math.min(COMMON[P_PITCH].max(), 12 * Math.log(target / pa.f0) / Math.log(2)));
            log.accept(String.format(Locale.ROOT, "fundamental ~%.1f Hz (%s) -> %s: tune %+.2f st%s", pa.f0, noteName(pa.f0),
                    noteName(pa.f0 * Math.pow(2, tune / 12)), tune,
                    pa.share < 0.2 ? "   (faint pitch: " + Math.round(100 * pa.share) + "% sines)" : ""));
        } else log.accept("no partials found: the keyed files carry only the residual");
        String ext = ogg ? ".ogg" : ".wav";
        double[] tv = new double[TRACKS]; Arrays.fill(tv, 1.0);
        boolean[] mu = new boolean[TRACKS];
        List<String> files = new ArrayList<>();
        Map<String, String> keyFiles = new LinkedHashMap<>();
        for (String part : new String[]{"residual", "sines"}) {
            Clip c = forgeClip(name, af, dur, tune, part.equals("sines") ? 1 : 0, part.equals("residual") ? 1 : 0);
            String fn = name + "_" + part + ext;
            renderFile(List.of(c), tv, mu, dir.resolve(fn), ogg, mono, false, true, 0);
            files.add(fn);
        }
        for (int k : keys) {
            String fn = name + "_" + keyTag(k) + ext;
            renderFile(List.of(forgeClip(name, af, dur, tune, 1, 1)), tv, mu, dir.resolve(fn), ogg, mono, false, true, k);
            files.add(fn); keyFiles.put(keyTag(k), fn);
            log.accept("  " + fn + "   " + noteName(rootHz * Math.pow(2, k / 12.0)));
        }
        writeModel(dir.resolve(name + ".partials.json"), name, af, pa, rootHz, tune, dur, keyFiles, files.get(0), files.get(1));
        files.add(name + ".partials.json");
        writeSoundsFragment(dir.resolve(name + ".sounds.json"), ns, eventPrefix, pathPrefix, name, keyFiles);
        files.add(name + ".sounds.json");
        ImageIO.write(spectrogram(af, pa, 900, 300), "png", dir.resolve(name + ".png").toFile());
        files.add(name + ".png");
        log.accept("done -> " + dir);
        return new ForgeOut(dir, pa.f0, pa.share, tune, pa.nTracks, dur, files);
    }
    /** A unity-gain partials clip over the whole recording, pitch-keyed only. */
    static Clip forgeClip(String name, String file, double dur, double tune, double sines, double resid) {
        Clip c = new Clip(name, PARTIALS, 0, 0, dur + 0.05, 7);
        c.file = file; c.keyed = KEY_PITCH;
        c.p[P_LEVEL] = 1; c.p[P_ATT] = 0.001; c.p[P_REL] = 0.001;
        c.p[P_PITCH] = tune; c.p[NCOMMON + PA_SINES] = sines; c.p[NCOMMON + PA_RESID] = resid;
        return c;
    }
    static void writeModel(Path out, String name, String source, Partials pa, double rootHz, double tune, double dur,
                           Map<String, String> keyFiles, String resFile, String sinFile) throws IOException {
        StringBuilder sb = new StringBuilder("{\n  \"format\": \"sfxlab-partials-1\",\n");
        sb.append(String.format(Locale.ROOT, "  \"name\": \"%s\",\n  \"source\": \"%s\",\n", name, source.replace("\\", "/")));
        sb.append(String.format(Locale.ROOT, "  \"sampleRate\": %d,\n  \"window\": %d,\n  \"hop\": %d,\n  \"frames\": %d,\n  \"duration\": %.4f,\n",
                SR, PA_N, PA_HOP, pa.nFrames, dur));
        sb.append(String.format(Locale.ROOT, "  \"rootHz\": %.4f,\n  \"f0Hz\": %.3f,\n  \"tuneSemitones\": %.4f,\n  \"sinesShare\": %.4f,\n",
                rootHz, pa.f0, tune, pa.share));
        sb.append("  \"residual\": \"").append(resFile).append("\",\n  \"sines\": \"").append(sinFile).append("\",\n  \"keys\": {");
        int i = 0;
        for (Map.Entry<String, String> e : keyFiles.entrySet()) sb.append(i++ > 0 ? ", " : "").append('"').append(e.getKey()).append("\": \"").append(e.getValue()).append('"');
        sb.append("},\n  \"notes\": \"tracks are as analysed (untuned); each starts at frame `start` (= first tracked frame - 1) with a ")
          .append("zero-amplitude fade frame at both ends; freq in Hz, amp = peak amplitude of a cosine at full scale 1.0; ")
          .append("frame f sounds at f*hop/sampleRate seconds; to play at K semitones above the root use freq * 2^((tuneSemitones + K)/12) ")
          .append("and add the residual file untransposed. harm = harmonic number of the track relative to f0Hz (0 = inharmonic), ")
          .append("for per-harmonic gain / odd-even / purity controls at runtime.\",\n  \"tracks\": [\n");
        for (int k = 0; k < pa.nTracks; k++) {
            PTrack t = pa.tracks[k];
            sb.append("    {\"start\": ").append(t.start).append(", \"harm\": ").append(t.harm).append(", \"freq\": [");
            for (int j = 0; j < t.len; j++) sb.append(j > 0 ? "," : "").append(String.format(Locale.ROOT, "%.1f", t.freq[j]));
            sb.append("], \"amp\": [");
            for (int j = 0; j < t.len; j++) sb.append(j > 0 ? "," : "").append(String.format(Locale.ROOT, "%.4g", t.amp[j]));
            sb.append("]}").append(k + 1 < pa.nTracks ? ",\n" : "\n");
        }
        sb.append("  ]\n}\n");
        Files.writeString(out, sb.toString());
    }
    /** sounds.json entries in the mod's flat style: "<prefix><name>_k07": {"sounds": ["<ns>:<path><name>/<name>_k07"]}. */
    static void writeSoundsFragment(Path out, String ns, String eventPrefix, String pathPrefix, String name, Map<String, String> keyFiles) throws IOException {
        StringBuilder sb = new StringBuilder("{\n");
        List<String> tags = new ArrayList<>(List.of("residual", "sines"));
        tags.addAll(keyFiles.keySet());
        for (int i = 0; i < tags.size(); i++)
            sb.append(String.format(Locale.ROOT, "  \"%s%s_%s\": {\"sounds\": [\"%s:%s%s/%s_%s\"]}%s%n",
                    eventPrefix, name, tags.get(i), ns, pathPrefix, name, name, tags.get(i), i + 1 < tags.size() ? "," : ""));
        Files.writeString(out, sb.append("}\n").toString());
    }
    /** 0–6 kHz spectrogram of the recording with the tracked partials drawn over it. */
    static BufferedImage spectrogram(String file, Partials pa, int w, int h) {
        float[][] smp = sample(file);
        int n = smp[0].length, half = PA_N / 2, nF = Math.max(1, pa.nFrames);
        double fmax = 6000;
        double[] win = new double[PA_N], re = new double[PA_N], im = new double[PA_N];
        for (int i = 0; i < PA_N; i++) win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / PA_N);
        float[][] db = new float[w][h];
        float mx = -200;
        for (int x = 0; x < w; x++) {
            int o = (int) ((long) x * nF / w) * PA_HOP - half;
            for (int i = 0; i < PA_N; i++) { int j = o + i; re[i] = j >= 0 && j < n ? 0.5 * (smp[0][j] + smp[1][j]) * win[i] : 0; im[i] = 0; }
            fft(re, im, false);
            for (int y = 0; y < h; y++) {
                double fr = fmax * (h - 1 - y) / h, bin = fr * PA_N / SR;
                int b = Math.min(half, (int) bin);
                double m = Math.max(Math.hypot(re[b], im[b]), Math.hypot(re[Math.min(half, b + 1)], im[Math.min(half, b + 1)]));
                db[x][y] = (float) (20 * Math.log10(m + 1e-9));
                if (db[x][y] > mx) mx = db[x][y];
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                double u = Math.max(0, Math.min(1, (db[x][y] - (mx - 72)) / 72));
                img.setRGB(x, y, heat(u));
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(90, 255, 235, 200));
        for (PTrack t : pa.tracks) {
            int px = -1, py = -1;
            for (int j = 1; j < t.len - 1; j++) {
                int x = (int) ((long) (t.start + j) * w / nF), y = (int) (h - 1 - t.freq[j] / fmax * h);
                if (px >= 0 && y >= 0 && y < h) g.drawLine(px, py, x, y);
                px = x; py = y;
            }
        }
        g.dispose();
        return img;
    }
    static final int[][] HEAT = {{0, 0, 0}, {80, 18, 110}, {200, 60, 70}, {250, 150, 40}, {255, 240, 190}};   // black, purple, red, orange, pale yellow
    static int heat(double u) {
        double p = u * (HEAT.length - 1);
        int i = Math.min(HEAT.length - 2, (int) p);
        double f = p - i;
        int r = (int) (HEAT[i][0] + (HEAT[i + 1][0] - HEAT[i][0]) * f), g = (int) (HEAT[i][1] + (HEAT[i + 1][1] - HEAT[i][1]) * f), b = (int) (HEAT[i][2] + (HEAT[i + 1][2] - HEAT[i][2]) * f);
        return (r << 16) | (g << 8) | b;
    }
    // ---- BAKE: what the mod ships for a set of families, staged in forge/baked/ (git-ignored), never touching the sources:
    //   partials/<stem>.ptk, <stem>.res.ogg   each partials analysis the families play (Partials.writeBaked: the tracks,
    //                                          deflated, and the residual as a mono ogg; stem = Partials.bakedName)
    //   samples/<file>                         the recordings their sample and choir clips play, as they are
    //   manifest.txt                           per family: `family <id>`, then `partials <file> <floor> <min len> <stem>`
    //                                          and `sample <file>` lines
    static final Path BAKE_DIR = FORGE_DIR.resolve("baked");
    static void bake(List<String> fams, Path out, int quality, java.util.function.Consumer<String> log) throws Exception {
        Files.createDirectories(out.resolve("partials"));
        StringBuilder man = new StringBuilder("# SfxLab bake: what each family plays at runtime (sfxlab.runtime.Partials.readBaked reads the partials)\n");
        Set<String> done = new HashSet<>();
        long rawBytes = 0, bakedBytes = 0, sampleBytes = 0;
        for (String fam : fams) {
            List<String> probs = SfxFormat.validateFamily(Files.readAllLines(familyFile(fam)));
            if (!probs.isEmpty()) throw new IOException(fam + ".sfx has " + probs.size() + " format problem(s), not baked:\n  " + String.join("\n  ", probs));
        }
        for (String fam : fams) {
            Family fm = parseFamily(Files.readAllLines(familyFile(fam)));
            List<Clip> all = new ArrayList<>(fm.palette().layers);
            for (Spell sp : fm.spells()) all.addAll(sp.bench.layers);
            TreeSet<String> lines = new TreeSet<>();
            for (Clip c : all) {
                if (c.file == null) continue;
                if (c.type == PARTIALS) {
                    double fl = paFloor(c), ml = paMinLen(c);
                    String stem = Partials.bakedName(c.file, fl, ml);
                    lines.add("partials " + c.file + " " + Math.round(fl) + " " + Math.round(ml) + " " + stem);
                    if (!done.add("p:" + stem)) continue;
                    Partials pa = partials(c.file, fl, ml, true);
                    int n = pa.res[0].length;
                    float[] m = new float[n]; float peak = 0;
                    for (int i = 0; i < n; i++) { m[i] = 0.5f * (pa.res[0][i] + pa.res[1][i]); peak = Math.max(peak, Math.abs(m[i])); }
                    float g = peak > 0.99f ? peak / 0.99f : 1f;   // keep the encoder's input inside full scale; readBaked scales back
                    Path raw = Files.createTempFile("sfxlab-res", ".f32");
                    try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(raw), 1 << 16))) {
                        for (float x : m) o.writeInt(Integer.reverseBytes(Float.floatToIntBits(x / g)));   // f32le
                    }
                    Path ogg = out.resolve("partials").resolve(stem + ".res.ogg"), ptk = out.resolve("partials").resolve(stem + ".ptk");
                    run("ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", String.valueOf(SR), "-ac", "1", "-i", raw.toString(),
                        "-c:a", "libvorbis", "-q:a", String.valueOf(quality), "-fflags", "+bitexact", "-flags:a", "+bitexact", ogg.toString());   // bitexact: no random stream serial, so an unchanged bake is byte-identical (no churn in the mod's history)
                    Files.delete(raw);
                    try (OutputStream o = Files.newOutputStream(ptk)) { Partials.writeBaked(o, pa, n, g); }   // m is (L + R) / 2 of the residual as the engine plays it
                    long cached = 8L * n + 20L * pa.tracks.length; for (PTrack t : pa.tracks) cached += 8L * t.len;
                    rawBytes += cached; bakedBytes += Files.size(ogg) + Files.size(ptk);
                    log.accept(String.format(Locale.ROOT, "  %-58s %6.1f MB -> %5.1f MB (%d tracks, %.1f s)", stem, cached / 1e6,
                                             (Files.size(ogg) + Files.size(ptk)) / 1e6, pa.tracks.length, n / (double) SR));
                } else if (c.type == SAMPLE || c.type == CHOIR) {
                    lines.add("sample " + c.file);
                    if (!done.add("s:" + c.file)) continue;
                    Path dst = out.resolve("samples").resolve(c.file);
                    Files.createDirectories(dst.getParent());
                    Files.copy(samplePath(c.file), dst, StandardCopyOption.REPLACE_EXISTING);
                    sampleBytes += Files.size(dst);
                }
            }
            man.append("family ").append(fam).append('\n');
            for (String l : lines) man.append(l).append('\n');
            log.accept("family " + fam + ": " + lines.stream().filter(l -> l.startsWith("partials")).count() + " partials layers' analyses, "
                       + lines.stream().filter(l -> l.startsWith("sample")).count() + " recordings");
        }
        Files.writeString(out.resolve("manifest.txt"), man.toString());
        log.accept(String.format(Locale.ROOT, "baked into %s: partials %.1f MB (%.1f MB as analysed), recordings %.1f MB", out, bakedBytes / 1e6, rawBytes / 1e6, sampleBytes / 1e6));
    }

    static String opt(List<String> a, String k, String def) {
        int i = a.indexOf(k);
        if (i < 0 || i + 1 >= a.size()) return def;
        String v = a.get(i + 1); a.remove(i + 1); a.remove(i);
        return v;
    }




    // =====================================================================
    // Project state
    // =====================================================================
    final ArrayList<Clip> clips = new ArrayList<>();
    final Object lock = new Object();
    final boolean[] mute = new boolean[TRACKS];
    final double[] trackVol = new double[TRACKS];
    { Arrays.fill(trackVol, 1.0); }
    static final double TVOL_MAX = 1.25;
    final Random uiRng = new Random();

    // ---- markers (named cue points) and the video reference
    static class Marker { double t; String name; Marker(double t, String name) { this.t = t; this.name = name; } }
    final ArrayList<Marker> markers = new ArrayList<>();
    volatile VideoRef video = null;

    // ---- export settings, remembered in lab.cfg (git-ignored; see lab.cfg.example)
    static final Path CFG_FILE = DIR.resolve("lab.cfg");
    String exportDir = "renders";   // relative = inside the workspace
    String forgeMirror = "";        // the mod's sounds folder the forge panel browses; empty until picked (folder… button)
    String modDir = "";             // the mod checkout the regulator panel's "sync to mod" button syncs into; empty until picked
    Path mirrorRoot() { return DIR.resolve(forgeMirror); }
    boolean expOgg = false, expMono = false, expNorm = false, expTrim = true;
    volatile boolean monoOut;   // shift+M: hear the mix as the game plays it, one mono source ((L + R) / 2, the export's downmix); remembered in lab.cfg

    // ---- transport (UI writes, audio reads)
    volatile boolean playing = false;
    volatile boolean loopOn = false;
    volatile double seekTo = 0;          // >= 0 requests a seek
    volatile double playPos = 0;
    volatile Clip solo = null;

    // ---- UI state
    Clip sel = null;
    int selTrack = 0;
    double pps = 130, scroll = 0;
    volatile String msg = ""; volatile long msgAt = 0;
    final ArrayList<double[]> combos = new ArrayList<>();
    int comboIdx = -1;
    static final int DR_NONE = 0, DR_MOVE = 1, DR_SIZE = 2, DR_SEEK = 3, DR_SLIDER = 4, DR_TVOL = 5, DR_VIDEO = 6, DR_TRIM = 7, DR_LEVEL = 8;
    int dragMode = DR_NONE, dragParam = -1, dragTrack = -1;
    double grabOff = 0;
    boolean dirty = false;
    boolean snapOn = true;
    double keyOff = 0;   // audition transposition in semitones; not saved with the project
    volatile long clipAt;   // last time the master input went over 0 dB (header shows SAT for a second)
    volatile long xrunAt;   // last time the output buffer ran dry (header shows XRUN for a second)
    double rootHz = ROOT_DEFAULT;   // key 0 = this note; saved with the project (`root` line)
    long lastEditAt = 0;
    String lastStampName = null;      // last named .sfx stamped or opened; prefills the save dialog

    // ---- undo/redo: whole-project snapshots. Continuous gestures (drags,
    // repeated nudges) coalesce into one step; structural ops always push.
    static class Snap { ArrayList<Clip> clips; double[] tvol; boolean[] mute; ArrayList<Marker> markers; VideoRef video; double vstart; }
    final ArrayDeque<Snap> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    Snap pendingSnap = null;    // captured on mouse-press, committed on first change
    String lastOpTag = ""; long lastOpAt = 0;

    Snap snapshot() {
        Snap s = new Snap();
        s.clips = new ArrayList<>();
        synchronized (lock) { for (Clip c : clips) s.clips.add(copyClip(c)); }
        s.tvol = trackVol.clone(); s.mute = mute.clone();
        s.markers = new ArrayList<>();
        for (Marker m : markers) s.markers.add(new Marker(m.t, m.name));
        s.video = video; s.vstart = video != null ? video.start : 0;
        return s;
    }
    void pushUndo(String tag) {
        if (benchOn) { pushBenchUndo(tag); return; }
        long now = System.currentTimeMillis();
        if (!tag.isEmpty() && tag.equals(lastOpTag) && now - lastOpAt < 1200) { lastOpAt = now; return; }
        lastOpTag = tag; lastOpAt = now;
        undoStack.push(snapshot());
        if (undoStack.size() > 100) undoStack.removeLast();
        redoStack.clear();
    }
    void commitPending() {
        if (pendingBench != null) {
            bUndo.push(pendingBench);
            if (bUndo.size() > 100) bUndo.removeLast();
            bRedo.clear();
            lastOpTag = ""; pendingBench = null;
            return;
        }
        if (pendingSnap == null) return;
        undoStack.push(pendingSnap);
        if (undoStack.size() > 100) undoStack.removeLast();
        redoStack.clear();
        lastOpTag = ""; pendingSnap = null;
    }
    void restore(Snap s) {
        synchronized (lock) {
            clips.clear();
            for (Clip c : s.clips) clips.add(copyClip(c));
        }
        System.arraycopy(s.tvol, 0, trackVol, 0, TRACKS);
        System.arraycopy(s.mute, 0, mute, 0, TRACKS);
        markers.clear();
        for (Marker m : s.markers) markers.add(new Marker(m.t, m.name));
        video = s.video;
        if (video != null) video.start = s.vstart;
        sel = null; solo = null;
        markEdit();
    }
    void doUndo() {
        if (benchOn) { benchUndo(); return; }
        if (undoStack.isEmpty()) { toast("nothing to undo"); return; }
        redoStack.push(snapshot());
        restore(undoStack.pop());
        lastOpTag = "";
        toast("undo");
    }
    void doRedo() {
        if (benchOn) { benchRedo(); return; }
        if (redoStack.isEmpty()) { toast("nothing to redo"); return; }
        undoStack.push(snapshot());
        restore(redoStack.pop());
        lastOpTag = "";
        toast("redo");
    }

    final float[] scopeL = new float[2048], scopeR = new float[2048];
    int scopePos = 0;

    void toast(String s) { msg = s; msgAt = System.currentTimeMillis(); }
    void markEdit() {
        if (benchOn) benchDirty = true;
        else dirty = true;
        lastEditAt = System.currentTimeMillis();
    }

    String projectText() {
        StringBuilder sb = new StringBuilder("# SfxLab project v2: clip name type track start dur seed key=value...\n");
        sb.append("loop ").append(loopOn ? 1 : 0).append('\n');
        sb.append(String.format(Locale.ROOT, "root %.4f%n", rootHz));
        for (int i = 0; i < TRACKS; i++)
            sb.append(String.format(Locale.ROOT, "track %d %.3f %d%n", i, trackVol[i], mute[i] ? 1 : 0));
        for (Marker m : markers) sb.append(String.format(Locale.ROOT, "marker %.4f %s%n", m.t, m.name));
        VideoRef v = video;
        if (v != null) sb.append(String.format(Locale.ROOT, "video %.4f %s%n", v.start, v.path));
        synchronized (lock) {
            for (Clip c : clips) sb.append(clipLine(c));
        }
        return sb.toString();
    }

    /** The non-clip lines of a project: markers and the video reference
     *  (videoOut = {path, start}; path stays null when there is none). */
    static void parseExtras(Path f, List<Marker> markersOut, String[] videoOut, double[] rootOut) throws IOException {
        for (String line : Files.readAllLines(f)) {
            String[] t = line.trim().split("\\s+", 3);
            if (t.length >= 2 && t[0].equals("root")) { if (rootOut != null) rootOut[0] = Double.parseDouble(t[1]); continue; }
            if (t.length < 3) continue;
            if (t[0].equals("marker")) markersOut.add(new Marker(Double.parseDouble(t[1]), t[2]));
            else if (t[0].equals("video")) { videoOut[0] = t[2]; videoOut[1] = t[1]; }
        }
    }
    void loadExtras(Path f) throws IOException {
        ArrayList<Marker> ms = new ArrayList<>();
        String[] vid = new String[2];
        double[] rt = {ROOT_DEFAULT};
        parseExtras(f, ms, vid, rt);
        rootHz = rt[0] > 0 ? rt[0] : ROOT_DEFAULT;
        markers.clear(); markers.addAll(ms);
        if (vid[0] == null) { video = null; return; }
        double st = Double.parseDouble(vid[1]);
        VideoRef cur = video;
        if (cur != null && cur.path.toString().equals(vid[0])) { cur.start = st; return; }
        attachVideo(Paths.get(vid[0]), st, false);
    }


    // =====================================================================
    // The bench: a regulator palette (H switches the workbench to it).
    // Layers are clips with no timeline position: every endless layer sounds
    // continuously while the bench plays, one-shot layers fire on the lock /
    // unlock events. Signals from the machine (HARMONIC-REGULATOR.md §4) drive
    // layer params through binds; a signature (a saved layer set for one spell)
    // blends in as the score rises. Bench files use the .sfx line style:
    //   layer id name type dur seed key=value...     dur 0 = endless
    //   range id param lo hi [note...]               the span that sounded good
    //   bind signal id|* param [lo hi] [rel]         rel = added to the layer's own value
    //   palette projects/x.sfx                       (signatures) the palette they belong to
    //   note free text
    // The timeline parser skips all of these, and old clip lines never carry
    // the new tokens, so both directions stay compatible.
    // =====================================================================
    static final Path BENCH_FILE = DIR.resolve("bench.sfx");   // the bench autosaves here, like project.sfx
    static final Path REG_DIR = DIR.resolve("regulator");      // regulator/<family>.sfx: the palette, then one `spell <id>` section per spell (its recipe, lock values, binds)
    static final Path WORKING_DIR = REG_DIR.resolve(".working"); // regulator/.working/<family>.sfx: a loaded family's autosaved working copy (git-ignored); S writes the family file
    static Path workingFile(String fam) { return WORKING_DIR.resolve(fam + ".sfx"); }
    String savedText;               // the loaded family as its file holds it (familyText's form), to tell unsaved edits apart
    volatile boolean unsaved;       // the working copy differs from the family file: the header says so, S saves, shift+O reverts


    /** How the machine derives a signal (RegulatorCore.computeSignals / orbSignals / evalOnce), for the panel's tooltips.
     *  "Engaged" = a motion switched on with some reach and a ratio above idle. */
    static String sigHelp(String sig) {
        if (sig.startsWith("score.")) return "<b>" + sig + "</b>  0..1 \u2014 how well the machine matches spell " + sig.substring(6) + "'s recipe: each recipe component takes its best "
                + "engaged motion on the same axis, scored e^(\u22125\u00b7|ratio \u2212 n|), halved on the wrong phase, \u00d70.7 when the reach is off by more than rtol; "
                + "averaged over the components, then \u00d70.6 per extra motion the recipe has no use for.<br>The spell's signature blends in by smoothstep(0.55, 1) of this, unless a "
                + "<i>blend</i> row in its table says otherwise.";
        String arm = sig.length() > 4 && sig.startsWith("arm") ? sig.substring(3, 4) : null;
        if (arm != null && sig.endsWith(".ratio")) return "<b>" + sig + "</b>  0..8 \u2014 the ratio (turns per receiver cycle) of arm " + arm + "'s engaged motion with the largest reach; 0 with none. "
                + "Bind <i>" + sig.replace(".ratio", ".pitch") + "</i> instead for the same thing as semitones folded into one octave (12\u00b7log2 ratio, 0..12).";
        if (arm != null && sig.endsWith(".pitch")) return "<b>" + sig + "</b>  0..12 st \u2014 arm " + arm + "'s ratio as semitones folded into one octave: 12\u00b7log2(arm" + arm + ".ratio) mod 12, so ratios 1, 2, 4, 8 all give 0, 3 and 6 give 7.02, 5 gives 3.86. "
                + "0 while the arm is silent. No slider: scrub arm" + arm + ".ratio.";
        if (arm != null && sig.endsWith(".reach")) return "<b>" + sig + "</b>  0..1 \u2014 the summed reach (amplitude) of arm " + arm + "'s engaged motions, clamped.";
        return switch (sig) {
            case "radiance" -> "<b>radiance</b>  0..1 \u2014 the total reach of the engaged motions on the Z axis, clamped: the pylons rising = power radiating.";
            case "consonance" -> "<b>consonance</b>  0..1 \u2014 how simple the ratios between engaged motions are: each pair's ratios rounded to integers and reduced to a:b, scored 2/(a+b), averaged over the pairs. "
                    + "1:1 and 1:2 score 1 and 0.67, 2:3 0.4, 5:7 0.17. One engaged motion alone counts 1, none 0.";
            case "tension" -> "<b>tension</b>  0..1 \u2014 the highest engaged ratio / 8.";
            case "drive" -> "<b>drive</b>  0..1 \u2014 the crank's ratio / 8: how hard the machine is being turned, whatever is engaged.";
            case "coherence" -> "<b>coherence</b>  0..1 \u2014 how close the engaged ratios sit to whole numbers: e^(\u22128\u00b7|r \u2212 round r|) averaged over them (0 with none). "
                    + "Dead on = 1, a tenth off = 0.45, a quarter off = 0.14. The figure closes as this rises.";
            case "score" -> "<b>score</b>  0..1 \u2014 the pinned (target) recipe's match, as for score.&lt;spell&gt;. Above 0.55 the lock mix starts blending in; 1 locks.";
            case "orb.speed" -> "<b>orb.speed</b>  0..1 \u2014 the pen's speed over the sum of every engaged motion's peak speed, so 1 means every motion pulling the same way at once; 60 ms envelope.";
            case "orb.accel" -> "<b>orb.accel</b>  0..1 \u2014 the pen's acceleration over the sum of every motion's peak acceleration; 60 ms envelope. Spikes at the turns of the figure.";
            case "orb.curl" -> "<b>orb.curl</b>  0..1 \u2014 the curvature of the loop the pen is drawing right now, \u00d7 the figure's extent / 4: a full-size circle 0.25, a loop a quarter that size 1, a straight run 0; 60 ms envelope.";
            case "orb.radius" -> "<b>orb.radius</b>  0..1 \u2014 the orb's distance from the receiver over the figure's extent; 60 ms envelope. Breathes once per receiver cycle for most figures.";
            case "stir" -> "<b>stir</b>  0..1 \u2014 0 with every arm at rest, 1 once any engaged motion turns at \u00d70.5 or faster, whatever the crank does; 150 ms envelope. "
                    + "<i>bind stir bed level 0.6 0</i> is a bed that plays at rest and drops out as soon as the arms move.";
            case "tone.root", "tone.third", "tone.fifth", "tone.seventh" -> "<b>" + sig + "</b>  0..1 \u2014 arm-agnostic: how much reach sits on this chord tone of the harmonic series (root 0, third 3.86, fifth 7.02, seventh 9.69 st). "
                    + "Every engaged motion's ratio becomes a pitch class; \u221areach \u00b7 e^(\u22122\u00b7semitones off) is summed over them, clamped (\u221a so a quarter-reach tone still sings at half). "
                    + "At the integers 2/4/8 are root, 3/6 fifth, 5 third, 7 seventh; a ratio gliding 1 \u2192 2 lights root, third, fifth, seventh, root in turn.";
            case "stack" -> "<b>stack</b>  0..1 \u2014 engaged motions / 6, clamped: how full the machine is (a tier-III recipe's six motions = 1). "
                    + "A headroom hook: <i>bind stack bed level 0 -0.12 rel</i> steps a bed back as the chord fills.";
            case "fit" -> "<b>fit</b>  0..1 \u2014 the reach hint: over the pinned recipe's matched components only, e^(\u22126\u00b7|reach \u2212 target|) averaged. 1 when every latched motion's reach is on its target, ~0.5 at the edge of rtol. "
                    + "Ratio and phase are the score's business; fit isolates reach, so <i>bind fit root shimmer 0.3 0</i> lets a note steady as the reach comes right.";
            default -> "<b>" + sig + "</b>";
        };
    }
    /** A tooltip that stays up until the mouse leaves the component (the dismiss delay is global, so it is raised only while over it). */
    static void holdTip(JComponent c) {
        c.addMouseListener(new MouseAdapter() {
            int dismiss;
            public void mouseEntered(MouseEvent e) { dismiss = ToolTipManager.sharedInstance().getDismissDelay(); ToolTipManager.sharedInstance().setDismissDelay(Integer.MAX_VALUE); }
            public void mouseExited(MouseEvent e) { ToolTipManager.sharedInstance().setDismissDelay(dismiss); }
        });
    }
    /** What the bind tables' columns take, for their header tooltips (BCOLS order). */
    static String bindColHelp(String col) {
        return switch (col) {
            case "#" -> "the row's place in the list: the family file's order, and the number a bound slider shows. Click to sort back into file order.";
            case "on" -> "off: the row stays in the table but contributes nothing \u2014 for A/B comparison. Written as `off` on the line.";
            case "signal" -> "what drives the row (hover a row for how the machine derives it). arm{n}.pitch is arm{n}.ratio as semitones in one octave; score.&lt;spell&gt; is that spell's recipe match.";
            case "layer" -> "the layer id the row moves, or * for every layer on the bench that has the param.";
            case "param" -> "the param's key as the slider names it (spaces as _). A spell's table also takes <i>blend</i> on the spell's own row: its blend-in curve.";
            case "lo", "hi" -> "the param's value at signal 0 (lo) and at signal 1 (hi); lo above hi runs it downhill. <i>auto</i> (or blank): the layer's marked range, else the full spec range "
                    + "(rel: 0 .. the spec's width). Absolute rows replace the slider's saved value; rel rows add to it \u2014 rows on one param are summed.";
            case "rel" -> "ticked: the row's value is added to the saved slider value (or to an absolute row's value) instead of replacing it. Pitch rows default to rel, in semitones.";
            case "map" -> "<html>reshapes the value before it lands. Blank: a straight line lo \u2192 hi.<br><b>steps=N</b>: quantised to N steps across lo..hi (steps=1 is a hard gate at the half-way point).<br>"
                    + "<b>scale=&lt;chord&gt;</b>: the value, taken as semitones, snaps to that chord's nearest degree in any octave \u2014 one of: "
                    + String.join(", ", CHORD_NAMES).replace(' ', '_') + "<br>(both are written the same way on the bind line).</html>";
            default -> col;
        };
    }



    /** One-time: a family kept the old way, regulator/<f>/<f>.sfx plus spells/*.sfx, becomes the single regulator/<f>.sfx
     *  (the old files go once the new one re-reads with the same spells and layers). */
    static void migrateFamilies() {
        if (!Files.isDirectory(REG_DIR)) return;
        try (var st = Files.list(REG_DIR)) {
            for (Path d : st.filter(Files::isDirectory).toList()) {
                String n = d.getFileName().toString();
                Path pal = d.resolve(n + ".sfx"), out = REG_DIR.resolve(n + ".sfx");
                if (!Files.exists(pal) || Files.exists(out)) continue;
                try {
                    Bench b = parseBench(Files.readAllLines(pal));
                    ArrayList<Spell> sps = new ArrayList<>(); ArrayList<Path> used = new ArrayList<>(List.of(pal));
                    if (Files.isDirectory(d.resolve("spells"))) try (var ss = Files.list(d.resolve("spells"))) {
                        for (Path p : ss.filter(f -> f.getFileName().toString().endsWith(".sfx")).sorted().toList()) {
                            sps.add(makeSpell(p.getFileName().toString().replaceFirst("\\.sfx$", ""), parseBench(Files.readAllLines(p))));
                            used.add(p);
                        }
                    }
                    String text = SfxFormat.familyText(b, sps, b.root);
                    Family chk = parseFamily(Arrays.asList(text.split("\n")));
                    if (chk.spells().size() != sps.size() || chk.palette().layers.size() != b.layers.size()) throw new IOException("re-read mismatch");
                    for (int i = 0; i < sps.size(); i++) if (chk.spells().get(i).bench.layers.size() != sps.get(i).bench.layers.size()) throw new IOException("re-read mismatch in spell " + sps.get(i).id);
                    Files.writeString(out, text);
                    for (Path p : used) Files.delete(p);
                    try { Files.deleteIfExists(d.resolve("spells")); Files.delete(d); } catch (IOException ignored) {}   // only if nothing else is in there
                    System.err.println("migrated family " + n + ": " + used.size() + " files → regulator/" + n + ".sfx");
                } catch (Exception e) { System.err.println("family " + n + " not migrated: " + e); }
            }
        } catch (IOException ignored) {}
    }

    // ---- bench state (UI writes, audio reads)
    boolean benchOn;                        // the workbench shows the bench instead of the timeline (H); remembered in lab.cfg
    String benchName;                       // workspace-relative file the bench was opened from / stamped to
    volatile boolean benchPlaying;
    volatile String family;                                  // the loaded family (regulator/<family>.sfx), remembered in lab.cfg
    int familyGen;                                           // bumped when the family or its spells change (the panel and machine rebuild)
    boolean benchDirty;
    int benchScroll, benchGen;              // benchGen: bumped on structural changes so the panel knows to rebuild its lists
    /** A bench undo snapshot: the family text (palette and spells: spell layers are edited in the same view) and which family. */
    static class BenchSnap { String family, text; }
    final ArrayDeque<BenchSnap> bUndo = new ArrayDeque<>(), bRedo = new ArrayDeque<>();
    BenchSnap pendingBench;
    final HashSet<String> collapsed = new HashSet<>();     // spell groups folded in the bench view
    Spell selSpell;                                        // the spell whose layer is selected (null: a palette layer)
    static final int ROW_LAYER = 0, ROW_SPELL = 1, ROW_SPELL_LAYER = 2;
    record BenchRow(int kind, Clip clip, Spell spell) {}
    /** What the bench view lists: the palette's layers, then each spell of the family as a group of its own layers
     *  (the spell that is itself open on the bench is not repeated). */
    java.util.List<BenchRow> benchRows() {
        ArrayList<BenchRow> out = new ArrayList<>();
        for (Clip c : bench.layers) out.add(new BenchRow(ROW_LAYER, c, null));
        for (Spell sp : mix.spells) {
            out.add(new BenchRow(ROW_SPELL, null, sp));
            if (!collapsed.contains(sp.id)) for (Clip c : sp.bench.layers) out.add(new BenchRow(ROW_SPELL_LAYER, c, sp));
        }
        return out;
    }
    BenchSnap benchSnap() { BenchSnap b = new BenchSnap(); b.family = family; b.text = familyText(); return b; }
    String familyText() { return SfxFormat.familyText(bench, mix.spells, rootHz); }
    /** A spell changed: the working copy autosaves (spells live in the family's file). */
    void markSpellDirty(Spell sp) { benchDirty = true; benchGen++; lastEditAt = System.currentTimeMillis(); }
    BenchPanel bpanel; boolean bpanelOn;    // the docked regulator panel (J); remembered in lab.cfg
    String panelFold = "";                  // the panel's folded sections ("signals", "spells"), remembered in lab.cfg
    double[] sigHold; HashMap<String, Double> scoreHold;   // the panel's own values from before the machine took the signals over
    /** The machine takes the signals and spell scores (its core writes them every frame while it drives) or gives them
     *  back: the panel's values from before it took over return, so nothing the machine last wrote keeps blending the
     *  layers behind the panel's sliders once drive is unticked or the window is closed. */
    void setSigDriven(boolean on) {
        if (on == mix.driven) return;
        if (on) { sigHold = sigVal.clone(); scoreHold = new HashMap<>(spellScore); }
        else {
            if (sigHold != null) System.arraycopy(sigHold, 0, sigVal, 0, sigVal.length);
            if (scoreHold != null) { spellScore.keySet().retainAll(scoreHold.keySet()); spellScore.putAll(scoreHold); }
            sigHold = null; scoreHold = null;
        }
        mix.driven = on; benchGen++;
        if (bpanel != null) bpanel.pull();
    }
    // ---- the live mix (sfxlab.runtime.BenchMixer: signals, binds, the spell blend, fired one-shots); the mod runs the same class
    final BenchMixer mix = new BenchMixer(lock);
    final Bench bench = mix.bench;
    final double[] sigVal = mix.sigVal;
    final java.util.concurrent.ConcurrentHashMap<String, Double> spellScore = mix.spellScore;
    final java.util.Set<String> mutedSigs = mix.mutedSigs;
    final java.util.concurrent.ConcurrentLinkedQueue<Clip> fireQ = mix.fireQ;
    final ArrayList<Clip> transients = mix.transients;
    boolean bindLive(Bind b) { return mix.bindLive(b); }
    double signal(String name) { return mix.signal(name); }
    double signalMax(String name) { return mix.signalMax(name); }
    double signalNorm(String name) { return mix.signalNorm(name); }
    /** The bindable signal names: the contract plus one score per spell of the family. */
    java.util.List<String> signalChoices() {
        ArrayList<String> out = new ArrayList<>(Arrays.asList(SIGNAL_CHOICES));
        for (Spell sp : mix.spells) out.add("score." + sp.id);
        return out;
    }
    double blendW() { return mix.blendW(); }
    void benchLive(double now, List<Clip> out) { mix.live(now, out); }
    Bind blendBind(Spell sp) { return mix.blendBind(sp); }
    double spellWeight(Spell sp) { return mix.spellWeight(sp); }
    void applyBinds(List<Bind> bs, Clip c, double[] m) { mix.applyBinds(bs, c, m); }
    double[] bindRange(Bind b, Clip c, int pi) { return mix.bindRange(b, c, pi); }
    double bindTerm(Bind b, Clip c, int pi) { return mix.bindTerm(b, c, pi); }
    /** `lo + w·sig` with the zeros dropped, for the bind tooltips. */
    static String bindExpr(double lo, double hi, String sig) {
        String w = fmtNum5(hi - lo);
        if (w.startsWith("-")) w = "(" + w.replace("-", "\u2212") + ")";
        String t = w.equals("0") ? "" : w.equals("1") ? sig : w + "\u00b7" + sig;
        if (lo == 0) return t.isEmpty() ? "0" : t;
        return fmtNum5(lo) + (t.isEmpty() ? "" : " + " + t);
    }
    /** The algebra behind a bind row, for the table's tooltip: this row's term, then the whole sum on its param
     *  (rows added; one absolute row replaces the saved value, rel rows ride on top) and the value that gives right now. */
    String bindTip(Bench target, Bind b) {
        Clip c = b.layer.equals("*") ? (sel != null && sel.id != null ? target.byId(sel.id) : null) : target.byId(b.layer);
        if (c == null && b.layer.equals("*")) for (Clip l : target.layers) if (idxOf(l.type, b.param) >= 0) { c = l; break; }
        if (c == null) return "<html>no layer " + b.layer + " on this bench</html>";
        int pi = idxOf(c.type, b.param);
        if (pi < 0) return "<html>" + c.id + " has no param " + b.param + "</html>";
        double[] lh = bindRange(b, c, pi);
        String saved = fmtNum5(c.p[pi]), tgt = c.id + "." + b.param;
        StringBuilder sb = new StringBuilder("<html><b>row " + (target.binds.indexOf(b) + 1) + "</b>   " + tgt + (b.layer.equals("*") ? "   (every layer; the numbers here are " + c.id + "'s)" : "") + "<br>");
        sb.append(b.rel ? "adds   " + bindExpr(lh[0], lh[1], b.sig) + "   on top of the saved " + saved + " (or of an absolute row's value)"
                        : "sets   " + bindExpr(lh[0], lh[1], b.sig) + "   \u2192 " + fmtNum5(lh[0]) + " at " + b.sig + " 0, " + fmtNum5(lh[1]) + " at " + b.sig + " 1; the saved " + saved + " drops out");
        if (b.auto()) sb.append("<br>lo / hi auto: " + (c.range != null && c.range.get(pi) != null ? "the marked range" : b.rel ? "0 .. the spec's width" : "the spec range"));
        if (b.steps > 0) sb.append("<br>then snapped to " + b.steps + " steps");
        if (b.scale != null) sb.append("<br>then snapped to the " + b.scale.replace('_', ' ') + " scale");
        if (!bindLive(b)) sb.append("<br><i>off" + (b.mute ? "" : " (its signal is muted)") + ": contributes nothing</i>");
        // the sum on this param: every live row that touches it
        ArrayList<Bind> rows = new ArrayList<>();
        for (Bind o : target.binds) if ((o.layer.equals("*") || o.layer.equals(c.id)) && o.param.equals(b.param) && bindLive(o)) rows.add(o);
        int nAbs = 0; for (Bind o : rows) if (!o.rel) nAbs++;
        ArrayList<String> terms = new ArrayList<>();
        if (nAbs != 1) terms.add(saved);   // one absolute row cancels the saved value and leads instead
        StringBuilder sig = new StringBuilder();
        double now = c.p[pi];
        for (Bind o : rows) {
            double[] r = bindRange(o, c, pi);
            String e = bindExpr(r[0], r[1], o.sig) + " <font color=#808080>[" + (target.binds.indexOf(o) + 1) + "]</font>";
            if (o.rel) terms.add(e); else if (nAbs > 1) terms.add("(" + e + " \u2212 " + saved + ")"); else terms.add(0, e);
            now += bindTerm(o, c, pi);
            if (sig.length() > 0) sig.append(", ");
            sig.append(o.sig).append(' ').append(fmtNum5(signal(o.sig)));
        }
        PSpec ps = spec(c.type, pi);
        sb.append("<br><br>" + tgt + " = " + String.join(" + ", terms));
        if (nAbs > 1) sb.append("<br><b>" + nAbs + " absolute rows: each subtracts the saved value, so the slider comes back in with a minus sign \u2014 keep one absolute row, make the others rel</b>");
        sb.append("<br>right now: " + fmtNum5(Math.max(ps.min(), Math.min(ps.max(), now))) + (rows.isEmpty() ? "" : "   with " + sig) + "   (clamped to " + fmtNum(ps.min()) + " .. " + fmtNum(ps.max()) + ")");
        sb.append("<br><br><div width=520>" + sigHelp(b.sig) + "</div>");
        return sb.append("</html>").toString();
    }
    double[] spellBindMod(Spell sp, Clip s) { return mix.spellBindMod(sp, s); }
    /** Solo a layer: a palette layer with the searching mix's binds, or a spell's layer alone at its target values. */
    void toggleSolo(Clip c, Spell sp) {
        if (mix.solo == c && benchPlaying) { mix.solo = null; mix.soloSpell = null; toast("solo off"); return; }   // a stopped bench: P plays it again
        mix.solo = c; mix.soloSpell = sp;
        if (!benchPlaying) toggleBenchPlay();
        List<Bind> bs; synchronized (lock) { bs = new ArrayList<>(sp != null ? sp.bench.binds : bench.binds); }
        toast((sp != null ? "spell " + sp.id + " · " : "") + c.id + " solo, " + (mix.driven ? "live through its binds (the machine drives the signals)" : "as authored" + (sp != null ? " for the spell" : ""))
              + " (P or the S box clears)" + (mix.driven ? bindNote(bs, c) : ""));
    }
    /** Why a soloed layer sounds the way it does: the binds that touch it, each with what its signal holds right now,
     *  and a warning when they pin its level at 0 (a bound level at a signal that sits at 0 is silence, not a bug). */
    String bindNote(List<Bind> bs, Clip c) {
        if (!mix.bindsOn) return "";
        StringBuilder sb = new StringBuilder();
        for (Bind b : bs) {
            if (!(b.layer.equals("*") || b.layer.equals(c.id)) || idxOf(c.type, b.param) < 0) continue;
            sb.append(sb.length() == 0 ? "   binds: " : ", ").append(b.param).append(" ← ").append(b.sig).append(String.format(Locale.ROOT, " (%.2f)", signal(b.sig)));
        }
        if (sb.length() == 0) return "";
        double[] m = new double[c.p.length];
        applyBinds(bs, c, m);
        if (c.p[P_LEVEL] + m[P_LEVEL] < 0.01) sb.append("  — its level is held at 0 right now; cut the machine's power to hear it as authored");
        return sb.toString();
    }
    /** ENTER / the machine's Cut power: the bench stops and rewinds and the solo is dropped, so the next P means "hear this one". */
    void stopBench() { benchPlaying = false; seekTo = 0; mix.solo = null; mix.soloSpell = null; }
    /** Fires a one-shot layer: a copy, so a layer can overlap itself. Your own fires (P, the panel's buttons) wake a
     *  stopped bench where it stands, no rewind. Returns false when the shot was dropped instead. */
    boolean fire(Clip c) { return fire(c, true); }
    /** With wake off (the machine's events) a stopped bench stays stopped and the shot is dropped: the machine
     *  never restarts playback behind your back, and no shots pile up to fire the next time you press SPACE. */
    boolean fire(Clip c, boolean wake) {
        if (!benchPlaying) { if (!wake) return false; benchPlaying = true; }
        mix.fire(c);
        return true;
    }
    /** The lock / unlock event: every one-shot layer marked for it fires (the bench's own and the picked signature's). */
    void fireEvent(int on) { fireEvent(on, true, true); }
    /** The lock / unlock event for the bench's own layers (the palette's, or the spell being edited). */
    void fireEvent(int on, boolean setScore, boolean wake) {
        int n = 0;
        List<Clip> ls;
        synchronized (lock) { ls = new ArrayList<>(bench.layers); }
        for (Clip c : ls) if (c.on == on && fire(c, wake)) n++;
        if (setScore) { if (on == ON_LOCK) setSignal(SIG_SCORE, 1); else if (sigVal[SIG_SCORE] > 0.5) setSignal(SIG_SCORE, 0.5); }
        toast(ON_NAMES[on] + (n > 0 ? ": " + n + " one-shot" + (n > 1 ? "s" : "") + " fired" : " — no bench layer is set to fire on it"));
    }
    /** A spell's own event: its one-shots marked for it fire. */
    int fireSpell(String id, int on) { return fireSpell(id, on, true); }
    int fireSpell(String id, int on, boolean wake) {
        int n = 0;
        for (Spell sp : mix.spells) if (sp.id.equals(id)) for (Clip c : sp.bench.layers) if (c.on == on && fire(c, wake)) n++;
        return n;
    }

    // ---- families: regulator/<family>.sfx holds the palette and, as `spell <id>` sections, the roster; the loaded one's edits autosave to its working copy until S
    static java.util.List<String> familyNames() {
        ArrayList<String> out = new ArrayList<>();
        try (var st = Files.list(REG_DIR)) {
            st.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".sfx")).map(p -> p.getFileName().toString().replaceFirst("\\.sfx$", "")).sorted().forEach(out::add);
        } catch (IOException ignored) {}
        return out;
    }
    static Path familyFile(String name) { return REG_DIR.resolve(name + ".sfx"); }
    /** The family a file is, when it sits in regulator/ as <family>.sfx; else null. */
    static String familyOf(Path f) {
        Path p = f.toAbsolutePath().normalize().getParent(); String n = f.getFileName().toString();
        return p != null && p.equals(REG_DIR.toAbsolutePath().normalize()) && n.endsWith(".sfx") ? n.substring(0, n.length() - 4) : null;
    }
    /** Loads a family onto the bench: its palette and its spells, from its file, or from its working copy when that holds
     *  unsaved edits (they survive switching families and restarts). null detaches: the bench stays, spells go, autosave
     *  returns to bench.sfx. */
    void loadFamily(String name) { loadFamily(name, true); }
    void loadFamily(String name, boolean restore) {
        if (name == null || name.isEmpty()) { family = null; unsaved = false; savedText = null; setSpells(new ArrayList<>()); benchName = null; markEdit(); saveCfg(); return; }
        Path f = familyFile(name);
        try {
            Family fm = parseFamily(Files.readAllLines(f));
            pushBenchUndo("");
            benchPlaying = false;
            family = name;
            installBench(fm.palette());
            if (benchOn && fm.palette().root > 0) rootHz = fm.palette().root;
            sel = null; selSpell = null;
            setSpells(fm.spells());
            benchName = relPath(f); benchDirty = false;
            savedText = familyText(); unsaved = false;
            Path w = workingFile(name);
            if (restore && Files.exists(w)) {
                Family wf = parseFamily(Files.readAllLines(w));
                double wRoot = benchOn && wf.palette().root > 0 ? wf.palette().root : rootHz;
                if (!SfxFormat.familyText(wf.palette(), wf.spells(), wRoot).equals(savedText)) {
                    installBench(wf.palette()); rootHz = wRoot; setSpells(wf.spells());
                    unsaved = true;
                    toast("opened " + name + " with its unsaved edits (" + bench.layers.size() + " layers, " + mix.spells.size() + " spells) — S saves them to " + relPath(f) + ", shift+O reverts to the saved file");
                    reportProblems(name, Files.readAllLines(w));
                    saveCfg();
                    return;
                }
            }
            toast("opened " + relPath(f) + " (" + bench.layers.size() + " layers, " + bench.binds.size() + " binds, " + mix.spells.size() + " spell" + (mix.spells.size() == 1 ? "" : "s") + ") — edits autosave to a working copy, S saves the family");
            reportProblems(name, Files.readAllLines(f));
        } catch (Exception e) { toast("family " + name + " failed: " + e); }
        saveCfg();
    }
    /** A family file's format problems (SfxFormat.validateFamily), which the lenient loader would otherwise skip in silence:
     *  listed on the console, counted in a toast. The mod refuses a family that has any. */
    void reportProblems(String name, List<String> lines) {
        List<String> probs = SfxFormat.validateFamily(lines);
        if (probs.isEmpty()) return;
        System.err.println(name + ".sfx: " + probs.size() + " format problem" + (probs.size() == 1 ? "" : "s") + ":");
        for (String p : probs) System.err.println("  " + p);
        toast(name + ".sfx has " + probs.size() + " format problem" + (probs.size() == 1 ? "" : "s") + " the mod would refuse — the first: " + probs.get(0) + " (all: ./sfxlab --check " + name + ")");
    }
    /** S with a family loaded: the working state becomes the family file. */
    void saveFamily() {
        if (family == null) { saveFamilyAs(); return; }
        try {
            String txt = familyText();
            Files.createDirectories(REG_DIR);
            Files.writeString(familyFile(family), txt);
            savedText = txt; unsaved = false; benchDirty = false;
            Files.deleteIfExists(workingFile(family));
            toast("saved " + relPath(familyFile(family)));
        } catch (Exception e) { toast("save failed: " + e); }
    }
    /** shift+O: drop the unsaved edits and reload the family file (undo can still bring them back this session). */
    void revertFamily() {
        if (family == null) { toast("no family loaded"); return; }
        if (!unsaved && !benchDirty) { toast(family + " has no unsaved edits"); return; }
        if (!java.awt.GraphicsEnvironment.isHeadless() && JOptionPane.showConfirmDialog(this, "Drop the unsaved edits to " + family + " and reload " + relPath(familyFile(family)) + "?",
                "Revert family", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try { Files.deleteIfExists(workingFile(family)); } catch (IOException ignored) {}
        loadFamily(family, false);
        toast("reverted " + family + " to " + relPath(familyFile(family)) + " (ctrl+Z brings the edits back)");
    }
    void setSpells(java.util.List<Spell> out) {
        mix.spells = out;
        if (mix.soloSpell != null) { mix.solo = null; mix.soloSpell = null; }
        spellScore.keySet().removeIf(k -> out.stream().noneMatch(sp -> sp.id.equals(k)));
        familyGen++; benchGen++;
        primeFamily();
    }
    /** Families primed this session (Engine.prime: analyses and recordings loaded, the JIT warmed on every layer's path). */
    final Set<String> primed = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Once per family per session, in the background: pay every layer's first-use cost before the first lock does,
     *  which otherwise stalls the audio thread (the stutter as a spell first locks on). */
    void primeFamily() {
        String f = family;
        if (f == null || Boolean.getBoolean("sfxlab.noprime") || !primed.add(f)) return;
        ArrayList<Clip> all;
        synchronized (lock) { all = new ArrayList<>(bench.layers); }
        for (Spell sp : mix.spells) all.addAll(sp.bench.layers);
        Thread t = new Thread(() -> {
            try { Engine.prime(all, 0.5); }
            catch (Exception e) { System.err.println("priming " + f + " failed: " + e); }
        }, "prime-" + f);
        t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }
    /** The family's recipes for the machine (spells with a recipe line); the prototype's roster when there are none. */
    RegulatorCore.Recipe[] familyRecipes() {
        ArrayList<RegulatorCore.Recipe> out = new ArrayList<>();
        for (Spell sp : mix.spells) if (sp.recipe != null) out.add(sp.recipe);
        return out.isEmpty() ? RegulatorCore.DEFAULT_RECIPES : out.toArray(new RegulatorCore.Recipe[0]);
    }
    Spell spell(String id) { for (Spell sp : mix.spells) if (sp.id.equals(id)) return sp; return null; }
    /** The recipe's text form without the keyword: "tier=1 [secret=1] X3p1 Y2p0@0.35 ...". */
    static String recipeText(Bench b) { return b.comps == null ? "" : recipeLine(b).substring(7); }
    /** Parses recipe text; null when it holds no motions. */
    static Bench parseRecipe(String text) {
        if (text == null || text.isBlank()) return null;
        Bench b = parseBench(List.of("recipe " + text.trim().replaceFirst("^recipe\\s+", "")));
        return b.comps != null && b.comps.length > 0 ? b : null;
    }
    /** Sets a spell's recipe; the working copy autosaves and the machine re-reads the roster. */
    boolean setSpellRecipe(Spell sp, String text) {
        Bench r = parseRecipe(text);
        if (r == null) { toast("recipe: tier=N [secret=1] then motions like X3p1 or Y5p2@0.35 (axis, integer ratio, phase in quarters, blueprint amplitude)"); return false; }
        pushUndo("");
        Bench b = sp.bench;
        b.comps = r.comps; b.tier = r.tier; b.secret = r.secret; b.rtol = r.rtol;
        sp.recipe = new RegulatorCore.Recipe(sp.id, sp.name, b.tier, "", b.secret, b.comps); sp.recipe.rtol = b.rtol;
        markSpellDirty(sp); familyGen++;
        String prob = recipeProblem(r);
        toast(sp.id + " recipe: " + recipeLine(r).substring(7) + (prob != null ? "   — WARNING, unbuildable: " + prob : RegulatorCore.degenerate(sp.recipe) ? "   — WARNING: this trace retraces itself into an open line (reads poorly as a sigil)" : ""));
        return true;
    }
    void recipeDialog(Spell sp, String prefill) {
        String in = (String) JOptionPane.showInputDialog(this,
                "Recipe of " + sp.name + " (tier=N [secret=1] [rtol=0.1], then motions like X3p1r0.7: axis, integer ratio, phase in quarters, reach target):",
                "Recipe", JOptionPane.PLAIN_MESSAGE, null, null, prefill != null ? prefill : recipeText(sp.bench));
        if (in != null) setSpellRecipe(sp, in);
    }
    void setSignal(int i, double v) { sigVal[i] = Math.max(0, Math.min(SIG_MAX[i], v)); if (bpanel != null) bpanel.pull(); }

    // ---- layer management
    String newLayerId(String base) {
        String b = base.replaceAll("^.*/", "").replaceAll("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_]+", "").toLowerCase(Locale.ROOT);
        if (b.isEmpty()) b = "layer";
        if (b.length() > 16) b = b.substring(0, 16);
        String id = b; int n = 2;
        while (bench.byId(id) != null) id = b + n++;
        return id;
    }
    /** Adds any clip to the bench as a layer: endless unless it already carries on=. */
    Clip addLayer(Clip c) {
        pushUndo("");
        c.track = 0; c.start = 0; c.vlink = false;
        if (c.id == null || bench.byId(c.id) != null) c.id = newLayerId(c.id != null ? c.id : c.name);
        if (c.on == ON_NONE) c.dur = ENDLESS;
        if (c.type == SAMPLE && c.on == ON_NONE) c.p[NCOMMON] = 1;   // an endless sample layer loops
        synchronized (lock) { bench.layers.add(c); }
        sel = c; benchGen++; markEdit();
        return c;
    }
    /** In bench mode a new clip becomes a layer; on the timeline it lands as usual. */
    void place(Clip c) {
        if (benchOn) addLayer(c);
        else { synchronized (lock) { clips.add(c); } sel = c; markEdit(); }
    }
    void removeLayer(Clip c) {
        pushUndo("");
        synchronized (lock) { bench.layers.remove(c); }
        if (sel == c) sel = null;
        if (mix.solo == c) mix.solo = null;
        benchGen++; markEdit();
    }
    /** How long a layer plays once: the recording's length at its rate, else 1.5 s. */
    void setLayerOn(Clip c, int on) {
        pushUndo("");
        c.on = on;
        c.dur = on == ON_NONE ? ENDLESS : (c.dur >= ENDLESS / 2 ? naturalDur(c) : c.dur);
        if (on == ON_NONE && c.type == SAMPLE) c.p[NCOMMON] = 1;
        benchGen++; markEdit();
    }
    void renameLayer(Clip c) {
        String in = (String) JOptionPane.showInputDialog(this, "Layer id (binds and signatures refer to it):", "Rename layer", JOptionPane.PLAIN_MESSAGE, null, null, c.id);
        if (in == null) return;
        String id = in.trim().replaceAll("[^A-Za-z0-9_]+", "");
        if (id.isEmpty() || id.equals(c.id)) return;
        if (bench.byId(id) != null) { toast("id " + id + " is taken"); return; }
        pushUndo("");
        for (Bind b : bench.binds) if (b.layer.equals(c.id)) b.layer = id;
        c.id = id; benchGen++; markEdit();
    }
    /** A layer the sample browser's next pick swaps into (armed from the row menu); null = the browser adds as usual. */
    Clip swapTarget;
    /** The layer plays samples/<f> from now on, keeping its id, type, saved values, ranges, notes and binds. A spell's copy of
     *  the layer (same id and type, same old file) follows, so the spell blends the same recording. Partials re-analyse. */
    void swapSource(Clip c, String f) {
        if (c.type != SAMPLE && c.type != CHOIR && c.type != PARTIALS) { toast(c.id + " is a " + TYPE_NAMES[c.type] + " layer: no recording to swap"); return; }
        if (f.equals(c.file)) { toast(c.id + " already plays " + f); return; }
        float[][] smp = sample(f);
        if (smp[0].length <= 1) { toast("couldn't decode " + f); return; }
        pushUndo("");
        String old = c.file, name = f.replaceFirst("\\.[^.]+$", "");
        ArrayList<Spell> followed = new ArrayList<>();
        synchronized (lock) {
            c.file = f; c.name = name; c.pa = null; c.paFloor = Long.MIN_VALUE; c.paRetry = 0;
            for (Spell sp : mix.spells) {
                Clip sc = sp.bench.byId(c.id);
                if (sc == null || sc == c || sc.type != c.type || !Objects.equals(sc.file, old)) continue;
                sc.file = f; sc.name = name; sc.pa = null; sc.paFloor = Long.MIN_VALUE; sc.paRetry = 0;
                followed.add(sp);
            }
        }
        for (Spell sp : followed) markSpellDirty(sp);
        if (c.type == PARTIALS) partials(c, false);
        benchGen++; markEdit();
        double dur = smp[0].length / (double) SR;
        toast(String.format(Locale.ROOT, "%s now plays %s (%.2fs), was %s \u2014 id, values and binds kept%s%s%s", c.id, f, dur, old,
                c.on != ON_NONE ? String.format(Locale.ROOT, "; one-shot length stays %.2fs", c.dur) : "",
                (c.keyed & KEY_PITCH) != 0 ? "; R retunes it to the root" : "",
                followed.isEmpty() ? "" : "; followed in spell " + followed.stream().map(sp -> sp.id).collect(java.util.stream.Collectors.joining(", "))));
    }
    void setRange(Clip c, int pi, double lo, double hi) {
        pushUndo("");
        if (c.range == null) c.range = new HashMap<>();
        double[] r = c.range.get(pi);
        if (Double.isNaN(lo)) lo = r != null ? r[0] : spec(c.type, pi).min();
        if (Double.isNaN(hi)) hi = r != null ? r[1] : spec(c.type, pi).max();
        c.range.put(pi, new double[]{Math.min(lo, hi), Math.max(lo, hi)});
        benchGen++; markEdit();
    }
    void clearRange(Clip c, int pi) {
        pushUndo("");
        if (c.range != null) c.range.remove(pi);
        if (c.rnote != null) c.rnote.remove(pi);
        benchGen++; markEdit();
    }
    /** Adds a bind to the palette, or to the selected spell when one of its layers is selected. */
    void addBind(String sg, String layer, String pname) {
        pushUndo("");
        boolean rel = pname.equals("pitch");   // pitch rides on the layer's own tuning; everything else targets absolute knob positions
        Bench target = selSpell != null && sel != null && selSpell.bench.layers.contains(sel) ? selSpell.bench : bench;
        synchronized (lock) { target.binds.add(new Bind(sg, layer, pname, rel ? 0 : Double.NaN, rel ? 12 : Double.NaN, rel)); }
        benchGen++;
        if (target == bench) markEdit(); else markSpellDirty(selSpell);
        toast((target == bench ? "" : "spell " + selSpell.id + ": ") + "bind " + sg + " → " + layer + "." + pname + (rel ? " (+0..12 st)" : " (marked range, else full range — edit in the panel)"));
    }
    void removeBind(Bind b) {
        pushUndo("");
        synchronized (lock) { if (!bench.binds.remove(b)) for (Spell sp : mix.spells) if (sp.bench.binds.remove(b)) markSpellDirty(sp); }
        benchGen++; markEdit();
    }
    /** The binds on a param: the palette's for a palette layer, the spell's for a spell's layer. */
    List<Bind> bindsOn(Clip c, int pi) {
        ArrayList<Bind> out = new ArrayList<>();
        String k = key(c.type, pi);
        for (Bind b : bindSrc(c).binds) if (b.param.equals(k) && (b.layer.equals("*") || b.layer.equals(c.id))) out.add(b);
        return out;
    }
    /** The table a layer's binds live in: the spell's for a spell's own layer, else the palette's. */
    Bench bindSrc(Clip c) { return selSpell != null && c != null && selSpell.bench.layers.contains(c) ? selSpell.bench : bench; }
    /** The rows of the bind table driving a param, as the panel numbers them: "3", "2,5", a * after a row that binds every layer. */
    String bindRows(Clip c, int pi) {
        List<Bind> all = bindSrc(c).binds;
        StringBuilder sb = new StringBuilder();
        for (Bind b : bindsOn(c, pi)) { if (sb.length() > 0) sb.append(','); sb.append(all.indexOf(b) + 1); if (b.layer.equals("*")) sb.append('*'); }
        return sb.toString();
    }
    /** shift+H: the selected timeline clip joins the bench. Loops become beds; anything else a lock one-shot. */
    void sendSelToBench() {
        if (sel == null) { toast("select a clip first"); return; }
        Clip c = copyClip(sel);
        c.id = null;
        boolean bed = c.type == CLOUD || c.type == TONE || c.type == NOISE || c.type == CHOIR || (sampled(c) && looping(c));
        c.on = bed ? ON_NONE : ON_LOCK;
        boolean was = benchOn;
        benchOn = true;
        addLayer(c);
        benchOn = was;
        toast(c.id + " added to the bench as " + (bed ? "an endless layer" : "a one-shot on lock") + (was ? "" : " (H shows the bench)"));
    }
    /** A layer goes back to the timeline as a clip at the playhead. */
    void layerToTimeline(Clip c) {
        Clip n = copyClip(c);
        n.id = null; n.on = ON_NONE; n.lmute = false; n.range = null; n.rnote = null;
        n.track = selTrack; n.start = playPos;
        if (n.dur >= ENDLESS / 2) n.dur = sampled(c) ? naturalDur(c) : 4.0;
        boolean was = benchOn;
        benchOn = false;
        pushUndo("");
        synchronized (lock) { clips.add(n); }
        benchOn = was;
        markEdit();
        toast(c.id + " copied to the timeline at the playhead (H shows it)");
    }

    // ---- bench files: the autosave keeps a loaded family's working copy in regulator/.working/ (S writes the family
    // file), and a scratch bench in bench.sfx
    void saveBench(boolean quiet) {
        try {
            if (family != null) {
                String txt = familyText();
                unsaved = !txt.equals(savedText);
                Path w = workingFile(family);
                if (unsaved) { Files.createDirectories(WORKING_DIR); Files.writeString(w, txt); }
                else Files.deleteIfExists(w);   // edited back to the saved state
            } else Files.writeString(BENCH_FILE, benchText(bench, rootHz));
            benchDirty = false;
            if (!quiet) toast(family != null ? "working copy saved (S writes " + relPath(familyFile(family)) + ")" : "saved " + relPath(BENCH_FILE));
        } catch (Exception e) { toast("bench save failed: " + e); }
    }
    void installBench(Bench b) {
        synchronized (lock) {
            bench.layers.clear(); bench.layers.addAll(b.layers);
            bench.binds.clear(); bench.binds.addAll(b.binds);
        }
        bench.notes.clear(); bench.notes.addAll(b.notes);
        bench.comments.clear(); bench.comments.addAll(b.comments);
        bench.palette = b.palette;
        bench.name = null; bench.comps = null;   // the palette carries no recipe: spells do
        mix.solo = null; mix.soloSpell = null; benchScroll = 0; benchGen++;
    }
    String relPath(Path f) {
        try { return DIR.relativize(f.toAbsolutePath().normalize()).toString().replace('\\', '/'); }
        catch (Exception e) { return f.toString(); }
    }
    /** A scratch bench from any bench file (a family file from elsewhere keeps its spells): no family, autosaves to bench.sfx. */
    void loadBenchFile(Path f) {
        try {
            Family fm = parseFamily(Files.readAllLines(f));
            pushBenchUndo("");
            benchPlaying = false;
            family = null;
            installBench(fm.palette());
            rootHz = fm.palette().root > 0 ? fm.palette().root : ROOT_DEFAULT;
            sel = null; selSpell = null;
            setSpells(fm.spells());
            benchName = f.equals(BENCH_FILE) ? null : relPath(f);
            markEdit(); saveCfg();
            toast("opened " + f.getFileName() + " as a scratch bench (" + bench.layers.size() + " layers, " + bench.binds.size() + " binds" + (mix.spells.isEmpty() ? "" : ", " + mix.spells.size() + " spells") + ") — S saves it as a family");
        } catch (Exception e) { toast("open failed: " + e); }
    }
    void openBench() {
        JFileChooser fc = new JFileChooser((Files.isDirectory(REG_DIR) ? REG_DIR : PROJECTS_DIR).toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("bench files: a family (regulator/<family>.sfx) or a scratch bench", "sfx"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path f = fc.getSelectedFile().toPath().toAbsolutePath().normalize();
        String fam = familyOf(f);
        if (fam != null) loadFamily(fam); else loadBenchFile(f);
    }
    /** shift+S on the bench (S with no family): the bench and its spells become the family regulator/<name>.sfx, naming a
     *  scratch bench or forking the loaded family under a new name. */
    void saveFamilyAs() {
        String name = (String) JOptionPane.showInputDialog(this,
                "Save the bench and its spells as a family (regulator/<name>.sfx):" + (family != null ? "\nA new name forks " + family + " (its own file stays as last saved)." : ""),
                "Save family", JOptionPane.PLAIN_MESSAGE, null, null, family != null ? family : "");
        if (name == null) return;
        name = name.trim().replaceFirst("\\.sfx$", "").replaceAll("[^A-Za-z0-9_-]+", "_");
        if (name.isEmpty()) return;
        Path f = familyFile(name);
        if (Files.exists(f) && !name.equals(family) && JOptionPane.showConfirmDialog(this,
                "family " + name + " exists — overwrite it?", "Save family", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        try {
            Files.createDirectories(REG_DIR);
            String old = family;
            family = name;
            String txt = familyText();
            Files.writeString(f, txt);
            savedText = txt; unsaved = false;
            if (old != null) Files.deleteIfExists(workingFile(old));   // its edits went into the new family
            Files.deleteIfExists(workingFile(name));
            benchName = relPath(f); benchDirty = false; familyGen++; benchGen++;
            saveCfg();
            toast("saved " + relPath(f) + " — edits autosave to a working copy, S saves again");
        } catch (Exception e) { toast("save failed: " + e); }
    }
    /** The `new spell` button: a spell joins the loaded family with its recipe (prefilled from the machine's sigil when one is
     *  on the arms) and, if asked, every palette layer at its current values as the lock targets. */
    void newSpell() {
        if (family == null) { toast("save the bench as a family first (S) — spells live in the family file"); return; }
        String pre = machine != null && machine.frame != null && machine.frame.isVisible() ? machine.currentSigil() : "tier=1 ";
        JTextField idF = new JTextField("", 14), rcF = new JTextField(pre, 28);
        JCheckBox allC = new JCheckBox("start with every palette layer at its current values", false);
        JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
        p.add(new JLabel("id (e.g. fire_bolt)")); p.add(idF);
        p.add(new JLabel("recipe: tier=N [secret=1] [rtol=0.1] X3p1r0.7 Y2p0 …")); p.add(rcF);
        p.add(new JLabel("")); p.add(allC);
        if (JOptionPane.showConfirmDialog(this, p, "New spell of " + family, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String id = idF.getText().trim().replaceAll("[^A-Za-z0-9_-]+", "_").toLowerCase(Locale.ROOT);
        if (id.isEmpty()) { toast("a spell needs an id"); return; }
        if (spell(id) != null) { toast("spell " + id + " exists — its recipe… button edits the recipe"); return; }
        Bench r = parseRecipe(rcF.getText());
        pushUndo("");
        Bench b = new Bench();
        if (r != null) { b.comps = r.comps; b.tier = r.tier; b.secret = r.secret; b.rtol = r.rtol; }
        if (allC.isSelected()) for (Clip c : bench.layers) { Clip n = copyClip(c); n.lmute = false; n.range = null; n.rnote = null; b.layers.add(n); }
        Spell sp = makeSpell(id, b);
        ArrayList<Spell> out = new ArrayList<>(mix.spells); out.add(sp); setSpells(out);
        markSpellDirty(sp);
        String prob = recipeProblem(b);
        toast("spell " + id + " added to " + family
                + (sp.recipe == null ? " — no recipe yet, so the machine cannot score it (recipe… sets one)" : prob != null ? "   — WARNING, unbuildable: " + prob : RegulatorCore.degenerate(sp.recipe) ? "   — WARNING: this trace retraces itself into an open line" : "")
                + "; a palette row's menu pushes layers into it");
    }
    void deleteSpell(Spell sp) {
        if (JOptionPane.showConfirmDialog(this, "Delete spell " + sp.id + " from " + family + "? (ctrl+Z undoes)", "Delete spell", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        pushUndo("");
        ArrayList<Spell> out = new ArrayList<>(mix.spells); out.remove(sp); setSpells(out);
        if (selSpell == sp) { sel = null; selSpell = null; }
        collapsed.remove(sp.id);
        markSpellDirty(sp);
        toast("spell " + sp.id + " deleted");
    }
    /** N: a fresh scratch bench. The loaded family is left as it is on disk; the panel brings it back. */
    void clearBench() {
        pushBenchUndo("");
        benchPlaying = false;
        String was = family;
        family = null; setSpells(new ArrayList<>());
        installBench(new Bench());
        sel = null; selSpell = null; benchName = null;
        markEdit(); saveCfg();
        toast("scratch bench" + (was != null ? " — family " + was + " is untouched, pick it in the panel to come back" : "") + " (ctrl+Z undoes)");
    }

    // ---- bench undo (whole-bench text snapshots; pushUndo / commitPending route here in bench mode)
    void pushBenchUndo(String tag) {
        long now = System.currentTimeMillis();
        if (!tag.isEmpty() && tag.equals(lastOpTag) && now - lastOpAt < 1200) { lastOpAt = now; return; }
        lastOpTag = tag; lastOpAt = now;
        bUndo.push(benchSnap());
        if (bUndo.size() > 100) bUndo.removeLast();
        bRedo.clear();
    }
    void restoreBench(BenchSnap snap) {
        Family fm = parseFamily(Arrays.asList(snap.text.split("\n")));
        String selId = sel != null ? sel.id : null, selSp = selSpell != null ? selSpell.id : null;
        family = snap.family;
        installBench(fm.palette());
        setSpells(fm.spells());
        Spell sp = selSp != null ? spell(selSp) : null;
        selSpell = sp; sel = sp != null ? sp.bench.byId(selId) : bench.byId(selId);
        if (sel == null) selSpell = null;
        markEdit(); saveCfg();
    }
    void benchUndo() {
        if (bUndo.isEmpty()) { toast("nothing to undo"); return; }
        bRedo.push(benchSnap());
        restoreBench(bUndo.pop());
        lastOpTag = "";
        toast("undo");
    }
    void benchRedo() {
        if (bRedo.isEmpty()) { toast("nothing to redo"); return; }
        bUndo.push(benchSnap());
        restoreBench(bRedo.pop());
        lastOpTag = "";
        toast("redo");
    }
    /** Mouse-press bookkeeping for a drag: the undo snapshot is taken now, committed on the first change. */
    void grabUndo() { if (benchOn) pendingBench = benchSnap(); else pendingSnap = snapshot(); }

    // ---- bench UI: the layer rows take the timeline's place
    static final String[] BENCH_ACTIONS = {"open", "save as", "new spell", "panel", "browser", "timeline"};
    String[] actions() { return benchOn ? BENCH_ACTIONS : ACTIONS; }
    int benchRowH() { return 26; }
    int benchRowsY() { return rulerY() + 26; }
    int benchRowsN() { return Math.max(1, (panelY() - 8 - benchRowsY()) / benchRowH()); }
    Rectangle benchRowRect(int i) { return new Rectangle(8, benchRowsY() + (i - benchScroll) * benchRowH(), getWidth() - 20, benchRowH() - 2); }
    Rectangle levelRect(int i) { Rectangle r = benchRowRect(i); return new Rectangle(r.x + r.width - 400, r.y + 8, 110, 9); }

    void toggleBench() {
        benchOn = !benchOn;
        playing = false; benchPlaying = false; solo = null; mix.solo = null; sel = null;
        dragMode = DR_NONE;
        saveCfg();
        toast(benchOn ? "bench: layers sound together; signals drive them — J opens the regulator panel, H returns to the timeline"
                      : "timeline");
    }
    void toggleBenchPlay() {
        if (!benchPlaying) { seekTo = 0; benchPlaying = true; }
        else benchPlaying = false;
    }
    void selectLayer(int d) {
        java.util.List<BenchRow> rows = benchRows();
        if (rows.isEmpty()) return;
        int i = -1;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k).clip() == sel && sel != null) { i = k; break; }
        do { i = Math.max(0, Math.min(rows.size() - 1, i + (d == 0 ? 1 : d))); } while (rows.get(i).kind() == ROW_SPELL && i > 0 && i < rows.size() - 1);
        if (rows.get(i).kind() == ROW_SPELL) return;
        sel = rows.get(i).clip(); selSpell = rows.get(i).spell();
        if (i < benchScroll) benchScroll = i;
        if (i >= benchScroll + benchRowsN()) benchScroll = i - benchRowsN() + 1;
    }
    /** A palette layer's live level (its own), or where the blend currently sits for a spell's layer of the same id. */
    Clip liveRef(Clip c, Spell sp) { if (sp == null) return c; Clip pal = bench.byId(c.id); return pal != null && pal.type == c.type ? pal : c; }

    void paintBench(Graphics2D g, int w) {
        int y0 = rulerY();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.setColor(new Color(245, 235, 215));
        int n = bench.layers.size();
        java.util.List<BenchRow> rows = benchRows();
        g.drawString(String.format(Locale.ROOT, "BENCH  %s   %d layer%s   %s   score %.2f → blend %.0f%%   family %s",
                benchName != null ? benchName + (benchDirty ? " *" : "") : "(scratch bench)", n, n == 1 ? "" : "s", benchPlaying ? "▶" : "‖",
                sigVal[SIG_SCORE], 100 * blendW(), family != null ? family + " (" + mix.spells.size() + " spell" + (mix.spells.size() == 1 ? "" : "s") + ", autosaves)" : "none — S saves the bench as one"), tlX() - 40, y0 + 14);
        g.setColor(new Color(60, 60, 60));
        g.drawLine(8, y0 + 20, w - 12, y0 + 20);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        if (rows.isEmpty()) {
            g.setColor(Color.GRAY);
            g.drawString("no layers yet — 1-9/0 add a synth layer, W imports a recording, A opens the sample browser (its adds land here),", 20, benchRowsY() + 20);
            g.drawString("shift+H sends the selected timeline clip over. Every layer sounds at once while the bench plays (SPACE).", 20, benchRowsY() + 38);
            return;
        }
        int vis = benchRowsN(), total = rows.size();
        benchScroll = Math.max(0, Math.min(benchScroll, Math.max(0, total - vis)));
        Shape clip0 = g.getClip();
        g.clipRect(0, benchRowsY() - 2, w, vis * benchRowH() + 4);
        for (int i = benchScroll; i < Math.min(total, benchScroll + vis); i++) {
            BenchRow row = rows.get(i);
            Rectangle r = benchRowRect(i);
            if (row.kind() == ROW_SPELL) {   // a spell's group header: click folds it
                Spell sp = row.spell();
                g.setColor(new Color(28, 24, 18));
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setColor(new Color(255, 214, 110));
                g.drawString((collapsed.contains(sp.id) ? "▸ " : "▾ ") + "spell " + sp.id, r.x + 8, r.y + 17);
                g.setColor(new Color(170, 150, 110));
                g.drawString(String.format(Locale.ROOT, "%-22s score %.2f → blend %3.0f%%   %d layer%s%s%s", sp.name.length() > 22 ? sp.name.substring(0, 21) + "…" : sp.name,
                        spellScore.getOrDefault(sp.id, 0.0), 100 * sp.w, sp.bench.layers.size(), sp.bench.layers.size() == 1 ? "" : "s",
                        sp.recipe == null ? "   no recipe" : "", "") + (blendBind(sp) != null ? "   blend: " + blendBind(sp).sig + " " + (blendBind(sp).auto() ? "0.55..1" : fmtNum5(blendBind(sp).lo) + ".." + fmtNum5(blendBind(sp).hi)) + (blendBind(sp).map().isEmpty() ? "" : " " + blendBind(sp).map()) : ""), r.x + 8 + 27 * 7, r.y + 17);
                continue;
            }
            Clip c = row.clip(); Spell sp = row.spell();
            boolean spellRow = row.kind() == ROW_SPELL_LAYER;
            int x0 = r.x + (spellRow ? 20 : 0);
            boolean isSel = c == sel, muted = mix.solo != null ? mix.solo != c : c.lmute;
            g.setColor(isSel ? new Color(42, 42, 42) : spellRow ? (i % 2 == 0 ? new Color(20, 18, 14) : new Color(26, 23, 18)) : i % 2 == 0 ? new Color(16, 16, 16) : new Color(23, 23, 23));
            g.fillRect(r.x, r.y, r.width, r.height);
            // mute box (and solo, palette rows only)
            g.setColor(c.lmute ? new Color(180, 60, 60) : new Color(60, 60, 60));
            if (c.lmute) g.fillRect(x0 + 4, r.y + 5, 14, 14); else g.drawRect(x0 + 4, r.y + 5, 14, 14);
            g.setColor(c.lmute ? Color.WHITE : Color.GRAY);
            g.drawString("M", x0 + 7, r.y + 17);
            {
                boolean so = mix.solo == c;
                g.setColor(so ? new Color(90, 200, 160) : new Color(60, 60, 60));
                if (so) g.fillRect(x0 + 24, r.y + 5, 14, 14); else g.drawRect(x0 + 24, r.y + 5, 14, 14);
                g.setColor(so ? Color.BLACK : Color.GRAY);
                g.drawString("S", x0 + 28, r.y + 17);
            }
            Color tc = TYPE_COLORS[c.type];
            g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), muted ? 90 : 220));
            g.fillRect(x0 + 46, r.y + 5, 6, 14);
            g.setColor(isSel ? Color.WHITE : muted ? Color.GRAY : Color.LIGHT_GRAY);
            g.drawString(String.format(Locale.ROOT, "%-14s", c.id.length() > 14 ? c.id.substring(0, 14) : c.id), x0 + 60, r.y + 17);
            g.setColor(muted ? new Color(90, 90, 90) : Color.GRAY);
            String nm = c.name.length() > 28 ? c.name.substring(0, 27) + "…" : c.name;
            g.drawString(String.format(Locale.ROOT, "%-29s %-8s", nm, TYPE_NAMES[c.type]), x0 + 60 + 15 * 7, r.y + 17);
            // level bar: the layer's own (target) level, and a white tick where the sound is right now
            Rectangle lr = levelRect(i);
            g.setColor(new Color(70, 70, 70));
            g.drawRect(lr.x, lr.y, lr.width, lr.height);
            double lmax = spec(c.type, P_LEVEL).max();
            g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), muted ? 60 : 170));
            g.fillRect(lr.x + 1, lr.y + 1, (int) (Math.min(1, c.p[P_LEVEL] / lmax) * (lr.width - 1)), lr.height - 1);
            Clip ref = liveRef(c, sp);
            double[] m = ref.mod;
            if (m != null && benchPlaying && !muted) {
                int lx = lr.x + 1 + (int) (Math.max(0, Math.min(1, (ref.p[P_LEVEL] + m[P_LEVEL]) / lmax)) * (lr.width - 2));
                g.setColor(Color.WHITE);
                g.drawLine(lx, lr.y - 2, lx, lr.y + lr.height + 2);
            }
            g.setColor(Color.GRAY);
            g.drawString(spellRow ? "target" : "level", lr.x - 46, r.y + 17);
            // right: what the layer is and what moves it
            String tag = c.on != ON_NONE ? String.format(Locale.ROOT, "on %s %.2fs", ON_NAMES[c.on], c.dur) : "endless";
            g.setColor(c.on != ON_NONE ? new Color(255, 200, 120) : new Color(120, 120, 120));
            g.drawString(tag, lr.x + lr.width + 14, r.y + 17);
            g.setColor(new Color(120, 120, 120));
            if (spellRow) g.drawString(bench.byId(c.id) != null ? "shared with the palette" : "this spell only", lr.x + lr.width + 14 + 16 * 7, r.y + 17);
            else {
                int nb = 0, nr = c.range != null ? c.range.size() : 0;
                for (Bind b : bench.binds) if (b.layer.equals("*") || b.layer.equals(c.id)) nb++;
                g.drawString((nb > 0 ? nb + " bind" + (nb > 1 ? "s" : "") : "") + (nr > 0 ? (nb > 0 ? " · " : "") + nr + " range" + (nr > 1 ? "s" : "") : ""),
                        lr.x + lr.width + 14 + 16 * 7, r.y + 17);
            }
        }
        g.setClip(clip0);
        if (total > vis) {
            g.setColor(Color.GRAY);
            g.drawString(String.format(Locale.ROOT, "↕ %d-%d of %d (wheel scrolls)", benchScroll + 1, Math.min(total, benchScroll + vis), total), w - 12 - 30 * 7, y0 + 14);
        }
    }

    void benchClick(MouseEvent e) {
        int mx = e.getX(), my = e.getY();
        if (my < benchRowsY()) return;
        java.util.List<BenchRow> rows = benchRows();
        int row = (my - benchRowsY()) / benchRowH() + benchScroll;
        if (row < 0 || row >= rows.size() || row - benchScroll >= benchRowsN()) { if (!SwingUtilities.isRightMouseButton(e)) { sel = null; selSpell = null; } return; }
        BenchRow br = rows.get(row);
        Rectangle r = benchRowRect(row);
        if (br.kind() == ROW_SPELL) {
            if (SwingUtilities.isRightMouseButton(e)) { spellMenu(br.spell(), mx, my); return; }
            if (!collapsed.remove(br.spell().id)) collapsed.add(br.spell().id);
            return;
        }
        Clip c = br.clip(); Spell sp = br.spell();
        int x0 = r.x + (sp != null ? 20 : 0);
        if (SwingUtilities.isRightMouseButton(e)) { sel = c; selSpell = sp; if (sp != null) spellLayerMenu(c, sp, mx, my); else layerMenu(c, mx, my); return; }
        if (mx >= x0 + 4 && mx < x0 + 18) { if (sp == null) { pushUndo(""); c.lmute = !c.lmute; markEdit(); } else { pushUndo(""); c.lmute = !c.lmute; markSpellDirty(sp); } return; }
        if (mx >= x0 + 24 && mx < x0 + 38) { toggleSolo(c, sp); return; }
        sel = c; selSpell = sp;
        Rectangle lr = levelRect(row);
        if (new Rectangle(lr.x - 2, lr.y - 5, lr.width + 4, lr.height + 10).contains(mx, my)) {
            grabUndo(); dragMode = DR_LEVEL; setLevel(mx); return;
        }
        if (e.getClickCount() >= 2 && sp == null) renameLayer(c);
    }
    /** The spell group's menu: fold, recipe, and bringing palette layers in. */
    void spellMenu(Spell sp, int mx, int my) {
        JPopupMenu m = new JPopupMenu();
        m.add(item(collapsed.contains(sp.id) ? "expand" : "fold", () -> { if (!collapsed.remove(sp.id)) collapsed.add(sp.id); }));
        m.add(item("recipe…  (" + recipeText(sp.bench) + ")", () -> recipeDialog(sp, null)));
        m.add(item("lock  (score → 1, fires its on=lock one-shots)", () -> { spellScore.put(sp.id, 1.0); fireSpell(sp.id, ON_LOCK); if (bpanel != null) bpanel.pull(); }));
        m.addSeparator();
        JMenu add = new JMenu("add a palette layer at its current values");
        for (Clip pc : bench.layers) add.add(item(pc.id + (sp.bench.byId(pc.id) != null ? "  (replace)" : ""), () -> addLayerToSpell(pc, sp)));
        m.add(add);
        m.addSeparator();
        m.add(item("delete spell " + sp.id + "…", () -> deleteSpell(sp)));
        m.show(this, mx, my);
    }
    /** Copies a palette layer into a spell as its target values (replacing the spell's layer of that id). */
    void addLayerToSpell(Clip pc, Spell sp) {
        pushUndo("");
        Clip n = copyClip(pc); n.lmute = false; n.range = null; n.rnote = null;
        Clip old = sp.bench.byId(pc.id);
        if (old != null) sp.bench.layers.set(sp.bench.layers.indexOf(old), n); else sp.bench.layers.add(n);
        markSpellDirty(sp);
        toast(pc.id + " → " + sp.id + " at its current values");
    }
    void spellLayerMenu(Clip c, Spell sp, int mx, int my) {
        JPopupMenu m = new JPopupMenu();
        Clip pal = bench.byId(c.id);
        if (pal != null && pal.type == c.type) m.add(item("take the palette's current values", () -> { pushUndo(""); System.arraycopy(pal.p, 0, c.p, 0, c.p.length); markSpellDirty(sp); }));
        m.addSeparator();
        for (int on = 0; on < ON_NAMES.length; on++) {
            final int o = on;
            JCheckBoxMenuItem it = new JCheckBoxMenuItem(on == ON_NONE ? "endless layer (a bed)" : "one-shot, fires on " + ON_NAMES[on], c.on == on);
            it.addActionListener(e -> { if (c.on != o) { pushUndo(""); c.on = o; c.dur = o == ON_NONE ? ENDLESS : (c.dur >= ENDLESS / 2 ? naturalDur(c) : c.dur); markSpellDirty(sp); } });
            m.add(it);
        }
        m.addSeparator();
        m.add(item("remove from " + sp.id + "  (DEL)", () -> removeSpellLayer(c, sp)));
        m.show(this, mx, my);
    }
    void removeSpellLayer(Clip c, Spell sp) {
        pushUndo("");
        sp.bench.layers.remove(c);
        if (sel == c) { sel = null; selSpell = null; }
        if (mix.solo == c) { mix.solo = null; mix.soloSpell = null; }
        markSpellDirty(sp);
    }
    void setLevel(int mx) {
        if (sel == null) return;
        java.util.List<BenchRow> rows = benchRows();
        int row = -1;
        for (int k = 0; k < rows.size(); k++) if (rows.get(k).clip() == sel) { row = k; break; }
        if (row < 0) return;
        Rectangle lr = levelRect(row);
        double u = Math.max(0, Math.min(1, (mx - lr.x) / (double) lr.width));
        double v = Math.round(u * spec(sel.type, P_LEVEL).max() * 200) / 200.0;
        if (sel.p[P_LEVEL] == v) return;
        commitPending();
        sel.p[P_LEVEL] = v;
        markEdit();
    }
    static JMenuItem item(String label, Runnable r) { JMenuItem m = new JMenuItem(label); m.addActionListener(e -> r.run()); return m; }
    void layerMenu(Clip c, int mx, int my) {
        JPopupMenu m = new JPopupMenu();
        m.add(item("rename id…  (" + c.id + ")", () -> renameLayer(c)));
        m.addSeparator();
        for (int on = 0; on < ON_NAMES.length; on++) {
            final int o = on;
            JCheckBoxMenuItem it = new JCheckBoxMenuItem(on == ON_NONE ? "endless layer (a bed)" : "one-shot, fires on " + ON_NAMES[on] + (on == ON_ACCEPT ? " (the crystal takes a latched motion: the chime)" : ""), c.on == on);
            it.addActionListener(e -> { if (c.on != o) setLayerOn(c, o); });
            m.add(it);
        }
        if (c.on != ON_NONE) {
            m.add(item(String.format(Locale.ROOT, "one-shot length…  (%.2fs)", c.dur), () -> {
                String in = (String) JOptionPane.showInputDialog(this, "Seconds this one-shot plays:", "Length", JOptionPane.PLAIN_MESSAGE, null, null, String.format(Locale.ROOT, "%.2f", c.dur));
                if (in == null) return;
                try { double d = Double.parseDouble(in.trim()); if (d > 0.01) { pushUndo(""); c.dur = d; markEdit(); } } catch (NumberFormatException ex) { toast("couldn't parse \"" + in + "\""); }
            }));
            m.add(item("fire it now  (P)", () -> fire(c)));
        }
        m.addSeparator();
        m.add(item("duplicate  (D)", () -> { sel = c; dupSel(); }));
        m.add(item("copy to the timeline at the playhead", () -> layerToTimeline(c)));
        if (c.type == SAMPLE || c.type == CHOIR || c.type == PARTIALS) {
            String pick = browserOn && browser != null ? browser.sel() : null;
            if (pick != null && !pick.equals(c.file)) m.add(item("swap source with " + pick + "  (the browser's pick)", () -> swapSource(c, pick)));
            m.add(item("swap source\u2026  (then dbl-click / ENTER a sample in the browser)", () -> {
                swapTarget = c;
                showBrowser(true);
                toast("pick a recording for " + c.id + " in the browser: dbl-click / ENTER swaps it in, keeping the id, values and binds (ESC cancels)");
            }));
        }
        if (!mix.spells.isEmpty()) {
            JMenu add = new JMenu("add to a spell at these values");
            for (Spell sp : mix.spells) add.add(item(sp.id + (sp.bench.byId(c.id) != null ? "  (replace)" : ""), () -> addLayerToSpell(c, sp)));
            m.add(add);
        }
        m.add(item("remove  (DEL)", () -> removeLayer(c)));
        m.show(this, mx, my);
    }
    /** Right-click on a slider in bench mode: value, range notes and binds for that param. */
    void sliderMenu(int i, int mx, int my) {
        Clip c = sel;
        if (c == null) return;
        PSpec s = spec(c.type, i);
        String pname = key(c.type, i);
        double[] r = c.range != null ? c.range.get(i) : null;
        JPopupMenu m = new JPopupMenu();
        m.add(item("type value…", () -> typeParam(i)));
        m.addSeparator();
        m.add(item(String.format(Locale.ROOT, "range low = here (%s)%s", fmtVal(c, i), r != null ? "   now " + fmtNum5(r[0]) : ""), () -> setRange(c, i, c.p[i], Double.NaN)));
        m.add(item(String.format(Locale.ROOT, "range high = here (%s)%s", fmtVal(c, i), r != null ? "   now " + fmtNum5(r[1]) : ""), () -> setRange(c, i, Double.NaN, c.p[i])));
        m.add(item("type range…", () -> {
            String in = (String) JOptionPane.showInputDialog(this, s.name() + " range that sounds good: low high  (spec " + fmtNum(s.min()) + " .. " + fmtNum(s.max()) + ")",
                    "Range", JOptionPane.PLAIN_MESSAGE, null, null, r != null ? fmtNum5(r[0]) + " " + fmtNum5(r[1]) : fmtNum(s.min()) + " " + fmtNum(s.max()));
            if (in == null) return;
            String[] t = in.trim().split("[\\s,]+");
            try { setRange(c, i, Double.parseDouble(t[0]), Double.parseDouble(t[t.length > 1 ? 1 : 0])); }
            catch (Exception ex) { toast("couldn't parse \"" + in + "\" — type two numbers"); }
        }));
        m.add(item("note…" + (c.rnote != null && c.rnote.get(i) != null ? "  (" + c.rnote.get(i) + ")" : ""), () -> {
            String in = (String) JOptionPane.showInputDialog(this, "Note on " + c.id + "." + s.name() + " (what this range does, what to avoid):", "Note", JOptionPane.PLAIN_MESSAGE, null, null,
                    c.rnote != null && c.rnote.get(i) != null ? c.rnote.get(i) : "");
            if (in == null) return;
            pushUndo("");
            if (c.range == null || !c.range.containsKey(i)) setRange(c, i, s.min(), s.max());
            if (c.rnote == null) c.rnote = new HashMap<>();
            if (in.isBlank()) c.rnote.remove(i); else c.rnote.put(i, in.trim());
            benchGen++; markEdit();
        }));
        if (r != null) m.add(item("clear range", () -> clearRange(c, i)));
        m.addSeparator();
        JMenu bm = new JMenu("bind a signal to " + c.id + "." + s.name());
        for (String sg : signalChoices()) bm.add(item(sg, () -> addBind(sg, c.id, pname)));
        m.add(bm);
        JMenu bAll = new JMenu("bind a signal to every layer's " + s.name());
        for (String sg : signalChoices()) bAll.add(item(sg, () -> addBind(sg, "*", pname)));
        m.add(bAll);
        for (Bind b : bindsOn(c, i)) m.add(item("remove bind " + b.sig + " → " + b.layer + "." + b.param, () -> removeBind(b)));
        m.show(this, mx, my);
    }

    void showBenchPanel(boolean on) {
        if (frame == null) return;
        if (bpanel == null) bpanel = new BenchPanel(this);
        if (on == bpanelOn && on == (bpanel.getParent() != null)) return;
        dock(bpanel, on);
        bpanelOn = on;
        saveCfg();
        if (!on) requestFocusInWindow();
    }
    void toggleBenchPanel() { showBenchPanel(!bpanelOn); }

    // ---- the machine window (U): the regulator as the game plays it (sfxlab.runtime.ConductedMachine), driving the bench
    Machine machine;
    void showMachine() {
        if (machine == null) machine = new Machine(this);
        machine.open();
    }

    /** The regulator machine, conducted: the same {@link ConductedMachine} the game runs, worked on the stage with
     *  the mouse the way the game's three casts work it. Hold the left button for crescendo and the right for
     *  diminuendo on what the pointer is over when the button goes down (the thread stays on it until the button
     *  comes up); the middle button, or T, is the toggle.
     *  <pre>
     *    the aiming arm's crystal   crescendo / diminuendo: the reach of the component in hand
     *    the composite figure       crescendo / diminuendo: the aim, and so the ratio;  toggle: plant the component
     *    the component in hand      crescendo / diminuendo: its phase
     *    a planted component        toggle: take it back in hand
     *    the seated crystal         toggle: the next arm aims;  crescendo while a sigil holds: voice it
     *  </pre>
     *  While "drive the bench" is on and the receiver is powered, the core's signals replace the panel's sliders
     *  every frame and its events fire the bench's one-shots; unpowered or closed, the panel's own values come
     *  back and a solo (P) is the authored layer. */
    static class Machine extends JPanel {
        final SfxLab lab;
        ConductedMachine cm;
        RegulatorCore core;                 // cm.core
        RegulatorCore.Recipe pinned;        // the blueprint shown, and what auto-play goes for; the machine itself is free
        int seenFamily = -1;
        final JPanel tg = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        final ButtonGroup tgGroup = new ButtonGroup();
        final Autopilot auto = new Autopilot(System.nanoTime());
        final JToggleButton autoB = new JToggleButton("▶ auto-play"), pauseB = new JToggleButton("freeze");
        final JSlider speedS = new JSlider(5, 40, 10);
        final JCheckBox mistakesB = new JCheckBox("mistakes", true), anyB = new JCheckBox("any spell", false);
        final JSlider snapS = new JSlider(2, 30, 10), periodS = new JSlider(10, 80, (int) Math.round(RegulatorCore.DRAW_PERIOD * 10));
        final JLabel snapL = new JLabel(), periodL = new JLabel();
        JFrame frame;
        final Stage stage = new Stage();
        final JToggleButton power = new JToggleButton("Seat crystal");
        final JCheckBox driveB = new JCheckBox("drive the bench", true), valsB = new JCheckBox("show values");
        final JComboBox<String> viewBox = new JComboBox<>(new String[]{"orbit", "front", "top"});
        final java.util.List<JToggleButton> targetB = new ArrayList<>();
        final JTextArea status = new JTextArea(2, 30);
        final JTextArea vals = new JTextArea(8, 30);
        String flash; long flashUntil;
        long lastNs; double flashV; int frameNo, voicedCount;
        double yaw, elev = 0.5, dYaw, dElev, ext = 1, turn;

        // ---- the hand: what the thread is on
        enum On { ARM, COMPOSITE, COMPONENT, CRYSTAL }
        record Pick(On on, int axis, int slot, double x, double y, double r, boolean selected) {}
        final java.util.List<Pick> picks = new ArrayList<>();
        Pick thread; boolean threadRising, threadVoiced; double threadHeld;
        final Point mouse = new Point(-1, -1);

        Machine(SfxLab lab) {
            this.lab = lab;
            setLayout(new BorderLayout(6, 6));
            setBackground(Color.BLACK);
            add(stage, BorderLayout.CENTER);
            JPanel ctl = new JPanel(); ctl.setLayout(new BoxLayout(ctl, BoxLayout.Y_AXIS));
            ctl.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            rebuildCore();
            ctl.add(tg);
            JPanel pw = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            power.addActionListener(e -> {
                core.power(power.isSelected());
                if (!power.isSelected()) { endThread(); cm.clear(); }
                if (driveB.isSelected() && lab.benchOn) { if (power.isSelected()) { if (!lab.benchPlaying) lab.toggleBenchPlay(); } else lab.stopBench(); }
                power.setText(power.isSelected() ? "Take crystal" : "Seat crystal");
            });
            pw.add(power); pw.add(new JLabel("view")); pw.add(viewBox);
            ctl.add(pw);
            ctl.add(section("the casts — on the stage, as in the game"));
            JTextArea help = new JTextArea(
                    "hold LEFT  = crescendo     hold RIGHT = diminuendo\n"
                  + "MIDDLE click, or T  = toggle\n\n"
                  + "aiming arm's crystal   cresc / dim: reach\n"
                  + "composite figure       cresc / dim: the aim (ratio)\n"
                  + "                       toggle: plant\n"
                  + "component in hand      cresc / dim: phase\n"
                  + "planted component      toggle: take it back\n"
                  + "seated crystal         toggle: next arm aims\n"
                  + "                       cresc at a lock: voice");
            help.setFont(mono); help.setEditable(false); help.setOpaque(false); help.setFocusable(false);
            help.setMaximumSize(new Dimension(380, 170));
            ctl.add(help);
            JPanel cmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            snapS.setPreferredSize(new Dimension(110, 20));
            snapS.setToolTipText("the acceptance window, the same at every ratio: let go inside it and the crystal takes the motion onto the integer. The difficulty scaler");
            snapS.addChangeListener(e -> { core.snapTol = snapS.getValue() / 100.0; snapLabel(); });
            cmRow.add(new JLabel("acceptance")); cmRow.add(snapS); cmRow.add(snapL);
            ctl.add(cmRow);
            JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            periodS.setPreferredSize(new Dimension(110, 20));
            periodS.setToolTipText("the receiver's period: how long the figure takes to go round once. The tempo of everything; the ratios, the wells and the levels of the signals do not change with it");
            periodS.addChangeListener(e -> { core.setDrawPeriod(periodS.getValue() / 10.0); snapLabel(); });
            pr.add(new JLabel("period")); pr.add(periodS); pr.add(periodL);
            ctl.add(pr);
            snapLabel();
            ctl.add(section("the sigil"));
            JPanel rs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            JButton recipeB = new JButton("→ recipe");
            recipeB.setToolTipText("write the components on the machine (rounded to their stations) as the pinned spell's recipe");
            recipeB.addActionListener(e -> captureRecipe());
            JButton clearB = new JButton("clear");
            clearB.setToolTipText("a fresh machine: every component gone, the aim at rest");
            clearB.addActionListener(e -> { endThread(); cm.clear(); });
            rs.add(recipeB); rs.add(clearB); rs.add(driveB); rs.add(valsB);
            ctl.add(rs);
            ctl.add(section("auto-play — a player conducts toward the pinned spell"));
            JPanel ap = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            autoB.addActionListener(e -> { if (autoB.isSelected()) { if (!power.isSelected()) power.doClick(); auto.start(); } else { auto.stop(); } });
            pauseB.setToolTipText("stop the machine's time: the figure and auto-play hold still, the signals stay put, and the layers can be tuned against this exact moment");
            pauseB.addActionListener(e -> auto.paused = pauseB.isSelected());
            speedS.setPreferredSize(new Dimension(90, 20)); speedS.setToolTipText("the player's hands, ×0.5 .. ×4");
            speedS.addChangeListener(e -> auto.speed = speedS.getValue() / 10.0);
            mistakesB.setToolTipText("meander now and then: settle on the next station first, hear it, and come back");
            anyB.setToolTipText("after each voicing, pin a random spell of the family and go for that one");
            mistakesB.addActionListener(e -> auto.mistakes = mistakesB.isSelected());
            anyB.addActionListener(e -> auto.anySpell = anyB.isSelected());
            ap.add(autoB); ap.add(pauseB); ap.add(new JLabel("speed")); ap.add(speedS);
            ctl.add(ap);
            JPanel ap2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            ap2.add(mistakesB); ap2.add(anyB);
            ctl.add(ap2);
            status.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
            status.setEditable(false); status.setLineWrap(true); status.setWrapStyleWord(true); status.setOpaque(false);
            status.setMaximumSize(new Dimension(380, 40));
            ctl.add(status);
            vals.setFont(mono); vals.setEditable(false);
            JScrollPane vs = new JScrollPane(vals); vs.setPreferredSize(new Dimension(360, 220));
            ctl.add(vs);
            ctl.add(Box.createVerticalGlue());
            for (Component k : ctl.getComponents()) if (k instanceof JComponent jc) jc.setAlignmentX(LEFT_ALIGNMENT);
            tg.setLayout(new GridLayout(0, 2, 4, 2));   // the blueprints two to a row: the panel stays narrow and the stage gets the window
            ctl.setPreferredSize(new Dimension(400, 700));
            add(ctl, BorderLayout.EAST);
            new javax.swing.Timer(33, e -> { if (frame != null) frameTick(); }).start();   // only once it is a window: a headless test steps it itself
        }
        /** The components on the machine as recipe text: integer ratios, phases, and the reaches as targets. */
        String currentSigil() {
            java.util.List<RegulatorCore.Eng> eng = core.engaged();
            StringBuilder sb = new StringBuilder("tier=" + (pinned != null ? pinned.tier : 1));
            if (pinned != null && pinned.secret) sb.append(" secret=1");
            for (RegulatorCore.Eng e : eng) {
                double amp = Math.max(0.05, Math.min(1, Math.round(e.amp() * 20) / 20.0));   // the reach target, to 0.05
                sb.append(' ').append("XYZ".charAt(e.axis())).append((int) Math.max(1, Math.round(e.r()))).append('p').append(e.ph()).append(amp != 1 ? "r" + fmtNum5(amp) : "");
            }
            return sb.toString();
        }
        void captureRecipe() {
            if (pinned == null) return;
            Spell sp = lab.spell(pinned.id);
            if (sp == null) { lab.toast("the pinned blueprint is not a spell of the loaded family (pick the family in the panel, J)"); return; }
            if (core.engaged().isEmpty()) { lab.toast("nothing on the machine — build the components first"); return; }
            String text = currentSigil();
            Bench r = parseRecipe(text);
            RegulatorCore.Recipe probe = new RegulatorCore.Recipe(sp.id, sp.name, r.tier, "", r.secret, r.comps);
            String prob = recipeProblem(r);
            String warn = prob != null ? "\n\nWARNING, unbuildable at this tier: " + prob : RegulatorCore.degenerate(probe) ? "\n\nWARNING: this trace retraces itself into an open line; it will read poorly as a sigil." : "";
            if (JOptionPane.showConfirmDialog(this, "Make this the recipe of " + sp.name + "?\n\n" + text + warn + "\n\n(the file keeps its layers; the machine scores the new sigil from now on)",
                    "Recipe", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            lab.setSpellRecipe(sp, text);
        }
        void snapLabel() {
            snapL.setText(String.format(Locale.ROOT, "±%.2f of a ratio", core.acceptWindow(1)));
            periodL.setText(String.format(Locale.ROOT, "%.1f s", core.drawPeriod));
        }
        static JLabel section(String t) { JLabel l = new JLabel(t); l.setForeground(Color.GRAY); l.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2)); return l; }
        void open() {
            if (frame == null) {
                frame = new JFrame("Harmonic Regulator — the machine");
                frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                frame.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closed(); } });
                frame.add(this);
                frame.setSize(1180, 860);
                frame.setLocationByPlatform(true);
            }
            frame.setVisible(true); frame.toFront();
        }
        /** The window is closed: the session is over. Auto-play stops, the crystal comes out (the machine is cleared),
         *  the bench stops playing and the panel's sliders own the signals again, so reopening starts clean. */
        void closed() {
            auto.stop(); autoB.setSelected(false);
            auto.paused = false; pauseB.setSelected(false);
            endThread();
            if (power.isSelected()) power.doClick();
            if (lab.benchPlaying) lab.stopBench();
            lab.setSigDriven(false);
            lastNs = 0;
        }
        void notify(String m) { notify(m, 3.5); }
        void notify(String m, double sec) { flash = m; flashUntil = System.currentTimeMillis() + (long) (sec * 1000); }
        void pin(RegulatorCore.Recipe r) {
            pinned = r;
            for (JToggleButton b : targetB) if (b.isSelected() != (b.getClientProperty("recipe") == r)) b.setSelected(b.getClientProperty("recipe") == r);
            stage.repaint();
        }
        /** The machine follows the loaded family's roster (spell files with a recipe line); the blueprint buttons follow it. */
        void rebuildCore() {
            seenFamily = lab.familyGen;
            boolean wasOn = core != null && core.powered;
            endThread();
            cm = new ConductedMachine(lab.familyRecipes(), RegulatorCore.ARMS, RegulatorCore.AXES);
            core = cm.core;
            core.power(wasOn);
            core.snapTol = snapS.getValue() / 100.0;
            core.setDrawPeriod(periodS.getValue() / 10.0);
            auto.stop(); autoB.setSelected(false);
            for (JToggleButton b : targetB) tgGroup.remove(b);
            targetB.clear(); tg.removeAll();
            pinned = null;
            for (RegulatorCore.Recipe r : core.recipes) {
                if (r.secret) continue;
                JToggleButton b = new JToggleButton(r.name + " " + new String[]{"", "I", "II", "III"}[r.tier]);
                b.putClientProperty("recipe", r);
                b.addActionListener(e -> pin(r));
                tgGroup.add(b); tg.add(b); targetB.add(b);
                if (pinned == null) pinned = r;
            }
            if (!targetB.isEmpty()) targetB.get(0).setSelected(true);
            tg.revalidate(); tg.repaint();
        }

        // ---- the casts
        /** A channel begins on what the pointer is over: the thread stays there until {@link #endThread}. */
        void beginThread(Pick p, boolean rising) {
            endThread();
            if (p == null || !core.powered || auto.on) return;
            if (p.on() == On.COMPOSITE && !cm.takeSelected()) return;
            thread = p; threadRising = rising; threadHeld = 0; threadVoiced = false;
        }
        void endThread() {
            if (thread != null) {
                if (thread.on() == On.COMPOSITE) cm.letGo();
                if (thread.on() == On.COMPONENT) cm.phaseLetGo();
            }
            thread = null;
        }
        /** A frame of the channel in hand, on the verbs' shipped curves. */
        void cast(double dt) {
            if (thread == null) return;
            threadHeld += dt;
            ConductedMachine.Verb v = threadRising ? ConductedMachine.CRESCENDO : ConductedMachine.DIMINUENDO;
            switch (thread.on()) {
                case COMPOSITE -> cm.push(v.aim(threadHeld, dt));
                case ARM -> cm.reach(v.reach(threadHeld, dt));
                case COMPONENT -> cm.phase(v.phase(threadHeld, dt));
                case CRYSTAL -> { if (threadRising && !threadVoiced && core.matched != null && threadHeld >= ConductedMachine.VOICE_SECONDS) { threadVoiced = true; voice(); } }
            }
        }
        /** One press of the toggle on what the pointer is over. */
        void toggle(Pick p) {
            if (!core.powered || auto.on) return;
            if (p == null) { notify("The toggle found nothing to press.", 1.5); return; }
            switch (p.on()) {
                case CRYSTAL -> { cm.nextAxis(); notify(RegulatorCore.AXIS[cm.selectedAxis()] + " aims.", 1.5); }
                case COMPOSITE -> notify(cm.plant() ? "Planted." : "Nothing in hand to plant: give the aiming arm's crystal some reach first.", 2.5);
                case COMPONENT -> { cm.select(p.axis(), p.slot()); notify("Taken back in hand.", 1.5); }
                default -> notify("The toggle does nothing there.", 1.5);
            }
        }
        /** Crescendo into the seated crystal while a sigil holds: the crystal is written and a fresh one seated. */
        boolean voice() {
            java.util.List<RegulatorCore.Snap> snap = core.voice();
            if (snap == null) return false;
            cm.clear();
            voicedCount++;
            flashV = 1;
            notify(core.lastVoiced.name + " voiced. A fresh crystal is in the socket.");
            return true;
        }
        /** What a press at this point lands on: for a channel the aiming arm's crystal, the composite, the component in
         *  hand or the seated crystal; for the toggle the seated crystal, the composite or a planted component. */
        Pick pickAt(Point at, boolean forToggle) {
            Pick best = null; double bestD = 1;
            for (Pick p : picks) {
                if (forToggle ? p.on() == On.ARM || (p.on() == On.COMPONENT && p.selected()) : p.on() == On.COMPONENT && !p.selected()) continue;
                double d = Math.hypot(at.x - p.x(), at.y - p.y()) / p.r();
                if (d < bestD) { bestD = d; best = p; }
            }
            return best;
        }

        void frameTick() {
            if (frame != null && !frame.isVisible()) { lastNs = 0; endThread(); lab.setSigDriven(false); return; }   // closed: nothing runs, the panel's sliders are free again
            long now = System.nanoTime();
            double dt = lastNs == 0 ? 1 / 60.0 : Math.min(0.05, (now - lastNs) / 1e9);
            lastNs = now;
            if (lab.familyGen != seenFamily) rebuildCore();
            step(dt);
        }
        /** One frame of machine time: the hand's cast (or the auto-player's), the machine, the hand-off to the bench. Tests call this with fixed dt. */
        void step(double dt) {
            // frozen: no machine time passes (the figure, auto-play), so the signals hold still and the layers can be
            // adjusted against exactly the state that was playing
            if (!auto.paused) {
                if (auto.on) auto.advance(dt * auto.speed); else cast(dt);
                cm.tick(dt);
            }
            flashV = Math.max(0, flashV - dt * 1.2);
            frameNo++;
            boolean driving = driveB.isSelected() && core.powered;   // an unpowered machine writes nothing: the signals are the panel's again
            lab.setSigDriven(driving);
            if (driving) {   // only while driving: with drive off the panel's sliders own the scores and signals
                for (RegulatorCore.Recipe r : core.recipes) lab.spellScore.put(r.id, core.eval.get(r.id).score);
                for (int i = 0; i < RegulatorCore.SIGNALS.length; i++) { int k = sigIdx(RegulatorCore.SIGNALS[i]); if (k >= 0) lab.sigVal[k] = core.signals[i]; }
                if (lab.bpanel != null && lab.bpanelOn && frameNo % 3 == 0) lab.bpanel.pull();   // 10 Hz is plenty for twelve sliders
            }
            for (String ev : core.events()) {
                if (ev.equals("lock")) { if (driving) lab.fireEvent(ON_LOCK, false, false); }
                else if (ev.equals("unlock")) { if (driving) lab.fireEvent(ON_UNLOCK, false, false); }
                else if (ev.startsWith("match:")) { if (driving) lab.fireSpell(ev.substring(6), ON_LOCK, false); }
                else if (ev.startsWith("unmatch:")) { if (driving) lab.fireSpell(ev.substring(8), ON_UNLOCK, false); }
                else if (ev.startsWith("discover:")) notify("Something answered that no blueprint shows: " + core.recipe(ev.substring(9)).name + ".");
                else if (ev.startsWith("accept:")) { if (driving) lab.fireEvent(ON_ACCEPT, false, false); notify("The crystal takes ×" + ev.substring(ev.lastIndexOf(':') + 1) + ".", 1.8); }
            }
            // status line
            String m;
            if (auto.paused) m = "FROZEN — the machine's time is stopped; tune the layers, then unfreeze." + (auto.on ? "   (auto: " + auto.doing + ")" : "");
            else if (auto.on) m = "auto: " + auto.doing;
            else if (System.currentTimeMillis() < flashUntil) m = flash;
            else if (!core.powered) m = "Seat a crystal to begin.";
            else if (core.matched != null) m = "The sigil holds: " + core.matched.name + ". Crescendo into the seated crystal writes it.";
            else if (thread != null) m = (threadRising ? "crescendo" : "diminuendo") + " on " + switch (thread.on()) { case ARM -> "the reach"; case COMPOSITE -> "the aim"; case COMPONENT -> "the phase"; case CRYSTAL -> "the seated crystal"; };
            else if (!cm.selectedExists()) m = RegulatorCore.AXIS[cm.selectedAxis()] + " aims. Crescendo on its crystal gives a component reach; on the composite, winds the aim.";
            else if (core.acceptable(cm.aim()) > 0 && cm.motion(cm.selectedAxis(), cm.selectedSlot()).drv) m = "Within reach of ×" + core.acceptable(cm.aim()) + ": the crystal is taking it.";
            else if (core.targetEval.score > 0.7) m = "Close. Watch the figure slow.";
            else m = " ";
            if (!status.getText().equals(m)) status.setText(m);
            if (valsB.isSelected() && frameNo % 4 == 0) {
                StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "aim ×%.3f   %s aims (slot %d)   reach %.2f%n", cm.aim(), RegulatorCore.AXIS[cm.selectedAxis()], cm.selectedSlot() + 1, cm.selectedReach()));
                for (int a = 0; a < RegulatorCore.AXES; a++) for (int s = 0; s < cm.slots(); s++) {
                    RegulatorCore.Motion mo = cm.motion(a, s);
                    if (mo.eng) sb.append(String.format(Locale.ROOT, "%s%s %d  ×%.3f  φ%.2f  reach %.2f%s%n", a == cm.selectedAxis() && s == cm.selectedSlot() ? "▸" : " ", RegulatorCore.AXIS[a], s + 1, mo.r, cm.phaseAngle(a, s), mo.amp, mo.drv ? "  (in hand)" : ""));
                }
                sb.append('\n');
                for (RegulatorCore.Recipe r : core.recipes) sb.append(String.format(Locale.ROOT, "%-14s %.3f%s%n", r.name, core.eval.get(r.id).score, core.eval.get(r.id).exact ? "  ✓" : ""));
                sb.append('\n');
                for (int i = 0; i < RegulatorCore.SIGNALS.length; i++) sb.append(String.format(Locale.ROOT, "%-12s %.3f%n", RegulatorCore.SIGNALS[i], core.signals[i]));
                for (int a = 0; a < 3; a++) sb.append(String.format(Locale.ROOT, "arm%d.pitch   %.2f st%n", a + 1, core.pitch(a)));
                if (!vals.getText().contentEquals(sb)) vals.setText(sb.toString());
            } else if (!valsB.isSelected() && !vals.getText().isEmpty()) vals.setText("");
            stage.repaint();
        }

        /** The auto-player: conducts the machine toward the pinned spell with the game's own casts, on the verbs' shipped
         *  curves (reach on the aiming arm, the aim wound past its station and damped back, the phase turned, planted),
         *  in a random order of components, so the soundscape can be listened to as it will be played. */
        class Autopilot {
            Random rng;
            boolean on, paused, mistakes = true, anySpell; double speed = 1;
            String doing = "";
            interface Move { boolean run(double dt); }   // true once it is done
            record Step(String what, Move move, double timeout) {}
            final ArrayDeque<Step> plan = new ArrayDeque<>();
            Step cur; double elapsed;
            Autopilot(long seed) { rng = new Random(seed); }
            void start() { on = true; paused = false; plan.clear(); cur = null; endThread(); cm.clear(); planTarget(); }
            void stop() { on = false; plan.clear(); cur = null; doing = ""; if (cm != null) { cm.letGo(); cm.phaseLetGo(); } }
            double between(double a, double b) { return a + rng.nextDouble() * (b - a); }
            Step wait(String what, double sec) { double[] t = {0}; return new Step(what, dt -> (t[0] += dt) >= sec, sec + 1); }
            Step once(String what, double pause, Runnable r) { double[] t = {0}; return new Step(what, dt -> { if ((t[0] += dt) < pause) return false; r.run(); return true; }, pause + 1); }
            void planTarget() {
                if (anySpell) {
                    ArrayList<RegulatorCore.Recipe> rs = new ArrayList<>();
                    for (RegulatorCore.Recipe r : core.recipes) if (!r.secret) rs.add(r);
                    if (!rs.isEmpty()) pin(rs.get(rng.nextInt(rs.size())));
                }
                RegulatorCore.Recipe rec = pinned;
                if (rec == null) { on = false; return; }
                java.util.List<RegulatorCore.Comp> comps = new ArrayList<>(Arrays.asList(rec.comps));
                Collections.shuffle(comps, rng);
                for (RegulatorCore.Comp c : comps) planComponent(c);
                plan.add(wait("holding the lock, listening", between(3, 6)));
                plan.add(once("crescendo into the crystal", 0.5, () -> { if (!voice()) cm.clear(); planTarget(); }));
            }
            void planComponent(RegulatorCore.Comp c) {
                String ax = RegulatorCore.AXIS[c.axis()];
                // the toggle at the socket until this component's arm aims
                double[] t = {0};
                plan.add(new Step("the toggle at the socket: " + ax + " aims", dt -> {
                    if ((t[0] += dt) < 0.45) return false;
                    if (cm.selectedAxis() == c.axis()) return true;
                    cm.nextAxis(); t[0] = 0;
                    return false;
                }, 8));
                // reach: crescendo past it, diminuendo back
                double want = c.amp(), over = Math.min(1, want + between(0.04, 0.2));
                double[] h = {0};
                plan.add(new Step("reach: crescendo on " + ax + "'s crystal", dt -> { h[0] += dt; cm.reach(ConductedMachine.CRESCENDO.reach(h[0], dt)); return cm.selectedReach() >= over - 1e-9; }, 8));
                double[] h2 = {0};
                plan.add(new Step(String.format(Locale.ROOT, "reach %.2f: diminuendo back, watching the orb", want), dt -> {
                    if (cm.selectedReach() <= want + 0.02) return true;
                    h2[0] += dt;
                    cm.reach(Math.max(want - cm.selectedReach(), ConductedMachine.DIMINUENDO.reach(h2[0], dt)));
                    return false;
                }, 8));
                // the aim: sometimes the next station first, heard and left
                if (mistakes && rng.nextDouble() < 0.3 && c.n() < 7) {
                    plan.add(new Step("winding the aim", aimTo(c.n() + 1), 30));
                    plan.add(wait("that's ×" + (c.n() + 1) + " — listening, then back", between(0.8, 2.2)));
                }
                plan.add(new Step("the aim to ×" + c.n() + ": past it, and back into its window", aimTo(c.n()), 30));
                // the phase, turned on the component's own figure
                double[] h3 = {0}; boolean[] let = {false};
                plan.add(new Step("phase: turning the component", dt -> {
                    double at = cm.phaseAngle(cm.selectedAxis(), cm.selectedSlot()), gap = ((c.phase() - at) % 4 + 4) % 4;
                    if (!let[0]) {
                        if (gap < 0.3 || gap > 3.7) { cm.phaseLetGo(); let[0] = true; return false; }
                        h3[0] += dt; cm.phase(ConductedMachine.CRESCENDO.phase(h3[0], dt));
                        return false;
                    }
                    return gap < 1e-6 || gap > 4 - 1e-6;
                }, 10));
                if (rng.nextDouble() < 0.5) plan.add(wait("listening", between(0.5, 1.5)));
                plan.add(once("the toggle on the composite: planted", between(0.3, 0.7), cm::plant));
            }
            /** The aim to station n the way a hand does it: crescendo past, diminuendo back into the window, let go, and
             *  wait for the crystal to take it; round again if it slipped through. */
            Move aimTo(int n) {
                int[] st = {0}; double[] h = {0};
                double over = mistakes ? between(0.12, 0.4) : 0.12;
                return dt -> {
                    double a = cm.aim(), w = core.acceptWindow(n);
                    RegulatorCore.Motion m = cm.motion(cm.selectedAxis(), cm.selectedSlot());
                    switch (st[0]) {
                        case 0 -> {   // take the aim in hand, unless it already stands on the station
                            if (m.eng && !m.drv && m.r == n) return true;
                            if (!cm.takeSelected()) return false;
                            h[0] = 0; st[0] = a < n + 0.6 * w ? 1 : 2;
                        }
                        case 1 -> {   // crescendo, past it
                            h[0] += dt; cm.push(ConductedMachine.CRESCENDO.aim(h[0], dt));
                            if (cm.aim() >= Math.min(ConductedMachine.AIM_MAX, n + over)) { st[0] = 2; h[0] = 0; }
                        }
                        case 2 -> {   // diminuendo, back into the window
                            if (a < n - w) { st[0] = 1; h[0] = 0; }
                            else if (a <= n + 0.6 * w) { cm.letGo(); st[0] = 3; h[0] = 0; }
                            else { h[0] += dt; cm.push(Math.max(n + 0.3 * w - a, ConductedMachine.DIMINUENDO.aim(h[0], dt))); }
                        }
                        default -> {   // hands off: the crystal draws it in and takes it
                            h[0] += dt;
                            if (m.eng && !m.drv && m.r == n) return true;
                            if (core.acceptable(cm.aim()) != n || h[0] > 8) st[0] = 0;
                        }
                    }
                    return false;
                };
            }
            void advance(double dt) {
                if (cur == null) { cur = plan.poll(); if (cur == null) { on = false; doing = ""; return; } elapsed = 0; doing = cur.what(); }
                elapsed += dt;
                if (cur.move().run(dt) || elapsed > cur.timeout()) { if (elapsed > cur.timeout()) { cm.letGo(); cm.phaseLetGo(); } cur = null; }
            }
        }

        /** The stage: the composite figure over the seated crystal, the three arms, the ring of stations with the
         *  components turning at them, and the blueprint strip under it. Figure axes: X right, Y up, Z toward the
         *  conductor (the viewer, in the orbit and front views). */
        class Stage extends JPanel {
            final double[] pt = new double[3];
            static final int TRAIL = 8000;                       // the pen sub-sampled at 480 a second: room for two cycles of an 8 s period
            final double[][] trail = new double[TRAIL][3]; final double[] trailAt = new double[TRAIL]; int trailN, trailPos; double trailTau = -1;
            Point dragAt; double dragYaw, dragElev;
            /** In stage units (the stage's short side is about five): the composite's radius, the ring of stations, a
             *  component's size per unit of reach, and how far under the composite the crystal sits. */
            static final double FIG = 0.7, RING = 1.9, COMP = 0.34, FLOOR = -1.35;
            static final Color PALE = new Color(255, 247, 224), WARM = new Color(255, 150, 70), GOLD = new Color(255, 214, 110);
            Stage() {
                setBackground(new Color(19, 14, 12));
                setFocusable(true);
                MouseAdapter m = new MouseAdapter() {
                    @Override public void mousePressed(MouseEvent e) {
                        requestFocusInWindow();
                        mouse.setLocation(e.getPoint());
                        if (SwingUtilities.isMiddleMouseButton(e)) { toggle(pickAt(e.getPoint(), true)); return; }
                        Pick p = pickAt(e.getPoint(), false);
                        if (p != null) beginThread(p, SwingUtilities.isLeftMouseButton(e));
                        else if (viewBox.getSelectedIndex() == 0) { dragAt = e.getPoint(); dragYaw = dYaw; dragElev = dElev; }
                    }
                    @Override public void mouseDragged(MouseEvent e) {
                        mouse.setLocation(e.getPoint());
                        if (dragAt != null) { dYaw = dragYaw + (e.getX() - dragAt.x) * 0.01; dElev = Math.max(-0.45, Math.min(1.0, dragElev + (e.getY() - dragAt.y) * 0.01)); }
                    }
                    @Override public void mouseMoved(MouseEvent e) { mouse.setLocation(e.getPoint()); }
                    @Override public void mouseReleased(MouseEvent e) { dragAt = null; endThread(); }
                };
                addMouseListener(m); addMouseMotionListener(m);
                addKeyListener(new KeyAdapter() { @Override public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_T) toggle(pickAt(mouse, true)); } });
            }
            /** Stage units to the screen: {x, y, depth, scale}. Elevation 0 looks from the front, a quarter turn from above. */
            double[] proj(double x, double y, double z, double R, int W, int H) {
                double cy = Math.cos(yaw), sy = Math.sin(yaw), ce = Math.cos(elev), se = Math.sin(elev);
                double x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
                double up = y * ce - z1 * se, depth = -(z1 * ce + y * se);
                double k = 3.4 / (3.4 + depth * 0.35);
                return new double[]{W / 2.0 + x1 * R * k, H * 0.42 - up * R * k, depth, k};
            }
            /** Where a ratio's station lies: round the machine from the conductor's side, 45° a unit. */
            void station(double ratio, double[] out) {
                double a = Math.toRadians(45 * ratio);
                out[0] = -RING * Math.sin(a); out[1] = 0; out[2] = RING * Math.cos(a);
            }
            @Override protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int BP = 150, W = getWidth(), H = getHeight() - BP;
                int view = viewBox.getSelectedIndex();
                double tY = view == 0 ? dYaw : 0, tE = view == 0 ? 0.5 + dElev : view == 1 ? 0 : Math.PI / 2 - 0.001;
                yaw += (tY - yaw) * 0.12; elev += (tE - elev) * 0.12;
                double R = Math.min(W, H) * 0.2;
                ext += (core.extent() - ext) * 0.05;
                boolean hold = core.matched != null;
                picks.clear();
                Font small = new Font(Font.MONOSPACED, Font.PLAIN, 11);
                g.setFont(small);

                // the seated crystal, under the composite
                double[] C = proj(0, FLOOR, 0, R, W, H);
                int cx = (int) C[0], cy = (int) C[1], cs = (int) (R * 0.16 * C[3]);
                float pulse = core.powered ? (float) (0.5 + 0.25 * Math.sin(core.tau * 2)) : 0.12f;
                g.setColor(new Color(255, 210, 150, (int) (255 * (0.3 + pulse * 0.45))));
                g.fillPolygon(new int[]{cx, cx + (int) (cs * 0.7), cx + (int) (cs * 0.7), cx, cx - (int) (cs * 0.7), cx - (int) (cs * 0.7)},
                              new int[]{cy - (int) (cs * 1.6), cy - cs / 2, cy + cs / 2, cy + (int) (cs * 1.6), cy + cs / 2, cy - cs / 2}, 6);
                picks.add(new Pick(On.CRYSTAL, -1, -1, cx, cy, cs * 1.9 + 6, false));
                if (!core.powered) {
                    g.setColor(new Color(233, 220, 196, 140)); g.setFont(new Font(Font.SERIF, Font.ITALIC, 20));
                    String t = "The socket is empty."; g.drawString(t, W / 2 - g.getFontMetrics().stringWidth(t) / 2, (int) (H * 0.42));
                    blueprint(g, W, H, BP);
                    return;
                }
                int aiming = cm.selectedAxis(), selSlot = cm.selectedSlot();
                double aim = cm.aim();

                // the stations
                int near = core.acceptable(aim);
                for (int n = 1; n <= 7; n++) {
                    station(n, pt);
                    double[] S = proj(pt[0], pt[1], pt[2], R, W, H);
                    int r = n == near ? 5 : 3;
                    g.setColor(n == near ? new Color(255, 194, 122, 220) : new Color(184, 140, 78, 110));
                    g.fillOval((int) S[0] - r, (int) S[1] - r, 2 * r, 2 * r);
                    g.setColor(new Color(184, 140, 78, 120));
                    g.drawString("×" + n, (int) S[0] + 8, (int) S[1] + 14);
                }

                // the components, grouped by ratio: each group one figure at its station, with its own orb
                boolean[] drawn = new boolean[9];
                int[] ga = new int[9], gs = new int[9];
                for (int s0 = 0; s0 < RegulatorCore.ARMS; s0++) for (int a0 = 0; a0 < RegulatorCore.AXES; a0++) {
                    RegulatorCore.Motion lead = core.comps[s0][a0];
                    if (drawn[s0 * 3 + a0] || !lead.eng || lead.amp <= RegulatorCore.ENGAGE_AMP) continue;
                    int n = 0; boolean inHand = false; double size = 0;
                    for (int s = 0; s < RegulatorCore.ARMS; s++) for (int a = 0; a < RegulatorCore.AXES; a++) {
                        RegulatorCore.Motion mo = core.comps[s][a];
                        if (drawn[s * 3 + a] || !mo.eng || mo.amp <= RegulatorCore.ENGAGE_AMP || Math.abs(mo.r - lead.r) > 0.02) continue;
                        drawn[s * 3 + a] = true; ga[n] = a; gs[n++] = s; size = Math.max(size, mo.amp);
                        if (a == aiming && s == selSlot) inHand = true;
                    }
                    station(lead.r, pt);
                    double sx = pt[0], sy = pt[1], sz = pt[2], grow = Math.min(1, lead.r / 0.6);
                    Color col = inHand ? PALE : WARM;
                    java.awt.geom.Path2D.Float ring = new java.awt.geom.Path2D.Float();
                    for (int i = 0; i <= 48; i++) {
                        double th = 2 * Math.PI * i / 48, x = 0, y = 0, z = 0;
                        for (int k = 0; k < n; k++) {
                            RegulatorCore.Motion mo = core.comps[gs[k]][ga[k]];
                            double v = mo.amp * grow * Math.sin(th + (mo.osc - lead.osc) + cm.phaseAngle(ga[k], gs[k]) * Math.PI / 2);
                            if (ga[k] == 0) x += v; else if (ga[k] == 1) y += v; else z += v;
                        }
                        double[] q = proj(sx + x * COMP, sy + y * COMP, sz + z * COMP, R, W, H);
                        if (i == 0) ring.moveTo(q[0], q[1]); else ring.lineTo(q[0], q[1]);
                    }
                    g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), inHand ? 150 : 100));
                    g.setStroke(new BasicStroke(inHand ? 1.8f : 1.2f)); g.draw(ring);
                    double x = 0, y = 0, z = 0;
                    for (int k = 0; k < n; k++) { double v = cm.swing(ga[k], gs[k], core.tau); if (ga[k] == 0) x += v; else if (ga[k] == 1) y += v; else z += v; }
                    double[] q = proj(sx + x * COMP, sy + y * COMP, sz + z * COMP, R, W, H), S = proj(sx, sy, sz, R, W, H);
                    g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 70)); g.fillOval((int) q[0] - 8, (int) q[1] - 8, 16, 16);
                    g.setColor(new Color(255, 245, 225, 235)); g.fillOval((int) q[0] - 3, (int) q[1] - 3, 7, 7);
                    for (int k = 0; k < n; k++) picks.add(new Pick(On.COMPONENT, ga[k], gs[k], S[0], S[1], Math.max(18, size * COMP * R * S[3] + 10), ga[k] == aiming && gs[k] == selSlot));
                }

                // the arms: the array turns with the aim; the aiming arm faces out to its station, the others the composite
                double want = 45 * aim - aiming * 120, d = want - turn;
                d -= 360 * Math.round(d / 360);
                turn += d * 0.15;
                for (int i = 0; i < 3; i++) {
                    double th = Math.toRadians(turn + i * 120), ux = -Math.sin(th), uz = Math.cos(th);
                    boolean aims = i == aiming;
                    double[] B = proj(ux * 0.85, FLOOR, uz * 0.85, R, W, H), E = proj(ux * 1.2, FLOOR + 0.6, uz * 1.2, R, W, H);
                    double hr = aims ? 1.1 : 0.75, hy = FLOOR + (aims ? 1.2 : 1.05);
                    double[] Hd = proj(ux * hr, hy, uz * hr, R, W, H);
                    boolean carries = false;
                    for (int s = 0; s < cm.slots(); s++) { RegulatorCore.Motion mo = cm.motion(i, s); if (mo.eng && mo.amp > RegulatorCore.ENGAGE_AMP) carries = true; }
                    g.setColor(aims ? new Color(230, 189, 124, 225) : new Color(184, 140, 78, 140));
                    g.setStroke(new BasicStroke(aims ? 3f : 2.2f));
                    g.drawLine((int) B[0], (int) B[1], (int) E[0], (int) E[1]);
                    g.drawLine((int) E[0], (int) E[1], (int) Hd[0], (int) Hd[1]);
                    int hx = (int) Hd[0], hY = (int) Hd[1], s = (int) (9 * Hd[3]);
                    if (aims) {   // its beam, out to what it is building
                        station(aim, pt);
                        double v = cm.selectedExists() ? cm.swing(aiming, selSlot, core.tau) * COMP : 0;
                        double[] T = proj(pt[0] + (aiming == 0 ? v : 0), pt[1] + (aiming == 1 ? v : 0), pt[2] + (aiming == 2 ? v : 0), R, W, H);
                        g.setColor(new Color(255, 247, 224, cm.selectedExists() ? 70 : 28)); g.setStroke(new BasicStroke(1.2f));
                        g.drawLine(hx, hY, (int) T[0], (int) T[1]);
                        g.setColor(new Color(255, 220, 160, 60)); g.fillOval(hx - s - 5, hY - s - 5, 2 * s + 10, 2 * s + 10);
                        picks.add(new Pick(On.ARM, i, selSlot, hx, hY, s + 12, true));
                    }
                    g.setColor(aims || carries ? new Color(255, 194, 122) : new Color(107, 82, 56));
                    g.fillPolygon(new int[]{hx, hx + s / 2, hx, hx - s / 2}, new int[]{hY - s, hY, hY + s, hY}, 4);
                    g.setColor(new Color(184, 140, 78, 150)); g.setFont(small);
                    g.drawString(RegulatorCore.AXIS[i], (int) B[0] - 3, (int) B[1] + 15);
                }

                // the composite: the pen's path sub-sampled into a ring of positions that fade with age. Integer ratios
                // retrace one figure; a detuned motion precesses it, slowing as it is tuned in.
                double u = FIG / ext;
                if (trailTau < 0 || core.tau < trailTau) { trailTau = core.tau; trailN = 0; trailPos = 0; }
                int sub = Math.max(1, Math.min(64, (int) Math.ceil((core.tau - trailTau) * 480)));
                for (int k = 1; k <= sub; k++) {
                    double at = trailTau + (core.tau - trailTau) * k / sub;
                    core.pen(at, pt);
                    double[] t3 = trail[trailPos]; t3[0] = pt[0]; t3[1] = pt[1]; t3[2] = pt[2]; trailAt[trailPos] = at;
                    trailPos = (trailPos + 1) % TRAIL; trailN = Math.min(TRAIL, trailN + 1);
                }
                trailTau = core.tau;
                int NB = 8;
                java.awt.geom.Path2D.Float[] paths = new java.awt.geom.Path2D.Float[NB];
                double[] prev = null, head = null;
                double span = 2 * core.drawPeriod;   // two of the receiver's cycles
                for (int i = 0; i < trailN; i++) {
                    int idx = (trailPos - trailN + i + TRAIL) % TRAIL;
                    double age = (core.tau - trailAt[idx]) / span;
                    if (age > 1) continue;
                    double[] t3 = trail[idx];
                    double[] q = proj(t3[0] * u, t3[1] * u, t3[2] * u, R, W, H);
                    if (prev != null) {
                        int b = Math.max(0, Math.min(NB - 1, (int) ((1 - age) * NB)));   // age bucket: 0 oldest, NB-1 newest
                        if (paths[b] == null) paths[b] = new java.awt.geom.Path2D.Float();
                        paths[b].moveTo(prev[0], prev[1]); paths[b].lineTo(q[0], q[1]);
                    }
                    prev = q; head = q;
                }
                for (int pass = 0; pass < 2; pass++) {
                    g.setStroke(new BasicStroke((float) (pass == 1 ? 1.5 + flashV * 2 : 6 + flashV * 8), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    for (int b = 0; b < NB; b++) {
                        if (paths[b] == null) continue;
                        double fresh = (b + 0.5) / NB;   // 1 = freshest
                        Color c = hold ? GOLD : WARM;
                        double al = (pass == 1 ? 0.95 : 0.09) * fresh * fresh * (hold ? 1.1 : 1);
                        g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.min(1, al))));
                        g.draw(paths[b]);
                    }
                }
                double[] O = proj(0, 0, 0, R, W, H);
                if (head == null) head = O;
                int ox = (int) head[0], oy = (int) head[1];
                g.setColor(new Color(255, 200, 120, 70)); g.fillOval(ox - 11, oy - 11, 22, 22);
                g.setColor(new Color(255, 240, 210, 235)); g.fillOval(ox - 5, oy - 5, 10, 10);
                picks.add(new Pick(On.COMPOSITE, -1, -1, O[0], O[1], Math.max(34, FIG * R * O[3] + 8), false));
                if (flashV > 0) { g.setColor(new Color(255, 200, 140, (int) (flashV * 64))); g.fillRect(0, 0, W, H); }

                // what is on the machine, in words: every component counts toward a match, wherever it stands
                g.setFont(small);
                int line = 0;
                for (int a = 0; a < RegulatorCore.AXES; a++) for (int s = 0; s < cm.slots(); s++) {
                    RegulatorCore.Motion mo = cm.motion(a, s);
                    if (!mo.eng || mo.amp <= RegulatorCore.ENGAGE_AMP) continue;
                    boolean sel = a == aiming && s == selSlot, onStation = mo.r == Math.rint(mo.r) && mo.r >= 1;
                    g.setColor(sel ? new Color(255, 247, 224, 220) : onStation ? new Color(230, 190, 124, 190) : new Color(230, 120, 90, 210));
                    g.drawString(String.format(Locale.ROOT, "%s %s ×%.2f  φ%s  reach %.2f%s", sel ? "▸" : " ", RegulatorCore.AXIS[a], mo.r, RegulatorCore.PHASE[mo.ph], mo.amp,
                            mo.r <= RegulatorCore.ENGAGE_R ? "   (at rest: not in the sum)" : onStation ? "" : "   (off its station)"), 10, 18 + 14 * line++);
                }
                if (line == 0) { g.setColor(new Color(184, 140, 78, 150)); g.drawString("nothing on the machine", 10, 18); line = 1; }
                g.setColor(hold ? GOLD : new Color(184, 140, 78, 170));
                g.drawString(hold ? "holds: " + core.matched.name : core.best != null && !core.engaged().isEmpty() ? String.format(Locale.ROOT, "nearest: %s  %.0f%%", core.best.name, 100 * core.targetEval.score) : "", 10, 22 + 14 * line);

                // the thread, from the pointer to what it is on
                if (thread != null) {
                    double tx = thread.x(), ty = thread.y();
                    for (Pick p : picks) if (p.on() == thread.on() && p.axis() == thread.axis() && p.slot() == thread.slot()) { tx = p.x(); ty = p.y(); }
                    if (thread.on() == On.COMPOSITE) { tx = ox; ty = oy; }
                    g.setColor(new Color(255, 247, 224, threadRising ? 200 : 110));
                    g.setStroke(new BasicStroke(threadRising ? 1.8f : 1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 5}, (float) ((((threadRising ? -1 : 1) * core.tau * 40) % 11 + 11) % 11)));   // the dashes run toward the machine for crescendo, back for diminuendo; a dash phase may not be negative
                    g.drawLine(mouse.x, mouse.y, (int) tx, (int) ty);
                }
                blueprint(g, W, H, BP);
            }
            // the blueprint strip: front (X right, Y up) and top (X right, +Z toward the bottom); static per pinned
            // recipe and size, so it is drawn once into an image
            void blueprint(Graphics2D g, int W, int H, int BP) {
                if (pinned == null) return;
                if (bpImg == null || bpRec != pinned || bpImg.getWidth() != W || bpImg.getHeight() != BP) {
                    bpImg = new BufferedImage(Math.max(1, W), BP, BufferedImage.TYPE_INT_RGB);
                    Graphics2D gi = bpImg.createGraphics();
                    gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    gi.translate(0, -H);
                    paintBlueprint(gi, W, H, BP);
                    gi.dispose();
                    bpRec = pinned;
                }
                g.drawImage(bpImg, 0, H, null);
            }
            BufferedImage bpImg; RegulatorCore.Recipe bpRec;
            void paintBlueprint(Graphics2D g, int W, int H, int BP) {
                int by = H;
                g.setColor(new Color(24, 34, 48)); g.fillRect(0, by, W, BP);
                g.setColor(new Color(214, 230, 245, 20));
                for (int x = 0; x < W; x += 12) g.drawLine(x, by, x, by + BP);
                for (int y = by; y < by + BP; y += 12) g.drawLine(0, y, W, y);
                RegulatorCore.Recipe rec = pinned;
                double E = RegulatorCore.extent(rec);
                int pw = Math.min(W / 2, 2 * BP);
                int ox0 = W / 2 - pw;
                g.setFont(new Font(Font.SERIF, Font.ITALIC, 13));
                int inner = BP - 22;   // a caption row on top, the panes under it
                for (int k = 0; k < 2; k++) {
                    double[][] bp = RegulatorCore.blueprint(rec, k);
                    int ox = ox0 + pw * k + pw / 2, oy = by + 22 + inner / 2;
                    double uu = Math.min(pw, inner) * 0.40 / E;
                    g.setColor(new Color(214, 230, 245, 64));
                    g.drawLine(ox - (int) (uu * E), oy, ox + (int) (uu * E), oy); g.drawLine(ox, oy - (int) (uu * E), ox, oy + (int) (uu * E));
                    g.setColor(new Color(222, 236, 250, 140)); g.setStroke(new BasicStroke(0.8f));
                    for (int i = 1; i < bp.length; i++)
                        g.drawLine((int) Math.round(ox + bp[i - 1][0] * uu), (int) Math.round(oy - bp[i - 1][1] * uu), (int) Math.round(ox + bp[i][0] * uu), (int) Math.round(oy - bp[i][1] * uu));
                    g.setColor(new Color(214, 230, 245, 150)); g.drawString(k == 0 ? "front" : "top", ox0 + pw * k + 6, by + BP - 6);
                }
                g.setColor(new Color(214, 230, 245, 200));
                StringBuilder parts = new StringBuilder();
                for (RegulatorCore.Comp c : rec.comps) parts.append("  ").append(RegulatorCore.AXIS[c.axis()]).append(" ×").append(c.n()).append(" φ").append(RegulatorCore.PHASE[c.phase()]).append(c.amp() != 1 ? String.format(Locale.ROOT, " reach %.2f", c.amp()) : "");
                g.drawString(rec.name + " — tier " + new String[]{"", "I", "II", "III"}[rec.tier] + ":" + parts + "   (front: X right, Y up · top: X right, +Z down)", 8, by + 15);
            }
        }
    }

    /** The docked regulator panel: signal sliders (the scrubber), the signature
     *  picker with the lock / unlock events, the bind table, the marked ranges
     *  and the bench's free notes. */
    static class BenchPanel extends JPanel {
        final SfxLab lab;
        final JSlider[] sl = new JSlider[SIGNALS.length];
        final JLabel[] sv = new JLabel[SIGNALS.length];
        final JComboBox<String> famBox = new JComboBox<>();
        final JPanel spellsP = new JPanel(new GridBagLayout());
        final ArrayList<Object[]> spellRows = new ArrayList<>();   // {Spell, JSlider, JLabel}
        int seenFamily = -1;
        final JCheckBox bindsB = new JCheckBox("binds", true);
        final JLabel drivenL = new JLabel(" ");
        JPanel bindsBox, spellBindsBox;
        JTable paletteTable;
        final ArrayList<BindModel> bindModels = new ArrayList<>();
        /** One table's model: the binds of a bench (the palette's, or a spell's, whose edits mark that spell dirty). */
        class BindModel extends javax.swing.table.AbstractTableModel {
            final Bench target; final Spell sp;
            final javax.swing.table.TableRowSorter<BindModel> sorter;
            BindModel(Bench target, Spell sp) { this.target = target; this.sp = sp; sorter = new javax.swing.table.TableRowSorter<>(this); }   // after target: the sorter reads the row count at once
            void edited() { if (sp == null) lab.markEdit(); else lab.markSpellDirty(sp); }
            public int getRowCount() { return target.binds.size(); }
            public int getColumnCount() { return BCOLS.length; }
            public String getColumnName(int c) { return BCOLS[c]; }
            public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : c == 1 || c == 7 ? Boolean.class : String.class; }
            public boolean isCellEditable(int r, int c) { return c != 0; }
            public Object getValueAt(int r, int c) {
                if (r >= target.binds.size()) return c == 0 ? 0 : c == 1 || c == 7 ? Boolean.FALSE : "";
                Bind b = target.binds.get(r);
                return switch (c) {
                    case 0 -> r + 1;
                    case 1 -> !b.mute;
                    case 2 -> b.sig; case 3 -> b.layer; case 4 -> b.param;
                    case 5 -> b.auto() ? "auto" : fmtNum5(b.lo); case 6 -> b.auto() ? "auto" : fmtNum5(b.hi);
                    case 8 -> b.map();
                    default -> b.rel;
                };
            }
            public void setValueAt(Object v, int r, int c) {
                if (r >= target.binds.size() || c == 0) return;
                Bind b = target.binds.get(r);
                lab.pushUndo("");
                try {
                    switch (c) {
                        case 1 -> b.mute = !Boolean.TRUE.equals(v);
                        case 2 -> b.sig = v.toString().trim();
                        case 3 -> b.layer = v.toString().trim();
                        case 4 -> b.param = v.toString().trim().replace(' ', '_');
                        case 5, 6 -> {
                            String t = v.toString().trim().toLowerCase(Locale.ROOT);
                            if (t.isEmpty() || t.equals("auto")) { b.lo = Double.NaN; b.hi = Double.NaN; }
                            else {
                                if (b.auto()) { Clip lc = target.byId(b.layer); int pi = lc != null ? idxOf(lc.type, b.param) : -1; PSpec ps = pi >= 0 ? spec(lc.type, pi) : new PSpec("", 0, 1, 0); b.lo = ps.min(); b.hi = ps.max(); }
                                if (c == 5) b.lo = Double.parseDouble(t); else b.hi = Double.parseDouble(t);
                            }
                        }
                        case 8 -> { if (!b.setMap(v.toString())) lab.toast("map: steps=N or scale=<chord name: " + String.join(", ", CHORD_NAMES).replace(' ', '_') + ">"); }
                        default -> b.rel = Boolean.TRUE.equals(v);
                    }
                } catch (NumberFormatException ex) { lab.toast("couldn't parse \"" + v + "\""); }
                edited();
                fireTableRowsUpdated(r, r);
            }
        }
        /** A vertical stack that takes the scroll pane's width, so tables fit and titles truncate instead of widening it. */
        static class VBox extends JPanel implements Scrollable {
            public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
            public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
            public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return 120; }
            public boolean getScrollableTracksViewportWidth() { return true; }
            public boolean getScrollableTracksViewportHeight() { return false; }
        }
        /** A titled bind table with its add / remove buttons, for the palette or one spell. */
        JPanel bindSection(String title, Bench target, Spell sp) {
            BindModel model = new BindModel(target, sp);
            bindModels.add(model);
            JTable table = new JTable(model) {
                public String getToolTipText(MouseEvent e) {   // the row's algebra: its term, the sum on its param, the value right now
                    int r = rowAtPoint(e.getPoint()); if (r < 0) return null;
                    int mr = convertRowIndexToModel(r);
                    return mr < target.binds.size() ? lab.bindTip(target, target.binds.get(mr)) : null;
                }
            };
            ToolTipManager.sharedInstance().registerComponent(table);
            table.setTableHeader(new javax.swing.table.JTableHeader(table.getColumnModel()) {
                public String getToolTipText(MouseEvent e) {   // what each column takes
                    int c = columnAtPoint(e.getPoint()); if (c < 0) return null;
                    String h = bindColHelp(BCOLS[table.convertColumnIndexToModel(c)]);
                    return h.startsWith("<html>") ? h : "<html><div width=420>" + h + "</div></html>";
                }
            });
            ToolTipManager.sharedInstance().registerComponent(table.getTableHeader());
            holdTip(table); holdTip(table.getTableHeader());   // the algebra and the column help stay up until the mouse leaves
            if (sp == null) paletteTable = table;
            table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            int[] widths = {24, 26, 84, 74, 74, 46, 46, 28, 64};
            for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            table.setRowSorter(model.sorter);   // click a header to sort; the filter box above the tables narrows the rows
            model.sorter.setRowFilter(rowFilter());
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "back");
            table.getActionMap().put("back", new AbstractAction() { public void actionPerformed(ActionEvent e) { lab.requestFocusInWindow(); } });
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyBinds");
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "pasteBinds");
            table.getActionMap().put("copyBinds", new AbstractAction() { public void actionPerformed(ActionEvent e) { copyBinds(table, model); } });
            table.getActionMap().put("pasteBinds", new AbstractAction() { public void actionPerformed(ActionEvent e) { pasteBinds(target, sp); } });
            JPanel sec = new JPanel(new BorderLayout(2, 2));
            sec.setBorder(BorderFactory.createEmptyBorder(6, 0, 2, 0));
            JLabel head = new JLabel(title);
            if (sp != null) head.setForeground(new Color(150, 110, 30));
            sec.add(head, BorderLayout.NORTH);
            JPanel body = new JPanel(new BorderLayout());
            body.add(table.getTableHeader(), BorderLayout.NORTH);
            body.add(table, BorderLayout.CENTER);
            sec.add(body, BorderLayout.CENTER);
            JPanel bb = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton addB = new JButton("+ bind…"), remB = new JButton("− remove"), copyB = new JButton("copy"), pasteB = new JButton("paste…");
            for (JButton b : new JButton[]{addB, remB, copyB, pasteB}) b.setMargin(new Insets(0, 4, 0, 4));
            copyB.setToolTipText("the selected rows (none: the whole table) as bind lines on the clipboard (ctrl+C) — paste into another table, family or text editor");
            pasteB.setToolTipText("bind lines from the clipboard into this table, retargeted to a layer if you like (ctrl+V)");
            addB.addActionListener(e -> addBindDialog(target, sp));
            remB.addActionListener(e -> {
                ArrayList<Bind> del = new ArrayList<>();
                for (int r : table.getSelectedRows()) { int mr = table.convertRowIndexToModel(r); if (mr < target.binds.size()) del.add(target.binds.get(mr)); }
                if (del.isEmpty()) return;
                lab.pushUndo("");
                synchronized (lab.lock) { target.binds.removeAll(del); }
                model.edited(); lab.benchGen++;
            });
            copyB.addActionListener(e -> copyBinds(table, model));
            pasteB.addActionListener(e -> pasteBinds(target, sp));
            bb.add(addB); bb.add(remB); bb.add(copyB); bb.add(pasteB);
            sec.add(bb, BorderLayout.SOUTH);
            sec.setMaximumSize(new Dimension(Integer.MAX_VALUE, sec.getPreferredSize().height + 400));
            return sec;
        }
        boolean refreshing; int seenGen = -1;
        static final String[] BCOLS = {"#", "on", "signal", "layer", "param", "lo", "hi", "rel", "map"};   // #: the row's place in the list (the family file's order; bound sliders show it)
        final JTextField filterF = new JTextField(10);
        final JCheckBox selOnlyC = new JCheckBox("selected layer");
        Clip filterSel;   // the layer the filter last followed
        /** The rows to show: matching the filter text (signal, layer or param) and, if ticked, the selected layer's. */
        RowFilter<BindModel, Integer> rowFilter() {
            return new RowFilter<>() {
                public boolean include(Entry<? extends BindModel, ? extends Integer> e) {
                    List<Bind> bs = e.getModel().target.binds;
                    int r = e.getIdentifier();
                    if (r >= bs.size()) return true;
                    Bind b = bs.get(r);
                    String q = filterF.getText().trim().toLowerCase(Locale.ROOT);
                    if (selOnlyC.isSelected() && lab.sel != null && lab.sel.id != null && !(b.layer.equals("*") || b.layer.equals(lab.sel.id))) return false;
                    return q.isEmpty() || b.sig.toLowerCase(Locale.ROOT).contains(q) || b.layer.toLowerCase(Locale.ROOT).contains(q) || b.param.toLowerCase(Locale.ROOT).contains(q);
                }
            };
        }
        void refilter() { for (BindModel m : bindModels) m.sorter.setRowFilter(rowFilter()); }
        /** The selected rows (none: every row) as bind lines on the clipboard. */
        void copyBinds(JTable table, BindModel model) {
            StringBuilder sb = new StringBuilder(); int n = 0;
            int[] rows = table.getSelectedRows();
            if (rows.length == 0) rows = java.util.stream.IntStream.range(0, table.getRowCount()).toArray();
            for (int r : rows) { int mr = table.convertRowIndexToModel(r); if (mr < model.target.binds.size()) { sb.append(model.target.binds.get(mr).line()).append('\n'); n++; } }
            if (n == 0) { lab.toast("no binds to copy"); return; }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(sb.toString()), null);
            lab.toast(n + " bind line" + (n == 1 ? "" : "s") + " copied — paste… into another table (or a family file)");
        }
        /** Bind lines from the clipboard join a table, all retargeted to one layer if asked (a sample swap, a transfer between families). */
        void pasteBinds(Bench target, Spell sp) {
            String text;
            try { text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor); }
            catch (Exception e) { lab.toast("clipboard has no text"); return; }
            List<Bind> in = parseBench(Arrays.asList(text.split("\n"))).binds;
            if (in.isEmpty()) { lab.toast("clipboard has no bind lines (bind <signal> <layer> <param> …)"); return; }
            List<Clip> ls; synchronized (lab.lock) { ls = new ArrayList<>(target.layers); }
            JComboBox<String> layC = new JComboBox<>();
            layC.addItem("as written");
            layC.addItem("*");
            for (Clip c : ls) layC.addItem(c.id);
            if (lab.sel != null && lab.sel.id != null && target.byId(lab.sel.id) != null) layC.setSelectedItem(lab.sel.id);
            JPanel p = new JPanel(new BorderLayout(4, 4));
            StringBuilder pv = new StringBuilder("<html>");
            for (int i = 0; i < Math.min(8, in.size()); i++) pv.append(in.get(i).line().substring(5)).append("<br>");
            if (in.size() > 8) pv.append("… ").append(in.size() - 8).append(" more");
            p.add(new JLabel(pv.append("</html>").toString()), BorderLayout.NORTH);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.add(new JLabel("layer for all of them:")); row.add(layC);
            p.add(row, BorderLayout.SOUTH);
            if (JOptionPane.showConfirmDialog(this, p, "Paste " + in.size() + " bind" + (in.size() == 1 ? "" : "s") + (sp == null ? " into the palette" : " into spell " + sp.id), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            String lay = (String) layC.getSelectedItem();
            lab.pushUndo("");
            synchronized (lab.lock) { for (Bind b : in) { if (!"as written".equals(lay)) b.layer = lay; target.binds.add(b); } }
            lab.benchGen++;
            if (sp == null) lab.markEdit(); else lab.markSpellDirty(sp);
            lab.toast(in.size() + " bind" + (in.size() == 1 ? "" : "s") + " pasted" + ("as written".equals(lay) ? "" : " onto " + lay));
        }

        BenchPanel(SfxLab lab) {
            this.lab = lab;
            setLayout(new BorderLayout(4, 4));
            setPreferredSize(new Dimension(600, 100));   // wide enough for the signal readouts and a spell's recipe line
            setBackground(Color.BLACK);
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

            JPanel top = new JPanel(new GridBagLayout());
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(1, 4, 1, 4); gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL;
            gc.gridy = 0; gc.gridx = 0; gc.gridwidth = 3;
            JPanel sigRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            sigRow.add(new JLabel("family"));
            famBox.setFont(mono);
            famBox.setToolTipText("regulator/<family>.sfx: the palette and its spells, loaded onto the bench; edits autosave to a working copy (regulator/.working/) until S saves the family");
            famBox.addActionListener(e -> { if (!refreshing) { String f = (String) famBox.getSelectedItem(); if (f != null && !f.equals(lab.family)) lab.loadFamily(f.equals("(none)") ? null : f); } });
            sigRow.add(famBox);
            JButton lockB = new JButton("bench lock"), unlockB = new JButton("bench unlock"), rescanB = new JButton("↻"), syncB = new JButton("sync to mod");
            syncB.setToolTipText("<html><div width=420>run the mod's sync: checks the SAVED family files, bakes what they play, and copies the runtime, the families and the bake into the mod. "
                    + "Edits not yet saved with S are not synced and are left as they are. Nothing is committed; relaunch the game to hear it.</div></html>");
            syncB.addActionListener(e -> lab.syncToMod());
            bindsB.setToolTipText("off: every layer plays its saved params — no signal moves anything and no spell blends in — for auditioning a layer on its own");
            bindsB.addActionListener(e -> { lab.mix.bindsOn = bindsB.isSelected(); lab.toast(lab.mix.bindsOn ? "binds on: signals move bound params, spells blend in by their scores" : "binds off: layers play as saved, no blend (solo / mute to audition)"); });
            lockB.setToolTipText("the lock event for the layers on the bench: score → 1, fires their on=lock one-shots (each spell has its own buttons below)");
            unlockB.setToolTipText("the unlock event for the layers on the bench: fires their on=unlock one-shots");
            lockB.addActionListener(e -> lab.fireEvent(ON_LOCK));
            unlockB.addActionListener(e -> lab.fireEvent(ON_UNLOCK));
            rescanB.setToolTipText("rescan regulator/ and reload the family from disk");
            rescanB.addActionListener(e -> { rescanFamilies(); if (lab.family != null) lab.loadFamily(lab.family); });
            for (AbstractButton b : new AbstractButton[]{lockB, unlockB, rescanB, bindsB, syncB}) b.setFocusable(false);   // a click here must not take P / SPACE away from the bench
            sigRow.add(lockB); sigRow.add(unlockB); sigRow.add(rescanB); sigRow.add(bindsB);
            top.add(sigRow, gc);
            // fold buttons: the signal sliders and the spell rows each fold away so the bind tables get the height
            gc.gridy = 1;
            JPanel foldRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JPanel sigGrid = new JPanel(new GridBagLayout());
            JToggleButton sigT = new JToggleButton("signals", !lab.panelFold.contains("signals")), spT = new JToggleButton("spells", !lab.panelFold.contains("spells"));
            for (JToggleButton t : new JToggleButton[]{sigT, spT}) { t.setMargin(new Insets(0, 4, 0, 4)); t.setFocusable(false); t.setFont(mono); }
            sigT.setToolTipText("show / fold the signal sliders (the scrubber)");
            spT.setToolTipText("show / fold the spells' score sliders and buttons");
            Runnable fold = () -> {
                sigGrid.setVisible(sigT.isSelected()); spellsP.setVisible(spT.isSelected());
                sigT.setText((sigT.isSelected() ? "▾ " : "▸ ") + "signals"); spT.setText((spT.isSelected() ? "▾ " : "▸ ") + "spells");
                lab.panelFold = (sigT.isSelected() ? "" : "signals,") + (spT.isSelected() ? "" : "spells");
                top.revalidate(); top.repaint();
            };
            sigT.addActionListener(e -> { fold.run(); lab.saveCfg(); }); spT.addActionListener(e -> { fold.run(); lab.saveCfg(); });
            foldRow.add(sigT); foldRow.add(spT);
            syncB.setMargin(new Insets(0, 6, 0, 6));
            foldRow.add(syncB);   // here, not on the family row: that row is full, and a narrow panel clipped the button away
            JLabel muteHint = new JLabel("box off: its binds hold still (A/B)"); muteHint.setForeground(Color.GRAY);
            foldRow.add(muteHint);
            top.add(foldRow, gc);
            gc.gridy = 2;
            top.add(sigGrid, gc);
            gc.gridy = 98; gc.gridwidth = 3;
            top.add(spellsP, gc);
            gc.gridy = 99;
            drivenL.setForeground(new Color(200, 120, 40));
            top.add(drivenL, gc);
            gc.gridwidth = 1;
            for (int i = 0; i < SIGNALS.length; i++) {
                final int k = i;
                gc.gridy = i + 1;
                gc.gridx = 0; gc.weightx = 0;
                sigGrid.add(sigLabel(SIGNALS[i], mono), gc);
                gc.gridx = 1; gc.weightx = 1;
                sl[i] = new JSlider(0, 1000, 0);
                sl[i].addChangeListener(e -> { if (!refreshing) { lab.sigVal[k] = sl[k].getValue() / 1000.0 * SIG_MAX[k]; label(k); } });
                sl[i].setToolTipText("<html><div width=520>" + sigHelp(SIGNALS[i]) + "<br><i>scrub it by hand here; with the machine driving the bench it follows the machine</i></div></html>"); holdTip(sl[i]);
                sigGrid.add(sl[i], gc);
                gc.gridx = 2; gc.weightx = 0;
                sv[i] = new JLabel(); sv[i].setFont(mono); sv[i].setPreferredSize(new Dimension(190, 16));
                sv[i].setToolTipText("<html><div width=520>" + sigHelp(SIGNALS[i]) + "</div></html>"); holdTip(sv[i]);
                sigGrid.add(sv[i], gc);
                label(i);
            }
            fold.run();
            add(top, BorderLayout.NORTH);

            // binds: the palette's table (the searching mix), then one table per spell (its lock mix), stacked
            bindsBox = new VBox(); bindsBox.setLayout(new BoxLayout(bindsBox, BoxLayout.Y_AXIS));
            bindsBox.setToolTipText("signal → layer.param over lo..hi (auto = the marked range) · map: steps=N or scale=penta · on: off keeps the row but stops it moving anything");
            JPanel filterRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            filterRow.add(new JLabel("filter"));
            filterF.setFont(mono);
            filterF.setToolTipText("show only binds whose signal, layer or param contains this (e.g. orb, cutoff, seventh)");
            filterF.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refilter(); }
            });
            filterF.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ESCAPE"), "back");
            filterF.getActionMap().put("back", new AbstractAction() { public void actionPerformed(ActionEvent e) { lab.requestFocusInWindow(); } });
            selOnlyC.setFocusable(false);
            selOnlyC.setToolTipText("show only the binds on the layer selected on the bench (and the * ones)");
            selOnlyC.addActionListener(e -> refilter());
            filterRow.add(filterF); filterRow.add(selOnlyC);
            filterRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            bindsBox.add(filterRow);
            bindsBox.add(bindSection("palette binds — the searching mix", lab.bench, null));
            spellBindsBox = new JPanel(); spellBindsBox.setLayout(new BoxLayout(spellBindsBox, BoxLayout.Y_AXIS));
            bindsBox.add(spellBindsBox);
            bindsBox.add(Box.createVerticalGlue());
            JScrollPane bsp = new JScrollPane(bindsBox); bsp.getVerticalScrollBar().setUnitIncrement(16);
            add(bsp, BorderLayout.CENTER);
            add(new JLabel("  ESC: back to the bench · click a column header to sort · ctrl+C / ctrl+V copy and paste bind lines · white tick: the live value"), BorderLayout.SOUTH);
            for (JComponent c : new JComponent[]{famBox}) {
                c.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"), "back");
                c.getActionMap().put("back", new AbstractAction() { public void actionPerformed(ActionEvent e) { lab.requestFocusInWindow(); } });
            }
            rescanFamilies();
            refresh();
            new javax.swing.Timer(200, e -> {
                if (lab.benchGen != seenGen) refresh();
                if (lab.familyGen != seenFamily) rebuildSpells();
                for (Object[] r : spellRows) {
                    Spell sp = (Spell) r[0];
                    ((JLabel) r[2]).setText(String.format(Locale.ROOT, "%.2f  blend %.0f%%", lab.spellScore.getOrDefault(sp.id, 0.0), 100 * sp.w));
                }
                boolean drv = lab.mix.driven;
                if (sl[0].isEnabled() == drv) {
                    for (JSlider s : sl) s.setEnabled(!drv);
                    for (Object[] r : spellRows) ((JSlider) r[1]).setEnabled(!drv);
                    drivenL.setText(drv ? "signals driven by the machine (U) — cut its power, untick 'drive the bench', or close it to use these" : " ");
                }
                for (int i = 0; i < 3; i++) label(i);   // ratio rows show the derived pitch
                label(SIG_SCORE);
                if (selOnlyC.isSelected() && lab.sel != filterSel) { filterSel = lab.sel; refilter(); }
            }).start();
        }
        /** A signal's name with its on / off box: off holds every bind on that signal still (session only, not saved). */
        JPanel sigLabel(String sig, Font mono) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            JCheckBox on = new JCheckBox("", !lab.mutedSigs.contains(sig));
            on.setMargin(new Insets(0, 0, 0, 0)); on.setFocusable(false);
            on.setToolTipText("on: binds on " + sig + " move their params · off: they hold still, so the signal's effect can be compared live");
            JLabel nm = new JLabel(sig); nm.setFont(mono);
            nm.setToolTipText("<html><div width=520>" + sigHelp(sig) + "</div></html>"); holdTip(nm);
            nm.setForeground(on.isSelected() ? UIManager.getColor("Label.foreground") : Color.GRAY);
            on.addActionListener(e -> { if (on.isSelected()) lab.mutedSigs.remove(sig); else lab.mutedSigs.add(sig); nm.setForeground(on.isSelected() ? UIManager.getColor("Label.foreground") : Color.GRAY); lab.benchGen++; });
            p.add(on); p.add(nm);
            return p;
        }
        void label(int i) {
            double v = lab.sigVal[i];
            String s = String.format(Locale.ROOT, "%.2f", v);
            if (i < 3) { double st = lab.signal(SIGNALS[i].replace(".ratio", ".pitch")); s += v > 0.05 ? String.format(Locale.ROOT, "  pitch %.2f st", st) : "  (off)"; }
            if (i == SIG_SCORE) s += "  (the pinned spell's)";
            sv[i].setText(s);
        }
        /** One score slider per spell of the family, with its own lock / unlock buttons. */
        void rebuildSpells() {
            refreshing = true;
            seenFamily = lab.familyGen;
            spellsP.removeAll(); spellRows.clear();
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(1, 4, 1, 4); gc.anchor = GridBagConstraints.WEST; gc.fill = GridBagConstraints.HORIZONTAL;
            Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);
            int row = 0;
            for (Spell sp : lab.mix.spells) {
                // row 1: score.<id>  [slider]  value · row 2: what it is, and its buttons
                gc.gridy = row++; gc.gridwidth = 1;
                gc.gridx = 0; gc.weightx = 0;
                spellsP.add(sigLabel("score." + sp.id, mono), gc);
                gc.gridx = 1; gc.weightx = 1;
                JSlider s = new JSlider(0, 1000, (int) Math.round(lab.spellScore.getOrDefault(sp.id, 0.0) * 1000));
                s.setEnabled(!lab.mix.driven);
                s.setToolTipText("<html><div width=520>" + sigHelp("score." + sp.id) + "</div></html>"); holdTip(s);
                s.addChangeListener(e -> { if (!refreshing) lab.spellScore.put(sp.id, s.getValue() / 1000.0); });
                spellsP.add(s, gc);
                gc.gridx = 2; gc.weightx = 0;
                JLabel v = new JLabel(); v.setFont(mono); v.setPreferredSize(new Dimension(190, 16));
                spellsP.add(v, gc);
                gc.gridy = row++; gc.gridx = 0; gc.gridwidth = 3;
                JPanel under = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
                String prob = sp.recipe != null ? recipeProblem(sp.bench) : null;
                String full = sp.recipe == null ? "no recipe — the machine cannot score it" : "tier " + sp.recipe.tier + (sp.recipe.secret ? " secret" : "") + "  " + recipeText(sp.bench).replaceFirst("^tier=\\d( secret=1)? ", "") + (prob != null ? "  ⚠" : "");
                JLabel what = new JLabel(full.length() > 40 ? full.substring(0, 39) + "…" : full);   // a tier-III recipe is long: the tooltip has all of it, so the row never widens the panel
                what.setFont(mono); what.setForeground(sp.recipe == null || prob != null ? new Color(200, 120, 40) : Color.GRAY);
                what.setToolTipText(sp.name + " · " + full + (prob != null ? " · unbuildable: " + prob : ""));
                JButton lk = new JButton("lock"), ul = new JButton("unlock"), rc = new JButton("recipe…");
                for (JButton b : new JButton[]{lk, ul, rc}) { b.setMargin(new Insets(0, 4, 0, 4)); b.setFont(mono); b.setFocusable(false); }
                rc.addActionListener(e -> lab.recipeDialog(sp, null));
                lk.addActionListener(e -> { lab.spellScore.put(sp.id, 1.0); s.setValue(1000); int n = lab.fireSpell(sp.id, ON_LOCK); lab.toast(sp.id + " lock: " + n + " one-shot" + (n == 1 ? "" : "s")); });
                ul.addActionListener(e -> { lab.spellScore.put(sp.id, 0.5); s.setValue(500); int n = lab.fireSpell(sp.id, ON_UNLOCK); lab.toast(sp.id + " unlock: " + n + " one-shot" + (n == 1 ? "" : "s")); });
                under.add(Box.createHorizontalStrut(14)); under.add(lk); under.add(ul); under.add(rc); under.add(what);
                spellsP.add(under, gc);
                spellRows.add(new Object[]{sp, s, v});
            }
            if (lab.mix.spells.isEmpty()) { gc.gridy = 0; gc.gridx = 0; gc.gridwidth = 3; JLabel l = new JLabel(lab.family == null ? "no family loaded — pick one above, or S saves the bench as a new one" : "no spells in " + lab.family + " yet — the new spell button adds one"); l.setForeground(Color.GRAY); spellsP.add(l, gc); }
            spellsP.revalidate(); spellsP.repaint();
            if (spellBindsBox != null) {
                bindModels.removeIf(m -> m.sp != null);
                spellBindsBox.removeAll();
                for (Spell sp : lab.mix.spells) spellBindsBox.add(bindSection("spell " + sp.id + " binds — its lock mix", sp.bench, sp));
                bindsBox.invalidate(); bindsBox.revalidate(); bindsBox.repaint();
            }
            refreshing = false;
        }
        /** The lab moved a signal (lock / unlock): the sliders follow. */
        void pull() {
            refreshing = true;
            for (int i = 0; i < SIGNALS.length; i++) { sl[i].setValue((int) Math.round(lab.sigVal[i] / SIG_MAX[i] * 1000)); label(i); }
            for (Object[] r : spellRows) ((JSlider) r[1]).setValue((int) Math.round(lab.spellScore.getOrDefault(((Spell) r[0]).id, 0.0) * 1000));
            refreshing = false;
        }
        void rescanFamilies() {
            refreshing = true;
            famBox.removeAllItems();
            famBox.addItem("(none)");
            for (String f : familyNames()) famBox.addItem(f);
            famBox.setSelectedItem(lab.family != null ? lab.family : "(none)");
            refreshing = false;
        }
        void refresh() {
            refreshing = true;
            seenGen = lab.benchGen;
            for (BindModel m : bindModels) { m.fireTableDataChanged(); m.sorter.setRowFilter(rowFilter()); }
            if (lab.family != null && !lab.family.equals(famBox.getSelectedItem())) rescanFamilies();
            refreshing = false;
        }
        void addBindDialog(Bench target, Spell sp) {
            List<Clip> ls;
            synchronized (lab.lock) { ls = new ArrayList<>(target.layers); }
            if (ls.isEmpty()) { lab.toast("add a layer first"); return; }
            JComboBox<String> sigC = new JComboBox<>(lab.signalChoices().toArray(new String[0]));
            JComboBox<String> layC = new JComboBox<>();
            layC.addItem("*");
            for (Clip c : ls) layC.addItem(c.id);
            if (sp != null) layC.addItem(SPELL_LAYER);   // the spell itself: `blend`, its weight curve
            if (lab.sel != null && lab.sel.id != null) layC.setSelectedItem(lab.sel.id);
            JTextField loF = new JTextField("auto", 6), hiF = new JTextField("auto", 6), mapF = new JTextField("", 12);
            JCheckBox relC = new JCheckBox("rel (added to the layer's own value)");
            JComboBox<String> parC = new JComboBox<>();
            Runnable fillParams = () -> {
                parC.removeAllItems();
                String lid = (String) layC.getSelectedItem();
                if (SPELL_LAYER.equals(lid)) { parC.addItem(BLEND_PARAM); loF.setText("0.55"); hiF.setText("1"); return; }
                Clip c = target.byId(lid);
                if (c == null) c = ls.get(0);
                for (int i = 0; i < c.p.length; i++) parC.addItem(key(c.type, i));
            };
            fillParams.run();
            layC.addActionListener(e -> fillParams.run());
            parC.addActionListener(e -> { boolean pitch = "pitch".equals(parC.getSelectedItem()); relC.setSelected(pitch); if (pitch) { loF.setText("0"); hiF.setText("12"); } });
            JPanel p = new JPanel(new GridLayout(0, 2, 4, 4));
            p.add(new JLabel("signal")); p.add(sigC);
            p.add(new JLabel("layer (* = all)")); p.add(layC);
            p.add(new JLabel("param")); p.add(parC);
            p.add(new JLabel("lo (auto = marked range)")); p.add(loF);
            p.add(new JLabel("hi")); p.add(hiF);
            p.add(new JLabel("")); p.add(relC);
            mapF.setToolTipText(bindColHelp("map"));
            loF.setToolTipText("<html><div width=420>" + bindColHelp("lo") + "</div></html>"); hiF.setToolTipText(loF.getToolTipText());
            relC.setToolTipText("<html><div width=420>" + bindColHelp("rel") + "</div></html>");
            sigC.setToolTipText("<html><div width=420>" + bindColHelp("signal") + "</div></html>");
            sigC.addActionListener(e -> { Object v = sigC.getSelectedItem(); if (v != null) sigC.setToolTipText("<html><div width=520>" + sigHelp(v.toString()) + "</div></html>"); });
            for (JComponent f : new JComponent[]{mapF, loF, hiF, relC, sigC}) holdTip(f);
            p.add(new JLabel("map: steps=N or scale=name")); p.add(mapF);
            if (JOptionPane.showConfirmDialog(this, p, sp == null ? "Add bind (palette)" : "Add bind (spell " + sp.id + ")", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            try {
                String lo = loF.getText().trim().toLowerCase(Locale.ROOT), hi = hiF.getText().trim().toLowerCase(Locale.ROOT);
                boolean auto = lo.isEmpty() || lo.equals("auto") || hi.isEmpty() || hi.equals("auto");
                lab.pushUndo("");
                Bind nb = new Bind((String) sigC.getSelectedItem(), (String) layC.getSelectedItem(), (String) parC.getSelectedItem(),
                        auto ? Double.NaN : Double.parseDouble(lo), auto ? Double.NaN : Double.parseDouble(hi), relC.isSelected());
                if (!nb.setMap(mapF.getText())) { lab.toast("map: steps=N or scale=<" + String.join(", ", CHORD_NAMES).replace(' ', '_') + ">"); return; }
                synchronized (lab.lock) { target.binds.add(nb); }
                lab.benchGen++;
                if (sp == null) lab.markEdit(); else lab.markSpellDirty(sp);
            } catch (NumberFormatException ex) { lab.toast("couldn't parse the range"); }
        }
    }

    // =====================================================================
    // Clip library: banked clips, reusable across projects. Same file format
    // as projects, so entries can be hand-copied between the two.
    // =====================================================================
    final ArrayList<Clip> library = new ArrayList<>();

    void loadLibrary() {
        try {
            if (Files.exists(LIB_FILE)) library.addAll(parseProject(LIB_FILE, null, null, null));
        } catch (Exception e) { System.err.println("library load failed: " + e); }
    }

    void saveLibrary() {
        try {
            StringBuilder sb = new StringBuilder("# SfxLab clip library — B banks the selected clip, Q browses\n");
            for (Clip c : library) sb.append(clipLine(c));
            Files.createDirectories(DIR);
            Files.writeString(LIB_FILE, sb.toString());
        } catch (Exception e) { toast("library save failed: " + e); }
    }

    void saveToLibrary() {
        if (sel == null) { toast("select a clip first"); return; }
        String name = (String) JOptionPane.showInputDialog(this, "Library name for this clip:",
                "Save to Library", JOptionPane.PLAIN_MESSAGE, null, null, sel.name);
        if (name == null) return;
        name = name.trim().replace(' ', '-');
        if (name.isEmpty()) return;
        final String fname = name;
        if (library.stream().anyMatch(e -> e.name.equals(fname))
                && JOptionPane.showConfirmDialog(this, name + " exists in the library — replace it?",
                        "Save to Library", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Clip c = copyClip(sel);
        c.name = name; c.track = 0; c.start = 0;
        library.removeIf(e -> e.name.equals(fname));
        library.add(c);
        saveLibrary();
        toast("banked \"" + name + "\" — Q browses the library");
    }

    void insertFromLibrary(Clip entry) {
        pushUndo("");
        Clip c = copyClip(entry);
        c.track = selTrack;
        c.start = playPos;
        synchronized (lock) { clips.add(c); }
        sel = c;
        markEdit();
        toast(String.format(Locale.ROOT, "%s added at %.2fs on track %d", c.name, c.start, c.track + 1));
    }

    void renameLibraryEntry(Clip entry) {
        String name = (String) JOptionPane.showInputDialog(this, "New name:",
                "Rename", JOptionPane.PLAIN_MESSAGE, null, null, entry.name);
        if (name == null) return;
        name = name.trim().replace(' ', '-');
        if (name.isEmpty() || name.equals(entry.name)) return;
        final String fname = name;
        if (library.stream().anyMatch(e -> e != entry && e.name.equals(fname))) {
            toast("\"" + name + "\" already exists in the library");
            return;
        }
        entry.name = name;
        saveLibrary();
    }

    void deleteLibraryEntry(Clip entry) {
        if (JOptionPane.showConfirmDialog(this, "Delete \"" + entry.name + "\" from the library?",
                "Delete", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        library.remove(entry);
        saveLibrary();
        toast("deleted " + entry.name);
    }

    /** W: bring a recorded audio file in as a clip. The file lands in
     *  samples/ so projects stay portable; anything ffmpeg can
     *  read is accepted (non-PCM files are decoded to .wav on the way in). */
    void importSample() {
        JFileChooser fc = new JFileChooser(DIR.toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "audio / video files", "wav", "aiff", "aif", "au", "mp3", "ogg", "flac", "m4a", "opus",
                "mp4", "mkv", "webm", "mov"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path src = fc.getSelectedFile().toPath();
        try {
            Files.createDirectories(SAMPLE_DIR);
            String fname = src.getFileName().toString().replace(' ', '-');
            if (!isPcmName(fname)) {
                if (!haveFfmpeg()) { toast("ffmpeg not found — needed for non-wav files (sudo apt install ffmpeg)"); return; }
                fname = fname.replaceFirst("\\.[^.]+$", "") + ".wav";
                Path dst = SAMPLE_DIR.resolve(fname);
                if (!Files.exists(dst))
                    run("ffmpeg", "-v", "error", "-y", "-i", src.toString(), "-vn", "-ac", "2", "-ar", String.valueOf(SR),
                        "-c:a", "pcm_s16le", dst.toString());
            } else {
                Path dst = SAMPLE_DIR.resolve(fname);
                if (!Files.exists(dst)) Files.copy(src, dst);
            }
            addSampleClip(fname, selTrack, playPos);
        } catch (Exception e) { toast("import failed: " + e); }
    }

    /** Drops a clip playing samples/<fname> at `start`; null if it won't decode. */
    Clip addSampleClip(String fname, int track, double start) {
        float[][] smp = sample(fname);
        if (smp[0].length <= 1) { toast("couldn't decode " + fname); return null; }
        double dur = smp[0].length / (double) SR;
        Clip c = new Clip(fname.replaceFirst("\\.[^.]+$", ""), SAMPLE, track, start, dur, uiRng.nextLong());
        c.file = fname;
        c.p[P_ATT] = 0.002;
        c.p[P_REL] = 0.02;   // near-zero fades: let the recording speak
        if (benchOn) {
            addLayer(c);
            toast(String.format(Locale.ROOT, "sample %s (%.2fs) is layer %s — right-click its row to make it a one-shot", fname, dur, c.id));
            return c;
        }
        pushUndo("");
        synchronized (lock) { clips.add(c); }
        sel = c;
        markEdit();
        toast(String.format(Locale.ROOT, "sample %s (%.2fs) at %.2fs on track %d", fname, dur, start, track + 1));
        return c;
    }

    // =====================================================================
    // Video reference: a screen recording laid on the timeline so sounds
    // can be timed to what's on screen. ffmpeg extracts its frames once into
    // video-cache/<name>-<hash>/ as JPEGs (≤ 30 fps, 640 px wide,
    // first VIDEO_MAX_SECS seconds); the filmstrip lane and the monitor
    // window decode them on demand.
    // =====================================================================
    static final Path VIDEO_CACHE = DIR.resolve("video-cache");
    static final int VIDEO_MAX_SECS = 300, VIDEO_H = 62, THUMB_H = 54;

    static class VideoRef {
        final Path path;
        volatile double start;
        volatile double fps = 30;
        volatile int frames = 0;
        volatile String status = "preparing…";
        volatile Path dir;
        int thumbW = 96;
        final LinkedHashMap<Integer, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> e) { return size() > 48; }
        };
        final HashMap<Integer, BufferedImage> thumbs = new HashMap<>();
        VideoRef(Path path, double start) { this.path = path; this.start = start; }
        double dur() { return frames / fps; }
        int frameAt(double t) { return (int) Math.floor((t - start) * fps + 1e-6); }
        synchronized BufferedImage frame(int i) {
            if (i < 0 || i >= frames || dir == null) return null;
            BufferedImage im = cache.get(i);
            if (im == null) {
                try { im = ImageIO.read(dir.resolve(String.format("f%05d.jpg", i + 1)).toFile()); }
                catch (IOException e) { return null; }
                if (im == null) return null;
                cache.put(i, im);
                thumbW = Math.max(16, THUMB_H * im.getWidth() / im.getHeight());
            }
            return im;
        }
        synchronized BufferedImage thumb(int i) {
            BufferedImage t = thumbs.get(i);
            if (t == null) {
                BufferedImage im = frame(i);
                if (im == null) return null;
                t = new BufferedImage(thumbW, THUMB_H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = t.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(im, 0, 0, thumbW, THUMB_H, null);
                g.dispose();
                thumbs.put(i, t);
            }
            return t;
        }
    }

    /** V: attach a video at the playhead. Its audio track (if any) is dropped
     *  on the last track as a reference clip. */
    void attachVideoDialog() {
        if (!haveFfmpeg()) { toast("ffmpeg not found — needed for video (sudo apt install ffmpeg)"); return; }
        JFileChooser fc = new JFileChooser(video != null && video.path.getParent() != null
                ? video.path.getParent().toFile() : DIR.toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "video files", "mp4", "mkv", "webm", "mov", "avi", "gif"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        pushUndo("");
        attachVideo(fc.getSelectedFile().toPath(), playPos, true);
        markEdit();
        showMonitor(true);
    }

    void attachVideo(Path path, double start, boolean addRefAudio) {
        VideoRef v = new VideoRef(path, start);
        video = v;
        new Thread(() -> prepareVideo(v, addRefAudio), "video-prep").start();
    }

    void prepareVideo(VideoRef v, boolean addRefAudio) {
        try {
            if (!Files.exists(v.path)) { v.status = "file not found"; toast("video not found: " + v.path); return; }
            if (!haveFfmpeg()) { v.status = "ffmpeg not found"; toast("ffmpeg not found — needed for video"); return; }
            String base = v.path.getFileName().toString().replaceFirst("\\.[^.]+$", "").replace(' ', '-');
            int hash = Objects.hash(v.path.toAbsolutePath().toString(), Files.size(v.path),
                                    Files.getLastModifiedTime(v.path).toMillis());
            Path dir = VIDEO_CACHE.resolve(base + "-" + Integer.toHexString(hash));
            Path meta = dir.resolve("meta.txt");
            if (!Files.exists(meta)) {
                v.status = "extracting frames…";
                toast("extracting video frames (first time only)…");
                Files.createDirectories(dir);
                double fps = 30;
                try {   // keep the native rate up to 30 fps; faster recordings are thinned
                    String[] r = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                            "stream=r_frame_rate", "-of", "csv=p=0", v.path.toString()).trim().split("/");
                    double nat = r.length == 2 ? Double.parseDouble(r[0]) / Double.parseDouble(r[1]) : Double.parseDouble(r[0]);
                    if (nat > 1 && nat < 30) fps = nat;
                } catch (Exception ignore) {}
                String fpsStr = String.format(Locale.ROOT, "%.3f", fps);
                run("ffmpeg", "-v", "error", "-y", "-i", v.path.toString(), "-t", String.valueOf(VIDEO_MAX_SECS),
                    "-vf", "fps=" + fpsStr + ",scale=640:-2", "-q:v", "4", dir.resolve("f%05d.jpg").toString());
                int n;
                try (var st = Files.list(dir)) { n = (int) st.filter(p -> p.getFileName().toString().endsWith(".jpg")).count(); }
                if (n == 0) throw new IOException("no frames extracted");
                Files.writeString(meta, fpsStr + " " + n + "\n");
            }
            String[] m = Files.readString(meta).trim().split("\\s+");
            v.fps = Double.parseDouble(m[0]); v.frames = Integer.parseInt(m[1]); v.dir = dir; v.status = null;
            toast(String.format(Locale.ROOT, "video ready: %.1fs at %.2f fps — M opens the monitor, [ ] step frames", v.dur(), v.fps));
            if (addRefAudio) {
                Path wav = SAMPLE_DIR.resolve(base + "-ref.wav");
                if (!Files.exists(wav)) {
                    Files.createDirectories(SAMPLE_DIR);
                    try {
                        run("ffmpeg", "-v", "error", "-y", "-i", v.path.toString(), "-vn", "-t", String.valueOf(VIDEO_MAX_SECS),
                            "-ac", "2", "-ar", String.valueOf(SR), "-c:a", "pcm_s16le", wav.toString());
                    } catch (Exception noAudio) { Files.deleteIfExists(wav); }
                }
                if (Files.exists(wav)) {
                    String fn = wav.getFileName().toString();
                    SwingUtilities.invokeLater(() -> {
                        Clip c = addSampleClip(fn, TRACKS - 1, v.start);
                        if (c != null) {
                            c.vlink = true;
                            toast("video's audio on track " + TRACKS + ", linked to the picture — mute it (click the "
                                    + TRACKS + ") or delete it");
                        }
                    });
                }
            }
        } catch (Exception e) {
            v.status = "failed: " + e.getMessage();
            toast("video failed: " + e.getMessage());
        }
    }

    void showVideoMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem att = new JMenuItem(video == null ? "attach video…  (V)" : "replace video…  (V)");
        att.addActionListener(ev -> attachVideoDialog());
        m.add(att);
        if (video != null) {
            JMenuItem mon = new JMenuItem("monitor window  (M)");
            mon.addActionListener(ev -> showMonitor(true));
            JMenuItem here = new JMenuItem("start the video at the playhead");
            here.addActionListener(ev -> { pushUndo(""); moveVideoBy(playPos - video.start); markEdit(); });
            JMenuItem off = new JMenuItem("type the video start…");
            off.addActionListener(ev -> {
                String in = (String) JOptionPane.showInputDialog(this,
                        "Video starts at (seconds; negative = the recording began before 0):",
                        "Video start", JOptionPane.PLAIN_MESSAGE, null, null, String.format(Locale.ROOT, "%.3f", video.start));
                if (in == null) return;
                try { pushUndo(""); moveVideoBy(Double.parseDouble(in.trim()) - video.start); markEdit(); }
                catch (NumberFormatException ex) { toast("couldn't parse \"" + in + "\""); }
            });
            JMenuItem rm = new JMenuItem("remove video");
            rm.addActionListener(ev -> { pushUndo(""); video = null; markEdit(); toast("video removed (ctrl+Z undoes)"); });
            m.add(mon); m.add(here); m.add(off);
            if (sel != null) {
                JMenuItem ln = new JMenuItem((sel.vlink ? "unlink \"" : "link \"") + sel.name + "\" " + (sel.vlink ? "from" : "to") + " the video");
                ln.addActionListener(ev -> {
                    pushUndo(""); sel.vlink = !sel.vlink; markEdit();
                    toast(sel.vlink ? sel.name + " now moves with the video" : sel.name + " unlinked");
                });
                m.add(ln);
            }
            m.addSeparator(); m.add(rm);
        }
        m.show(this, actRect(0).x, paletteY() + 26);
    }

    /** Moves the video and every clip linked to it by delta seconds. */
    void moveVideoBy(double delta) {
        VideoRef v = video;
        if (v != null) v.start += delta;
        synchronized (lock) { for (Clip c : clips) if (c.vlink) c.start += delta; }
    }

    /** How much of a sample clip's recording (as a fraction of its length)
     *  plays in the first `lt` seconds of the clip. Tape mode integrates the
     *  pitch sweep; LFO wobble is ignored. Negative lt extrapolates backwards. */
    static double srcConsumed(Clip c, double lt) {
        int n = sample(c.file)[0].length;
        double speed = c.p[speedIdx(c.type)];
        if (keepLen(c)) return lt * SR * speed / n;
        int steps = 400;
        double dt = lt / steps, acc = 0;
        for (int i = 0; i < steps; i++) {
            double prog = (i + 0.5) * dt / c.dur;
            acc += Math.pow(2, (c.p[P_PITCH] + c.p[P_PSWP] * prog) / 12.0) * speed * dt;
        }
        return acc * SR / n;
    }

    /** Re-bases a linear sweep so that a clip cut at `frac` of its length keeps
     *  the same value curve: the left part gets the first frac of the sweep,
     *  the right part starts where the sweep had got to and finishes it. */
    static void splitSweep(Clip left, Clip right, int base, int swp, double frac, double fracLeft) {
        double s = left.p[swp];
        right.p[base] = left.p[base] + s * frac;
        right.p[swp] = s * (1 - frac);
        left.p[swp] = s * fracLeft;   // fracLeft > frac: the left keeps its slope over its crossfade tail
    }

    static final double XFADE = 0.005;   // crossfade length at a split
    /** X: split the selected clip at the playhead. */
    void splitSel() {
        if (sel == null) { toast("select a clip first"); return; }
        double t = playPos;
        if (t <= sel.start + 0.01 || t >= sel.end() - 0.01) { toast("put the playhead inside the clip to split it"); return; }
        pushUndo("");
        Clip a = sel, b = copyClip(a);
        double frac = (t - a.start) / a.dur, fracL = (t - a.start + XFADE) / a.dur;
        if (sampled(a)) {
            int si = startIdx(a.type);
            double st = a.p[si] + srcConsumed(a, t - a.start);
            b.p[si] = looping(a) ? st - Math.floor(st) : Math.min(1, st);
        }
        splitSweep(a, b, P_PITCH, P_PSWP, frac, fracL);
        splitSweep(a, b, P_CUT, P_CSWP, frac, fracL);
        splitSweep(a, b, P_PAN, P_PANSWP, frac, fracL);
        if (a.type == CLOUD) splitSweep(a, b, NCOMMON + 1, NCOMMON + 6, frac, fracL);
        if (a.type == CHOIR) splitSweep(a, b, NCOMMON + CH_GATHER, NCOMMON + CH_GSWP, frac, fracL);
        b.start = t; b.dur = a.end() - t;
        a.dur = t - a.start + XFADE;                 // the halves overlap by XFADE: the left's
        a.p[P_REL] = XFADE;                          // fade-out and the right's fade-in sum to 1,
        b.p[P_ATT] = XFADE;                          // so the cut is a crossfade, not a notch
        synchronized (lock) { clips.add(b); }
        sel = b;
        markEdit();
        toast(String.format(Locale.ROOT, "split at %.2fs — the right half is selected", t));
    }

    /** Left-edge trim to a new start time: the clip shrinks or grows from the
     *  left while its content stays put (sample position and sweeps re-based). */
    void trimStart(Clip c, double newStart) {
        newStart = Math.min(newStart, c.end() - 0.05);
        double delta = newStart - c.start;
        if (Math.abs(delta) < 1e-9) return;
        double frac = delta / c.dur;
        if (sampled(c)) {
            int si = startIdx(c.type);
            double st = c.p[si] + srcConsumed(c, delta);
            c.p[si] = looping(c) ? st - Math.floor(st) : Math.max(0, Math.min(1, st));
        }
        int[][] sweeps = {{P_PITCH, P_PSWP}, {P_CUT, P_CSWP}, {P_PAN, P_PANSWP}};
        for (int[] sw : sweeps) { c.p[sw[0]] += c.p[sw[1]] * frac; c.p[sw[1]] *= (1 - frac); }
        if (c.type == CLOUD) { c.p[NCOMMON + 1] += c.p[NCOMMON + 6] * frac; c.p[NCOMMON + 6] *= (1 - frac); }
        if (c.type == CHOIR) { c.p[NCOMMON + CH_GATHER] += c.p[NCOMMON + CH_GSWP] * frac; c.p[NCOMMON + CH_GSWP] *= (1 - frac); }
        c.start = newStart; c.dur -= delta;
    }

    // ---- monitor: a separate window showing the frame under the playhead
    JFrame monitor;
    void showMonitor(boolean show) {
        if (monitor == null) {
            JPanel pnl = new JPanel() {
                @Override protected void paintComponent(Graphics g0) {
                    super.paintComponent(g0);
                    paintMonitor((Graphics2D) g0, getWidth(), getHeight());
                }
            };
            pnl.setBackground(Color.BLACK);
            pnl.setPreferredSize(new Dimension(640, 396));
            pnl.setFocusable(true);
            pnl.addKeyListener(new KeyAdapter() { @Override public void keyPressed(KeyEvent e) { handleKey(e); } });
            pnl.addMouseListener(new MouseAdapter() { @Override public void mousePressed(MouseEvent e) { pnl.requestFocusInWindow(); } });
            monitor = new JFrame("SfxLab — video monitor");
            monitor.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            monitor.add(pnl);
            monitor.pack();
            Window top = SwingUtilities.getWindowAncestor(this);
            if (top != null) monitor.setLocation(top.getX() + top.getWidth() - monitor.getWidth() - 20, top.getY() + 60);
        }
        monitor.setVisible(show);
        if (show) monitor.toFront();
    }
    void toggleMonitor() { showMonitor(monitor == null || !monitor.isVisible()); }

    void paintMonitor(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        VideoRef v = video;
        if (v == null) { g.setColor(Color.GRAY); g.drawString("no video — V attaches one", 14, 24); return; }
        int idx = v.frameAt(playPos);
        BufferedImage im = v.frame(idx);
        int ih = h - 22;
        if (im != null) {
            double sc = Math.min(w / (double) im.getWidth(), ih / (double) im.getHeight());
            int dw = (int) (im.getWidth() * sc), dh = (int) (im.getHeight() * sc);
            g.drawImage(im, (w - dw) / 2, (ih - dh) / 2, dw, dh, null);
        } else {
            g.setColor(Color.GRAY);
            String s = v.status != null ? v.status
                     : idx < 0 ? String.format(Locale.ROOT, "video starts at %.2fs", v.start) : "past the end of the video";
            g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, ih / 2);
        }
        g.setColor(new Color(90, 255, 190));
        String tc = String.format(Locale.ROOT, "%7.3fs   frame %d / %d   video %.3fs", playPos, idx, v.frames, playPos - v.start);
        g.drawString(tc, 10, h - 7);
        g.setColor(Color.GRAY);
        String hint = "SPACE play · [ ] step · K marker";
        int hw = g.getFontMetrics().stringWidth(hint);
        if (w - hw - 10 > g.getFontMetrics().stringWidth(tc) + 30) g.drawString(hint, w - hw - 10, h - 7);
    }

    /** [ / ]: move the playhead one video frame (50 ms when there's no video). */
    void stepFrame(int d) {
        VideoRef v = video;
        playing = false; solo = null;
        if (v != null && v.frames > 0) {
            int idx = v.frameAt(playPos) + d;
            seekTo = Math.max(0, v.start + idx / v.fps);
        } else seekTo = Math.max(0, playPos + d * 0.05);
    }

    // ---- markers
    void addMarker() {
        String name = (String) JOptionPane.showInputDialog(this, String.format(Locale.ROOT, "Marker name at %.3fs:", playPos),
                "Add marker", JOptionPane.PLAIN_MESSAGE, null, null, "m" + (markers.size() + 1));
        if (name == null) return;
        name = name.trim().replace(' ', '-');
        if (name.isEmpty()) name = "m" + (markers.size() + 1);
        pushUndo("");
        markers.add(new Marker(playPos, name));
        markers.sort(Comparator.comparingDouble(m -> m.t));
        markEdit();
        toast("marker " + name + " — clip drags snap to it");
    }

    Marker nearestMarker(double t, double maxDist) {
        Marker best = null;
        for (Marker m : markers)
            if (Math.abs(m.t - t) <= maxDist && (best == null || Math.abs(m.t - t) < Math.abs(best.t - t))) best = m;
        return best;
    }

    void removeMarker(Marker m) {
        if (m == null) { toast("no marker there"); return; }
        pushUndo("");
        markers.remove(m);
        markEdit();
        toast("removed marker " + m.name);
    }

    // ---- sync to mod: the mod's own tools/sync_sfxlab.sh, run from here (the regulator panel's button). It checks the
    // saved family files, bakes what they play, and copies the runtime, the families and the bake into the mod. What
    // is synced is what is SAVED: edits still in a family's working copy (not yet S) stay there, untouched. Nothing
    // is committed in either repository, and the game reads the result at its next launch.
    static final String SYNC_SCRIPT = "tools/sync_sfxlab.sh";
    boolean syncing;
    /** The mod checkout: the remembered one, else one found above the forge's mirror folder, else asked for. */
    Path findMod() {
        if (!modDir.isEmpty() && Files.exists(Paths.get(modDir).resolve(SYNC_SCRIPT))) return Paths.get(modDir);
        if (!forgeMirror.isEmpty()) for (Path p = Paths.get(forgeMirror).toAbsolutePath(); p != null; p = p.getParent()) if (Files.exists(p.resolve(SYNC_SCRIPT))) return p;
        JFileChooser fc = new JFileChooser(DIR.getParent() != null ? DIR.getParent().toFile() : DIR.toFile());
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("The mod's folder (the one holding " + SYNC_SCRIPT + ")");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        Path p = fc.getSelectedFile().toPath();
        if (!Files.exists(p.resolve(SYNC_SCRIPT))) { toast("no " + SYNC_SCRIPT + " in " + p); return null; }
        return p;
    }
    void syncToMod() {
        if (syncing) { toast("a sync is already running"); return; }
        Path mod = findMod();
        if (mod == null) return;
        if (!mod.toString().equals(modDir)) { modDir = mod.toString(); saveCfg(); }
        JTextArea out = new JTextArea(22, 100);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12)); out.setEditable(false);
        JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), "Sync to mod — " + mod);
        dlg.add(new JScrollPane(out)); dlg.pack(); dlg.setLocationRelativeTo(this); dlg.setVisible(true);
        out.append("syncing what is saved (S) in " + DIR + "\ninto " + mod + "\n\n");
        syncing = true;
        Thread t = new Thread(() -> {
            String verdict;
            try {
                ProcessBuilder pb = new ProcessBuilder("bash", SYNC_SCRIPT).directory(mod.toFile()).redirectErrorStream(true);
                pb.environment().put("SFXLAB_SRC", DIR.toAbsolutePath().toString());
                Process p = pb.start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String l; (l = r.readLine()) != null; ) { String line = l; SwingUtilities.invokeLater(() -> { out.append(line + "\n"); out.setCaretPosition(out.getDocument().getLength()); }); }
                }
                int code = p.waitFor();
                verdict = code == 0 ? "\nSYNCED. Nothing was committed in either repository; the game picks this up at its next launch.\n"
                                    : "\nNOT SYNCED (exit " + code + "): the lines above say why. The mod is as it was unless the copying had begun.\n";
            } catch (Exception e) { verdict = "\nNOT SYNCED: " + e + "\n"; }
            String v = verdict;
            SwingUtilities.invokeLater(() -> { out.append(v); out.setCaretPosition(out.getDocument().getLength()); syncing = false; });
        }, "sync-to-mod");
        t.setDaemon(true); t.start();
    }

    // ---- lab.cfg: export settings
    void loadCfg() {
        try {
            if (!Files.exists(CFG_FILE)) return;
            for (String l : Files.readAllLines(CFG_FILE)) {
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String k = l.substring(0, eq).trim(), v = l.substring(eq + 1).trim();
                switch (k) {
                    case "export_dir" -> exportDir = v;
                    case "forge_mirror" -> forgeMirror = v;
                    case "mod_dir" -> modDir = v;
                    case "browser" -> browserOn = v.equals("1");
                    case "bench" -> benchOn = v.equals("1");
                    case "bpanel" -> bpanelOn = v.equals("1");
                    case "bench_name" -> benchName = v.isEmpty() ? null : v;
                    case "family" -> family = v.isEmpty() ? null : v;
                    case "bpanel_fold" -> panelFold = v;
                    case "export_ogg" -> expOgg = v.equals("1");
                    case "export_mono" -> expMono = v.equals("1");
                    case "export_norm" -> expNorm = v.equals("1");
                    case "export_trim" -> expTrim = v.equals("1");
                    case "mono_out" -> monoOut = v.equals("1");
                }
            }
        } catch (Exception e) { System.err.println("cfg load failed: " + e); }
    }
    void saveCfg() {
        try {
            Files.createDirectories(DIR);
            Files.writeString(CFG_FILE, String.format("export_dir=%s%nexport_ogg=%d%nexport_mono=%d%nexport_norm=%d%nexport_trim=%d%nforge_mirror=%s%nbrowser=%d%nbench=%d%nbpanel=%d%nbench_name=%s%nfamily=%s%nbpanel_fold=%s%nmono_out=%d%nmod_dir=%s%n",
                    exportDir, expOgg ? 1 : 0, expMono ? 1 : 0, expNorm ? 1 : 0, expTrim ? 1 : 0, forgeMirror, browserOn ? 1 : 0,
                    benchOn ? 1 : 0, bpanelOn ? 1 : 0, benchName != null ? benchName : "", family != null ? family : "", panelFold, monoOut ? 1 : 0, modDir));
        } catch (IOException e) { toast("cfg save failed: " + e); }
    }

    void showLibraryMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem imp = new JMenuItem("import sample file…  (W)   wav/aiff, or mp3/ogg/video via ffmpeg");
        imp.addActionListener(ev -> importSample());
        m.add(imp);
        m.addSeparator();
        if (library.isEmpty()) {
            JMenuItem it = new JMenuItem("library is empty — select a clip and press B");
            it.setEnabled(false);
            m.add(it);
        } else {
            for (Clip e : library) {
                JMenuItem it = new JMenuItem(String.format(Locale.ROOT, "%s   (%s, %.2fs)",
                        e.name, TYPE_NAMES[e.type], e.dur));
                it.addActionListener(ev -> insertFromLibrary(e));
                m.add(it);
            }
            m.addSeparator();
            JMenu manage = new JMenu("manage");
            for (Clip e : library) {
                JMenu sub = new JMenu(e.name);
                JMenuItem rn = new JMenuItem("rename…");
                rn.addActionListener(ev -> renameLibraryEntry(e));
                JMenuItem del = new JMenuItem("delete");
                del.addActionListener(ev -> deleteLibraryEntry(e));
                sub.add(rn);
                sub.add(del);
                manage.add(sub);
            }
            m.add(manage);
        }
        m.show(this, actRect(1).x, paletteY() + 26);
    }

    /** Autosave of the workspace — always project.sfx, never a named stamp. */
    void saveProject(boolean quiet) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(PROJECT_FILE, projectText());
            dirty = false;
            if (!quiet) toast("workspace autosaved — S stamps it to a named .sfx");
        } catch (Exception e) { toast("save failed: " + e); }
    }

    /** S: the one save. Stamps the current timeline into a named .sfx copy. */
    void stampProject() {
        String def = lastStampName != null ? lastStampName.replaceFirst("\\.sfx$", "") : "";
        String name = (String) JOptionPane.showInputDialog(this, "Save timeline as (in projects/):",
                "Save", JOptionPane.PLAIN_MESSAGE, null, null, def);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;
        if (!name.toLowerCase(Locale.ROOT).endsWith(".sfx")) name += ".sfx";
        if (name.equals(PROJECT_FILE.getFileName().toString())) {
            toast("that's the workspace file — pick another name");
            return;
        }
        Path f = PROJECTS_DIR.resolve(name);
        // re-stamping the last-used name is plain "save"; a different existing
        // name is a real overwrite and asks first
        if (Files.exists(f) && !name.equals(lastStampName) && JOptionPane.showConfirmDialog(this,
                name + " exists — overwrite?", "Save", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION)
            return;
        try {
            Files.createDirectories(PROJECTS_DIR);
            Files.writeString(f, projectText());
            lastStampName = name;
            toast("saved " + name);
        } catch (Exception e) { toast("save failed: " + e); }
    }

    /** Loads a .sfx into the workspace. The named file itself is never edited
     *  in place — S re-stamps it. Undoable, so ctrl+Z restores the old timeline. */
    void loadProjectFile(Path f) {
        try {
            boolean[] lp = new boolean[1];
            double[] tv = new double[TRACKS];
            Arrays.fill(tv, 1.0);
            boolean[] mu = new boolean[TRACKS];
            List<Clip> cs = parseProject(f, lp, tv, mu);
            pushUndo("");
            playing = false; solo = null;
            synchronized (lock) { clips.clear(); clips.addAll(cs); }
            System.arraycopy(tv, 0, trackVol, 0, TRACKS);
            System.arraycopy(mu, 0, mute, 0, TRACKS);
            loopOn = lp[0];
            loadExtras(f);
            sel = null; seekTo = 0;
            String fn = f.getFileName().toString();
            if (!fn.equals(PROJECT_FILE.getFileName().toString())) lastStampName = fn;
            markEdit();
            toast("opened " + fn + " into the workspace (" + cs.size() + " clips)");
        } catch (Exception e) { toast("open failed: " + e); }
    }

    void openProject() {
        JFileChooser fc = new JFileChooser((Files.isDirectory(PROJECTS_DIR) ? PROJECTS_DIR : DIR).toFile());
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("SfxLab projects (*.sfx)", "sfx"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            loadProjectFile(fc.getSelectedFile().toPath());
    }

    /** N: clear the workspace for the next sound. Undoable, so no confirm nag. */
    void clearWorkspace() {
        pushUndo("");
        playing = false; solo = null;
        synchronized (lock) { clips.clear(); }
        Arrays.fill(trackVol, 1.0);
        Arrays.fill(mute, false);
        markers.clear(); video = null;
        loopOn = false; sel = null; seekTo = 0;
        rootHz = ROOT_DEFAULT;
        lastStampName = null;
        markEdit();
        toast("workspace cleared (ctrl+Z undoes)");
    }

    static List<Clip> parseProject(Path f, boolean[] loopOut, double[] tvolOut, boolean[] muteOut) throws IOException {
        return SfxFormat.parseProject(Files.readAllLines(f), loopOut, tvolOut, muteOut);
    }

    /** A small starter arrangement so an empty install makes a sound: a spell impact. */
    void demoProject() {
        Clip pad = addPal(0, 4, 0.0); pad.dur = 2.6; pad.p[P_ECHO] = 0.4; pad.p[P_REL] = 1.2; pad.p[P_LEVEL] = 0.5;
        addPal(7, 0, 0.0);                                        // woosh in
        addPal(6, 1, 0.85);                                       // zap at the impact
        Clip spk = addPal(8, 2, 0.9); spk.p[P_ECHO] = 0.3;        // sparkle shower
        Clip dwn = addPal(5, 3, 0.95); dwn.p[P_LEVEL] = 0.5;      // downer tail
        toast("demo project — SPACE plays it");
    }
    Clip addPal(int palIdx, int track, double start) {
        Clip c = fromPal(PALETTE[palIdx], track, start);
        synchronized (lock) { clips.add(c); }
        return c;
    }

    // =====================================================================
    // Combos bridge: the spells inscribed in the old game become clip groups.
    // The old entangled params are unpacked into the new modular ones.
    // =====================================================================
    void loadCombos() {
        try {
            if (!Files.exists(COMBO_FILE)) return;
            for (String line : Files.readAllLines(COMBO_FILE)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] t = line.split("\\s+");
                if (t.length < 11) continue;
                double[] c = new double[13];
                for (int i = 0; i < 11; i++) c[i] = Double.parseDouble(t[i]);
                c[11] = t.length > 12 ? Double.parseDouble(t[11]) : 0;
                c[12] = t.length > 12 ? Double.parseDouble(t[12]) : 0;
                combos.add(c);
            }
        } catch (Exception e) { System.err.println("combo load failed: " + e); }
    }

    void cycleCombo(int d) {
        if (combos.isEmpty()) { toast("no combos.txt entries found"); return; }
        comboIdx = comboIdx < 0 ? (d > 0 ? 0 : combos.size() - 1)
                 : ((comboIdx + d) % combos.size() + combos.size()) % combos.size();
        toast(String.format("combo %d/%d selected — I inserts it at the playhead", comboIdx + 1, combos.size()));
    }

    void insertCombo() {
        if (combos.isEmpty()) { toast("no combos.txt entries found"); return; }
        if (comboIdx < 0) comboIdx = 0;
        double[] cb = combos.get(comboIdx);
        double g = cb[0], st = cb[1], pu = cb[2], fo = cb[3], pit = cb[4];
        int chord = (int) Math.max(0, Math.min(2, cb[5]));
        double bLog = cb[6], bMor = cb[7], bPit = cb[8], reso = cb[11], echo = cb[12];
        double t0 = playPos;
        // old filter mapped focus over 150..9000 Hz; translate to the new cutoff control
        double u = Math.max(0, Math.min(1, Math.log(150 * Math.pow(60, fo) / 40) / Math.log(250)));
        String base = "combo" + (comboIdx + 1);

        Clip cloud = fromPal(PALETTE[0], 0, t0);
        cloud.name = base; cloud.dur = 3;
        cloud.p[P_PITCH] = -12 + pit;
        cloud.p[NCOMMON + 1] = g;                      // gather
        cloud.p[NCOMMON + 2] = Math.max(0, 1 - st);    // drift = the old instability
        cloud.p[NCOMMON + 3] = (g + st + pu) / 3;      // timbre (the old derived morph)
        cloud.p[NCOMMON + 4] = chord;
        cloud.p[NCOMMON + 5] = 0.35;
        cloud.p[P_CUT] = u; cloud.p[P_RES] = reso; cloud.p[P_ECHO] = echo; cloud.p[P_REL] = 0.8;

        Clip tones = fromPal(PALETTE[1], 1, t0);
        tones.name = base + "-b"; tones.dur = 3;
        tones.p[P_PITCH] = pit + bPit;                 // B rode an octave above A's 55 Hz base
        tones.p[NCOMMON] = bLog / Math.log(2) * 12;
        tones.p[NCOMMON + 1] = bMor;
        tones.p[P_LEVEL] = 0.4; tones.p[P_ECHO] = echo; tones.p[P_REL] = 0.8;

        double nAmt = (1 - pu) * 0.7 * (1 - 0.45 * fo); // the void, unpacked from purity
        pushUndo("");
        synchronized (lock) {
            clips.add(cloud); clips.add(tones);
            if (nAmt > 0.03) {
                Clip nz = fromPal(PALETTE[3], 2, t0);
                nz.name = base + "-n"; nz.dur = 3;
                nz.p[P_LEVEL] = nAmt; nz.p[P_CUT] = u; nz.p[P_RES] = reso;
                nz.p[P_ECHO] = echo; nz.p[P_REL] = 0.8;
                clips.add(nz);
            }
        }
        sel = cloud;
        markEdit();
        toast(String.format(Locale.ROOT, "combo %d/%d inserted at %.2fs", comboIdx + 1, combos.size(), t0));
    }

    // =====================================================================
    // Realtime audio
    // =====================================================================
    void audioLoop() throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(SR, 16, 2, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(fmt);
        line.open(fmt, BLOCK * 4 * 12);   // ~70 ms: rides out GC pauses and background analyses (was 23 ms, which dropped out)
        line.start();
        long startedAt = System.currentTimeMillis();
        byte[] buf = new byte[BLOCK * 4];
        Engine eng = new Engine();
        double[] bufL = new double[BLOCK], bufR = new double[BLOCK];
        List<Clip> snap = new ArrayList<>();
        // Master fade: the output ramps over FADE_S around every start, pause and seek instead of cutting mid-cycle.
        // A pause keeps rendering until the ramp is down; a seek waits for the ramp to be down before it clears the
        // voices, then the restart ramps back up. Bench layers are endless, so this is their only attack / release.
        double gain = 0;
        final double gStep = 1.0 / (FADE_S * SR);

        while (true) {
            boolean bm = benchOn;   // bench: every layer sounds, time never wraps, signals set the modulation targets
            double sk = seekTo;
            boolean seekWait = sk >= 0 && gain > gStep;   // still audible: ramp down first, seek next block
            if (sk >= 0 && !seekWait) { seekTo = -1; eng.t = sk; eng.voices.clear(); transients.clear(); gain = 0; }
            snap.clear();
            Clip so = null;
            boolean pl;
            double end;
            if (bm) { benchLive(eng.t, snap); pl = benchPlaying; end = Double.MAX_VALUE; }
            else {
                synchronized (lock) { snap.addAll(clips); }
                so = solo; pl = playing;
                end = so != null ? so.end() + 1.0 : timelineEnd(snap) + (loopOn ? 0 : 1.0);
            }
            eng.voices.keySet().removeIf(c -> !snap.contains(c) || eng.t < c.start || eng.t >= c.end());
            eng.key = keyOff;

            double gTarget = pl && !seekWait ? 1 : 0;
            boolean render = pl || gain > 0;   // a stopped transport keeps rendering only while the fade-out is audible
            if (render) eng.renderBlock(snap, so, bm ? null : mute, bm ? null : trackVol, bufL, bufR, BLOCK);
            for (int i = 0; i < BLOCK; i++) {
                gain = gTarget > gain ? Math.min(1, gain + gStep) : Math.max(0, gain - gStep);
                double l = render ? bufL[i] * gain : 0, r = render ? bufR[i] * gain : 0;
                if (monoOut) { double m = (l + r) * 0.5; l = m; r = m; }   // the game's positional sources are mono
                scopeL[scopePos] = (float) l; scopeR[scopePos] = (float) r;
                scopePos = (scopePos + 1) % scopeL.length;
                // clamp: the limiter keeps this under 0.85, but an unclamped cast would wrap past full scale
                int sl = (int) Math.max(-32768, Math.min(32767, l * 32767)), sr = (int) Math.max(-32768, Math.min(32767, r * 32767));
                buf[i * 4] = (byte) sl; buf[i * 4 + 1] = (byte) (sl >> 8);
                buf[i * 4 + 2] = (byte) sr; buf[i * 4 + 3] = (byte) (sr >> 8);
            }
            if (pl && eng.t >= end) {   // the end is now checked per block (5.8 ms), not per sample
                if (so == null && loopOn) { eng.t = 0; eng.voices.clear(); }
                else { playing = false; solo = null; }
            }
            playPos = eng.t;
            if (eng.inPeak > 1) clipAt = System.currentTimeMillis();
            eng.inPeak = 0;
            if (pl && line.available() >= line.getBufferSize() && System.currentTimeMillis() - startedAt > 500) xrunAt = System.currentTimeMillis();   // the buffer ran dry: a dropout
            line.write(buf, 0, buf.length);
        }
    }

    // =====================================================================
    // Offline export (also the CLI --render path)
    // =====================================================================
    /** E: render the mix to a file. The options are remembered, so once the
     *  folder points at the mod's sounds directory and ogg+mono are ticked,
     *  export is name → OK → the file is in place. */
    void exportWav() {
        String def = lastStampName != null ? lastStampName.replaceFirst("\\.sfx$", "") : "sfx-" + System.currentTimeMillis();
        JTextField nameF = new JTextField(def, 26);
        JTextField dirF = new JTextField(DIR.resolve(exportDir).toString(), 26);
        JButton browse = new JButton("…");
        browse.addActionListener(ev -> {
            JFileChooser fc = new JFileChooser(dirF.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) dirF.setText(fc.getSelectedFile().getPath());
        });
        JCheckBox ogg = new JCheckBox("ogg (vorbis) — what Minecraft loads", expOgg && haveFfmpeg());
        ogg.setEnabled(haveFfmpeg());
        JCheckBox mono = new JCheckBox("mono — required for positional (in-world) sounds", expMono);
        JCheckBox norm = new JCheckBox("normalize peak to -1 dBFS", expNorm);
        JCheckBox trim = new JCheckBox("trim the silent tail", expTrim);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST; gc.insets = new Insets(2, 4, 2, 4);
        gc.gridy = 0; gc.gridx = 0; form.add(new JLabel("name"), gc);
        gc.gridx = 1; gc.gridwidth = 2; form.add(nameF, gc);
        gc.gridy = 1; gc.gridx = 0; gc.gridwidth = 1; form.add(new JLabel("folder"), gc);
        gc.gridx = 1; form.add(dirF, gc);
        gc.gridx = 2; form.add(browse, gc);
        gc.gridx = 1; gc.gridwidth = 2;
        gc.gridy = 2; form.add(ogg, gc);
        gc.gridy = 3; form.add(mono, gc);
        gc.gridy = 4; form.add(norm, gc);
        gc.gridy = 5; form.add(trim, gc);
        if (JOptionPane.showConfirmDialog(this, form, "Export mix", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String name = nameF.getText().trim();
        if (name.isEmpty()) name = def;
        name = name.replaceFirst("(?i)\\.(wav|ogg)$", "");
        expOgg = ogg.isSelected(); expMono = mono.isSelected(); expNorm = norm.isSelected(); expTrim = trim.isSelected();
        exportDir = dirF.getText().trim().isEmpty() ? "renders" : dirF.getText().trim();
        saveCfg();
        Path outFile = DIR.resolve(exportDir).resolve(name + (expOgg ? ".ogg" : ".wav"));
        if (Files.exists(outFile) && JOptionPane.showConfirmDialog(this, outFile.getFileName() + " exists — overwrite?",
                "Export", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        List<Clip> snap;
        synchronized (lock) { snap = new ArrayList<>(clips); }
        double[] tv = trackVol.clone();
        boolean[] mu = mute.clone();
        boolean fOgg = expOgg, fMono = expMono, fNorm = expNorm, fTrim = expTrim;
        new Thread(() -> {
            try {
                Files.createDirectories(outFile.getParent());
                double peak = renderFile(snap, tv, mu, outFile, fOgg, fMono, fNorm, fTrim, keyOff);
                toast(String.format(Locale.ROOT, "exported %s (peak %.2f) → %s", outFile.getFileName(), peak, outFile.getParent()));
            } catch (Exception e) { toast("export failed: " + e); }
        }, "export").start();
    }

    /** Renders what you hear: track volumes and mutes are honored. */
    static double renderWav(List<Clip> cs, double[] tvol, boolean[] mute, Path outFile) throws Exception {
        return renderFile(cs, tvol, mute, outFile, false, false, false, false, 0);
    }

    /** Full export: stereo or mono mixdown, optional peak normalize to -1 dBFS,
     *  optional trim of the silent tail (below -60 dBFS, keeping 50 ms), and
     *  .ogg via ffmpeg/libvorbis. Returns the peak as written. */
    static double renderFile(List<Clip> cs, double[] tvol, boolean[] mute, Path outFile,
                             boolean ogg, boolean mono, boolean normalize, boolean trim, double key) throws Exception {
        double end = timelineEnd(cs) + 1.5;   // room for release + echo tail
        if (outFile.toAbsolutePath().getParent() != null) Files.createDirectories(outFile.toAbsolutePath().getParent());
        int total = (int) (end * SR);
        double[] mix = new double[total * 2];
        Engine e = new Engine();
        e.key = key;
        for (Clip c : cs) if (c.type == PARTIALS && c.file != null) partials(c, true);
        double[] bl = new double[BLOCK], br = new double[BLOCK];
        double peak = 0;
        int last = 0;
        for (int i = 0; i < total; i += BLOCK) {   // blocks: the clips render in parallel, bit-identical to sample by sample
            if ((i & 1023) == 0) e.voices.keySet().removeIf(c -> e.t < c.start || e.t >= c.end());
            int n = Math.min(BLOCK, total - i);
            e.renderBlock(cs, null, mute, tvol, bl, br, n);
            for (int k = 0; k < n; k++) {
                double a = Math.max(Math.abs(bl[k]), Math.abs(br[k]));
                peak = Math.max(peak, a);
                if (a > 0.001) last = i + k;
                mix[(i + k) * 2] = bl[k]; mix[(i + k) * 2 + 1] = br[k];
            }
        }
        if (trim) total = Math.min(total, last + SR / 20);
        double gain = normalize && peak > 1e-6 ? 0.891 / peak : 1;
        int ch = mono ? 1 : 2;
        byte[] data = new byte[total * 2 * ch];
        for (int i = 0; i < total; i++) {
            if (mono) {
                int s = (int) Math.max(-32768, Math.min(32767, (mix[i * 2] + mix[i * 2 + 1]) * 0.5 * gain * 32767));
                data[i * 2] = (byte) s; data[i * 2 + 1] = (byte) (s >> 8);
            } else {
                int sl = (int) Math.max(-32768, Math.min(32767, mix[i * 2] * gain * 32767));
                int sr = (int) Math.max(-32768, Math.min(32767, mix[i * 2 + 1] * gain * 32767));
                data[i * 4] = (byte) sl; data[i * 4 + 1] = (byte) (sl >> 8);
                data[i * 4 + 2] = (byte) sr; data[i * 4 + 3] = (byte) (sr >> 8);
            }
        }
        AudioFormat fmt = new AudioFormat(SR, 16, ch, true, false);
        Path wav = ogg ? Files.createTempFile("sfxlab-", ".wav") : outFile;
        AudioSystem.write(new AudioInputStream(new ByteArrayInputStream(data), fmt, total),
                AudioFileFormat.Type.WAVE, wav.toFile());
        if (ogg) {
            try { run("ffmpeg", "-v", "error", "-y", "-i", wav.toString(), "-c:a", "libvorbis", "-q:a", "6", outFile.toString()); }
            finally { Files.deleteIfExists(wav); }
        }
        return peak * gain;
    }

    // =====================================================================
    // UI
    // =====================================================================
    int tlX() { return 56; }
    int paletteY() { return 40; }
    int rulerY() { return 74; }
    int rulerH() { return 30; }                                   // tick row + marker row
    int videoY() { return rulerY() + rulerH(); }
    int videoH() { return video != null ? VIDEO_H : 0; }
    int tracksY() { return videoY() + videoH(); }
    int trackH() { return 44; }
    int panelY() { return tracksY() + TRACKS * trackH() + 10; }
    int xOf(double t) { return tlX() + (int) Math.round((t - scroll) * pps); }
    double tOf(int x) { return Math.max(0, tOfRaw(x)); }
    double tOfRaw(int x) { return scroll + (x - tlX()) / pps; }   // may be negative: clips can hang off the left

    Rectangle palRect(int i) { return new Rectangle(12 + i * 70, paletteY(), 66, 24); }
    static final String[] ACTIONS = {"video", "library", "+ lib", "preview", "export", "save"};
    Rectangle actRect(int i) { return new Rectangle(getWidth() - (actions().length - i) * 74 - 12, paletteY(), 68, 24); }
    static final int SLIDER_ROWS = 16;   // partials has 46 params: three columns of 16
    Rectangle sliderRect(int i) {
        int col = i / SLIDER_ROWS, row = i % SLIDER_ROWS;
        return new Rectangle(14 + col * 310 + 92, panelY() + 30 + row * 22, 150, 13);
    }
    Rectangle clipRect(Clip c) {
        int x = xOf(c.start), x2 = xOf(c.end());
        return new Rectangle(x, tracksY() + c.track * trackH() + 3, Math.max(6, x2 - x), trackH() - 6);
    }
    static final Color[] TYPE_COLORS = {
        new Color(90, 255, 190), new Color(255, 215, 120), new Color(150, 170, 255),
        new Color(255, 140, 200), new Color(255, 170, 90), new Color(170, 225, 225),
        new Color(215, 175, 255), new Color(245, 235, 215)
    };

    SfxLab() {
        setPreferredSize(new Dimension(1200, 918));   // 16 slider rows + 6 legend lines
        setBackground(Color.BLACK);
        setFocusable(true);
        loadCombos();
        loadLibrary();
        loadCfg();
        try {
            if (Files.exists(PROJECT_FILE)) {
                boolean[] lp = new boolean[1];
                List<Clip> cs = parseProject(PROJECT_FILE, lp, trackVol, mute);
                synchronized (lock) { clips.addAll(cs); }
                loopOn = lp[0];
                loadExtras(PROJECT_FILE);
            } else demoProject();
        } catch (Exception e) {
            System.err.println("project load failed: " + e);
            toast("project load failed — starting empty");
        }
        try {
            migrateFamilies();
            if (family != null && Files.exists(familyFile(family))) loadFamily(family);   // the family file is the working state
            else {
                family = null;
                if (Files.exists(BENCH_FILE)) {
                    Family fm = parseFamily(Files.readAllLines(BENCH_FILE));
                    installBench(fm.palette()); setSpells(fm.spells());
                    if (benchOn && fm.palette().root > 0) rootHz = fm.palette().root;
                }
            }
        } catch (Exception e) {
            System.err.println("bench load failed: " + e);
            toast("bench load failed — starting with an empty bench");
        }

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { handleKey(e); }
        });
        buildMouse();

        new javax.swing.Timer(33, ev -> {
            if (dirty && System.currentTimeMillis() - lastEditAt > 1200 && !Boolean.getBoolean("sfxlab.noautosave")) saveProject(true);   // -Dsfxlab.noautosave=true: headless tests must not touch project.sfx
            if (benchDirty && System.currentTimeMillis() - lastEditAt > 1200 && !Boolean.getBoolean("sfxlab.noautosave")) saveBench(true);
            repaint();
            if (monitor != null && monitor.isVisible()) monitor.repaint();
        }).start();
    }

    /** All keys, shared by the main panel and the monitor window. */
    void handleKey(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9) { addFromPalette(kc - KeyEvent.VK_1); return; }
        if (kc == KeyEvent.VK_0) { addFromPalette(9); return; }
        if (kc == KeyEvent.VK_H) { if (e.isShiftDown()) sendSelToBench(); else toggleBench(); return; }
        if (kc == KeyEvent.VK_J) { toggleBenchPanel(); return; }
        if (kc == KeyEvent.VK_U) { showMachine(); return; }
        if (kc == KeyEvent.VK_M && e.isShiftDown()) { monoOut = !monoOut; saveCfg(); toast(monoOut ? "mono: the mix as the game plays it (one positional source)" : "stereo"); repaint(); return; }
        if (benchOn) {   // the bench's own bindings; everything timeline-only is inert here
            switch (kc) {
                case KeyEvent.VK_SPACE -> toggleBenchPlay();
                case KeyEvent.VK_ENTER -> stopBench();
                case KeyEvent.VK_P -> previewSel();
                case KeyEvent.VK_S -> { if (e.isShiftDown()) saveFamilyAs(); else saveFamily(); }
                case KeyEvent.VK_O -> { if (e.isShiftDown()) revertFamily(); else openBench(); }
                case KeyEvent.VK_N -> clearBench();
                case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSel();
                case KeyEvent.VK_D -> dupSel();
                case KeyEvent.VK_UP -> selectLayer(-1);
                case KeyEvent.VK_DOWN -> selectLayer(1);
                case KeyEvent.VK_COMMA -> { if (e.isShiftDown()) setKey(keyOff - 1); }
                case KeyEvent.VK_PERIOD -> { if (e.isShiftDown()) setKey(keyOff + 1); }
                case KeyEvent.VK_SLASH -> { if (e.isShiftDown()) setKey(0); }
                case KeyEvent.VK_T -> cycleKeyed();
                case KeyEvent.VK_F -> showForge();
                case KeyEvent.VK_A -> toggleBrowser();
                case KeyEvent.VK_R -> { if (e.isControlDown()) rootDialog(); else if (e.isShiftDown()) tuneDialog(); else tuneSel(0); }
                case KeyEvent.VK_W -> importSample();
                case KeyEvent.VK_C -> { if (!e.isControlDown()) toggleChoir(); }
                case KeyEvent.VK_Z -> { if (e.isControlDown()) { if (e.isShiftDown()) doRedo(); else doUndo(); } }
                case KeyEvent.VK_Y -> { if (e.isControlDown()) doRedo(); }
                case KeyEvent.VK_L, KeyEvent.VK_X, KeyEvent.VK_K, KeyEvent.VK_V, KeyEvent.VK_M, KeyEvent.VK_I, KeyEvent.VK_B, KeyEvent.VK_Q,
                     KeyEvent.VK_E, KeyEvent.VK_G, KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET -> toast("timeline only — H switches back");
            }
            return;
        }
        switch (kc) {
            case KeyEvent.VK_SPACE -> togglePlay();
            case KeyEvent.VK_ENTER -> { playing = false; solo = null; seekTo = 0; }
            case KeyEvent.VK_L -> loopOn = !loopOn;
            case KeyEvent.VK_P -> previewSel();
            case KeyEvent.VK_E -> exportWav();
            case KeyEvent.VK_S -> stampProject();
            case KeyEvent.VK_O -> openProject();
            case KeyEvent.VK_N -> clearWorkspace();
            case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> deleteSel();
            case KeyEvent.VK_D -> dupSel();
            case KeyEvent.VK_X -> splitSel();
            case KeyEvent.VK_LEFT -> nudge(e.isShiftDown() ? -0.1 : -0.01);
            case KeyEvent.VK_RIGHT -> nudge(e.isShiftDown() ? 0.1 : 0.01);
            case KeyEvent.VK_UP -> moveTrack(-1);
            case KeyEvent.VK_DOWN -> moveTrack(1);
            case KeyEvent.VK_COMMA -> { if (e.isShiftDown()) setKey(keyOff - 1); else cycleCombo(-1); }
            case KeyEvent.VK_PERIOD -> { if (e.isShiftDown()) setKey(keyOff + 1); else cycleCombo(1); }
            case KeyEvent.VK_SLASH -> { if (e.isShiftDown()) setKey(0); }
            case KeyEvent.VK_T -> cycleKeyed();
            case KeyEvent.VK_F -> showForge();
            case KeyEvent.VK_A -> toggleBrowser();
            case KeyEvent.VK_R -> { if (e.isControlDown()) rootDialog(); else if (e.isShiftDown()) tuneDialog(); else tuneSel(0); }
            case KeyEvent.VK_I -> insertCombo();
            case KeyEvent.VK_G -> { snapOn = !snapOn; toast(snapOn ? "snap on (50 ms grid)" : "snap off"); }
            case KeyEvent.VK_B -> saveToLibrary();
            case KeyEvent.VK_Q -> showLibraryMenu();
            case KeyEvent.VK_W -> importSample();
            case KeyEvent.VK_C -> { if (!e.isControlDown()) toggleChoir(); }
            case KeyEvent.VK_Z -> { if (e.isControlDown()) { if (e.isShiftDown()) doRedo(); else doUndo(); } }
            case KeyEvent.VK_Y -> { if (e.isControlDown()) doRedo(); }
            case KeyEvent.VK_EQUALS -> zoom(1.25, getWidth() / 2);
            case KeyEvent.VK_MINUS -> zoom(0.8, getWidth() / 2);
            case KeyEvent.VK_V -> attachVideoDialog();
            case KeyEvent.VK_M -> toggleMonitor();
            case KeyEvent.VK_K -> { if (e.isShiftDown()) removeMarker(nearestMarker(playPos, 0.5)); else addMarker(); }
            case KeyEvent.VK_OPEN_BRACKET -> stepFrame(-1);
            case KeyEvent.VK_CLOSE_BRACKET -> stepFrame(1);
        }
    }

    void buildMouse() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                int mx = e.getX(), my = e.getY();
                for (int i = 0; i < PALETTE.length; i++)
                    if (palRect(i).contains(mx, my)) { addFromPalette(i); return; }
                for (int i = 0; i < actions().length; i++)
                    if (actRect(i).contains(mx, my)) {
                        if (benchOn) switch (i) {
                            case 0 -> openBench();
                            case 1 -> saveFamilyAs();
                            case 2 -> newSpell();
                            case 3 -> toggleBenchPanel();
                            case 4 -> toggleBrowser();
                            case 5 -> toggleBench();
                        } else switch (i) {
                            case 0 -> showVideoMenu();
                            case 1 -> showLibraryMenu();
                            case 2 -> saveToLibrary();
                            case 3 -> previewSel();
                            case 4 -> exportWav();
                            case 5 -> stampProject();
                        }
                        return;
                    }
                if (my >= panelY()) {
                    if (sel != null)
                        for (int i = 0; i < sel.p.length; i++) {
                            Rectangle r = sliderRect(i);
                            if (new Rectangle(r.x - 4, r.y - 5, r.width + 8, r.height + 10).contains(mx, my)) {
                                if (SwingUtilities.isRightMouseButton(e) && benchOn) { sliderMenu(i, mx, my); return; }
                                if (SwingUtilities.isRightMouseButton(e) || e.getClickCount() >= 2) {
                                    typeParam(i);
                                    return;
                                }
                                grabUndo();
                                dragMode = DR_SLIDER; dragParam = i; setParam(mx);
                                return;
                            }
                        }
                    return;
                }
                if (benchOn) { if (my >= rulerY()) benchClick(e); return; }
                if (my >= rulerY() && my < videoY()) {
                    if (SwingUtilities.isRightMouseButton(e)) { removeMarker(nearestMarker(tOf(mx), 6 / pps)); return; }
                    dragMode = DR_SEEK; seekTo = tOf(mx); return;
                }
                if (video != null && my >= videoY() && my < tracksY()) {
                    if (e.isControlDown()) { pendingSnap = snapshot(); dragMode = DR_VIDEO; grabOff = tOfRaw(mx) - video.start; }
                    else { dragMode = DR_SEEK; seekTo = tOf(mx); }
                    return;
                }
                if (my >= tracksY() && my < tracksY() + TRACKS * trackH()) {
                    int tr = (my - tracksY()) / trackH();
                    if (mx < tlX()) {
                        // track header: volume bar at the bottom, number = mute toggle
                        if (my - (tracksY() + tr * trackH()) > trackH() - 16) {
                            pendingSnap = snapshot();
                            dragMode = DR_TVOL; dragTrack = tr; setTrackVol(mx);
                        } else {
                            pushUndo("");
                            mute[tr] = !mute[tr];
                            markEdit();
                        }
                        return;
                    }
                    Clip hit = null;
                    synchronized (lock) {
                        for (Clip c : clips) if (c.track == tr && clipRect(c).contains(mx, my)) hit = c;
                    }
                    if (hit != null) {
                        sel = hit; selTrack = tr;
                        pendingSnap = snapshot();
                        Rectangle r = clipRect(hit);
                        if (mx > r.x + r.width - 8) dragMode = DR_SIZE;
                        else if (r.width > 20 && r.x >= tlX() && mx < r.x + 8) dragMode = DR_TRIM;
                        else { dragMode = DR_MOVE; grabOff = tOfRaw(mx) - hit.start; }
                    } else {
                        sel = null; selTrack = tr;
                        dragMode = DR_SEEK; seekTo = tOf(mx);
                    }
                }
            }
            @Override public void mouseDragged(MouseEvent e) {
                int mx = e.getX(), my = e.getY();
                switch (dragMode) {
                    case DR_SEEK -> seekTo = tOf(mx);
                    case DR_MOVE -> {
                        if (sel == null) return;
                        commitPending();
                        double ns = snap(tOfRaw(mx) - grabOff, e);
                        if (sel.vlink) moveVideoBy(ns - sel.start);   // linked: the picture comes along
                        else sel.start = ns;
                        sel.track = Math.max(0, Math.min(TRACKS - 1, (my - tracksY()) / trackH()));
                        markEdit();
                    }
                    case DR_SIZE -> {
                        if (sel == null) return;
                        commitPending();
                        sel.dur = Math.max(0.05, snap(tOfRaw(mx), e) - sel.start);
                        markEdit();
                    }
                    case DR_TRIM -> {
                        if (sel == null) return;
                        commitPending();
                        trimStart(sel, snap(tOfRaw(mx), e));
                        markEdit();
                    }
                    case DR_SLIDER -> setParam(mx);
                    case DR_LEVEL -> setLevel(mx);
                    case DR_TVOL -> setTrackVol(mx);
                    case DR_VIDEO -> {
                        VideoRef v = video;
                        if (v == null) return;
                        commitPending();
                        moveVideoBy(snap(tOfRaw(mx) - grabOff, e) - v.start);
                        markEdit();
                    }
                }
            }
            @Override public void mouseReleased(MouseEvent e) {
                dragMode = DR_NONE; dragParam = -1; dragTrack = -1; pendingSnap = null; pendingBench = null;
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                if (benchOn && e.getY() < panelY()) { benchScroll = Math.max(0, benchScroll + (e.getWheelRotation() > 0 ? 1 : -1)); return; }
                if (e.getY() >= panelY()) {
                    // wheel over a slider row fine-feeds that param
                    if (sel == null) return;
                    for (int i = 0; i < sel.p.length; i++) {
                        Rectangle r = sliderRect(i);
                        if (new Rectangle(r.x - 96, r.y - 4, r.width + 160, r.height + 8).contains(e.getX(), e.getY())) {
                            wheelParam(i, e);
                            return;
                        }
                    }
                    return;
                }
                if (e.isControlDown()) zoom(e.getWheelRotation() < 0 ? 1.15 : 0.87, e.getX());
                else scroll = Math.max(0, scroll + e.getWheelRotation() * 30 / pps);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Grid snap (G toggles, shift inverts while dragging); markers pull
     *  within 6 px regardless of the grid. */
    double snap(double t, MouseEvent e) {
        double g = (snapOn != e.isShiftDown()) ? 0.05 : 0.001;
        Marker m = nearestMarker(t, 6 / pps);
        return m != null ? m.t : Math.round(t / g) * g;
    }

    Rectangle tvolRect(int tr) { return new Rectangle(8, tracksY() + tr * trackH() + trackH() - 13, 42, 7); }

    void setTrackVol(int mx) {
        if (dragTrack < 0) return;
        Rectangle r = tvolRect(dragTrack);
        double u = Math.max(0, Math.min(1, (mx - r.x) / (double) r.width));
        double v = u * TVOL_MAX;
        if (Math.abs(trackVol[dragTrack] - v) < 1e-9) return;
        commitPending();
        trackVol[dragTrack] = v;
        markEdit();
    }

    void addFromPalette(int i) {
        if (i < 0 || i >= PALETTE.length) return;
        if (benchOn) {
            Clip c = addLayer(fromPal(PALETTE[i], 0, 0));
            toast(c.name + " added to the bench as layer " + c.id);
            return;
        }
        pushUndo("");
        Clip c = fromPal(PALETTE[i], selTrack, playPos);
        synchronized (lock) { clips.add(c); }
        sel = c;
        markEdit();
        toast(String.format(Locale.ROOT, "%s added at %.2fs on track %d", c.name, c.start, c.track + 1));
    }

    void togglePlay() {
        if (benchOn) { toggleBenchPlay(); return; }
        solo = null;
        if (!playing) {
            List<Clip> snap;
            synchronized (lock) { snap = new ArrayList<>(clips); }
            if (playPos >= timelineEnd(snap)) seekTo = 0;
            playing = true;
        } else playing = false;
    }

    void previewSel() {
        if (sel == null) { toast(benchOn ? "select a layer first" : "select a clip first"); return; }
        if (benchOn) {
            if (sel.on != ON_NONE) { fire(sel); toast(sel.id + " fired"); return; }
            toggleSolo(sel, selSpell);
            return;
        }
        solo = sel;
        seekTo = Math.max(0, sel.start);   // clips may hang off the left of 0
        playing = true;
    }

    void deleteSel() {
        if (sel == null) return;
        if (benchOn) { if (selSpell != null) removeSpellLayer(sel, selSpell); else removeLayer(sel); return; }
        pushUndo("");
        synchronized (lock) { clips.remove(sel); }
        sel = null;
        markEdit();
    }

    /** C: a sample clip becomes a choir clip (or a choir clip becomes a
     *  keep-len sample clip). File, start and speed carry over; everything
     *  else the two types share (common + tail params) is copied by name. */
    /** C: sample -> choir -> partials -> sample, keeping common/tail params, start and speed. */
    void toggleChoir() {
        if (sel == null || !sampled(sel)) { toast("select a sample clip first"); return; }
        pushUndo("");
        Clip o = sel;
        int nt = o.type == SAMPLE ? CHOIR : o.type == CHOIR ? PARTIALS : SAMPLE;
        Clip c = new Clip(o.name, nt, o.track, o.start, o.dur, o.seed);
        c.file = o.file; c.vlink = o.vlink; c.keyed = o.keyed;
        for (int i = 0; i < c.p.length; i++) {
            int oi = idxOf(o.type, key(nt, i));
            if (oi >= 0 && (i < NCOMMON || i >= c.p.length - N_TAIL)) c.p[i] = o.p[oi];
        }
        c.p[startIdx(nt)] = o.p[startIdx(o.type)];
        c.p[speedIdx(nt)] = o.p[speedIdx(o.type)];
        if (nt == SAMPLE) { c.p[NCOMMON] = o.type == PARTIALS ? o.p[NCOMMON + PA_LOOP] : 1; c.p[NCOMMON + 2] = 1; }   // loop, keep len
        else if (nt == CHOIR && o.p[P_REL] < 0.1) { c.p[P_ATT] = 0.3; c.p[P_REL] = 0.6; }   // a raw import's near-zero fades don't suit a pad
        else if (nt == PARTIALS) { c.p[NCOMMON + PA_LOOP] = 1; partials(c, false); }   // choir was looping; start the analysis now
        if (benchOn) {   // a layer keeps its identity; marked ranges follow their params by name
            c.id = o.id; c.on = o.on; c.lmute = o.lmute;
            if (o.range != null) for (var en : o.range.entrySet()) {
                int ni = idxOf(nt, key(o.type, en.getKey()));
                if (ni < 0) continue;
                if (c.range == null) c.range = new HashMap<>();
                c.range.put(ni, en.getValue().clone());
                String nt2 = o.rnote != null ? o.rnote.get(en.getKey()) : null;
                if (nt2 != null) { if (c.rnote == null) c.rnote = new HashMap<>(); c.rnote.put(ni, nt2); }
            }
            java.util.List<Clip> ls = selSpell != null ? selSpell.bench.layers : bench.layers;   // a spell's layer lives in that spell's list
            synchronized (lock) { int at = ls.indexOf(o); if (at >= 0) ls.set(at, c); }
            if (mix.solo == o) mix.solo = c;
            benchGen++;
        } else synchronized (lock) { clips.set(clips.indexOf(o), c); }
        sel = c;
        if (benchOn && selSpell != null) markSpellDirty(selSpell); else markEdit();
        String note = "";
        if (benchOn && selSpell != null) {   // the blend pairs a spell's layer with the palette's by id, and only when the types match
            Clip pal = bench.byId(c.id);
            if (pal != null && pal.type != nt) note = "   — the palette's " + c.id + " is a " + TYPE_NAMES[pal.type] + ": this layer won't blend until they match";
        }
        toast(switch (nt) {
            case CHOIR -> "choir: " + c.file + " — voices/gather/chord/wander/scatter on the panel";
            case PARTIALS -> "partials: " + c.file + " — analyzing; pitch (and the key) move the sines, the residual stays put";
            default -> "back to a sample clip (loop, keep len)";
        } + note);
    }

    void dupSel() {
        if (sel == null) return;
        if (benchOn) {
            if (selSpell != null) { toast("a spell's layers mirror the palette's ids — duplicate on the palette, then add it to the spell"); return; }
            Clip c = copyClip(sel);
            c.seed = uiRng.nextLong();
            c.id = newLayerId(sel.id); c.lmute = false;
            addLayer(c);
            return;
        }
        pushUndo("");
        Clip c = new Clip(sel.name, sel.type, sel.track, sel.end(), sel.dur, uiRng.nextLong());
        System.arraycopy(sel.p, 0, c.p, 0, sel.p.length);
        c.file = sel.file; c.vlink = sel.vlink; c.keyed = sel.keyed;
        synchronized (lock) { clips.add(c); }
        sel = c;
        markEdit();
    }

    /** Where the clip's fundamental sounds now (before the key), in Hz. Synth
     *  clips are absolute; recordings need their analysis (0 while it runs). */
    double clipHz(Clip c) {
        if (!sampled(c)) return 110 * Math.pow(2, c.p[P_PITCH] / 12);
        Partials pa = partials(c, false);
        if (pa == null) return 0;
        double base = c.type == PARTIALS ? partialsRoot(pa, c.p[NCOMMON + PA_ROOT]) : pa.f0;
        if (base <= 0) return 0;
        double r = Math.pow(2, c.p[P_PITCH] / 12) * base / (pa.f0 > 0 ? pa.f0 : base);
        if (c.type == SAMPLE && c.p[NCOMMON + 2] < 0.5) r *= c.p[NCOMMON + 3];   // tape mode: speed moves pitch too
        return (pa.f0 > 0 ? pa.f0 : base) * r;
    }

    /** R: moves the selected clip's pitch so its fundamental lands on the
     *  nearest octave of root × 2^(degree/12) — the tonic for 0, a fifth for 7. */
    void tuneSel(int degree) {
        if (sel == null) { toast("select a clip first"); return; }
        double cur = clipHz(sel);
        if (cur <= 0) {
            toast(sampled(sel) ? (PARTS.containsKey(partKey(sel)) ? "no clear pitch in this recording" : "still analysing — try again in a moment")
                               : "nothing to tune");
            return;
        }
        double t = rootHz * Math.pow(2, degree / 12.0);
        double target = t * Math.pow(2, Math.round(Math.log(cur / t) / Math.log(2)));   // nearest octave
        double delta = 12 * Math.log(target / cur) / Math.log(2);
        PSpec ps = spec(sel.type, P_PITCH);
        double want = sel.p[P_PITCH] + delta, np = Math.max(ps.min(), Math.min(ps.max(), want));
        pushUndo("");
        sel.p[P_PITCH] = np;
        markEdit();
        toast(String.format(Locale.ROOT, "%s: %+.0f cents → %s%s%s", sel.name, delta * 100, noteName(clipHz(sel)),
                degree != 0 ? String.format(Locale.ROOT, " (%+d st over the root)", degree) : "",
                np != want ? "  — pitch range limit, not fully there" : ""));
    }

    void tuneDialog() {
        if (sel == null) { toast("select a clip first"); return; }
        String in = (String) JOptionPane.showInputDialog(this,
                "Tune \"" + sel.name + "\" to how many semitones above the root " + noteName(rootHz) + "?\n(0 = root, 4 = major third, 7 = fifth, 12 = octave; negative is fine)",
                "Tune to a degree", JOptionPane.PLAIN_MESSAGE, null, null, "0");
        if (in == null) return;
        try { tuneSel(Integer.parseInt(in.trim())); }
        catch (NumberFormatException e) { toast("couldn't parse \"" + in + "\""); }
    }

    void rootDialog() {
        String in = (String) JOptionPane.showInputDialog(this,
                "Project root — the note key 0 stands for (a name like C2 or F#3, or Hz):",
                "Set root", JOptionPane.PLAIN_MESSAGE, null, null, noteName(rootHz).split(" ")[0]);
        if (in == null) return;
        double hz = parseNote(in);
        if (Double.isNaN(hz) || hz < 8 || hz > 8000) { toast("couldn't parse \"" + in + "\" — try C2 or 65.4"); return; }
        rootHz = hz;
        markEdit();
        toast(String.format(Locale.ROOT, "root %s (%.2f Hz) — key 0 now means this", noteName(hz), hz));
    }

    // ---- docked sample browser (A) and the forge window (F)
    JFrame frame;              // set by main; the browser docks into it
    SampleBrowser browser;
    boolean browserOn;         // remembered in lab.cfg
    void toggleBrowser() { showBrowser(!browserOn); }
    JPanel east;               // the frame's right-hand dock: browser and regulator panel sit side by side in it
    /** Docks a panel on the right (or removes it), widening the window by its
     *  width so the timeline keeps its size, but never past the screen. */
    void dock(JComponent c, boolean on) {
        if (frame == null) return;
        if (east == null) { east = new JPanel(); east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS)); east.setBackground(Color.BLACK); frame.add(east, BorderLayout.EAST); }
        int dw = c.getPreferredSize().width;
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (on) { east.add(c); if (!maximized) frame.setSize(frame.getWidth() + dw, frame.getHeight()); }
        else { east.remove(c); if (!maximized) frame.setSize(frame.getWidth() - dw, frame.getHeight()); }
        if (!maximized) {   // never grow past the screen: the timeline shrinks instead
            Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (frame.getWidth() > scr.width) frame.setSize(scr.width, frame.getHeight());
            if (frame.getX() + frame.getWidth() > scr.x + scr.width) frame.setLocation(Math.max(scr.x, scr.x + scr.width - frame.getWidth()), frame.getY());
        }
        frame.revalidate(); frame.repaint();
    }
    void showBrowser(boolean on) {
        if (frame == null) return;
        if (browser == null) browser = new SampleBrowser(this);
        if (on == browserOn && (on == (browser.getParent() != null))) return;
        if (!on) browser.stop();
        dock(browser, on);
        browserOn = on;
        saveCfg();
        if (on) browser.filter.requestFocusInWindow(); else requestFocusInWindow();
    }
    Forge forge;
    void showForge() {
        if (forge == null) forge = new Forge(this);
        forge.open();
    }

    /** Drops a recording onto the timeline as a partials clip tuned by `tune`
     *  (copied / decoded into samples/forge/ the way W imports do). */
    void importAsPartials(Path src, String name, double tune) {
        try {
            Path sub = SAMPLE_DIR.resolve("forge");
            Files.createDirectories(sub);
            String fname = src.getFileName().toString().replace(' ', '-');
            if (!isPcmName(fname)) {
                fname = fname.replaceFirst("\\.[^.]+$", "") + ".wav";
                Path dst = sub.resolve(fname);
                if (!Files.exists(dst))
                    run("ffmpeg", "-v", "error", "-y", "-i", src.toString(), "-vn", "-ac", "2", "-ar", String.valueOf(SR), "-c:a", "pcm_s16le", dst.toString());
            } else {
                Path dst = sub.resolve(fname);
                if (!Files.exists(dst)) Files.copy(src, dst);
            }
            String rel = "forge/" + fname;
            double dur = sample(rel)[0].length / (double) SR;
            pushUndo("");
            Clip c = new Clip(name, PARTIALS, selTrack, playPos, dur, uiRng.nextLong());
            c.file = rel; c.p[P_PITCH] = tune; c.p[P_ATT] = 0.005; c.p[P_REL] = 0.05;
            synchronized (lock) { clips.add(c); }
            sel = c;
            markEdit();
            partials(c, false);
            toast(String.format(Locale.ROOT, "%s on track %d as a partials clip, tuned %+.2f st", name, selTrack + 1, tune));
        } catch (Exception e) { toast("import failed: " + e); }
    }

    void setKey(double k) {
        keyOff = Math.max(-36, Math.min(36, k));
        toast(keyOff == 0 ? "key 0 (as written)" : String.format(Locale.ROOT, "key %+.0f st — keyed clips transpose", keyOff));
    }

    /** T: cycles which of the selected clip's params follow the global key. */
    void cycleKeyed() {
        if (sel == null) { toast("select a clip first"); return; }
        pushUndo("");
        sel.keyed = (sel.keyed + 1) & KEY_BOTH;
        markEdit();
        toast(sel.name + " follows key: " + KEY_NAMES[sel.keyed]);
    }

    void nudge(double d) {
        if (sel == null) return;
        pushUndo("nudge");
        if (sel.vlink) moveVideoBy(d); else sel.start += d;
        markEdit();
    }

    void moveTrack(int d) {
        if (sel == null) return;
        pushUndo("mvtrack");
        sel.track = Math.max(0, Math.min(TRACKS - 1, sel.track + d));
        selTrack = sel.track;
        markEdit();
    }

    void zoom(double factor, int anchorX) {
        double tA = tOf(anchorX);
        pps = Math.max(20, Math.min(800, pps * factor));
        scroll = Math.max(0, tA - (anchorX - tlX()) / pps);
    }

    /** Right-click / double-click a slider: type an exact value. Accepts a
     *  plain number, or "440hz" for the pitch and cutoff params. */
    void typeParam(int i) {
        if (sel == null) return;
        PSpec s = spec(sel.type, i);
        String in = (String) JOptionPane.showInputDialog(this,
                String.format(Locale.ROOT, "%s (%s .. %s%s):", s.name(),
                        fmtNum(s.min()), fmtNum(s.max()),
                        s.name().equals("pitch") || s.name().equals("cutoff") ? ", or e.g. 440hz" : ""),
                "Set " + s.name(), JOptionPane.PLAIN_MESSAGE, null, null,
                String.format(Locale.ROOT, "%.3f", sel.p[i]));
        if (in == null) return;
        in = in.trim().toLowerCase(Locale.ROOT);
        try {
            double val;
            if (in.endsWith("hz")) {
                double hz = Double.parseDouble(in.substring(0, in.length() - 2).trim());
                val = switch (s.name()) {
                    case "pitch" -> 12 * Math.log(hz / 110) / Math.log(2);
                    case "cutoff" -> Math.log(hz / 40) / Math.log(250);
                    default -> hz;
                };
            } else val = Double.parseDouble(in);
            val = Math.max(s.min(), Math.min(s.max(), val));
            if (discrete(sel.type, i)) val = Math.round(val);
            pushUndo("");
            sel.p[i] = val;
            markEdit();
        } catch (NumberFormatException ex) { toast("couldn't parse \"" + in + "\""); }
    }
    static String fmtNum(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%s", v);
    }

    /** Wheel over a slider row: fine steps (1/500 of range; shift = 1/5000,
     *  for zeroing in on exact ratios). Discrete params step whole units. */
    void wheelParam(int i, MouseWheelEvent e) {
        PSpec s = spec(sel.type, i);
        double rot = e.getPreciseWheelRotation();
        double val;
        if (discrete(sel.type, i)) val = sel.p[i] - Math.signum(rot);
        else val = sel.p[i] - rot * (s.max() - s.min()) / (e.isShiftDown() ? 5000.0 : 500.0);
        val = Math.max(s.min(), Math.min(s.max(), val));
        if (val == sel.p[i]) return;
        pushUndo("wheel:" + i);
        sel.p[i] = val;
        markEdit();
    }

    void setParam(int mx) {
        if (sel == null || dragParam < 0 || dragParam >= sel.p.length) return;
        PSpec s = spec(sel.type, dragParam);
        Rectangle r = sliderRect(dragParam);
        double u = Math.max(0, Math.min(1, (mx - r.x) / (double) r.width));
        double val = s.min() + (s.max() - s.min()) * u;
        if (discrete(sel.type, dragParam)) val = Math.round(val);
        if (sel.p[dragParam] == val) return;
        commitPending();
        sel.p[dragParam] = val;
        markEdit();
    }

    String fmtVal(Clip c, int i) {
        double v = c.p[i];
        String n = spec(c.type, i).name();
        return switch (n) {
            case "filter" -> new String[]{"LP", "BP", "HP"}[(int) Math.max(0, Math.min(2, Math.round(v)))];
            case "lfo shape" -> new String[]{"sine", "tri", "square", "random"}[(int) Math.max(0, Math.min(3, Math.round(v)))];
            case "lfo rate" -> String.format(Locale.ROOT, "%.1f Hz", v);
            case "env curve" -> v > 0.05 ? String.format(Locale.ROOT, "%.2f perc", v)
                              : v < -0.05 ? String.format(Locale.ROOT, "%.2f swell", v) : "linear";
            case "color" -> String.format(Locale.ROOT, "%.2f %s", v, v < 0.15 ? "white" : v < 0.6 ? "pink~" : "brown~");
            case "loop" -> v >= 0.5 ? "on" : "off";
            case "duck from" -> v < 0.5 ? "off" : "track " + (int) Math.round(v);
            case "phaser rate" -> String.format(Locale.ROOT, "%.2f Hz", v);
            case "ph pos" -> v < 0 ? "LFO" : String.format(Locale.ROOT, "%.2f (%.0f Hz)", v, 200 * Math.pow(16, v));
            case "lfo pos" -> v < 0 ? "free (rate)" : String.format(Locale.ROOT, "%.2f cyc", v);
            case "flange pos" -> v < 0 ? "LFO" : String.format(Locale.ROOT, "%.2f (%.1f ms)", v, 1.2 + 3.8 * v);
            case "ph stages" -> String.valueOf((int) Math.round(v));
            case "pitch mode" -> v >= 0.5 ? "keep len" : "tape";
            case "speed" -> v < 0.005 ? "freeze" : String.format(Locale.ROOT, "×%.2f", v);
            case "chord" -> CHORD_NAMES[chordIdx(v)];
            case "voices", "range" -> String.valueOf((int) Math.round(v));
            case "wander" -> String.format(Locale.ROOT, "±%.0f st", v);
            case "floor" -> String.format(Locale.ROOT, "%.0f dB", v);
            case "odd/even" -> String.format(Locale.ROOT, "%+.2f %s", v, v < -0.05 ? "evens cut" : v > 0.05 ? "odds cut" : "as is");
            case "tilt" -> String.format(Locale.ROOT, "%+.2f %s", v, v < -0.05 ? "dark" : v > 0.05 ? "bright" : "flat");
            case "purity" -> String.format(Locale.ROOT, "%.2f %s", v, v < 0.95 ? "cleaner" : v > 1.05 ? "more alien" : "as is");
            case "stretch" -> String.format(Locale.ROOT, "%+.3f %s", v, v > 0.01 ? "bell" : v < -0.01 ? "squashed" : "harmonic");
            case "gather", "bank", "bank shimmer" -> String.format(Locale.ROOT, "%.2f%s", v, v < 0.005 ? " off" : "");
            case "root shift" -> {
                Partials pa = c.file != null ? PARTS.get(partKey(c)) : null;
                double root = pa != null ? partialsRoot(pa, v) : 0;
                yield String.format(Locale.ROOT, "%+.0f st%s", v, root > 0 ? " → " + noteName(root).split(" ")[0] : v == 0 ? " (auto)" : "");
            }
            case "harm tol" -> String.format(Locale.ROOT, "%.1f %%", v);
            case "min len" -> String.format(Locale.ROOT, "%.0f ms", v);
            case "pitch" -> c.type == SAMPLE || c.type == CHOIR || c.type == PARTIALS ? String.format(Locale.ROOT, "%.1f st (×%.2f)", v, Math.pow(2, v / 12))
                                             : String.format(Locale.ROOT, "%.1f st %s", v, noteName(110 * Math.pow(2, v / 12)).split(" ")[0]);
            case "cutoff" -> String.format(Locale.ROOT, "%.0f Hz", 40 * Math.pow(250, v));
            default -> String.format(Locale.ROOT, "%.2f", v);
        };
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        double end;
        synchronized (lock) { end = timelineEnd(clips); }

        // ---- top bar
        g.setColor(new Color(90, 255, 190));
        g.drawString(benchOn ? "SynthLab SFX · bench (H: timeline)" + (family != null ? " · " + family + (unsaved || benchDirty ? "*  unsaved: S saves, shift+O reverts" : "") : "") : "SynthLab SFX · workspace" + (lastStampName != null ? " (" + lastStampName + ")" : ""), 14, 24);
        g.setColor(Color.GRAY);
        VideoRef vid = video;
        String tp = benchOn
                ? String.format(Locale.ROOT, "%6.1fs   %s   root %s   %s", playPos, benchPlaying ? "▶ " : "‖ ", noteName(rootHz),
                                keyOff != 0 ? String.format(Locale.ROOT, "KEY %+.0f (%s)  ", keyOff, noteName(rootHz * Math.pow(2, keyOff / 12))) : "")
                : (vid != null && vid.frames > 0 ? String.format(Locale.ROOT, "frame %d   ", vid.frameAt(playPos)) : "")
                + String.format(Locale.ROOT, "%6.2fs / %.2fs   %s%s%s%s%s",
                playPos, end, playing ? "▶ " : "‖ ", loopOn ? "loop " : "", snapOn ? "snap " : "",
                "root " + noteName(rootHz) + "   " + (keyOff != 0 ? String.format(Locale.ROOT, "KEY %+.0f (%s)  ", keyOff, noteName(rootHz * Math.pow(2, keyOff / 12))) : ""),
                combos.isEmpty() ? "" : "combo " + (comboIdx < 0 ? "-" : String.valueOf(comboIdx + 1)) + "/" + combos.size());
        if (monoOut) tp = "MONO   " + tp;
        boolean clipping = System.currentTimeMillis() - clipAt < 1000, xrun = System.currentTimeMillis() - xrunAt < 1000;
        if (xrun) { tp = "XRUN — audio dropped out (buffer ran dry)   " + tp; g.setColor(new Color(255, 160, 60)); }
        if (clipping) { tp = "SAT — mix over 0 dB, the limiter is squashing it   " + tp; g.setColor(new Color(255, 80, 80)); }
        g.drawString(tp, w - g.getFontMetrics().stringWidth(tp) - 14, 24);
        long dtMsg = System.currentTimeMillis() - msgAt;
        if (!msg.isEmpty() && dtMsg < 3000) {
            g.setColor(new Color(255, 235, 130, (int) (255 * (1 - dtMsg / 3000.0))));
            g.drawString(msg, w / 2 - g.getFontMetrics().stringWidth(msg) / 2, 24);
        }

        // ---- palette + action buttons
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        for (int i = 0; i < PALETTE.length; i++) {
            Rectangle r = palRect(i);
            Color tc = TYPE_COLORS[PALETTE[i].type()];
            g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 34));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 150));
            g.drawRect(r.x, r.y, r.width, r.height);
            String lb = (i + 1) % 10 + " " + PALETTE[i].label();
            g.drawString(lb, r.x + (r.width - g.getFontMetrics().stringWidth(lb)) / 2, r.y + 16);
        }
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        String[] acts = actions();
        for (int i = 0; i < acts.length; i++) {
            Rectangle r = actRect(i);
            g.setColor(new Color(40, 40, 40));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Color.GRAY);
            g.drawRect(r.x, r.y, r.width, r.height);
            g.drawString(acts[i], r.x + (r.width - g.getFontMetrics().stringWidth(acts[i])) / 2, r.y + 17);
        }

        if (benchOn) paintBench(g, w);
        else {
        // ---- ruler
        double[] steps = {0.05, 0.1, 0.25, 0.5, 1, 2, 5};
        double step = 5;
        for (double s : steps) if (s * pps >= 55) { step = s; break; }
        g.setColor(new Color(60, 60, 60));
        g.drawLine(tlX(), videoY() - 2, w - 12, videoY() - 2);
        for (double t = Math.ceil(scroll / step) * step; ; t += step) {
            int x = xOf(t);
            if (x > w - 12) break;
            g.setColor(new Color(60, 60, 60));
            g.drawLine(x, rulerY() + 8, x, tracksY() + TRACKS * trackH());
            g.setColor(Color.GRAY);
            g.drawString(step >= 1 ? String.format(Locale.ROOT, "%.0fs", t)
                                   : String.format(Locale.ROOT, "%.2f", t), x + 3, rulerY() + 12);
        }

        // ---- video lane: filmstrip anchored to the video's start
        if (vid != null) {
            int y = videoY();
            g.setColor(new Color(12, 12, 20));
            g.fillRect(tlX(), y, w - 12 - tlX(), VIDEO_H);
            g.setColor(Color.GRAY);
            g.drawString("vid", 20, y + 22);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            g.drawString("ctrl-drag", 6, y + 38);
            g.drawString("to move", 6, y + 50);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            Shape clip0 = g.getClip();
            g.clipRect(tlX(), y, w - 12 - tlX(), VIDEO_H);
            if (vid.frames > 0) {
                int x0 = xOf(vid.start), x1 = xOf(vid.start + vid.dur());
                int tw = vid.thumbW;
                for (int k = Math.max(0, (tlX() - x0) / tw); ; k++) {
                    int x = x0 + k * tw;
                    if (x >= x1 || x > w - 12) break;
                    BufferedImage t = vid.thumb((int) Math.floor(k * tw / pps * vid.fps + 1e-6));
                    if (t != null) g.drawImage(t, x, y + 4, null);
                    g.setColor(new Color(0, 0, 0, 120));
                    g.drawLine(x, y + 4, x, y + 4 + THUMB_H);
                }
                g.setColor(new Color(170, 190, 255, 160));
                g.drawRect(x0, y + 2, x1 - x0, VIDEO_H - 4);
            } else {
                g.setColor(Color.GRAY);
                g.drawString(vid.path.getFileName() + " — " + vid.status, tlX() + 8, y + 34);
            }
            g.setClip(clip0);
        }

        // ---- tracks
        for (int tr = 0; tr < TRACKS; tr++) {
            int y = tracksY() + tr * trackH();
            g.setColor(tr % 2 == 0 ? new Color(16, 16, 16) : new Color(23, 23, 23));
            g.fillRect(tlX(), y, w - 12 - tlX(), trackH());
            g.setColor(mute[tr] ? new Color(180, 60, 60) : Color.GRAY);
            g.drawString((tr + 1) + (mute[tr] ? "×" : ""), 20, y + 18);
            Rectangle vr = tvolRect(tr);
            g.setColor(new Color(70, 70, 70));
            g.drawRect(vr.x, vr.y, vr.width, vr.height);
            g.setColor(mute[tr] ? new Color(120, 60, 60) : new Color(90, 200, 160));
            g.fillRect(vr.x + 1, vr.y + 1, (int) (trackVol[tr] / TVOL_MAX * (vr.width - 1)), vr.height - 1);
        }

        // ---- clips (clipped to the track area: they may hang off the left of 0)
        Shape clipArea = g.getClip();
        g.clipRect(tlX(), tracksY(), w - 12 - tlX(), TRACKS * trackH());
        synchronized (lock) {
            for (Clip c : clips) {
                Rectangle r = clipRect(c);
                if (r.x + r.width < tlX() || r.x > w - 12) continue;
                Color tc = TYPE_COLORS[c.type];
                boolean isSel = c == sel;
                g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), mute[c.track] ? 40 : isSel ? 120 : 70));
                g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
                if (sampled(c) && r.width > 12) drawWave(g, c, r, tc, w);
                g.setColor(isSel ? Color.WHITE : new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 200));
                g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);
                if (c.vlink) {   // linked to the video: a lane-blue left edge
                    g.setColor(new Color(170, 190, 255, 220));
                    g.fillRect(r.x + 1, r.y + 1, 3, r.height - 1);
                }
                if (r.x + r.width - Math.max(r.x, tlX()) > 34) {
                    g.setColor(isSel ? Color.WHITE : Color.LIGHT_GRAY);
                    Shape clip0 = g.getClip();
                    g.clipRect(r.x + 2, r.y, r.width - 4, r.height);
                    g.drawString(c.name, Math.max(r.x + 6, tlX() + 4), r.y + 16);
                    g.setClip(clip0);
                }
            }
        }
        g.setClip(clipArea);

        // ---- markers: flags on the ruler's second row, guide lines down the tracks
        for (Marker m : markers) {
            int x = xOf(m.t);
            if (x < tlX() || x > w - 12) continue;
            g.setColor(new Color(255, 220, 80, 70));
            g.drawLine(x, rulerY() + 18, x, panelY() - 6);
            g.setColor(new Color(255, 220, 80));
            g.fillPolygon(new int[]{x, x + 6, x}, new int[]{rulerY() + 16, rulerY() + 20, rulerY() + 24}, 3);
            g.drawString(m.name, x + 8, rulerY() + 26);
        }

        // ---- playhead (the seeker)
        int px = xOf(playPos);
        if (px >= tlX() && px <= w - 12) {
            g.setColor(new Color(255, 90, 90));
            g.drawLine(px, rulerY(), px, panelY() - 6);
            g.fillPolygon(new int[]{px - 5, px + 5, px}, new int[]{rulerY(), rulerY(), rulerY() + 7}, 3);
        }
        }   // end of the timeline view

        // ---- parameter panel
        g.setColor(new Color(40, 40, 40));
        g.drawLine(0, panelY() - 2, w, panelY() - 2);
        if (sel != null) {
            Color tc = TYPE_COLORS[sel.type];
            g.setColor(tc);
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            String pinfo = "";
            if (sampled(sel)) {
                double hz = clipHz(sel);   // also kicks off the analysis for plain sample / choir clips
                Partials pa = PARTS.get(partKey(sel));
                if (pa == null) pinfo = "   analysing…";
                else pinfo = (sel.type == PARTIALS ? "   " + pa.nTracks + " partials" : "")
                           + (hz > 0 ? String.format(Locale.ROOT, "   ~%.0f Hz = %s%s", hz, noteName(hz),
                                        pa.share < 0.2 ? String.format(Locale.ROOT, " (faint: %.0f%% sines)", 100 * pa.share) : "")
                                     : "   no clear pitch");
            } else pinfo = "   " + noteName(clipHz(sel));
            if (benchOn && selSpell != null)
                g.drawString(String.format(Locale.ROOT, "%s  (%s)   spell %s · layer %s — TARGET values at lock%s   %s   key: %s   —  white ticks: where the blend sits now",
                        sel.name, TYPE_NAMES[sel.type], selSpell.id, sel.id, bench.byId(sel.id) != null ? " (shared with the palette)" : " (this spell only)",
                        sel.on != ON_NONE ? String.format(Locale.ROOT, "one-shot %.2fs on %s", sel.dur, ON_NAMES[sel.on]) : "endless", KEY_NAMES[sel.keyed]), 14, panelY() + 16);
            else if (benchOn)
                g.drawString(String.format(Locale.ROOT, "%s  (%s)   layer %s   %s%s   key: %s   —  P %s · right-click a slider: range / bind",
                        sel.name, TYPE_NAMES[sel.type], sel.id,
                        sel.on != ON_NONE ? String.format(Locale.ROOT, "one-shot %.2fs on %s", sel.dur, ON_NAMES[sel.on]) : "endless",
                        pinfo, KEY_NAMES[sel.keyed], sel.on != ON_NONE ? "fires it" : "solos it"), 14, panelY() + 16);
            else
            g.drawString(String.format(Locale.ROOT, "%s  (%s)   track %d   start %.2fs   dur %.2fs%s%s   key: %s   —  P previews solo",
                    sel.name, TYPE_NAMES[sel.type], sel.track + 1, sel.start, sel.dur,
                    sel.vlink ? "   linked to video" : "", pinfo, KEY_NAMES[sel.keyed]), 14, panelY() + 16);
            Clip live = benchOn ? liveRef(sel, selSpell) : sel;   // a spell layer's ticks and marks come from the palette layer it blends into
            double[] smod = benchOn ? live.mod : null;
            for (int i = 0; i < sel.p.length; i++) {
                PSpec s = spec(sel.type, i);
                Rectangle r = sliderRect(i);
                g.setColor(Color.GRAY);
                g.drawString(s.name(), r.x - 92, r.y + 11);
                g.drawRect(r.x, r.y, r.width, r.height);
                double u = (sel.p[i] - s.min()) / (s.max() - s.min());
                g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 170));
                g.fillRect(r.x + 1, r.y + 1, (int) (u * (r.width - 2)), r.height - 1);
                if (benchOn) {
                    // authoring marks: the range that sounded good (yellow brackets under the bar),
                    // a dot for a bound param, and the live modulated value as a white tick
                    double[] rg = live.range != null ? live.range.get(i) : null;
                    if (rg != null) {
                        g.setColor(new Color(255, 220, 80));
                        int x0 = r.x + 1 + (int) ((rg[0] - s.min()) / (s.max() - s.min()) * (r.width - 2));
                        int x1 = r.x + 1 + (int) ((rg[1] - s.min()) / (s.max() - s.min()) * (r.width - 2));
                        g.drawLine(x0, r.y + r.height + 1, x1, r.y + r.height + 1);
                        g.drawLine(x0, r.y + r.height - 1, x0, r.y + r.height + 3);
                        g.drawLine(x1, r.y + r.height - 1, x1, r.y + r.height + 3);
                    }
                    List<Bind> lb = bindsOn(live, i);
                    boolean bound = lb.stream().anyMatch(this::bindLive);
                    if (!lb.isEmpty()) {
                        Color bc = bound ? new Color(120, 200, 255) : new Color(90, 90, 90);   // grey: every bind on it is switched off
                        g.setColor(bc); g.fillOval(r.x - 9, r.y + 4, 5, 5);
                        // the bind table's row numbers, at the left end of the bar: black over the fill, the dot's colour past it
                        String rs = bindRows(live, i);
                        Font pf = g.getFont();
                        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
                        int fx0 = r.x + 1 + (int) (u * (r.width - 2));
                        Shape sc0 = g.getClip();
                        g.clipRect(r.x, r.y - 2, fx0 - r.x, r.height + 4);
                        g.setColor(Color.BLACK);
                        g.drawString(rs, r.x + 3, r.y + 10);
                        g.setClip(sc0);
                        g.clipRect(fx0, r.y - 2, r.x + r.width - fx0, r.height + 4);
                        g.setColor(bc);
                        g.drawString(rs, r.x + 3, r.y + 10);
                        g.setClip(sc0);
                        g.setFont(pf);
                    }
                    if ((bound || selSpell != null) && smod != null && benchPlaying && i < smod.length) {
                        {
                            double ue = Math.max(0, Math.min(1, (live.p[i] + smod[i] - s.min()) / (s.max() - s.min())));
                            int xe = r.x + 1 + (int) (ue * (r.width - 2));
                            g.setColor(Color.WHITE);
                            g.drawLine(xe, r.y - 2, xe, r.y + r.height + 2);
                        }
                    }
                }
                // value lives inside the bar so long readouts can't collide with the next column's label;
                // drawn in two clipped passes so the part over the fill is black and the rest stays light
                String vs = fmtVal(sel, i);
                int vx = r.x + r.width - g.getFontMetrics().stringWidth(vs) - 4, fx = r.x + 1 + (int) (u * (r.width - 2));
                Shape sc = g.getClip();
                g.clipRect(r.x, r.y - 2, fx - r.x, r.height + 4);
                g.setColor(Color.BLACK);
                g.drawString(vs, vx, r.y + 11);
                g.setClip(sc);
                g.clipRect(fx, r.y - 2, r.x + r.width - fx + 4, r.height + 4);
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(vs, vx, r.y + 11);
                g.setClip(sc);
            }
        } else {
            g.setColor(Color.GRAY);
            g.drawString(benchOn ? "no layer selected — click a row, or add with the palette / keys 1-9, W import, A browser"
                                 : "no clip selected — click one, or add with the palette / keys 1-9 (lands at the playhead)", 14, panelY() + 16);
        }

        // ---- little Lissajous, because it would be wrong to lose it
        drawScope(g, w - 105, panelY() + 88, 82);

        // ---- help: every binding in handleKey / buildMouse (keep in sync)
        g.setColor(new Color(110, 110, 110));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));   // 7 px/char: ~165 chars fit at 1200 wide
        if (benchOn) {
            g.drawString("mouse  click row: select · M / S boxes: mute / solo · level bar: drag · r-click row: id, endless / one-shot, fire, swap source, remove · dbl-click row: rename id", 14, h - 74);
            g.drawString("       slider: drag · wheel: fine · r-click slider: type value, mark the range that sounds good, note, bind a signal · wheel over rows: scroll", 14, h - 61);
            g.drawString("keys   1-9 0 add a synth layer · W import recording · A sample browser (adds land here) · DEL remove · D dup · up/down select · C sample/choir/partials", 14, h - 48);
            g.drawString("       SPACE play bench · ENTER stop · P solo / fire · T key-track · R tune to root · shift+R degree · ctrl+R root · < > key ±1 st · U the machine · shift+M mono", 14, h - 35);
            g.drawString("       J regulator panel: signal sliders, signature picker, lock / unlock events, binds, ranges, notes · signals move bound params live (white tick)", 14, h - 22);
            g.drawString("       edits autosave to a working copy · S save family · shift+S save as · shift+O revert · O open · N scratch bench · H timeline (shift+H sends a clip) · ctrl+Z undo", 14, h - 9);
            return;
        }
        g.drawString("mouse  drag clip: move (up/down = track) · left edge: trim · right edge: resize · shift-drag: invert snap · track #: mute · bar under #: volume · ruler: scrub", 14, h - 74);
        g.drawString("       r-click marker: delete · ctrl-drag video: slide · wheel: scroll · ctrl+wheel: zoom · slider: drag · r-click: type value · wheel on slider: fine (shift: finer)", 14, h - 61);
        g.drawString("keys   1-9 0 palette at playhead · X split · D dup · DEL · arrows: nudge 10ms (shift 100) / track · C sample/choir/partials · T key-track · G snap · + - zoom", 14, h - 48);
        g.drawString("       SPACE play · ENTER rewind · L loop · P solo · K mark (shift+K unmark) · [ ] frame · V video · M monitor · shift+M mono · < > key ±1 st (? resets)", 14, h - 35);
        g.drawString("       R tune to root · shift+R tune to a degree · ctrl+R set root · , . pick combo · I insert combo · B bank clip · Q library · W import sample", 14, h - 22);
        g.drawString("       A sample browser · F forge (promote sounds for the mod) · E export · S save as · O open · N clear · ctrl+Z undo · ctrl+Y redo", 14, h - 9);
    }

    /** Sample clips show their waveform, mapped through the clip's playback
     *  rate, so where the recording actually ends inside the clip is visible. */
    void drawWave(Graphics2D g, Clip c, Rectangle r, Color tc, int w) {
        float[] pk = peaks(c.file);
        if (pk.length == 0) return;
        long n = (long) pk.length * PBIN;
        boolean loop = looping(c);
        double base = c.p[startIdx(c.type)] * n;
        double rate = c.p[speedIdx(c.type)] * (keepLen(c) ? 1 : Math.pow(2, c.p[P_PITCH] / 12.0));
        double spp = SR * rate / pps;   // source samples per pixel
        int mid = r.y + r.height / 2, hh = r.height / 2 - 3;
        g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 150));
        int xa = Math.max(r.x + 1, tlX()), xb = Math.min(r.x + r.width - 1, w - 12);
        for (int x = xa; x < xb; x++) {
            double p0 = base + (x - r.x) * spp;
            if (!loop && p0 >= n) break;
            int b0 = (int) (p0 / PBIN), b1 = Math.max(b0 + 1, (int) ((p0 + spp) / PBIN));
            float m = 0;
            for (int b = b0; b < b1; b++) {
                int bi = loop ? b % pk.length : b;
                if (bi >= pk.length) break;
                m = Math.max(m, pk[bi]);
            }
            int hgt = (int) (m * hh);
            g.drawLine(x, mid - hgt, x, mid + hgt);
        }
    }

    void drawScope(Graphics2D g, int cx, int cy, int size) {
        int pos = scopePos;
        for (int i = 0; i < scopeL.length - 2; i += 2) {
            int idx = (pos + i) % scopeL.length, idx2 = (idx + 1) % scopeL.length;
            float age = i / (float) scopeL.length;
            g.setColor(new Color(90, 255, 190, (int) (age * age * 150) + 6));
            g.drawLine(cx + (int) (scopeL[idx] * size), cy - (int) (scopeR[idx] * size),
                       cx + (int) (scopeL[idx2] * size), cy - (int) (scopeR[idx2] * size));
        }
    }

    // =====================================================================
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--render")) {
            List<String> a = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            boolean mono = a.remove("--mono"), norm = a.remove("--normalize"), trim = !a.remove("--no-trim");
            double key = 0;
            int ki = a.indexOf("--key");
            if (ki >= 0 && ki + 1 < a.size()) { key = Double.parseDouble(a.get(ki + 1)); a.remove(ki + 1); a.remove(ki); }
            Path proj = a.size() > 0 ? Paths.get(a.get(0)) : PROJECT_FILE;
            Path outw = a.size() > 1 ? Paths.get(a.get(1)) : DIR.resolve("renders").resolve("sfx-render.wav");
            double[] tv = new double[TRACKS];
            Arrays.fill(tv, 1.0);
            boolean[] mu = new boolean[TRACKS];
            List<Clip> cs = parseProject(proj, new boolean[1], tv, mu);
            double peak = renderFile(cs, tv, mu, outw, outw.toString().toLowerCase(Locale.ROOT).endsWith(".ogg"), mono, norm, trim, key);
            System.out.printf(Locale.ROOT, "rendered %d clips, %.2fs -> %s (peak %.3f%s)%n",
                    cs.size(), timelineEnd(cs) + 1.5, outw, peak, key != 0 ? String.format(Locale.ROOT, ", key %+.2f st", key) : "");
            return;
        }
        if (args.length > 0 && args[0].equals("--check")) {
            List<String> fams = args.length > 1 ? Arrays.asList(args).subList(1, args.length) : familyNames();
            int bad = 0;
            for (String fam : fams) {
                List<String> probs = SfxFormat.validateFamily(Files.readAllLines(familyFile(fam)));
                System.out.println(fam + ".sfx: " + (probs.isEmpty() ? "clean (family format " + SfxFormat.FAMILY_FORMAT + ")" : probs.size() + " problem" + (probs.size() == 1 ? "" : "s")));
                for (String p : probs) System.out.println("  " + p);
                if (!probs.isEmpty()) bad++;
            }
            System.exit(bad == 0 ? 0 : 1);
        }
        if (args.length > 0 && args[0].equals("--bake")) {
            List<String> a = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            Path out = Paths.get(opt(a, "--out", BAKE_DIR.toString()));
            int q = Integer.parseInt(opt(a, "--quality", "5"));
            List<String> fams = a.isEmpty() ? familyNames() : a;
            bake(fams, out, q, System.out::println);
            return;
        }
        if (args.length > 0 && args[0].equals("--forge")) {
            List<String> a = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
            boolean wav = a.remove("--wav"), stereo = a.remove("--stereo");
            String name = opt(a, "--name", null), root = opt(a, "--root", "C2"), reg = opt(a, "--register", "nearest"),
                   ks = opt(a, "--keys", null), ns = opt(a, "--ns", "bubbys_world"),
                   ep = opt(a, "--event-prefix", "spell_"), pp = opt(a, "--path-prefix", "spells/");
            if (a.isEmpty()) {
                System.out.println("usage: --forge <sound.ogg|project.sfx> [--name n] [--root C2] [--register nearest|0|1|2] [--keys 0,2,4,7,9,...] [--wav] [--stereo] [--ns bubbys_world] [--event-prefix spell_] [--path-prefix spells/]");
                return;
            }
            Path src = Paths.get(a.get(0));
            int[] keys = ks == null ? FORGE_KEYS : Arrays.stream(ks.split(",")).mapToInt(x -> Integer.parseInt(x.trim())).toArray();
            forge(src, forgeName(name != null ? name : src.getFileName().toString()), parseNote(root),
                  reg.equals("nearest") ? null : Integer.valueOf(reg), keys, !wav, !stereo, ns, ep, pp, System.out::println);
            return;
        }
        SfxLab lab = new SfxLab();
        Thread audio = new Thread(() -> {
            try { lab.audioLoop(); } catch (LineUnavailableException e) { e.printStackTrace(); System.exit(1); }
        }, "audio");
        audio.setDaemon(true);
        audio.setPriority(Thread.MAX_PRIORITY);
        audio.start();

        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("SynthLab SFX — timeline workbench");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { lab.saveProject(true); if (lab.benchDirty) lab.saveBench(true); }
            });
            f.add(lab, BorderLayout.CENTER);
            lab.frame = f;
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            if (lab.browserOn) { lab.browserOn = false; lab.showBrowser(true); }
            if (lab.bpanelOn) { lab.bpanelOn = false; lab.showBenchPanel(true); }
            lab.requestFocusInWindow();
        });
    }
}
