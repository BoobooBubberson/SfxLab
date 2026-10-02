#!/usr/bin/env bash
# tests/run.sh [--bless]
# Compiles the runtime, the GUI and the tests into .build/test, then runs:
#   CoreTest   RegulatorCore (runtime only): matching, the three crank models, levers, events, signals, pen, blueprint
#   ConductedTest  the machine as the game conducts it (runtime only): the verbs, the aim, every default recipe by the casts
#   BakeTest   the baked partials format (runtime only): quantisation bounds, silence, residual gain
#   FormatTest the family format (runtime only): fixtures validate clean and re-write stably; injected problems are named
#   GuiCheck   the regulator panel and the machine window, headless (never saves)
#   golden.sh  the reference renders, bit-identical to tests/golden.md5 (--bless accepts new ones)
# Both run in a scratch workspace, .build/test/ws: the frozen family and project files in tests/fixtures/ (so
# authoring never turns the suite red; refresh them with tests/fixtures.sh when you choose), with samples/ and the
# forge/ analysis cache linked in from the checkout.
# Exits non-zero if anything fails.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
CLS=.build/test/classes
rm -rf "$CLS"; mkdir -p "$CLS"
javac -encoding UTF-8 -d "$CLS" $(find runtime -name '*.java') SfxLab.java tools/bench/BenchRender.java tests/*.java || exit 1
WS=$PWD/.build/test/ws
rm -rf "$WS"; mkdir -p "$WS"
cp -r tests/fixtures/regulator tests/fixtures/projects "$WS/"
for d in samples forge tools; do ln -s "$PWD/$d" "$WS/$d"; done
export SFXLAB_DIR="$WS"
status=0
echo "== CoreTest";  java -cp "$CLS" CoreTest | grep -vE '^PASS|^  ' ; [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
echo "== ConductedTest"; java -cp "$CLS" ConductedTest | grep -vE '^PASS' ; [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
echo "== BakeTest";  java -cp "$CLS" BakeTest | grep -vE '^PASS' ; [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
echo "== FormatTest"; java -cp "$CLS" FormatTest tests/fixtures/regulator | grep -vE '^PASS' ; [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
echo "== GuiCheck";  java -Djava.awt.headless=true -cp "$CLS" GuiCheck "$PWD/.build/test/shots" | grep -vE '^PASS' ; [ "${PIPESTATUS[0]}" -eq 0 ] || status=1
echo "== golden";    tests/golden.sh "$PWD/$CLS" "${1:-}" || status=1
[ $status -eq 0 ] && echo "ALL GREEN" || echo "SOMETHING FAILED"
exit $status
