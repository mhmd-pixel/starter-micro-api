#!/usr/bin/env bash
# Builds podcast-kit.zip: guides, the gm tool, QC script and the Optocast example. No secrets, no audio.
#   scripts/build-kit.sh [output.zip]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$(realpath -m "${1:-$root/out/podcast-kit.zip}")"
stage="$(mktemp -d)"; kit="$stage/podcast-kit"
mkdir -p "$kit/bin" "$kit/tools/gemini-media" "$kit/examples/optocast-astigmatism" "$kit/.claude/skills/gemini-media"
cp "$root/kit/README.md" "$root/docs/PODCAST_GUIDE.md" "$root/docs/LESSONS.md" "$root/vertex.env.example" "$root/.gitignore" "$kit/"
cp "$root/bin/gm" "$root/bin/qc-episode.sh" "$kit/bin/"
cp "$root"/tools/gemini-media/{cli,lib,dialogue,speech}.mts "$root/tools/gemini-media/tsconfig.json" "$kit/tools/gemini-media/"
cp "$root"/projects/optocast-astigmatism/{script.txt,lexicon.tsv,chapters.txt,voicing-report.md} "$kit/examples/optocast-astigmatism/"
sed 's#projects/optocast-astigmatism#examples/optocast-astigmatism#g' "$root/projects/optocast-astigmatism/README.md" > "$kit/examples/optocast-astigmatism/README.md"
# In the kit the guides sit at the root, not in docs/
sed 's#docs/PODCAST_GUIDE.md#PODCAST_GUIDE.md#g; s#docs/LESSONS.md#LESSONS.md#g' "$root/.claude/skills/gemini-media/SKILL.md" > "$kit/.claude/skills/gemini-media/SKILL.md"
node -e '
const fs=require("fs"); const [src,dst]=process.argv.slice(1)
const p=JSON.parse(fs.readFileSync(src+"/package.json")); p.name="gemini-podcast-kit"; p.description="Podcasts from scripts with Gemini TTS + Argon on Vertex AI"; delete p.main
fs.writeFileSync(dst+"/package.json", JSON.stringify(p,null,2)+"\n")
const l=JSON.parse(fs.readFileSync(src+"/package-lock.json")); l.name=p.name; l.packages[""].name=p.name; delete l.packages[""].main
fs.writeFileSync(dst+"/package-lock.json", JSON.stringify(l,null,2)+"\n")' "$root" "$kit"
chmod +x "$kit/bin/gm" "$kit/bin/qc-episode.sh"
# Refuse to ship anything that looks like a credential
if grep -rlE "BEGIN PRIVATE KEY-----\\\\nMII[A-Za-z0-9+/]{20}|gserviceaccount\.com\"?$" "$kit" --exclude=vertex.env.example | grep -v "\.mts$"; then echo "refusing: credential-like content" >&2; exit 1; fi
rm -f "$out"; mkdir -p "$(dirname "$out")"
(cd "$stage" && zip -qr "$out" podcast-kit)
rm -rf "$stage"
echo "$out"; unzip -l "$out" | tail -n +4 | head -n -2 | awk '{print "  " $4}'
