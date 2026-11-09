#!/usr/bin/env python3
"""An audition sheet: instruments hiding in SfxLab's clip types, each playing the same
phrase, one after another, so they can be compared and lifted into pieces.

    python3 tools/music/instruments.py > projects/instruments.sfx
    python3 tools/music/instruments.py --list          # the running order with start times

Each instrument is a function note(s, beat, beats, pitch, level) in INSTRUMENTS; copy the one
you want. Levels are set so each sits near -20 dB RMS (see GAIN, measured with listen.py).
Pitch is semitones from A2 = 110 Hz; the phrase is written around A3 and moved into each
instrument's range by `octave`.
"""
import sys
from sfxsong import Song, CHORD, cutoff_for_hz, hz

BPM = 90
s = Song("Instruments", BPM, seed=9_2026,
         comment="an audition of instruments made from the lab's clip types, by Claude",
         tracks=[(1.0, 0)] * 6)
R = s.R

# ---- the instruments ----------------------------------------------------------------
def felt_piano(beat, beats, p, lv):        # from the_coil_forgets: FM ratio 1 + octave, decaying
    s.clip("felt_piano", "tones", 0, beat, beats + 2, level=lv, attack=0.004, release="full", pitch=p,
           interval=12, timbre=0.0, detune=0.04, fm_ratio=1.0, fm_depth=0.7, env_curve=0.55, cutoff=0.62,
           reverb=0.4)

def electric_piano(beat, beats, p, lv):    # softer FM, tremolo panning: a tine piano
    s.clip("e_piano", "tones", 0, beat, beats + 1.5, level=lv, attack=0.003, release="full", pitch=p,
           interval=0, timbre=0.0, detune=0.02, fm_ratio=1.0, fm_depth=0.45, env_curve=0.35,
           cutoff=0.7, lfo_rate=4.5, **{"lfo>amp": 0.35}, phaser=0.2, reverb=0.3)

def marimba(beat, beats, p, lv):           # a tuned bar: the 4th partial, a quick decay
    s.clip("marimba", "tones", 0, beat, 1.2, level=lv, attack=0.002, release="full", pitch=p,
           interval=0, timbre=0.0, fm_ratio=4.0, fm_depth=0.55, env_curve=0.9, reverb=0.3)

def vibraphone(beat, beats, p, lv):        # metal bar: longer ring, motor tremolo
    s.clip("vibraphone", "tones", 0, beat, beats + 2.5, level=lv, attack=0.002, release="full", pitch=p,
           interval=12, timbre=0.0, fm_ratio=4.0, fm_depth=0.3, env_curve=0.45, lfo_rate=5.5,
           **{"lfo>amp": 0.45}, reverb=0.45)

def music_box(beat, beats, p, lv):         # a high comb tooth: inharmonic FM ratio, short and bright
    s.clip("music_box", "tones", 0, beat, 2.5, level=lv, attack=0.001, release="full", pitch=p,
           interval=0, timbre=0.0, fm_ratio=7.13, fm_depth=0.35, env_curve=0.7, reverb=0.45, echo=0.15)

def tubular_bell(beat, beats, p, lv):      # additive bank, harmonics stretched apart: metal
    s.clip("tubular_bell", "tones", 1, beat, 4, level=lv, attack=0.002, release="full", pitch=p,
           interval=0, timbre=0.0, bank=1.0, stretch=0.09, tilt=-0.35, env_curve=0.5, reverb=0.5)

def pipe_organ(beat, beats, p, lv):        # bank, all harmonics, a little dark; no envelope
    s.clip("pipe_organ", "tones", 1, beat, beats, level=lv, attack=0.03, release=0.08, pitch=p,
           interval=12, timbre=0.0, bank=1.0, tilt=-0.45, reverb=0.6)

def clarinet(beat, beats, p, lv):          # bank, odd harmonics only: the hollow tube
    s.clip("clarinet", "tones", 1, beat, beats, level=lv, attack=0.06, release=min(0.12, beats * 0.3),
           pitch=p, interval=0, timbre=0.0, bank=1.0, tilt=-0.5, cutoff=0.7, lfo_rate=5.0,
           **{"odd/even": -1.0, "lfo>pitch": 0.05}, reverb=0.35)

