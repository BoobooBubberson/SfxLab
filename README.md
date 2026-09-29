# SfxLab

A timeline sound-effect workbench in plain Java. It began as SynthLab, a
spellcasting-audio toy, and grew into a small DAW built for game sound: synth
generators and recordings on six tracks, per-clip effects, a global musical key,
spectral (sines + residual) re-pitching of recordings, and a "forge" that
promotes a finished sound into a key-set of files for a Minecraft mod.

The design notes behind the tuning features are in
[AUDIO-MAGIC-NOTES.md](AUDIO-MAGIC-NOTES.md).

## Requirements

- Java 21 (or newer). The authoring GUI is `SfxLab.java`, run in source-file mode; the runtime it shares with the
  mod (engine, live mix, `RegulatorCore`) is in `runtime/`, which `./sfxlab` compiles into `.build/runtime.jar`
  whenever a source there changes (about a second), so there is still no build step to run by hand
- `ffmpeg` on the PATH for anything that is not a plain wav: mp3/ogg/flac samples, video reference, ogg export
- optional: Python 3 with numpy and scipy for `tools/partials.py`, the standalone prototype of the partials analysis
- more cores help: clips render in parallel (bit-identical to the single-threaded path); partials analyses are cached in `forge/.parts/`

## Run

```sh
git clone https://github.com/BoobooBubberson/SfxLab.git
cd SfxLab
./sfxlab
```

The folder holding `SfxLab.java` is the workspace: `samples/`, `projects/`,
`forge/`, `renders/` and `lab.cfg` live inside it. Set `SFXLAB_DIR` to use a
different folder; running from elsewhere without it falls back to `~/synthlab`.

Press **O** and open `projects/pyretic_synth.sfx` to see a real project. Its
samples are in the repo. The three files in `projects/examples/` are the recipes
referenced by the notes; they need library recordings that are not tracked (see
`samples/README.md`), so they load with silent clips until you supply those.

Headless, without the GUI:

```sh
# render a project to wav or ogg
./sfxlab --render projects/pyretic_synth.sfx renders/pyretic.ogg --mono --normalize [--key 7]

# promote a sound or project into a tuned key-set (residual, sines, one file per key)
./sfxlab --forge projects/pyretic_synth.sfx --name pyretic --root C2
```

## Layout

| path | what |
|---|---|
| `SfxLab.java` | the lab; the doc comment at the top is the full manual and key map |
| `projects/` | named `.sfx` projects (S stamps here, O opens from here); `examples/` are the notes' recipes |
| `regulator/` | one file per regulator family, `<family>.sfx`: the palette that plays through the tuning, then a `spell <id>` section per spell (recipe, lock values, binds) |
| `docs/` | the Harmonic Regulator design handover and its web prototype (the spec for the machine) |
| `samples/` | recordings; only `pyretic/` is tracked, see `samples/README.md` |
| `library.sfx` | the clip library (B banks a clip, Q browses) |
| `combos.txt` | SynthLab-era parameter combos (, and . browse them, I inserts) |
| `tools/` | `partials.py` prototype and `sfx_batch.sh` batch cleaner for raw recordings |
| `legacy/` | `SynthLab.java`, the original spellcasting playground, and its presets |
| `lab.cfg.example` | template for the git-ignored `lab.cfg` |
| `forge/`, `renders/`, `video-cache/`, `samples/.decoded/` | outputs and caches, git-ignored |
| `bench.sfx` | the bench's autosave (git-ignored), like `project.sfx` for the timeline |

## Working in the lab

The header comment in `SfxLab.java` documents every generator, parameter and
key. The short version:

