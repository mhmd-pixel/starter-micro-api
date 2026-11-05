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
| Check a clip against its script | `bin/gm review clip.mp3 --text "intended words" [--json]` (blind, Persian-aware, cut-off + pitch) |
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

## Podcasts and multi-host scripts (`bin/gm dialogue`)

Read `docs/PODCAST_GUIDE.md` (the step-by-step) and `docs/LESSONS.md` (the traps and numbers) before voicing an episode.
In short:

- First, ask the user for the four credentials (`GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLIENT_EMAIL`, `GOOGLE_PRIVATE_KEY`,
  `GOOGLE_CLOUD_LOCATION=global`) unless `bin/gm doctor` already passes.
- `bin/gm dialogue script.txt --voices Sara=Sulafat,Kaveh=Orus --lexicon lexicon.tsv --out out/<name>`
  makes one TTS call per line, checks each blind with Argon (consonant-skeleton diff, last word, cut-off,
  pitch), retakes, level-matches, writes `episode.mp3` at −16 LUFS, and also `report.md` and `chapters.txt`.
- Fix with `--redo-failed`, `--only 4,17`, or `bin/gm speak "…" --voice Orus --takes 4 --review` + `--adopt 4=take.wav`.
  Then run `bin/qc-episode.sh out/<name>/episode.mp3`.
- Never use multi-speaker TTS mode (voices land on the wrong speaker). Never set `languageCode: fa-IR`
  (it invents lines). `[laughing]`-style tags are stripped by default because they sound acted.
- Argon is suggestible: ask blind. Trust it on words, cut-offs, mispronunciations and wrong voices. Ignore its
  delivery scores and gap opinions.
