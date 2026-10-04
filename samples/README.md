# samples/

Recordings that sample, choir and partials clips play. Projects reference them
by path relative to this folder (`file=pyretic/fire_loop.ogg`); if a file has
since been sorted into another subfolder, SfxLab falls back to the first file
under `samples/` with the same name.

Tracked in git: the sets the regulator families play (`pyretic/`, `gravitic/`,
`luminal/`, `aqueous/`, `cryic/`, `resonant/`), all cleared for use (see `CREDITS.md`).
Everything else in the local library is git-ignored until its license has been checked.
A family layer must play an `.ogg`: the game reads nothing else, and the bake refuses
other formats.

Adding a recording: press **W** in the lab (anything ffmpeg can read; non-wav
files are decoded into `.decoded/` on first use), or drop files here and use the
docked browser (**A**). `tools/sfx_batch.sh` batch-cleans raw recordings
(declip, normalize, mono ogg) the way the Audacity workflow did.
