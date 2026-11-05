/**
 * Language-aware checks shared by `gm speak --review` and `gm dialogue`:
 * normalising transcriptions (Persian and Latin), a word diff, a consonant-skeleton
 * comparison that ignores colloquial spelling, pitch, cut-off detection and levels.
 * PCM is 16-bit mono; the TTS model returns 24 kHz.
 */
export const RATE = 24000

// ---------------------------------------------------------------- persian-aware comparison

export function clean(text: string) {
  return text
    .replace(/\[[^\]]*\]|\([^)]*\)|（[^）]*）/g, " ") // [laughing], (خنده)
    .replace(/[ً-ٰٟـ]/g, "")
    .replace(/[يى]/g, "ی")
    .replace(/ك/g, "ک")
    .replace(/ة/g, "ه")
    .replace(/[أإآ]/g, "ا")
    .replace(/ؤ/g, "و")
    .replace(/[۰-۹]/g, (d) => String("۰۱۲۳۴۵۶۷۸۹".indexOf(d)))
    .replace(/[‌‍]/g, "")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .toLowerCase()
}

export const words = (text: string) => clean(text).split(/\s+/).filter(Boolean)

/** Consonants only, spaces and doubled letters collapsed: colloquial spellings meet here */
export const skeleton = (text: string) => clean(text).replace(/[\sاویهءئ]/g, "").replace(/(.)\1+/g, "$1")

export function lcsDiff<T>(a: T[], b: T[]) {
  const t = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1))
  for (let i = a.length - 1; i >= 0; i--) for (let j = b.length - 1; j >= 0; j--) t[i][j] = a[i] === b[j] ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1])
  const missing: T[] = []
  const extra: T[] = []
  let run = 0
  let longest = 0
  let i = 0
  let j = 0
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      run = 0
      i++, j++
      continue
    }
    if (j < b.length && (i >= a.length || t[i][j + 1] >= t[i + 1][j])) extra.push(b[j++])
    else missing.push(a[i++])
    longest = Math.max(longest, ++run)
  }
  return { missing, extra, longest }
}

// ---------------------------------------------------------------- pitch

/** Median F0 of voiced frames (autocorrelation on a 8 kHz copy) */
export function medianPitch(pcm: Buffer) {
  const step = 3
  const rate = RATE / step
  const n = Math.floor(pcm.length / 2 / step)
  const x = new Float32Array(n)
  for (let i = 0; i < n; i++) x[i] = pcm.readInt16LE(i * step * 2)
  const size = Math.round(rate * 0.04)
  const minLag = Math.floor(rate / 400)
  const maxLag = Math.ceil(rate / 70)
  const found: number[] = []
  for (let s = 0; s + size + maxLag < n; s += size) {
    let energy = 0
    for (let i = 0; i < size; i++) energy += x[s + i] * x[s + i]
    if (energy < size * 1200 * 1200) continue
    let best = 0
    let lag = 0
    for (let l = minLag; l <= maxLag; l++) {
      let c = 0
      for (let i = 0; i < size; i++) c += x[s + i] * x[s + i + l]
      if (c > best) (best = c), (lag = l)
    }
    if (lag && best > energy * 0.5) found.push(rate / lag)
  }
  found.sort((a, b) => a - b)
  return found.length ? Math.round(found[Math.floor(found.length / 2)]) : 0
}

/** Trims silence generously (quiet Persian endings) and fades the edges */
export function edge(pcm: Buffer) {
  const samples = Math.floor(pcm.length / 2)
  const loud = (i: number) => Math.abs(pcm.readInt16LE(i * 2)) > 32768 * 0.01
  let start = 0
  while (start < samples && !loud(start)) start++
  let end = samples - 1
  while (end > start && !loud(end)) end--
  const out = Buffer.from(pcm.subarray(Math.max(0, start - Math.round(RATE * 0.05)) * 2, Math.min(samples, end + Math.round(RATE * 0.25)) * 2))
  const n = out.length / 2
  const fadeIn = Math.min(n, Math.round(RATE * 0.01))
  const fadeOut = Math.min(n, Math.round(RATE * 0.08))
  for (let i = 0; i < fadeIn; i++) out.writeInt16LE(Math.round(out.readInt16LE(i * 2) * (i / fadeIn)), i * 2)
  for (let i = 0; i < fadeOut; i++) out.writeInt16LE(Math.round(out.readInt16LE((n - 1 - i) * 2) * (i / fadeOut)), (n - 1 - i) * 2)
  return out
}


/** Speech still loud in the last 120 ms means the model stopped mid-word */
export function abruptEnd(pcm: Buffer) {
  const n = Math.floor(pcm.length / 2)
  const rms = (from: number, to: number) => {
    let sum = 0
    for (let i = from; i < to; i++) sum += pcm.readInt16LE(i * 2) ** 2
    return Math.sqrt(sum / Math.max(1, to - from))
  }
  const tail = rms(Math.max(0, n - Math.round(RATE * 0.12)), n)
  const body = rms(0, n)
  return body > 0 && tail / body > 0.45
}

/** The script's last word has to be among the last words heard */
export function endsRight(text: string, heard: string) {
  const last = words(text).map(skeleton).filter(Boolean).at(-1)
  if (!last) return true
  const tail = words(heard).slice(-3).map(skeleton).join("")
  return tail.includes(last)
}

/** RMS of the louder (speech) frames only, so pauses and breaths do not count */
export function speechLevel(pcm: Buffer) {
  const n = Math.floor(pcm.length / 2)
  const size = Math.round(RATE * 0.03)
  const frames: number[] = []
  for (let s = 0; s + size <= n; s += size) {
    let sum = 0
    for (let i = s; i < s + size; i++) sum += pcm.readInt16LE(i * 2) ** 2
    frames.push(Math.sqrt(sum / size))
  }
  if (!frames.length) return 0
  const peak = Math.max(...frames)
  const loud = frames.filter((f) => f > peak * 0.2)
  return Math.sqrt(loud.reduce((a, f) => a + f * f, 0) / loud.length)
}

export function applyGain(pcm: Buffer, db: number) {
  if (Math.abs(db) < 0.2) return pcm
  const g = 10 ** (db / 20)
  const out = Buffer.alloc(pcm.length)
  for (let i = 0; i < pcm.length / 2; i++) out.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(pcm.readInt16LE(i * 2) * g))), i * 2)
  return out
}


/**
 * Compares a blind transcription with the intended text. A dropped or added word
 * leaves a run of 3+ differing consonants; spelling variants («سیلندرو / سیلندر رو»,
 * «یه کم / یکم») leave 1-2, so they pass.
 */
export function compareText(intended: string, heard: string) {
  const wordDiff = lcsDiff(words(intended), words(heard))
  const a = [...skeleton(intended)]
  const chars = lcsDiff(a, [...skeleton(heard)])
  const diff = chars.missing.length + chars.extra.length
  const why: string[] = []
  if (chars.longest >= 4) why.push(`run of ${chars.longest} consonants differs`)
  if (diff > Math.max(3, a.length * 0.12)) why.push(`${diff} of ${a.length} consonants differ`)
  if (!endsRight(intended, heard)) why.push("last word missing or cut")
  return { missing: wordDiff.missing, extra: wordDiff.extra, skeletonDiff: diff, longestRun: chars.longest, why }
}

/** Removes [laughing], [sigh], [short pause] and the like: performed tags tend to sound acted */
export const stripTags = (text: string) => text.replace(/\[[^\]]*\]\s*/g, "").replace(/\s{2,}/g, " ").trim()
