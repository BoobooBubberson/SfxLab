#!/usr/bin/env bash
# sfx_batch.sh - batch equivalent of the Audacity workflow:
#   Clip Fix  ->  Normalize (peak, remove DC)  ->  export mono .ogg (Vorbis)
#
# Usage:
#   sfx_batch.sh [options] file1.flac file2.wav ...
#   sfx_batch.sh [options] -d some_dir          # all audio files in a directory
#
# Options:
#   -o DIR      output directory (default: ./processed)
#   -p DB       target peak in dBFS (default: -1.0, same as Audacity Normalize)
#   -q N        vorbis quality 0-10 (default: 5, roughly Audacity's default)
#   -r HZ       output sample rate (default: 44100; use 0 to keep source rate)
#   -t N        adeclip threshold (default: 10; lower = more aggressive)
#   -s          keep stereo (default: mix to mono)
#   -f          overwrite existing outputs
#   -n          dry run: print what would be done
#
# Requires: ffmpeg with libvorbis (checked at start).
set -euo pipefail

OUT="./processed"
PEAK_DB="-1.0"
QUALITY=5
RATE=44100
THRESH=10
MONO=1
FORCE=0
DRY=0
SRC_DIR=""

usage() { sed -n '2,20p' "$0"; exit 1; }

while getopts "o:p:q:r:t:d:sfnh" opt; do
  case "$opt" in
    o) OUT="$OPTARG" ;;
    p) PEAK_DB="$OPTARG" ;;
    q) QUALITY="$OPTARG" ;;
    r) RATE="$OPTARG" ;;
    t) THRESH="$OPTARG" ;;
    d) SRC_DIR="$OPTARG" ;;
    s) MONO=0 ;;
    f) FORCE=1 ;;
    n) DRY=1 ;;
    h|*) usage ;;
  esac
done
shift $((OPTIND - 1))

command -v ffmpeg >/dev/null || { echo "ffmpeg not found" >&2; exit 1; }
ffmpeg -hide_banner -encoders 2>/dev/null | grep -q libvorbis || { echo "ffmpeg lacks libvorbis" >&2; exit 1; }

# Collect inputs
files=()
if [[ -n "$SRC_DIR" ]]; then
  while IFS= read -r -d '' f; do files+=("$f"); done < <(
    find "$SRC_DIR" -maxdepth 1 -type f \
      \( -iname '*.flac' -o -iname '*.wav' -o -iname '*.mp3' -o -iname '*.ogg' -o -iname '*.m4a' -o -iname '*.aiff' -o -iname '*.aif' \) \
      -print0 | sort -z)
fi
files+=("$@")
[[ ${#files[@]} -gt 0 ]] || usage

mkdir -p "$OUT"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

channels_filter="anull"
[[ $MONO -eq 1 ]] && channels_filter="pan=mono|c0=0.5*FL+0.5*FR"
rate_filter="anull"
[[ "$RATE" != "0" ]] && rate_filter="aresample=${RATE}"

ok=0; skipped=0; failed=0
for in_f in "${files[@]}"; do
  base="$(basename "${in_f%.*}")"
  out_f="$OUT/$base.ogg"

  if [[ -e "$out_f" && $FORCE -eq 0 ]]; then
    echo "skip   $base.ogg (exists, use -f to overwrite)"; skipped=$((skipped+1)); continue
  fi
  if [[ $DRY -eq 1 ]]; then
    echo "would  $in_f -> $out_f"; continue
  fi

  # Pass 1: declip + downmix + resample into a 32-bit float wav (no clamping of
  # restored peaks), and measure peak + DC offset of the result in the same run.
  # Resampling must come before the measurement: it can lower peaks noticeably.
  mid="$TMP/$base.wav"
  if ! stats="$(ffmpeg -hide_banner -nostdin -y -i "$in_f" \
        -af "adeclip=threshold=${THRESH},${channels_filter},${rate_filter},astats=measure_perchannel=none:measure_overall=Peak_level+DC_offset" \
        -c:a pcm_f32le "$mid" 2>&1)"; then
    echo "FAIL   $in_f (declip pass)"; echo "$stats" | tail -5; failed=$((failed+1)); continue
  fi
  peak="$(echo "$stats" | awk '/Peak level dB/ {v=$NF} END {print v}')"
  dc="$(echo "$stats"   | awk '/DC offset/     {v=$NF} END {print v}')"
  if [[ -z "$peak" || "$peak" == "-inf" ]]; then
    echo "FAIL   $in_f (silent or unreadable)"; failed=$((failed+1)); continue
  fi

  # Pass 2: remove DC, apply gain so peak lands on PEAK_DB, encode mono ogg.
  gain="$(awk -v t="$PEAK_DB" -v p="$peak" 'BEGIN {printf "%.4f", t - p}')"
  dcshift="$(awk -v d="$dc" 'BEGIN {printf "%.6f", -d}')"
  if ! err="$(ffmpeg -hide_banner -nostdin -y -i "$mid" \
        -af "dcshift=shift=${dcshift},volume=${gain}dB" \
        -c:a libvorbis -q:a "$QUALITY" "$out_f" 2>&1)"; then
    echo "FAIL   $in_f (encode pass)"; echo "$err" | tail -5; failed=$((failed+1)); continue
  fi
  rm -f "$mid"
  printf "done   %-45s peak %+6.2f dB -> gain %+6.2f dB, dc %+.4f\n" "$base.ogg" "$peak" "$gain" "$dc"
  ok=$((ok+1))
done

echo "---- $ok processed, $skipped skipped, $failed failed -> $OUT"
[[ $failed -eq 0 ]]
