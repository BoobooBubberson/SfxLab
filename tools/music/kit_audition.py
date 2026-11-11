#!/usr/bin/env python3
"""The noise kit: one groove, the kick in several variants of the 0.7 / -1 recipe, then the toms and cymbals.

    python3 tools/music/kit_audition.py > projects/kit_audition.sfx
    python3 tools/music/kit_audition.py --list
"""
import sys
from sfxsong import Song
from noise_kit import Kit

BPM = 140
s = Song("Kit audition", BPM, seed=10_11_2026, comment="the noise kit, by Claude", tracks=[(0.8, 0), (0.8, 0)] + [(1.0, 0)] * 4)
spb = 60 / BPM

# (label, Kit keyword arguments)
KICKS = [
    ("the recipe as given: 0.3 s, color 0.5", dict()),
    ("shorter and tighter: 0.18 s", dict(kick_beats=0.18 / spb)),
    ("longer, a boom: 0.5 s", dict(kick_beats=0.5 / spb)),
    ("white noise (color 0): more click", dict(kick_color=0.0)),
    ("brown noise (color 0.85): more thud", dict(kick_color=0.85)),
    ("resonance 0.6: the sweep sings, a pitched drop", dict(kick_res=0.6)),
    ("resonance 0.6, brown, drive 0.4: a metal kick", dict(kick_res=0.6, kick_color=0.85, kick_drive=0.4)),
]
GROOVE = [(0, "k"), (0.5, "h"), (1, "s"), (1.5, "h"), (1.75, "k"), (2, "k"), (2.5, "h"), (3, "s"), (3.5, "h")]
SLOT = 4 * 4 + 2


def groove(kit, t0, bars=4):
    for b in range(bars):
        for at, x in GROOVE:
            t = t0 + 4 * b + at
            {"k": kit.kick, "s": kit.snare, "h": kit.hat}[x](t)
        kit.hat(t0 + 4 * b + 0.25, 0.5); kit.hat(t0 + 4 * b + 2.25, 0.5)
    kit.open_hat(t0 + 4 * bars - 0.5)


for i, (label, kw) in enumerate(KICKS):
    groove(Kit(s, **kw), i * SLOT)

t0 = len(KICKS) * SLOT
kit = Kit(s, kick_res=0.6, kick_color=0.85, kick_drive=0.4)
for i in range(6):                                  # tom run, then each tom alone
    kit.tom(t0 + 0.25 * i, i)
for i in range(6):
    kit.tom(t0 + 2 + i, i)
kit.crash(t0 + 8); kit.kick(t0 + 8)
for i in range(8):
    kit.ride(t0 + 12 + i * 0.5, 1.0 if i % 2 == 0 else 0.7)

if __name__ == "__main__":
    if "--list" in sys.argv:
        for i, (label, _) in enumerate(KICKS):
            print(f"{i * SLOT * spb:5.1f} s  kick: {label}")
        print(f"{t0 * spb:5.1f} s  toms (a run, then high to floor), a crash, the ride")
    else:
        s.write()
