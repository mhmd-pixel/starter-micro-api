/**
 * gm dialogue: voices a two-speaker script with Gemini TTS multi-speaker mode,
 * checks every chunk by ear with Argon, and assembles one normalised episode.
 *
 *   gm dialogue script.txt --voices Sara=Sulafat,Kaveh=Charon [--out out/episode]
 *       [--lexicon lexicon.tsv] [--max-words 130] [--retakes 2] [--only 3,7] [--force]
 *       [--assemble] [--gap 0.35] [--section-gap 0.9] [--tempo 1.0] [--listener "…"]
 *
 * Script: one turn per line as `Name: text`. A line starting with `#` starts a new
 * section (its text becomes a chapter title); `>` lines and blank lines are ignored.
 * Lexicon: `written<TAB>spoken` per line, applied before speech only, so the script
 * stays as written (e.g. `اپتوکست	اُپتوکست`).
 *
 * Each chunk is cached by a hash of model + voices + spoken text, so re-running only
 * voices what changed. A chunk passes when the blind transcription has no run of
 * dropped or added words and the number of turns matches; otherwise it is retaken.
 */
import { createHash } from "node:crypto"
import { existsSync, readFileSync } from "node:fs"
import { copyFile, mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { Modality, type GoogleGenAI } from "@google/genai"
import { LISTEN_MODEL, TTS_MODEL, ffmpeg, parseJson, retry, samplesOf, secondsOf, wav } from "./lib.mts"

export interface DialogueOptions {
  ai: () => GoogleGenAI
  script: string
  voices: Record<string, string>
  out: string
  lexicon?: string
  maxWords: number
  retakes: number
  only?: number[]
  force: boolean
  assembleOnly: boolean
  gap: number
  sectionGap: number
  tempo: number
  listener: string
  concurrency: number
}

interface Turn {
  speaker: string
  text: string
}

interface Chunk {
  index: number
  section: string
  sectionStart: boolean
  turns: Turn[]
  spoken: string
  hash: string
}

interface Check {
  heardTurns: { voice: string; text: string }[]
  natural: number
  issues: string[]
  missing: string[]
  extra: string[]
  longestMissing: string
  longestExtra: string
  turnsExpected: number
  turnsHeard: number
  score: number
  ok: boolean
}

interface Entry {
  hash: string
  file: string
  duration: number
  takes: number
  check?: Check
  reviewError?: string
}

type Manifest = Record<string, Entry>

const RATE = 24000

// ---------------------------------------------------------------- script

function parseScript(file: string, lexicon: [string, string][]) {
  const turns: (Turn & { section: string; sectionStart: boolean })[] = []
  let section = "Start"
  let sectionStart = true
  for (const raw of readFileSync(file, "utf8").split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith(">")) continue
    if (line.startsWith("#")) {
      section = line.replace(/^#+\s*/, "")
      sectionStart = true
      continue
    }
    const match = line.match(/^([^:：]{1,40})[:：]\s*(.+)$/)
    if (!match) throw new Error(`Not a "Name: text" line: ${line.slice(0, 60)}`)
    turns.push({ speaker: match[1].trim(), text: match[2].trim(), section, sectionStart })
    sectionStart = false
  }
  const speak = (text: string) => lexicon.reduce((t, [from, to]) => t.split(from).join(to), text)
  return { turns, speak }
}

function loadLexicon(file?: string): [string, string][] {
  if (!file) return []
  return readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.startsWith("#"))
    .map((line) => line.split("\t") as [string, string])
    .filter((pair) => pair.length === 2 && pair[0])
}

/** Speaker1, Speaker2… in the order the voices were given */
const alias = (voices: Record<string, string>, speaker: string) => {
  const index = Object.keys(voices).indexOf(speaker)
  if (index < 0) throw new Error(`No voice for speaker "${speaker}" (have ${Object.keys(voices).join(", ")})`)
  return `Speaker${index + 1}`
}

export const wordCount = (text: string) => text.replace(/\[[^\]]*\]/g, " ").split(/\s+/).filter(Boolean).length

