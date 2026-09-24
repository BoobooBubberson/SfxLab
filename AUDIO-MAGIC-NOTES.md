# Audio-magic notes — SfxLab features for the crystal / resonance system

Written 2026-09-20 after a day of prototyping. Reference for the Minecraft
integration. Everything below lives in `SfxLab.java` unless noted (the
pre-feature snapshots that used to sit beside it as `.bak` files are now
just git history).

## 1. The design conclusions

**Why "one-shot + synth doot" sounded forced.** The ear groups sounds by
common fate: same onset, same pitch/filter motion, same room. A pad played
beside a fireball shares none of that, so it reads as two objects. The note
has to live *inside* the effect's own components. Four places it can live,
all now supported:

| where the note lives            | mechanism in SfxLab                                   | reads as                       |
|---------------------------------|-------------------------------------------------------|--------------------------------|
| resonant filtering of noise     | filter-keyed bandpass on a sample clip                | a whoosh that "sings"          |
| sweep endpoints                 | pitch-keyed synth clip; sweeps ride on top of the key | a zap that lands on the note   |
| tuned transients                | pitch-keyed pluck / sub thump under the impact        | the "instrument" in the hit    |
| the partials of a recording     | partials clip: sines transposed, residual untouched   | an arc / ring in a new key     |


## 2. Features built today

### Key transposition (`--key`, `< >`)

- Global key in semitones, applied at render time, never written into params.
  GUI: `shift+,` / `shift+.` step it, `shift+/` resets; header shows
  `KEY +7 (G2)`. Headless: `--render proj.sfx out.wav --key 7` (fractional ok
  → cents-level detune renders).
