#!/usr/bin/env python3
"""'The Coil Remembers' (The Coil Forgets, part II) - D minor, 112 BPM. From the lament into something with teeth.

112 is twice part I's 56, so the lament ground goes on at the same pace, one chord a bar now:
D C# C B Bb A in the bass (Dm A/C# Dm/C G/B Gm/Bb Asus4 A A7), eight bars a turn.

  Static    0-8    where part I stopped: rain, the felt piano, the coil trying its first phrase. This time
                   every note misfires LESS than the last: it is learning it back. A heartbeat kick; power-up
  Pulse     8-16   the beat: kick, snare, the coil-ticker hats; acid bass on the ground (env>cut accents),
                   a synth-pluck arpeggio; the coil sings its second phrase, steady now
  Build     16-24  brass stabs (env>cut, a settling accent), strings, the acid opening bar by bar; a riser,
                   a snare roll, a half bar of nothing
  Assault   24-40  the ground as a chromatic metal riff on the heavy guitar (D C# C B Bb A), chugs with the
                   kick under them, distorted acid doubling it, brass on every bar, choir; the coil screams
                   the climax phrase through drive, an octave under it doubling
  The light 40-42  everything stops but brass and choir on G major, the chord that lasted two beats in part I,
                   held two bars
  Remember  42-51  one last turn, full; the coil's first phrase again, and where part I broke off on Bb it
                   falls to A this time, and the turn ends on D instead of going round. One hit, the coil's D left
                   ringing alone

Uses the envelope controls (decay, sus_level, env>cut, mod_decay) and timbre 1 = the saw.
Tracks (GUI numbering): 1 kick (duck key) 2 drums 3 bass 4 coil 5 piano/pluck/brass/strings/choir 6 guitar/fx

    python3 tools/music/the_coil_remembers.py > projects/the_coil_remembers.sfx
"""
from sfxsong import Song, CHORD, ticker_pitch

BPM = 112
s = Song("The Coil Remembers", BPM, seed=112_2026,
         comment="D minor, 112 BPM, part II of The Coil Forgets, by Claude. tracks: 1 kick 2 drums 3 bass 4 coil 5 keys/brass/choir 6 guitar/fx",
         tracks=[(0.45, 0), (0.7, 0), (0.4, 0), (0.7, 0), (0.55, 0), (0.45, 0)])
R = s.R
BAR = 4
DUCK = dict(duck=0.5, duck_from=1)
CELLO = "resonant/cello_00002_pad.ogg"
TREBLE = "luminal/luminal_synth_vocal_treble_00004.ogg"
GTR = "resonant/guitar_heavy_00002.ogg"          # one D power chord: pitch 0 = D

# the ground, one chord per bar: (bass, upper voicing, chord root, quality); semitones from A2
GROUND = [(-7, (5, 8, 12), 5, "minor"),       # Dm
          (-8, (7, 12, 16), 0, "major"),      # A/C#
          (-9, (8, 12, 17), 5, "minor"),      # Dm/C
          (-10, (5, 10, 14), 10, "major"),    # G/B
          (-11, (5, 10, 13), 10, "minor"),    # Gm/Bb
          (-12, (7, 12, 17), 0, "sus4"),      # Asus4
          (-12, (7, 12, 16), 0, "major"),     # A
          (-12, (10, 16, 19), 0, "harm7")]    # A7

def hum(beat, amount=0.02):
    return beat + R.uniform(-amount, amount)

def g(bar):
    return GROUND[bar % 8]

# ---- keys -----------------------------------------------------------------------------
def piano(beat, pitch, level, beats=4.0):
    s.clip("piano", "tones", 4, hum(beat), beats, level=level, attack=0.004, release="full", pitch=pitch,
           interval=12, timbre=0.0, detune=0.04, fm_ratio=1.0, fm_depth=1.1, env_curve=0.45, cutoff=0.5,
           env_cut=0.3, mod_decay=0.45, reverb=0.5, pan=max(-0.4, min(0.4, (pitch - 10) / 40)))

def piano_ground(bar0, bars, level=0.45, density=3):
    for b in range(bars):
        bass, upper, _, _ = g(bar0 + b)
        t = (bar0 + b) * BAR
        piano(t, bass, level * 1.1, beats=6)
        piano(t, bass + 12, level * 0.35, beats=6)
        for i, p in enumerate(upper[:density]):
            piano(t + (i + 1), p, level * (0.62 - 0.08 * i), beats=5)

