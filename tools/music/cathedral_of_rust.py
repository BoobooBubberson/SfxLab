#!/usr/bin/env python3
"""'Cathedral of Rust' - D minor, 66 BPM, half-time doom with a choir. Dread or boss-approach music.

Built around one of the README's untried ideas: the D-major cello pad resynthesised as
partials and pulled onto each chord with `gather` (minor chords get chord=5), so a major
recording plays a minor progression. Over it a Tesla coil chants: ring high, so each
pulse is a damped sine, with a new formant (vowel) every syllable. The drums are a doom
stomp, an anvil (pitched resonant noise plus an inharmonic FM ping) and a slow coil
ticker like clockwork. The luminal voice choir comes in as chaos, gathers onto the chords
for the climax, and comes apart again at the end.

Progression, one chord per bar: Dm  Bb  Gm  A  (A major: the harmonic-minor dominant).
Tracks (GUI numbering): 1 kick (duck key) 2 drums 3 guitar/bass 4 coil chant 5 cello/choir 6 drone/bells

    python3 cathedral_of_rust.py > projects/cathedral_of_rust.sfx
"""
from sfxsong import Song, ticker_pitch, cutoff_for_hz, hz, CHORD

BPM = 66
s = Song("Cathedral of Rust", BPM, seed=66_2026,
         comment="D minor, 66 BPM, by Claude. tracks: 1 kick 2 drums 3 guitar/bass 4 coil chant 5 cello/choir 6 drone/bells",
         tracks=[(0.25, 0), (0.7, 0), (0.45, 0), (1.0, 0), (0.7, 0), (0.65, 0)])
R = s.R
BAR = 4
DUCK = dict(duck=0.45, duck_from=1)

# (name, guitar shift from D, quality, root in semitones from A2 for the sub)
PROG = [("Dm", 0, "minor", -7), ("Bb", -4, "major", -11), ("Gm", -7, "minor", -14), ("A", -5, "major", -12)]

CELLO = "resonant/cello_00002_pad.ogg"           # sustained D major
GTR = "resonant/guitar_heavy_00002.ogg"          # one D power chord, rings ~2 s
PAD = "resonant/guitar_heavy_00001_pad.ogg"      # sustained D power chord
BELL = "resonant/bell_00003.ogg"                 # G bell with a D overtone: -5.37 to D
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"   # C4: -2.94 makes A3, +2.06 makes D4

# ---- instruments -----------------------------------------------------------------
def cello(bar, beats, shift, quality, level=0.5, attack=1.2):
    s.clip("cello", "partials", 4, bar * BAR, beats, level=level, attack=attack, release=1.5,
           pitch=shift, file=CELLO, gather=0.85, chord=CHORD[quality], purity=0.3, shimmer=0.15,
           reverb=0.45, cutoff=0.7, **DUCK)

def guitar(beat, shift, beats=3.0, level=0.6, cut=0.6):
    s.clip("gtr", "sample", 2, beat, beats, level=level, attack=0.0, release=0.8, pitch=shift,
           file=GTR, cutoff=cut, resonance=0.1, drive=0.2, reverb=0.25, **DUCK)

def sub(bar, root, beats=BAR):
    s.clip("sub", "tones", 2, bar * BAR, beats, level=0.4, attack=0.02, release=1.0, pitch=root,
           interval=12, timbre=0.15, cutoff=0.35, **DUCK)

def stomp(beat, vel=1.0):
    s.clip("kick", "tones", 0, beat, 1.4, level=vel, attack=0.001, release=1.2, pitch=-2,
           pitch_swp=-17, interval=0, timbre=0.0, drive=0.5, env_curve=0.25, reverb=0.15, keyed=0)

