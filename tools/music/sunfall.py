#!/usr/bin/env python3
"""'Sunfall' - D minor to D major, 84 BPM. A cinematic cue built on the cloud generator.

A cloud is up to 14 oscillators. Ungathered, each wanders anywhere from 0.35x to 12x the
pitch; `gather` pulls voice i onto slot i of a chord table that spans three or four octaves.
That gives this piece all its orchestra:
  - the opening is a Deep Note: 14 voices of noise-like wandering that converge on D
  - a few voices gathered onto `unison` are a section (each voice carries its own +-0.6%
    detune): the string ostinato and the horn theme
  - 14 voices gathered onto a chord are a full, wide string voicing
  - 8 low voices on `power` with a saw, drive and an opening filter are the braam
  - and in the bridge the strings let go of the chord (gather_swp -1) and melt back to cloud
Under them, taiko and timpani made from tones, and the luminal choir.

Form (bars): Deep Note 0-4 | Pulse 4-12 | Rise 12-20 | Dissolve 20-24 | Sunfall 24-32
Progression: Dm Bb F C (two bars each); the ending Bb C D, the major tonic borrowed at the last.
Tracks (GUI numbering): 1 taiko/timpani (duck key) 2 ostinato 3 braam/low strings 4 horns
5 strings/choir 6 deep note/risers/impacts

    python3 tools/music/sunfall.py > projects/sunfall.sfx
"""
from sfxsong import Song, CHORD

BPM = 84
s = Song("Sunfall", BPM, seed=84_2026,
         comment="D minor -> D major, 84 BPM, by Claude. tracks: 1 taiko/timpani 2 ostinato 3 braam/low strings 4 horns 5 strings/choir 6 deep note/fx",
         tracks=[(0.8, 0), (1.0, 0), (0.22, 0), (0.9, 0), (0.8, 0), (0.7, 0)])
R = s.R
BAR = 4
DUCK = dict(duck=0.25, duck_from=1)
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"   # C4: -2.94 makes A3

# (name, root at octave 3 in semitones from A2, quality)
CH = {"Dm": (5, "minor"), "Bb": (1, "major"), "F": (8, "major"), "C": (3, "major"),
      "D": (5, "major")}
PROG = ["Dm", "Dm", "Bb", "Bb", "F", "F", "C", "C"]          # per bar, 8 bars
THIRD = {"minor": 3, "major": 4}

# ---- percussion ---------------------------------------------------------------------
def taiko(beat, vel=1.0, pitch=-6):
    s.clip("taiko", "tones", 0, beat, 2.0, level=0.65 * vel, attack=0.002, release="full", pitch=pitch,
           pitch_swp=-4, interval=0, timbre=0.1, fm_ratio=1.4, fm_depth=1.6, env_curve=0.55,
           reverb=0.35, keyed=0)
    s.clip("taiko_skin", "noise", 0, beat, 0.4, level=0.5 * vel, attack=0.001, release="full",
           filter=1, cutoff=0.32, resonance=0.4, color=0.6, env_curve=0.6, reverb=0.3, keyed=0)

def tom(beat, vel=1.0):
    taiko(beat, 0.55 * vel, pitch=1)

def timpani(beat, pitch, vel=1.0, beats=1.5):
    s.clip("timpani", "tones", 0, beat, beats, level=0.6 * vel, attack=0.003, release="full",
           pitch=pitch, pitch_swp=0.15, interval=12, timbre=0.05, fm_ratio=2.0, fm_depth=0.5,
           env_curve=0.35, reverb=0.4)

def timp_roll(beat, beats, pitch, v0, v1, div=0.25):
    n = int(round(beats / div))
    for i in range(n):
        timpani(beat + i * div, pitch, v0 + (v1 - v0) * i / max(1, n - 1), beats=0.6)

TAIKO_A = [(0, 1.0), (2.5, 0.6)]
TAIKO_B = [(0, 1.0), (1.5, 0.55), (2, 0.8), (3, 0.6), (3.5, 0.85)]