def pluck_arp(bar0, bars, level=0.32, step=0.5):
    pat = [0, 1, 2, 1, 2, 0, 1, 2]      # through the voicing, an octave up
    for b in range(bars):
        _, upper, _, _ = g(bar0 + b)
        for i in range(int(BAR / step)):
            p = upper[pat[i % len(pat)]] + 12 + (12 if i % 8 >= 6 else 0)
            s.clip("pluck", "tones", 4, (bar0 + b) * BAR + i * step, step * 2.5, level=level * (1 if i % 2 == 0 else 0.75),
                   attack=0.002, release="full", pitch=p, interval=12, timbre=1.0, detune=0.25, cutoff=0.3,
                   resonance=0.45, env_curve=0.4, env_cut=0.5, mod_decay=0.1, echo=0.22, reverb=0.3,
                   pan=0.3 if i % 2 else -0.3, **DUCK)

def brass_stab(beat, beats, bar, level=0.3, octave=0):
    _, upper, root, q = g(bar)
    for p, pan in zip(upper, (-0.3, 0.0, 0.3)):
        s.clip("brass", "tones", 4, beat, beats, level=level, attack=0.03, release=min(0.25, beats * 0.25 * 60 / BPM),
               pitch=p + 12 + octave, interval=-12, timbre=1.0, detune=0.15, cutoff=0.3, resonance=0.15,
               drive=0.25, env_cut=0.45, decay=0.3, sus_level=0.65, reverb=0.35, pan=pan)

def strings(bar0, bars, level=0.3, cut=0.55):
    for b in range(bars):
        _, _, root, q = g(bar0 + b)
        s.clip("strings", "cloud", 4, (bar0 + b) * BAR - 0.2, BAR + 0.6, level=level, attack=0.3, release=0.5,
               pitch=root, voices=10, gather=1.0, drift=0.1, timbre=1.0, chord=CHORD[q], sub=0.0,
               cutoff=cut, reverb=0.5, **DUCK)

CHOIR_PITCH = {5: 2.06, 0: -2.94, 10: -4.94}

def choir(bar0, bars, level=0.4):
    for b in range(bars):
        _, _, root, q = g(bar0 + b)
        s.clip("choir", "choir", 4, (bar0 + b) * BAR - 0.2, BAR + 0.6, level=level, attack=0.3, release=0.5,
               pitch=CHOIR_PITCH[root], chord=CHORD["minor" if q == "minor" else "major"], file=TREBLE,
               start=0.2, speed=0.3, voices=8, gather=1.0, drift=0.4, wander=2, scatter=0.4, reverb=0.6,
               cutoff=0.75)

# ---- bass -----------------------------------------------------------------------------
ACID = [(0, 0, 1.0), (0.5, 0, 0.5), (1, 12, 0.7), (1.5, 0, 0.5), (2, 0, 0.9), (2.5, 7, 0.6), (3, 12, 0.8), (3.5, 0, 0.6)]

def acid(bar0, bars, cut0=0.16, cut1=0.16, drive=0.35, level=0.38, riff=False):
    for b in range(bars):
        bass = g(bar0 + b)[0]
        cut = cut0 + (cut1 - cut0) * b / max(1, bars - 1)
        pat = CHUG if riff else ACID
        for at, off, acc in pat:
            ln = 0.45
            s.clip("acid", "tones", 2, (bar0 + b) * BAR + at, ln, level=level * (0.75 + 0.25 * acc),
                   attack=0.003, release=0.06, pitch=bass + off, interval=0, timbre=1.0, cutoff=cut,
                   resonance=0.78, drive=drive, env_cut=0.25 + 0.35 * acc, mod_decay=0.14, **DUCK)

def sub(bar0, bars, level=0.35):
    for b in range(bars):
        s.clip("sub", "tones", 2, (bar0 + b) * BAR, BAR, level=level, attack=0.01, release=0.1,
               pitch=g(bar0 + b)[0], interval=0, timbre=2.0, **DUCK)

# ---- guitar riff: the ground's bass as power chords, chugged ----------------------------
# (beat, length, accent); a long chord on the one, sixteenth chugs after
CHUG = [(0, 0, 1.0), (0.75, 0, 0.5), (1.5, 0, 0.7), (2, 0, 0.9), (2.75, 0, 0.5), (3, 0, 0.8), (3.5, 0, 0.6)]

def riff(bar0, bars, level=0.55):
    for b in range(bars):
        bass = g(bar0 + b)[0]
        shift = bass + 7                   # from D2 (-7): D=0, C#=-1, C=-2, B=-3, Bb=-4, A=-5
        t = (bar0 + b) * BAR
        for at, _, acc in CHUG:
            long = at in (0, 2)
            s.clip("gtr", "sample", 5, t + at, 0.9 if long else 0.4, level=level * (0.7 + 0.3 * acc),
                   attack=0.002, release=0.12 if long else 0.06, pitch=shift, file=GTR,
                   cutoff=0.62 if long else 0.48, drive=0.3, pan=-0.35 if b % 2 else 0.35)

