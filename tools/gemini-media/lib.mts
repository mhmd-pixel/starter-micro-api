/**
 * Shared pieces for the gemini-media CLI: credentials, the Vertex client,
 * retries, WAV handling, ffmpeg and turning files into request parts.
 *
 * Credentials are never printed. Checks report booleans and counts only.
 */
import { spawn } from "node:child_process"
import { existsSync, readFileSync, statSync } from "node:fs"
import { mkdir } from "node:fs/promises"
import os from "node:os"
import path from "node:path"
import { createRequire } from "node:module"
import { GoogleGenAI, type GoogleGenAIOptions, type Part } from "@google/genai"

const ffmpegStatic = createRequire(import.meta.url)("ffmpeg-static") as string | null

export const TTS_MODEL = process.env.GM_TTS_MODEL || "gemini-3.8-flash-tts"
export const LISTEN_MODEL = process.env.GM_LISTEN_MODEL || "gemini-4-argon"
export const DEFAULT_VOICE = "en-au-tutor-4"
export const ROOT = path.resolve(import.meta.dirname, "../..")
export const OUT = path.join(ROOT, "out")
export const DEFAULT_ENV_FILE = path.join(os.homedir(), ".config/gemini-media/vertex.env")
/** Largest file sent inline before it is compressed first (raw bytes; base64 adds a third) */
export const INLINE_LIMIT = Number(process.env.GM_INLINE_MB || 18) * 1024 * 1024

const KEYS = ["GOOGLE_CLOUD_PROJECT", "GOOGLE_CLIENT_EMAIL", "GOOGLE_PRIVATE_KEY", "GOOGLE_CLOUD_LOCATION", "GOOGLE_APPLICATION_CREDENTIALS"] as const
type Creds = Partial<Record<(typeof KEYS)[number], string>>

// ---------------------------------------------------------------- arguments

export interface Args {
  positional: string[]
  options: Record<string, string>
  flags: Set<string>
}

/** `--name value` options, bare `--flag`s (listed in `flagNames`) and positionals */
export function parseArgs(argv: string[], flagNames: string[]): Args {
  const args: Args = { positional: [], options: {}, flags: new Set() }
  for (let i = 0; i < argv.length; i++) {
    const item = argv[i]
    if (!item.startsWith("--") || item === "--") {
      if (item !== "--") args.positional.push(item)
      continue
    }
    const [name, inline] = item.slice(2).split(/=(.*)/s)
    if (inline !== undefined) args.options[name] = inline
    else if (flagNames.includes(name)) args.flags.add(name)
    else args.options[name] = argv[++i] ?? ""
  }
  return args
}

// ---------------------------------------------------------------- credentials

