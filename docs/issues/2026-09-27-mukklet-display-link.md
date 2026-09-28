# 2026-09-27 — Mukklet display link (stream now-playing to an ESP32 display)

**Status:** Done
**Link:** `../esp32-mukklet/docs/MUKK_TASK.md`, contract `../esp32-mukklet/docs/PROTOCOL.md`   **Updated:** 2026-09-27

## Report

This is a feature request, not a bug. The request comes from the sibling repo
`../esp32-mukklet`: an ESP32 desk display ("Mukklet") that shows what Mukk plays and has a
knob that controls Mukk.

- `PROTOCOL.md` is the contract. The ESP32 is a WebSocket **server** at `ws://<host>/ws`;
  Mukk is the **client**. Mukk must not change or extend the protocol. A mismatch goes into
  the "Open questions" section of `MUKK_TASK.md`.
- Mukk sends `track`, `cover` (JSON header + binary chunks), and `state` (on change and
  every 5 s as a heartbeat). The display sends `hello` (cover size and format, `maxChunk`)
  and `cmd` (`play_pause`, `next`, `prev`, `volume` with `delta`, `seek` with `deltaMs`).
- Cover formats: `mono1` (grayscale, Floyd–Steinberg, 1 bpp, MSB = leftmost, 1 = white)
  and `rgb565` (big-endian). Mukk does all image work.
- Reconnect with backoff 2 s → 30 s. Never block playback or UI. No error dialog.
- Setting: on/off (default off) and host (default `mukklet.local`, may include `:port`).
- Test tool: `python3 ../esp32-mukklet/tools/fake_display.py [--format rgb565 --size 240]`
  listens on `localhost:8765`, flags violations with `!! PROTOCOL:`, saves covers as PNG in
  `/tmp/fake_display/`, and sends commands from stdin keys.

What the task omits: where the component lives in Mukk's module graph, which JSON library
to use, and how to tell a "seek" apart from normal position progress. Those are decisions
below.

## Findings

### Data sources in Mukk

- **Playback state.** `AudioPlayer.state: StateFlow<PlaybackState>`
  (`core/player/.../AudioPlayer.kt:28-29`). `PlaybackState` has `playbackStatus`,
  `currentTrackPath`, `positionMs`, `durationMs`, `volume`
  (`core/model/.../PlaybackState.kt`). The position is polled **every 200 ms** while
  playing (`AudioPlayer.kt:196-207`), so the flow emits about 5 times per second.
- **Repeat / shuffle.** `_settingsState` in `MukkViewModel` (`MukkViewModel.kt:50`).
  Private to the ViewModel; `uiState` exposes it combined with everything else.
- **Track metadata.** `MediaTrackData` from the DB. The ViewModel keeps `_tracks` (all
  tracks) and `uiState.currentTrack` resolves the playing one (`MukkViewModel.kt:76`).
  An unscanned file has no DB row; `MetadataReader.read(file)` returns `AudioMetadata`
  for it (`core/scanner/.../MetadataReader.kt:40`).
- **Cover art.** The task names `MetadataReader.readAlbumArt()`. **That method does not
  exist any more.** The current API is `readNowPlayingExtras(file)`, which returns an
  already-decoded Compose `ImageBitmap` (`MetadataReader.kt:15-31`, decoded through Skia at
  `:80-81`). The raw bytes are `audioFile.tag?.firstArtwork?.binaryData` (`:31`). The
  encoder needs raw bytes (or a `BufferedImage`), so `core:scanner` needs a small new
  accessor for them.
- **Next-track preview.** `nextTrack()` (`MukkViewModel.kt:225-273`) picks from
  `_selectedFolderEntries` — the folder selected in the tree, not necessarily the folder
  that is playing. Repeat ONE replays the current track (`:233-241`). Shuffle picks at
  random (`:244-252`). End of folder with repeat OFF calls `stop()` (`:256-261`). A
  preview must reproduce this logic without its side effects.

### Actions the display must drive

