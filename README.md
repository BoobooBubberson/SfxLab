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
| `projects/` | named `.sfx` projects (S stamps here, O opens from here); `examples/` are the notes' recipes |
| `samples/` | recordings; only `pyretic/` is tracked, see `samples/README.md` |
| `library.sfx` | the clip library (B banks a clip, Q browses) |
| `combos.txt` | SynthLab-era parameter combos (, and . browse them, I inserts) |
| `tools/` | `partials.py` prototype and `sfx_batch.sh` batch cleaner for raw recordings |
| `legacy/` | `SynthLab.java`, the original spellcasting playground, and its presets |
| `lab.cfg.example` | template for the git-ignored `lab.cfg` |
| `forge/`, `renders/`, `video-cache/`, `samples/.decoded/` | outputs and caches, git-ignored |

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

`.sfx` files are plain text, one clip per line, so they diff and merge like
code and clip lines can be copied between projects and the library by hand.

## Contributing samples

Recordings go in a subfolder of `samples/` with a row in `samples/CREDITS.md`
naming the source and license. Only files that are clear to redistribute are
committed; everything else stays local under the git-ignore rules.
