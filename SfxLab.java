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
 *     phaser, phaser rate, ph stages  a chain of all-pass stages swept by their
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
 *     lfo rate/shape, lfo>pitch/cut/amp   per-clip LFO (sine / triangle /
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
 *
 * FILE FORMATS: .sfx is the lab's editable source (the workspace autosaves
 * to project.sfx; S stamps named copies); exported .wav is what the mod
 * consumes. parseProject/renderWav are static and headless, so the mod's
 * build can also batch-render .sfx files via --render, or embed this class
 * and synthesize at runtime.
 *   DEL         delete clip     D duplicate       arrows nudge / change track
 *   X           split the selected clip at the playhead
 *   ctrl+Z      undo            ctrl+shift+Z / ctrl+Y  redo
 *   G           toggle snap-to-grid (shift-drag inverts it while held)
 *   , / .       browse combos.txt entries   I  insert combo as clips
 *   ctrl+wheel  zoom            wheel scroll
 *
 * Run:  java SfxLab.java        from the checkout — the folder holding SfxLab.java
 *       is the workspace (samples/, projects/, forge/, renders/, lab.cfg live in it).
 *       $SFXLAB_DIR overrides that; ~/synthlab is the fallback when run elsewhere.
 * Headless render:  java SfxLab.java --render [project.sfx] [out.wav|out.ogg] [--mono] [--normalize] [--no-trim] [--key N]
 * Headless forge:   java SfxLab.java --forge <sound.ogg|project.sfx> [--name n] [--root C2] [--register nearest|0|1|2] [--keys 0,2,4,...] [--wav] [--stereo]
 */
public class SfxLab extends JPanel {

    static final int SR = 44100, BLOCK = 256;
    static final int PH_MAX = 12;   // phaser all-pass stage limit (each pair of stages adds a notch)
    static final int TRACKS = 6, NV = 14;
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

    // =====================================================================
    // Wavetables (unchanged from SynthLab): 0 gritty gnarl, 1 saw, 2 sine
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

