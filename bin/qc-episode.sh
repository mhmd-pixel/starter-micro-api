#!/usr/bin/env bash
# Whole-episode QC: Argon listens to an assembled episode in overlapping parts, as a
# podcast producer, and reports flow, voice consistency, wrong voices and glitches.
#   bin/qc-episode.sh out/<name>/episode.mp3 [part_seconds=270] [listener]
# Reports go to <episode dir>/qc/part-<start>.md. A "cut-off" at 00:00 or at the very
# end of a part is the part boundary, not a real problem.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
episode="$1"; part="${2:-270}"; listener="${3:-a native Tehrani Persian listener}"
dir="$(dirname "$episode")/qc"; mkdir -p "$dir"
length=$("$here/node_modules/.bin/tsx" -e "import('$here/tools/gemini-media/lib.mts').then(async m => console.log(Math.ceil(await m.durationOf('$episode'))))")
prompt='This is part of a podcast assembled from separately recorded lines. Judge it as a podcast producer and audio engineer, concisely: 1) Does it flow like a real conversation? Name transitions (mm:ss) with gaps that feel too long, too short, or overlapping. 2) Does each host keep the same voice, tone and loudness? Name any line that sounds like a different person, a level jump, or a mood jump. 3) Any line in the wrong voice? 4) Glitches, clicks, cut-off or mispronounced words, static? 5) Overall 1-10. Only real problems; say "none" if none.'
start=0
while [ "$start" -lt "$length" ]; do
  end=$((start + part)); [ "$end" -gt "$length" ] && end=$length
  ( "$here/bin/gm" listen "$episode" "$prompt" --start "$start" --end "$end" --listener "$listener" --save "$dir/part-$start.md" >/dev/null 2>&1 \
      && echo "part $start-${end}s done" || echo "part $start-${end}s FAILED" ) &
  start=$((end - 5))   # 5 s overlap so nothing falls between parts
  [ $((length - start)) -le 5 ] && break
done
wait
for f in $(ls "$dir"/part-*.md | sort -t- -k2 -n); do echo; echo "=== $(basename "$f")"; cat "$f"; done
