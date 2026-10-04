# Authoring regulator families — approach and lessons

Notes written 2026-09-27 after building the first four families on the bench, and
revised 2026-10-03 after rebuilding them and adding three (cryic, voltaic, resonant).
The revision is section 0, which overrules what it contradicts below. This is the
thought process, not the file format: the format is in HARMONIC-REGULATOR.md
§5.3, the signals in §4, the engine details in AUDIO-MAGIC-NOTES.md.

Everything below was decided by measurement (tools/bench) and by the user's
ears in turn. Nothing was judged by my ears, because I have none; where a
choice could only be made by listening, it is flagged as such in the palette's
`note` lines.

## 0. What changed on 2026-10-03: notes from oscillators, recordings as texture

The first four families made their notes out of recordings (partials lifted to the
chord tones, or a stream through band-passes). They had character and they were
tiring: the user heard clashing and could not say why. Measured, the pitched
recordings were not on their notes. A flanged wind pad was a cluster (C, D, D#, F#,
G#, A, many 20 to 40 cents off); a synth loop used as the root was mostly A and E; a
bed tuned by its estimated fundamental sat a semitone flat. Every family was rebuilt
on one rule, and each rebuild was the version the user kept:

- **Everything pitched is an oscillator.** Tones, the cloud, the arc. The pitch is
  exact and the family can be listened to for minutes, which a puzzle needs.
- **Recordings are texture and accents.** Unpitched ones (fire, stream, crackle)
  play as they are, on the orb or on stir. Pitched ones come back only gathered:
  a partials layer with `gather` 1 pulls every partial onto C or G and leaves the
  noise alone, so the flicker and grit that gave the family its character stay and
  the wrong notes go. Oscillators alone were exact and generic; the gathered loops
  are what brought pyretic's fire back.
- **One synth idiom says the element.** A lightsaber hum for voltaic (doubled saws a
  few cents apart, low-passed, a parked comb), bowed glass for cryic (sines with a
  bell-stretched bank), a sample-and-hold resonant low-pass for aqueous (the classic
  burble), FM growl for pyretic, a throat-sung drone and struck resonators for
  resonant. Realism was the wrong target: the faithful Tesla-coil arc was painful
  in seconds and survives only as accents.
- **Each family has its own way of sounding in tune.** `coherence` slows a beat
  (voltaic), stops a tremble (cryic), stills a burble (aqueous), cleans an FM growl
  (pyretic), brings a pitch swing to rest (gravitic), gathers a vocal (luminal),
  turns random strikes into a ringing tone (resonant). The table is in
  AUDIO-MAGIC-NOTES.md, section 9.
- **Check a pitched recording before trusting it.** Take the strongest spectral
  peaks of the file as tuned and name their pitch classes. One dominant class within
  a few cents is a note; several is a cluster and wants `gather` 1 or a place among
  the accents. `tools/padloop.py` prints this for a recording and makes a struck
  or fading one into a level, seamless loop.
- **Reach hints.** `fit` and `hand.fit` (HARMONIC-REGULATOR.md, section 4) are read
  against the recipe the planted stations point to. Bind `fit` so a voice steadies
  when the reaches are right; bind `hand.fit` as a small highlight that adds nothing
  at 0.
- **Recordings a family plays must be `.ogg`.** The game reads nothing else and the
  bake refuses other formats.

Sections 2 to 6 and 8 still hold. Section 7's recipes for making notes out of
recordings (lifted partials, the band-pass subtraction) are how the first drafts
were built and are no longer how a family's notes are made; its level and profiling
advice still applies to the texture layers.

## 1. What a family has to do

While a player searches, the bench has to say four things at once, and each
needs its own carrier so they do not mask each other:

- **that the machine is alive** — a bed that is always there at rest;
- **which notes the ratios are reaching for** — voices that come in as the
  ratios approach the chord tones;
- **how in tune it is** — something that changes character with coherence;
- **what the orb is doing** — colour that follows the pen, not the arms.

At lock the spell file takes over the chord and adds its own textures and
one-shots. The searching mix should be interesting on its own for a long time,
because that is where the player spends most of the session.

## 2. The register plan

The single biggest lesson. The first two pyretic attempts turned to mud
because every pitched layer sat in the same low octave (C1–C2) with reverb
and echo on the bass. Below about 130 Hz the ear cannot separate the notes of
a chord; a third added to a C2 rumble is just more rumble.

So every family is voiced by register before anything else:

- **one bass layer only** (the bed), no echo, little or no reverb;
- **chord-tone voices at C3–C4** (or, in luminal, an open bass voicing with
  the third no lower than E3);
- **textures and the chord pad above that**, where reverb does no harm.

Where a recording is all sub-bass (gravitic, most of aqueous), the voices are
its partials lifted two or three octaves with the residual switched off, so
the rumble stays in the bed and the note goes where it can be heard. Where a
recording is all treble (luminal), the voices go to the bass instead.

## 3. Notes come from chord tones, not arms

`arm{n}.pitch` reports only the largest-reach motion on that arm, so the
small-reach chord tones of a tier-III recipe never became notes. The
`tone.root/third/fifth/seventh` signals fixed that: each is the reach sitting
on that pitch class from any engaged motion, whichever arm holds it, weighted
by how close the folded pitch is (a ratio gliding 1 → 2 sings root, third,
fifth, seventh, root). Every family now binds one voice per tone. The tuning
process is arm-agnostic; the mix is too.

Reach enters as √reach, so a quarter-reach motion still sings at half.
`fit` (reach agreement with the pinned recipe, over matched components only)
is the reach hint: bound to shimmer or resonance, a wrong reach leaves a note
wobbling and the right one lets it ring. `stack` (engaged motions / 6) makes
the bed step back and the chord pad grow as the machine fills.

## 4. The score is a switch, not a slope

A recipe's score only rises in the last third of a ratio before the integer,
and the latch snaps to it, so the blend from searching mix to lock mix runs in
about a third of a second. Do not expect the spell blend to carry any
evolution. Evolution lives in the searching mix's continuous binds; the spell
file is the reward.

Consequences for spell files:

- set only the bed, the chord pad (its chord and level), textures and
  one-shots — never the voices' pitches, which must keep following the tones;
- give each spell a different chord on the pad (power, major, major 7,
  harmonic 7, minor) since the arms alone give the same triad to most
  tier-II/III recipes; only a ratio of 7 lights the seventh voice;
- check every recipe with tools/bench's Validate (buildable at its tier,
  non-degenerate); one phase set in five traces an open line.

