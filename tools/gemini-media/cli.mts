/**
 * gemini-media: speech generation, listening and watching through Gemini on Vertex AI.
 *
 *   gm speak "text" [--voice en-au-tutor-4] [--out out/x.mp3] [--takes 3] [--review] [--lang en-AU] [--file script.txt]
 *   gm review <audio> --text "intended words"   blind transcription + word diff + delivery notes (JSON)
 *   gm listen <audio> ["question"]              any audio: music, speech, a mix (add --json for JSON)
 *   gm watch <video|YouTube URL> ["question"]   video with its sound; --start/--end to cut
 *   gm transcribe <audio|video>                 transcript with rough timestamps and speakers
 *   gm ask "prompt" [files…]                    any mix of images, audio, video, PDFs, text
 *   gm frames <video> [--every 2 | --count 12 | --at 1.5,4] [--sheet]   stills you can look at yourself
 *   gm voices [--lang en-AU] [--gender female] [--search podcast]
 *   gm doctor                                   checks credentials and both models
 *
 * Common: --env <file> (default ~/.config/gemini-media/vertex.env, then env vars),
 * --model <id>, --save <file> to keep the answer, --listener "who should listen".
 */
import { createHash } from "node:crypto"
import { existsSync, readFileSync } from "node:fs"
import { mkdir, writeFile } from "node:fs/promises"
import path from "node:path"
import { Modality, type Part } from "@google/genai"
import {
  DEFAULT_VOICE, LISTEN_MODEL, OUT, TTS_MODEL, client, diffWords, durationOf, ffmpeg, kindOf, loadCreds,
  parseArgs, parseJson, partOf, retry, samplesOf, secondsOf, slug, trim, writeAudio,
} from "./lib.mts"

const FLAGS = ["json", "review", "sheet", "no-trim", "raw", "help", "female", "male"]
const args = parseArgs(process.argv.slice(2), FLAGS)
const [command, ...rest] = args.positional
const opt = (name: string) => args.options[name]
const flag = (name: string) => args.flags.has(name)
const clip = { start: opt("start") ? Number(opt("start")) : undefined, end: opt("end") ? Number(opt("end")) : undefined }
const LISTENER = opt("listener") || "a native Australian English listener"
const rel = (file: string) => {
  const relative = path.relative(process.cwd(), path.resolve(file))
  return relative.startsWith("..") ? path.resolve(file) : relative || file
}

let ai: ReturnType<typeof client> | undefined
const gemini = () => (ai ??= client(opt("env")))

async function output(text: string) {
  console.log(text)
  if (opt("save")) {
    await mkdir(path.dirname(path.resolve(opt("save"))), { recursive: true })
    await writeFile(opt("save"), text.endsWith("\n") ? text : text + "\n")
    console.error(`saved ${rel(path.resolve(opt("save")))}`)
  }
}

/** One call to the listening model with a prompt and any media parts */
async function ask(prompt: string, parts: Part[], json = flag("json")) {
  const response = await retry(
    () =>
      gemini().models.generateContent({
        model: opt("model") || LISTEN_MODEL,
        contents: [{ role: "user", parts: [{ text: prompt }, ...parts] }],
        config: {
          ...(json ? { responseMimeType: "application/json" } : {}),
          ...(opt("temperature") ? { temperature: Number(opt("temperature")) } : {}),
        },
      }),
    5,
    "listen",
  )
  const text = response.text ?? ""
  if (!text.trim()) throw new Error(`The model returned no text (finish reason: ${response.candidates?.[0]?.finishReason ?? "unknown"})`)
  return text
}

async function mediaParts(sources: string[]) {
  const parts: Part[] = []
  for (const source of sources) {
    const { part, note } = await partOf(source, clip)
    console.error(`  sending ${note}`)
    parts.push(part)
  }
  return parts
}

// ---------------------------------------------------------------- speak