def flute(beat, beats, p, lv):             # near-sine and an octave, breath noise around the note
    s.clip("flute", "tones", 1, beat, beats, level=lv, attack=0.07, release=min(0.15, beats * 0.3),
           pitch=p, interval=12, timbre=0.15, lfo_rate=5.2, **{"lfo>pitch": 0.08}, reverb=0.45)
    s.clip("flute_breath", "noise", 1, beat, beats, level=lv * 0.07, attack=0.03,
           release=min(0.15, beats * 0.3), filter=1, cutoff=cutoff_for_hz(hz(p) * 2), resonance=0.85,
           color=0.5, reverb=0.45)

def ocarina(beat, beats, p, lv):           # the arc with a long ring on the octave: almost pure
    s.clip("ocarina", "arc", 2, beat, beats, level=lv, attack=0.05, release=min(0.15, beats * 0.3),
           pitch=p, ring=1.0, formant=12, burst=25, jitter=0.01, snap=0.0, color=0.2,
           lfo_rate=5.0, **{"lfo>pitch": 0.06}, reverb=0.45)

def reed(beat, beats, p, lv):              # the arc unrung, band-passed: a buzzy double reed
    s.clip("reed", "arc", 2, beat, beats, level=lv, attack=0.04, release=min(0.12, beats * 0.3),
           pitch=p, ring=0.3, formant=31.02, burst=1.5, jitter=0.005, snap=1.0, color=0.35,
           filter=1, cutoff=0.72, resonance=0.3, lfo_rate=5.5, **{"lfo>pitch": 0.06}, reverb=0.4)

def violin(beat, beats, p, lv):            # saw, a body band-pass, vibrato
    s.clip("violin", "tones", 2, beat, beats, level=lv, attack=0.1, release=min(0.2, beats * 0.35),
           pitch=p, interval=0, timbre=2.0, detune=0.03, cutoff=0.74, resonance=0.35,
           lfo_rate=5.6, **{"lfo>pitch": 0.13}, reverb=0.45)

def harp(beat, beats, p, lv):
    s.clip("harp", "pluck", 3, beat, beats + 2.5, level=lv, attack=0.002, release="full", pitch=p,
           damp=0.3, sustain=0.95, pick=0.45, reverb=0.45)

def koto(beat, beats, p, lv):              # bright, plucked near the bridge, a small bend up
    s.clip("koto", "pluck", 3, beat, beats + 1.5, level=lv, attack=0.001, release="full", pitch=p - 0.4,
           pitch_swp=0.4 if beats >= 1 else 0, damp=0.12, sustain=0.92, pick=0.93, reverb=0.35)

def sitar(beat, beats, p, lv):             # bright pluck through a little drive and a band-pass: buzz
    s.clip("sitar", "pluck", 3, beat, beats + 1.5, level=lv, attack=0.001, release="full", pitch=p,
           damp=0.08, sustain=0.96, pick=0.97, drive=0.35, filter=1, cutoff=0.74, resonance=0.35,
           flange=0.25, reverb=0.35)

def fretless_bass(beat, beats, p, lv):     # dark pluck, low, a little slide into each note
    s.clip("fretless", "pluck", 4, beat, beats + 0.5, level=lv, attack=0.003, release="full", pitch=p - 0.3,
           pitch_swp=0.3, damp=0.75, sustain=0.92, pick=0.3, cutoff=0.45)
    s.clip("fretless_body", "tones", 4, beat, beats + 0.5, level=lv * 0.6, attack=0.01, release="full",
           pitch=p - 0.3, pitch_swp=0.3, interval=12, timbre=0.1, env_curve=0.3)

def didgeridoo(beat, beats, p, lv):        # an arc drone through a resonant band-pass wobbling: the drone
    s.clip("didgeridoo", "arc", 4, beat, beats, level=lv, attack=0.05, release=0.1, pitch=p, ring=0.4,
           formant=24, burst=3, snap=0.9, jitter=0.02, filter=1, cutoff=0.42, resonance=0.75,
           lfo_rate=2.2, **{"lfo>cut": 0.18}, reverb=0.3)

def wind_chimes(beat, beats, p, lv):       # sparkle: random pentatonic pings, FM decaying with each
    s.clip("wind_chimes", "sparkle", 5, beat, beats, level=lv, attack=0.2, release=1.0, pitch=p,
           density=4, ping_decay=0.4, spread=1.0, range=2, fm_ratio=3.5, fm_depth=1.6, reverb=0.6)