# ---- drums ----------------------------------------------------------------------------
def kick(beat, vel=1.0, dark=False):
    s.clip("kick", "tones", 0, beat, 0.6, level=0.8 * vel, attack=0.001, release="full", pitch=3,
           pitch_swp=-22, interval=0, timbre=0.0, drive=0.5, env_curve=0.35,
           cutoff=0.35 if dark else 1.0, keyed=0)

def snare(beat, vel=1.0, tune=0.0):
    s.clip("snare", "noise", 1, beat, 0.5, level=min(1.0, 0.85 * vel), attack=0.001, release="full", filter=1,
           cutoff=0.74, resonance=0.2, color=0.3, env_curve=0.6, reverb=0.25, keyed=0)
    s.clip("snare_body", "tones", 1, beat, 0.3, level=min(1.0, 0.65 * vel), attack=0.001, release="full",
           pitch=9 + tune, pitch_swp=-7, interval=7, timbre=0.0, drive=0.35, env_curve=0.5, keyed=0)

def tom(beat, pitch, vel=1.0):
    s.clip("tom", "tones", 1, beat, 1.0, level=0.6 * vel, attack=0.001, release="full", pitch=pitch,
           pitch_swp=-5, interval=0, timbre=2.0, fm_ratio=1.4, fm_depth=1.2, env_curve=0.5, reverb=0.3, keyed=0)

def crash(beat, level=0.25):
    s.clip("crash", "noise", 1, beat, 8, level=level, attack=0.001, release="full", filter=2, cutoff=0.8,
           color=0.0, env_curve=0.75, reverb=0.45, pan=0.2, keyed=0)

def hats(bar0, bars, per_beat=4, misfire=0.35, level=0.4):
    s.clip("coil_hats", "arc", 1, bar0 * BAR, bars * BAR, level=level, attack=0.0, release=0.05,
           pitch=ticker_pitch(BPM, per_beat), divide=4, burst=1.2, snap=0.85, misfire=misfire, filter=2,
           cutoff=0.74, pan=-0.25, keyed=0)

# ---- the coil ---------------------------------------------------------------------------
def voice(beat, beats, pitch, level=0.5, misfire=0.0, flicker=0.12, jitter=0.02, drive=0.0, burst=14,
          pan=0.05):
    ring = 0.5
    long = beats >= 2
    s.clip("voice", "arc", 3, hum(beat), beats + ring, level=level, attack=0.12 if long else 0.05,
           release=min(0.8, (beats + ring) * s.spb * 0.45), pitch=pitch, ring=0.95, formant=12,
           burst=burst, jitter=jitter, misfire=misfire, flicker=flicker, snap=0.1, color=0.3, drive=drive,
           reverb=0.45, echo=0.15, pan=pan, **({"lfo_rate": 4.8, "lfo>pitch": 0.1} if long else {}))

# part I's phrases (in its beats; here each of its beats is two) C#4=16 D4=17 E4=19 F4=20 G4=22 A4=24
# Bb4=25 B4=26 C5=27 C#5=28 D5=29 E5=31 F5=32 G5=34
P1 = [(0.5, 1, 20), (1.5, .5, 19), (2, 1, 19), (3, .5, 17), (3.5, .5, 16), (4, 1.5, 17),
      (5.5, .5, 24), (6, 1, 24), (7, 1, 22), (8, 1.5, 25), (9.5, .5, 24), (10, 1, 22), (11, 1, 20),
      (12, 4, 19)]
P2 = [(0, 1.5, 25), (1.5, .5, 24), (2, 1, 24), (3, 1, 22), (4, 1.5, 20), (5.5, .5, 27),
      (6, 1, 26), (7, 1, 29), (8, 1.5, 29), (9.5, .5, 27), (10, 1, 25), (11, 1, 24),
      (12, 2, 19), (14, 2, 16)]
P3 = [(0, 2, 29), (2, 1.5, 28), (3.5, .5, 31), (4, 1.5, 32), (5.5, .5, 31), (6, 1.5, 29),
      (7.5, .5, 31), (8, 1, 34), (9, 1, 32), (10, 1, 29), (11, 1, 27), (12, 4, 28)]

def sing(bar0, phrase, shift=0, **kw):
    for b, ln, p in phrase:
        voice(bar0 * BAR + 2 * b, 2 * ln, p + shift, **kw)