async function synthesize(text: string, voice: string) {
  return retry(
    async () => {
      const response = await gemini().models.generateContent({
        model: opt("model") || TTS_MODEL,
        contents: [{ role: "user", parts: [{ text }] }],
        config: {
          responseModalities: [Modality.AUDIO],
          speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } }, ...(opt("lang") ? { languageCode: opt("lang") } : {}) },
        },
      })
      const parts = response.candidates?.[0]?.content?.parts ?? []
      const clips = parts.filter((part) => part.inlineData?.data).map((part) => samplesOf(Buffer.from(part.inlineData!.data!, "base64")))
      if (clips.length === 0) throw new Error("The speech model returned no audio")
      const rate = clips[0].rate
      const pcm = Buffer.concat(clips.map((c) => c.pcm))
      return { pcm: flag("no-trim") ? pcm : trim(pcm, rate), rate }
    },
    5,
    "speak",
  )
}

async function speak() {
  const text = opt("file") ? readFileSync(opt("file"), "utf8").trim() : rest.join(" ").trim()
  if (!text) throw new Error('Nothing to say: gm speak "text" or --file script.txt')
  const voice = opt("voice") || DEFAULT_VOICE
  const takes = Math.max(1, Number(opt("takes") || 1))
  const hash = createHash("sha1").update(`${TTS_MODEL}|${voice}|${text}`).digest("hex").slice(0, 8)
  const base = opt("out") ? path.resolve(opt("out")) : path.join(OUT, "speech", `${slug(text)}-${voice}-${hash}.mp3`)
  const results = await Promise.all(
    Array.from({ length: takes }, async (_, index) => {
      const file = takes === 1 ? base : base.replace(/(\.[a-z0-9]+)?$/i, (ext) => `-take-${index + 1}${ext || ".mp3"}`)
      const { pcm, rate } = await synthesize(text, voice)
      await writeAudio(pcm, rate, file)
      return { file, seconds: Math.round(secondsOf(pcm, rate) * 100) / 100, pcm, rate }
    }),
  )
  for (const result of results) console.log(`${rel(result.file)} · ${result.seconds}s · ${voice}`)
  if (flag("review")) {
    console.log("")
    for (const result of results) {
      const verdict = await reviewSpeech(result.file, text).catch((error) => ({ error: String(error.message ?? error) }))
      console.log(`${path.basename(result.file)}: ${summary(verdict)}`)
    }
  }
}

// ---------------------------------------------------------------- review

interface Heard {
  heard: string
  natural: number
  energy: number
  issues: string[]
  accent?: string
}

/**
 * The listener is suggestible, so it transcribes without seeing the script,
 * and the words are compared here. Delivery notes come from the same blind pass.
 */
async function reviewSpeech(file: string, intended: string) {
  const parts = await mediaParts([file])
  const context = opt("context") ? ` Context: ${opt("context")}.` : ""
  const prompt =
    `You are ${LISTENER}, checking a voice-over clip before it goes into an ad.${context} ` +
    "Listen carefully. Do not assume or guess words: write exactly what is said, including slips, repeated or half-said words. " +
    'Answer only with JSON: {"heard": exact transcription, "natural": 1-10, "energy": 1-10 for an upbeat read, ' +
    '"accent": short description of the accent, ' +
    '"issues": [specific problems: mispronounced words (quote the word), wrong stress, clipped or cut-off endings, glitches, clicks, static, robotic artefacts, odd pauses or pacing; empty if none]}'
  const result = await retry(
    async () => {
      const heard = parseJson<Heard>(await ask(prompt, parts, true))
      if (typeof heard.heard !== "string" || !Array.isArray(heard.issues)) throw new Error("Unusable review")
      return heard
    },
    4,
    "review",
  )
  const diff = diffWords(intended, result.heard)
  return { file: rel(file), intended, ...result, ...diff, verdict: diff.matches && result.issues.length === 0 ? "ok" : "check" }
}

function summary(r: Awaited<ReturnType<typeof reviewSpeech>> | { error: string }) {
  if ("error" in r) return `no usable review (${r.error})`
  const lines = [`${r.verdict.toUpperCase()} · natural ${r.natural}/10 · energy ${r.energy}/10 · heard: ${r.heard}`]
  if (r.missing.length) lines.push(`    missing: ${r.missing.join(" ")}`)
  if (r.extra.length) lines.push(`    extra:   ${r.extra.join(" ")}`)
  for (const issue of r.issues) lines.push(`    - ${issue}`)
  return lines.join("\n")
}

