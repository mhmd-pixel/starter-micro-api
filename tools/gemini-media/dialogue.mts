/**
 * gm dialogue: voices a multi-host script turn by turn, checks every turn by ear
 * (Argon, blind) and by pitch, retakes failures, and assembles one normalised episode.
 *
 *   gm dialogue script.txt --voices Sara=Sulafat,Kaveh=Orus [--out out/episode]
 *       [--lexicon lexicon.tsv] [--retakes 3] [--only 12,40] [--force] [--assemble]
 *       [--gap 0.32] [--section-gap 1.0] [--tempo 1.0] [--listener "…"] [--concurrency 6]
 *
 * Script: one turn per line as `Name: text`. A line starting with `#` starts a new
 * section (a chapter); `>` lines and blank lines are ignored. Lexicon: `written<TAB>spoken`
 * per line, applied to the speech only (e.g. `اپتوکست	اُپتوکست`).
 *
 * Why turn by turn: Gemini TTS multi-speaker mode on Vertex (Nov 2026) does not keep
 * voices on their speakers. The first configured voice goes to whoever speaks first,
 * whatever the labels say, and real names used as labels are read aloud. One call per
 * turn with that speaker's voice is the only reliable mapping.
 *
 * Each turn is cached by a hash of model + voice + spoken text, so re-running voices
 * only what changed. Every take gets a blind transcription (the listener never sees the
 * script) compared on a consonant skeleton, which ignores colloquial spelling such as
 * «سیلندرو / سیلندر رو» or «یه کم / یکم» but catches dropped, added or swapped words,
 * plus a pitch check against the speaker's other turns to catch a wrong voice.
 */
import { createHash } from "node:crypto"
import { existsSync, readFileSync } from "node:fs"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { Modality, type GoogleGenAI } from "@google/genai"
import { LISTEN_MODEL, TTS_MODEL, ffmpeg, parseJson, retry, samplesOf, secondsOf, wav } from "./lib.mts"
import { RATE, abruptEnd, applyGain, compareText, edge, medianPitch, skeleton, speechLevel, stripTags } from "./speech.mts"

export interface DialogueOptions {
  ai: () => GoogleGenAI
  script: string
  voices: Record<string, string>
  out: string
  lexicon?: string
  retakes: number
  only?: number[]
  force: boolean
  keepTags: boolean
  redoFailed: boolean
  adopt?: Record<number, string>
  assembleOnly: boolean
  gap: number
  sectionGap: number
  tempo: number
  listener: string
  concurrency: number
}

interface Turn {
  index: number
  speaker: string
  text: string
  spoken: string
  section: string
  sectionStart: boolean
  hash: string
}

interface Check {
  heard: string
  natural: number
  issues: string[]
  missing: string[]
  extra: string[]
  skeletonDiff: number
  longestRun: number
  pitch: number
  score: number
  ok: boolean
  why: string[]
}

interface Entry {
  hash: string
  file: string
  duration: number
  takes: number
  check?: Check
  reviewError?: string
}


// ---------------------------------------------------------------- script

function loadLexicon(file?: string): [string, string][] {
  if (!file) return []
  return readFileSync(file, "utf8")
    .split(/\r?\n/)
    .filter((line) => line.trim() && !line.startsWith("#"))
    .map((line) => line.split("\t") as [string, string])
    .filter((pair) => pair.length === 2 && pair[0])
}

function parseScript(file: string, lexicon: [string, string][], voices: Record<string, string>, keepTags = false) {
  const turns: Turn[] = []
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
    const speaker = match[1].trim()
    const text = match[2].trim()
    if (!voices[speaker]) throw new Error(`No voice for speaker "${speaker}" (have ${Object.keys(voices).join(", ")})`)
    // Tags like [laughing] are performed, but sound acted; they are dropped unless --keep-tags
    const spoken = lexicon.reduce((t, [from, to]) => t.split(from).join(to), keepTags ? text : stripTags(text))
    const hash = createHash("sha1").update(`${TTS_MODEL}|${voices[speaker]}|${spoken}`).digest("hex").slice(0, 10)
    turns.push({ index: turns.length + 1, speaker, text, spoken, section, sectionStart, hash })
    sectionStart = false
  }
  return turns
}

