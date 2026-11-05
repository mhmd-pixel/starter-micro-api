# Podcast from a script: Gemini TTS + Argon on Vertex

A step-by-step playbook for an agent that has been handed a finished podcast script
(two or more hosts, any language, written and tested on colloquial Persian) and has to
deliver a finished, checked MP3. Everything here was done for real on a 100-line,
2,300-word Persian episode in Nov 2026. The companion file **`LESSONS.md`** holds the
details behind each step: the traps, the numbers and the edge cases. Read it once
before your first run.

**Where the code is:** the GitHub repo `mhmd-pixel/starter-micro-api`, branch `ccr-14c9c9d2-1wk7yn`
(or the default branch, if it has been merged). A session opened on it has everything below ready: the guides are
in `docs/`, the example in `projects/optocast-astigmatism/`. In the zip version they sit at the root and in `examples/`.

The kit:

| Path | What it is |
|---|---|
| `bin/gm` | The CLI. Installs dependencies on first run. `bin/gm --help` lists every command |
| `tools/gemini-media/cli.mts` | Commands: `speak`, `review`, `listen`, `watch`, `transcribe`, `ask`, `frames`, `voices`, `doctor`, `dialogue` |
| `tools/gemini-media/dialogue.mts` | The episode pipeline (`gm dialogue`): one TTS call per line, blind check, retakes, assembly, report |
| `tools/gemini-media/speech.mts` | Shared checks: Persian-aware text comparison, pitch, cut-off detection, levels |
| `tools/gemini-media/lib.mts` | Vertex client and credentials, retries, WAV parsing, ffmpeg, media upload |
| `bin/qc-episode.sh` | Whole-episode listen by Argon, in overlapping parts |
| `examples/optocast-astigmatism/` | A real script, lexicon, chapters and voicing report |
| `vertex.env.example` | The credentials file format |
| `.claude/skills/gemini-media/SKILL.md` | Short notes Claude Code loads automatically in this folder |

---

## 0. Get the credentials from the user first

Before anything else, **ask the user for the four Vertex values**. Nothing works without them:

| Variable | What it is |
|---|---|
| `GOOGLE_CLOUD_PROJECT` | GCP project id |
| `GOOGLE_CLIENT_EMAIL` | Service account email, `…@….iam.gserviceaccount.com` |
| `GOOGLE_PRIVATE_KEY` | The service account's PEM key, **one line with literal `\n`**, in double quotes |
| `GOOGLE_CLOUD_LOCATION` | `global` (always `global`) |

The service account needs only the **Vertex AI User** role (`roles/aiplatform.user`).
A key JSON file works too: `GOOGLE_APPLICATION_CREDENTIALS=/path/key.json` replaces the email and key.

Ask for them in this order of preference:
1. Set as environment variables in the agent's environment settings (they survive new sessions), or
2. A path to an env file they upload, or
3. Pasted into the chat, as a last resort. If they paste them, write them to the file below at once and don't repeat them.

Write them to `~/.config/gemini-media/vertex.env` with `chmod 600` (format: `vertex.env.example`).
The parser copes with lines that were run together when copied (`…comGOOGLE_PRIVATE_KEY=…`).

**Rules:** never print, `cat`, `echo` or commit a credential value, not even to check the format.
`bin/gm doctor` checks them with booleans and lengths only. Don't set `GEMINI_API_KEY` or `GOOGLE_API_KEY`.

```bash
bin/gm doctor        # credentials (booleans only) + one TTS call + one Argon call
```

---

## 1. Set up

Node 20.11+ and network access to `aiplatform.googleapis.com`. ffmpeg comes from npm (`ffmpeg-static`).

```bash
npm ci               # or just run bin/gm, which installs on first run
bin/gm doctor
```

---

## 2. Prepare the script

`gm dialogue` reads plain text:

```text
# Section title          ← starts a chapter (optional)
Sara: first line…
Kaveh: reply…
> a note line            ← ignored
```

- **One turn per line, `Name: text`.** Names must match `--voices`. Keep the user's words. The audio
  should follow their script, and any wording suggestions go back to them (step 8).
- **Sections:** if the source has segment headings, add them as `#` lines. They become chapters
  with timestamps and a slightly longer pause.
- **Stage tags** such as `[laughing]`, `[sigh]` and `[short pause]` are **dropped by default**. The model performs
  them, but listeners found them artificial and the episode is better without them. Pass `--keep-tags` only if asked.