- Per-clip mode, cycled with `T`, saved as `keyed=`: **off / pitch / filter /
  pitch+filter**. Synth clips default to both; sample/choir default to
  *filter only* (their pitch is the recording's own); partials default to both.
- Pitch-keyed: `pitch` moves, sweeps/LFO ride on top. Filter-keyed: `cutoff`
  moves by the same ratio (cutoff is stored as Hz, type `440hz` to set it).
- Verified bit-exact at key 0; peaks land on 2^(k/12) within ±20 cents.

Use-cases: bake a pentatonic set of a spell in one shell loop; render the
"spam-detuned" variant with `--key 0.4`; audition a whole project in G by
pressing `>` seven times.

### Recipe: a recording that sings (`projects/examples/whoosh-keyed.sfx`, `cinder-keyed.sfx`)

Two copies of the same sample: one **dry** (`keyed=0`, the body, never moves),
one through a **resonant bandpass** (`filter=1`, `cutoff=440hz`,
`resonance≈0.85–0.9`, `keyed=2`). Add a **pitch-keyed pluck** two octaves
under the root as the impact thump. Result: still a fireball, with a note.

Use-cases: subtle note injection for non-tonal effects (whooshes, bursts);
the pluck as the "emphasis instrument" for crits and enemy-type resonance
hits. Tune the sing layer's level/resonance: too faint → raise, wah-pedal →
lower resonance toward 0.7.

### Partials clip type (`C` cycles sample → choir → partials → sample)

A sines + residual model of a recording (spectral modeling synthesis). Every
stable sinusoid is tracked through the spectrogram and replayed by an
oscillator bank; what is left after notching those peaks out (crackle, hiss,
breath) is the residual, played keep-len at ratio 1 — **never transposed**.

- `sines` / `residual` mix the two halves (0 either to solo the other).
- `pitch` (and the key) moves only the sines.
- `floor` (dB over the frame median) and `min len` (ms) decide what counts as
  a partial; changing them re-analyses in the background (~0.5 s / 2.5 s audio).
- `speed` time-stretches both halves; **0 freezes** (spectral freeze).
- `start`, `loop` as on sample clips. Panel shows the track count.
- Same algorithm as `tools/partials.py`
  (`python3 tools/partials.py in.ogg outdir --keys=-5,0,7`, note the `=`).

Sinusoidal energy share from a library survey:

| sound                    | share | verdict                                  |
|--------------------------|-------|------------------------------------------|
| charge_up, arc_weave     | 97 %  | ideal                                    |
| ice_cast, wet_blast      | ~61 % | good — ring moves, crackle stays         |
| electric_magic_short     | 12 %  | still convincing                         |
| fireball_burst, whooshes | 3–15 % | nothing to shift — use the bandpass recipe |

Use-cases: electric / frost / arcane spells whose recordings carry a tone;
hums and drones for rift spires (freeze + key = stable chord stems); turning
a baked effect back into an editable tone + texture pair.

### Root & tuning (`R`, `shift+R`, `ctrl+R`)

- Project `root` (saved; default C2 = 65.41 Hz). `ctrl+R` sets it by note
  name (`C2`, `f#3`) or Hz. Header shows `root C2`.
- Every recording-based clip shows its **sounding fundamental** on the panel
  (`~1390 Hz = F6 -8c`), after its own pitch offset (tape-mode speed included).
  `(faint: 3% sines)` marks recordings with no real pitch. Synth pitch sliders
  show note names.
- `R` tunes the selected clip to the nearest octave of the root; `shift+R`
  to a chosen degree above it (7 = fifth) for chord-tone layers. Works on
  synth and recording clips; the toast reports the move in cents.

Use-case: the authoring checklist — every pitched layer of a spell project
reads C-something on the panel before it is baked.

## 3. Files

- `projects/examples/whoosh-keyed.sfx`, `projects/examples/cinder-keyed.sfx` — bandpass + pluck recipes.
- `projects/examples/partials-demo.sfx` — ice cast + charge-up as partials clips.
- `archive/keyed/renders/` (local only) — the above at pentatonic keys; `archive/keyed/partials/` — Python
  tool output (sines / residual / per-key mixes) for several library sounds.
- `tools/partials.py` — the prototype analysis tool (numpy/scipy/ffmpeg).

## 4. Key cheat sheet (additions today)

`< >` key ±1 st · `?` key reset · `T` clip key-track mode · `C` sample/choir/
partials · `R` tune to root · `shift+R` tune to degree · `ctrl+R` set root ·
`F` forge · `A` sample browser · `--render … --key N` · `--forge sound.ogg`

## 5. The forge (added later the same day)

`F` in the workbench (or the headless `--forge` flag) opens the promotion
tool. It turns the authoring convention inside out: make sounds freestyle in
the workbench, then promote the finished result with one button. Staging
folder: `forge/<name>/`; the mod's sounds folder is only ever
read (path saved in `lab.cfg` as `forge_mirror`, `folder…` changes it).

- **Left:** the mirror of the mod's sounds folder with a filter box. Badges
  show each file's tonal share and estimated note (computed once in the
  background, cached in `forge/.badges-*.txt`; green ≥ 50 %, yellow ≥ 20 %).
  `promote current timeline…` renders whatever is on the workbench and
  promotes that instead of a file.
- **Middle:** the selected sound's analysis (length, partials, share,
  fundamental, what the promotion will tune it to) over a spectrogram with
  the tracked partials. Preview buttons: original, sines only, residual only,
  or the model at any key.
- **Right:** root, register (nearest octave / a fixed octave above the root),
  the key list, namespace and prefixes for sounds.json, format, **Run**, and
  `open in workbench` (drops the sound on the timeline as a partials clip
  already tuned to the root).

**Run produces**, per sound:

| file                      | what                                                        |
|---------------------------|-------------------------------------------------------------|
| `<name>_residual.ogg`     | the untransposed texture (crackle, hiss, breath)            |
| `<name>_sines.ogg`        | the partials alone, tuned to the root                        |
| `<name>_k00.ogg` … `k33`  | one per key (semitones above the root; `kn05` = −5)          |
| `<name>.partials.json`    | the model: tracks (`start`, `freq[]`, `amp[]`), `tuneSemitones`, `rootHz`, `f0Hz`, `sinesShare`, hop/window, file map — for a runtime resynth in the mod |
| `<name>.sounds.json`      | fragment in the mod's flat style: `"spell_<name>_k07": {"sounds": ["bubbys_world:spells/<name>/<name>_k07"]}` |
| `<name>.png`              | spectrogram + tracks                                         |

