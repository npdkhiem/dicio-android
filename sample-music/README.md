# Sample music for Dicio 4.3 local-music testing

Small, original, copyright-free synthesized tones (ffmpeg sine waves). Not real songs — just distinguishable pitches for voice / same-name / folder tests.

## Copy onto the phone

1. On the phone, open Files (or any file manager) and go to the shared **Music/** folder (or the folder you pick in Dicio → Settings → Local music).
2. Copy everything under this `sample-music/` folder into that Music folder, keeping the `AlbumA/` and `AlbumB/` subfolders.
3. Grant Dicio storage / music-folder access when prompted, then try the commands below.

Total size is under 1 MB.

## Files

| File | Format / note | Pitch (approx.) | Suggested test |
|------|---------------|-----------------|----------------|
| `Hello.mp3` / `.m4a` / `.flac` / `.ogg` / `.wav` | Same name, five formats | A4 440 Hz | Same-name dedupe: say **"play Hello"** — Dicio should pick one without asking when names match. |
| `AlbumA/Intro.mp3` | Higher tone | C5 523 Hz | Same basename in two folders — ask which if names differ only by path? (both named Intro) |
| `AlbumB/Intro.mp3` | Higher still | E5 659 Hz | Same as above; the two Intros sound different so you can hear which folder played. |
| `Snow.mp3` | | F4 349 Hz | Short-word / **"no"** confusion: **"play Snow"** |
| `Glove Puppet.mp3` | Space in name | G4 392 Hz | **"love"** substring: **"play Glove Puppet"** or **"play glove"** |
| `Yesterday.ogg` | ogg only | B4 494 Hz | **"play Yesterday"** |
| `Next.mp3` | | D5 587 Hz | **"play next from my music"** / next-track routing vs song titled Next |
| `Hà Nội mùa thu.mp3` | Vietnamese name | F5 698 Hz | Vietnamese filename / voice: **"play Hà Nội mùa thu"** |
| `Broken.mp3` | **Deliberately corrupt** (junk bytes, not audio) | — | Error path: **"play Broken"** — expect a failure reply, not a crash. Also useful for repeat-all mid-play failure (N2) and stale error text (N3/N4). |

## Useful voice commands (English)

- **"play song Hello"** / **"play Hello"**
- **"play Intro"** (may disambiguate AlbumA vs AlbumB)
- **"play Snow"** / **"play Glove Puppet"** / **"play Yesterday"** / **"play Next"**
- **"play Hà Nội mùa thu"**
- **"play Broken"** (expect error)
- **"stop music"** / **"pause music"** / **"resume music"**
- **"next song"** / **"previous song"**
- **"repeat"** / **"repeat off"** (repeat is off by default)

## Notes

- These tones are generated for testing only; redistribute freely.
- `Broken.mp3` must stay corrupt — do not replace it with a real encode.
