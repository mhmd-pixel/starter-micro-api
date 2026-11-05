# Optocast — Prescribing Cylinder (astigmatism episode)

- `script.txt`: the final script, speaker lines identical to `Optocast_Astigmatism_TTS.txt`, with `#` section lines added for chapters
- `lexicon.tsv`: spoken spellings applied only to the TTS (`اپتوکست` → `اُپتوکست`)
- `chapters.txt`, `voicing-report.md`: from the last render (17:38, 100/100 turns)

Re-render (only changed lines are re-voiced):

```bash
bin/gm dialogue projects/optocast-astigmatism/script.txt --voices Sara=Sulafat,Kaveh=Orus \
  --lexicon projects/optocast-astigmatism/lexicon.tsv --out out/optocast-astigmatism
```

Hand-picked takes (after targeted listens) in the last render: turn 4 (complete «نیست»), 26 («پرل», not «پل»),
31 «دو؟», 33 «و سه؟», 71 «وای». Re-voicing those lines will replace the picks.