/** Reads KEY=value files, even when lines have been run together without newlines */
function parseEnvFile(text: string): Creds {
  const pattern = new RegExp(`(${KEYS.join("|")})=`, "g")
  const hits = [...text.matchAll(pattern)]
  const creds: Creds = {}
  hits.forEach((hit, index) => {
    const end = index + 1 < hits.length ? hits[index + 1].index : text.length
    const raw = text.slice(hit.index + hit[0].length, end).trim()
    creds[hit[1] as keyof Creds] = raw.replace(/^(["'])([\s\S]*)\1$/, "$2").trim()
  })
  return creds
}

export function loadCreds(envFile?: string): { creds: Creds; source: string } {
  const file = envFile || process.env.GEMINI_MEDIA_ENV || process.env.VOICE_ENV_FILE || (existsSync(DEFAULT_ENV_FILE) ? DEFAULT_ENV_FILE : "")
  if (file) {
    if (!existsSync(file)) throw new Error(`Env file not found: ${file}`)
    return { creds: parseEnvFile(readFileSync(file, "utf8")), source: file }
  }
  const creds: Creds = {}
  for (const key of KEYS) if (process.env[key]) creds[key] = process.env[key]
  return { creds, source: "environment variables" }
}

export function client(envFile?: string) {
  // An API key would point at a project without credits, and the SDK warns about it
  delete process.env.GEMINI_API_KEY
  delete process.env.GOOGLE_API_KEY
  const { creds, source } = loadCreds(envFile)
  const keyFile = creds.GOOGLE_APPLICATION_CREDENTIALS
  let project = creds.GOOGLE_CLOUD_PROJECT
  if (!project && keyFile && existsSync(keyFile)) project = JSON.parse(readFileSync(keyFile, "utf8")).project_id
  if (!project) throw new Error(`No GOOGLE_CLOUD_PROJECT in ${source}`)
  let googleAuthOptions: GoogleGenAIOptions["googleAuthOptions"]
  if (creds.GOOGLE_CLIENT_EMAIL && creds.GOOGLE_PRIVATE_KEY) {
    googleAuthOptions = {
      projectId: project,
      credentials: { client_email: creds.GOOGLE_CLIENT_EMAIL, private_key: creds.GOOGLE_PRIVATE_KEY.split("\\n").join("\n") },
    }
  } else if (keyFile) {
    googleAuthOptions = { projectId: project, keyFile }
  } else {
    throw new Error(`No service account in ${source}: need GOOGLE_CLIENT_EMAIL + GOOGLE_PRIVATE_KEY, or GOOGLE_APPLICATION_CREDENTIALS`)
  }
  silenceKeyWarning()
  return new GoogleGenAI({ vertexai: true, project, location: creds.GOOGLE_CLOUD_LOCATION || "global", googleAuthOptions })
}

/** The SDK always notes that explicit credentials beat an API key, even when no key is set */
function silenceKeyWarning() {
  const debug = console.debug
  console.debug = (...args: unknown[]) => {
    if (!String(args[0]).includes("take precedence")) debug(...args)
  }
}

// ---------------------------------------------------------------- calls

function statusOf(error: unknown): number | undefined {
  const e = error as { status?: number; code?: number }
  return e?.status ?? e?.code
}

/** Retries transient failures with backoff; a bad request fails at once */
export async function retry<T>(run: () => Promise<T>, attempts = 5, label = "request"): Promise<T> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await run()
    } catch (error) {
      const status = statusOf(error)
      const message = String((error as Error)?.message ?? error)
      if (/Error 403 \(Forbidden\)!!1/.test(message)) throw new Error("Google returned an HTML 403 page: the network is blocking Google, not a permissions problem")
      const permanent = status !== undefined && [400, 401, 403, 404].includes(status)
      if (permanent || attempt >= attempts) throw error
      // Quota errors need real waiting; other transient errors a short backoff
      const quota = status === 429 || /RESOURCE_EXHAUSTED|"code":\s*429/.test(message)
      const wait = quota ? Math.min(90000, 15000 * 2 ** (attempt - 1)) * (0.75 + Math.random() / 2) : 1500 * 2 ** (attempt - 1)
      process.stderr.write(`  ${label} failed (${quota ? "rate limited" : (status ?? message.slice(0, 80))}), retrying in ${Math.round(wait / 1000)}s (${attempt}/${attempts - 1})…\n`)
      await new Promise((resolve) => setTimeout(resolve, wait))
    }
  }
}

/** Pulls the first JSON object or array out of a model answer */
export function parseJson<T = unknown>(text: string | undefined): T {
  const body = (text ?? "").trim().replace(/^```(?:json)?\s*|\s*```$/g, "")
  try {
    return JSON.parse(body) as T
  } catch {
    const match = body.match(/[[{][\s\S]*[\]}]/)
    if (!match) throw new Error("No JSON in answer")
    return JSON.parse(match[0]) as T
  }
}

// ---------------------------------------------------------------- audio

/** The TTS model answers with a whole WAV (metadata chunks after the samples) or bare PCM */
export function samplesOf(data: Buffer) {
  if (data.subarray(0, 4).toString() !== "RIFF") return { pcm: data, rate: 24000 }
  let rate = 24000
  let pcm: Buffer = Buffer.alloc(0)
  for (let at = 12; at + 8 <= data.length; ) {
    const id = data.subarray(at, at + 4).toString()
    const size = Math.min(data.readUInt32LE(at + 4), data.length - at - 8)
    if (id === "fmt ") rate = data.readUInt32LE(at + 12)
    if (id === "data") pcm = data.subarray(at + 8, at + 8 + size)
    at += 8 + size + (size % 2)
  }
  return { pcm, rate }
}

