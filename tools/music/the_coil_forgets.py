#!/usr/bin/env python3
"""'The Coil Forgets' - D minor, 56 BPM. The saddest thing the lab could make.

Built on the lament bass: a four-bar ground that sinks a semitone at a time,
D C# C B Bb A (Dm  A/C#  Dm/C  G/B  Gm/Bb  Asus4  A  A7), as under Dido's Lament. It never
stops. The one brightening, G major over B, comes and is gone in two beats.

Over it the coil sings: an arc with ring near 1 and its formant on a harmonic, so it is a
pure, breathy voice. The melody is built from sighs, a dissonant note on the beat
falling a step onto the chord (Bb to A over D minor, A to G over G). Felt piano alone
first, then the voice, then strings (a cloud) and the D-major cello turned minor
through partials, then the choir for one phrase. Then everything leaves. The piano plays
the ground alone, and the coil begins its first phrase again but misfires more with
every note, until it stops before the last one. A soft rain throughout.

Tracks (GUI numbering): 1 piano bass 2 piano 3 cello 4 coil voice 5 strings/choir 6 rain

    python3 tools/music/the_coil_forgets.py > projects/the_coil_forgets.sfx
"""
from sfxsong import Song, CHORD

BPM = 56
s = Song("The Coil Forgets", BPM, seed=56_2026,
         comment="D minor, 56 BPM, by Claude. tracks: 1 piano bass 2 piano 3 cello 4 coil voice 5 strings/choir 6 rain",
         tracks=[(0.9, 0), (0.9, 0), (0.7, 0), (0.85, 0), (0.85, 0), (0.6, 0)])
R = s.R
BAR = 4
CELLO = "resonant/cello_00002_pad.ogg"          # D major: partials shift from D, gather to the chord
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"   # C4: -2.94 makes A3

# the ground, per half bar: (bass, upper voicing, chord root for strings/cello, quality)
# pitches in semitones from A2: D2=-7 C#2=-8 C2=-9 B1=-10 Bb1=-11 A1=-12; D3=5 E3=7 F3=8 G3=10
# A3=12 Bb3=13 B3=14 C#4=16 D4=17 E4=19
GROUND = [(-7, (5, 8, 12), 5, "minor"),       # Dm
          (-8, (7, 12, 16), 0, "major"),      # A/C#
          (-9, (8, 12, 17), 5, "minor"),      # Dm/C
          (-10, (5, 10, 14), 10, "major"),    # G/B  (the one light)
          (-11, (5, 10, 13), 10, "minor"),    # Gm/Bb
          (-12, (7, 12, 17), 0, "sus4"),      # Asus4
          (-12, (7, 12, 16), 0, "major"),     # A
          (-12, (10, 16, 19), 0, "harm7")]    # A7

def hum(beat, amount=0.025):
    return beat + R.uniform(-amount, amount)

# ---- felt piano -------------------------------------------------------------------
def piano(track, beat, pitch, level, beats=3.0):
    s.clip("piano", "tones", track, hum(beat), beats, level=level, attack=0.004, release="full",
           pitch=pitch, interval=12, timbre=0.0, detune=0.04, fm_ratio=1.0, fm_depth=0.7,
           env_curve=0.55, cutoff=0.62, reverb=0.5, pan=max(-0.4, min(0.4, (pitch - 10) / 40)))

def ground(bar0, bars, density=3, level=0.5, last_bass_long=False):
    """The ground on the piano: bass on the half bar, then `density` voicing notes broken upward."""
    for k in range(bars * 2):
        bass, upper, _, _ = GROUND[k % 8]
        b = bar0 * BAR + k * 2
        piano(0, b, bass, level * 1.1, beats=4.0 if (last_bass_long and k == bars * 2 - 1) else 3.0)
        piano(0, b, bass + 12, level * 0.35, beats=3.0)
        for i, p in enumerate(upper[:density]):
            piano(1, b + 0.5 * (i + 1), p, level * (0.62 - 0.08 * i), beats=2.6)

# ---- strings and cello -------------------------------------------------------------
def strings(bar0, bars, level=0.25, cut=0.5):
    for k in range(bars * 2):
        _, _, root, q = GROUND[k % 8]
        s.clip("strings", "cloud", 4, bar0 * BAR + k * 2 - 0.3, 2.9, level=level, attack=0.7,
               release=0.8, pitch=root, voices=10, gather=1.0, drift=0.1, timbre=0.6,
               chord=CHORD[q], sub=0.0, cutoff=cut, reverb=0.55)

def cello(bar0, bars, level=0.35):
    for k in range(bars * 2):
        _, _, root, q = GROUND[k % 8]
        shift = root - 5 if root - 5 >= -6 else root - 5 + 12      # from D, nearest
        shift = shift - 12 if shift > 6 else shift
        s.clip("cello", "partials", 2, bar0 * BAR + k * 2 - 0.3, 2.9, level=level, attack=0.6,
               release=0.9, pitch=shift, file=CELLO, gather=0.9, chord=CHORD[q], purity=0.2,
               shimmer=0.2, tilt=-0.3, reverb=0.45, cutoff=0.6)

CHOIR_PITCH = {5: 2.06, 0: -2.94, 10: -4.94}   # D4, A3, G3 from the C4 recording

def choir(bar0, bars, level=0.4):
    for k in range(bars * 2):
        _, _, root, q = GROUND[k % 8]
        s.clip("choir", "choir", 4, bar0 * BAR + k * 2 - 0.3, 2.9, level=level, attack=0.7,
               release=0.9, pitch=CHOIR_PITCH[root], chord=CHORD["minor" if q == "minor" else "major"],
               file=TREBLE, start=0.2, speed=0.3, voices=8, gather=1.0, drift=0.4, wander=2,
               scatter=0.4, reverb=0.65, cutoff=0.75)