def anvil(beat, vel=1.0):
    for f, lv, pan in ((hz(29), 0.5, -0.15), (hz(36), 0.35, 0.2)):     # D5 and A5 rings
        s.clip("anvil", "noise", 1, beat, 2.0, level=lv * vel, attack=0.001, release=1.8,
               filter=1, resonance=0.92, cutoff=cutoff_for_hz(f), color=0.2, env_curve=0.8,
               reverb=0.5, pan=pan, keyed=0)
    s.clip("anvil_ping", "tones", 1, beat, 1.5, level=0.22 * vel, attack=0.001, release="full",
           pitch=29, interval=0, timbre=0.0, fm_ratio=3.53, fm_depth=1.8, env_curve=0.85,
           reverb=0.5, keyed=0)
    s.clip("anvil_crack", "noise", 1, beat, 0.15, level=0.4 * vel, attack=0.0, release=0.1,
           filter=2, cutoff=0.7, color=0.0, keyed=0)

def clockwork(bar0, bars, misfire=0.55, level=0.2):
    s.clip("clockwork", "arc", 1, bar0 * BAR, bars * BAR, level=level, attack=0.0, release=0.1,
           pitch=ticker_pitch(BPM, 4), divide=4, ring=0.0, burst=2.0, snap=0.9, misfire=misfire,
           filter=2, cutoff=0.6, pan=0.3, reverb=0.35, keyed=0)

VOWELS = [7, 12, 16, 19]            # u  o  e  a, roughly

def chant(beat, beats, pitch, level=0.62, pan=0.0, vowel=None, glide=0.0):
    ring = 0.6
    s.clip("chant", "arc", 3, beat, beats + ring, level=level, attack=0.08, release=ring + 0.2,
           pitch=pitch, pitch_swp=glide, ring=0.9, formant=vowel if vowel is not None else R.choice(VOWELS),
           burst=11, jitter=0.04, flicker=0.15, snap=0.2, reverb=0.6, echo=0.25, pan=pan,
           lfo_rate=4.8, **{"lfo>pitch": 0.12})

def choir(bar, beats, pitch, quality, gather=1.0, gather_swp=0.0, wander=2, level=0.5, attack=0.6,
          release=1.5):
    s.clip("choir", "choir", 4, bar * BAR, beats, level=level, attack=attack, release=release,
           pitch=pitch, chord=CHORD[quality], file=TREBLE, start=0.2, speed=0.3, voices=8,
           gather=gather, gather_swp=gather_swp, drift=0.5, wander=wander, scatter=0.4,
           reverb=0.65, cutoff=0.8)

def bell(beat, level=0.5):
    s.clip("bell", "sample", 5, beat, 7, level=level, release=3, pitch=-5.37, file=BELL, reverb=0.55)
    s.clip("bell_low", "tones", 5, beat, 7, level=0.25, attack=0.002, release="full", pitch=-7,
           interval=12, timbre=0.0, bank=1.0, stretch=0.06, tilt=-0.4, env_curve=0.6, reverb=0.6)

# choir pitch for each chord (shift from the C4 recording) and quality
CHOIR = {"Dm": (2.06, "minor"), "Bb": (-1.94, "major"), "Gm": (-4.94, "minor"), "A": (-2.94, "major")}

# chant: (beat, length, pitch); D3=5 E3=7 F3=8 G3=10 A3=12 Bb3=13 C#4=16 D4=17 E4=19 F4=20 G4=22
# A4=24 Bb4=25 C5=27 C#5=28 D5=29
CHANT_A = [(0, 2, 12), (2, 1, 10), (3, 1, 8), (4, 1, 7), (5, 1, 8), (6, 2, 5)]
CHANT_B = [(0, 2, 12), (2, 1, 13), (3, 1, 12),
           (4, 2, 8), (6, 1, 10), (7, 1, 8),
           (8, 1, 10), (9, 1, 13), (10, 1, 12), (11, 1, 10),
           (12, 1, 7), (13, 1, 12), (14, 2, 16)]
CHANT_C = [(0, 2, 24), (2, 1, 22), (3, 1, 20),
           (4, 1, 20), (5, 1, 22), (6, 1, 24), (7, 1, 25),
           (8, 2, 29), (10, 1, 27), (11, 1, 25),
           (12, 1, 24), (13, 1, 22), (14, 2, 28)]

# =============================================================================
# arrangement (bars)
# =============================================================================
A, B, C, D, END = 0, 2, 6, 10, 12

