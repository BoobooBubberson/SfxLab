#!/usr/bin/env python3
"""The cat recordings as instruments: eight ways to play them, each on the opening of the coil's
phrase (an octave up, where meows live) over a quiet piano ground. 112 BPM, eight bars each.

    python3 tools/music/meow_audition.py > projects/meow_audition.sfx
    python3 tools/music/meow_audition.py --list
"""
import sys
from sfxsong import Song, CHORD
from meows import meow, mew, meow_choir, meow_partials, meow_hold

BPM = 112
s = Song("Meow audition", BPM, seed=9_11_2026, comment="the cat recordings as instruments, by Claude",
         tracks=[(0.6, 0), (1.0, 0), (1.0, 0), (1.0, 0), (1.0, 0), (1.0, 0)])
BAR = 4
# the ground, a chord per bar (bass, root, quality)
GROUND = [(-7, 5, "minor"), (-8, 0, "major"), (-9, 5, "minor"), (-10, 10, "major"),
          (-11, 10, "minor"), (-12, 0, "sus4"), (-12, 0, "major"), (-12, 0, "harm7")]
# the coil's first phrase (part I's beats x2), an octave up: F5=32 E5=31 D5=29 C#5=28 A5=36 G5=34 Bb5=37
PHRASE = [(1, 2, 32), (3, 1, 31), (4, 2, 31), (6, 1, 29), (7, 1, 28), (8, 3, 29), (11, 1, 36),
          (12, 2, 36), (14, 2, 34), (16, 3, 37), (19, 1, 36), (20, 2, 34), (22, 2, 32), (24, 8, 31)]
SIGHS = [(16, 3, 37, 36), (11, 1, 36, 34)]

def backing(t0, bars=8):
    for b in range(bars):
        bass, root, q = GROUND[b % 8]
        s.clip("piano", "tones", 0, t0 + b * BAR, 6, level=0.35, attack=0.004, release="full", pitch=bass,
               interval=12, timbre=0.0, fm_ratio=1.0, fm_depth=1.0, env_curve=0.45, cutoff=0.5,
               env_cut=0.3, mod_decay=0.45, reverb=0.4)
        s.clip("pad", "cloud", 0, t0 + b * BAR, BAR + 0.5, level=0.12, attack=0.3, release=0.4, pitch=root,
               voices=6, gather=1.0, drift=0.1, timbre=1.9, chord=CHORD[q], cutoff=0.5, reverb=0.5)

def lead(t0, fn):
    for b, ln, p in PHRASE:
        fn(t0 + b, ln, p)

TESTS = [
    ("sampler lead: kitty", lambda t0: lead(t0, lambda b, ln, p: meow(s, 3, b, ln, p, "kitty", level=0.5))),
    ("sampler lead: garage1", lambda t0: lead(t0, lambda b, ln, p: meow(s, 3, b, ln, p, "garage1", level=0.5))),
    ("held meows: u6, keep length", lambda t0: lead(t0, lambda b, ln, p: meow(s, 3, b, ln, p, "u6", level=0.5,
                                                                             keep=True, speed=0.6))),
    ("kitten mews, staccato", lambda t0: [mew(s, 3, t0 + b + 0.5 * i, p, k=i, level=0.55)
                                          for b, ln, p in PHRASE for i in range(max(1, int(ln * 2)))
                                          if i < 4]),
    ("meow choir on the chords", lambda t0: [meow_choir(s, 4, t0 + b * BAR, BAR + 0.3, GROUND[b][1] + 24,
                                                        CHORD["minor" if GROUND[b][2] == "minor" else "major"],
                                                        which="u6", level=0.45) for b in range(8)]),
    ("meow partials, gathered on the chords", lambda t0: [meow_partials(s, 4, t0 + b * BAR, BAR + 0.3,
                                                                        GROUND[b][1] + 24, which="kitty", level=0.45,
                                                                        speed=0.15, gather=0.9,
                                                                        chord=CHORD["minor" if GROUND[b][2] == "minor" else "major"])
                                                          for b in range(8)]),
    ("frozen meow, held", lambda t0: lead(t0, lambda b, ln, p: meow_hold(s, 3, b, ln + 0.3, p, "kitty", at=0.4,
                                                                        level=0.45))),
    ("the sigh, landing on the note", lambda t0: [meow(s, 3, t0 + b - 0.5, ln + 0.5, lo, "sigh", level=0.5)
                                                  for b, ln, hi, lo in [(1, 2, 32, 31), (6, 2, 29, 28), (16, 3, 37, 36),
                                                                        (20, 2, 34, 32), (24, 4, 31, 29)]]),
]
SLOT = 8 * BAR + 4

for i, (name, fn) in enumerate(TESTS):
    t0 = i * SLOT
    backing(t0)
    fn(t0)

if __name__ == "__main__":
    if "--list" in sys.argv:
        for i, (name, _) in enumerate(TESTS):
            print(f"{i * SLOT * 60 / BPM:6.1f} s  {name}")
    else:
        s.write()