All exist on `MukkViewModel`, and the transport bar and the Space key call the same
functions (`App.kt:88`, `App.kt:107`):
`togglePlayPause()` `:193`, `nextTrack()` `:225`, `previousTrack()` `:275`,
`setVolume(Double)` `:220` (also persists the value), `seekTo(Long)` `:216`.
`AudioPlayer.setVolume` clamps to 0.0–1.0 (`AudioPlayer.kt:139-142`); `seekTo` does not
clamp (`:134-136`), so the link must clamp `seek` to `0..durationMs` itself.

### Lifecycle

- `MukkViewModel` is created inside composition via `koinViewModel()` (`App.kt`).
  `main.kt` has no reference to it. `main.kt` closes singletons from Koin in
  `onCloseRequest` (`main.kt:78-86`). So a `DisplayLink` that is a Koin `single` can be
  closed there, next to `audioPlayer.dispose()`.
- `MukkViewModel.onCleared()` does nothing (`:795-798`); cleanup lives in `main.kt`.

### Libraries

- No JSON library in `gradle/libs.versions.toml` (grep for `serialization`/`json` finds
  nothing).
- The JDK's `java.net.http.HttpClient.newWebSocketBuilder()` needs no new dependency. Its
  `WebSocket.sendText/sendBinary` rule: a new send must not start before the previous
  send's `CompletableFuture` completes (JDK `java.net.http.WebSocket` Javadoc). So all
  outgoing messages must go through one serial sender (a `Channel` + one coroutine). This
  also gives the "never interleave two covers" guarantee from `PROTOCOL.md` for free.
- *Inference, to verify in the implementation part:* one `sendBinary(chunk, true)` call
  produces one WebSocket frame. `fake_display.py` reassembles fragments
  (`read_message`, lines 70-85), so it would **not** detect a split frame. If the JDK
  fragments large messages, the real ESP32 may see frames > `maxChunk`.
- `composeApp/build.gradle.kts` has a `commonTest` source set but no tests. Only
  `core:data` has a `jvmTest` (`core/data/build.gradle.kts`, `libs.kotlin.test`).

### `fake_display.py` checks (what "no `!! PROTOCOL`" means)

`check_track` requires every `track` field with the right JSON type, a non-empty `title`,
and a `next` key present (null allowed) (`fake_display.py:166-182`). `check_state`
requires `volume` as an int 0–100 and the enum strings (`:185-199`). It warns after 15 s
of silence (`:304`). It also warns if client frames are not masked — the JDK client masks.

## Root cause

Not applicable (feature). The main design question: the ViewModel is the only place that
knows all of "current track + metadata + next track + repeat/shuffle" and owns the
actions. So the link either lives inside the ViewModel's world, or the ViewModel feeds a
separate component a snapshot and receives commands back.

## Impact

Off by default. With the setting off, the change must make zero network calls. With it on,
the risks are: extra CPU from image encoding (once per track change — small), a stuck
send blocking later sends (mitigated by the serial sender plus a send timeout), and noisy
logs while the display is offline (log reconnect failures at debug).

## Open questions

These are protocol-fit questions. Per the task, they go into `MUKK_TASK.md`'s "Open
questions", not into a protocol change. None of them blocks the decision below.

1. **`play_pause` from idle.** `PROTOCOL.md` says: "If idle/stopped with a track
   selected, start playing it." Mukk's `togglePlayPause()` only replays
   `currentTrackPath`; with no current track it does nothing (`MukkViewModel.kt:196-202`).
   The task also says "same as the transport bar". Proposal: follow Mukk's behaviour and
   record the difference in `MUKK_TASK.md`.
2. **`next` under repeat ONE.** `nextTrack()` replays the current track. Proposal: send
   the current track as `next` (that is what it would play).
3. **`next` when the selected folder is not the playing folder.** `nextTrack()` uses the
   selected folder, so a preview does too. This is existing Mukk behaviour, not a
   protocol issue; the preview just mirrors it.
4. **Seek detection.** `PROTOCOL.md` wants `state` "on every seek change", but the
   position changes 5 times per second. Proposal: send `state` when `status`, `volume`,
   `repeat` or `shuffle` changes, or when the position differs from the expected
   extrapolation by more than 1.5 s (covers UI seeks, knob seeks and restarts). Plus the
   5 s heartbeat.

## Options

### A. New module `core:mukklet` + ViewModel wiring (recommended)