function chunkTurns(turns: ReturnType<typeof parseScript>["turns"], speak: (t: string) => string, maxWords: number, voices: Record<string, string>) {
  const chunks: Chunk[] = []
  let current: typeof turns = []
  const flush = () => {
    if (!current.length) return
    // Real names as labels get read aloud ("سارا، کیس اول…"); neutral labels do not
    const spoken = current.map((t) => `${alias(voices, t.speaker)}: ${speak(t.text)}`).join("\n")
    const hash = createHash("sha1").update(`${TTS_MODEL}|${JSON.stringify(voices)}|${spoken}`).digest("hex").slice(0, 10)
    chunks.push({ index: chunks.length + 1, section: current[0].section, sectionStart: current[0].sectionStart, turns: current, spoken, hash })
    current = []
  }
  for (const turn of turns) {
    const words = current.reduce((n, t) => n + wordCount(t.text), 0)
    if (current.length && (turn.sectionStart || words + wordCount(turn.text) > maxWords)) flush()
    current.push(turn)
  }
  flush()
  return chunks
}

// ---------------------------------------------------------------- persian-aware word compare

export function normWords(text: string) {
  return text
    .replace(/\[[^\]]*\]|\([^)]*\)|（[^）]*）/g, " ") // [laughing], (خنده)
    .replace(/[ً-ٰٟـ]/g, "") // harakat, tatweel
    .replace(/[يى]/g, "ی")
    .replace(/ك/g, "ک")
    .replace(/[ة]/g, "ه")
    .replace(/[أإآ]/g, "ا")
    .replace(/ؤ/g, "و")
    .replace(/[‌‍]/g, "") // ZWNJ joins: می‌گه = میگه
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w.replace(/هی$/, "ه")) // ezafe written or not: همه‌ی = همه
}

/** LCS diff that also reports the longest run of consecutive missing or extra words */
export function diffRuns(intended: string, heard: string) {
  const a = normWords(intended)
  const b = normWords(heard)
  const t = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--) for (let j = b.length - 1; j >= 0; j--) t[i][j] = a[i] === b[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1])
  const missing: string[] = []
  const extra: string[] = []
  let runMissing: string[] = []
  let runExtra: string[] = []
  let longestMissing: string[] = []
  let longestExtra: string[] = []
  const close = () => {
    if (runMissing.length > longestMissing.length) longestMissing = runMissing
    if (runExtra.length > longestExtra.length) longestExtra = runExtra
    runMissing = []
    runExtra = []
  }
  let i = 0
  let j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      close()
      i++, j++
    } else if (j < b.length && (i >= a.length || t[i][j + 1] >= t[i + 1][j])) {
      extra.push(b[j])
      runExtra.push(b[j++])
    } else {
      missing.push(a[i])
      runMissing.push(a[i++])
    }
  }
  close()
  return { words: a.length, missing, extra, longestMissing: longestMissing.join(" "), longestExtra: longestExtra.join(" ") }
}

// ---------------------------------------------------------------- voice and check

async function synthesize(ai: GoogleGenAI, chunk: Chunk, voices: Record<string, string>) {
  const speakers = [...new Set(chunk.turns.map((t) => t.speaker))]
  const all = Object.keys(voices)
  for (const s of speakers) if (!voices[s]) throw new Error(`No voice for speaker "${s}" (have ${all.join(", ")})`)
  // Multi-speaker mode wants both speakers declared even when a chunk has one
  const speakerVoiceConfigs = all.map((speaker) => ({ speaker: alias(voices, speaker), voiceConfig: { prebuiltVoiceConfig: { voiceName: voices[speaker] } } }))
  return retry(
    async () => {
      const response = await ai.models.generateContent({
        model: TTS_MODEL,
        contents: [{ role: "user", parts: [{ text: chunk.spoken }] }],
        config: {
          responseModalities: [Modality.AUDIO],
          speechConfig: all.length > 1 ? { multiSpeakerVoiceConfig: { speakerVoiceConfigs } } : { voiceConfig: speakerVoiceConfigs[0].voiceConfig },
        },
      })
      const parts = response.candidates?.[0]?.content?.parts ?? []
      const clips = parts.filter((p) => p.inlineData?.data).map((p) => samplesOf(Buffer.from(p.inlineData!.data!, "base64")))
      if (!clips.length) throw new Error("no audio")
      if (clips[0].rate !== RATE) throw new Error(`unexpected sample rate ${clips[0].rate}`)
      return edge(Buffer.concat(clips.map((c) => c.pcm)))
    },
    5,
    `chunk ${chunk.index} speech`,
  )
}

