# Persian dub: "What you take for granted" (YouTube Short, 52.2 s)

- `script.tsv`: English sentences with timings (from Argon) and the Persian narration, in natural spoken Persian (not verse).
  Sentence 4 was reworded because the TTS model returned no audio for the first wording, then shortened to fit its slot.
- Voice: Gemini 3.8 Flash TTS `Algieba`, the same voice as the first dub. One call per sentence, three takes each,
  every take checked blind. `plan.txt` records the take used and its start time.
- Music and SFX: Demucs `htdemucs_ft --two-stems=vocals`, with the English voice removed. Spot checks found no intelligible speech left.
  The narration is matched to the original voice's loudness (−13.2 LUFS); the mix comes out at −12.7 (original −13.0).
- Captions: `fa.ass` (Vazirmatn Bold 50, wrapping), over a soft band; the English captions are removed with `delogo=x=40:y=966:w=640:h=76`.
- Phone screens in Persian: `screens.json` + `tools/dub/screens.py`. On a reference frame of each shot the screen is
  rectified, the text areas are filled with a plane fitted to the background colour and redrawn in Persian (Pillow + raqm), then
  every frame is matched to the reference (SIFT + RANSAC homography; chained frame to frame on the phone's own keypoints
  when the direct match is weak, backwards and forwards), and the patches are warped in, brightness-matched, and kept
  under fingers by comparing each frame with the warped original. Fast motion-blurred frames at the start of each shot
  are left as they are.