# =============================================================================
STATIC, PULSE, BUILD, ASSAULT, LIGHT, REMEMBER, END = 0, 8, 16, 24, 40, 42, 51

# Static: the rain from part I, thinning; the piano; the coil learning its phrase back
s.clip("rain", "noise", 5, 0, 8 * BAR, level=0.16, attack=0.5, release=3.0, filter=2, cutoff=0.6,
       color=0.4, reverb=0.3, lfo_rate=0.13, **{"lfo>amp": 0.25}, keyed=0)
piano_ground(STATIC, 8, level=0.42, density=2)
for i, (b, ln, p) in enumerate(P1):
    u = i / (len(P1) - 1)
    voice(STATIC * BAR + 2 * b, 2 * ln, p, level=0.3 + 0.18 * u, misfire=0.8 * (1 - u) ** 1.5,
          flicker=0.7 * (1 - u) + 0.1, jitter=0.25 * (1 - u) + 0.02)
for b in range(4, 8):                           # a heartbeat under it, closer each bar
    t = b * BAR
    kick(t, 0.35 + 0.1 * (b - 4), dark=True); kick(t + 0.75, 0.25 + 0.08 * (b - 4), dark=True)
s.clip("power_up", "arc", 5, 6 * BAR, 2 * BAR, level=0.3, attack=1.5, release=0.05, pitch=-7, pitch_swp=36,
       ring=0.4, formant=12, burst=4, jitter=0.1, snap=0.5, flicker=0.3, reverb=0.3)
sub(STATIC + 4, 4, level=0.25)

# Pulse
crash(PULSE * BAR, 0.2)
for b in range(8):
    t = (PULSE + b) * BAR
    for k in range(4):
        kick(t + k, 0.9 if k % 2 == 0 else 0.75)
    snare(t + 1, 0.8); snare(t + 3, 0.9)
hats(PULSE, 8, misfire=0.5, level=0.35)
acid(PULSE, 8, cut0=0.14, cut1=0.2)
sub(PULSE, 8)
pluck_arp(PULSE, 8, level=0.3)
piano_ground(PULSE, 8, level=0.3, density=1)
sing(PULSE, P2, level=0.5)

# Build
crash(BUILD * BAR, 0.25)
for b in range(8):
    t = (BUILD + b) * BAR
    for k in range(4):
        kick(t + k, 0.9)
    if b < 7:
        snare(t + 1, 0.85); snare(t + 3, 0.95)
        if b % 2 == 1:
            snare(t + 3.5, 0.5)
    brass_stab(t, 1.5, BUILD + b, level=0.22 + 0.01 * b)
hats(BUILD, 7, per_beat=4, misfire=0.25, level=0.4)
acid(BUILD, 7, cut0=0.2, cut1=0.34, drive=0.45)
sub(BUILD, 7)
pluck_arp(BUILD, 7, level=0.3)
strings(BUILD, 7, level=0.25)
for i, p in enumerate([29, 31, 32, 34]):        # the coil climbs, a bar each, over the second half
    voice((BUILD + 4 + i) * BAR, 3.5 if i < 3 else 1.5, p, level=0.45 + 0.03 * i, drive=0.1 * i)
t = (BUILD + 7) * BAR                           # last bar: snare roll and toms into a half bar of nothing
for i in range(8):
    snare(t + i * 0.25, 0.4 + 0.08 * i, tune=i)
for i, p in enumerate((5, 2, -1, -4)):
    tom(t + 0.25 * i, p, 0.7 + 0.1 * i)
s.clip("riser", "noise", 5, (BUILD + 6) * BAR, 6, level=0.22, attack=2.0, release=0.05, filter=2,
       cutoff=0.4, cutoff_swp=0.45, color=0.0, reverb=0.3)

# Assault: two turns of the ground as a riff
crash(ASSAULT * BAR, 0.3)
s.clip("impact", "sample", 5, ASSAULT * BAR, 6, level=0.55, release=2.0,
       file="gravitic/gravitic_impact_00001.ogg", keyed=0)
for b in range(16):
    t = (ASSAULT + b) * BAR
    for at, _, acc in CHUG:                    # the kick doubles the guitar's accented chugs
        if acc >= 0.7:
            kick(t + at, 0.6 + 0.35 * acc)
    snare(t + 1, 1.0); snare(t + 3, 1.0)
    if b % 4 == 3:
        snare(t + 3.5, 0.6); snare(t + 3.75, 0.75)
    brass_stab(t, 1.0, ASSAULT + b, level=0.3)
    if b % 8 == 7:
        crash(t + 4, 0.22)
