#!/usr/bin/env python3
"""'Lantern Market' - G major in just intonation, 96 BPM, swung. A safe room / hub loop.

Every pitch is a whole-number ratio against G instead of an equal-tempered semitone,
so the chords are beatless: thirds at 5/4 (14 cents flatter than a piano), fifths at 3/2.
The dominant seventh takes the 7th harmonic (D * 7/4, a C 27 cents low), the sweet
barbershop seventh. Instruments: a glassy FM kalimba melody, swung Karplus-Strong arpeggios,
a round bass, and percussion of wooden "toks" (an FM ping plus a noise knock) tuned to the chord, so the drums
ring in the key. The project loops (loop 1).

Progression (bars): I I vi vi IV IV V7 V7 | I I vi vi IV V7 I I
Tracks (GUI numbering): 1 kick (duck key) 2 drips/shaker 3 bass 4 kalimba 5 arps/pad 6 sparkle

    python3 lantern_market.py > projects/lantern_market.sfx
"""
import math
from sfxsong import Song, cutoff_for_hz, hz

BPM = 96
s = Song("Lantern Market", BPM, seed=96_2026, loop=1,
         comment="G major, just intonation, 96 BPM swung, loops, by Claude. tracks: 1 kick 2 drips 3 bass 4 kalimba 5 arps/pad 6 sparkle",
         tracks=[(0.9, 0), (1.0, 0), (1.0, 0), (1.0, 0), (0.9, 0), (0.8, 0)])
R = s.R
BAR = 4
SWING = 0.08            # offbeat 8ths land at .58 of the beat
G4 = 22                 # semitones from A2

def ji(ratio, octave=0):
    """Pitch of G4 * ratio * 2^octave, in semitones from A2."""
    return G4 + 12 * octave + 12 * math.log2(ratio)

def sw(beat):
    """Swing: move offbeat eighths late."""
    whole = math.floor(beat + 1e-9)
    frac = beat - whole
    return whole + SWING + 0.5 if abs(frac - 0.5) < 1e-6 else beat

N = {"G": 1, "A": 9 / 8, "B": 5 / 4, "C": 4 / 3, "D": 3 / 2, "E": 5 / 3, "F#": 15 / 8,
     "C7": 21 / 16}     # C7: the septimal seventh over D

CH = {"I": [1, 5 / 4, 3 / 2], "vi": [5 / 6, 1, 5 / 4], "IV": [2 / 3, 5 / 6, 1],
      "V7": [3 / 4, 15 / 16, 9 / 8, 21 / 16]}       # ratios to G, near G4..
ROOT = {"I": 1, "vi": 5 / 6, "IV": 2 / 3, "V7": 3 / 4}
FORM = ["I", "I", "vi", "vi", "IV", "IV", "V7", "V7", "I", "I", "vi", "vi", "IV", "V7", "I", "I"]
s.max_beat = len(FORM) * BAR    # the loop is exactly 16 bars; tails fade before the seam

# ---- instruments -------------------------------------------------------------------
def kalimba(beat, beats, pitch, level=0.9):
    s.clip("kalimba", "tones", 3, sw(beat), beats + 1.0, level=level, attack=0.002, release="full",
           pitch=pitch, interval=12, timbre=0.15, fm_ratio=4.0, fm_depth=0.55, env_curve=0.45,
           echo=0.22, reverb=0.4, pan=0.1)

def pluck(beat, pitch, level=0.32, pan=0.0):
    level *= 2.0
    s.clip("arp", "pluck", 4, sw(beat), 1.6, level=level, attack=0.002, release="full", pitch=pitch,
           damp=0.45, sustain=0.85, pick=0.6, pan=pan, reverb=0.3, **{"duck": 0.25, "duck_from": 1})

def bass(beat, beats, pitch):
    s.clip("bass", "tones", 2, sw(beat), beats, level=0.8, attack=0.008, release=min(0.4, beats * 0.25),
           pitch=pitch, interval=12, timbre=0.25, cutoff=0.4, env_curve=0.3)

def kick(beat, vel=1.0):
    s.clip("kick", "tones", 0, sw(beat), 0.6, level=0.8 * vel, attack=0.001, release="full", pitch=-4,
           pitch_swp=-14, interval=0, timbre=0.0, env_curve=0.4, keyed=0)

def drip(beat, pitch, level=0.3, pan=0.0):
    """A wooden 'tok' tuned to the chord: an FM ping on the exact just pitch for the note, and a
    short burst of resonant noise for the knock (filtered noise alone wanders a semitone or so)."""
    s.clip("drip", "tones", 1, sw(beat), 0.6, level=min(1.0, level * 2.4), attack=0.001, release="full",
           pitch=pitch, interval=12, timbre=0.0, fm_ratio=3.0, fm_depth=0.9, env_curve=0.85,
           reverb=0.3, pan=pan, keyed=0)
    s.clip("knock", "noise", 1, sw(beat), 0.12, level=min(1.0, level * 1.5), attack=0.001, release="full",
           filter=1, resonance=0.85, cutoff=cutoff_for_hz(hz(pitch)), color=0.3, env_curve=0.5, pan=pan,
           keyed=0)