def cymbal_swell(beat, beats, level=0.13):
    """A suspended cymbal rolled into the next downbeat: band-passed noise opening upward."""
    s.clip("cymbal_swell", "noise", 5, beat, beats, level=level, attack=2.0, release=0.05, filter=1,
           cutoff=0.68, cutoff_swp=0.22, resonance=0.1, color=0.0, env_curve=-0.3, reverb=0.5, keyed=0)

def crash(beat, level=0.12):
    s.clip("crash", "noise", 5, beat, 8, level=level, attack=0.002, release="full", filter=2,
           cutoff=0.82, color=0.0, env_curve=0.75, reverb=0.55, pan=0.15, keyed=0)

def impact(beat, level=0.6):
    s.clip("impact", "sample", 5, beat, 6, level=level, release=2.5,
           file="gravitic/gravitic_impact_00001.ogg", keyed=0)

# ---- strings ------------------------------------------------------------------------
def section(name, track, beat, beats, pitch, level, timbre=1.6, cut=0.64, voices=4, **kw):
    """A few cloud voices gathered onto one pitch: a section."""
    s.clip(name, "cloud", track, beat, beats, level=level, pitch=pitch, voices=voices, gather=1.0,
           drift=0.08, timbre=timbre, chord=CHORD["unison"], sub=0.0, cutoff=cut, **kw)

OST = [0, 0, 7, 0, 12, 0, 7, None]   # None = the chord's third, an octave up

def ostinato(bar0, bars, per_beat=2, level=0.55, prog=PROG, octave=0):
    level *= 1.5
    step = 1 / per_beat
    for b in range(bars):
        root, q = CH[prog[b % len(prog)]]
        for i in range(4 * per_beat):
            off = OST[i % len(OST)]
            off = THIRD[q] + 12 if off is None else off
            accent = 1.0 if i % per_beat == 0 else 0.75
            section("ostinato", 1, (bar0 + b) * BAR + i * step, step * 0.9, root + off - 12 + octave,
                    level * accent, attack=0.006, release="full", env_curve=0.35, reverb=0.3,
                    pan=0.25 if i % 2 else -0.25)

def low_strings(bar, bars, chord, level=0.42):
    root, q = CH[chord]
    s.clip("low_strings", "cloud", 2, bar * BAR, bars * BAR + 0.5, level=level, attack=0.4,
           release=1.0, pitch=root - 12, voices=6, gather=1.0, drift=0.05, timbre=1.2,
           chord=CHORD[q], sub=0.25, cutoff=0.42, reverb=0.35, **DUCK)

def strings(bar, bars, chord, level=0.4, attack=1.0, gather=1.0, gather_swp=0.0, cut=0.72,
            release=1.5, pitch_swp=0.0):
    root, q = CH[chord]
    s.clip("strings", "cloud", 4, bar * BAR, bars * BAR + 0.5, level=level, attack=attack,
           release=release, pitch=root, pitch_swp=pitch_swp, voices=14, gather=gather,
           gather_swp=gather_swp, drift=0.15, timbre=1.0, chord=CHORD[q], sub=0.0, cutoff=cut,
           reverb=0.5, **DUCK)

def braam(bar, chord, bars=2, level=0.55):
    root, q = CH[chord]
    s.clip("braam", "cloud", 2, bar * BAR, bars * BAR, level=level, attack=0.03, release=2.0,
           pitch=root - 12, voices=8, gather=1.0, drift=0.05, timbre=2.0, chord=CHORD["power"],
           sub=0.45, cutoff=0.26, cutoff_swp=0.32, drive=0.4, reverb=0.4)

CHOIR_PITCH = {"Dm": 2.06, "Bb": -1.94, "F": 5.06, "C": 0.06, "D": 2.06}  # shift from C4

