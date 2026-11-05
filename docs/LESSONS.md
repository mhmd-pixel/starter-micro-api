# Lessons: Gemini TTS, Argon and Vertex for Persian podcasts

What we learned voicing a 100-line, two-host Persian episode (and short English and Persian ads before that),
Nov 2026. Each item says what happened and what to do. Numbers are from real runs.
`PODCAST_GUIDE.md` is the step-by-step; this is the why.

---

## 1. Vertex and the SDK

- **Models that answer:** `gemini-3.8-flash-tts` (speech) and `gemini-4-argon` (listening/watching).
  Ids with `-preview`, `gemini-4.0-argon` and the like return 404.
- **Location `global`**, service account with `roles/aiplatform.user`. Node SDK `@google/genai` ≥ 2.24.
- `GOOGLE_PRIVATE_KEY` must be one line with literal `\n`, turned back into newlines in code:
  `key.split("\\n").join("\n")`. Without that you get `DECODER routines::unsupported`.
- **Pasted env files get mangled.** One user's file had three variables run together on one line
  (`…gserviceaccount.comGOOGLE_PRIVATE_KEY="…"GOOGLE_CLOUD_LOCATION=global`).
  `lib.mts` splits on the known key names, not on newlines.
- **Don't set `GEMINI_API_KEY` / `GOOGLE_API_KEY`.** That project had no credits (402), and explicit credentials win anyway.
  The SDK prints a `console.debug` line "credentials will take precedence over the API key" *every time*,
  even with no key set. It's harmless, and `lib.mts` filters it.
- **Never print secrets**, not even "to check the format". Check with booleans and lengths (`gm doctor`).
  A printed key has to be rotated.
- An HTML page "Error 403 (Forbidden)!!1" on every model = the network blocks Google. A `ConnectTimeoutError` mid-run = retry.
- **Rate limits (429 RESOURCE_EXHAUSTED) hit Argon first.** At 6 parallel workers they came within minutes.
  The fix: concurrency 3–4, and back off 15 → 30 → 60 → 90 s with jitter on 429 (the default 1.5 s backoff just burns attempts).
  Speech calls take 2–5 s; Argon calls 10–40 s.
- `ai.voices.list()` works on Vertex only **unfiltered** (2,115 voices, pages of 1,000). Filter yourself.

## 2. TTS behaviour (any language)

- **Plain text only.** Any instruction in the text is spoken ("Read this warmly: …" is read aloud).
  `systemInstruction` returns 400. There's no style field on Vertex. Choose the voice for character.
- **The response is a whole WAV** (RIFF header, `data`, then metadata chunks such as an IPTC "algorithmicMedia" tag),
  not bare PCM. Wrapping it as PCM again gives a ~150 ms static burst at the end. Parse the chunks and keep `data`
  (`samplesOf()`). It's 24 kHz mono 16-bit.
- **Every call is a new take**: timing, intonation and errors change. Cache by hash of `model|voice|spoken text`
  and never re-voice an unchanged line by accident.
- **Truncated endings are the most common real fault** (roughly 5–10% of lines). The model stops early:
  «…درست» without «نیست», «امیدوارکنند» for «امیدوارکننده‌ای», «سیلند» for «سیلندر». We proved it's the model,
  not the trimming: untrimmed takes truncate the same way. Detect it two ways (`speech.mts`): the script's last word
  must be among the last words heard, and loud audio in the last 120 ms means it stopped mid-sound. A retake usually fixes it.
  One line («[laughing] و می‌گه هیچ‌کدومش درست نیست.») truncated in 10 of about a dozen takes, with or without trimming, with different punctuation, and again
  (2 of 3 takes) with the laugh tag removed. Some sentences are simply prone to it. We kept making takes and adopted a complete one.
- **Trim generously.** Persian endings are quiet (final «ت», «ه»). Cutting at 1% of full scale with 250 ms
  kept after the last loud sample and an 80 ms fade-out is safe. The old 120 ms tail clipped some endings.
- **Very short lines are the weakest** (one or two words: «دو؟», «و سه؟», «وای»). Seen: flat statement
  intonation instead of a question, «و» said as «او» or as a formal «وُ», and once **Arabic numbers instead of
  the line** («واحد اثنین سبعه»). Always make 4 takes and pick with a blind check; ~1 in 2 is good.
- **Stage tags** (`[laughing]`, `[sigh]`, `[short pause]`) are *performed*, not read: a chuckle, a «پوف».
  But the user judged them artificial and said the episode would be as good or better without them.
  **Strip them by default** (the tool does; `--keep-tags` keeps them). Lines starting with a laugh also truncated more often.