export function wav(pcm: Buffer, rate: number) {
  const header = Buffer.alloc(44)
  header.write("RIFF", 0)
  header.writeUInt32LE(36 + pcm.length, 4)
  header.write("WAVEfmt ", 8)
  header.writeUInt32LE(16, 16)
  header.writeUInt16LE(1, 20)
  header.writeUInt16LE(1, 22)
  header.writeUInt32LE(rate, 24)
  header.writeUInt32LE(rate * 2, 28)
  header.writeUInt16LE(2, 32)
  header.writeUInt16LE(16, 34)
  header.write("data", 36)
  header.writeUInt32LE(pcm.length, 40)
  return Buffer.concat([header, pcm])
}

/** Cuts silence below 1.2% of full scale, keeping 40 ms before and 120 ms after the words */
export function trim(pcm: Buffer, rate: number) {
  const samples = Math.floor(pcm.length / 2)
  const loud = (index: number) => Math.abs(pcm.readInt16LE(index * 2)) > 32768 * 0.012
  let start = 0
  while (start < samples && !loud(start)) start++
  if (start >= samples) return pcm
  let end = samples - 1
  while (end > start && !loud(end)) end--
  const pad = Math.round(rate * 0.04)
  return pcm.subarray(Math.max(0, start - pad) * 2, Math.min(samples, end + pad * 3) * 2)
}

export const secondsOf = (pcm: Buffer, rate: number) => pcm.length / 2 / rate

// ---------------------------------------------------------------- ffmpeg

export const FFMPEG = ffmpegStatic || "ffmpeg"

/** Runs ffmpeg, feeding `input` on stdin when given, and returns stdout */
export function ffmpeg(args: string[], input?: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const child = spawn(FFMPEG, ["-hide_banner", "-loglevel", "error", ...args], { stdio: ["pipe", "pipe", "pipe"] })
    const out: Buffer[] = []
    let log = ""
    child.stdout.on("data", (chunk: Buffer) => out.push(chunk))
    child.stderr.on("data", (chunk: Buffer) => (log += chunk))
    child.stdin.on("error", () => undefined)
    child.on("error", reject)
    child.on("close", (code) => (code === 0 ? resolve(Buffer.concat(out)) : reject(new Error(`ffmpeg failed: ${log.trim()}`))))
    child.stdin.end(input)
  })
}

/** Media length in seconds, read from ffmpeg's own probe output */
export async function durationOf(file: string): Promise<number> {
  const log = await new Promise<string>((resolve) => {
    const child = spawn(FFMPEG, ["-hide_banner", "-i", file], { stdio: ["ignore", "ignore", "pipe"] })
    let text = ""
    child.stderr.on("data", (chunk: Buffer) => (text += chunk))
    child.on("close", () => resolve(text))
  })
  const match = log.match(/Duration: (\d+):(\d+):([\d.]+)/)
  return match ? Number(match[1]) * 3600 + Number(match[2]) * 60 + Number(match[3]) : NaN
}

export async function writeAudio(pcm: Buffer, rate: number, file: string) {
  await mkdir(path.dirname(file), { recursive: true })
  const ext = path.extname(file).toLowerCase()
  if (ext === ".wav") return ffmpeg(["-y", "-f", "wav", "-i", "-", "-c:a", "pcm_s16le", file], wav(pcm, rate))
  const codec = ext === ".mp3" ? ["-c:a", "libmp3lame", "-b:a", "96k", "-ar", "48000"] : []
  return ffmpeg(["-y", "-i", "-", "-ac", "1", ...codec, file], wav(pcm, rate))
}

// ---------------------------------------------------------------- media parts

const MIME: Record<string, string> = {
  ".wav": "audio/wav", ".mp3": "audio/mp3", ".flac": "audio/flac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
  ".aac": "audio/aac", ".m4a": "audio/mp4", ".aiff": "audio/aiff", ".aif": "audio/aiff",
  ".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm", ".mkv": "video/x-matroska",
  ".avi": "video/x-msvideo", ".mpeg": "video/mpeg", ".mpg": "video/mpeg", ".m4v": "video/mp4", ".3gp": "video/3gpp", ".flv": "video/x-flv",
  ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".webp": "image/webp", ".heic": "image/heic", ".gif": "image/gif",
  ".pdf": "application/pdf", ".txt": "text/plain", ".md": "text/plain", ".csv": "text/csv", ".json": "application/json", ".html": "text/html",
}