def choir(bar, bars, chord, level=0.5, **kw):
    q = CH[chord][1]
    s.clip("choir", "choir", 4, bar * BAR, bars * BAR + 0.5, level=level, attack=kw.pop("attack", 0.8),
           release=kw.pop("release", 1.5), pitch=CHOIR_PITCH[chord], chord=CHORD[q], file=TREBLE,
           start=0.2, speed=0.3, voices=8, gather=kw.pop("gather", 1.0), drift=0.5, wander=2,
           scatter=0.4, reverb=0.6, cutoff=0.8, **kw)

# ---- horns --------------------------------------------------------------------------
def horn(beat, beats, pitch, level=0.5, octave_below=False):
    ring = 0.35
    vib = {"lfo_rate": 4.8, "lfo>pitch": 0.1} if beats >= 2 else {}
    for p, lv, pan in ((pitch, level, 0.1), (pitch - 12, level * 0.7, -0.15)) if octave_below else ((pitch, level, 0.0),):
        section("horn", 3, beat, beats + ring, p, lv, timbre=1.25, cut=0.52, voices=3,
                attack=0.07, release=min(0.6, (beats + ring) * s.spb * 0.5), cutoff_swp=0.08,
                reverb=0.45, pan=pan, **vib)

# (beat in the 8-bar phrase, length, pitch) D4=17 E4=19 F4=20 G4=22 A4=24 Bb4=25 C5=27 D5=29
THEME = [(0, 1.5, 17), (1.5, .5, 24), (2, 2, 24),
         (4, 1, 22), (5, 1, 20), (6, 1, 22), (7, 1, 24),
         (8, 3, 25), (11, 1, 24),
         (12, 3, 20), (15, 1, 22),
         (16, 1.5, 24), (17.5, .5, 27), (18, 2, 27),
         (20, 1, 25), (21, 1, 24), (22, 1, 22), (23, 1, 20),
         (24, 3, 19), (27, .5, 20), (27.5, .5, 22),
         (28, 4, 19)]
# the ending over Bb C D: E5=31 F#5=33
FINALE = [(0, 1.5, 20), (1.5, .5, 25), (2, 2, 29),
          (4, 1, 27), (5, 1, 25), (6, 1, 27), (7, 1, 29),
          (8, 3, 31), (11, 1, 29),
          (12, 1, 27), (13, 1, 22), (14, 1, 27), (15, 1, 31),
          (16, 3, 33), (19, 1, 31),
          (20, 8, 29)]

# =============================================================================
DEEP, PULSE, RISE, DISSOLVE, SUNFALL, END = 0, 4, 12, 20, 24, 32

# Deep Note: chaos converging on D (14 beats), then the chord held into the first hit
s.clip("deep_note", "cloud", 5, 0, 14, level=0.5, attack=2.0, release=0.8, pitch=-7, voices=14,
       gather=0.0, gather_swp=1.0, drift=0.6, timbre=1.3, chord=CHORD["power"], sub=0.15,
       cutoff=0.55, cutoff_swp=0.15, reverb=0.45)
s.clip("deep_hold", "cloud", 5, 13, 7, level=0.5, attack=0.7, release=2.5, pitch=-7, voices=14,
       gather=1.0, drift=0.05, timbre=1.3, chord=CHORD["power"], sub=0.15, cutoff=0.7, reverb=0.45)
timp_roll(8, 7.5, -7, 0.15, 0.9)
cymbal_swell(8, 8)

# Pulse
t = PULSE * BAR
crash(t)
impact(t); taiko(t, 1.0); timpani(t, -7, 1.0, beats=3)
braam(PULSE, "Dm", level=0.5)
for b in range(8):
    ch = PROG[b]
    for at, v in TAIKO_A:
        taiko((PULSE + b) * BAR + at, 0.75 * v)
    if b % 2 == 0:
        low_strings(PULSE + b, 2, ch)
ostinato(PULSE, 8)
strings(PULSE + 4, 4, "F", level=0.22, attack=2.0)     # a first glow of the high strings
for b, ln, p in THEME:                                 # the theme's second half enters at bar 8
    if b >= 16:
        horn(PULSE * BAR + b, ln, p, level=0.42)

