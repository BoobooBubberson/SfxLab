# Writing music as .sfx

A handover for making music in SfxLab by writing `.sfx` files from Python scripts, as the pieces
here were (2026-11-08). The lab was built for spell sounds, but its clip types (a Tesla coil, a
choir grown from any recording, partials resynthesis, a bendable tuning) make unusual instruments.

| Script | Piece |
|---|---|
| `hymn_for_a_dead_coil.py` | D phrygian, 170 BPM, breakbeat + choir + Tesla-coil lead. `--riff pluck\|twin\|glitch\|swarm` picks the guitar (swarm the favourite, glitch close; pluck sounded 8-bit) |
| `tritave_signal.py` | alien: Bohlen-Pierce tuning (13 steps per 3:1, no octaves), 7/8, arc voice forming vowels |
| `overclock.py` | drum and bass into breakcore, F minor, 174 BPM: amen break, reese, vowel coil lead, choir gathering in the breakdown, tape-stop ending (combat) |
| `cathedral_of_rust.py` | half-time doom, D minor, 66 BPM: the D-major cello turned minor through partials `gather`, chanting coil, anvil, clockwork ticker (dread / boss) |
| `lantern_market.py` | G major in just intonation, 96 BPM swung, loops: FM kalimba, swung plucks, tuned FM toks, a 7/4 seventh on the V7 (hub / safe room) |
| `sunfall.py` | cinematic, D minor to D major, 84 BPM: a cloud Deep Note, cloud sections as string ostinato and horns, braams, taiko and timpani, the strings melting back to cloud in the bridge |
| `hoarfrost.py` | ambient exploration loop, D major pentatonic, 72 BPM, no drums: clouds gathering into and out of focus, struck singing bowl, a random-firing arc as frost crackle, blizzard wind |
| `the_coil_forgets.py` | the sad one, D minor, 56 BPM: the lament bass (D C# C B Bb A) as a ground, felt piano from tones, the coil as a voice singing sighs, cello partials, cloud strings, choir; at the end the voice misfires more each note until it stops |
| `instruments.py` | an audition sheet: 19 instruments built from the clip types (felt and electric piano, marimba, vibraphone, music box, tubular bell, pipe organ, clarinet, flute, ocarina, reed, violin, harp, koto, sitar, fretless bass, didgeridoo, wind chimes, choir), each a function to copy, levels matched to -20 dB; `--list` prints the running order |
| `envelope_ab.py` | before and after the envelope controls: felt piano, marimba, electric piano, violin, then brass, synth pluck and acid bass (new), each pair loudness-matched |
| `the_coil_remembers.py` | part II, D minor, 112 BPM (twice part I's 56, so the ground keeps its pace): the coil learns its phrase back, acid bass and synth pluck, brass stabs, the ground as a chromatic metal riff on the heavy guitar, the G major held, and the phrase part I broke off finally resolving to D. 1321 clips; renders ~1.8x real time on two cores |
| `sfxsong.py` | the helper the three above use: `Song.clip()` in beats, keys checked per clip type against `Sfx.java`, slider ranges and short envelopes reported |
| `probe.py` | measure recordings before using them: pitch, envelope, a timeline, section levels of a render |
| `listen.py` | look at a render: solo stems per track, RMS per track and section, spectrograms with bar lines, pitch classes per bar |

```sh
python3 tools/music/tritave_signal.py > projects/tritave_signal.sfx
JAVA_TOOL_OPTIONS=-Dsfxlab.noautosave=true ./sfxlab --render projects/tritave_signal.sfx renders/tritave.wav
```
`-Dsfxlab.noautosave=true` keeps a headless run from touching the workspace autosaves. Open the
project in the GUI with **O**.

## The file

Plain text, one item per line, `#` comments. A project is:

```
# SfxLab project v2: clip name type track start dur seed key=value...
loop 0                       # 1 = the timeline loops
root 110.0000                # the project's root in Hz (used by R / forge, not by synth pitch)
track 0 1.000 0              # track index (0-5), volume, mute (1 = muted). Six tracks
clip kick 1 0 0.0000 0.2500 812345 level=0.8 pitch=-1 pitch_swp=-19 interval=0 timbre=0 keyed=0
```

`clip <name> <type> <track> <start s> <dur s> <seed> key=value ...`

- **name**: a label, **no spaces** (the line splits on whitespace; a space shifts every field and the load fails)
- **type**: the number of the clip type: 0 cloud, 1 tones, 2 noise, 3 sparkle, 4 pluck, 5 sample, 6 choir, 7 partials, 8 arc
- **track**: 0-5 in the file; the GUI numbers them 1-6
- **start, dur**: seconds. **The release fades inside `dur`**: a clip is silent at start + dur, so a
  note that should ring needs the ring added to its length
- **release is the last `release` seconds of `dur`, and the envelope is `min(t/attack, (dur-t)/release)`
  raised to `8^env_curve`.** A release longer than the clip means it starts partway down its fade and never
  reaches its level: a 0.19 s snare with `release=0.45 env_curve=0.6` peaks about 26 dB down. For a hit, make
  the release the whole clip (`sfxsong`: `release="full"`) and shape it with `env_curve`. The hymn's snares
  sit ~26 dB and its kicks ~13 dB under their `level` for this reason
- **seed**: any 64-bit integer; the clip's random state (noise, voice offsets, jitter). Same seed, same sound
- **key=value**: any parameter left out takes its default, so lines only need what they change

Keys belong to a clip type: a tones clip has no `voices`, an arc no `interval`, and a key the type lacks is
skipped like a typo. Slider ranges are not enforced on load (attack 0-2 s, release 0-3 s).
Parameter keys are the slider names with spaces turned to `_`: `pitch_swp`, `env_curve`, `gather_swp`.
Some contain other characters: `lfo>pitch`, `lfo>cut`, `lfo>amp`, `odd/even` (in Python pass them as
`**{"lfo>pitch": 0.2}`). **An unknown key is skipped without a word**: the coil's vibrato was a typo
for a whole session. The scripts check every key against the `PSpec` names in
`runtime/sfxlab/runtime/Sfx.java`; copy that check into new scripts. Two keys are not sliders:
`file=` (a recording, relative to `samples/`) and `keyed=` (below).

## Pitch and time

- `pitch` is **semitones from A2 = 110 Hz**: 12 is A3, 24 A4 (440), -7 D2. It is continuous, so
  microtones and other tunings are just numbers (`tritave_signal.py`: a Bohlen-Pierce step is 1.463)
- on a recording (sample, choir, partials) `pitch` is a shift from the recording's own pitch, so measure
  it first (`probe.py pitch`) and shift by the difference, cents included
- an arc voice reads as its pitch with `formant=12` (the octave). At 19 the 3rd harmonic dominates and a
  sung D measures as A: fine for colour, risky for a melody
- arc `formant` does not detune the note: each pulse restarts the formant grain (FOF), so the output stays
  periodic and only the harmonics near the formant get louder. A pitch-class check of an arc lead reads
  bright harmonics (a 5th above for formant 19), not wrong notes
- resonant band-passed noise (`filter=1 resonance=0.9`) is pitched only roughly: in a short hit its peak lands
  anywhere within about ±1.5 semitones of the cutoff. For percussion that must be in key, put a short FM
  ping (tones, `fm_ratio=3`) on the note and keep the noise for the knock (`lantern_market.py drip()`)
- `pitch_swp` adds that many semitones linearly over the clip: kicks, risers, glides, tape stops
- the scripts write times in beats (or eighths) and convert to seconds in `clip()`
- `keyed`: 3 (default for synths) lets the project key (`< >`, `--key`) transpose the clip; set
  `keyed=0` on drums and on anything tempo-locked, or a transposition changes the tempo of an arc ticker

## Shared parameters (every clip type)

`level` 0-1, `attack` / `release` seconds, `pitch`, `pitch_swp`, `cutoff` 0-1 (40 Hz · 250^u: 0.5 is
630 Hz, 0.72 2.1 kHz, 1 open), `cutoff_swp`, `resonance`, `filter` 0 LP / 1 BP / 2 HP, `pan` -1..1,
`pan_swp`, `echo`, `drive` (a symmetric tanh, up to 25x: it buzzes, see below), `reverb` (a send),
`env_curve` (-1 swelling .. 1 percussive), `lfo_rate` + `lfo>pitch` / `lfo>cut` / `lfo>amp`,
`flange`, `phaser` + `phaser_rate` + `ph_stages`, and the sidechain: `duck` (depth) with
`duck_from` (the key track, **1-based**: `duck_from=1` ducks under track 0).
Every voice is high-passed at 30 Hz, so a fundamental under ~35 Hz thins out.

## The envelope as a modulator (2026-11-09)

Five shared keys, on every clip type. At their defaults a clip sounds exactly as before (the golden renders
and every music project re-render bit-identical).

| key | range | what it does |
|---|---|---|
| `decay` | 0-5 s | after the attack the level falls toward `sus_level`, exponentially with this time constant; 0 = off |
| `sus_level` | 0-1 | where the decay settles. `decay=0.3 sus_level=0.7`: an accent that settles (brass, a bowed bite) |
| `env>cut` | -1..1 | cutoff added at the envelope's peak: the filter opens with each note and closes as it fades |
| `env>fm` | 0-1 | how much tones FM depth follows the envelope. 1 (default) is the old behaviour; 0 holds it steady |
| `mod_decay` | 0-5 s | gives `env>cut` and `env>fm` their own decay from the attack, faster than the level's: the note dulls before it fades |

In Python `sfxsong` accepts `env_cut=` / `env_fm=` (and `lfo_pitch=`, `odd_even=` ...) for the keys with `>` or `/`.
Recipes, all in `envelope_ab.py`: synth pluck `timbre=1 cutoff=0.28 resonance=0.45 env>cut=0.55 mod_decay=0.12`;
acid `timbre=1 cutoff=0.18 resonance=0.8 drive=0.35 env>cut=0.5 mod_decay=0.16`; brass `timbre=1 cutoff=0.3
env>cut=0.42 decay=0.35 sus_level=0.7`; a piano's hammer `env>cut=0.3 mod_decay=0.45` with FM; a marimba's knock
`fm_ratio=4 mod_decay=0.05`. Remember `timbre=1` is the saw: a sine (2) has nothing for a filter to take away.

## The clip types as instruments

| type | what it is | parameters that matter |
|---|---|---|
| 0 cloud | up to 14 oscillators; ungathered each wanders 0.35x..12x the pitch, `gather` pulls voice i onto slot i of the chord table (slots span 3-4 octaves, first voices loudest) | `voices`, `gather`/`gather_swp` (0 to 1: a Deep Note), `drift` (wander speed), `timbre` (the same three tables: 0 gritty, 1 saw, 2 sine), `chord`, `sub` (a saw an octave down). 3-4 voices on `unison` (3) are a section, each carrying its own ±0.6% detune; 14 on a chord a wide orchestral voicing; 8 on `power` with drive and an opening cutoff a braam (use `timbre=1` for a real saw). Gather reaches 1 only at the clip's end: hold the chord in a second clip |
| 1 tones | two oscillators | `interval` (semitones to the second, continuous: 19.02 is a tritave), `timbre` blends three wavetables: **0 gritty** (16 harmonics at random phases), **1 saw** (12 harmonics), **2 sine** (not 0 sine .. 2 saw, as this line said until 2026-11-09; the pieces written before then used it that way, so their "saws" are sines and their "sines" are gritty), `detune`, `shimmer`, `fm_ratio`/`fm_depth`; an additive bank: `bank`, `odd/even` (-1 odd only: hollow), `tilt`, `stretch` (bell). Kicks: `pitch=-1 pitch_swp=-19 timbre=0` (gritty: the click is in the wave) |
| 2 noise | coloured noise | `color`; with `filter=1 resonance=0.9` and the cutoff on a note it is a pitched drip (`tritave_signal.py drip()`) |
| 4 pluck | Karplus-Strong string | `damp`, `sustain`, `pick`, `interval` (a second string, summed before the drive: power chords, or -3/-4 for thirds, liked). Through `drive` it sounds 8-bit; an amp stage is wanted |
| 5 sample | a recording | `file`, `start` (0-1 of the file), `loop`, `pitch_mode` 0 tape (pitch = speed) / 1 keep length (granular), `speed` (keep length at `speed=0` freezes a grain: the glitch buzzsaw) |
| 6 choir | up to 14 granular copies of a recording | `voices`, `gather` (0 every voice wanders, 1 all on the chord), `gather_swp` (0 to 1 over a clip: chaos resolving into a chord), `chord` (index: 0 octaves, 1 power, 2 major, 3 unison, 4 sus4, 5 minor, 6 harm 7, 7 overtones, 8 major 7, 9 penta, 10 augment, 11 harm 11, 12 tritone), `wander` (± semitones), `drift`, `scatter`, `speed`, `start`. Expensive: keep `voices` low for live playback |
| 7 partials | sines + residual resynthesis of a recording (analysis cached in `forge/.parts/`) | `sines`, `residual`, `stretch` (h to h^(1+s)), `purity` (0 clean, 2 alien), `shimmer`, `odd/even`, `tilt`, `gather` + `chord`, `speed`, `start` |
| 8 arc | a singing Tesla coil: a pulse train at the pitch, each pulse a crack | `ring` (0 raw spark .. 1 a damped sine per pulse, a voice), `formant` (where that sine sits, semitones above the pitch: change it per note for vowels), `burst` ms, `jitter`, `misfire`, `flicker`, `snap`, `color`, `divide` (the rate is the pitch this many octaves down) |

Arc tricks used so far:
- **lead**: `ring=0.62 formant=12 burst=7 jitter=0.05 snap=0.4 flicker=0.25` (hymn), or `ring=0.88 burst=11`
  with a random formant per note and short glide clips between notes (tritave)
- **self-playing hi-hats**: fire at exactly the sixteenth rate with `divide=4`, so `pitch = 12·log2(rate·16/110)`;
  `misfire` 0.2-0.8 drops ticks at random, `keyed=0`. A `pitch_swp` of -30 slows the clock to a stop
- **riser**: `pitch_swp=24` over two bars, `jitter=0.15`

## Recordings measured so far

| file | what it is | to D (or A) |
|---|---|---|
| `resonant/guitar_heavy_00002.ogg` | one D power chord, rings ~2 s then silence | pitch 0 = D |
| `resonant/guitar_heavy_00003.ogg` | A power chord, 18 cents flat, darker | +5.18 to D (or -6.82, an octave under) |
| `resonant/guitar_heavy_00001_pad.ogg` | sustained D power chord, darkening over 8 s | pitch 0 = D |
| `resonant/bell_00003.ogg` | G bell (+37c) with a strong D overtone | -5.37 to D |
| `resonant/cello_00002_pad.ogg` | sustained D **major** (its F# fights minor keys; a partials `gather` could pull it to F) | |
| `resonant/cello_heavy_00001/00002_pad.ogg` | G and C# chords | |
| `vocals/ethereal_vocals.wav` | voice on E4 | -2 to D |
| `luminal/luminal__synth_vocal_base_00001.ogg` | voice on Bb3 -35c (partials cached) | -0.65 to A |
| `luminal/luminal_synth_vocal_treble_00004.ogg` | voice on C4 (partials cached) | -2.94 to A3 |

Most recorded risers peak early, not at their end (`probe.py envelope`): place them by their peak, or synthesise the riser.
Only the cleared sample sets are in git (see `samples/README.md`); the hymn also reads
`vocals/`, `machines/` and `impacts/`, which are local, so it renders with silent clips elsewhere.

## Checking a piece without ears

Measure, then listen: the numbers catch a buried kick, not a bad melody.

```sh
python3 tools/music/listen.py stems  projects/x.sfx /tmp/x              # mix.wav + one solo render per used track
python3 tools/music/listen.py report /tmp/x 1.379 intro:0:4 drop:8:16   # RMS per track x section, lows, highs, peaks
python3 tools/music/listen.py draw   /tmp/x 1.379 /tmp/x.png 4 12       # spectrograms of mix and stems, bars 4-12
python3 tools/music/listen.py chroma /tmp/x/t5.wav 1.379 8 16           # pitch classes per bar: are the chords right?
```

`chroma` smears short low notes: a 16th at 65 Hz is ten cycles long and reads a semitone either side
of itself. Check a doubtful one alone, rendered long.

The master is `tanh(1.1 x) * 0.85`: a mix that drives it squashes everything at once, so balance the
tracks (track volume is the fader) before adding level. The duck keys on the key track's level after its
fader, reaching full depth at -12 dBFS.

```sh
# solo track N (0-5): mute every other track line, render, measure per section
awk -v t=2 '/^track /{ $4 = ($2==t)?0:1 } {print}' projects/x.sfx > /tmp/solo.sfx
JAVA_TOOL_OPTIONS=-Dsfxlab.noautosave=true ./sfxlab --render /tmp/solo.sfx /tmp/solo.wav
python3 tools/music/probe.py sections /tmp/solo.wav 1.4 intro:0:4 drop:4:12
```

Rough targets from the pieces so far (RMS over a section): lead and riff -15 to -18 dB, kick about
6 dB under the riff (transients read low), beds -25 to -30, mix -12 to -17 in the loud parts; peak
under -3 dBFS stereo (the mono fold adds ~3 dB).

For live playback in the GUI, keep clip counts to a few hundred and granular voices low: the
sample-heavy hymn variants (~1100 clips, choirs of 6-10 voices) rendered at only 0.7x real time and
stuttered live; `tritave_signal` (317 clips) renders 3x faster than real time.

## Ways to vary a script

- a second random stream (`VR`) for clips only a variant adds keeps every shared layer's seeds, so
  variants differ only where they should (`hymn_for_a_dead_coil.py`, `_burn`)
- write the musical material as short lists (riff steps, melody, chords) and the arrangement as calls
- ideas not yet tried: the amp stage in the engine; the D-major cello turned minor through partials
  `gather`; binds and signals (the regulator's live controls) driving a piece; other tunings
  (7-EDO, just intonation, a scale from a bell's own partials)
