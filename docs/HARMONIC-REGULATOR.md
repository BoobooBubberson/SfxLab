# Harmonic Regulator — design handover

Written 2026-09-24 at the end of the design and web-prototype phase, as the
starting brief for implementation work in SfxLab and the Minecraft mod. It
records what was decided, the exact behaviour the prototype settled on, and
the proposed shape of the sound split. Read `AUDIO-MAGIC-NOTES.md` alongside
it: that file is the engine's side of the story, this one is the machine's.

The reference implementation of everything in sections 2 to 4 is
`docs/regulator-prototype.html` (open it in a browser; this copy has no
embedded samples, so it plays Web Audio stand-ins). When a port behaves
differently from the prototype, the prototype is the spec unless this doc says
otherwise.

## 1. What the machine is

The Harmonic Regulator is the workbench that voices a crystal: it writes a
spell recipe onto an unvoiced crystal. It is a mechanical harmonograph. A
central receiver crystal is powered and surrounded by tuning arms; each arm can
oscillate in up to three motions (X, Y, Z). Every motion is a sinusoid whose
frequency is an integer multiple of the receiver's own. The sum of all motions
traces a 3D figure, the sigil, around the receiver, and the same numbers drive
the sound. A spell recipe is a sigil: a set of motions with integer ratios and
quarter-cycle phases.

Design intent, in the order it matters:

1. **A puzzle, not a mixing bench.** Controls never map one-to-one onto sound
   parameters. The player reads a target figure and works out which
   oscillations build it.
2. **Visuals and audio carry the same information.** Anything the ear can
   detect (beating, a lock) the eye can too (the figure rolls, then freezes).
   This is also the accessibility guarantee.
3. **Mystical, not mechanical.** No gears or gear items in the player's hands;
   the transmission is hidden. Catching a resonance should feel like the
   crystal answering, not a detent clicking.
4. **The research station is genuinely needed for hard sigils**, because they
   are hard, not because a gate demands it. Free tinkering and accidental
   discovery stay rewarding.

## 2. Decisions log

These were settled through discussion and a playtest of the prototype.