# Rise
for b in range(8):
    bar = RISE + b
    ch = PROG[b]
    pat = TAIKO_B if b % 4 != 3 else TAIKO_B[:3]
    for at, v in pat:
        taiko(bar * BAR + at, v)
    if b % 4 == 3:                         # tom fill into each four-bar phrase
        for i, at in enumerate((3, 3.25, 3.5, 3.75)):
            tom(bar * BAR + at, 0.6 + 0.12 * i)
    if b % 2 == 0:
        braam(bar, ch)
        low_strings(bar, 2, ch)
        strings(bar, 2, ch, level=0.36, attack=0.4)
        choir(bar, 2, ch)
        timpani(bar * BAR, CH[ch][0] - 12, 0.9)
ostinato(RISE, 8, per_beat=4, level=0.5)
cymbal_swell((RISE - 1) * BAR, BAR, level=0.1); crash(RISE * BAR); crash((RISE + 4) * BAR, 0.1)
for b, ln, p in THEME:
    horn(RISE * BAR + b, ln, p, level=0.5, octave_below=True)

# Dissolve: the strings let go of the chord; a riser; a beat of silence
t = DISSOLVE * BAR
taiko(t, 0.9); timpani(t, -7, 0.8, beats=3)
strings(DISSOLVE, 4, "Dm", level=0.4, attack=0.05, gather=1.0, gather_swp=-1.0, release=0.3,
        pitch_swp=12)
choir(DISSOLVE, 2, "Dm", level=0.45, release=1.0)
for b, ln, p in [(0, 3, 24), (3, 1, 22), (4, 4, 20)]:
    horn(t + b, ln, p, level=0.4)
s.clip("riser", "cloud", 5, (DISSOLVE + 2) * BAR, 2 * BAR - 1, level=0.4, attack=2.0, release=0.05,
       pitch=-7, pitch_swp=12, voices=12, gather=0.4, gather_swp=0.6, drift=0.5, timbre=1.8,
       chord=CHORD["octaves"], sub=0.0, cutoff=0.4, cutoff_swp=0.5, reverb=0.3)
cymbal_swell((DISSOLVE + 2) * BAR, 2 * BAR - 1, level=0.2)
timp_roll((DISSOLVE + 2) * BAR + 4, 3, -7, 0.25, 1.0)

# Sunfall: Bb C D, the theme climbing to F#
FIN = ["Bb", "Bb", "C", "C", "D", "D", "D", "D"]
t = SUNFALL * BAR
impact(t, 0.7); crash(t, 0.15)
for b in range(6):
    bar = SUNFALL + b
    ch = FIN[b]
    for at, v in TAIKO_B:
        taiko(bar * BAR + at, v)
    if b % 2 == 0:
        braam(bar, ch, level=0.6)
        low_strings(bar, 2, ch)
        strings(bar, 2 if b < 4 else 4, ch, level=0.4, attack=0.3)
        choir(bar, 2 if b < 4 else 4, ch, level=0.55)
        timpani(bar * BAR, CH[ch][0] - 12, 1.0)
ostinato(SUNFALL, 4, per_beat=4, level=0.5, prog=FIN)
ostinato(SUNFALL + 4, 1, per_beat=4, level=0.45, prog=["D"])
for b, ln, p in FINALE:
    horn(t + b, ln, p, level=0.5, octave_below=True)
# the last hit, then the D major chord left ringing
t = (SUNFALL + 5) * BAR
taiko(t, 1.0); timpani(t, -7, 1.0, beats=4); impact(t, 0.55); crash(t, 0.15)
s.clip("final_chord", "cloud", 5, t, 3 * BAR, level=0.45, attack=0.05, release=3.0, pitch=5,
       voices=14, gather=1.0, drift=0.1, timbre=1.1, chord=CHORD["major"], sub=0.2, cutoff=0.6,
       cutoff_swp=-0.25, reverb=0.6)

if __name__ == "__main__":
    s.write()