- **1..9, 0** drop a palette clip at the playhead; **W** imports a recording; **A** docks the sample browser
- **SPACE** play, **ENTER** rewind, **L** loop, **P** solo-preview the selected clip
- drag clips to move or retrack, drag the right edge to resize, the left edge to trim, **X** splits at the playhead
- **C** cycles a sample clip through choir and partials; **R** pins a recording's pitch to the root, **< >** transpose the whole project
- **K** drops a marker; **V** attaches a reference video, **M** opens its monitor
- **B / Q** bank a clip to the library and browse it; **, .** browse combos
- **S** stamps the timeline into `projects/<name>.sfx` (the workspace itself autosaves to `project.sfx`); **O** opens one; **N** clears
- **E** exports the mix (wav or ogg, mono, normalize, trim); **F** opens the forge
- **ctrl+Z / ctrl+Y** undo and redo; **G** toggles snap
- **H** switches to the bench, **J** docks the regulator panel (below)

## The bench

The timeline crafts one-shots. The Harmonic Regulator (`docs/HARMONIC-REGULATOR.md`)
  How the families were authored (register plan, chord-tone voices, pacing, the
  subtraction trick, the measurement loop): `docs/REGULATOR-FAMILIES.md`.
needs something else: a palette of layers that all sound at once and are steered by
the machine's signals rather than by time. **H** swaps the timeline for that bench.

- Every layer is a clip with no position. Endless layers loop for ever while the
  bench plays (**SPACE**); a layer marked one-shot (right-click its row) fires on the
  lock or unlock event instead. Rows have mute / solo boxes and a level bar.
- Add layers with the palette keys, **W** import, the sample browser (**A**), or
  **shift+H** from a selected timeline clip. **DEL**, **D**, **C**, **T**, **R** work on layers.
- Right-click a slider to mark the **range** that sounded good, with a note, or to
  **bind** a signal to it. A bind sweeps linearly unless its map says otherwise:
  `steps=3` turns the sweep into a staircase, `scale=penta` (any chord name the
  cloud knows) snaps a pitch bind to the nearest degree, so a signal can step
  through a scale or through octaves instead of gliding. Marked ranges are the default bind ranges, so the
  listening notes become the machine's data. Bound params show a dot and a white
  tick at the live value.
- **J** docks the regulator panel: a slider per signal (the scrubber), a **score**
  slider, the family picker, **lock** / **unlock** buttons and the bind tables. Every
  bind and every signal has an **on** box: off keeps the row but holds it still, so a
  signal's effect is compared live instead of deleted and retyped (`off` on the bind
  line). A filter box narrows the tables by signal, layer or param, or to the selected
  layer; column headers sort; **copy** / **paste…** (ctrl+C / ctrl+V) carry bind lines
  between tables, onto another layer, or into another family. The signal sliders and
  the spell rows fold so the tables get the height. The **binds** box switches every bind and every
  spell blend off so the whole bench plays as saved. While the machine window is
  open, powered and driving, the sliders follow it and are greyed out; **P** then
  solos a layer live through its binds. Cut the machine's power, close it, or
  untick drive and the signals go back to the panel's own values, and **P** plays
  the layer exactly as authored.
- Sounds are organised by **family**, one file each: `regulator/<family>.sfx` is the
  palette that plays through the tuning, followed by a `spell <id>` section per spell,
  the roster. Pick the family in the panel; it autosaves as you work, palette and
  spells alike. Each spell is a **signature**, the
  same layers at their lock values plus its one-shots, and carries its recipe
  (`recipe tier=1 X3p1r0.7 Y2p0`: axis, integer ratio, phase in quarter turns, reach
  target; `rtol=` sets the reach tolerance), which is what the machine and, later, the mod score.
  Edit it with the spell's `recipe…` button in the panel, or design the sigil on the
  machine's arms and press `→ recipe` to write it to the pinned spell; the `new spell`
  button asks for an id and a recipe, prefilled from the machine.
  Both warn when a sigil retraces itself into an open line.
- Every spell blends in by its own score signal (`score.<id>`, a slider per spell in
  the panel, with lock and unlock buttons), above 0.55. The panel stacks a bind table
  per spell under the palette's: the palette's binds shape the searching mix, a
  spell's binds move its own target values, blended in with it, and a bind on the
  reserved layer `spell` (`bind score.firebolt spell blend 0.55 1`) sets when and how
  sharply the spell comes in. Two spells scoring at once
  share the blend, so overlapping recipes are heard while authoring rather than
  discovered in the game. Pinning a spell in the machine only changes the blueprint.