# ---- the coil's voice ----------------------------------------------------------------
def voice(beat, beats, pitch, level=0.5, misfire=0.0, flicker=0.12, jitter=0.02, vowel=12):
    ring = 0.4
    long = beats >= 1.5
    s.clip("voice", "arc", 3, hum(beat, 0.02), beats + ring, level=level,
           attack=0.22 if long else 0.08, release=min(0.9, (beats + ring) * s.spb * 0.45),
           pitch=pitch, ring=0.95, formant=vowel, burst=14, jitter=jitter, misfire=misfire,
           flicker=flicker, snap=0.1, color=0.3, reverb=0.55, echo=0.18, pan=0.05,
           **({"lfo_rate": 4.6, "lfo>pitch": 0.09} if long else {}))

# phrases over the 4-bar ground (beat, length, pitch). C#4=16 D4=17 E4=19 F4=20 G4=22 A4=24
# Bb4=25 B4=26 C5=27 C#5=28 D5=29 E5=31 F5=32 G5=34
P1 = [(0.5, 1, 20), (1.5, .5, 19), (2, 1, 19), (3, .5, 17), (3.5, .5, 16), (4, 1.5, 17),
      (5.5, .5, 24), (6, 1, 24), (7, 1, 22), (8, 1.5, 25), (9.5, .5, 24), (10, 1, 22), (11, 1, 20),
      (12, 4, 19)]
P2 = [(0, 1.5, 25), (1.5, .5, 24), (2, 1, 24), (3, 1, 22), (4, 1.5, 20), (5.5, .5, 27),
      (6, 1, 26), (7, 1, 29), (8, 1.5, 29), (9.5, .5, 27), (10, 1, 25), (11, 1, 24),
      (12, 2, 19), (14, 2, 16)]
P3 = [(0, 2, 29), (2, 1.5, 28), (3.5, .5, 31), (4, 1.5, 32), (5.5, .5, 31), (6, 1.5, 29),
      (7.5, .5, 31), (8, 1, 34), (9, 1, 32), (10, 1, 29), (11, 1, 27), (12, 4, 28)]

def phrase(bar0, notes, level=0.5, vowel=12):
    for b, ln, p in notes:
        voice(bar0 * BAR + b, ln, p, level=level, vowel=vowel)

# =============================================================================
# form (bars): piano 0-4 | voice 4-8 | strings 8-12 | choir 12-16 | alone 16-20 | coda 20-22
ground(0, 4, density=2, level=0.45)
ground(4, 4, density=3, level=0.45)
phrase(4, P1, level=0.48)
ground(8, 4, density=3, level=0.5)
strings(8, 4, level=0.36)
cello(8, 4, level=0.3)
phrase(8, P2, level=0.52)
ground(12, 4, density=3, level=0.55)
strings(12, 4, level=0.42, cut=0.6)
cello(12, 4, level=0.38)
choir(12, 4, level=0.42)
phrase(12, P3, level=0.58)
for b, ln, p in P3:                                  # the strings double the climax an octave under
    s.clip("strings_line", "cloud", 4, 12 * BAR + b, ln + 0.4, level=0.16, attack=0.15,
           release=min(0.8, (ln + 0.4) * s.spb * 0.45), pitch=p - 12, voices=4, gather=1.0,
           drift=0.08, timbre=1.2, chord=CHORD["unison"], cutoff=0.55, reverb=0.5)

# alone: the piano, sparser; the voice tries its first phrase again and fails
ground(16, 4, density=2, level=0.42, last_bass_long=True)
broken = P1[:6] + [P1[8]]                        # it loses the middle, gets one more note, stops
for i, (b, ln, p) in enumerate(broken):
    u = i / (len(broken) - 1)
    voice(16 * BAR + b, ln, p, level=0.48 - 0.18 * u, misfire=0.1 + 0.7 * u, flicker=0.15 + 0.6 * u,
          jitter=0.02 + 0.25 * u)

# coda: the tonic low, its third high, and one last breath from the coil
t = 20 * BAR
piano(0, t, -7, 0.5, beats=8); piano(0, t, 5, 0.25, beats=8)
piano(1, t + 1, 8, 0.3, beats=7); piano(1, t + 2.5, 32, 0.18, beats=5.5)
s.clip("last_breath", "arc", 3, t + 3, 4, level=0.25, attack=0.6, release=2.5, pitch=24, ring=0.95,
       formant=12, burst=14, jitter=0.4, misfire=0.9, flicker=0.9, snap=0.1, color=0.3,
       reverb=0.7, echo=0.3, pitch_swp=-1)

# rain, all the way through, thinning at the end
s.clip("rain", "noise", 5, 0, 22 * BAR, level=0.16, attack=2.0, release=3.0, filter=2, cutoff=0.6,
       color=0.4, reverb=0.3, lfo_rate=0.13, **{"lfo>amp": 0.25}, keyed=0)
for i in range(26):                              # a few drops on the sill, nearer
    s.clip("drop", "sample", 5, R.uniform(0, 21) * BAR, 2, level=R.uniform(0.08, 0.16), release="full",
           file=f"aqueous/Aqueous_drip_0000{R.randint(1, 3)}.ogg", pitch=R.uniform(-3, 3), cutoff=0.7,
           reverb=0.5, pan=R.uniform(-0.7, 0.7), keyed=0)

if __name__ == "__main__":
    s.write()