## 5. The road from rest to chord

The user's words for the first tone-driven pyretic: "bubbling lake → orchestra
too fast, turns fully into music too quickly". The fix was pacing:

- the chord pad grows with `stack`, not `stir` (one motion is a whisper);
- it starts half-gathered with few voices, so the searching cluster is soft;
- the voices sit 6 dB or so under the bed until the machine is half full;
- the orb's colour (filters, phaser and flanger positions) needs headroom,
  so those layers must not be buried by the pad.

Measured targets that have read as "clear" so far: no band over ~60 % of the
energy, no limiter saturation, at lock the notes, pad and bed within about
6 dB of each other, one motion at −30 to −40 dB relative to a full lock at
−18 to −24.

## 6. The orb is "where", not "how much"

Signals bound to an effect's mix are a throttle; bound to its position they
are the picture. `ph pos`, `flange pos` and `lfo pos` park the phaser notch,
the comb delay and the LFO phase directly, so the orb steers them with the
mix fixed. Every orb → phaser hook was rebound that way and the
audio-visual link tightened noticeably. Radius → filter cutoffs, curl →
positions and shimmer, speed → rates and crackle, accel → drive have been the
useful pairings.

## 7. Working with the recordings you have

Profile first (tools/bench/BenchRender's sibling scripts; band split, RMS,
crest, tonal share and fundamental from the partials analyser). What the
numbers told us each time:

- **sub-heavy sets** (gravitic): lift voices as sines-only partials, add a
  halo layer (the bed's own partials an octave up) so the family survives
  small speakers, let the sub grow with `stack`.
- **treble sets** (luminal): textures high, voices in the bass, the reward a
  treble layer gathered onto the chord with coherence.
- **noise sets** (aqueous): make the notes by subtraction — copies of one
  loop through key-tracked resonant bandpasses at the chord tones (cutoff
  u = ln(f/40)/ln(250)), level from `tone.*`, resonance from `fit`. The
  recording stays intact underneath, which the user liked most of all.
- **peaky recordings** (a stream: RMS −25 dB, crest 16): bandpassed copies
  come out 10 dB quiet. `drive` ahead of the filter is a tanh soft limiter;
  0.12 lifts the body ~12 dB. Normalising would not have helped (the peaks
  were already near full scale).
- a loop of droplets analyses as 90 % sinusoidal: its partials, gathered
  onto the chord, are a reward layer for free; bind its `speed` to coherence
  and the water freezes when the ratios are perfect.
- the harmonic controls (gather, stretch, purity) are subtle on most
  recordings but they are exactly what a "less physical" variant wants.

## 8. The loop

1. Profile the samples; decide the register plan and what carries each of
   the four jobs in §1.
2. Write the palette with `range` lines that explain every bind's range in
   words, and `note` lines that say what the family is.
3. Write spells (tier II+), validate the recipes.
4. Render a scripted approach per spell tier with tools/bench and read the
   per-second table: loudness, saturation, band split, per-layer
   contribution.
5. Fix by measurement until the §5 targets hold; then hand over for ears.
6. Take the user's listening notes as the next design input — every
   revision so far came from one sentence of theirs.

Gotchas: a `layer` line needs `file=` (the name column is only a label);
a partials clip must be analysed before a headless render (`partials(c,
true)`); regex edits on `key=value` lines must not eat the newline; and the
JVM does not exit after a headless run without `System.exit`.
