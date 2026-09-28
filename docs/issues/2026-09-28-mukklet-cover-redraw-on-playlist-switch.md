# 2026-09-28 — Mukklet cover redraws when switching playlists

**Status:** Done
**Link:** — (reported in chat)   **Updated:** 2026-09-28

## Report
The user's words: "whenever i just click on a different playlist, while the music is playing
from the initial playlist, the cover on the esp32 screen is as if redrawn, only the cover
though, since the text parts are remained without changes, on changing the playlist the cover
still shows the correct one, just it is redrawn unnecessarily".

- Symptom: switching the active playlist during playback makes the display's cover blink
  (redraw). The text does not change. The final cover is correct.
- Environment: Mukk `master` after v1.1.1, the ESP32 display (`../esp32-mukklet`, `rgb565`
  160 × 160 cover).
- Not stated: whether other actions (a rescan, a file change in the library folder) cause
  the same blink. The findings below say they should.

## Findings
1. **A playlist switch changes two inputs of the display snapshot.**
   `activatePlaylist()` sets `_selectedFolderEntries` twice and calls `loadTracksSync()`,
   which sets `_tracks` (`MukkViewModel.kt:614`, `:621-622`, `:837`).
2. **A new `_tracks` value re-reads the cover as a new `CoverArt` object.**
   `displaySnapshot` combines the current path with `_tracks` and runs `mapLatest`, which
   calls `readCoverArt(path)` on every emission (`MukkViewModel.kt:107-110`, `:720-721`).
   `CoverArt` compares by identity on purpose (`DisplayLink.kt:46-50`), so the same bytes read
   again are a "different" cover.
3. **A new `_selectedFolderEntries` value can change `next`.** The playing track is not in the
   new playlist's list, so `currentIdx` is `-1` and `nextIndexInOrder` returns `0`
   (`MukkViewModel.kt:312`, `:319`). `next` becomes the new playlist's first track. That is
   correct — `nextTrack()` uses the same list — but it is a change.
4. **Any change of `track`, `next` or `cover` resends the cover.** `sendLoop` compares
   `Triple(track, next, cover)` with the last sent one and calls `sendTrack`, which always
   sends `track` and then `cover` + all binary chunks (`DisplayLink.kt` `sendLoop` /
   `sendTrack`). This was a known choice when covers were still `none`: the display-link doc
   says "A change of `next` alone resends `track` + `cover`"
   (`docs/issues/2026-09-27-mukklet-display-link.md:242`). The protocol also says `cover` is
   "Sent right after every `track`" (`../esp32-mukklet/docs/PROTOCOL.md:133`).
5. **The firmware blanks the cover while it receives a new one.** `cover_begin()` sets
   `complete = false` (`../esp32-mukklet/main/cover.c:18`), so `cover_for()` returns `NULL`
   and the UI fills the placeholder square (`main/ui.c:128-131`) until all chunks arrive.
   There is one buffer only, no PSRAM (`main/cover.h:3-6`). This is the visible "redraw".
6. **Why the text does not blink.** The firmware does not draw `next` (no use in
   `main/ui.c`), a repeated `track` with the same `id` keeps the scroll (`main/player.c:26-30`),
   and `display_frame()` skips strips whose hash did not change (`main/display.c:127-128`).
   So the resent `track` produces identical text pixels and no panel write.
7. **The firmware already copes with a `track` that has no `cover` after it.** A finished
   cover stays until a new `cover` message (`cover_cancel` does nothing when not receiving,
   `main/cover.c:42`), and `cover_for()` matches by track id (`main/cover.c:47-50`).
   Inference from reading the code; not yet run on the device.

Not reproduced by an automated test. The trace above is from reading the code on both sides.

## Root cause
Mukk resends the cover whenever the `track`/`next`/`cover` triple changes
(`DisplayLink.sendLoop`), and a playlist switch changes it twice for the same track: `next`
moves to the new playlist's first track, and the `_tracks` reload re-reads the art into a new,
identity-compared `CoverArt`. The firmware blanks its single cover buffer on every `cover`
message, so each resend shows as a redraw.