def shaker(beat, level):
    level *= 2.0
    s.clip("shaker", "noise", 1, sw(beat), 0.25, level=level, attack=0.004, release="full", filter=2,
           cutoff=0.82, color=0.0, env_curve=0.5, pan=-0.3, keyed=0)

def pad(bar, bars, chord):
    r = CH[chord]
    lo = [ji(x, -1) for x in r[:3]]
    for p, iv, pan in ((lo[0], 12 * math.log2(r[2] / r[0]), -0.3), (lo[1], 12, 0.3)):
        s.clip("pad", "tones", 4, bar * BAR, bars * BAR + 0.5, level=0.12, attack=0.8, release=1.2,
               pitch=p, interval=iv, timbre=0.6, detune=0.15, cutoff=0.42, pan=pan, reverb=0.5)

# ---- melody: (beat, length, note, octave from G4) -----------------------------------
MEL = [(0, 1, "B", 0), (1, .5, "D", 0), (1.5, .5, "E", 0), (2, 1.5, "D", 0), (3.5, .5, "B", 0),
       (4, 1, "A", 0), (5, 1, "G", 0), (6, 2, "D", -1),
       (8, 1, "E", 0), (9, .5, "G", 1), (9.5, .5, "E", 0), (10, 1.5, "B", 0), (11.5, .5, "A", 0),
       (12, 2, "B", 0), (14, 1, "G", 0), (15, 1, "A", 0),
       (16, 1.5, "C", 0), (17.5, .5, "B", 0), (18, 1, "A", 0), (19, 1, "G", 0), (20, 1, "E", -1),
       (21, 1, "G", 0), (22, 2, "A", 0),
       (24, 1, "D", 0), (25, .5, "F#", 0), (25.5, .5, "A", 1), (26, 1.5, "C7", 1), (27.5, .5, "A", 1),
       (28, 1, "F#", 0), (29, 1, "D", 0), (30, 2, "A", 0),
       (32, 1, "B", 0), (33, .5, "D", 0), (33.5, .5, "G", 1), (34, 1.5, "F#", 0), (35.5, .5, "D", 0),
       (36, 2, "B", 0), (38, 1, "A", 0), (39, 1, "B", 0),
       (40, 1.5, "E", 0), (41.5, .5, "D", 0), (42, 1, "B", 0), (43, 1, "G", 0),
       (44, 1, "A", 0), (45, 1, "B", 0), (46, 2, "G", 0),
       (48, 1, "C", 0), (49, 1, "E", 0), (50, 1, "G", 1), (51, 1, "E", 0),
       (52, 1, "A", 1), (53, .5, "C7", 1), (53.5, .5, "A", 1), (54, 1, "F#", 0), (55, 1, "D", 0),
       (56, 1, "B", 0), (57, 1, "A", 0), (58, 4, "G", 0)]
# ("A", 1) is A5 and ("C7", 1) the septimal C6: the V7 bars climb D5 F#5 A5 C6 and fall back

# arpeggio shape over 8 eighths: chord tone index, octave
ARP = [(0, -1), (2, -1), (1, 0), (2, -1), (0, 0), (2, -1), (1, 0), (2, -1)]
ARP7 = [(0, -1), (2, -1), (1, 0), (3, -1), (0, 0), (2, -1), (3, 0), (1, 0)]

# =============================================================================
for bar, chord in enumerate(FORM):
    b0 = bar * BAR
    tones = CH[chord]
    shape = ARP7 if chord == "V7" else ARP
    for i, (ti, oc) in enumerate(shape):
        pluck(b0 + i * 0.5, ji(tones[ti], oc), level=0.3 if i % 2 == 0 else 0.22,
              pan=-0.25 if i % 2 else 0.25)
    root = ji(ROOT[chord], -2)               # G2 region
    second_half = bar + 1 < len(FORM) and FORM[bar + 1] == chord
    bass(b0, 1.5, root)
    bass(b0 + 1.5, 0.5, root + 12)
    bass(b0 + 2, 2, root + (12 * math.log2(3 / 2) if second_half else 0))
    kick(b0); kick(b0 + 2.5, 0.7)
    if bar % 4 == 3:
        kick(b0 + 3.5, 0.5)
    # drips on 2 and 4: the chord's fifth and root, two octaves up
    drip(b0 + 1, ji(tones[2], 1), level=0.3, pan=0.2)
    drip(b0 + 3, ji(tones[0], 1), level=0.28, pan=-0.2)
    if R.random() < 0.5:                      # a ghost drip, swung
        drip(b0 + 3.5, ji(tones[1], 1), level=0.14, pan=0.35)
    for k in range(4):
        shaker(b0 + k + 0.5, 0.07 + 0.03 * R.random())
    if bar == 0 or FORM[bar - 1] != chord:
        span = 2 if second_half else 1
        pad(bar, span, chord)

for b, l, n, o in MEL:
    kalimba(b, l, ji(N[n], o))

# sparkle at the two section turns
for bar in (7, 15):
    s.clip("sparkle", "sparkle", 5, bar * BAR, 4, level=0.22, attack=0.4, release=2.0,
           pitch=ji(1, 1), density=8, ping_decay=0.2, spread=0.9, reverb=0.6)

if __name__ == "__main__":
    s.write()
