# Gemini podcast kit

Turn a finished podcast script into a checked, ready-to-publish MP3 with **Gemini TTS** (speech) and
**Gemini Argon** (listening) on **Vertex AI**. Built and proven on a two-host, 17-minute Persian episode.

**If you're an agent picking this up:**

1. **Ask the user for the four credentials** before anything else: `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLIENT_EMAIL`,
   `GOOGLE_PRIVATE_KEY`, `GOOGLE_CLOUD_LOCATION` (= `global`). Write them to `~/.config/gemini-media/vertex.env` (format:
   `vertex.env.example`, `chmod 600`). Never print or commit them. Details: `PODCAST_GUIDE.md` §0.
2. Run `bin/gm doctor` (it installs dependencies on first run; needs Node 20.11+).
3. Follow **`PODCAST_GUIDE.md`** step by step. Read **`LESSONS.md`** once: it explains why the steps are what they are,
   and lists the traps (multi-speaker mode, truncated endings, a suggestible listener, Persian spelling).

The whole job, given a script:

```bash
bin/gm doctor
bin/gm dialogue script.txt --voices Sara=Sulafat,Kaveh=Orus --lexicon lexicon.tsv --out out/my-episode
bin/gm dialogue … --redo-failed                    # re-voice lines that failed the checks
bin/gm speak "دو؟" --voice Orus --takes 4 --review # stubborn line: pick the best take…
bin/gm dialogue … --adopt 31=out/…/take-2.wav --assemble   # …and drop it in
bin/qc-episode.sh out/my-episode/episode.mp3       # whole-episode listen
```

| File | Purpose |
|---|---|
| `PODCAST_GUIDE.md` | Step-by-step playbook: credentials → script prep → voices → run → fixes → QC → delivery |
| `LESSONS.md` | Everything learned: Vertex, TTS quirks, multi-speaker failure, Persian, Argon, assembly, numbers |
| `bin/gm` | CLI entry point (`bin/gm --help`) |
| `bin/qc-episode.sh` | Whole-episode QC by Argon in overlapping parts |
| `tools/gemini-media/dialogue.mts` | The episode pipeline |
| `tools/gemini-media/speech.mts` | Persian-aware transcript comparison, pitch, cut-off, levels |
| `tools/gemini-media/cli.mts` | All commands (speak, review, listen, watch, transcribe, ask, frames, voices, doctor, dialogue) |
| `tools/gemini-media/lib.mts` | Vertex client, credentials, retries/backoff, WAV + ffmpeg, media upload |
| `examples/optocast-astigmatism/` | A real script with sections, a lexicon, and the resulting chapters and voicing report |
| `.claude/skills/gemini-media/SKILL.md` | Short notes Claude Code loads automatically when working in this folder |

Beyond podcasts, `gm` gives an agent ears and eyes: `gm listen` (music, speech, mixes), `gm watch` (video with sound,
YouTube links), `gm transcribe`, `gm ask` (images + audio + video + PDFs together), `gm frames` (stills to look at).