def choir_ooh(beat, beats, p, lv):         # cloud, sine voices gathered on the chord
    s.clip("choir_ooh", "cloud", 5, beat, beats, level=lv, attack=0.5, release=0.8, pitch=p, voices=8,
           gather=1.0, drift=0.15, timbre=0.1, chord=CHORD["minor"], sub=0.0, cutoff=0.6,
           lfo_rate=4.5, **{"lfo>pitch": 0.05}, reverb=0.6)

# (name, function, octave shift of the phrase, phrase kind)
INSTRUMENTS = [
    ("felt piano", felt_piano, 0, "mel"), ("electric piano", electric_piano, 0, "mel"),
    ("marimba", marimba, 0, "mel"), ("vibraphone", vibraphone, 0, "mel"),
    ("music box", music_box, 24, "mel"), ("tubular bell", tubular_bell, 12, "slow"),
    ("pipe organ", pipe_organ, 0, "chords"), ("clarinet", clarinet, 0, "mel"),
    ("flute", flute, 12, "mel"), ("ocarina", ocarina, 12, "mel"), ("reed", reed, 0, "mel"),
    ("violin", violin, 12, "mel"), ("harp", harp, 0, "mel"), ("koto", koto, 0, "mel"),
    ("sitar", sitar, 0, "mel"), ("fretless bass", fretless_bass, -24, "mel"),
    ("didgeridoo", didgeridoo, -12, "drone"), ("wind chimes", wind_chimes, 12, "texture"),
    ("choir ooh", choir_ooh, 0, "chords"),
]

# measured: dB to add so each sits near -20 dB RMS in its slot (listen.py, see --calibrate)
GAIN = {'felt piano': 1.7, 'electric piano': -1.7, 'marimba': 6.0, 'vibraphone': 3.3, 'music box': 2.2, 'tubular bell': 0.2, 'pipe organ': -4.9, 'clarinet': -4.0, 'flute': 1.8, 'ocarina': -5.1, 'reed': 1.5, 'violin': -9.6, 'harp': 6.2, 'koto': 5.6, 'sitar': -5.9, 'fretless bass': 12.5, 'didgeridoo': 2.8, 'wind chimes': -1.2, 'choir ooh': 0.9}

# the phrase, around A3 (12): A3 C4 E4 A4 G4 E4 D4 E4 (held)
MEL = [(0, 1, 12), (1, .5, 15), (1.5, .5, 19), (2, 1, 24), (3, .5, 22), (3.5, .5, 19), (4, 1, 17),
       (5, 3, 19)]
SLOW = [(0, 2, 12), (2, 2, 19), (4, 4, 17)]
CHORDS = [(0, 4, (12, 15, 19)), (4, 4, (10, 14, 17))]       # Am, then G
SLOT = 10                                                   # beats per instrument

def play(i, name, fn, octave, kind):
    t0 = i * SLOT
    lv = min(1.0, 0.5 * 10 ** (GAIN.get(name, 0) / 20))
    if kind == "mel":
        for b, ln, p in MEL:
            fn(t0 + b, ln, p + octave, lv)
    elif kind == "slow":
        for b, ln, p in SLOW:
            fn(t0 + b, ln, p + octave, lv)
    elif kind == "chords":
        if fn is choir_ooh:
            fn(t0, 4, 12 + octave, lv); s.clip("choir_ooh", "cloud", 5, t0 + 4, 4, level=lv, attack=0.5,
                release=0.8, pitch=10 + octave, voices=8, gather=1.0, drift=0.15, timbre=0.1,
                chord=CHORD["major"], cutoff=0.6, lfo_rate=4.5, **{"lfo>pitch": 0.05}, reverb=0.6)
        else:
            for b, ln, ps in CHORDS:
                for p in ps:
                    fn(t0 + b, ln, p + octave, lv * 0.6)
    elif kind == "drone":
        fn(t0, 8, 12 + octave, lv)
    elif kind == "texture":
        fn(t0, 8, 12 + octave, lv)

for i, (name, fn, octave, kind) in enumerate(INSTRUMENTS):
    play(i, name, fn, octave, kind)

if __name__ == "__main__":
    if "--list" in sys.argv:
        for i, (name, *_ ) in enumerate(INSTRUMENTS):
            print(f"{i * SLOT * 60 / BPM:6.1f} s  {name}")
    else:
        s.write()