- **Don't set `speechConfig.languageCode: "fa-IR"`.** In a test it **invented two lines** that weren't in the
  script («اوکی», «انگار یه ربات داره جایگزینت می‌کنه»). Without it, the accent is native Tehrani (Argon: 10/10).
  (English standard voices *do* need `languageCode: "en-AU"` etc. to lose an American accent. That's English only.)

## 3. Multi-speaker mode: don't use it for production

We tried it first (whole exchanges in one call, which sounds more conversational). It failed in three ways:

| Test | Result |
|---|---|
| Labels `Sara:` / `Kaveh:` | The first label was **spoken** («سارا، کیس اول…») in 4/4 takes |
| Preamble "TTS the following conversation between Sara and Kaveh:" | The preamble was **read aloud** in 2/4 takes |
| Neutral labels `Speaker1:` / `Speaker2:` | Labels silent in 4/4, **but** see the next row |
| Voice mapping (Kaveh speaks first, Sara's voice configured first) | **The first configured voice went to whoever spoke first in 16/16 takes**, whatever the labels said. Reversing the config order gave ~50/50. One take had two women |
| A 23-chunk episode | Kaveh in Sara's voice in **16 of 23 chunks** |

Conclusion: **one TTS call per line**, in that speaker's voice. It's reliable, there are no labels to read,
each line can be checked and retaken on its own, and the gaps are under control. It costs a little
spontaneity, which per-line level matching and varied gaps mostly recover.
If you ever must use multi-speaker, use neutral `SpeakerN` labels and verify each turn's voice by pitch.

## 4. Voices for Persian

- **No `fa-IR` prebuilt voices exist.** The 30 standard multilingual voices speak native Tehrani colloquial Persian.
- Audition result (same 4 lines, Argon rating each host separately, 1–10):

| Pair | Female | Male | Chemistry | Notes |
|---|---|---|---|---|
| **Sulafat + Orus** | 9/9/9 | 9/9/8 | **9** | Best: Orus sounds younger, curious |
| Sulafat + Charon | 9/8/8 | 9/9/8 | 8 | Charon: warm, deep, "prod-grade ad VO", but sounds older |
| Sulafat + Puck | 9/9/8 | 9/9/8 | 8 | Quick, engaged male |
| Leda + Charon | 9/8/8 | 9/9/8 | 8 | |
| Kore + Charon | 9/8/8 | 9/8/8 | 8 | |
| Achernar + Charon | 9/8/8 | 8/7/6 | 7 | Achernar clear, radio-host feel |
| Aoede + Charon | 8/7/7 | 9/8/6 | 7 | Aoede: more "audiobook" than conversation |
| Sulafat + Achird | 9/8/8 | 8/7/8 | 8 | Achird mixes formal and colloquial endings |
| Sulafat + Iapetus | 9/8/8 | 9/8/7 | 7 | Iapetus deep, mature |

- For a single narrator (ads): **Charon** (male) and **Achernar** (female) were both rated 10/10.
- Pitch, as measured: Sulafat ≈ 190–260 Hz, Orus ≈ 110–160 Hz. A line far outside its speaker's median is
  probably the wrong voice. Argon's gender labels matched these measurements every time.

## 5. Argon as the ears

**How to ask**
- **One clip per request.** Several clips → garbage (`Scho design<end_of_image>`), or it mixes them up.
- **Blind first.** It's suggestible: when told "the word should be «پرل» (pearl)", it confirmed «پرل» for every take,
  including the one that clearly said «پل» on a blind listen. Ask it to transcribe (or spell a word "sound by
  sound in Latin letters") without the answer, and compare yourself.
- JSON mode (`responseMimeType: "application/json"`), validate the shape, check the transcription isn't
  far shorter than the line, and retry 3–5 times. It sometimes answers something else entirely.
  A review failure must never fail the pipeline.
- Prompt it as the right listener: "a native Tehrani Persian listener" for Persian, "as a mixing engineer" for mixes.

**Comparing its transcription with the script (Persian)**
- A naive word diff flags ~40% of good lines. Argon's spelling varies: spaces («بیس کروشون» / «بیسکروشون»),
  clitics («منبعمون هم» → «منبعمونم», «سیلندرو» / «سیلندر رو»), «یه کم» / «یکم», ezafe «همه‌ی» / «همه»,
  digits («۱۴» for «چهارده»), Latin script for English terms.
- What works (`speech.mts`): normalise (ی/ي, ک/ك, harakat, ZWNJ, digits), then compare a **consonant skeleton**.
  Drop vowels and the letters ا و ی ه, drop spaces, and collapse doubled letters. Spelling variants then differ by 0–2 consonants;
  a dropped or added word leaves a run of 3+. The rule: fail on a run ≥ 4, or more than max(3, 12%) differing,
  or a missing last word. In this episode only 3 of 100 lines needed a retake on the first pass, each for a real error (a misheard word,
  a wrong verb ending); the old word diff had flagged 9 of 23 chunks on spelling noise alone. In tests it
  still catches «سیلندش» for «سیلندرش» and a dropped «عینکش».
- The skeleton is blind to **vowel-only damage** (a truncated «امیدوارکننده‌ای» → «امیدوارکنند»). Read
  Argon's own "cut-off word" notes too.

**What to trust and what not to**

| Trust (act on it) | Don't trust (or let the human decide) |
|---|---|
| Dropped, added or swapped words | Naturalness and energy scores: the same clip got 9 and then 7 |
| Cut-off endings, "last word missing" | "The laugh sounds slightly fake": varies between runs |
| Mispronunciations: «موریدین» for «مریدین», «پیمار» for «بیمار», «پل» for «پرل», an English "r" in «سیلندر» | Gap opinions: "too long" in one part, "too short" for the same kind of gap in another |
| Wrong voice or speaker (matched pitch measurements) | Timestamps: about ±1 s |
| Glitches, clicks, static | "Cut off at 00:00 / at the end" when you sent a `--start/--end` part: that's your cut |
| Clear level jumps between lines | Mix taste (EQ, "closer-miked") |

- It also notices **script-level** issues: a calque («ضررتو قطع کن» for "cut your losses"), a formal ending in
  colloquial speech, ambiguous wording («یه کم وضوح دادن» read as "gave clarity" where "gave up clarity" was meant),
  a jargon pronunciation («جاوال» vs the clinic's «ژاوال»). Pass these to the user; don't edit their script.
- Mispronunciations were **random, not systematic**: one retake fixed every one of them. Only put a word in the
  lexicon if it's wrong in every take.

**Whole-episode QC**
- Split into ~4.5-minute parts with 5 s overlap (`bin/qc-episode.sh`). Ask as a podcast producer: flow, voice
  consistency, wrong voice, glitches, a score. The full 17-minute file fits the inline limit, but shorter parts get
  sharper answers.
- Argon on the per-line levels: before level matching it named 3 level jumps in one part; after, "none, 10/10".

**It can also watch video** (`gm watch`): it quotes on-screen text exactly, describes scenes and judges audio-to-picture sync,
with timestamps about 1 s early. For exact timing, pull frames (`gm frames --at …`) and look yourself.

## 6. Writing Persian for TTS

- **Spell things the way they're said.** Numbers as words («منفی یک و بیست و پنج», «ده دهم», «آکسیس صد و شصت و پنج»).
- **Brand names: add the vowel mark.** «اپتوکست» was heard as «اپ‌تو‌کست» / «آپتوکست»; «اُپتوکست» fixes it.
  Put this in the lexicon so the script stays clean.
- **Ezafe:** an unwanted ezafe goes away with a comma («پیامک تأیید، خودکار…»); a missing one is fixed by writing the kasra («مادرِ پرنیا»).
- **The indefinite «ی» read as the noun suffix** («بیماری» heard as "disease"): «بیمار‌ی» with a ZWNJ, or «یک بیمار».
- **Homographs** («کره»): use the jargon term («اسفر») or the vowel mark («کُره»).
- **Loanwords** it stumbles on: reword around them if a retake doesn't help.
- Clinic English in Persian script (سیلندر، آکسیس، ترایال فریم، بیس کِرو، اسفریکال اکیوالنت، پنتوسکوپیک تیلت، اکسیکلوتورژن، پسودومایوپیا)
  was spoken naturally, the way Iranian clinicians say it.
- Colloquial Tehrani (`می‌گه`, `نیست`, `عینکو`) is read natively. Argon flagged one formal «دارند» inside colloquial
  speech, and one formal «وُ».
- **Don't trust Argon on meaning** for homographs. It accepted the "disease" reading of «بیماری». The human is the
  final judge of meaning.

## 7. Assembly

- **Gaps** between lines of different hosts: 0.30 s + up to 0.12 s of variation, 0.07 s less after a question,
  +0.35 s when the same host has two lines in a row, 0.8 s at a section break (1.0 s felt long).
- **Level matching per line:** the RMS of the speech frames (frames above 20% of the line's peak),
  scaled to the speaker's median, within ±6 dB. Then `loudnorm=I=-16:TP=-1.5:LRA=11`, 48 kHz, MP3 mono 128 kbps.
- **Fades:** 10 ms in, 80 ms out on every line, to avoid clicks at the joins.
- **Length:** Persian conversation ≈ **2.2 spoken words per second**, ≈ 130–140 wpm with gaps.
  Budget scripts at 130 wpm, not the 150–170 an English-style estimate gives.

## 8. Process: what cost time, and what to do instead

- **Test small before big.** The multi-speaker episode (23 chunks, 18 min) was useless because of the voice-mapping bug.
  A 4-line test with a pitch check would have shown it in 2 minutes.
- **Verify the checker itself.** The first word check passed truncated endings (a missing short last word fell under
  its tolerance) and failed good lines on spelling noise. Re-run the improved rules on the stored transcriptions
  (the tool does this automatically) instead of re-voicing.
- **Count line numbers the same way the tool does** (speaker lines only). Once a fix almost went to the wrong line.
- **Run long jobs in the background** with a monitor on progress and failure lines. Don't poll.
- **The assembly step is cheap** (`--assemble`, no API calls). Tune gaps and levels freely.
- **Keep every take** (`takes/`). A good take for a stubborn line may already be there.
- Deliverables in `out/` aren't committed. Send the files to the user before the container goes away.
