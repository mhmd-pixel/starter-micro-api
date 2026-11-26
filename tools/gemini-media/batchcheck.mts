/**
 * Experiment: check voiced dialogue lines in batches instead of one Argon call per line.
 *
 * N consecutive lines are joined with a short beep between them; Argon transcribes the
 * numbered segments blind (it never sees the script), and each transcript is diffed against
 * its script line with the same consonant-skeleton rules `gm dialogue` uses per line.
 *
 *   tsx batchcheck.mts --script s.txt --voices Sara=Sulafat,Kaveh=Orus --dir out/cam --size 10
 *        [--lexicon l.tsv] [--inject inject.json] [--only 1-40] [--concurrency 4] [--save result.json]
 *
 * inject.json: [{"index": 12, "file": "bad.wav", "note": "said X instead of Y"}] swaps in a
 * known-bad clip for a line (the script text stays as written), to measure the hit rate.
 */
import { readFileSync, writeFileSync } from "node:fs"
import path from "node:path"
import { LISTEN_MODEL, client, ffmpeg, parseArgs, parseJson, retry, samplesOf, wav } from "./lib.mts"
import { loadLexicon, parseScript } from "./dialogue.mts"
import { RATE, compareText } from "./speech.mts"

const args = parseArgs(process.argv.slice(2), [])
const opt = (k: string) => args.options[k]
const voices = Object.fromEntries(opt("voices").split(",").map((p: string) => p.split("=").map((s) => s.trim())))
const turns = parseScript(opt("script"), loadLexicon(opt("lexicon")), voices)
const dir = path.resolve(opt("dir"))
const manifest = JSON.parse(readFileSync(path.join(dir, "turns.json"), "utf8"))
const size = Number(opt("size") || 10)
const inject: { index: number; file: string; note: string }[] = opt("inject") ? JSON.parse(readFileSync(opt("inject"), "utf8")) : []
const [lo, hi] = (opt("only") || `1-${turns.length}`).split("-").map(Number)

const pcmOf = (file: string) => {
  const s = samplesOf(readFileSync(file))
  if (s.rate !== RATE) throw new Error(`${file}: rate ${s.rate}`)
  return s.pcm
}
const silence = (sec: number) => Buffer.alloc(Math.round(sec * RATE) * 2)
const beep = (() => {
  const n = Math.round(0.18 * RATE), b = Buffer.alloc(n * 2)
  for (let i = 0; i < n; i++) {
    const env = Math.min(1, i / 200, (n - i) / 200)
    b.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 1000 * i) / RATE) * 6000 * env), i * 2)
  }
  return b
})()

const selected = turns.filter((t) => t.index >= lo && t.index <= hi)
const clipFor = (index: number) => {
  const bad = inject.find((x) => x.index === index)
  return bad ? path.resolve(bad.file) : path.join(dir, manifest[String(index)].file)
}
const batches: (typeof selected)[] = []
for (let i = 0; i < selected.length; i += size) batches.push(selected.slice(i, i + size))

const ai = client(opt("env"))
const listener = opt("listener") || "a native Tehrani Persian listener with a trained ear"

async function checkBatch(batch: typeof selected) {
  const parts: Buffer[] = [silence(0.4)]
  batch.forEach((t, k) => {
    if (k) parts.push(silence(0.5), beep, silence(0.5))
    parts.push(pcmOf(clipFor(t.index)))
  })
  parts.push(silence(0.4))
  const audio = await ffmpeg(["-f", "wav", "-i", "-", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", "-f", "mp3", "-"], wav(Buffer.concat(parts), RATE))
  const prompt =
    `You are ${listener}, checking lines of a Persian two-host podcast for optometrists before release. ` +
    `The audio holds exactly ${batch.length} separately recorded segments, separated by a short beep. ` +
    "For each segment, in order, transcribe exactly what is said, in Persian script, as spoken (colloquial forms as heard). " +
    "Write numbers as words, and English clinical terms in Persian script as pronounced. Do not tidy, correct or guess: include slips, " +
    "repeats, and anything that sounds out of place. Never merge or split segments: one entry per beep-separated segment. " +
    `Answer only with JSON: {"segments": [{"n": 1, "heard": "...", "issues": [specific problems only: a mispronounced word (quote it), ` +
    "a foreign accent, glitches, a cut-off word, a voice that does not match the segment's speaker; empty if none]}]}"
  const started = Date.now()
  const r = await retry(
    async () => {
      const response = await ai.models.generateContent({
        model: LISTEN_MODEL,
        contents: [{ role: "user", parts: [{ text: prompt }, { inlineData: { mimeType: "audio/mp3", data: audio.toString("base64") } }] }],
        config: { responseMimeType: "application/json" },
      })
      const j = parseJson<{ segments: { n: number; heard: string; issues: string[] }[] }>(response.text)
      if (!Array.isArray(j.segments)) throw new Error("Unusable review")
      return j.segments
    },
    6,
    `batch ${batch[0].index}-${batch.at(-1)!.index}`,
  )
  const seconds = (Date.now() - started) / 1000
  return batch.map((t, k) => {
    const seg = r[k]
    const heard = seg?.heard ?? ""
    const c = compareText(t.text, heard)
    return {
      index: t.index, speaker: t.speaker, injected: inject.find((x) => x.index === t.index)?.note ?? null,
      ok: c.why.length === 0, why: c.why, heard, issues: seg?.issues ?? [], segments_returned: r.length, batch_len: batch.length, batch_seconds: seconds,
    }
  })
}

const t0 = Date.now()
const results: Awaited<ReturnType<typeof checkBatch>> = []
const queue = [...batches]
await Promise.all(Array.from({ length: Math.min(Number(opt("concurrency") || 4), queue.length) }, async () => {
  while (queue.length) results.push(...(await checkBatch(queue.shift()!)))
}))
results.sort((a, b) => a.index - b.index)
const wall = (Date.now() - t0) / 1000
const flagged = results.filter((r) => !r.ok)
const injected = results.filter((r) => r.injected)
const summary = {
  size, lines: results.length, calls: batches.length, wall_seconds: Math.round(wall),
  flagged: flagged.map((r) => r.index),
  injected_caught: injected.filter((r) => !r.ok).map((r) => r.index),
  injected_missed: injected.filter((r) => r.ok).map((r) => r.index),
  false_alarms: flagged.filter((r) => !r.injected).map((r) => r.index),
  segment_count_mismatches: [...new Set(results.filter((r) => r.segments_returned !== r.batch_len).map((r) => r.index))].length,
}
console.log(JSON.stringify(summary, null, 1))
for (const r of flagged) console.log(`  #${r.index}${r.injected ? " [injected: " + r.injected + "]" : ""}: ${r.why.join("; ")} · heard «${r.heard}»`)
if (opt("save")) writeFileSync(opt("save"), JSON.stringify({ summary, results }, null, 1))