async function review() {
  const file = rest[0]
  const intended = opt("text-file") ? readFileSync(opt("text-file"), "utf8").trim() : opt("text")
  if (!file || !intended) throw new Error('gm review <audio> --text "intended words"')
  const result = await reviewSpeech(file, intended)
  await output(flag("json") ? JSON.stringify(result, null, 2) : `${path.basename(file)}: ${summary(result)}`)
}

// ---------------------------------------------------------------- listen / watch / ask

async function listen() {
  const [file, ...question] = rest
  if (!file) throw new Error('gm listen <audio> ["question"]')
  const q = question.join(" ") ||
    "Describe this audio in detail. If there is speech: who speaks, accent, tone, pace, and a transcript. " +
      "If there is music: genre, instruments, tempo (BPM estimate), key/mood, structure, mix quality. " +
      "Note any technical problems (clipping, noise, distortion, level jumps, abrupt cuts)."
  await output(await ask(`Listen to the whole file carefully as ${LISTENER} and an experienced audio engineer. ${q}`, await mediaParts([file])))
}

async function watch() {
  const [source, ...question] = rest
  if (!source) throw new Error('gm watch <video|url> ["question"]')
  const length = existsSync(source) ? await durationOf(source) : NaN
  const span = Number.isNaN(length) ? "" : ` The clip sent runs ${(clip.end ?? length) - (clip.start ?? 0)} seconds${clip.start ? `, starting at ${clip.start}s of the original` : ""}.`
  const q = question.join(" ") ||
    "Describe what happens, scene by scene with approximate timestamps (mm:ss): visuals, on-screen text, camera moves and cuts, " +
      "and the audio (voice-over transcript, music, sound effects) and how well they line up. Then list any problems you notice."
  await output(await ask(`Watch and listen to this whole video carefully.${span} ${q}`, await mediaParts([source])))
}

async function transcribe() {
  const [source] = rest
  if (!source) throw new Error("gm transcribe <audio|video>")
  const prompt =
    `Transcribe every spoken word in this ${kindOf(source) === "video" ? "video" : "audio"} exactly, as ${LISTENER}. Do not tidy or guess. ` +
    "Start a new line at each change of speaker or every sentence or two, prefixed with [mm:ss] and the speaker (Speaker 1, Speaker 2… or a name if it is said). " +
    "Mark unclear words as [inaudible] and note music or sound effects in brackets."
  await output(await ask(prompt, await mediaParts([source])))
}

async function askCommand() {
  const [prompt, ...files] = rest
  if (!prompt) throw new Error('gm ask "prompt" [files…]')
  await output(await ask(prompt, await mediaParts(files)))
}

// ---------------------------------------------------------------- frames

async function frames() {
  const [file] = rest
  if (!file || !existsSync(file)) throw new Error("gm frames <video> [--every s | --count n | --at t1,t2] [--sheet]")
  const length = await durationOf(file)
  const dir = opt("out") ? path.resolve(opt("out")) : path.join(OUT, "frames", slug(path.basename(file, path.extname(file))))
  await mkdir(dir, { recursive: true })
  const width = opt("width") || "960"
  let times: number[]
  if (opt("at")) times = opt("at").split(",").map(Number)
  else {
    const every = opt("every") ? Number(opt("every")) : length / Number(opt("count") || 12)
    const from = clip.start ?? 0
    const to = clip.end ?? length
    times = []
    for (let t = from + every / 2; t < to; t += every) times.push(Math.round(t * 100) / 100)
  }
  const files: string[] = []
  for (const [index, t] of times.entries()) {
    const out = path.join(dir, `${String(index + 1).padStart(3, "0")}-${t.toFixed(2)}s.jpg`)
    await ffmpeg(["-y", "-ss", String(t), "-i", file, "-frames:v", "1", "-vf", `scale=${width}:-2`, "-q:v", "3", out])
    files.push(out)
  }
  console.log(`${files.length} frames from ${length.toFixed(1)}s in ${rel(dir)}`)
  for (const f of files) console.log(`  ${rel(f)}`)
  if (flag("sheet") && files.length) {
    const columns = Math.min(4, files.length)
    const rows = Math.ceil(files.length / columns)
    const sheet = path.join(dir, "sheet.jpg")
    // Feed the frames in order through the concat demuxer, which works everywhere
    const list = files.map((f) => `file '${f.replace(/'/g, "'\\''")}'`).join("\n")
    const listFile = path.join(dir, "frames.txt")
    await writeFile(listFile, list)
    await ffmpeg(["-y", "-f", "concat", "-safe", "0", "-i", listFile, "-vf", `scale=480:-2,tile=${columns}x${rows}:padding=4`, "-frames:v", "1", "-q:v", "3", sheet])
    console.log(`  contact sheet (left to right, top to bottom): ${rel(sheet)}`)
  }
}