- **Write numbers as words** in the script («منفی یک و بیست و پنج», «ده دهم»). Digits get read inconsistently.
- **Pronunciation goes in a lexicon, not the script.** `lexicon.tsv` holds `written<TAB>spoken` and is applied
  to speech only. Start with the brand name (`اپتوکست	اُپتوکست`). Add anything the checks find mispronounced
  *systematically*: the same error in every take. A one-off error just needs a retake.
- If the script has a separate "TTS-only" file and a full document, check that the speaker lines are
  identical. (In the example they were; the sectioned `script.txt` was built from the document and verified.)

---

## 3. Cast the voices

```bash
bin/gm voices --lang fa-IR      # there are none for Persian (Nov 2026); use the standard voices
bin/gm voices --lang all --search warm
```

Standard multilingual voices: Achernar, Achird, Algenib, Algieba, Alnilam, Aoede, Autonoe, Callirrhoe,
Charon, Despina, Enceladus, Erinome, Fenrir, Gacrux, Iapetus, Kore, Laomedeia, Leda, Orus, Puck,
Pulcherrima, Rasalgethi, Sadachbia, Sadaltager, Schedar, Sulafat, Umbriel, Vindemiatrix, Zephyr, Zubenelgenubi.

For a senior woman + younger man in Persian, **Sara = Sulafat, Kaveh = Orus** was rated best
(see LESSONS §4). For new hosts, audition: speak the same 2–3 lines with each candidate,
then have Argon rate each **clip on its own**:

```bash
for v in Sulafat Kore Leda Achernar; do bin/gm speak --file sample-sara.txt --voice $v --out out/aud/sara-$v.mp3; done
for f in out/aud/sara-*.mp3; do bin/gm listen "$f" 'Persian podcast host: senior female optometrist, warm, calm. Answer only JSON: {"tehrani_native":1-10,"natural":1-10,"warmth":1-10,"fits_role":1-10,"notes":""}' --json --save "${f%.mp3}.json"; done
```

Pick by native-ness, then role fit, and let the user decide if they care.
Auditioning **one voice per clip** matters: in multi-speaker clips Argon confuses who is who.

---

## 4. Test on one section, then run everything

```bash
P=projects/<name>
bin/gm dialogue $P/script.txt --voices Sara=Sulafat,Kaveh=Orus --lexicon $P/lexicon.tsv --out out/<name> --only 1,2,3,4
# listen to out/<name>/report.md + a spot check, then:
bin/gm dialogue $P/script.txt --voices Sara=Sulafat,Kaveh=Orus --lexicon $P/lexicon.tsv --out out/<name>
```

What it does per line:
1. One TTS call in that speaker's voice (plain text only).
2. A generous trim (1% threshold, 50 ms before, 250 ms after) and short fades.
3. A **blind** Argon transcription: Argon never sees the script.
4. A comparison on a **consonant skeleton**, so spelling variants pass but dropped or added words fail.
   Plus: the last word has to be heard, the audio must not end mid-sound, and the pitch has to match the
   speaker's other lines (catches a wrong voice).
5. Up to `--retakes` (default 3) new takes if it fails. The best take is kept.

Then it stitches the lines with conversational gaps, matches each line's speech level to its speaker's
median, normalises to −16 LUFS, and writes:

| File | Contents |
|---|---|
| `episode.mp3` | Mono 48 kHz, 128 kbps |
| `report.md` | Per-line verdicts, start times, pitch, the listener's notes |
| `chapters.txt` | `m:ss Section title` |
| `turns.json` | Manifest: hash, file, checks per line |
| `turns/`, `takes/` | Kept clips, and every take ever made |

It's cached by `model + voice + spoken text`, so a re-run only voices what changed.
It runs in the background: 100 lines take about 25 minutes at `--concurrency 4` (Argon rate limits set the pace).
Use `run_in_background` and a monitor that greps `turns voiced|error|not assembling|episode.mp3`.

Concurrency: 3–4. Above that, Argon returns 429 often. The tool backs off 15–90 s on 429.

---

## 5. Fix what the checks found

