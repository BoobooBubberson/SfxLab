#!/usr/bin/env bash
# tests/golden.sh <classes-dir> [--bless]      (run by tests/run.sh, which sets up $SFXLAB_DIR)
# Renders the reference set (every timeline project at key 0 and +7, one mono export, and a scripted machine
# approach per family through tools/bench/BenchRender) and compares the md5 of each wav with tests/golden.md5.
# The renders must stay bit-identical across refactors; a change that is meant to change the sound is blessed with
# --bless after listening to the new renders in .build/test/renders/.
# The hashes belong to this machine: they depend on the ffmpeg that decodes the recordings (6.1.1 when blessed) and
# on the local sample library (projects/examples/ use samples/minecraft_sounds_assets/, which git does not track).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CP=$1; BLESS=${2:-}
OUT=$ROOT/.build/test/renders
rm -rf "$OUT"; mkdir -p "$OUT"
cd "$SFXLAB_DIR"   # the scratch workspace: fixtures' regulator/ and projects/, the checkout's samples/, forge/, tools/
J="java -Djava.awt.headless=true -Dsfxlab.noautosave=true -cp $CP"
for p in projects/pyretic_synth.sfx projects/examples/*.sfx; do
    n=$(basename "$p" .sfx)
    $J SfxLab --render "$p" "$OUT/tl_$n.wav" >/dev/null 2>&1
    $J SfxLab --render "$p" "$OUT/tl_${n}_k7.wav" --key 7 >/dev/null 2>&1
done
$J SfxLab --render projects/pyretic_synth.sfx "$OUT/tl_pyretic_mono.wav" --mono --normalize >/dev/null 2>&1
while read -r fam spell; do
    $J BenchRender "regulator/$fam.sfx" "$fam" "tools/bench/$spell.txt" "$OUT/bench_${fam}_$spell.wav" >"$OUT/bench_${fam}_$spell.log" 2>&1
done <<LIST
pyretic firebolt
pyretic torch_lance
gravitic gravity_well
gravitic singularity
luminal prism
aqueous undertow
aqueous_mystic undertow
LIST
(cd "$OUT" && md5sum *.wav) > "$ROOT/.build/test/golden.md5"
if [ "$BLESS" = "--bless" ]; then
    cp "$ROOT/.build/test/golden.md5" "$ROOT/tests/golden.md5"
    echo "blessed $(wc -l < "$ROOT/tests/golden.md5") renders into tests/golden.md5"
    exit 0
fi
if diff "$ROOT/tests/golden.md5" "$ROOT/.build/test/golden.md5" >/dev/null; then
    echo "PASS $(wc -l < "$ROOT/tests/golden.md5") reference renders bit-identical"
else
    echo "FAIL reference renders changed (listen in $OUT/):"
    diff "$ROOT/tests/golden.md5" "$ROOT/.build/test/golden.md5" | grep '^>' | awk '{print "  " $3}'
    exit 1
fi
