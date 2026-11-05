---
name: gemini-media
description: Generate speech (Gemini 3.8 Flash TTS) and have audio, music or video heard and watched for you (Gemini 4 Argon) on Vertex AI, through the repo's `bin/gm` CLI. Use whenever a task needs a voice-over or spoken audio made, an audio file listened to (speech, music, a mix), a video watched or summarised, a transcript, a check that a clip says what the script says, or stills pulled from a video so you can look at them yourself.
---

# gemini-media (`bin/gm`)

You cannot hear audio or watch video. This tool can: Argon listens to audio and
watches video (with its sound track) natively, and the TTS model speaks text.
Run everything from the repo root through `bin/gm`. It installs dependencies
on first run.

## Credentials (check first in a new container)

`bin/gm doctor` prints booleans only and makes one call to each model. Credentials are read from:

1. `--env <file>`, or `GEMINI_MEDIA_ENV`
2. `~/.config/gemini-media/vertex.env` (chmod 600, **outside the repo**)
3. environment variables `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLIENT_EMAIL`, `GOOGLE_PRIVATE_KEY`
   (one line, literal `\n`), optional `GOOGLE_CLOUD_LOCATION=global`; or `GOOGLE_APPLICATION_CREDENTIALS=<key.json>`

The env-file parser copes with lines run together (`...comGOOGLE_PRIVATE_KEY=...`).
**Never print, cat, echo or commit credential values.** Check them with booleans and counts only.
If none are found, ask the user to add the three variables to the cloud environment's
settings, and never to paste the key into chat.

## Commands

| Goal | Command |
|---|---|
| Make speech | `bin/gm speak "text" [--voice en-au-tutor-4] [--out out/vo/line.mp3] [--takes 3] [--review]` |
| Script from file | `bin/gm speak --file script.txt --out out/vo/intro.wav` |
| Check a clip against its script | `bin/gm review clip.mp3 --text "intended words" [--json] [--context "furniture ad"]` |
| Listen to anything | `bin/gm listen song.mp3 ["question"]` |
| Watch a video | `bin/gm watch reel.mp4 ["question"] [--start 10 --end 25]` (also YouTube / `gs://` URLs) |
| Transcript | `bin/gm transcribe file.(mp3\|mp4)` |
| Mixed inputs | `bin/gm ask "prompt" a.jpg b.mp3 c.mp4 d.pdf` |
| See frames yourself | `bin/gm frames reel.mp4 --count 12 --sheet` (or `--every 2`, `--at 1.5,4`), then Read the jpgs |
| Pick a voice | `bin/gm voices [--lang en-AU\|all] [--female\|--male] [--search podcast] [--json]` |

Shared options: `--json` (ask for JSON), `--save <file>`, `--model <id>`, `--listener "who listens"`
(default: a native Australian English listener), `--temperature`, `--env`.
Output goes to `out/` (gitignored) unless `--out` says otherwise; `.mp3` or `.wav` follows the extension.

## How to use it well

- **Speech is plain text only.** Stage directions are read aloud. Spell things as they're said
  ("Cabinet Works", "ninety centimetres"), and avoid reading prices aloud.
- **Every call is a new take.** For a doubtful word, run `--takes 3 --review` and let the user pick.
- Australian voices: `en-au-tutor-4` (female, bright commercial) and `en-au-training-8` (male, upbeat).
  Standard voices (`Achird` etc.) need `--lang en-AU` or they sound American.
- `review` is **blind**: Argon transcribes without seeing the script, and the tool diffs the words
  itself (`missing` / `extra`). Trust the diff, dropped or mispronounced words, clipped endings and glitches.
  **Delivery scores and "artefact" notes drift between runs.** Run the review twice before acting on them.
- Argon timestamps are about ±1 s off. To check timing precisely, use `frames --at` and look yourself,
  or cut with `--start/--end` and ask about the short clip.
- One media file per question gives the most reliable answers; `ask` with several is for comparisons.
- Big files are cut and compressed automatically (video drops to 720p, then 480p, 360p, 240p) to stay under
  `GM_INLINE_MB` (default 18 MB). For long videos, ask about sections with `--start/--end`.
- Argon can take 10–40 s per call. Transient errors retry with backoff, and 4xx errors fail at once.
  An HTML "Error 403 (Forbidden)!!1" means the network is blocking Google.
- For a full ad mix: `bin/gm listen reel.mp4 "As a mixing engineer: is the VO intelligible over the music? ..."`.
  Apply only fixes that are safe either way.

## Multi-host episodes (`bin/gm dialogue`)

`bin/gm dialogue script.txt --voices Sara=Sulafat,Kaveh=Orus --lexicon lexicon.tsv --out out/<name>`

- Script: `Name: text` per line; `# Section` lines become chapters. The lexicon is `written<TAB>spoken`
  and is applied to the speech only.
- **One TTS call per turn** in that speaker's voice, 6 at a time. Each take gets a blind Argon transcription,
  compared on a consonant skeleton: spelling variants like «سیلندرو/سیلندر رو» and «یه کم/یکم» pass, while dropped
  or added words fail. Each take also gets a pitch check against that speaker's other turns. Failing turns are
  retaken (up to `--retakes`, default 3) and the best is kept. Turns are stitched with varied
  0.3–0.42 s gaps (shorter after a question, 1.0 s between sections), normalised to −16 LUFS.
  Outputs: `episode.mp3`, `report.md` (per-turn verdicts, timestamps), `chapters.txt`, `turns.json`.
  A re-run re-voices only changed turns; `--only 12,40` redoes specific ones.
- Learned the hard way (Nov 2026):
  - **Multi-speaker mode can't keep voices on speakers.** The first configured voice goes to whoever
    speaks first, whatever the labels say (16/16 takes). Real names as labels are also read aloud ("سارا، کیس اول…"),
    and a "TTS the following conversation…" preamble is read aloud too. Hence one call per turn.
  - **Don't set `languageCode: fa-IR`.** It invented extra lines. Without it, the Persian is native Tehrani.
  - `[laughing]`, `[sigh]` at the start of a line are performed, not read.
  - About 1 take in 3 drops or adds a word somewhere, so always run the blind check.
  - No fa-IR prebuilt voices exist. Argon rated Sulafat (F) + Orus (M) best for a senior woman and a younger man.
    Charon and Iapetus sound older and deeper.
  - Argon's voice-gender labels and my pitch measurement agreed. Pitch (F ≈ 180–260 Hz, M ≈ 110–160 Hz) is the cheap check.
  - Persian TTS pace: about 145 words/min in conversation.
