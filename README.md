# Workspace: gemini-media

A command-line tool, `bin/gm`, that gives Claude (or you) ears and eyes for media through Gemini on **Vertex AI**:

- **Speak**: text to speech with `gemini-3.8-flash-tts`, trimmed of silence and saved as MP3 or WAV
- **Review**: a blind transcription by `gemini-4-argon`, a word diff against the script, and delivery notes
- **Listen / watch / transcribe / ask**: Argon hears audio (speech, music, mixes) and watches video with its sound
- **Frames**: stills and a contact sheet from a video, for looking at directly
- **Voices**: browse the prebuilt voices (44 en-AU)

```bash
bin/gm doctor                                    # credentials (booleans only) + one call to each model
bin/gm speak "G'day! Not sure what size cabinet fits?" --takes 3 --review
bin/gm review out/speech/line.mp3 --text "G'day! Not sure what size cabinet fits?"
bin/gm watch reel.mp4 "Does the voice-over line up with the on-screen text?"
bin/gm listen track.mp3 "Genre, tempo and any mix problems?"
bin/gm frames reel.mp4 --count 12 --sheet
bin/gm voices --female
bin/gm --help
```

## Credentials

Never commit them: `.gitignore` blocks `*.env` and key JSONs. The tool looks, in order, at `--env <file>`,
`$GEMINI_MEDIA_ENV`, `~/.config/gemini-media/vertex.env`, then the environment variables
`GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLIENT_EMAIL` and `GOOGLE_PRIVATE_KEY` (one line with literal `\n`),
or `GOOGLE_APPLICATION_CREDENTIALS`. The service account needs `roles/aiplatform.user`.

For cloud sessions, add those three variables to the environment's settings so every new container has them.

Full usage notes for agents: [`.claude/skills/gemini-media/SKILL.md`](.claude/skills/gemini-media/SKILL.md).
Code: [`tools/gemini-media/`](tools/gemini-media/) (`cli.mts` commands, `lib.mts` client/audio/ffmpeg).
