"""The cat recordings in samples/cat/ as instruments.

The <name>.ogg files are the Pixabay mp3s beside them (see samples/CREDITS.md) run through
tools/sfx_batch.sh: declipped, peak-normalised to -1 dBFS, mono.

Measured 2026-11-09 (harmonic-sum pitch over the loud part; see the README's cat notes). Pitch in
semitones from A2 = 110 Hz, like every clip; `start`/`end` are the loud part in seconds.

    from meows import MEOWS, meow, mew, meow_choir, meow_partials, meow_hold
    meow(s, track=3, beat=8, beats=1, pitch=29)            # a meow on D5

A sample clip's `pitch` shifts the recording, so each call shifts by (target - the meow's own pitch).
In tape mode (the default) a shift also changes the length; `keep=True` keeps the length (granular).
"""

CAT = "cat/"
MEOWS = {
    # name: (file, pitch, loud start s, loud end s, file length s, what it is). The pitch is where each
    # meow sounds as the engine plays it (calibrated by rendering: middle of the note, within 0.05 st)
    "kitty":   ("kitty.ogg", 33.70, 0.02, 0.48, 0.60,
                "steadiest of all (IQR 0.4 st), clean harmonics: the lead"),
    "garage1": ("garage1.ogg", 30.71, 0.08, 0.62, 0.86,
                "a classic meow, small fall at the end"),
    "garage8": ("garage8.ogg", 31.27, 0.16, 0.52, 0.86,
                "a 'mi-ow': rises 27.5 -> 31.3 in its first 0.1 s, settles at 29.6; colour, not a held pitch"),
    "u6":      ("u6.ogg", 30.96, 0.16, 0.84, 1.08,
                "the longest steady meow, a vowel that opens"),
    "kitten":  ("kitten.ogg", 34.99, 0.04, 1.56, 2.11,
                "four mews: chop it (MEWS)"),
    "angry":   ("angry.ogg", 35.00, 0.38, 1.04, 1.54,
                "a yowl rising then falling, growly"),
    "sigh":    ("sigh.ogg", 24.8, 0.12, 1.30, 1.51,
                "falls ~640 -> ~460 Hz (30.4 -> 24.5 st): tuned by where it lands"),
}
# the kitten file's four mews: (start s, end s, pitch, gain dB)
MEWS = [(0.06, 0.33, 35.05, 3.3), (0.40, 0.79, 35.10, -2.0), (0.84, 1.08, 36.85, 0.6), (1.24, 1.54, 34.65, -1.5)]
# keep=True holds a meow from a steady stretch of it, if it has one: (start s, pitch there)
HOLD = {"u6": (0.55, 29.9)}   # u6 is "mee-ow": up to 32.4 by 0.3 s, then steady at 29.9 from 0.55 s
# dB added to `level` so every meow sits near -15 dB RMS while it sounds (measured)
GAIN = {"kitty": 1.0, "garage1": -1.3, "garage8": -0.5, "u6": -0.5, "kitten": 0.0, "angry": 2.0, "sigh": 0.0}


def _lv(level, db):
    return min(1.0, level * 10 ** (db / 20))


def _clip_len_beats(song, seconds, shift):
    """A tape shift of `shift` semitones plays 2^(shift/12) faster."""
    return seconds / (2 ** (shift / 12)) / song.spb


def meow(song, track, beat, beats, pitch, which="kitty", level=0.5, keep=False, pan=0.0, name="meow", **kw):
    """One meow on `pitch`. Tape mode by default: shorter when higher. `beats` caps the length;
    with keep=True the meow is held to `beats` at its own speed (granular, can stretch)."""
    f, own, t0, t1, flen, _ = MEOWS[which]
    if keep and which in HOLD:
        t0, own = HOLD[which]
    shift = pitch - own
    start = max(0.0, t0 - 0.02) / flen
    if keep:
        length = beats
        extra = dict(pitch_mode=1, speed=kw.pop("speed", 1.0))
    else:
        natural = _clip_len_beats(song, (t1 - t0) + 0.08, shift)
        length = min(beats, natural) if beats else natural
        extra = {}
    song.clip(name, "sample", track, beat, length, level=_lv(level, GAIN[which]), attack=kw.pop("attack", 0.005),
              release=kw.pop("release", min(0.08, length * song.spb * 0.3)), pitch=shift, file=CAT + f,
              start=start, pan=pan, **extra, **kw)
    return length


def mew(song, track, beat, pitch, k=0, level=0.5, pan=0.0, **kw):
    """One of the kitten's four mews (k = 0..3), on `pitch`, tape mode: a short staccato note."""
    f, _, *_, flen, _ = MEOWS["kitten"]
    a, b, own, gdb = MEWS[k % 4]
    shift = pitch - own
    length = _clip_len_beats(song, (b - a) + 0.03, shift)
    song.clip("mew", "sample", track, beat, length, level=_lv(level, gdb), attack=0.003,
              release=min(0.06, length * song.spb * 0.4), pitch=shift, file=CAT + f, start=a / flen,
              pan=pan, **kw)
    return length


def meow_choir(song, track, beat, beats, pitch, chord, which="u6", level=0.4, voices=6, gather=1.0, **kw):
    """A choir of copies of one meow, gathered onto a chord (choir clip: granular, holds as long as asked)."""
    f, own, t0, t1, flen, _ = MEOWS[which]
    song.clip("meow_choir", "choir", track, beat, beats, level=_lv(level, GAIN[which]), attack=kw.pop("attack", 0.15),
              release=kw.pop("release", 0.4), pitch=pitch - own, chord=chord, file=CAT + f,
              start=(t0 + 0.05) / flen, speed=kw.pop("speed", 0.25), voices=voices, gather=gather,
              drift=kw.pop("drift", 0.2), wander=kw.pop("wander", 1), scatter=kw.pop("scatter", 0.3), **kw)


def meow_partials(song, track, beat, beats, pitch, which="kitty", level=0.4, **kw):
    """A meow resynthesised as partials: `speed` stretches it, `gather`+`chord` harmonise it,
    `purity` from clean to alien."""
    f, own, t0, t1, flen, _ = MEOWS[which]
    song.clip("meow_partials", "partials", track, beat, beats, level=_lv(level, GAIN[which]),
              attack=kw.pop("attack", 0.05), release=kw.pop("release", 0.3), pitch=pitch - own,
              file=CAT + f, start=max(0.0, t0) / flen, speed=kw.pop("speed", 1.0), **kw)


def meow_hold(song, track, beat, beats, pitch, which="kitty", at=0.5, level=0.4, **kw):
    """A meow frozen at one moment (`at` 0..1 through its loud part), held as a tone."""
    f, own, t0, t1, flen, _ = MEOWS[which]
    song.clip("meow_hold", "sample", track, beat, beats, level=_lv(level, GAIN[which]), attack=kw.pop("attack", 0.08),
              release=kw.pop("release", 0.3), pitch=pitch - own, file=CAT + f,
              start=(t0 + at * (t1 - t0)) / flen, pitch_mode=1, speed=0.0, **kw)
