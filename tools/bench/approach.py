#!/usr/bin/env python3
"""Write a BenchRender approach script for any spell, from its recipe in the family file.

    python3 tools/bench/approach.py <family> <spell> [seconds between motions, default 2] > script.txt

Each motion is planted half a ratio under its station and glides onto it; the lock comes when the last one lands,
is held for five seconds, and the motions are then taken off one by one. (The six scripts beside this file are
hand-written approaches for the frozen families in tests/fixtures: the reference renders use them.)"""
import re, sys

def main():
    if len(sys.argv) < 3: sys.exit(__doc__)
    fam, spell = sys.argv[1], sys.argv[2]; step = float(sys.argv[3]) if len(sys.argv) > 3 else 2.0
    cur = None; recipe = None
    for l in open(f"regulator/{fam}.sfx"):
        if l.startswith("spell "): cur = l.split()[1]
        if l.startswith("recipe ") and cur == spell: recipe = l.strip()
    if not recipe: sys.exit(f"no recipe for {spell} in regulator/{fam}.sfx")
    comps = [("XYZ".index(m.group(1)), int(m.group(2)), int(m.group(3)), float(m.group(4) or 1))
             for m in re.finditer(r"\b([XYZ])(\d)p(\d)(?:r([0-9.]+))?", recipe)]
    print(f"# approach {fam} {spell} ({recipe}): a motion every {step:g} s, lock at ~{1 + step * (len(comps) - 1) + 0.5 + step * 0.75:.0f}")
    print(f"@0 pin {spell}")
    used = [0, 0, 0]; planted = []; t = 1.0
    for axis, n, ph, amp in comps:
        arm = used[axis]; used[axis] += 1; planted.append((arm, axis))
        print(f"@{t:g} motion {arm} {axis} {n - 0.5:g} {ph} {amp:g}")
        print(f"@{t + 0.5:g} ramp {arm} {axis} {n} {step * 0.75:g}")
        t += step
    t += 5
    for arm, axis in reversed(planted):
        print(f"@{t:g} off {arm} {axis}"); t += 0.5
    print(f"@{t + 2:g} end")

if __name__ == "__main__":
    main()
