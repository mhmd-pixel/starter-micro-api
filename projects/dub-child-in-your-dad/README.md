# Persian dub: "The child in your dad" (YouTube Short, 52.8 s)

- `poem.tsv`: English line timings (from Argon) plus the Persian lines. The poem is in رمل مسدس محذوف
  (فاعلاتن فاعلاتن فاعلن), 14 hemistichs in rhyming couplets, written for meaning, not word-for-word.
- Voice: Gemini 3.8 Flash TTS, `Algieba`, chosen over Charon, Enceladus and Orus in blind A/B against the original narrator.
  One call per couplet, three or four takes each, every take checked blind.
- Music and SFX: the original track with the English voice removed by Demucs (`htdemucs_ft`, `--two-stems=vocals`).
  Checked blind for leftover speech: none.
- Mix: the Persian voice is matched to the original narration's loudness (−14.3 LUFS), the mix comes out at −14.0 (original −14.1).
- Picture: the burned-in English captions are removed with `delogo=x=88:y=968:w=544:h=68`, a soft dark band is laid over
  that area, and the Persian captions from `fa.ass` are burned in (Vazirmatn Bold 50, centre 360,1000).
  x264 CRF 18 at 720x1280, 29.97 fps, AAC 160k.
- `plan.txt`: the chosen take and start time of each couplet.