- `core:mukklet` (depends on `core:model` only, plus `kotlinx-serialization-json`):
  - `CoverEncoder` — pure functions: decode bytes → center-crop → scale → `mono1` /
    `rgb565` → chunks. Plain `java.awt.image`.
  - `ProtocolMessages` — build `track` / `cover` / `state` JSON, parse `hello` / `cmd`.
    Use the `JsonObject` tree API (`buildJsonObject`, `Json.parseToJsonElement`), so no
    serialization compiler plugin is needed.
  - `DisplayLink` — the WebSocket client: connect loop with backoff, wait for `hello`,
    serial sender, 5 s heartbeat, state-change filter. It takes a
    `StateFlow<NowPlayingSnapshot?>` in and emits `DisplayCommand`s out. It knows nothing
    about the ViewModel.
- `composeApp`: `MukkViewModel` builds the snapshot flow (track data, next preview, cover
  bytes, playback state, repeat/shuffle) and maps `DisplayCommand`s to its own actions.
  Settings follow the usual path. `main.kt` closes the link.
- **Pros:** encoder and protocol code are testable in isolation (a `jvmTest` in the new
  module). Module boundary matches `CLAUDE.md` (non-UI logic in `core:*`). The ViewModel
  change is small and only glue.
- **Cons:** one more module and one new dependency (`kotlinx-serialization-json`). The
  ViewModel grows again (it is already 822 lines). The next-track preview duplicates part
  of `nextTrack()`'s logic — two copies can drift.
- **Risk / blast radius:** new code only, behind a default-off setting. The ViewModel
  constructor gains one parameter (`AppModule.kt`).

### B. Everything inside `composeApp`

- Same classes, but in `composeApp/.../mukklet/`.
- **Pros:** no new module, no build-logic work.
- **Cons:** the encoder tests would be the first tests in `composeApp`, in a module that
  pulls Compose. Mixes non-UI networking into the UI module, against the stated module
  split in `CLAUDE.md`.
- **Risk:** same as A.

### C. Hand-written JSON instead of `kotlinx-serialization-json`

Applies to A or B.

- **Pros:** zero new dependencies.
- **Cons:** outgoing JSON needs correct string escaping (Cyrillic and quotes in tags);
  incoming `hello` / `cmd` need a parser. A hand-rolled parser is exactly the kind of
  code that is wrong in edge cases. Not recommended.

### D. Do nothing / defer

- The ESP32 firmware is not started yet (`../esp32-mukklet/CLAUDE.md`, "Next step").
  Mukk's side can wait until the firmware exists.
- **Pros:** no work until the hardware side proves the protocol.
- **Cons:** the firmware session has no real client to test against; `MUKK_TASK.md` was
  written to let both sides progress in parallel against `fake_display.py`.

**Recommendation: A, with `kotlinx-serialization-json`.** The encoder is the part most
likely to be subtly wrong (byte order, bit order, dither), and A makes it testable
without Compose. The preview-drift con is real; mitigate it by extracting the pure
"pick next index" part of `nextTrack()` into one function that both `nextTrack()` and the
preview call — but only the part needed, and only in the wiring part.

## Decision

**Option A, with `kotlinx-serialization-json`** — approved by the user on 2026-09-27.
The user changed the order: text info (track, state, commands) first, covers last. The
plan below reflects that order.