Headless: `java SfxLab.java --forge sound.ogg [--name n] [--root C2]
[--register nearest|0|1|2] [--keys 0,2,4,...] [--wav] [--stereo]
[--ns bubbys_world] [--event-prefix spell_] [--path-prefix spells/]`.
A `.sfx` project is rendered first and promoted the same way. ~2.5 s for
15 keys.

**Runtime path (for the mod session).** The model is small (arc_weave: 134
tracks, 54 KB as JSON) and the oscillator bank that replays it is ~60 lines:
for each track, per sample, `phase += 2π·f·2^((tuneSemitones+K)/12)/sr`,
linear freq/amp interpolation between frames (`frame = t·sr/hop`), sum
`amp·cos(phase)`, add the residual file untransposed. That gives continuous
key, cents detune, freeze (hold a frame), sines/residual mix, and a "gather"
(pull partial frequencies toward the crystal's chord) at runtime, all while
the recording's texture stays intact. The baked `k` files are the fallback.

## 6. Sample browser (2026-09-21)

`A` docks a panel on the right of the workbench listing everything under
`samples` (subfolders included, `.decoded` hidden). Filter box,
count, tonal badges (share %, note, length — same cache as the forge, scanned
in the background with the files on screen first), `▶ preview` / `stop` /
`play on select`, `add at playhead` (also double-click or ENTER) and
`add as partials` (a partials clip already tuned to the nearest octave of
the root, using the badge's fundamental). `import file… (W)` and `rescan`
sit there too. ESC hands the keyboard back to the timeline; the panel's
open/closed state is remembered in `lab.cfg`. The window grows by the
panel's width when it opens so the timeline keeps its size.

## 7. Playback glitches on long partials clips (2026-09-21)

Symptom: "painful clipping" with ~20 s partials clips, worse with more
clips. Cause, measured: every floor / min-len slider step spawned its own
analysis thread, so a drag launched ~25 analyses of a 20 s file at once;
that stalled the engine (block max 51 ms here, 79 ms on 4 cores) against a
23 ms output buffer → dropouts. Fixes: one low-priority analysis worker,
newest request first; 70 ms output buffer; pending clips poll for their
analysis every 2048 samples instead of every sample; the header now shows
**XRUN** (buffer ran dry) or **SAT** (mix over 0 dB into the tanh master
limiter) for a second so the two are told apart. Also learned: the master
stage is `tanh(x·1.1)·0.85`, so the live output never wraps but stacked
loud clips saturate; the default per-clip `cutoff` of 1 is a 10 kHz lowpass
that trims the top octave of noisy residuals; the residual of a partials
clip is read straight at speed 1 (grains only when stretching).

## 8. The bench (2026-09-24)

`H` swaps the timeline for the regulator palette: layers (clips with no
position) that all sound at once, steered by the machine's signals through
`bind` lines, with per-param `range` marks that turn listening notes into
the bind ranges, and signatures in `spells/` that blend in with the score.
`J` docks the scrubber panel; `U` opens the machine (RegulatorCore with the
prototype's controls) which drives those signals live. Details and the file format are in
`docs/HARMONIC-REGULATOR.md` §5.3; the engine side is a per-clip `mod` array
added to the params through a 30 ms smoother (`Voice.effective`), which
ordinary timeline clips never use, so old renders stay byte-identical.

## 8b. Performance (2026-09-24)

The machine stuttered on a slower PC. Measured on the palette (8 layers, 6 of
them partials with 1.8k–12k tracks): every layer costs ~0.05× realtime on one
core, 0.4× for all of them, and no single layer dominates; the machine's
stage painted in ~6 ms a frame. Three changes, none of which alter a single
output bit (verified sample for sample against the old path on the pyretic
project and on the live palette with moving signals):

- **Parallel block rendering.** `Engine.render` is split into `clipSample`
  (one clip, one sample, into its own six-value slot) and `post` (sidechain
  envelopes, delay, room, limiter). `renderBlock` renders each clip's 256
  samples on a small thread pool and then mixes in clip order, so the sums
  are the same doubles in the same order. It falls back to the sample path
  when any clip ducks (the sidechain couples clips within a sample) or with a
  single clip. The realtime loop and the export both use it: 2.9× faster on
  8 threads here, and the loop-end check moved from per sample to per block.
- **Silent clips are skipped** at effective level < 1e-4 (a bench layer
  waiting for its cue, a signature-only layer before the blend).
- **Partials analyses are cached on disk** in `forge/.parts/` (git-ignored),
  keyed by file, thresholds, size and mtime, and loaded exactly (residual as
  floats). Opening the palette went from ~14 s of background analysis to
  0.6 s; the palette's cache is ~85 MB.
- What a partials layer costs is its *active* tracks per frame, not its
  total track count (a long file has thousands of short tracks in turn).
  Measured on the palette at floor 14: 00001 14 active, 00003 12, 00008 31,
  lava 42, fire 25 of 12 034 total; at floor 30 the synth loops lose a
  quarter, fire drops to 628 tracks / 1 active (its "partials" were crackle,
  and its sines share falls 32 → 17 %), lava does not move (its bubbles are
  strong peaks; 88 % of it is residual anyway). The floor slider now reaches
  42 dB. So the floor is a sound decision (what gets re-pitched, what stays
  texture), not a CPU one; `min len` is the lever for crackle.
- The machine's ribbon is stroked as 16 depth-bucketed paths instead of
  1500 segments, the blueprint strip is drawn once into an image per target,
  and the values readout and panel sliders update at 8–10 Hz.

## 9. Harmonic controls (2026-09-21)

Partials clips gained seven sliders that act on the tracked partials only
(the residual is untouched, so they bite on tonal material — check the
badge). Each partial knows its ratio to the recording's fundamental and its
harmonic number (0 = inharmonic), so the fundamental readout must be right
(R pins it).

| slider     | does                                                                 | neutral |
|------------|----------------------------------------------------------------------|---------|
| `odd/even` | <0 fades the even harmonics (hollow, clarinet); >0 fades the odd ones above the fundamental | 0 |
| `tilt`     | gain ∝ ratio^tilt: −1 dark … +1 bright (a 1/h series becomes flat)   | 0 |
| `purity`   | gain of the inharmonic partials: 0 = clean series, 2 = more alien    | 1 |
| `stretch`  | harmonic h lands at h^(1+s): bells / metal (±0.3)                    | 0 |
| `gather`   | pull every partial toward the nearest pitch class of `chord`         | 0 |
| `chord`    | the chord for gather (same 13 as cloud/choir)                        | 1 |
| `shimmer`  | slow random detune per partial, up to ~±20 cents                     | 0 |

The **tones** primitive got the same knobs on an additive bank of 24
harmonics (`bank` = its level, 0 = the old tone exactly; plus `odd/even`,
`tilt`, `stretch`, `gather`, `chord`, `bank shimmer`). With `timbre` 2 (sine)
the bank is the whole spectrum; with 0/1 it adds to the wavetable. Use: when
a bed recording runs out of range, replace or double it with a tone carrying
the same treatment. Verified on a 220 Hz 3-harmonic test tone: hollow
removes h2 to −117 dB, tilt +1 raises h2/h3 by 6/9.5 dB, stretch 0.1 moves
h2 to 2^1.1, gather (major) pulls it back; old projects and the partials
demos stay bit-identical at neutral. Side note: the per-clip filter's tanh
adds a faint 3rd harmonic (~−38 dB) to everything. The forge model JSON now
carries `harm` per track for the same controls at runtime. The panel has 14
slider rows (window 1200×874).

Two more partials sliders (same day): `root shift` moves the classification
root by semitones when the estimate landed on a harmonic or the wrong octave
(the slider value shows the note it resolves to; the panel readout and R
follow it), and `harm tol` is how far a partial may miss an integer ratio
and still count as a harmonic (3 % default; detuned unisons want 5–8 %).
Beware reading "share of energy on the series" at very low roots: a low root
makes a dense grid that catches partials by chance. Panel: 15 rows, 1200×896.

Notes after experimenting - Harmonic controls turned out to not be a great 
match for most samples tested. There are some useful adjustments that can 
be made, but these sliders should generally not become major levers as 
audio-magic mechanics. With gather at 1, chords can start to peak through,
but the cloud preset remains the best option for producing full chords if/when 
they are desired.