- The bench view lists the palette's layers and then every spell of the family as a
  foldable group of its own layers: each row shows the spell's target level with a
  white tick where the blend sits right now, and selecting a row edits that spell's
  target values in the panel (undo covers it). A palette row's menu pushes the layer
  into a spell at its current values; a spell header's menu deletes the spell.
- A bench with no family is a scratch bench that autosaves to `bench.sfx`; **S** saves
  it as a family, or forks the loaded family under a new name. **N** starts a fresh
  scratch bench and leaves the family on disk. `regulator/pyretic.sfx` is the worked example.
- **U** opens the machine: the regulator itself (crank, arms, motion levers, latch,
  phase and reach, the research setpoint, the voice lever, the copy socket) around
  `RegulatorCore`, with the pen's trail and the pinned blueprint. The crank only winds
  down; latching within the acceptance window snaps a motion to its integer ratio
  (the `on=accept` chime hook), outside it the motion is held detuned and beats.
  Levers down are the motions the trim controls reach and the crank couples to when
  touched; latch snaps them and lets the crank go; a lever up parks its motion. The
  prototype's catching crank and the earlier focus model are there as toggles. With "drive the bench"
  on and the receiver powered, its signals replace the panel's sliders and every spell's match fires that
  spell's one-shots, so the puzzle is played and the palette answers. **Auto-play**
  works the controls toward the pinned spell along a randomised path, with optional
  wrong resonances and an "any spell" mode that keeps chaining locks: pause it when
  the sound goes wrong, adjust the layers, resume.

`RegulatorCore` is the machine with no Swing or Minecraft in it: crank physics, the
lever state machine, figure sampling, recipe matching with shape equivalence, the
setpoint and copy socket, and the signal contract. It lives in the shared runtime,
`runtime/sfxlab/runtime/`, beside the engine (`Engine`, `Voice`, `Clip`), the
partials model, the `.sfx` format (`SfxFormat`) and the live mix (`BenchMixer`: signals,
binds and the spell blend). None of it touches Swing, the file system or ffmpeg (hosts
install a `Samples.loader` and optionally a `Partials.source`), so the mod builds the
same sources. The web prototype in `docs/` is the core's test oracle.

`.sfx` files are plain text, one clip per line, so they diff and merge like
code and clip lines can be copied between projects and the library by hand.

## Tests

```sh
tests/run.sh            # about 50 s; exits non-zero on any failure
```

- `CoreTest`: `RegulatorCore` on its own: shape matching, all three crank models (brake & wells, the free crank,
  the classic catching crank), levers and coupling, events, signals, the pen and the blueprint.
- `GuiCheck`: the regulator panel (binds, mutes, filter, undo) and the machine window playing to a lock and voicing
  the crystal, headless.
- `golden.sh`: 16 reference renders (every timeline project at key 0 and +7, a mono export, and a scripted machine
  approach per family) that must stay bit-identical. After a change meant to alter the sound, listen to the new
  renders in `.build/test/renders/` and accept them with `tests/run.sh --bless`.

The suite renders frozen copies of the family and project files in `tests/fixtures/`, so authoring never turns it
red; `tests/fixtures.sh` refreshes them from the last commit when you want the references to follow. The hashes are
this machine's: they depend on the local ffmpeg and on `samples/minecraft_sounds_assets/`, which git does not track.

## Contributing samples

Recordings go in a subfolder of `samples/` with a row in `samples/CREDITS.md`
naming the author, license and original title. Only files that are clear to
redistribute are committed; everything else stays local under the git-ignore
rules.

## License

The code and the in-lab synthesis are MIT (see `LICENSE`). Downloaded
recordings keep their own terms, listed per file in `samples/CREDITS.md`.