## Impact
Every playlist switch during playback. By the same path, also every library rescan and every
watcher-triggered `_tracks` reload (finding 2), and every change of `next` (repeat/shuffle
toggle, a folder change in the track list). Cosmetic only: the final cover is right. It also
costs ~51 KB of Wi-Fi traffic per resend. No workaround.

## Open questions
- None blocking. The manual device check is the only proof of the visible fix.

## Options

### A. Send `cover` only when the track id or the art changed (recommended)
- `DisplayLink.sendLoop`: keep the last sent `(trackId, cover)` apart from the last sent
  `track`/`next`. A `next`-only change sends `track` alone, with the `hasCover` of the last
  encode.
- `CoverArt`: compare by content (`===` first, then `contentEquals`), so a re-read of the same
  tag is equal. The 1 s tick compares the same instance, so the byte compare runs only when
  the ViewModel really read the art again.
- `../esp32-mukklet/docs/PROTOCOL.md`: change "Sent right after every `track`" to "after a
  `track` whose id or art changed; always after (re)connect".
- Pros: fixes all triggers in the Impact list, and saves the traffic. No firmware change.
- Cons: a protocol rule changes, and a second repo's doc must follow. The sender keeps a bit
  more state. A byte compare of a large embedded image (hundreds of KB) on each `_tracks`
  reload — rare, off the UI thread.
- Risk: if the firmware inference (finding 7) is wrong, the cover could vanish after a
  `next`-only change. The device check covers it.

### B. Stop the ViewModel from re-reading the art; keep `next` changes as they are
- Read the cover keyed on the path only (not on `_tracks`).
- Pros: tiny change.
- Cons: fixes the `_tracks` trigger only. A `next` change (finding 3) still redraws, so the
  playlist switch still blinks. A tag edit of the playing track's art would no longer reach
  the display until the next track.

### C. Won't fix
- Cons: the blink stays on every playlist switch and rescan.

**Recommendation: A.** It is the only option that removes the blink on playlist switch; B
leaves the `next` trigger in place.

## Decision
Option A, approved by the user on 2026-09-28.

## Implementation plan

### Part 1 — send `cover` only when the track id or the art changed
- [x] `CoverArt` compares by content (`===` first, then `contentEquals`). `LinkTiming.kt` gets
  a pure `isCoverDue(sent, snapshot)` and a `SentCover` (track id, art, encode result).
  `DisplayLink.sendLoop` sends `track` when `track`/`next` changed or a cover is due, and
  `cover` + chunks only when a cover is due; a `track` without a new cover reuses the last
  `hasCover`. `../esp32-mukklet/docs/PROTOCOL.md`: `cover` follows a `track` whose id or art
  changed, and always the first `track` after (re)connect. Fix the "resends `track` +
  `cover`" note in the display-link doc.
- Verify: `./gradlew :core:mukklet:jvmTest composeApp:jvmMainClasses detekt`. New tests in
  `CoverDueTest.kt`: same bytes in two `CoverArt` objects are equal; a cover is due on the
  first send, on a new track id, and on new art; it is not due when only `next` changed or
  the same art was read again. The equality test fails before the change. Manual (user): on
  the ESP32, switch playlists while a track plays — the cover must not blink, and must still
  change on the next track.
- Landed: as planned. `sendTrack` now takes the last `SentCover` and returns the new one. The
  two `CoverArt` tests failed before the change (`35 tests, 2 failed`) and pass after it
  (35 tests, 0 failed); `composeApp:jvmMainClasses` and `detekt` pass. Not run end to end
  against `fake_display.py`: a playlist switch needs GUI driving, and the unit tests cover the
  send decision. Device check: the user confirmed on the ESP32 (2026-09-28) that a playlist switch no
  longer redraws the cover.

## What landed
- `CoverArt` compares by content; `isCoverDue` + `SentCover` in `LinkTiming.kt`;
  `DisplayLink.sendLoop`/`sendTrack` send `cover` only when it is due.
- Test: `core/mukklet/src/jvmTest/.../CoverDueTest.kt`.
- `../esp32-mukklet/docs/PROTOCOL.md`: the `cover` rule (committed in that repo).
- Left out: the ViewModel still re-reads the art on every `_tracks` reload. That is disk I/O
  only now, with no resend.