export const kindOf = (file: string) => (MIME[path.extname(file).toLowerCase()] ?? "application/octet-stream").split("/")[0]

export interface Clip {
  start?: number
  end?: number
}

const clipArgs = ({ start, end }: Clip) => [...(start !== undefined ? ["-ss", String(start)] : []), ...(end !== undefined ? ["-to", String(end)] : [])]

/**
 * Turns a path or URL into a request part. Remote URLs (gs://, YouTube, https)
 * go by reference. Local audio and video are cut to `clip` and compressed
 * until they fit inline.
 */
export async function partOf(source: string, clip: Clip = {}): Promise<{ part: Part; note: string }> {
  if (/^(gs|https?):\/\//.test(source)) {
    const youtube = /youtu\.?be/.test(source)
    const mimeType = youtube ? "video/*" : (MIME[path.extname(new URL(source).pathname).toLowerCase()] ?? "video/*")
    return { part: { fileData: { fileUri: source, mimeType } }, note: `${source} (by reference)` }
  }
  if (!existsSync(source)) throw new Error(`File not found: ${source}`)
  const ext = path.extname(source).toLowerCase()
  const size = statSync(source).size
  let mimeType = MIME[ext]
  const kind = kindOf(source)
  const cut = clip.start !== undefined || clip.end !== undefined
  if ((kind === "audio" || kind === "video") && (cut || size > INLINE_LIMIT || !mimeType)) {
    const { data, mime, label } = kind === "video" ? await shrinkVideo(source, clip) : await shrinkAudio(source, clip)
    return { part: { inlineData: { mimeType: mime, data: data.toString("base64") } }, note: `${path.basename(source)} → ${label}, ${mb(data.length)}` }
  }
  if (!mimeType) mimeType = "text/plain"
  if (size > INLINE_LIMIT) throw new Error(`${source} is ${mb(size)}, over the ${mb(INLINE_LIMIT)} inline limit`)
  return { part: { inlineData: { mimeType, data: readFileSync(source).toString("base64") } }, note: `${path.basename(source)}, ${mb(size)}` }
}

const mb = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`

async function shrinkAudio(file: string, clip: Clip) {
  for (const [rate, bitrate] of [["44100", "128k"], ["24000", "64k"], ["16000", "32k"]]) {
    const data = await ffmpeg([...clipArgs(clip), "-i", file, "-vn", "-ac", "1", "-ar", rate, "-c:a", "libmp3lame", "-b:a", bitrate, "-f", "mp3", "-"])
    if (data.length <= INLINE_LIMIT) return { data, mime: "audio/mp3", label: `mp3 ${bitrate}` }
  }
  throw new Error(`${file} is still too large after compression; use --start/--end to send part of it`)
}

async function shrinkVideo(file: string, clip: Clip) {
  // The listener samples about one frame a second, so resolution and frame rate can drop a lot
  const steps = [
    { height: 720, fps: 30, crf: 28, audio: "96k" },
    { height: 480, fps: 15, crf: 32, audio: "64k" },
    { height: 360, fps: 5, crf: 36, audio: "48k" },
    { height: 240, fps: 2, crf: 40, audio: "32k" },
  ]
  const { mkdtemp, readFile, rm } = await import("node:fs/promises")
  const dir = await mkdtemp(path.join(os.tmpdir(), "gm-"))
  try {
    for (const step of steps) {
      const out = path.join(dir, `v${step.height}.mp4`)
      await ffmpeg([
        "-y", ...clipArgs(clip), "-i", file,
        "-vf", `scale=-2:'min(${step.height},ih)',fps='min(${step.fps},source_fps)'`,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", String(step.crf), "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-ac", "1", "-b:a", step.audio, "-movflags", "+faststart", out,
      ])
      const data = await readFile(out)
      if (data.length <= INLINE_LIMIT) return { data, mime: "video/mp4", label: `mp4 ${step.height}p ${step.fps}fps` }
    }
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
  throw new Error(`${file} is still too large after compression; use --start/--end to send part of it`)
}

// ---------------------------------------------------------------- text

export const slug = (text: string) =>
  text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "").slice(0, 40) || "clip"