| decision | detail |
|---|---|
| Harmonograph direction | Replaces an earlier gear-ratio idea. Gears are hidden, never items. |
| Recipes are integer ratios + quarter phases | Amplitude (reach) shapes the figure and the mix but is never part of a recipe. |
| Match on shape, not parameters | Any phase set that traces the identical figure counts (section 3.4). |
| Mirrored asymmetric sigils are wrong answers | Symmetric sigils accept their mirrors automatically, because a mirror of a symmetric figure is the same figure. Left/right spell variants are a possible later feature, shelved. |
| Lever model | Down = driven by crank and trim. Up and lit = held. Up and dark = off. |
| Stopping a motion | No release gesture. Bring the crank down to rest (ratio 0) and latch; a motion held at rest is switched off. |
| Motions start at rest | Pulling a lever down does not start the motion; the crank spins it up from 0. |
| Latch snaps | Latching while caught writes the exact integer, never the in-flight value. |
| Arm-agnostic matching | The figure depends only on the sum per axis, so which arm provides a motion does not affect the sigil. Arms differ only in which sound layer they drive. Open question in section 7. |
| Tiers | Tier I: 2 arms × 1 motion. Tier II: 3 arms × 2. Tier III: 3 arms × 3. Arm and motion count are the upgrade path. |
| Research setpoint | Loads the target's motions with ratios off by 0.1 to 0.2 and one phase wrong. Close enough to be audible and nearly coherent, not solved. |
| Copy socket | Reads a voiced crystal back into the machine with ratios off by about ±0.035. Spells are voiced a few times per game (the upgrade moment), never in bulk. |
| Blueprints | Drawn by the research station as a damped harmonograph trace, pinned beside the machine, in two views: front (X–Y) and top (X–Z). Playtest: the views are what make sigils readable; a flat top view alone told the player Fire bolt needs no Z. |
| Sound split | Searching mix (generic, from the figure's structure) blends into an authored per-spell signature as the score rises. Section 5. |

Playtest calibration: Fire bolt (tier I) was solved from the blueprint alone.
Cinder bloom (tier II) took about 5 minutes of failed attempts before the
setpoint, which is the intended balance. Torch lance (tier III) was solvable
with the setpoint; its asymmetric features worked as clues.

## 3. Mechanics, with the prototype's constants

### 3.1 The crank (the single drive)

The crank's speed is the frequency ratio: **ratio = 2 × crank speed in
rev/s**, capped at ratio 8. Every driven motion takes the crank's ratio.

Input, in ratio units:

| input | effect |
|---|---|
| drag | direct control; velocity smoothed (new = old + 0.35 × (measured − old)) |
| scroll notch | ±0.1 (shift: ±0.02) |
| +/− buttons | ±0.25 |

Every nudge sets a **slip** timer of 0.5 s (0.15 s after a drag release). During
slip the crank cannot be caught and does not brake, so the player can push
through resonances.

Free-running behaviour each frame, when not dragged and slip has expired:

1. **Catch at an integer n = 1..8** if |ratio − n| < **0.16 / n**. While caught,
   the speed eases to exactly n (rate 7/s, snapped when within 4e-4 ratio) and
   friction is off, so a caught crank holds indefinitely. Simple ratios are
   wide and easy; high ones are narrow and brief.
2. **Catch at rest** if ratio < 0.12: decays to 0 (rate 9/s). Rest is a catch
   point like any integer.
3. **Otherwise** friction: speed × e^(−0.09·dt). Below ratio 1 there is also a
   linear brake of 0.32 ratio/s, so the zone between rest and ×1 is a dead
   zone: the drive ramps down to rest in about two seconds unless pushed.

Consequence the design relies on: the flywheel only winds down, so to reach a
higher resonance the player overshoots and coasts into it.

### 3.2 Levers, latch and trim

Per motion (arm × axis) state: `off`, `driven`, `held`.

- **off → driven:** tier permitting (per-arm motion count). Starts at phase 0,
  reach 0.7. If no other motion is driven, ratio 0 and the crank is set to rest.
  If others are driven, it joins them at the crank's current ratio.
- **driven → held** (click the lever, or Latch for all driven): if the crank is
  caught, the ratio snaps to the caught integer. A motion held at ratio 0 goes
  to `off`.
- **held → driven:** if it is the only driven motion, the crank loads its ratio
  (and is immediately caught if that ratio is an integer, so it does not slide).
  If others are driven, it joins them at the crank's ratio (ganging).
- **Trim** applies to every driven motion at once: the phase dial steps phase
  by a quarter cycle; the reach control sets reach.

Ganging is a real technique, not a side effect: Cinder bloom is built from
matched pairs (X and Y both ×1, X and Y both ×5).

### 3.3 The figure

For each motion with ratio r, phase p (quarters), reach a:

```
contribution(t) = a · min(1, r / 0.6) · sin(r·t + p·π/2 + (r − round(r))·τ·4.4)
```

summed per axis over all engaged motions, t ∈ [0, 2π) along the trace, τ = real
time in seconds. The `min(1, r/0.6)` envelope fades a motion in from rest, which
also stops a motion at ratio 0 with a non-zero phase from offsetting the whole
figure. The `(r − round(r))·τ` term makes an off-integer motion roll over time,
the oscilloscope behaviour: a rolling figure is detuned, a still figure is
locked. While the receiver is powered and the target is not matched, a
high-frequency noise term is added whose size falls with coherence (the ribbon
starts as jagged noise and settles as motions lock).

### 3.4 Matching

A motion takes part in matching only if it is engaged with reach > 0.04 and
ratio > 0.05. Against a recipe's component list:

- Each recipe component greedily takes the best unused engaged motion on the
  same axis. Component score = e^(−5·|r − n|) × (1 if phase equal, else 0.5).
- Recipe score = mean component score × 0.6^(number of extra engaged motions).
- **Exact** = every component matched with |r − n| < 1e-6 and equal phase, and
  no extra motions. Only exact unlocks the voice lever.

**Shape equivalence.** The score is the best over all phase sets that trace the
identical figure. Two operations generate them:

- start the trace k quarter-cycles later: each ×n component's phase becomes
  p + n·k (mod 4), k = 0..3;
- trace it backwards: p becomes 2 − p (mod 4).

Their combinations give up to 8 equivalent phase sets per recipe. Flipping one
axis alone (p + 2 on that axis's components) is a mirror, not in the set, so a
mirrored asymmetric sigil fails. A symmetric sigil's mirror is already one of
the 8, so symmetric sigils need no special casing.

Recipes should be checked at authoring time for **degenerate traces** (a phase
choice that makes the curve retrace itself into an open line, such as
sin 3t against cos 2t). They are valid but read poorly as sigils.

### 3.5 Recipes in the prototype

`a` = axis (0 X, 1 Y, 2 Z), `n` = ratio, `p` = phase in quarters, `amp` = reach
used only for drawing the blueprint.

```
Fire bolt     tier I    reward fire   X n3 p1 · Y n2 p0
Cinder bloom  tier II   reward lava   X n1 p1 · Y n1 p0 · X n5 p1 (0.35) · Y n5 p2 (0.35) · Z n3 p0 (0.55)
Torch lance   tier III  reward torch  X n1 p0 · Y n2 p1 (0.8) · Z n3 p1 (0.7) · X n4 p2 (0.4) · Y n5 p0 (0.3) · Z n6 p3 (0.25)
Will-o'-wisp  tier I    secret, reward cloud   X n1 p0 · Y n2 p0
```

The prototype's first Fire bolt used p0/p1 and was degenerate; it was changed
to the current values.

### 3.6 Blueprint drawing

Each view plots the recipe with a slow decay, e^(−0.022·t) over four cycles,
and a tiny per-component detune (±0.0025, varied per component). That produces
the moiré of a real harmonograph drawing. Front view: X right, Y up. Top view:
X right, **+Z toward the bottom**, which must match the machine's top camera.
The prototype once had these opposite, and it cost the playtester real time.

### 3.7 Feedback while searching

- **Beating.** Each engaged motion plays a quiet tone at f0 × r (f0 = C3). An
  anchor tone at f0 × round(r) fades in with the square of the target score.
  Near the answer, the two beat, slowing to stillness at lock.
- **Near-miss flicker.** Any recipe scoring above 0.75 fades in its reward
  layer faintly (up to 0.16 of full) — including recipes not on the pinned
  blueprint, and secret ones. That is how accidental discovery happens.
- **Lock.** Exact match: the figure freezes and brightens, the reward layer
  ignites, the voice lever unlocks. Torch lance plays the flamethrower turn-on
  into its loop, and turn-off if the lock is broken. The secret recipe glides
  the cloud from scattered pitches into a harmonic chord (a Deep Note moment).

## 4. The signal contract

The regulator emits these each tick; the sound engine consumes them. The web
prototype, SfxLab's scrubber and the mod should all use this one list.
Formulas marked *proposed* are starting points to tune by ear.

| signal | range | definition |
|---|---|---|
| `arm{n}.ratio` | 0..8 | ratio of arm n's engaged motion with the largest reach (0 if none) |
| `arm{n}.pitch` | 0..12 st | 12·log2(ratio) folded into one octave |
| `arm{n}.reach` | 0..1 | summed reach of arm n's engaged motions, clamped |
| `radiance` | 0..1 | *proposed:* total Z reach, clamped (pylons rising = power radiating) |
| `consonance` | 0..1 | *proposed:* mean simplicity of the ratios between engaged motions, e.g. 2/(a+b) for each pair reduced to lowest terms a:b |
| `tension` | 0..1 | *proposed:* highest engaged ratio / 8 |
| `drive` | 0..1 | crank ratio / 8 |
| `coherence` | 0..1 | mean of e^(−8·|r − round(r)|) over engaged motions (0 if none) |
| `score` | 0..1 | target recipe score (3.4); per-recipe scores also available for flicker |
| events | | `lock`, `unlock`, `discover` (a secret recipe matched), `voice` (crystal written) |

## 5. The sound split (proposed, to build in SfxLab first)

The prototype's audio was a stand-in and sounded too uniform across spells:
every spell shared the same bed and layers, and only the final reward layer
differed. The plan separates two jobs and blends between them.

**Searching mix (generic, per family).** Computed from the signals, so every
configuration sounds alive and distinct while the player explores. Authored
once per family.

**Lock mix (authored, per spell).** A signature: which layers play, their
levels and knob settings, the ignition layers, and one-shots for lock and
unlock. It should be the same asset the casting gauntlet plays in combat.

**Blend.** Every parameter the two share moves from its searching value to its
signature value with w = smoothstep(0.55, 1.0, score). Clips that exist only in
the signature fade in by w (or only at lock, per clip). The spell comes into
focus as the player approaches it.

### 5.1 Proposed `.sfx` extensions

The parser already skips unknown line types and unknown `key=value` tokens, so
these are backward compatible with existing files and older builds.

- **`id=<handle>` on clip lines.** A stable handle for binding and blending,
  since clip names repeat (the pyretic project has three `flamethrower_loop`
  clips).
- **`bind` lines** in a family palette:

  ```
  # bind <signal> <clip id | *> <param> [min max]
  bind arm1.pitch   synth3  pitch            # added to the clip's own pitch, semitones
  bind arm2.pitch   synth8  pitch
  bind radiance     *       reverb  0 0.6
  bind consonance   cloud   gather  0 1
  bind tension      synth1  drive   0 0.05
  bind arm1.reach   synth3  level   0 0.5
  ```

  Ranges should come from the palette notes (for example, synth_00001 drive
  0–0.05; synth_00008 drive 0–0.1, resonance 0–1; fire, lava drive 0–0.2).
  Per the audio-magic notes, partials `gather` disappointed as a chord lever
  and cloud is the best chord player, so consonance binds to cloud's gather.
- **`on=lock` / `on=unlock` on clip lines** in a signature: one-shots fired by
  events rather than the timeline (flamethrower turn-on/turn-off).
- **Files.** Family palette: `projects/pyretic_regulator.sfx` (the current
  pyretic project plus ids and binds). Signatures: `spells/<spell>.sfx`,
  ordinary projects using the palette's clip ids.

### 5.2 Engine work this needs

- **Smoothed live params.** The engine reads each clip's params raw every
  sample. That is fine for slider drags but will zipper when signals move
  continuously. Bound params need a one-pole smoother (a few tens of ms).
- **Seamless beds.** When a looping project wraps, the realtime loop clears all
  voices, so a regulator bed would click at every wrap (18.8 s for the pyretic
  project). Live beds should use long clips that loop internally rather than
  relying on the timeline loop.
- **Per-arm pitch** goes through each clip's `pitch` param, not the global key.
  On partials clips that moves only the sines, which is exactly right.
- **CPU in the mod.** Several partials banks plus cloud or choir voices at
  once should be measured early. The forge's baked per-key files are the
  fallback if runtime resynthesis is too heavy.

### 5.3 As built in SfxLab (2026-09-24)

Step 1 of section 6 is done, with one change of shape from 5.1: the palette is
not a timeline project with ids added but a **bench**, a list of layers with no
timeline position (SfxLab's `H` view). The reasons: the 6-track timeline was a
bottleneck for a palette, and the searching mix and the lock mix are both
snapshots in signal space, not arrangements. The file format is the `.sfx`
line style, which the timeline parser skips:

```
layer <id> <name> <type> <dur> <seed> key=value... [on=lock|unlock] [mute=1]   dur 0 = endless
range <id> <param> <lo> <hi> [note]      the span that sounded good (authoring notes; default bind range)
bind <signal> <id|*> <param> [lo hi] [rel]   no range = the layer's marked range, else the full spec range
palette projects/x.sfx                   (signature files) the palette they were authored against
note <free text>
```

- Binds target absolute knob positions (the modulation is the difference from
  the saved value); `rel` binds add to the layer's own value, which is what
  pitch wants. Several binds on one param add up, so two opposed level binds
  are a crossfade: `bind tension synth1 level 0.8 0` next to
  `bind tension synth3 level 0 0.7` fades one bed into the other. That is how
  the "beds shared by all arms, morphing as you tune" idea is tried without code.
- `arm{n}.pitch` is derived from `arm{n}.ratio` in the tool (12·log2, folded).
- Bound params go through a 30 ms one-pole smoother in the engine; the
  harmonic controls (which rebuild per-partial caches) are stepped at 1/100 of
  their range so a glide costs a few rebuilds rather than one per sample.
- A signature is a bench file in `spells/` with the same ids at their lock
  values; shared params blend by `w = smoothstep(0.55, 1, score)`, signature-only
  layers fade in by `w`, one-shots marked `on=lock` / `on=unlock` fire on the
  events (the `lock` / `unlock` buttons in the panel, later the core). A
  palette layer the signature omits keeps its searching value; to silence a
  layer at lock, the signature includes it at level 0.
- Live beds: the bench never wraps (endless layers have no end), so the
  timeline-loop click in 5.2 does not arise.
- Deliverables: `projects/pyretic_regulator.sfx` (8 layers, the palette notes'
  ranges, a starting bind set with the arm-to-layer question left open in its
  `note` lines), `spells/firebolt.sfx` and `spells/torchlance.sfx` as first
  signatures to tune by ear. Old projects render byte-identical.

### 5.4 Step 2 as built: `RegulatorCore` and the machine window (2026-09-24)

`RegulatorCore` is a second top-level class in `SfxLab.java` (Java 21's
single-file launcher cannot load a second source file; the mod copies the
class verbatim). It has no Swing or Minecraft in it and ports sections 3.1
to 3.4, the setpoint / copy socket / voicing, the blueprint trace and the
signal contract, with the prototype's constants. Its API: `setTarget`,
`power`, `selectArm`, `axisLever`, `latch`, `phaseStep`, `setReach`,
`nudge` / `dragStart` / `dragVelocity` / `dragEnd`, `loadSetpoint`,
`applySnapshot`, `voice`; `tick(dt)` each frame; then `signals[]`
(`SIGNALS` order, `pitch(arm)` derived), `eval` per recipe, `targetEval`,
`noise`, and `events()` (`lock`, `unlock`, `discover:<id>`, `wrong:<id>`,
`voice`, `stopped:<arm>:<axis>`). `figurePoint`, `armVector`, `extent` and
`blueprint(recipe, view)` draw the ribbon and the pinned sigil (top view
returns −Z so +Z draws toward the bottom). `degenerate(recipe)` is the
authoring check from 3.4.

56 headless checks cover it: shape equivalence (Fire bolt has 8 equivalent
phase sets and is symmetric, so its mirror counts; Torch lance mirrored on X
fails), scoring formulas, every crank regime (slip, catch, hold, dead-zone
brake, coasting into a resonance, the cap at 8), the lever state machine
and ganging, the setpoint's jitter and single wrong phase, the copy socket,
lock / unlock / discover / voice events, the signal formulas and the
blueprint geometry.

Two behaviours worth knowing that the tests made explicit:

- Friction runs during the slip timer (only the catch and the dead-zone
  brake wait for it). A scroll to exactly ×3.0 therefore decays to 2.87
  during the 0.5 s slip, outside the ×3 window (±0.053), and misses; one
  notch of overshoot (3.1 → 2.96) catches. This is the "overshoot and coast"
  rule of 3.1 in practice, and it is why the wide windows at ×1 and ×2
  forgive and the narrow ones above do not.
- Signals not fixed by the prototype were implemented as the section 4
  proposals: consonance is the mean of 2/(a+b) over pairs of engaged
  motions with their ratios rounded and reduced (1 for a single motion, 0
  for none); radiance is total Z reach; tension is the highest ratio / 8.

`U` in SfxLab opens the machine: the prototype's panel (blueprint picker,
power, arms, motion levers, crank with nudges and latch, phase and reach,
setpoint, voice lever, shelf and copy socket, "show values") around a
painted stage (ribbon with the receiver's noise, arms, front / top / orbit
views, the blueprint strip). With "drive the bench" on, the core's signals
replace the regulator panel's sliders every frame and lock / unlock fire
the bench's one-shots, so the puzzle is played and the palette answers.
That closes the loop for step 3: the mod's block entity runs the same core
and the same palette, signature and bind files.

## 6. Order of work

1. **SfxLab: format and scrubber.** Implement `id=`, `bind`, signatures and
   `on=` events, the smoothed live params, and a **signal scrubber** panel: a
   slider per signal plus a score slider and a signature picker, with the
   palette looping and the signature blending in. Authoring sound needs this,
   not the puzzle. Deliverables: `projects/pyretic_regulator.sfx` and a first
   `spells/firebolt.sfx`. This settles the format the mod's engine must mirror,
   which ends the chicken-and-egg between the two.
2. **Shared core.** A plain Java `RegulatorCore` with no Swing or Minecraft
   dependencies: crank physics (3.1), lever state machine (3.2), figure
   sampling (3.3), matching with shape equivalence (3.4), recipes, and signal
   computation (4). SfxLab's scrubber can later drive it; the mod's block entity
   uses it directly. Port the prototype's constants exactly; the HTML is the
   test oracle.
3. **Minecraft.** The block entity and its state, the control-panel screen,
   ribbon and arm rendering, the sound engine consuming signals and signatures,
   the voice lever writing the recipe to the crystal item, then the copy socket
   and research station.

## 7. Open questions

- **Should arms matter to the answer?** Matching is arm-agnostic today, so arms
  differ only in sound. If arm assignment should be part of a recipe, it needs
  a visible difference too (pylon colour, ribbon texture), or the rule will
  feel arbitrary.
- **Family differences.** Pyretic is the only palette. Other families could
  differ in damping, blueprint style, which motions they favour, or catch
  widths, so each family plays a little differently rather than being a reskin.
- **Precision and quality.** The lock is binary. If near-integer imprecision is
  ever used, keep it cosmetic or stability-related so it does not become
  stackable modifiers again.
- **Research station.** The blueprint drawing is decided; its own gameplay is
  not.
- **Roster.** 10–14 spells across tiers, plus secrets. The prototype suggests
  there is plenty of distinct sigil space; each recipe needs the degeneracy
  check (3.4) and a readable front/top pair.
- **Multiplayer.** Machine state is server-authoritative; audio and ribbon are
  client-side from synced motion state.
- **Left/right variants.** Mirror pairs as spell variants, shelved for later.
