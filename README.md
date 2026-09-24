# SfxLab

A timeline sound-effect workbench in one Java file. It began as SynthLab, a
spellcasting-audio toy, and grew into a small DAW built for game sound: synth
generators and recordings on six tracks, per-clip effects, a global musical key,
spectral (sines + residual) re-pitching of recordings, and a "forge" that
promotes a finished sound into a key-set of files for a Minecraft mod.

The design notes behind the tuning features are in
[AUDIO-MAGIC-NOTES.md](AUDIO-MAGIC-NOTES.md).

## Requirements

- Java 21 (or newer); the whole lab is `SfxLab.java`, run in source-file mode, no build step
- `ffmpeg` on the PATH for anything that is not a plain wav: mp3/ogg/flac samples, video reference, ogg export
- optional: Python 3 with numpy and scipy for `tools/partials.py`, the standalone prototype of the partials analysis
- more cores help: clips render in parallel (bit-identical to the single-threaded path); partials analyses are cached in `forge/.parts/`

## Run

```sh
git clone https://github.com/BoobooBubberson/SfxLab.git
cd SfxLab
java SfxLab.java
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
java SfxLab.java --render projects/pyretic_synth.sfx renders/pyretic.ogg --mono --normalize [--key 7]

# promote a sound or project into a tuned key-set (residual, sines, one file per key)
java SfxLab.java --forge projects/pyretic_synth.sfx --name pyretic --root C2
```

## Layout

| path | what |
|---|---|
| `SfxLab.java` | the lab; the doc comment at the top is the full manual and key map |
| `projects/` | named `.sfx` projects (S stamps here, O opens from here); `examples/` are the notes' recipes; `pyretic_regulator.sfx` is the regulator palette |
| `spells/` | spell signatures for the regulator: the palette's layers at their lock values, one bench file per spell |
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
needs something else: a palette of layers that all sound at once and are steered by
the machine's signals rather than by time. **H** swaps the timeline for that bench.

- Every layer is a clip with no position. Endless layers loop for ever while the
  bench plays (**SPACE**); a layer marked one-shot (right-click its row) fires on the
  lock or unlock event instead. Rows have mute / solo boxes and a level bar.
- Add layers with the palette keys, **W** import, the sample browser (**A**), or
  **shift+H** from a selected timeline clip. **DEL**, **D**, **C**, **T**, **R** work on layers.
- Right-click a slider to mark the **range** that sounded good, with a note, or to
  **bind** a signal to it. Marked ranges are the default bind ranges, so the
  listening notes become the machine's data. Bound params show a dot and a white
  tick at the live value.
- **J** docks the regulator panel: a slider per signal (the scrubber), a **score**
  slider, the signature picker, **lock** / **unlock** buttons, the bind table,
  the ranges and free notes. The **binds** box switches every bind off so a layer
  can be auditioned at its saved params (solo it with **P**). While the machine
  window is open and driving, the sliders follow it and are greyed out.
- A **signature** is the same layers at their lock values, saved into `spells/`
  with the `signature` button. Pick it in the panel and drag score toward 1: shared
  params blend from their searching values to the signature's, layers only the
  signature has fade in, and lock fires its one-shots.
- **S** stamps the bench as a palette in `projects/`; the workspace autosaves to
  `bench.sfx`. `projects/pyretic_regulator.sfx` with `spells/firebolt.sfx` and
  `spells/torchlance.sfx` is the worked example.
- **U** opens the machine: the regulator itself (crank, arms, motion levers, latch,
  phase and reach, the research setpoint, the voice lever, the copy socket) around
  `RegulatorCore`, with the ribbon and the pinned blueprint. With "drive the bench"
  on, its signals replace the panel's sliders and its lock / unlock events fire the
  bench's one-shots, so the puzzle is played and the palette answers.

`RegulatorCore` is the machine with no Swing or Minecraft in it: crank physics, the
lever state machine, figure sampling, recipe matching with shape equivalence, the
setpoint and copy socket, and the signal contract. It is the second top-level class
in `SfxLab.java` (Java 21's single-file launcher cannot load a second source file);
the mod copies it verbatim. The web prototype in `docs/` is its test oracle.

`.sfx` files are plain text, one clip per line, so they diff and merge like
code and clip lines can be copied between projects and the library by hand.

## Contributing samples

Recordings go in a subfolder of `samples/` with a row in `samples/CREDITS.md`
naming the author, license and original title. Only files that are clear to
redistribute are committed; everything else stays local under the git-ignore
rules.

## License

The code and the in-lab synthesis are MIT (see `LICENSE`). Downloaded
recordings keep their own terms, listed per file in `samples/CREDITS.md`.