# drone under everything until the collapse
s.clip("drone", "sample", 5, 0, D * BAR + 2, level=0.35, attack=2.0, release=3.0, pitch=0,
       file=PAD, loop=1, pitch_mode=1, speed=0.3, cutoff=0.35, cutoff_swp=0.2, reverb=0.5,
       **DUCK)

# A: emergence. Cello on D, chanting alone, clockwork faint
cello(A, 2 * BAR + 1, 0, "minor", level=0.45, attack=2.0)
for b, ln, p in CHANT_A:
    chant(A * BAR + b, ln, p, level=0.55)
clockwork(A, 2, misfire=0.8, level=0.14)
bell(A * BAR)

# B: the march. One chord per bar, twice
for k in range(4):
    bar = B + k
    name, gshift, q, root = PROG[k]
    cello(bar, BAR + 1, gshift, q)
    guitar(bar * BAR, gshift)
    guitar(bar * BAR + 2.5, gshift, beats=1.2, level=0.45, cut=0.5)
    sub(bar, root)
    stomp(bar * BAR); stomp(bar * BAR + 2.5, 0.75)
    anvil(bar * BAR + 2, 0.8 + 0.05 * k)
for b, ln, p in CHANT_B:
    chant(B * BAR + b, ln, p)
clockwork(B, 4, misfire=0.5)

# C: the climax. Choir gathers out of chaos over the first chord, then sits on the chords
for k in range(4):
    bar = C + k
    name, gshift, q, root = PROG[k]
    cello(bar, BAR + 1, gshift, q, level=0.4)
    guitar(bar * BAR, gshift, level=0.65, cut=0.7)
    guitar(bar * BAR + 1.5, gshift, beats=1.0, level=0.45, cut=0.55)
    guitar(bar * BAR + 2.5, gshift, beats=1.2, level=0.5, cut=0.6)
    sub(bar, root)
    stomp(bar * BAR); stomp(bar * BAR + 1.5, 0.6); stomp(bar * BAR + 2.5, 0.8)
    anvil(bar * BAR + 2)
    if k == 3:                               # a second anvil drives into the last chord's turn
        anvil(bar * BAR + 3.5, 0.7)
    cp, cq = CHOIR[name]
    if k == 0:
        choir(bar, BAR + 1, cp, cq, gather=0.0, gather_swp=1.0, wander=12, attack=1.5)
    else:
        choir(bar, BAR + 1, cp, cq)
for b, ln, p in CHANT_C:
    v = R.choice(VOWELS)
    chant(C * BAR + b, ln, p, vowel=v)
    chant(C * BAR + b, ln, p - 12, level=0.38, pan=-0.3, vowel=v)   # an octave under
clockwork(C, 4, misfire=0.3, level=0.24)
bell(C * BAR); bell((C + 2) * BAR, 0.45)

# D: collapse. The last D lands; choir and coil come apart; one bell
t = D * BAR
stomp(t); anvil(t, 1.0); bell(t, 0.6)
guitar(t, 0, beats=6, level=0.6, cut=0.7)
s.clip("sub_end", "tones", 2, t, 6, level=0.4, attack=0.02, release=3, pitch=-7, pitch_swp=-5,
       interval=12, timbre=0.15, cutoff=0.35)
cello(D, 2 * BAR, 0, "minor", level=0.4, attack=0.05)
choir(D, 2 * BAR, 2.06, "minor", gather=1.0, gather_swp=-1.0, wander=12, attack=0.05, release=3,
      level=0.5)
s.clip("dying_chant", "arc", 3, t, 2 * BAR, level=0.4, attack=0.01, release=3, pitch=29,
       pitch_swp=-24, ring=0.9, formant=19, burst=11, jitter=0.4, misfire=0.5, flicker=0.8,
       reverb=0.6, echo=0.3)
clockwork(D, 1, misfire=0.6, level=0.2)
s.clip("clock_stop", "arc", 1, (D + 1) * BAR, BAR, level=0.2, attack=0.0, release=0.1,
       pitch=ticker_pitch(BPM, 4), pitch_swp=-24, divide=4, ring=0.0, burst=2.0, snap=0.9,
       misfire=0.4, filter=2, cutoff=0.6, pan=0.3, reverb=0.35, keyed=0)

if __name__ == "__main__":
    s.write()