Read `report.md`. Line numbers count **speaker lines only** (headings and blank lines don't count):

```bash
grep -v '^#' $P/script.txt | grep -v '^$' | grep -n 'some words'     # → line number
```

1. **Lines marked CHECK:** `--redo-failed` re-voices every failing line.
2. **Listener notes worth acting on** (they pass the word check but are real): "cut-off word",
   "the last word is missing", "mispronounced «…»", "foreign accent on …", "glitch". Re-voice those with `--only 4,17,…`.
3. **Stubborn lines** (usually one- or two-word reactions like «دو؟», «و سه؟», «وای»; see LESSONS §3):
   make several takes, have each checked blind and ranked, and adopt the best:
   ```bash
   bin/gm speak "دو؟" --voice Orus --takes 4 --review --out out/<name>/picks/t31.wav
   bin/gm dialogue … --adopt 31=out/<name>/picks/t31-take-2.wav --assemble
   ```
   For one doubtful word, ask a **blind, targeted** question. Never tell Argon what the word should be:
   ```bash
   bin/gm ask 'Write the third word of this Persian line exactly as you hear it, sound by sound in Latin letters (e.g. b-a-r-g). Do not guess meaning. Answer JSON: {"word_latin": ""}' take.wav --json
   ```
4. **Ignore** notes that a laugh "sounds slightly acted", mood or delivery scores, and gap-length opinions.
   They drift between runs (LESSONS §5).

`--adopt` takes are marked "adopted by hand" and are not re-judged. Re-voicing those lines replaces them.

---

## 6. Check the whole episode

```bash
bin/qc-episode.sh out/<name>/episode.mp3            # ~4.5-minute parts, 5 s overlap
```

Trust: wrong voice, a word that's mispronounced or cut, glitches, a clear level jump.
Ignore: "cut off at 00:00 / at the end" (that's the part boundary), and gap opinions that
contradict between parts. Fix with `--only`/`--adopt`, then `--assemble` (assembly is fast and voices nothing).

The tool already does the safe mix fixes: per-line level matching (±6 dB), loudness, gaps
(0.30–0.42 s, a little less after a question, +0.35 s when the same host continues, 0.8 s between sections).
`--gap` and `--section-gap` change them.

---

## 7. Timing expectations

- Persian conversation voices at about **130–140 words per minute** including gaps: 2,331 words → 17:38.
  Scripts estimated at 150–170 wpm run ~20% long. The length is fine unless the user set a hard limit.
  Don't speed it up (`--tempo`) without asking.
- Wall clock for 100 lines: about 25 min voicing + 15–30 min of fixes and QC.

---

## 8. Deliver

- Send `episode.mp3` and `report.md` to the user. `out/` is not committed (gitignored, and too large),
  and a cloud container is reclaimed. Keep the script, lexicon, chapters and report in `projects/<name>/` and commit those.
- Tell them, briefly: length, voices, what was retaken or picked by hand, and known imperfections.
- **Script suggestions** that Argon raised (calques, unclear wording, register slips) go to the user as a
  short list. Don't change their script yourself.

---

## Command reference

```text
gm dialogue script.txt --voices A=Voice,B=Voice [--out dir] [--lexicon f.tsv] [--retakes 3]
           [--only 3,7] [--redo-failed] [--force] [--adopt 4=take.wav,…] [--assemble]
           [--keep-tags] [--gap 0.3] [--section-gap 0.8] [--tempo 1.0] [--concurrency 3] [--listener "…"]
gm speak "text" | --file f.txt  [--voice V] [--out f.mp3|.wav] [--takes N] [--review] [--keep-tags] [--no-trim]
gm review clip --text "intended"          blind transcription + Persian-aware diff + cut-off + pitch
gm listen audio ["question"] [--json] [--start s --end s]
gm watch video|YouTube URL ["question"]   (video with its sound)
gm transcribe audio|video
gm ask "prompt" [files…]                  images, audio, video, PDFs, text together
gm frames video [--count 12 | --every 2 | --at 1.5,4] [--sheet]   stills you can look at yourself
gm voices [--lang fa-IR|en-AU|all] [--female|--male] [--search word] [--json]
gm doctor
Common: --env <file>, --model <id>, --save <file>, --listener "who listens"
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `DECODER routines::unsupported` | The private key's `\n` were not literal; keep it one line with `\n` |
| HTML "Error 403 (Forbidden)!!1" on every call | The network is blocking Google (VPN, proxy), not a permissions problem |
| 404 on a model | Wrong id: use exactly `gemini-3.8-flash-tts` and `gemini-4-argon` (no `-preview`) |
| `400` with `systemInstruction` on TTS | Not supported: plain text only |
| Frequent 429 | Lower `--concurrency` to 3; the tool waits it out |
| A static burst at the end of each clip | You wrapped the returned WAV as PCM again; use `samplesOf()` |
| Stage directions or names spoken aloud | See LESSONS §2–3: plain text only, one call per line |
| Report says "not assembling" | Some lines aren't voiced for the current text; run without `--assemble` |