export const wordCount = (text: string) => text.replace(/\[[^\]]*\]/g, " ").split(/\s+/).filter(Boolean).length

// ---------------------------------------------------------------- voice and check

async function synthesize(ai: GoogleGenAI, turn: Turn, voice: string) {
  return retry(
    async () => {
      const response = await ai.models.generateContent({
        model: TTS_MODEL,
        contents: [{ role: "user", parts: [{ text: turn.spoken }] }],
        config: { responseModalities: [Modality.AUDIO], speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } } },
      })
      const parts = response.candidates?.[0]?.content?.parts ?? []
      const clips = parts.filter((p) => p.inlineData?.data).map((p) => samplesOf(Buffer.from(p.inlineData!.data!, "base64")))
      if (!clips.length) throw new Error("no audio")
      if (clips[0].rate !== RATE) throw new Error(`unexpected sample rate ${clips[0].rate}`)
      return edge(Buffer.concat(clips.map((c) => c.pcm)))
    },
    7,
    `turn ${turn.index} speech`,
  )
}

async function listen(ai: GoogleGenAI, turn: Turn, pcm: Buffer, listener: string) {
  const audio = await ffmpeg(["-f", "wav", "-i", "-", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "96k", "-f", "mp3", "-"], wav(pcm, RATE))
  const prompt =
    `You are ${listener}, checking one line of a Persian podcast for optometrists before release. ` +
    "Transcribe exactly what is said, in Persian script, as spoken (colloquial forms as heard). Write numbers as words, and English " +
    "clinical terms in Persian script as pronounced. Do not tidy, correct or guess: include slips, repeats, and anything that sounds out of place. " +
    'Answer only with JSON: {"heard": "...", "natural": 1-10, "issues": [specific problems only: a mispronounced word (quote it), ' +
    "an English or foreign accent, glitches, static, clicks, a cut-off word, unnatural pauses, a laugh or sigh that sounds fake; empty if none]}"
  return retry(
    async () => {
      const response = await ai.models.generateContent({
        model: LISTEN_MODEL,
        contents: [{ role: "user", parts: [{ text: prompt }, { inlineData: { mimeType: "audio/mp3", data: audio.toString("base64") } }] }],
        config: { responseMimeType: "application/json" },
      })
      const r = parseJson<{ heard: string; natural: number; issues: string[] }>(response.text)
      if (typeof r.heard !== "string" || !Array.isArray(r.issues)) throw new Error("Unusable review")
      if (skeleton(turn.text).length >= 8 && skeleton(r.heard).length < skeleton(turn.text).length * 0.3) throw new Error("Review too short to be a transcription")
      return r
    },
    7,
    `turn ${turn.index} review`,
  )
}

function judge(turn: Turn, heard: { heard: string; natural: number; issues: string[] }, pitch: number, expectPitch: number | undefined, abrupt: boolean): Check {
  const c = compareText(turn.text, heard.heard)
  const why = [...c.why]
  if (abrupt) why.push("audio ends mid-sound (cut off)")
  if (expectPitch && pitch && Math.abs(Math.log2(pitch / expectPitch)) > 0.45) why.push(`pitch ${pitch} Hz vs speaker's usual ${expectPitch} Hz: wrong voice?`)
  const score = c.skeletonDiff + 3 * Math.max(0, c.longestRun - 2) + (why.some((w) => w.includes("cut")) ? 20 : 0) + (why.some((w) => w.startsWith("pitch")) ? 50 : 0)
  return {
    heard: heard.heard, natural: heard.natural, issues: heard.issues, missing: c.missing, extra: c.extra,
    skeletonDiff: c.skeletonDiff, longestRun: c.longestRun, pitch, score, ok: why.length === 0, why,
  }
}