/** Trims silence generously (speech tails are quiet in Persian endings) and fades the edges */
function edge(pcm: Buffer) {
  const samples = Math.floor(pcm.length / 2)
  const loud = (i: number) => Math.abs(pcm.readInt16LE(i * 2)) > 32768 * 0.01
  let start = 0
  while (start < samples && !loud(start)) start++
  let end = samples - 1
  while (end > start && !loud(end)) end--
  const from = Math.max(0, start - Math.round(RATE * 0.05))
  const to = Math.min(samples, end + Math.round(RATE * 0.3))
  const out = Buffer.from(pcm.subarray(from * 2, to * 2))
  const n = out.length / 2
  const fadeIn = Math.min(n, Math.round(RATE * 0.01))
  const fadeOut = Math.min(n, Math.round(RATE * 0.08))
  for (let i = 0; i < fadeIn; i++) out.writeInt16LE(Math.round(out.readInt16LE(i * 2) * (i / fadeIn)), i * 2)
  for (let i = 0; i < fadeOut; i++) {
    const at = n - 1 - i
    out.writeInt16LE(Math.round(out.readInt16LE(at * 2) * (i / fadeOut)), at * 2)
  }
  return out
}

async function check(ai: GoogleGenAI, chunk: Chunk, pcm: Buffer, listener: string): Promise<Check> {
  const audio = await ffmpeg(["-f", "wav", "-i", "-", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", "-f", "mp3", "-"], wav(pcm, RATE))
  const prompt =
    `You are ${listener}. This is part of a two-host Persian podcast for optometrists (a woman and a man). ` +
    "Transcribe exactly what is said, in Persian script, as spoken (colloquial forms as heard). " +
    "Write numbers as words, and English clinical terms in Persian script as they are pronounced. Do not tidy, correct, summarise or guess: " +
    "include slips, repeats and any words that seem out of place. Start a new turn at every change of speaker. " +
    'Answer only with JSON: {"turns": [{"voice": "female" or "male", "text": "..."}], "natural": 1-10, ' +
    '"issues": [specific problems: mispronounced words (quote them), English or foreign-sounding accent, wrong voice for a turn, ' +
    "glitches, static, clicks, cut-off words, unnatural pauses or overlaps, a laugh that sounds fake; empty if none]}"
  const heard = await retry(
    async () => {
      const response = await ai.models.generateContent({
        model: LISTEN_MODEL,
        contents: [{ role: "user", parts: [{ text: prompt }, { inlineData: { mimeType: "audio/mp3", data: audio.toString("base64") } }] }],
        config: { responseMimeType: "application/json" },
      })
      const r = parseJson<{ turns: { voice: string; text: string }[]; natural: number; issues: string[] }>(response.text)
      if (!Array.isArray(r.turns) || !r.turns.every((t) => typeof t.text === "string") || !Array.isArray(r.issues)) throw new Error("Unusable review")
      if (normWords(r.turns.map((t) => t.text).join(" ")).length < normWords(chunk.spoken).length * 0.3) throw new Error("Review too short to be a transcription")
      return r
    },
    4,
    `chunk ${chunk.index} review`,
  )
  const intended = chunk.turns.map((t) => t.text).join(" ")
  const diff = diffRuns(intended, heard.turns.map((t) => t.text).join(" "))
  const runMissing = diff.longestMissing.split(" ").filter(Boolean).length
  const runExtra = diff.longestExtra.split(" ").filter(Boolean).length
  // Merge consecutive same-voice turns the listener may have split
  const turnsHeard = heard.turns.filter((t, i, all) => i === 0 || all[i - 1].voice !== t.voice).length
  const expected = chunk.turns.filter((t, i, all) => i === 0 || all[i - 1].speaker !== t.speaker).length
  const turnGap = Math.abs(turnsHeard - expected)
  const score = diff.missing.length + diff.extra.length + 4 * Math.max(0, runMissing - 2) + 4 * Math.max(0, runExtra - 2) + 6 * turnGap
  const loose = Math.max(3, Math.round(diff.words * 0.06))
  const ok = runMissing <= 3 && runExtra <= 3 && turnGap === 0 && diff.missing.length + diff.extra.length <= loose
  return { heardTurns: heard.turns, natural: heard.natural, issues: heard.issues, ...diff, turnsExpected: expected, turnsHeard, score, ok }
}

// ---------------------------------------------------------------- run

async function pool<T>(items: T[], size: number, run: (item: T) => Promise<void>) {
  const queue = [...items]
  await Promise.all(Array.from({ length: Math.min(size, queue.length) }, async () => {
    while (queue.length) await run(queue.shift()!)
  }))
}

const fmt = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`

export async function dialogue(o: DialogueOptions) {
  const { turns, speak } = parseScript(o.script, loadLexicon(o.lexicon))
  const chunks = chunkTurns(turns, speak, o.maxWords, o.voices)
  const dir = path.resolve(o.out)
  const chunkDir = path.join(dir, "chunks")
  const takeDir = path.join(dir, "takes")
  await mkdir(chunkDir, { recursive: true })
  await mkdir(takeDir, { recursive: true })
  const manifestFile = path.join(dir, "manifest.json")
  const manifest: Manifest = existsSync(manifestFile) ? JSON.parse(readFileSync(manifestFile, "utf8")) : {}
  const save = () => writeFile(manifestFile, JSON.stringify(manifest, null, 1) + "\n")
  const totalWords = turns.reduce((n, t) => n + wordCount(t.text), 0)
  console.log(`${turns.length} turns, ${totalWords} words, ${chunks.length} chunks · ${Object.entries(o.voices).map(([s, v]) => `${s}=${v}`).join(", ")}`)

  const todo = chunks.filter((c) => {
    if (o.assembleOnly) return false
    if (o.only) return o.only.includes(c.index)
    const entry = manifest[c.index]
    return o.force || !entry || entry.hash !== c.hash || !existsSync(path.join(dir, entry.file))
  })
  if (todo.length) console.log(`voicing ${todo.length} chunk(s): ${todo.map((c) => c.index).join(", ")}`)

  await pool(todo, o.concurrency, async (chunk) => {
    let best: { pcm: Buffer; check?: Check; error?: string } | undefined
    let takes = 0
    for (let take = 1; take <= 1 + o.retakes; take++) {
      takes = take
      const pcm = await synthesize(o.ai(), chunk, o.voices)
      await writeFile(path.join(takeDir, `${String(chunk.index).padStart(2, "0")}-${chunk.hash}-t${take}.wav`), wav(pcm, RATE))
      let result: Check | undefined
      let error: string | undefined
      try {
        result = await check(o.ai(), chunk, pcm, o.listener)
      } catch (e) {
        error = String((e as Error).message ?? e)
      }
      const better = !best || (result && (!best.check || result.score < best.check.score))
      if (better) best = { pcm, check: result, error }
      const tag = result ? `${result.ok ? "ok" : "CHECK"} score ${result.score} (−${result.missing.length} +${result.extra.length}, turns ${result.turnsHeard}/${result.turnsExpected})` : `no review: ${error}`
      console.log(`  chunk ${chunk.index} take ${take}: ${secondsOf(pcm, RATE).toFixed(1)}s · ${tag}`)
      if (result?.ok) break
    }
    const file = `chunks/${String(chunk.index).padStart(2, "0")}-${chunk.hash}.wav`
    await writeFile(path.join(dir, file), wav(best!.pcm, RATE))
    manifest[chunk.index] = { hash: chunk.hash, file, duration: Math.round(secondsOf(best!.pcm, RATE) * 100) / 100, takes, check: best!.check, reviewError: best!.error }
    await save()
  })
  // Forget chunks that no longer exist in the script
  for (const key of Object.keys(manifest)) if (Number(key) > chunks.length) delete manifest[key]
  await save()

  // ------------------------------------------------ assemble
  const missingChunks = chunks.filter((c) => !manifest[c.index] || manifest[c.index].hash !== c.hash)
  if (missingChunks.length) {
    console.log(`not assembling: chunks ${missingChunks.map((c) => c.index).join(", ")} are not voiced for the current script`)
    return
  }
  const parts: Buffer[] = []
  const chapters: { title: string; at: number }[] = []
  let at = 0
  for (const chunk of chunks) {
    if (parts.length) {
      const gap = chunk.sectionStart ? o.sectionGap : o.gap
      parts.push(Buffer.alloc(Math.round(gap * RATE) * 2))
      at += gap
    }
    if (chunk.sectionStart) chapters.push({ title: chunk.section, at: at / o.tempo })
    const { pcm } = samplesOf(readFileSync(path.join(dir, manifest[chunk.index].file)))
    parts.push(pcm)
    at += secondsOf(pcm, RATE)
  }
  const episode = path.join(dir, "episode.mp3")
  const filters = [...(o.tempo !== 1 ? [`atempo=${o.tempo}`] : []), "loudnorm=I=-16:TP=-1.5:LRA=11", "aresample=48000"]
  await ffmpeg(["-y", "-f", "wav", "-i", "-", "-af", filters.join(","), "-ac", "1", "-c:a", "libmp3lame", "-b:a", "128k", episode], wav(Buffer.concat(parts), RATE))
  const length = at / o.tempo

  // ------------------------------------------------ report
  const rows = chunks.map((c) => {
    const e = manifest[c.index]
    const k = e.check
    const verdict = k ? (k.ok ? "ok" : "**CHECK**") : "no review"
    const notes = [
      k?.longestMissing && k.longestMissing.split(" ").length > 1 ? `missing: «${k.longestMissing}»` : "",
      k?.longestExtra && k.longestExtra.split(" ").length > 1 ? `extra: «${k.longestExtra}»` : "",
      ...(k?.issues ?? []),
      e.reviewError ?? "",
    ].filter(Boolean)
    return `| ${c.index} | ${c.section} | ${c.turns.length} | ${e.duration.toFixed(1)}s | ${e.takes} | ${verdict} | ${k ? `−${k.missing.length} +${k.extra.length}` : ""} | ${k?.natural ?? ""} | ${notes.join("<br>").replace(/\|/g, "/")} |`
  })
  const report = [
    `# ${path.basename(dir)}`,
    "",
    `- Length: **${fmt(length)}** (${turns.length} turns, ${totalWords} words, ${(totalWords / length * 60).toFixed(0)} words/min${o.tempo !== 1 ? `, tempo ×${o.tempo}` : ""})`,
    `- Voices: ${Object.entries(o.voices).map(([s, v]) => `${s} = ${v}`).join(", ")} · model ${TTS_MODEL}, checked by ${LISTEN_MODEL}`,
    `- Chunks: ${chunks.length}, passing ${chunks.filter((c) => manifest[c.index].check?.ok).length}`,
    "",
    "## Chapters",
    "",
    ...chapters.map((c) => `- ${fmt(c.at)} ${c.title}`),
    "",
    "## Chunks",
    "",
    "Word diff is against the script, from a blind transcription (the listener never saw the text). −missing +extra counts include spelling noise; runs of 2+ words are listed.",
    "",
    "| # | Section | Turns | Length | Takes | Verdict | Words | Natural | Notes |",
    "|---|---|---|---|---|---|---|---|---|",
    ...rows,
    "",
  ].join("\n")
  await writeFile(path.join(dir, "report.md"), report)
  if (o.lexicon) await copyFile(o.lexicon, path.join(dir, "lexicon-used.tsv")).catch(() => undefined)
  console.log(`\n${path.relative(process.cwd(), episode)} · ${fmt(length)} · ${chunks.filter((c) => manifest[c.index].check?.ok).length}/${chunks.length} chunks pass`)
  console.log(`report: ${path.relative(process.cwd(), path.join(dir, "report.md"))}`)
}
