#!/usr/bin/env python3
"""Before and after the envelope controls (decay, sus level, env>cut, env>fm, mod decay; 2026-11-09).

Each instrument plays the phrase twice: first as it could be made before, then with the new
controls. The three at the end could not be made before, so their "before" is the same patch with
the filter fixed halfway along the sweep the "after" makes.

    python3 tools/music/envelope_ab.py > projects/envelope_ab.sfx
    python3 tools/music/envelope_ab.py --list
"""
import sys
from sfxsong import Song
import instruments as I

BPM = 90
s = Song("Envelope A/B", BPM, seed=11_2026, comment="each instrument twice: before, then with the envelope controls, by Claude",
         tracks=[(1.0, 0)] * 6)
I.s = s                                     # the instrument functions write into this song

# ---- after ------------------------------------------------------------------------------
def felt_piano_2(beat, beats, p, lv):
    """The hammer's brightness falls away in half a second while the note keeps ringing."""
    s.clip("felt_piano", "tones", 0, beat, beats + 2, level=lv, attack=0.004, release="full", pitch=p,
           interval=12, timbre=0.0, detune=0.04, fm_ratio=1.0, fm_depth=1.1, env_curve=0.45,
           cutoff=0.5, env_cut=0.3, mod_decay=0.45, reverb=0.4)

def marimba_2(beat, beats, p, lv):
    """A real bar's 4th partial dies far faster than its fundamental: only the knock is bright."""
    s.clip("marimba", "tones", 0, beat, 1.4, level=lv, attack=0.002, release="full", pitch=p,
           interval=0, timbre=2.0, fm_ratio=4.0, fm_depth=0.9, env_curve=0.7, mod_decay=0.05, reverb=0.3)

def electric_piano_2(beat, beats, p, lv):
    """The tine's bark on the attack, then a round tone that decays and holds."""
    s.clip("e_piano", "tones", 0, beat, beats + 1.5, level=lv, attack=0.003, release=0.4, pitch=p,
           interval=0, timbre=2.0, detune=0.02, fm_ratio=1.0, fm_depth=3.0, mod_decay=0.12,
           decay=0.8, sus_level=0.35, cutoff=0.75, lfo_rate=4.5, **{"lfo>amp": 0.35}, phaser=0.2, reverb=0.3)

def violin_2(beat, beats, p, lv):
    """The bow bites (brighter and louder for a moment), then settles to a held tone."""
    s.clip("violin", "tones", 2, beat, beats, level=lv, attack=0.06, release=min(0.2, beats * 0.35), pitch=p,
           interval=0, timbre=1.0, detune=0.03, cutoff=0.62, resonance=0.35, env_cut=0.14,
           decay=0.25, sus_level=0.8, lfo_rate=5.6, **{"lfo>pitch": 0.13}, reverb=0.45)

def brass(beat, beats, p, lv, new=True):
    """Brass: the filter opens with the breath. A sharp accent that settles."""
    extra = dict(env_cut=0.42, decay=0.35, sus_level=0.7) if new else dict(cutoff=0.51)   # before: the filter fixed halfway
    s.clip("brass", "tones", 2, beat, beats, level=lv, attack=0.05, release=min(0.15, beats * 0.3), pitch=p,
           interval=0, timbre=1.0, detune=0.15, resonance=0.15, drive=0.15,
           lfo_rate=5.0, **{"lfo>pitch": 0.05}, reverb=0.4, **{"cutoff": 0.3, **extra})

def synth_pluck(beat, beats, p, lv, new=True):
    """The synth pluck: a saw whose filter snaps shut."""
    extra = dict(env_cut=0.55, mod_decay=0.12) if new else dict(cutoff=0.55)
    s.clip("synth_pluck", "tones", 3, beat, beats + 1, level=lv, attack=0.002, release="full", pitch=p,
           interval=12, timbre=1.0, detune=0.25, resonance=0.45, env_curve=0.4,
           echo=0.25, reverb=0.3, **{"cutoff": 0.28, **extra})

def acid_bass(beat, beats, p, lv, new=True):
    """Acid: a resonant low-pass on a saw, swept down by every note."""
    extra = dict(env_cut=0.5, mod_decay=0.16) if new else dict(cutoff=0.43)
    s.clip("acid", "tones", 4, beat, beats * 0.9, level=lv, attack=0.003, release=min(0.08, beats * 0.2),
           pitch=p, interval=0, timbre=1.0, resonance=0.8, drive=0.35, **{"cutoff": 0.18, **extra})

PAIRS = [
    ("felt piano", I.felt_piano, felt_piano_2, 0),
    ("marimba", I.marimba, marimba_2, 0),
    ("electric piano", I.electric_piano, electric_piano_2, 0),
    ("violin", I.violin, violin_2, 12),
    ("brass", lambda *a: brass(*a, new=False), brass, 0),
    ("synth pluck", lambda *a: synth_pluck(*a, new=False), synth_pluck, 12),
    ("acid bass", lambda *a: acid_bass(*a, new=False), acid_bass, -12),
]
LEVEL = {"felt piano": 0.6, "marimba": 0.9, "electric piano": 0.45, "violin": 0.17, "brass": 0.2,
         "synth pluck": 0.3, "acid bass": 0.35}
# dB per (instrument, before/after) so both halves of a pair play at the same loudness (measured, listen.py)
TRIM = {'felt piano/before': 0.1, 'felt piano/after': -1.8, 'marimba/before': -3.3, 'marimba/after': -5.6, 'electric piano/before': -4.9, 'electric piano/after': -7.2, 'violin/before': 1.9, 'violin/after': 2.4, 'brass/before': -4.4, 'brass/after': -3.1, 'synth pluck/before': 5.2, 'synth pluck/after': 4.6, 'acid bass/before': -9.7, 'acid bass/after': -6.8}
SLOT = 10

def order():
    t = 0
    for name, old, new, octave in PAIRS:
        for tag, fn in (("before", old), ("after", new)):
            yield t, name, tag, fn, octave
            t += SLOT

for t, name, tag, fn, octave in order():
    for b, ln, p in I.MEL:
        fn(t + b, ln, p + octave, min(1.0, LEVEL[name] * 10 ** (TRIM.get(f"{name}/{tag}", 0) / 20)))

if __name__ == "__main__":
    if "--list" in sys.argv:
        for t, name, tag, *_ in order():
            print(f"{t * 60 / BPM:6.1f} s  {name}, {tag}")
    else:
        s.write()