**One PR for all parts** (user, 2026-09-27). This overrides the usual "every part lands via
its own PR" rule for this doc only. Every part commits to branch
`mukklet/part1-protocol-messages` and updates PR #9
(https://github.com/Grigoriym/Mukk/pull/9). Don't open a new PR, and don't merge #9 until
Part 5 is ticked. Parts still land one per session, each with its own commit(s) and
`Landed:` note.


## Implementation plan

Order set by the user on 2026-09-27: **text info first, covers last.** Until the cover
parts land, Mukk sends `hasCover: false` and `{"type":"cover","trackId":…,"none":true}`
after every `track`. `PROTOCOL.md` allows that ("tag has none, or it failed to decode"),
so every part below is protocol-compliant on its own.

### Part 1 — `core:mukklet` module + protocol messages
- [x] New module `core:mukklet` (registered in `settings.gradle.kts`, `jvmTest` with
  `libs.kotlin.test`). Add `kotlinx-serialization-json` to the catalog.
  `ProtocolMessages`: build `track`, `track: null`, `cover` with `none: true`, `state`;
  parse `hello` and `cmd`, ignoring unknown fields and unknown `type` / `cmd` values.
- Verify: `./gradlew :core:mukklet:jvmTest detekt`. Tests parse the content of
  `../esp32-mukklet/docs/protocol/hello.json` and `cmd.json` (copied into test resources,
  not read across repos). They check that built messages have the same keys and JSON
  types as `track.json`, `track_none.json`, `state.json` and `cover_none.json`, including
  Cyrillic strings and quotes in titles.
- Landed: `kotlinx-serialization-json` 1.11.0, tree API only (no compiler plugin). Tests
  compare built messages to the fixtures by full JSON equality, not only keys and types.
  Fixtures live in `core/mukklet/src/jvmTest/resources/protocol/`. `hello` with an unknown
  or missing cover format parses as `CoverFormat.NONE` (send no art).

### Part 2 — `DisplayLink` WebSocket client (text only)
- [x] Connect loop (backoff 2, 4, 8, 16, 30, 30… s), wait for `hello`, serial sender,
  full resync after every (re)connect (`track` → `cover none` → `state`), 5 s heartbeat,
  state-change filter (Open question 4), `close()`. Input:
  `StateFlow<NowPlayingSnapshot?>`; output: `Flow<DisplayCommand>`. Not wired into the
  app yet.
- Verify: `./gradlew :core:mukklet:jvmTest detekt` for the pure parts (backoff sequence,
  state-change filter). Then a manual run against `fake_display.py` from a throwaway
  `main` in the scratchpad: no `!! PROTOCOL` lines, `state` every 5 s, reconnect after
  killing and restarting the fake display. The agent runs and reads this itself.
- Landed: API is `DisplayLink.start(host, snapshots)` / `stop()` / `close()` plus
  `commands: Flow<DisplayCommand>`; `start()` on a running link replaces it
  (`cancelAndJoin`). The serial sender is one collector coroutine that awaits each send
  (the listener never sends), not a separate `Channel`. Added a 10 s `hello` timeout and a
  10 s send timeout, both of which drop the connection and retry. The heartbeat ticks every
  1 s, so `state` goes out every 5–6 s. `cover` is skipped for `track: null` (no `trackId`)
  and for `hello` `format: "none"`. A change of `next` alone resends `track` + `cover` (changed on
  2026-09-28: now `track` only, see `2026-09-28-mukklet-cover-redraw-on-playlist-switch.md`).
  Pure parts live in `LinkTiming.kt` (`reconnectDelayMs`, `isStateDue`). Added
  `kotlinx-coroutines-core` to the catalog. The manual check ran from a temporary
  `jvmTest` file (no `kotlinc` on this machine), deleted afterwards.

### Part 3 — wire into Mukk + settings + docs (text only)
- [x] `MukkViewModel` builds the snapshot (DB data, or `MetadataReader.read()` fallback
  for unscanned files; next-track preview sharing the pick logic with `nextTrack()`) and
  maps commands to its actions. Settings `mukklet.enabled` / `mukklet.host` through
  `SettingsState` → `PreferencesManager` → `SettingsDialog`. `main.kt` closes the link.
  `CLAUDE.md` documents the module and the keys. Open questions 1–2 recorded in
  `MUKK_TASK.md`.
- Verify: `./gradlew composeApp:jvmMainClasses detekt`, then against the running app with
  `fake_display.py`: track change, pause, seek and volume change each produce the right
  message within ~1 s; no `!! PROTOCOL` lines; reconnect works; with the setting off, no
  connection attempts. The agent checks the output itself. The user confirms that the
  fake-display keys (`p n b + - f r`) do the same as the matching Mukk buttons.
- Landed: the snapshot flow is `WhileSubscribed`, so with the setting off Mukk does no snapshot
  work either. `next` is computed from the path of the *resolved* track, not the player's
  newest path. Otherwise a track change first sent the old track with the new `next`. Host
  edits restart the link after a 500 ms pause in typing. `track.id` is the hex `hashCode()` of
  the path. The fake-display keys were driven by the agent, not the user. The user then
  checked the real device (`mukklet-oled` at `mukklet.local`, `hello` format `none`) with the
  knob and confirmed it works. The ESP32 session recorded Open questions 1–2 in `MUKK_TASK.md`
  and accepted both as is.

### Part 4 — cover encoder
- [x] `CoverEncoder` in `core:mukklet`: decode bytes → center-crop → scale → `mono1`
  (Floyd–Steinberg, MSB = leftmost, 1 = white) or `rgb565` (big-endian) → chunks by
  `maxChunk`. Decode failure → `null` (caller keeps sending `none: true`). Plain
  `java.awt.image`. Not wired in yet.
- Verify: `./gradlew :core:mukklet:jvmTest detekt`. Tests assert: crop of a 200×100 image
  keeps the center; `mono1` of a left-white/right-black 8×1 image is `0xF0`; a solid
  mid-gray dithers to about 50% set bits; `rgb565` of pure red is bytes `F8 00`; 512
  bytes with `maxChunk` 200 → chunks of 200, 200, 112.
- Landed: API is `CoverEncoder.encode(bytes, CoverSpec): ByteArray?` and
  `CoverEncoder.chunks(data, maxChunk)`. `encode` also returns `null` for format `none` and
  for an invalid spec (`w`/`h` ≤ 0, or `mono1` with `w` not a multiple of 8) instead of
  throwing, so a bad `hello` can't kill the link. Decoding uses `ImageIO` (JPEG, PNG, BMP,
  GIF); other formats in tags (e.g. WebP, CMYK JPEG) give `null`. A large downscale halves
  in bilinear steps before the last step, to avoid aliasing. Extra tests: vertical crop,
  `rgb565` byte order for blue and green, full `encode` output size for both formats,
  `null` for non-image bytes.

