#!/usr/bin/env bash
# tests/fixtures.sh: refreshes tests/fixtures/ from the committed family and project files (git HEAD), for when the
# reference renders should follow your authoring. Then run tests/run.sh --bless after listening to the new renders.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
for f in regulator/*.sfx projects/pyretic_synth.sfx projects/examples/*.sfx; do
    mkdir -p "tests/fixtures/$(dirname "$f")"
    git show "HEAD:$f" > "tests/fixtures/$f"
done
echo "fixtures refreshed from HEAD:"; git status --short tests/fixtures
