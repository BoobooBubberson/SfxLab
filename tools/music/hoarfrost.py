#!/usr/bin/env python3
"""'Hoarfrost' - D major pentatonic, 72 BPM, no drums, loops. Exploration / snowfield ambience.

The harmony breathes in and out of focus. Every four bars two clouds overlap: one gathering
from half-wandering onto a pentatonic chord (gather 0.5 -> 0.97), the other letting go of
it (0.97 -> 0.5). The `penta` table has no semitones in it, so a half-gathered cloud is a
blur rather than a clash. The bases move D, G, D, A (the pentatonics of D, G and A share
four notes), so the shift between blocks is felt more than heard.

Over it, singing bowl 2 (a clean D4 with F#, B and E partials) plays sparse notes from the
current pentatonic, tape-pitched; an arc fires at random (jitter 1, a raw spark, mostly
misfiring) as frost crackling; sparkle for ice glints; blizzard wind low-passed, gusting
on a slow LFO; and a sine sub that breathes.

Tracks (GUI numbering): 1 (empty) 2 frost crackle/glints 3 sub 4 bowls 5 clouds 6 wind

    python3 tools/music/hoarfrost.py > projects/hoarfrost.sfx
"""
import math
from sfxsong import Song, CHORD

BPM = 72
s = Song("Hoarfrost", BPM, seed=72_2026, loop=1,
         comment="D major pentatonic, 72 BPM, loops, by Claude. tracks: 1 - 2 crackle/glints 3 sub 4 bowls 5 clouds 6 wind",
         tracks=[(1.0, 0), (1.0, 0), (0.8, 0), (1.0, 0), (1.0, 0), (1.0, 0)])
R = s.R
BAR = 4
BLOCKS = [5, 10, 5, 0]          # cloud base per 4-bar block: D3, G3, D3, A2 (semitones from A2)
LOOP = len(BLOCKS) * 4 * BAR
s.max_beat = LOOP
BOWL = "resonant/singing-bowl_00002_pad.ogg"   # D4 +-2c: pitch 0 = D4 (17 semitones over A2)
PENTA = [0, 2, 4, 7, 9]                        # major pentatonic steps


def cloud(beat, beats, base, g0, g1, level=0.48, attack=2.0, release=2.0):
    s.clip("cloud", "cloud", 4, beat, beats, level=level, attack=attack, release=release, pitch=base,
           voices=10, gather=g0, gather_swp=g1 - g0, drift=0.15, timbre=0.45, chord=CHORD["penta"],
           sub=0.0, cutoff=0.6, reverb=0.65, echo=0.15, pan_swp=0.0)


def bowl(beat, note, level=0.4, pan=0.0):
    """note in semitones from A2; the bowl is tape-pitched from its D4."""
    s.clip("bowl", "sample", 3, beat, 5, level=level, attack=0.005, release="full", pitch=note - 17,
           env_curve=0.45,
           file=BOWL, reverb=0.55, echo=0.2, pan=pan)


def scale(base):
    """The pentatonic over a base, as pitches from A2, in the bowl's comfortable range."""
    root = base % 12 + 12                                  # an octave over A2 .. G#3
    notes = [root + st + 12 * o for o in (0, 1) for st in PENTA]
    return [n for n in notes if 14 <= n <= 29]           # B3 .. D5: within +-12 of the bowl


# ---- the clouds: one focusing, one blurring, overlapping by two beats each side --------
blk = 4 * BAR
for i, base in enumerate(BLOCKS):
    t = i * blk
    prev = BLOCKS[i - 1]
    cloud(t, blk + 2, base, 0.5, 0.97)                    # this block's chord comes into focus
    if i > 0:
        cloud(t - 2, blk / 2 + 2, prev, 0.97, 0.5, level=0.3, attack=0.5)   # the last one lets go
cloud(0, blk / 2, BLOCKS[-1], 0.97, 0.5, level=0.3, attack=0.01)          # across the loop seam
# a high, thin cloud an octave up in the second half of each block: shimmer
for i, base in enumerate(BLOCKS):
    s.clip("halo", "cloud", 4, i * blk + 8, 8, level=0.14, attack=2.0, release=2.0, pitch=base + 12,
           voices=8, gather=0.6, gather_swp=0.35, drift=0.5, timbre=0.1, chord=CHORD["penta"],
           cutoff=0.8, reverb=0.7, echo=0.3)

# ---- sub: the block's root, breathing -------------------------------------------------
for i, base in enumerate(BLOCKS):
    root = base - 24 if base - 24 >= -14 else base - 12      # around D1..G1, kept above ~45 Hz
    root = base - 12 if root < -12 else root
    s.clip("sub", "tones", 2, i * blk, blk + (0 if i == len(BLOCKS) - 1 else 1), level=0.4,
           attack=1.5, release=1.5, pitch=root, interval=12, timbre=0.0, cutoff=0.3,
           lfo_rate=0.15, **{"lfo>amp": 0.35})

# ---- bowls: sparse, stepwise mostly, phrases of 3-5 then a rest ----------------------
beat = 1.0
idx = 2
while beat < LOOP - 4:
    base = BLOCKS[int(beat // blk)]
    notes = scale(base)
    idx = max(0, min(len(notes) - 1, idx + R.choice([-2, -1, -1, 1, 1, 2])))
    for _ in range(R.randint(3, 5)):
        if beat >= LOOP - 4:
            break
        bowl(beat, notes[idx], level=R.uniform(0.6, 0.9), pan=R.uniform(-0.5, 0.5))
        beat += R.choice([1.5, 2, 2, 3])
        idx = max(0, min(len(notes) - 1, idx + R.choice([-1, -1, 1, 1, 2, -2])))
    beat += R.choice([3, 4, 5])

# ---- frost: a random-firing arc crackling, and glints ----------------------------------
for i in range(len(BLOCKS)):
    s.clip("crackle", "arc", 1, i * blk, blk, level=0.25, attack=1.0, release=1.0, pitch=24,
           divide=3, jitter=1.0, burst=0.3, snap=0.9, misfire=0.85, color=0.0, filter=2, cutoff=0.75,
           pan=-0.4, pan_swp=0.8, reverb=0.4, keyed=0)
for i, base in enumerate(BLOCKS):
    s.clip("glint", "sparkle", 1, i * blk + 6, 6, level=0.16, attack=1.0, release=2.0,
           pitch=base + 24, density=5, ping_decay=0.25, spread=1.0, reverb=0.6, echo=0.3)

# ---- wind ------------------------------------------------------------------------------
s.clip("wind", "sample", 5, 0, LOOP, level=0.3, attack=2.0, release=2.0, file="cryic/blizzard_1.ogg",
       loop=1, cutoff=0.45, lfo_rate=0.1, **{"lfo>cut": 0.12}, keyed=0)

if __name__ == "__main__":
    s.write()