// ---------------------------------------------------------------- run

async function pool<T>(items: T[], size: number, run: (item: T) => Promise<void>) {
  const queue = [...items]
  await Promise.all(Array.from({ length: Math.min(size, queue.length) }, async () => {
    while (queue.length) await run(queue.shift()!)
  }))
}

const fmt = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`
const pad = (n: number) => String(n).padStart(3, "0")

export async function dialogue(o: DialogueOptions) {
  const turns = parseScript(o.script, loadLexicon(o.lexicon), o.voices, o.keepTags)
  const dir = path.resolve(o.out)
  await mkdir(path.join(dir, "turns"), { recursive: true })
  await mkdir(path.join(dir, "takes"), { recursive: true })
  const manifestFile = path.join(dir, "turns.json")
  const manifest: Record<string, Entry> = existsSync(manifestFile) ? JSON.parse(readFileSync(manifestFile, "utf8")) : {}
  const save = () => writeFile(manifestFile, JSON.stringify(manifest, null, 1) + "\n")
  const totalWords = turns.reduce((n, t) => n + wordCount(t.text), 0)
  console.log(`${turns.length} turns, ${totalWords} words · ${Object.entries(o.voices).map(([s, v]) => `${s}=${v}`).join(", ")}`)

  // Pitch reference per voice: from turns already voiced and passing, else learnt as we go
  const pitches: Record<string, number[]> = {}
  const notePitch = (speaker: string, p: number) => p && (pitches[speaker] ??= []).push(p)
  const usual = (speaker: string) => {
    const list = [...(pitches[speaker] ?? [])].sort((a, b) => a - b)
    return list.length >= 3 ? list[Math.floor(list.length / 2)] : undefined
  }
  for (const t of turns) {
    const e = manifest[t.index]
    if (e?.hash === t.hash && e.check?.ok) notePitch(t.speaker, e.check.pitch)
  }

  // Apply the current rules to stored reviews, so a stricter check finds old misses
  for (const t of turns) {
    const e = manifest[t.index]
    if (e?.hash !== t.hash || !e.check || e.check.heard === "(adopted by hand)" || !existsSync(path.join(dir, e.file))) continue
    const { pcm } = samplesOf(readFileSync(path.join(dir, e.file)))
    const again = judge(t, { heard: e.check.heard, natural: e.check.natural, issues: e.check.issues }, e.check.pitch, usual(t.speaker), abruptEnd(pcm))
    if (e.check.ok && !again.ok) console.log(`  turn ${t.index} now fails: ${again.why.join("; ")}`)
    e.check = again
  }

  // Hand-picked takes replace a turn's clip (same trimming), recorded as checked by hand
  for (const [index, file] of Object.entries(o.adopt ?? {})) {
    const turn = turns[Number(index) - 1]
    if (!turn) throw new Error(`--adopt: no turn ${index}`)
    let { pcm, rate } = samplesOf(readFileSync(file))
    if (rate !== RATE) pcm = samplesOf(await ffmpeg(["-i", file, "-ac", "1", "-ar", String(RATE), "-f", "wav", "-"])).pcm
    pcm = edge(pcm)
    const target = `turns/${pad(turn.index)}-${turn.hash}.wav`
    await writeFile(path.join(dir, target), wav(pcm, RATE))
    const old = manifest[turn.index]?.check
    manifest[turn.index] = {
      hash: turn.hash, file: target, duration: Math.round(secondsOf(pcm, RATE) * 100) / 100, takes: (manifest[turn.index]?.takes ?? 0) + 1,
      check: { heard: "(adopted by hand)", natural: 0, issues: [`adopted ${path.basename(file)} after a targeted listen`], missing: [], extra: [], skeletonDiff: 0, longestRun: 0, pitch: medianPitch(pcm), score: 0, ok: true, why: [] },
    }
    void old
    console.log(`  turn ${turn.index}: adopted ${path.basename(file)}`)
  }
  await save()

  const todo = turns.filter((t) => {
    if (o.assembleOnly) return false
    if (o.only) return o.only.includes(t.index)
    const e = manifest[t.index]
    return o.force || !e || e.hash !== t.hash || !e.check || (o.redoFailed && !e.check.ok) || !existsSync(path.join(dir, e.file))
  })
  if (todo.length) console.log(`voicing ${todo.length} turn(s)`)

  await pool(todo, o.concurrency, async (turn) => {
    const voice = o.voices[turn.speaker]
    let best: { pcm: Buffer; check?: Check; error?: string } | undefined
    let takes = 0
    for (let take = 1; take <= 1 + o.retakes; take++) {
      takes = take
      const pcm = await synthesize(o.ai(), turn, voice)
      await writeFile(path.join(dir, "takes", `${pad(turn.index)}-${turn.hash}-t${take}.wav`), wav(pcm, RATE))
      const pitch = medianPitch(pcm)
      let result: Check | undefined
      let error: string | undefined
      try {
        result = judge(turn, await listen(o.ai(), turn, pcm, o.listener), pitch, usual(turn.speaker), abruptEnd(pcm))
      } catch (e) {
        error = String((e as Error).message ?? e)
      }
      if (!best || (result && (!best.check || result.score < best.check.score))) best = { pcm, check: result, error }
      if (result?.ok) {
        notePitch(turn.speaker, pitch)
        break
      }
      console.log(`  turn ${turn.index} (${turn.speaker}) take ${take}: ${result ? result.why.join("; ") : `no review: ${error}`}${result ? ` · heard «${result.heard.slice(0, 80)}»` : ""}`)
    }
    const file = `turns/${pad(turn.index)}-${turn.hash}.wav`
    await writeFile(path.join(dir, file), wav(best!.pcm, RATE))
    manifest[turn.index] = { hash: turn.hash, file, duration: Math.round(secondsOf(best!.pcm, RATE) * 100) / 100, takes, check: best!.check, reviewError: best!.error }
    const done = Object.keys(manifest).length
    if (done % 10 === 0) console.log(`  … ${done}/${turns.length} turns voiced`)
    await save()
  })
  for (const key of Object.keys(manifest)) if (Number(key) > turns.length) delete manifest[key]
  // Re-judge pitch now that every speaker has a full reference
  for (const t of turns) {
    const e = manifest[t.index]
    const ref = usual(t.speaker)
    if (e?.check && e.check.heard !== "(adopted by hand)" && ref && Math.abs(Math.log2(e.check.pitch / ref)) > 0.45 && !e.check.why.some((w) => w.startsWith("pitch"))) {
      e.check.why.push(`pitch ${e.check.pitch} Hz vs speaker's usual ${ref} Hz: wrong voice?`)
      e.check.ok = false
    }
  }
  await save()

  // ------------------------------------------------ assemble
  const unvoiced = turns.filter((t) => manifest[t.index]?.hash !== t.hash)
  if (unvoiced.length) return console.log(`not assembling: turns ${unvoiced.map((t) => t.index).join(", ")} are not voiced for the current script`)
  // Turns are voiced separately, so their levels drift: match each turn's speech level
  // to its speaker's median (gain limited to ±6 dB) before the overall loudness pass
  const clips = turns.map((t) => samplesOf(readFileSync(path.join(dir, manifest[t.index].file))).pcm)
  const levels = clips.map(speechLevel)
  const median = (list: number[]) => [...list].sort((a, b) => a - b)[Math.floor(list.length / 2)]
  const target: Record<string, number> = {}
  for (const speaker of Object.keys(o.voices)) target[speaker] = median(turns.map((t, i) => (t.speaker === speaker ? levels[i] : NaN)).filter((v) => v > 0))
  const parts: Buffer[] = []
  const chapters: { title: string; at: number }[] = []
  const starts: number[] = []
  let at = 0
  for (const [i, turn] of turns.entries()) {
    if (i > 0) {
      // Conversational gaps: a little variation, quicker after a question, longer between sections
      const jitter = ((turn.index * 7919) % 100) / 100
      let gap = turn.sectionStart ? o.sectionGap : o.gap + 0.12 * jitter
      if (!turn.sectionStart && /[؟?]\s*$/.test(turns[i - 1].text)) gap -= 0.07
      if (!turn.sectionStart && turns[i - 1].speaker === turn.speaker) gap += 0.35
      parts.push(Buffer.alloc(Math.round(Math.max(0.12, gap) * RATE) * 2))
      at += Math.max(0.12, gap)
    }
    if (turn.sectionStart) chapters.push({ title: turn.section, at: at / o.tempo })
    starts.push(at / o.tempo)
    const gainDb = levels[i] > 0 ? Math.max(-6, Math.min(6, 20 * Math.log10(target[turn.speaker] / levels[i]))) : 0
    const pcm = applyGain(clips[i], gainDb)
    parts.push(pcm)
    at += secondsOf(pcm, RATE)
  }
  const episode = path.join(dir, "episode.mp3")
  const filters = [...(o.tempo !== 1 ? [`atempo=${o.tempo}`] : []), "loudnorm=I=-16:TP=-1.5:LRA=11", "aresample=48000"]
  await ffmpeg(["-y", "-f", "wav", "-i", "-", "-af", filters.join(","), "-ac", "1", "-c:a", "libmp3lame", "-b:a", "128k", episode], wav(Buffer.concat(parts), RATE))
  const length = at / o.tempo

  // ------------------------------------------------ report
  const passing = turns.filter((t) => manifest[t.index].check?.ok).length
  const rows = turns.map((t, i) => {
    const e = manifest[t.index]
    const k = e.check
    const verdict = k ? (k.ok ? "ok" : "**CHECK**") : "no review"
    const notes = [...(k?.why ?? []), ...(k?.issues ?? []), e.reviewError ?? ""].filter(Boolean)
    const heard = k && !k.ok ? `<br>heard: «${k.heard}»` : ""
    return `| ${t.index} | ${fmt(starts[i])} | ${t.speaker} | ${e.duration.toFixed(1)}s | ${e.takes} | ${verdict} | ${k?.natural ?? ""} | ${k?.pitch ?? ""} | ${(notes.join("<br>") + heard).replace(/\|/g, "/")} |`
  })
  const report = [
    `# ${path.basename(dir)}`,
    "",
    `- Length **${fmt(length)}** · ${turns.length} turns · ${totalWords} words · ${((totalWords / length) * 60).toFixed(0)} words/min${o.tempo !== 1 ? ` · tempo ×${o.tempo}` : ""}`,
    `- Voices: ${Object.entries(o.voices).map(([s, v]) => `${s} = ${v}`).join(", ")} · ${TTS_MODEL}, one call per turn · checked blind by ${LISTEN_MODEL} + pitch`,
    `- Turns passing: **${passing}/${turns.length}**`,
    "",
    "## Chapters",
    "",
    ...chapters.map((c) => `- ${fmt(c.at)} ${c.title}`),
    "",
    "## Turns",
    "",
    "Verdict compares a blind transcription with the script on a consonant skeleton (spelling variants pass, dropped or added words fail) and the turn's pitch with the speaker's usual pitch. Notes after the verdict reasons are the listener's own remarks; they vary between runs.",
    "",
    "| # | At | Speaker | Length | Takes | Verdict | Natural | Pitch | Notes |",
    "|---|---|---|---|---|---|---|---|---|",
    ...rows,
    "",
  ].join("\n")
  await writeFile(path.join(dir, "report.md"), report)
  await writeFile(path.join(dir, "chapters.txt"), chapters.map((c) => `${fmt(c.at)} ${c.title}`).join("\n") + "\n")
  console.log(`\n${path.relative(process.cwd(), episode)} · ${fmt(length)} · ${passing}/${turns.length} turns pass`)
  console.log(`report: ${path.relative(process.cwd(), path.join(dir, "report.md"))}`)
}