riff(ASSAULT, 16)
acid(ASSAULT, 16, cut0=0.3, cut1=0.3, drive=0.7, level=0.32, riff=True)
hats(ASSAULT, 16, per_beat=4, misfire=0.1, level=0.6)
choir(ASSAULT, 16, level=0.38)
strings(ASSAULT, 16, level=0.22, cut=0.62)
sing(ASSAULT, P3, level=0.3, drive=0.45, burst=9)
sing(ASSAULT, P3, shift=-12, level=0.2, drive=0.3, burst=9, pan=-0.3)
sing(ASSAULT + 8, P2, shift=12, level=0.27, drive=0.45, burst=9)
sing(ASSAULT + 8, P2, level=0.19, drive=0.3, burst=9, pan=-0.3)

# The light: G major, held
t = LIGHT * BAR
kick(t, 1.0); crash(t, 0.25)
for p, pan in zip((10, 14, 17, 22, 26), (-0.4, -0.2, 0.0, 0.2, 0.4)):   # G3 B3 D4 G4 B4
    s.clip("brass_light", "tones", 4, t, 2 * BAR, level=0.22, attack=0.15, release=1.2, pitch=p,
           interval=-12 if p < 15 else 0, timbre=1.0, detune=0.2, cutoff=0.42, env_cut=0.2, decay=1.2,
           sus_level=0.8, reverb=0.55, pan=pan)
s.clip("choir_light", "choir", 4, t, 2 * BAR, level=0.5, attack=0.4, release=1.2, pitch=-4.94,
       chord=CHORD["major"], file=TREBLE, start=0.2, speed=0.3, voices=8, gather=1.0, drift=0.3, wander=2,
       scatter=0.4, reverb=0.7, cutoff=0.8)
s.clip("sub_light", "tones", 2, t, 2 * BAR, level=0.35, attack=0.05, release=1.0, pitch=-10, interval=12,
       timbre=2.0)
voice(t, 3, 26, level=0.5); voice(t + 4, 3.5, 29, level=0.5)

# Remember: the last turn, and the phrase completed
crash(REMEMBER * BAR, 0.3)
for b in range(8):
    t = (REMEMBER + b) * BAR
    for at, _, acc in CHUG:
        if acc >= 0.7:
            kick(t + at, 0.6 + 0.35 * acc)
    snare(t + 1, 1.0); snare(t + 3, 1.0)
    brass_stab(t, 1.0, REMEMBER + b, level=0.3)
riff(REMEMBER, 8)
acid(REMEMBER, 8, cut0=0.3, cut1=0.4, drive=0.7, level=0.32, riff=True)
hats(REMEMBER, 8, per_beat=4, misfire=0.05, level=0.6)
choir(REMEMBER, 8, level=0.4)
strings(REMEMBER, 8, level=0.24, cut=0.65)
done = [n for n in P1 if n[0] < 12] + [(12, 2, 19), (14, 2, 16)]   # ends on C#, the leading tone, and then:
for b, ln, p in done:
    voice(REMEMBER * BAR + 2 * b, 2 * ln, p + 12, level=0.32, drive=0.35, burst=10)
    voice(REMEMBER * BAR + 2 * b, 2 * ln, p, level=0.22, drive=0.25, burst=10, pan=-0.3)

# the end: one hit on D, and the coil's D ringing alone, clean
t = (REMEMBER + 8) * BAR
kick(t, 1.0); crash(t, 0.35); snare(t, 1.0)
s.clip("impact_end", "sample", 5, t, 8, level=0.6, release=2.5, file="gravitic/gravitic_impact_00001.ogg", keyed=0)
s.clip("gtr_end", "sample", 5, t, 8, level=0.6, attack=0.002, release=2.5, pitch=0, file=GTR, cutoff=0.65, drive=0.3)
for p, pan in zip((5, 8, 12, 17), (-0.3, -0.1, 0.1, 0.3)):
    s.clip("brass_end", "tones", 4, t, 2, level=0.28, attack=0.02, release=0.6, pitch=p + 12, interval=-12,
           timbre=1.0, detune=0.15, cutoff=0.3, env_cut=0.45, decay=0.3, sus_level=0.5, drive=0.25, reverb=0.5,
           pan=pan)
s.clip("sub_end", "tones", 2, t, 6, level=0.4, attack=0.005, release=3.0, pitch=-7, interval=12, timbre=2.0)
voice(t, 12, 29, level=0.5)                     # D5, no misfire, no drive
piano(t + 4, -7, 0.4, beats=10); piano(t + 4, 5, 0.25, beats=10)

if __name__ == "__main__":
    s.write()