// ---------------------------------------------------------------- voices

interface Voice {
  id: string
  display_name?: string
  language_code?: string
  accent?: string
  persona?: string
  gender?: string
  pitch?: string
  description?: string
  type?: string
}

async function voices() {
  const all: Voice[] = []
  let token: string | undefined
  do {
    const page = (await retry(() => gemini().voices.list({ page_size: 1000, ...(token ? { page_token: token } : {}) }))) as unknown as {
      voices?: Voice[]
      next_page_token?: string
    }
    all.push(...(page.voices ?? []))
    token = page.next_page_token || undefined
  } while (token)
  const lang = (opt("lang") ?? "en-AU").toLowerCase()
  const gender = opt("gender") || (flag("female") ? "female" : flag("male") ? "male" : "")
  const search = opt("search")?.toLowerCase()
  const list = all.filter(
    (v) =>
      v.type !== "VOICE_TYPE_PROMPTED" &&
      (lang === "all" || (v.language_code ?? "").toLowerCase() === lang) &&
      (!gender || v.gender === gender) &&
      (!search || JSON.stringify(v).toLowerCase().includes(search)),
  )
  if (flag("json")) return output(JSON.stringify(list, null, 2))
  const lines = list.map((v) => `${v.id.padEnd(24)} ${(v.gender ?? "").padEnd(7)} ${(v.pitch ?? "").replace("PITCH_", "").toLowerCase().padEnd(7)} ${v.accent ?? ""} · ${v.description ?? v.display_name ?? ""}`)
  await output(`${list.length} of ${all.length} voices\n${lines.join("\n")}`)
}

// ---------------------------------------------------------------- doctor

async function doctor() {
  const { creds, source } = loadCreds(opt("env"))
  const key = creds.GOOGLE_PRIVATE_KEY ?? ""
  console.log(`credentials from: ${source}`)
  console.log(`  project set: ${Boolean(creds.GOOGLE_CLOUD_PROJECT)}`)
  console.log(`  service account email set: ${Boolean(creds.GOOGLE_CLIENT_EMAIL)}, looks right: ${/\.iam\.gserviceaccount\.com$/.test(creds.GOOGLE_CLIENT_EMAIL ?? "")}`)
  console.log(`  private key set: ${Boolean(key)}, PEM markers: ${key.includes("BEGIN PRIVATE KEY") && key.includes("END PRIVATE KEY")}, length ${key.length}`)
  console.log(`  key file: ${creds.GOOGLE_APPLICATION_CREDENTIALS ? existsSync(creds.GOOGLE_APPLICATION_CREDENTIALS) : "not used"}`)
  const t0 = Date.now()
  const { pcm, rate } = await synthesize("Testing, one, two, three.", DEFAULT_VOICE)
  console.log(`  ${TTS_MODEL}: ok, ${secondsOf(pcm, rate).toFixed(2)}s of audio in ${Date.now() - t0} ms`)
  const t1 = Date.now()
  const text = await ask("Reply with the single word: ready", [], false)
  console.log(`  ${LISTEN_MODEL}: ${text.trim()} in ${Date.now() - t1} ms`)
}

// ---------------------------------------------------------------- main

const commands: Record<string, () => Promise<unknown>> = {
  speak, say: speak, tts: speak, review, listen, hear: listen, watch, video: watch, transcribe, ask: askCommand, frames, voices, doctor,
}

async function main() {
  if (!command || flag("help") || !commands[command]) {
    const usage = readFileSync(new URL(import.meta.url), "utf8").match(/\/\*\*([\s\S]*?)\*\//)![1].replace(/^ \* ?/gm, "")
    console.log(usage.trim())
    if (command && !commands[command] && !flag("help")) process.exitCode = 1
    return
  }
  await commands[command]()
}

main().catch((error) => {
  console.error(`error: ${error?.message ?? error}`)
  process.exit(1)
})