### Part 5 — send covers
- [x] New `core:scanner` accessor for the raw embedded art bytes. The snapshot carries
  them; `DisplayLink` sends `cover` + binary chunks in the format `hello` asked for, and
  `hasCover` reflects it. `hello` with `format: "none"` → no `cover` messages at all.
  Update `CLAUDE.md`.
- Verify: `./gradlew composeApp:jvmMainClasses detekt`, then the running app against
  `fake_display.py` in both `mono1` 64 and `rgb565 --size 240`: no `!! PROTOCOL` lines,
  PNGs in `/tmp/fake_display/`, a track without art sends `none: true`. Confirm one
  binary frame per chunk (Findings, "Inference") with a temporary `fin`/length print in a
  scratch copy of `fake_display.py`. The user confirms that the PNGs look right.
- Note (2026-09-27, from the `esp32-mukklet` session): the real OLED (128×64 SSD1315) can't
  show covers yet. Its firmware hard-codes `hello` format `none` (`main/proto.c:115`) and
  drops binary frames. `mono1` 64×64 on it is planned but not scheduled. So `fake_display.py`
  is the only cover check for this part; the device check stays text-only (no `cover`
  messages at all, since `hello` says `none`).
- Landed: the accessor is `MetadataReader.readArtworkBytes(file)`. `NowPlayingSnapshot` gained
  `cover: CoverArt?`; `CoverArt` wraps the bytes and compares by identity (a new tag read is a
  new cover; no byte compare, no `ArrayInDataClass` finding). Changed on 2026-09-28 to
  compare by content, see `2026-09-28-mukklet-cover-redraw-on-playlist-switch.md`. The ViewModel reads the art in
  the same `mapLatest` step as the track, so both change together. `DisplayLink` ignores the
  snapshot's `hasCover` and sets it from the encode result. It re-encodes when only `next`
  changes; not cached, as encoding is cheap and runs off the UI thread. The Findings
  "Inference" is confirmed: each `sendBinary(chunk, true)` is one frame with `fin` set
  (`mono1` 64: 1 × 512 B; `rgb565` 240: 28 × 4096 B + 1 × 512 B = 115200). Zero
  `!! PROTOCOL` lines in both runs; a track with no art sent `hasCover: false` and
  `none: true`. The agent viewed the PNGs; the user's own look at them was asked for at
  hand-off.