    // Chord tables: just-intonation ratios per voice slot (root = 1). The
    // cloud tables spread over several octaves; the first three are the
    // originals and new chords are appended so old files keep their index.
    //   unison     no chord, only the per-voice detune — a pure thickener
    //   sus4       1 4/3 3/2: open, medieval, stone-and-bell
    //   minor      1 6/5 3/2
    //   harm 7     1 5/4 3/2 7/4: the natural-overtone seventh — locked-in, organ-like
    //   overtones  1 2 3 4 5 …: one resonating object
    //   major 7    1 5/4 3/2 15/8: shimmery, floating
    //   penta      1 9/8 5/4 3/2 5/3: never wrong at any gather
    //   augment    1 5/4 25/16: uncanny, no resolution
    //   harm 11    1 11/8 13/8: the upper overtones nobody tunes to — alien, not harsh
    //   tritone    1 45/32 2: beats against everything — the corrupt state
    static final double[][] CHORDS = {
        {0.5, 0.5, 1, 1, 1, 2, 2, 2, 4, 4, 4, 8, 8, 16},                       // octaves
        {0.5, 0.75, 1, 1, 1.5, 2, 2, 3, 3, 4, 4, 6, 8, 12},                    // power
        {0.5, 1, 1, 1.25, 1.5, 2, 2, 2.5, 3, 4, 4, 5, 6, 8},                   // major
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},                            // unison
        {0.5, 1, 1, 4 / 3.0, 1.5, 2, 2, 8 / 3.0, 3, 4, 4, 16 / 3.0, 6, 8},     // sus4
        {0.5, 1, 1, 1.2, 1.5, 2, 2, 2.4, 3, 4, 4, 4.8, 6, 8},                  // minor
        {0.5, 1, 1, 1.25, 1.5, 1.75, 2, 2.5, 3, 3.5, 4, 5, 6, 7},              // harm 7
        {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},                       // overtones
        {0.5, 1, 1, 1.25, 1.5, 1.875, 2, 2.5, 3, 3.75, 4, 5, 6, 7.5},          // major 7
        {0.5, 1, 1.125, 1.25, 1.5, 5 / 3.0, 2, 2.25, 2.5, 3, 10 / 3.0, 4, 5, 6},   // penta
        {0.5, 1, 1, 1.25, 1.5625, 2, 2, 2.5, 3.125, 4, 4, 5, 6.25, 8},         // augment
        {0.5, 1, 1, 1.375, 1.625, 2, 2, 2.75, 3.25, 4, 4, 5.5, 6.5, 8},        // harm 11
        {0.5, 1, 1, 1.40625, 2, 2, 2.8125, 4, 4, 5.625, 8, 8, 11.25, 16},      // tritone
    };
    static final String[] CHORD_NAMES = {"octaves", "power", "major", "unison", "sus4", "minor", "harm 7",
                                         "overtones", "major 7", "penta", "augment", "harm 11", "tritone"};
    static final int NCHORD = CHORDS.length;
    static final double[][] CHORD_LOGS = new double[NCHORD][NV];
    static {
        for (int c = 0; c < NCHORD; c++)
            for (int v = 0; v < NV; v++) CHORD_LOGS[c][v] = Math.log(CHORDS[c][v]);
    }
    // choir chord slots: the same chords, but every ratio kept within an
    // octave of the source (a recording shifted further stops sounding like
    // itself). Slot order matters — the first voices are the loudest, so the
    // root leads and the colour tones follow.
    static final double[][] CHOIR_CHORDS = {
        {1, 1, 2, 0.5, 1, 1, 2, 0.5, 1, 1, 2, 0.5, 1, 1},                                    // octaves
        {1, 1.5, 1, 0.75, 1.5, 2, 1, 1.5, 0.5, 1, 0.75, 1.5, 2, 1},                          // power
        {1, 1.25, 1.5, 1, 0.75, 1.25, 1.5, 2, 1, 0.625, 1.25, 1.5, 0.5, 1},                  // major
        {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},                                          // unison
        {1, 4 / 3.0, 1.5, 1, 2 / 3.0, 4 / 3.0, 1.5, 2, 1, 0.75, 4 / 3.0, 1.5, 0.5, 1},        // sus4
        {1, 1.2, 1.5, 1, 0.75, 1.2, 1.5, 2, 1, 0.6, 1.2, 1.5, 0.5, 1},                       // minor
        {1, 1.5, 1.25, 1.75, 1, 0.75, 1.5, 1.25, 2, 0.875, 1.75, 1, 0.5, 1.5},               // harm 7
        {1, 1.5, 1.25, 1.75, 1.125, 1.375, 1.625, 2, 1, 1.5, 0.5, 1.25, 0.75, 1},            // overtones (folded)
        {1, 1.25, 1.5, 1.875, 1, 0.75, 1.25, 1.5, 0.9375, 2, 1, 0.625, 1.5, 0.5},            // major 7
        {1, 1.5, 1.25, 1.125, 5 / 3.0, 1, 0.75, 1.5, 1.25, 2, 0.5625, 0.625, 1, 0.5},        // penta
        {1, 1.25, 1.5625, 1, 0.78125, 1.25, 1.5625, 2, 1, 0.625, 1.25, 1.5625, 0.5, 1},      // augment
        {1, 1.375, 1.625, 1, 0.6875, 1.375, 1.625, 2, 1, 0.8125, 1.375, 1.625, 0.5, 1},      // harm 11
        {1, 1.40625, 1, 2, 0.703125, 1.40625, 1, 0.5, 1.40625, 2, 1, 0.703125, 1.40625, 1},  // tritone
    };
    static final double[][] CHOIR_LOGS = new double[NCHORD][NV];
    static {
        for (int c = 0; c < NCHORD; c++)
            for (int v = 0; v < NV; v++) CHOIR_LOGS[c][v] = Math.log(CHOIR_CHORDS[c][v]);
    }
    static int chordIdx(double v) { return (int) Math.max(0, Math.min(NCHORD - 1, Math.round(v))); }
    static final double[] PENTA = {1, 9.0 / 8, 5.0 / 4, 3.0 / 2, 5.0 / 3};
    static final double W_LO = Math.log(0.35), W_HI = Math.log(12);

    // =====================================================================
    // Parameter model: every clip carries COMMON params + its type's extras.
    // The UI, serialization, and DSP all read from the same specs.
    // =====================================================================
    record PSpec(String name, double min, double max, double def) {}

    static final int P_LEVEL = 0, P_ATT = 1, P_REL = 2, P_PITCH = 3, P_PSWP = 4, P_CUT = 5,
                     P_CSWP = 6, P_RES = 7, P_MODE = 8, P_PAN = 9, P_PANSWP = 10, P_ECHO = 11,
                     P_DRIVE = 12, NCOMMON = 13;
    static final PSpec[] COMMON = {
        new PSpec("level",      0,   1,   0.8),
        new PSpec("attack",     0,   2,   0.02),
        new PSpec("release",    0,   3,   0.25),
        new PSpec("pitch",    -36,  54,   0),
        new PSpec("pitch swp",-60,  60,   0),
        new PSpec("cutoff",     0,   1,   1),
        new PSpec("cutoff swp",-1,   1,   0),
        new PSpec("resonance",  0,   1,   0),
        new PSpec("filter",     0,   2,   0),      // LP / BP / HP
        new PSpec("pan",       -1,   1,   0),
        new PSpec("pan swp",   -2,   2,   0),
        new PSpec("echo",       0,   1,   0),
        new PSpec("drive",      0,   1,   0),   // added post-v2: exists by name only
    };

    static final int CLOUD = 0, TONE = 1, NOISE = 2, SPARKLE = 3, PLUCK = 4, SAMPLE = 5, CHOIR = 6, PARTIALS = 7;
    // KEY: a global transposition in semitones (GUI: < > keys; --render --key N)
    // applied non-destructively at render time to every clip that opts in.
    // Pitch-keyed clips move their `pitch` (sweeps and LFO ride on top, so a
    // zap still lands on the key); filter-keyed clips move `cutoff` by the same
    // ratio, so a resonant bandpass on a recorded whoosh sings the key. Synth
    // clips default to both, recordings to filter only (their pitch is usually
    // the recording's own). Zero key is bit-exact with the old renderer.
    static final int KEY_OFF = 0, KEY_PITCH = 1, KEY_FILTER = 2, KEY_BOTH = 3;
    static final String[] KEY_NAMES = {"off", "pitch", "filter", "pitch+filter"};
    static final double KEY_U = Math.log(2) / Math.log(250) / 12;   // cutoff units per semitone (fc = 40·250^u)
    static int defaultKeyed(int type) { return type == SAMPLE || type == CHOIR ? KEY_FILTER : KEY_BOTH; }
    static final String[] TYPE_NAMES = {"cloud", "tones", "noise", "sparkle", "pluck", "sample", "choir", "partials"};
    // choir extras (offsets from NCOMMON). 13 common + 9 + 14 tail = 36: exactly three slider columns.
    static final int CH_START = 0, CH_SPEED = 1, CH_VOICES = 2, CH_GATHER = 3, CH_DRIFT = 4, CH_CHORD = 5,
                     CH_WANDER = 6, CH_SCATTER = 7, CH_GSWP = 8;
    // partials extras (offsets from NCOMMON); start/speed sit where choir's do
    static final int PA_START = 0, PA_SPEED = 1, PA_SINES = 2, PA_RESID = 3, PA_FLOOR = 4, PA_MINLEN = 5, PA_LOOP = 6,
                     PA_ODD = 7, PA_TILT = 8, PA_PURITY = 9, PA_STRETCH = 10, PA_GATHER = 11, PA_CHORD = 12, PA_SHIM = 13,
                     PA_ROOT = 14, PA_HTOL = 15;
    // tones bank extras (offsets from NCOMMON) and its size
    static final int TB_BANK = 6, TB_ODD = 7, TB_TILT = 8, TB_STRETCH = 9, TB_GATHER = 10, TB_CHORD = 11, TB_SHIM = 12, TB_HARM = 24;
    /** Clips that read a recording: sample and choir. */
    static boolean sampled(Clip c) { return (c.type == SAMPLE || c.type == CHOIR || c.type == PARTIALS) && c.file != null; }
    static int startIdx(int type) { return type == CHOIR || type == PARTIALS ? NCOMMON + CH_START : NCOMMON + 1; }
    static int speedIdx(int type) { return type == CHOIR || type == PARTIALS ? NCOMMON + CH_SPEED : NCOMMON + 3; }
    static boolean looping(Clip c) { return c.type == CHOIR || c.p[c.type == PARTIALS ? NCOMMON + PA_LOOP : NCOMMON] >= 0.5; }
    static boolean keepLen(Clip c) { return c.type == CHOIR || c.type == PARTIALS || c.p[NCOMMON + 2] >= 0.5; }
    // Every clip ends with this shared tail block (appended after the type
    // extras so older project files still parse positionally — missing values
    // get defaults). Access it as the LAST N_TAIL params of any clip; new
    // shared params get APPENDED here to keep old files loading.
    static final PSpec[] TAIL_SPECS = {
        new PSpec("lfo rate", 0.1, 20, 4),
        new PSpec("lfo>pitch", 0, 12, 0),
        new PSpec("lfo>cut", 0, 0.5, 0),
        new PSpec("lfo>amp", 0, 1, 0),
        new PSpec("lfo shape", 0, 3, 0),   // sine, tri, square, random S&H
        new PSpec("env curve", -1, 1, 0),  // linear .. percussive / swelling
        new PSpec("reverb", 0, 1, 0),      // send into the shared room
        new PSpec("flange", 0, 1, 0),      // swept-comb mix; sweep rides the clip LFO
        new PSpec("flange fb", -0.95, 0.95, 0.5),
        new PSpec("duck", 0, 1, 0),           // sidechain depth: level dips when `duck from` is loud
        new PSpec("duck from", 0, TRACKS, 0), // key track (1-based); 0 = off
        new PSpec("phaser", 0, 1, 0),         // wet mix of the swept all-pass chain
        new PSpec("phaser rate", 0.05, 8, 0.4),
        new PSpec("ph stages", 1, PH_MAX, 4),   // more stages = more notches, thicker swirl
    };
    static final int N_TAIL = TAIL_SPECS.length;
    /** Index of a tail param for a given type (j = offset within TAIL_SPECS). */
    static int tailIdx(int type, int j) { return nParams(type) - N_TAIL + j; }
    static PSpec[] withLfo(PSpec... a) {
        PSpec[] r = Arrays.copyOf(a, a.length + N_TAIL);
        System.arraycopy(TAIL_SPECS, 0, r, a.length, N_TAIL);
        return r;
    }
    static final PSpec[][] EXTRAS = {
        // gather swp is appended last so older project files still parse positionally
        withLfo(new PSpec("voices", 1, 14, 10), new PSpec("gather", 0, 1, 0.85), new PSpec("drift", 0, 1, 0.25),
                new PSpec("timbre", 0, 2, 0.6), new PSpec("chord", 0, NCHORD - 1, 1), new PSpec("sub", 0, 1, 0.3),
                new PSpec("gather swp", -1, 1, 0)),
        withLfo(new PSpec("interval", -24, 24, 12), new PSpec("timbre", 0, 2, 2),
                new PSpec("detune", 0, 1, 0),       new PSpec("shimmer", 0, 1, 0),
                new PSpec("fm ratio", 0.25, 8, 2),  new PSpec("fm depth", 0, 8, 0),
                // additive harmonic bank (TB_HARM harmonics of the base pitch) with the
                // same harmonic controls as a partials clip; `bank` 0 = off (old sound)
                new PSpec("bank", 0, 1, 0), new PSpec("odd/even", -1, 1, 0), new PSpec("tilt", -1, 1, 0),
                new PSpec("stretch", -0.3, 0.3, 0), new PSpec("gather", 0, 1, 0), new PSpec("chord", 0, NCHORD - 1, 1),
                new PSpec("bank shimmer", 0, 1, 0)),
        withLfo(new PSpec("color", 0, 1, 0)),
        withLfo(new PSpec("density", 2, 40, 14), new PSpec("ping decay", 0.02, 0.4, 0.09),
                new PSpec("spread", 0, 1, 0.8),  new PSpec("range", 0, 2, 1),
                new PSpec("fm ratio", 0.25, 8, 2), new PSpec("fm depth", 0, 8, 0)),
        withLfo(new PSpec("damp", 0, 1, 0.5), new PSpec("sustain", 0, 1, 0.7),
                new PSpec("pick", 0, 1, 0.8), new PSpec("interval", -12, 12, 0)),
        withLfo(new PSpec("loop", 0, 1, 0), new PSpec("start", 0, 1, 0),
                new PSpec("pitch mode", 0, 1, 0),   // 0 tape (pitch = speed), 1 keep len (granular)
                new PSpec("speed", 0, 3, 1)),        // keep len: time-stretch (0 freezes); tape: extra rate
        withLfo(new PSpec("start", 0, 1, 0), new PSpec("speed", 0, 3, 1),
                new PSpec("voices", 1, 14, 8), new PSpec("gather", 0, 1, 0.85), new PSpec("drift", 0, 1, 0.2),
                new PSpec("chord", 0, NCHORD - 1, 1), new PSpec("wander", 0, 24, 7),   // ± semitones the ungathered voices roam
                new PSpec("scatter", 0, 1, 0.3),                              // read-position spread, as a fraction of the recording
                new PSpec("gather swp", -1, 1, 0)),
        withLfo(new PSpec("start", 0, 1, 0), new PSpec("speed", 0, 3, 1),   // keep-len time (0 freezes: partials hold, residual grain-freezes)
                new PSpec("sines", 0, 2, 1), new PSpec("residual", 0, 2, 1),   // the two halves of the model, mixed separately
                new PSpec("floor", 6, 30, 14),      // dB a peak must rise above the frame's median to count as a partial
                new PSpec("min len", 20, 200, 46),  // ms a partial must persist; shorter = noise, stays in the residual
                new PSpec("loop", 0, 1, 0),
                // harmonic controls (shared with the tones bank, see harmGain / harmFreq)
                new PSpec("odd/even", -1, 1, 0),     // <0 fades the even harmonics (hollow), >0 fades the odd ones above the fundamental
                new PSpec("tilt", -1, 1, 0),         // gain ∝ ratio^tilt: dark .. bright
                new PSpec("purity", 0, 2, 1),        // gain of the partials that are NOT on the harmonic series: 0 clean, 2 alien
                new PSpec("stretch", -0.3, 0.3, 0),  // harmonic h lands at h^(1+stretch): bells / metal
                new PSpec("gather", 0, 1, 0),        // pull every partial toward the nearest tone of `chord` (any octave)
                new PSpec("chord", 0, NCHORD - 1, 1),
                new PSpec("shimmer", 0, 1, 0),       // slow random detune per partial, up to ~±20 cents
                new PSpec("root shift", -36, 36, 0), // semitones added to the estimated fundamental before classifying (fix octave errors)
                new PSpec("harm tol", 1, 10, 3)),    // % a partial may miss an integer ratio and still count as a harmonic
    };
    static int nParams(int type) { return NCOMMON + EXTRAS[type].length; }
    static PSpec spec(int type, int i) { return i < NCOMMON ? COMMON[i] : EXTRAS[type][i - NCOMMON]; }
    static String key(int type, int i) { return spec(type, i).name().replace(' ', '_'); }
    static int idxOf(int type, String key) {
        for (int i = 0; i < nParams(type); i++) if (key(type, i).equals(key)) return i;
        return -1;
    }

    // Frozen positional layout of pre-v2 project files (v2 saves key=value
    // pairs instead). New params never go in here — they only exist by name.
    static final String[] LEGACY_COMMON = {"level", "attack", "release", "pitch", "pitch_swp", "cutoff",
                                           "cutoff_swp", "resonance", "filter", "pan", "pan_swp", "echo"};
    static final String[][] LEGACY_EXTRA = {
        {"voices", "gather", "drift", "timbre", "chord", "sub", "gather_swp"},
        {"interval", "timbre", "detune", "shimmer"},
        {},
        {"density", "ping_decay", "spread", "range"},
        {},   // pluck is post-v2: named params only
        {},   // sample too
        {},   // choir too
        {},   // partials too
    };
    static final String[] LEGACY_TAIL = {"lfo_rate", "lfo>pitch", "lfo>cut", "lfo>amp", "lfo_shape", "env_curve"};
    static String legacyName(int type, int i) {
        if (i < LEGACY_COMMON.length) return LEGACY_COMMON[i];
        i -= LEGACY_COMMON.length;
        if (i < LEGACY_EXTRA[type].length) return LEGACY_EXTRA[type][i];
        i -= LEGACY_EXTRA[type].length;
        return i < LEGACY_TAIL.length ? LEGACY_TAIL[i] : null;
    }
    static boolean discrete(int type, int i) {
        String n = spec(type, i).name();
        return n.equals("filter") || n.equals("chord") || n.equals("voices") || n.equals("range")
            || n.equals("lfo shape") || n.equals("loop") || n.equals("pitch mode") || n.equals("duck from")
            || n.equals("ph stages") || n.equals("floor") || n.equals("min len") || n.equals("root shift");
    }

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
            list.getActionMap().put("back", new AbstractAction() { public void actionPerformed(ActionEvent e) { lab.requestFocusInWindow(); } });
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
                        lab.pushUndo("");
                        Clip c = new Clip(forgeName(Paths.get(f).getFileName().toString()), PARTIALS, lab.selTrack, lab.playPos, dur, lab.uiRng.nextLong());
                        c.file = f; c.p[P_PITCH] = t; c.p[P_ATT] = 0.005; c.p[P_REL] = 0.05;
                        synchronized (lab.lock) { lab.clips.add(c); }
                        lab.sel = c; lab.markEdit(); partials(c, false);
                        lab.toast(String.format(Locale.ROOT, "%s as partials on track %d, tuned %+.2f st%s", f, lab.selTrack + 1, t, b[0] > 0 ? " -> " + noteName(b[0] * Math.pow(2, t / 12)) : " (no clear pitch)"));
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

    static class Clip {
        String name; int type, track; double start, dur; long seed; double[] p;
        String file;   // SAMPLE clips: filename inside samples/
        boolean vlink; // moves with the video (its own audio track, by default)
        int keyed;     // which of this clip's params follow the global key (KEY_* bits)
        Partials pa; long paFloor = Long.MIN_VALUE, paMin; int paRetry;   // PARTIALS: analysis cached for the current floor / min len
        Clip(String name, int type, int track, double start, double dur, long seed) {
            this.name = name; this.type = type; this.track = track;
            this.start = start; this.dur = dur; this.seed = seed;
            keyed = defaultKeyed(type);
            p = new double[nParams(type)];
            for (int i = 0; i < p.length; i++) p[i] = spec(type, i).def();
        }
        double end() { return start + dur; }
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

    // =====================================================================
    // Engine: renders any clip list sample by sample. The realtime loop and
    // the offline exporter each own one, so exporting never glitches playback.
    // Per-clip Random is seeded from the clip, so renders are reproducible.
    // =====================================================================
    static class Voice {
        final Random rng;
        double[] ph, wander, det, vpan;                   // cloud
        double phSub, ph1, ph2, ph3, ph4, j;              // sub / tones / shimmer walk
        double phM1, phM2;                                // tones FM modulator phases
        double nl1, nl2;                                  // noise color filter state
        float[] ks, ks2; int kp; double ex, dcp;          // Karplus-Strong string state
        double sp = -1;                                   // sampler source position (init on first render)
        final double[] gpos = new double[2]; final int[] gage = new int[2];   // keep-len grains
        boolean gFirst; double[] gref;                    // first-grain flag, alignment scratch
        double[] coff; double[][] cgpos; int[][] cgage; boolean[] cgFirst;   // choir: per-voice read offset + grains
        double[] pph;                                     // partials: per-track oscillator phases
        double[] hg, hf, hsh, hsr;                        // harmonic controls: cached gain / freq multiplier, shimmer phase / rate per partial
        double cOdd = Double.NaN, cTilt, cPur, cStr, cGat, cShift, cTol; int cChord = -1;   // the params those caches were built for
        double[] bph;                                     // tones bank: per-harmonic phases
        final float[] fl1 = new float[FLN], fl2 = new float[FLN]; int fp;   // flanger lines
        final double[] apx = new double[2 * PH_MAX], apy = new double[2 * PH_MAX];   // phaser all-pass states
        double phFbL, phFbR;                              // phaser feedback
        double lo1, b1, lo2, b2;                          // stereo SVF state
        double nextPing;                                  // sparkle spawn clock
        final double[] pf = new double[12], pp = new double[12], pa = new double[12], ppan = new double[12];
        final double[] pm = new double[12];               // sparkle per-ping FM phases
        Voice(Clip c) {
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

    static double wrap01(double x) { x = x % 1; return x < 0 ? x + 1 : x; }
    static final int KSN = 4096;   // string delay-line size; floors pitch at ~11 Hz
    static final int FLN = 256;    // flanger delay-line size (max ~5.8 ms)

    // ---- sample store: files from samples/, decoded once to
    // stereo floats at engine rate. A failed load caches as silence.
    static final Path SAMPLE_DIR = DIR.resolve("samples");
    static final java.util.concurrent.ConcurrentHashMap<String, float[][]> SAMPLES = new java.util.concurrent.ConcurrentHashMap<>();
    static final java.util.concurrent.ConcurrentHashMap<String, Path> SAMPLE_PATHS = new java.util.concurrent.ConcurrentHashMap<>();

    /** samples/<name> — or, when that file has since been sorted into another
     *  subfolder, the first file under samples/ with the same basename, so old
     *  projects keep resolving after the library is reorganised. */
    static Path samplePath(String name) {
        Path p = SAMPLE_DIR.resolve(name);
        if (Files.exists(p)) return p;
        return SAMPLE_PATHS.computeIfAbsent(name, nm -> {
            String base = Paths.get(nm).getFileName().toString();
            try (var st = Files.walk(SAMPLE_DIR)) {
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

    static float[][] sample(String name) {
        return SAMPLES.computeIfAbsent(name, nm -> {
            try {
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
            } catch (Exception e) {
                System.err.println("sample load failed: " + nm + " — " + e);
                return new float[2][1];
            }
        });
    }

    static boolean isPcmName(String nm) {
        String l = nm.toLowerCase(Locale.ROOT);
        return l.endsWith(".wav") || l.endsWith(".aif") || l.endsWith(".aiff") || l.endsWith(".au");
    }
    /** Anything javax.sound can't read (mp3, ogg, a video's audio track…) is
     *  decoded once by ffmpeg into samples/.decoded/<name>.wav. */
    static Path decodedPath(Path f) throws IOException, InterruptedException {
        if (isPcmName(f.getFileName().toString())) return f;
        Path abs = f.toAbsolutePath();
        String tag = abs.startsWith(SAMPLE_DIR) || abs.getParent() == null ? ""
                   : Integer.toHexString(abs.getParent().toString().hashCode()) + "-";   // outside samples/: keep same-named files apart
        Path out = SAMPLE_DIR.resolve(".decoded").resolve(tag + f.getFileName() + ".wav");
        if (!Files.exists(out)) {
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

    /** Linear-interpolated sample read. One-shot callers keep pos < n-1;
     *  the wrap on the second tap is for loops. */
    // ---- partials: a sines + residual model of a recording (spectral modeling
    // synthesis, Serra & Smith). Every stable sinusoid is tracked through a
    // 2048/256 Hann STFT; the tracked peaks are notched out of the spectrogram
    // to leave the residual (noise, breath, crackle); an oscillator bank replays
    // the partials at any transposition over the untouched residual. Same
    // algorithm and constants as tools/partials.py. Analysis runs once per
    // (file, floor, min len) on a worker thread — synchronously for --render —
    // and is cached for the session.
    static final int PA_N = 2048, PA_HOP = 256, PA_MAXPK = 40, PA_GAP = 3;
    static final double PA_FMIN = 40, PA_FMAX = 12000, PA_TOL = 0.06, PA_RANGE = 60;
    static class PTrack { int start, len; float[] freq, amp; float fmed, ratio; int harm; }   // start = first frame - 1: one fade frame each end; ratio = median freq / f0, harm = harmonic number (0 = inharmonic)
    static class Partials { int nFrames, nTracks; float[][] res; PTrack[] tracks; int[][] active; double f0, share; }   // f0: fundamental estimate (0 = none); share: sines / (sines + residual) energy
    static final java.util.concurrent.ConcurrentHashMap<String, Partials> PARTS = new java.util.concurrent.ConcurrentHashMap<>();
    static final Set<String> PARTS_PENDING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // Any recording-based clip can be analysed (sample / choir clips use the
    // default thresholds) — that is where their pitch estimate comes from.
    static double paFloor(Clip c) { return c.type == PARTIALS ? Math.round(c.p[NCOMMON + PA_FLOOR]) : 14; }
    static double paMinLen(Clip c) { return c.type == PARTIALS ? Math.round(c.p[NCOMMON + PA_MINLEN]) : 46; }
    static String partKey(String file, double fl, double ml) { return file + "|" + Math.round(fl) + "|" + Math.round(ml); }
    static String partKey(Clip c) { return partKey(c.file, paFloor(c), paMinLen(c)); }
    static Partials partials(Clip c, boolean sync) { return partials(c.file, paFloor(c), paMinLen(c), sync); }
    /** The analysis, or null while a worker computes it (sync = block instead). */
    static Partials partials(String file, double fl, double ml, boolean sync) {
        String k = partKey(file, fl, ml);
        Partials pa = PARTS.get(k);
        if (pa != null) return pa;
        if (sync) { pa = analyzePartials(file, fl, ml); PARTS.put(k, pa); return pa; }
        if (PARTS_PENDING.add(k)) {
            // one analysis at a time, newest request first: dragging a slider on a
            // 20 s clip used to launch a thread per value (25 analyses at once,
            // each allocating tens of MB) — that starved the audio thread
            PARTS_QUEUE.addFirst(new String[]{k, file, String.valueOf(fl), String.valueOf(ml)});
            startPartsWorker();
        }
        return null;
    }
    static void startPartsWorker() {
        synchronized (PARTS_QUEUE) {
            if (partsWorker != null) return;
            partsWorker = new Thread(() -> {
                String[] job;
                while ((job = PARTS_QUEUE.pollFirst()) != null) {
                    try { PARTS.put(job[0], analyzePartials(job[1], Double.parseDouble(job[2]), Double.parseDouble(job[3]))); }
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
    static final java.util.concurrent.ConcurrentLinkedDeque<String[]> PARTS_QUEUE = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static Thread partsWorker;
    static Partials analyzePartials(String file, double floorDb, double minLenMs) {
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
        class T { final List<double[]> pts = new ArrayList<>(); int gap; }   // {bin, freq, amp, frame}
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
    // ---- harmonic controls, shared by partials clips (per tracked partial) and
    // the tones bank (per synthesized harmonic). r = frequency / fundamental.
    static final double LN2 = Math.log(2);
    /** Harmonic number of ratio r (within 3 %), 0 = not on the series. */
    static int harmNum(double r) { return harmNum(r, 0.03); }
    static int harmNum(double r, double tol) {
        int h = (int) Math.round(r);
        return h >= 1 && h <= 32 && Math.abs(r / h - 1) < tol ? h : 0;
    }
    /** The root a partials clip classifies against: the estimate shifted by `root shift` (110 Hz stands in when there is no estimate). */
    static double partialsRoot(Partials pa, double shiftSemis) {
        double base = pa.f0 > 0 ? pa.f0 : 110;
        return shiftSemis == 0 && pa.f0 <= 0 ? 0 : base * Math.pow(2, shiftSemis / 12);
    }
    /** Gain for a partial: tilt brightens or darkens by ratio, odd/even fades one
     *  side of the series above the fundamental, purity scales the inharmonic ones. */
    static double harmGain(double r, int h, double oddEven, double tilt, double purity) {
        double g = 1;
        if (tilt != 0 && r > 0) g = Math.pow(Math.max(r, 0.25), tilt);
        if (h == 0) g *= purity;
        else if (h >= 2) {
            if (oddEven < 0 && (h & 1) == 0) g *= 1 + oddEven;
            else if (oddEven > 0 && (h & 1) == 1) g *= 1 - oddEven;
        }
        return g;
    }
    /** Frequency multiplier: stretch warps the spacing (h -> h^(1+s)), gather pulls
     *  toward the nearest pitch class of the chord, keeping the octave. */
    static double harmFreq(double r, double stretch, double gather, int chord) {
        if (r <= 0) return 1;
        double m = 1;
        if (stretch != 0) m = Math.pow(r, stretch);
        if (gather > 0) {
            double lr = Math.log(r * m), frac = lr - Math.floor(lr / LN2) * LN2, bestD = 1e9;
            for (double tl : CHORD_LOGS[chord]) {
                double tf = tl - Math.floor(tl / LN2) * LN2;
                for (double cand : new double[]{tf - LN2, tf, tf + LN2}) { double d = cand - frac; if (Math.abs(d) < Math.abs(bestD)) bestD = d; }
            }
            m *= Math.exp(gather * bestD);
        }
        return m;
    }
    /** Deterministic per-partial shimmer LFO rate (Hz) and start phase. */
    static double hash01(long seed, int k, int salt) { double h = Math.sin(k * 12.9898 + salt * 78.233 + (seed & 4095)) * 43758.5453; return h - Math.floor(h); }

    /** Fundamental estimate from the tracked partials: candidates are the
     *  strongest tracks' median frequencies (and their halves, for a missing
     *  fundamental); the one whose harmonic series collects the most partial
     *  energy wins. Effects rarely have a textbook series, so this lands on the
     *  dominant ring when nothing better exists. 0 when there are no partials. */
    static double estimateF0(List<PTrack> tracks) {
        int nt = tracks.size();
        if (nt == 0) return 0;
        double[] fm = new double[nt], en = new double[nt];
        Integer[] idx = new Integer[nt];
        for (int k = 0; k < nt; k++) {
            PTrack t = tracks.get(k);
            fm[k] = t.freq[t.len / 2];
            double e = 0;
            for (float a : t.amp) e += a * a;
            en[k] = e; idx[k] = k;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(en[b], en[a]));
        double best = 0, bestScore = -1;
        for (int i = 0; i < Math.min(16, nt); i++)
            for (double cand : new double[]{fm[idx[i]], fm[idx[i]] / 2}) {
                if (cand < PA_FMIN) continue;
                double score = 0;
                for (int k = 0; k < nt; k++) {
                    double r = fm[k] / cand;
                    int h = (int) Math.round(r);
                    if (h >= 1 && h <= 16 && Math.abs(r / h - 1) < 0.03) score += en[k] / Math.sqrt(h);
                }
                if (score > bestScore) { bestScore = score; best = cand; }
            }
        return best;
    }

    // ---- notes: names are concert pitch (A4 = 440, C4 = 60); the project root
    // is where the crystal's degree I sits, and the key is measured from it
    static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    /** "F#6 -4c" style name for a frequency (no cents when within half a cent). */
    static String noteName(double hz) {
        if (hz <= 0) return "-";
        double m = 69 + 12 * Math.log(hz / 440) / Math.log(2);
        int r = (int) Math.round(m), cents = (int) Math.round((m - r) * 100);
        String n = NOTE_NAMES[((r % 12) + 12) % 12] + (int) Math.floor(r / 12.0 - 1);
        return cents == 0 ? n : String.format(Locale.ROOT, "%s %+dc", n, cents);
    }
    /** Parses "c2", "F#4", "bb3", "65.4" or "65.4hz" into Hz; NaN if it won't parse. */
    static double parseNote(String in) {
        String t = in.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([a-g])([#b]?)(-?\\d)$").matcher(t);
        if (m.matches()) {
            int semi = "c d ef g a b".indexOf(m.group(1));
            if (m.group(2).equals("#")) semi++; else if (m.group(2).equals("b")) semi--;
            int midi = 12 * (Integer.parseInt(m.group(3)) + 1) + semi;
            return 440 * Math.pow(2, (midi - 69) / 12.0);
        }
        try { return Double.parseDouble(t.endsWith("hz") ? t.substring(0, t.length() - 2).trim() : t); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    /** In-place iterative radix-2 FFT (inverse is scaled by 1/n). */
    static void fft(double[] re, double[] im, boolean inv) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inv ? 1 : -1), wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
        if (inv) for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
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
    static String opt(List<String> a, String k, String def) {
        int i = a.indexOf(k);
        if (i < 0 || i + 1 >= a.size()) return def;
        String v = a.get(i + 1); a.remove(i + 1); a.remove(i);
        return v;
    }

    /** True (and records them) when the harmonic params differ from the voice's cache. */
    static boolean harmChanged(Voice v, double odd, double tilt, double pur, double str, double gat, int chord, double shift, double tol) {
        if (v.cOdd == odd && v.cTilt == tilt && v.cPur == pur && v.cStr == str && v.cGat == gat && v.cChord == chord && v.cShift == shift && v.cTol == tol) return false;
        v.cOdd = odd; v.cTilt = tilt; v.cPur = pur; v.cStr = str; v.cGat = gat; v.cChord = chord; v.cShift = shift; v.cTol = tol;
        return true;
    }
    static double smpAt(float[] a, double pos, int n) {
        int i0 = (int) pos;
        double fr = pos - i0;
        return a[i0] * (1 - fr) + a[(i0 + 1) % n] * fr;
    }

    // ---- keep-len granular sampler: 50 ms Hann grains at 50 % overlap (the
    // windows sum to 1), each new grain WSOLA-aligned to the sounding one
    static final int GLEN = (int) (0.05 * SR), GHALF = GLEN / 2;
    static final int GK = 200, GSTEP = 3, GSRCH = SR / 80 / GSTEP * GSTEP;   // 4.5 ms match, ±12.5 ms search (0 is a candidate)
    static double mono(float[][] s, double pos, int n, boolean loop) {
        int i = (int) Math.floor(pos);
        if (loop) i = ((i % n) + n) % n;
        else if (i < 0 || i >= n) return 0;
        return s[0][i] + s[1][i];
    }
    /** Where should a grain start so that it continues, in phase, what the
     *  other grain is about to play? Nominal start is `src`; slide it by up
     *  to ±GSRCH samples to maximize normalized correlation with the sounding
     *  grain's next GK reads (a small bias prefers the nominal spot). */
    static double alignGrain(float[][] s, int n, double src, double other, double ratio, boolean loop, double[] ref) {
        if (other < 0) return src;
        double eRef = 1e-9;
        for (int k = 0; k < GK; k++) { ref[k] = mono(s, other + k * ratio, n, loop); eRef += ref[k] * ref[k]; }
        double best = -1e9;
        int bestD = 0;
        for (int d = -GSRCH; d <= GSRCH; d += GSTEP) {   // coarse pass
            double sc = grainScore(s, n, src + d, ratio, loop, ref, eRef) - 0.02 * Math.abs(d) / GSRCH;
            if (sc > best) { best = sc; bestD = d; }
        }
        int coarse = bestD;
        for (int d = coarse - GSTEP + 1; d < coarse + GSTEP; d++) {   // fine pass around it
            if (d == coarse) continue;
            double sc = grainScore(s, n, src + d, ratio, loop, ref, eRef) - 0.02 * Math.abs(d) / GSRCH;
            if (sc > best) { best = sc; bestD = d; }
        }
        return src + bestD;
    }
    static double grainScore(float[][] s, int n, double p0, double ratio, boolean loop, double[] ref, double eRef) {
        if (!loop && (p0 < 0 || p0 + GK * ratio >= n)) return -1e9;
        double dot = 0, e = 1e-9;
        for (int k = 0; k < GK; k++) {
            double x = mono(s, p0 + k * ratio, n, loop);
            dot += x * ref[k]; e += x * x;
        }
        return dot / Math.sqrt(e * eRef);
    }

    static class Engine {
        double t = 0;
        double key = 0;   // global transposition, semitones (see KEY_*)
        double inPeak = 0;   // loudest master input since last read (>1 = the tanh limiter is squashing)
        final HashMap<Clip, Voice> voices = new HashMap<>();
        // sidechain: per-track peak envelope of the previous sample's output
        final double[] trackEnv = new double[TRACKS], trackAbs = new double[TRACKS];
        static final double DK_ATT = 1 - Math.exp(-1.0 / (0.002 * SR)), DK_REL = 1 - Math.exp(-1.0 / (0.15 * SR));
        final float[] dlyL = new float[(int) (0.31 * SR)], dlyR = new float[(int) (0.37 * SR)];
        int dpL, dpR;
        // reverb: freeverb-lite — 6 damped combs + 2 allpasses per channel,
        // right channel offset for width. Fed by each clip's reverb send.
        static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491};
        static final int[] ALLP = {556, 441};
        static final double RV_FB = 0.84, RV_DAMP = 0.3;
        final float[][] cvL = new float[COMB.length][], cvR = new float[COMB.length][];
        final float[][] avL = new float[ALLP.length][], avR = new float[ALLP.length][];
        final int[] cpL = new int[COMB.length], cpR = new int[COMB.length],
                    apL = new int[ALLP.length], apR = new int[ALLP.length];
        final double[] cfL = new double[COMB.length], cfR = new double[COMB.length];
        {
            for (int i = 0; i < COMB.length; i++) { cvL[i] = new float[COMB[i]]; cvR[i] = new float[COMB[i] + 23]; }
            for (int i = 0; i < ALLP.length; i++) { avL[i] = new float[ALLP[i]]; avR[i] = new float[ALLP[i] + 23]; }
        }

        /** One sample of a Karplus-Strong string: a noise burst (one period
         *  long, lowpassed by pick softness) circulates in a fractional delay
         *  line; each pass loses highs (damp) and level (fb). Both strings of
         *  a clip share the write clock; the caller advances v.kp. */
        double ksString(Voice v, float[] buf, double f, double lt, double k, double fb, double pick) {
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

        /** Render one sample of the mix into out[0..1] and advance time. */
        void render(List<Clip> cs, Clip solo, boolean[] mute, double[] tvol, double[] out) {
            double mixL = 0, mixR = 0, sendL = 0, sendR = 0, rvInL = 0, rvInR = 0;
            for (int ci = 0; ci < cs.size(); ci++) {
                Clip c = cs.get(ci);
                if (solo != null ? c != solo : (mute != null && mute[c.track])) continue;
                double lt = t - c.start;
                if (lt < 0 || lt >= c.dur) continue;
                double[] p = c.p;
                double prog = lt / c.dur;
                int lb = p.length - N_TAIL;
                double kp = (c.keyed & KEY_PITCH) != 0 ? key : 0, ku = (c.keyed & KEY_FILTER) != 0 ? key * KEY_U : 0;
                double env = 1;
                if (p[P_ATT] > 1e-4) env = Math.min(1, lt / p[P_ATT]);
                if (p[P_REL] > 1e-4) env = Math.min(env, (c.dur - lt) / p[P_REL]);
                if (env <= 0) continue;
                double curve = p[lb + 5];
                if (curve != 0) env = Math.pow(env, Math.pow(8, curve));
                Voice v = voices.computeIfAbsent(c, Voice::new);
                // per-clip LFO: phase runs in clip-local time; random shape is
                // hashed from the cycle count + seed so renders stay deterministic
                double lfo = 0, lfoAmp = p[lb + 3];
                if (p[lb + 1] != 0 || p[lb + 2] != 0 || lfoAmp != 0 || p[lb + 7] > 0.005) {
                    double cyc = lt * p[lb];
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
                                // harmonic controls: per-track gain and frequency multipliers,
                                // rebuilt only when a control moves (neutral values give exactly 1.0)
                                boolean ch = harmChanged(v, p[NCOMMON + PA_ODD], p[NCOMMON + PA_TILT], p[NCOMMON + PA_PURITY],
                                                         p[NCOMMON + PA_STRETCH], p[NCOMMON + PA_GATHER], chordIdx(p[NCOMMON + PA_CHORD]),
                                                         p[NCOMMON + PA_ROOT], p[NCOMMON + PA_HTOL] * 0.01);
                                if (v.hg == null || v.hg.length != nt || ch) {
                                    if (v.hg == null || v.hg.length != nt) { v.hg = new double[nt]; v.hf = new double[nt]; }
                                    double root = partialsRoot(pa, v.cShift);   // classification root: the estimate, shifted
                                    for (int k = 0; k < nt; k++) {
                                        PTrack tr = pa.tracks[k];
                                        double r = root > 0 ? tr.fmed / root : 0;
                                        int h = harmNum(r, v.cTol);
                                        v.hg[k] = harmGain(r, h, v.cOdd, v.cTilt, v.cPur);
                                        v.hf[k] = harmFreq(r, v.cStr, v.cGat, v.cChord);
                                    }
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
                    double d = (0.0012 + 0.0038 * (0.5 + 0.5 * lfo)) * SR;   // 1.2 .. 5 ms
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
                    double sw = 0.5 + 0.5 * Math.sin(2 * Math.PI * lt * p[lb + 12]);
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
                trackAbs[c.track] = Math.max(trackAbs[c.track], Math.max(Math.abs(sL), Math.abs(sR)));
                mixL += sL; mixR += sR;
                sendL += sL * p[P_ECHO]; sendR += sR * p[P_ECHO];
                rvInL += sL * p[lb + 6]; rvInR += sR * p[lb + 6];
            }
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

    static double timelineEnd(List<Clip> cs) {
        double e = 1;
        for (Clip c : cs) e = Math.max(e, c.end());
        return e;
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
    Path mirrorRoot() { return DIR.resolve(forgeMirror); }
    boolean expOgg = false, expMono = false, expNorm = false, expTrim = true;

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
    static final int DR_NONE = 0, DR_MOVE = 1, DR_SIZE = 2, DR_SEEK = 3, DR_SLIDER = 4, DR_TVOL = 5, DR_VIDEO = 6, DR_TRIM = 7;
    int dragMode = DR_NONE, dragParam = -1, dragTrack = -1;
    double grabOff = 0;
    boolean dirty = false;
    boolean snapOn = true;
    double keyOff = 0;   // audition transposition in semitones; not saved with the project
    volatile long clipAt;   // last time the master input went over 0 dB (header shows SAT for a second)
    volatile long xrunAt;   // last time the output buffer ran dry (header shows XRUN for a second)
    static final double ROOT_DEFAULT = 65.4064;   // C2: the crystal's degree I in the mod
    double rootHz = ROOT_DEFAULT;   // key 0 = this note; saved with the project (`root` line)
    long lastEditAt = 0;
    String lastStampName = null;      // last named .sfx stamped or opened; prefills the save dialog

    // ---- undo/redo: whole-project snapshots. Continuous gestures (drags,
    // repeated nudges) coalesce into one step; structural ops always push.
    static class Snap { ArrayList<Clip> clips; double[] tvol; boolean[] mute; ArrayList<Marker> markers; VideoRef video; double vstart; }
    final ArrayDeque<Snap> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    Snap pendingSnap = null;    // captured on mouse-press, committed on first change
    String lastOpTag = ""; long lastOpAt = 0;

    static Clip copyClip(Clip c) {
        Clip n = new Clip(c.name, c.type, c.track, c.start, c.dur, c.seed);
        System.arraycopy(c.p, 0, n.p, 0, c.p.length);
        n.file = c.file; n.vlink = c.vlink; n.keyed = c.keyed;
        return n;
    }
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
        long now = System.currentTimeMillis();
        if (!tag.isEmpty() && tag.equals(lastOpTag) && now - lastOpAt < 1200) { lastOpAt = now; return; }
        lastOpTag = tag; lastOpAt = now;
        undoStack.push(snapshot());
        if (undoStack.size() > 100) undoStack.removeLast();
        redoStack.clear();
    }
    void commitPending() {
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
        if (undoStack.isEmpty()) { toast("nothing to undo"); return; }
        redoStack.push(snapshot());
        restore(undoStack.pop());
        lastOpTag = "";
        toast("undo");
    }
    void doRedo() {
        if (redoStack.isEmpty()) { toast("nothing to redo"); return; }
        undoStack.push(snapshot());
        restore(redoStack.pop());
        lastOpTag = "";
        toast("redo");
    }

    final float[] scopeL = new float[2048], scopeR = new float[2048];
    int scopePos = 0;

    void toast(String s) { msg = s; msgAt = System.currentTimeMillis(); }
    void markEdit() { dirty = true; lastEditAt = System.currentTimeMillis(); }

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

    static String clipLine(Clip c) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "clip %s %d %d %.4f %.4f %d",
                c.name, c.type, c.track, c.start, c.dur, c.seed));
        for (int i = 0; i < c.p.length; i++)
            sb.append(String.format(Locale.ROOT, " %s=%.5f", key(c.type, i), c.p[i]));
        if (c.file != null) sb.append(" file=").append(c.file);
        if (c.vlink) sb.append(" vlink=1");
        sb.append(" keyed=").append(c.keyed);
        return sb.append('\n').toString();
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
        pushUndo("");
        Clip c = new Clip(fname.replaceFirst("\\.[^.]+$", ""), SAMPLE, track, start, dur, uiRng.nextLong());
        c.file = fname;
        c.p[P_ATT] = 0.002;
        c.p[P_REL] = 0.02;   // near-zero fades: let the recording speak
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
                    case "browser" -> browserOn = v.equals("1");
                    case "export_ogg" -> expOgg = v.equals("1");
                    case "export_mono" -> expMono = v.equals("1");
                    case "export_norm" -> expNorm = v.equals("1");
                    case "export_trim" -> expTrim = v.equals("1");
                }
            }
        } catch (Exception e) { System.err.println("cfg load failed: " + e); }
    }
    void saveCfg() {
        try {
            Files.createDirectories(DIR);
            Files.writeString(CFG_FILE, String.format("export_dir=%s%nexport_ogg=%d%nexport_mono=%d%nexport_norm=%d%nexport_trim=%d%nforge_mirror=%s%nbrowser=%d%n",
                    exportDir, expOgg ? 1 : 0, expMono ? 1 : 0, expNorm ? 1 : 0, expTrim ? 1 : 0, forgeMirror, browserOn ? 1 : 0));
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
        ArrayList<Clip> out = new ArrayList<>();
        for (String line : Files.readAllLines(f)) {
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
            // v2 tokens are key=value; bare numbers are legacy positional
            for (int i = 7; i < t.length; i++) {
                int eq = t[i].indexOf('=');
                String k = eq >= 0 ? t[i].substring(0, eq) : legacyName(type, i - 7);
                if (k == null) continue;
                if (k.equals("file")) { c.file = t[i].substring(eq + 1); continue; }
                if (k.equals("vlink")) { c.vlink = t[i].endsWith("=1"); continue; }
                if (k.equals("keyed")) { c.keyed = Integer.parseInt(t[i].substring(eq + 1)) & KEY_BOTH; continue; }
                int pi = idxOf(type, k);
                if (pi >= 0) c.p[pi] = Double.parseDouble(eq >= 0 ? t[i].substring(eq + 1) : t[i]);
            }
            out.add(c);
        }
        return out;
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
        double[] out = new double[2];
        List<Clip> snap = new ArrayList<>();

        while (true) {
            double sk = seekTo;
            if (sk >= 0) { seekTo = -1; eng.t = sk; eng.voices.clear(); }
            snap.clear();
            synchronized (lock) { snap.addAll(clips); }
            Clip so = solo;
            boolean pl = playing;
            double end = so != null ? so.end() + 1.0 : timelineEnd(snap) + (loopOn ? 0 : 1.0);
            eng.voices.keySet().removeIf(c -> !snap.contains(c) || eng.t < c.start || eng.t >= c.end());
            eng.key = keyOff;

            for (int i = 0; i < BLOCK; i++) {
                double l = 0, r = 0;
                if (pl) {
                    eng.render(snap, so, mute, trackVol, out);
                    l = out[0]; r = out[1];
                    if (eng.t >= end) {
                        if (so == null && loopOn) { eng.t = 0; eng.voices.clear(); }
                        else { playing = false; pl = false; solo = null; }
                    }
                }
                scopeL[scopePos] = (float) l; scopeR[scopePos] = (float) r;
                scopePos = (scopePos + 1) % scopeL.length;
                // clamp: the limiter keeps this under 0.85, but an unclamped cast would wrap past full scale
                int sl = (int) Math.max(-32768, Math.min(32767, l * 32767)), sr = (int) Math.max(-32768, Math.min(32767, r * 32767));
                buf[i * 4] = (byte) sl; buf[i * 4 + 1] = (byte) (sl >> 8);
                buf[i * 4 + 2] = (byte) sr; buf[i * 4 + 3] = (byte) (sr >> 8);
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
        double[] out = new double[2];
        double peak = 0;
        int last = 0;
        for (int i = 0; i < total; i++) {
            if ((i & 1023) == 0) e.voices.keySet().removeIf(c -> e.t < c.start || e.t >= c.end());
            e.render(cs, null, mute, tvol, out);
            double a = Math.max(Math.abs(out[0]), Math.abs(out[1]));
            peak = Math.max(peak, a);
            if (a > 0.001) last = i;
            mix[i * 2] = out[0]; mix[i * 2 + 1] = out[1];
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
    Rectangle actRect(int i) { return new Rectangle(getWidth() - (ACTIONS.length - i) * 74 - 12, paletteY(), 68, 24); }
    static final int SLIDER_ROWS = 15;   // partials has 43 params: three columns of 15
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
        setPreferredSize(new Dimension(1200, 896));   // 15 slider rows + 6 legend lines
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

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { handleKey(e); }
        });
        buildMouse();

        new javax.swing.Timer(33, ev -> {
            if (dirty && System.currentTimeMillis() - lastEditAt > 1200 && !Boolean.getBoolean("sfxlab.noautosave")) saveProject(true);   // -Dsfxlab.noautosave=true: headless tests must not touch project.sfx
            repaint();
            if (monitor != null && monitor.isVisible()) monitor.repaint();
        }).start();
    }

    /** All keys, shared by the main panel and the monitor window. */
    void handleKey(KeyEvent e) {
        int kc = e.getKeyCode();
        if (kc >= KeyEvent.VK_1 && kc <= KeyEvent.VK_9) { addFromPalette(kc - KeyEvent.VK_1); return; }
        if (kc == KeyEvent.VK_0) { addFromPalette(9); return; }
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
                for (int i = 0; i < ACTIONS.length; i++)
                    if (actRect(i).contains(mx, my)) {
                        switch (i) {
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
                                if (SwingUtilities.isRightMouseButton(e) || e.getClickCount() >= 2) {
                                    typeParam(i);
                                    return;
                                }
                                pendingSnap = snapshot();
                                dragMode = DR_SLIDER; dragParam = i; setParam(mx);
                                return;
                            }
                        }
                    return;
                }
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
                dragMode = DR_NONE; dragParam = -1; dragTrack = -1; pendingSnap = null;
            }
            @Override public void mouseWheelMoved(MouseWheelEvent e) {
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
        pushUndo("");
        Clip c = fromPal(PALETTE[i], selTrack, playPos);
        synchronized (lock) { clips.add(c); }
        sel = c;
        markEdit();
        toast(String.format(Locale.ROOT, "%s added at %.2fs on track %d", c.name, c.start, c.track + 1));
    }

    void togglePlay() {
        solo = null;
        if (!playing) {
            List<Clip> snap;
            synchronized (lock) { snap = new ArrayList<>(clips); }
            if (playPos >= timelineEnd(snap)) seekTo = 0;
            playing = true;
        } else playing = false;
    }

    void previewSel() {
        if (sel == null) { toast("select a clip first"); return; }
        solo = sel;
        seekTo = Math.max(0, sel.start);   // clips may hang off the left of 0
        playing = true;
    }

    void deleteSel() {
        if (sel == null) return;
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
        synchronized (lock) { clips.set(clips.indexOf(o), c); }
        sel = c;
        markEdit();
        toast(switch (nt) {
            case CHOIR -> "choir: " + c.file + " — voices/gather/chord/wander/scatter on the panel";
            case PARTIALS -> "partials: " + c.file + " — analyzing; pitch (and the key) move the sines, the residual stays put";
            default -> "back to a sample clip (loop, keep len)";
        });
    }

    void dupSel() {
        if (sel == null) return;
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
    void showBrowser(boolean on) {
        if (frame == null) return;
        if (browser == null) browser = new SampleBrowser(this);
        if (on == browserOn && (on == (browser.getParent() != null))) return;
        int dw = browser.getPreferredSize().width;
        boolean maximized = (frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0;
        if (on) { frame.add(browser, BorderLayout.EAST); if (!maximized) frame.setSize(frame.getWidth() + dw, frame.getHeight()); }
        else { browser.stop(); frame.remove(browser); if (!maximized) frame.setSize(frame.getWidth() - dw, frame.getHeight()); }
        if (!maximized) {   // never grow past the screen: the timeline shrinks instead
            Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (frame.getWidth() > scr.width) frame.setSize(scr.width, frame.getHeight());
            if (frame.getX() + frame.getWidth() > scr.x + scr.width) frame.setLocation(Math.max(scr.x, scr.x + scr.width - frame.getWidth()), frame.getY());
        }
        browserOn = on;
        frame.revalidate(); frame.repaint();
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
        g.drawString("SynthLab SFX · workspace" + (lastStampName != null ? " (" + lastStampName + ")" : ""), 14, 24);
        g.setColor(Color.GRAY);
        VideoRef vid = video;
        String tp = (vid != null && vid.frames > 0 ? String.format(Locale.ROOT, "frame %d   ", vid.frameAt(playPos)) : "")
                + String.format(Locale.ROOT, "%6.2fs / %.2fs   %s%s%s%s%s",
                playPos, end, playing ? "▶ " : "‖ ", loopOn ? "loop " : "", snapOn ? "snap " : "",
                "root " + noteName(rootHz) + "   " + (keyOff != 0 ? String.format(Locale.ROOT, "KEY %+.0f (%s)  ", keyOff, noteName(rootHz * Math.pow(2, keyOff / 12))) : ""),
                combos.isEmpty() ? "" : "combo " + (comboIdx < 0 ? "-" : String.valueOf(comboIdx + 1)) + "/" + combos.size());
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
        for (int i = 0; i < ACTIONS.length; i++) {
            Rectangle r = actRect(i);
            g.setColor(new Color(40, 40, 40));
            g.fillRect(r.x, r.y, r.width, r.height);
            g.setColor(Color.GRAY);
            g.drawRect(r.x, r.y, r.width, r.height);
            g.drawString(ACTIONS[i], r.x + (r.width - g.getFontMetrics().stringWidth(ACTIONS[i])) / 2, r.y + 17);
        }

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

        // ---- parameter panel
        g.setColor(new Color(40, 40, 40));
        g.drawLine(0, panelY() - 2, w, panelY() - 2);
        if (sel != null) {
            Color tc = TYPE_COLORS[sel.type];
            g.setColor(tc);
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
            g.drawString(String.format(Locale.ROOT, "%s  (%s)   track %d   start %.2fs   dur %.2fs%s%s   key: %s   —  P previews solo",
                    sel.name, TYPE_NAMES[sel.type], sel.track + 1, sel.start, sel.dur,
                    sel.vlink ? "   linked to video" : "", pinfo, KEY_NAMES[sel.keyed]), 14, panelY() + 16);
            for (int i = 0; i < sel.p.length; i++) {
                PSpec s = spec(sel.type, i);
                Rectangle r = sliderRect(i);
                g.setColor(Color.GRAY);
                g.drawString(s.name(), r.x - 92, r.y + 11);
                g.drawRect(r.x, r.y, r.width, r.height);
                double u = (sel.p[i] - s.min()) / (s.max() - s.min());
                g.setColor(new Color(tc.getRed(), tc.getGreen(), tc.getBlue(), 170));
                g.fillRect(r.x + 1, r.y + 1, (int) (u * (r.width - 2)), r.height - 1);
                // value lives inside the bar so long readouts can't collide
                // with the next column's label
                String vs = fmtVal(sel, i);
                g.setColor(u > 0.72 ? Color.BLACK : Color.LIGHT_GRAY);
                g.drawString(vs, r.x + r.width - g.getFontMetrics().stringWidth(vs) - 4, r.y + 11);
            }
        } else {
            g.setColor(Color.GRAY);
            g.drawString("no clip selected — click one, or add with the palette / keys 1-9 (lands at the playhead)", 14, panelY() + 16);
        }

        // ---- little Lissajous, because it would be wrong to lose it
        drawScope(g, w - 105, panelY() + 88, 82);

        // ---- help: every binding in handleKey / buildMouse (keep in sync)
        g.setColor(new Color(110, 110, 110));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));   // 7 px/char: ~165 chars fit at 1200 wide
        g.drawString("mouse  drag clip: move (up/down = track) · left edge: trim · right edge: resize · shift-drag: invert snap · track #: mute · bar under #: volume · ruler: scrub", 14, h - 74);
        g.drawString("       r-click marker: delete · ctrl-drag video: slide · wheel: scroll · ctrl+wheel: zoom · slider: drag · r-click: type value · wheel on slider: fine (shift: finer)", 14, h - 61);
        g.drawString("keys   1-9 0 palette at playhead · X split · D dup · DEL · arrows: nudge 10ms (shift 100) / track · C sample/choir/partials · T key-track · G snap · + - zoom", 14, h - 48);
        g.drawString("       SPACE play · ENTER rewind · L loop · P solo · K mark (shift+K unmark) · [ ] frame · V video · M monitor · < > key ±1 st (? resets)", 14, h - 35);
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
                @Override public void windowClosing(WindowEvent e) { lab.saveProject(true); }
            });
            f.add(lab, BorderLayout.CENTER);
            lab.frame = f;
            f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            if (lab.browserOn) { lab.browserOn = false; lab.showBrowser(true); }
            lab.requestFocusInWindow();
        });
    }
}
