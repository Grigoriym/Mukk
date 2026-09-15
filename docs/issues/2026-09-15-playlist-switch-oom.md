# 2026-09-15 — Rapid playlist-tab switching OOMs the app

**Status:** Done — Parts 1-3 each landed but proved insufficient alone on manual (stress-test)
verification; Part 4 (join-based serialization) closed the gap and is confirmed working under an
aggressive rapid-click test with no `OutOfMemoryError` and a stable heap
**Link:** logged in `docs/revisit.md` while manually verifying
`docs/issues/2026-09-15-playlist-switch-lag.md` Part 1   **Updated:** 2026-09-15

## Report

Noted by the agent (not the user) during manual verification of the playlist-switch-lag fix, in
`docs/revisit.md`: "clicking between two playlist tabs several times in quick succession (one of
them the ~50k-file 'Default' library) crashed the whole JVM." `~/.local/share/mukk/mukk.log` and
the `:composeApp:run` console both end in `java.lang.OutOfMemoryError: Java heap space`, thrown
from the AWT event thread, a `TimerQueue` uncaught-exception handler, and a
`DefaultDispatcher-worker` running `FileScanner.scan()` (`FileScanner.kt:29` at the time) — i.e.
it happened mid-scan, not on startup.

Environment: real "Default" playlist linked to `/media/gregory/g/music`, 50,535 audio files
(measured in the linked issue's Finding 1). Not stated: exact number of clicks or click interval
needed to trigger it, or whether a smaller playlist can trigger it at all — reproduction was
qualitative ("several times in quick succession"), not a scripted, counted repro.

The revisit note already names a suspected cause and a suggested fix (store the `Job`, cancel any
in-flight one, mirroring `waveformJob`). Treating that as a hypothesis to verify, not a given.

## Findings

1. **`activatePlaylist()` launches an uncancelled, unstored coroutine on every call.**
   `MukkViewModel.kt:525-551`. The whole body — cached-entries paint, `fileScanner.scan()`,
   reconcile repaint, `startWatching()` — runs inside one `viewModelScope.launch { ... }` whose
   returned `Job` is discarded. Contrast `waveformJob` (`MukkViewModel.kt:614-617`,
   `loadWaveform()`: `waveformJob?.cancel()` before assigning a new one) and
   `watcherCollectionJob`/`pendingChangedDirs`/`pendingDeleteJob` (`MukkViewModel.kt:704-717,
   719-756`), all of which cancel their predecessor before starting a replacement. Verified this
   is still true after the playlist-switch-lag fix landed — that fix (Part 1: `2026-09-15-
   playlist-switch-lag.md`) changed what the *second* walk inside this same launch does, not
   whether the launch itself is guarded.

2. **The UI has no debounce, but it does have a same-tab guard — which doesn't stop the repro.**
   `PlaylistTabBar.kt:89`: `onClick = { if (playlist.id != activePlaylistId) onSelectPlaylist
   (playlist.id) }`. This only blocks re-clicking the *already-active* tab. `_activePlaylistId.value
   = playlist.id` (`MukkViewModel.kt:526`) is set **synchronously**, at the top of
   `activatePlaylist()`, before any suspending work starts. So clicking A → B → A in quick
   succession reads as three distinct clicks to the guard (id-vs-current-active always differs at
   click time, since the active id flips immediately on each call) and fires three independent,
   unguarded `activatePlaylist()` calls — each starting its own scan of up to a 50k-file folder,
   running concurrently with the others.

3. **Each concurrent call retains a real, multi-tens-of-MB working set for its full duration, not
   just a brief spike.** For the 50,535-file/142,980-row library measured in the linked issue,
   one `activatePlaylist()` call transiently holds, all at once and all alive for the scan's whole
   duration (not momentarily): `trackRepository.findByPathPrefix(browsePath)` at
   `MukkViewModel.kt:537` (first paint), `FileScanner.scan()`'s own
   `findByPathPrefix(...).associateBy { it.filePath }` at `FileScanner.kt:28-29` (into a
   `Map<String, MediaTrackData>`, held in a local across the entire per-file loop at `:32-35`),
   and a second `findByPathPrefix` call at `MukkViewModel.kt:545` for the post-scan repaint —
   three separate `List<MediaTrackData>`/`Map<String, MediaTrackData>` copies of a
   17-field-ish-sized data class (`MediaTrackData.kt:3-18`: 7 `String` fields including the full
   `filePath`, plus 8 numeric fields), each sized to the whole scanned folder. This is a
   correctness-relevant memory cost regardless of concurrency — Finding 1 is what turns it into an
   OOM: with no cancellation, N rapid clicks keep N of these working sets alive **simultaneously**,
   because each coroutine holds its own local references for as long as it runs, and nothing stops
   an earlier one from still running when a later one starts.
   *(Not separately re-measured this session — the linked issue already profiled the DB/walk cost
   in detail; this finding is about concurrency multiplying an already-known per-call cost, not
   about that cost being new.)*

4. **The heap ceiling is small relative to that per-call cost.** `composeApp/build.gradle.kts:47-
   48`: `jvmArgs += listOf("-Xmx512m", ...)`. A handful of concurrent 50k-entry scans, each
   plausibly in the tens-of-MB range per Finding 3, is enough to exhaust a 512MB heap alongside
   everything else already resident (Compose UI, GStreamer bindings, the JVM's own baseline
   footprint). This is inference from the numbers above, not a measured allocation profile —
   reproducing under a heap-dump/profiler wasn't attempted this session (see Open questions).

5. **Cancellation is safe to add where it takes effect at all — nothing in the launch body depends
   on running to completion.** `fileScanner.scan()` (`FileScanner.kt:19`:
   `withContext(Dispatchers.IO)`) and every `TrackRepository` call it makes are ordinary suspend
   functions with no `withContext(NonCancellable)` guards, so a `Job.cancel()` unwinds cleanly at
   an actual suspension point — no partial-write corruption, since `scanSingleFile()`'s writes
   (`FileScanner.kt:56-105`) are per-file and idempotent (a cancelled-mid-scan folder just gets
   the remaining files picked up on the next scan of it, identical to today's already-existing
   "scan interrupted by app close" case). The `try { ... } finally { _scanProgress.value =
   ScanProgress() }` at `MukkViewModel.kt:540-548` also runs correctly on cancellation — the
   `finally` block's body is a plain `StateFlow` assignment, not a suspending call, so it doesn't
   need `NonCancellable` to execute during unwind. **This finding turned out to be necessary but
   not sufficient — see Finding 7, added after Part 1 was implemented and failed manual
   verification.**

7. **(Added after Part 1's manual verification failed.) `FileScanner.scan()`'s hot loop has no
   cancellation checkpoint, so `Job.cancel()` on an in-flight scan of an already-known folder does
   not stop it promptly — or, in practice, at all before it finishes.** Kotlin coroutine
   cancellation is cooperative: a `Job.cancel()` only takes effect the next time the cancelled
   coroutine hits an actual suspension point (a real dispatcher hand-off, or an explicit
   `ensureActive()`/`yield()` check) — a purely synchronous stretch of code between suspension
   points runs to completion regardless of the cancel request. `scanSingleFile()`'s "file already
   known and unchanged" branch (`FileScanner.kt:56-66`, the `existing != null && !needsRescan`
   path) does exactly this: `MukkLogger.debug(...)` (`MukkLogger.kt:29`, a plain synchronous
   function, not `suspend`) followed by `return false` — no suspend call anywhere in that branch.
   For a folder that's already been scanned before (the realistic case for a repeat playlist
   switch — exactly what the OOM repro exercises), nearly every file in
   `audioFiles.forEachIndexed { ... }` (`FileScanner.kt:32-35`) takes this branch, so the entire
   50,535-iteration loop can run start-to-finish inside one `withContext(Dispatchers.IO)` slice
   without ever yielding back to the dispatcher or checking `isActive`. Part 1's
   `activatePlaylistJob?.cancel()` therefore only prevents the *ViewModel* from starting a second
   concurrent scan launch — it does not stop an already-running `fileScanner.scan()` call, which
   keeps executing (and keeps holding its full `audioFiles` list and `existingByPath` map) exactly
   as if it had never been cancelled.
   **Verified by reproducing:** built Part 1, launched the app (`./gradlew :composeApp:run`), and
   scripted 20 rapid alternating clicks (`xdotool`, ~150ms apart) between the real "Default"
   playlist (50,535 files, already fully scanned from prior sessions) and the small "Boss"
   playlist. `~/.local/share/mukk/mukk.log` shows repeated, identical "Skipping unchanged file"
   log bursts for the same 10 Boss files roughly every 2.5-3 seconds for over a minute *after* the
   click script had finished — consistent with input events backed up in the AWT queue behind a
   stalled/GC-thrashing EDT, each one still launching (and Part 1's `cancel()` failing to actually
   stop) a full, uninterruptible scan. The run ended in the same `OutOfMemoryError: Java heap
   space` pattern as the original report, and the JVM process was gone afterward (confirmed via
   `ps aux`) — Part 1 did not fix the crash.

8. **A second, smaller correctness issue rides along with the same root cause.** With N concurrent
   `activatePlaylist()` coroutines, the last one to reach each `_selectedFolderEntries.value = ...`
   or `startWatching(playlist.folderPath)` assignment "wins," regardless of which playlist tab is
   actually showing as active. A user landing on tab A after clicking through B and back to A
   could see B's watcher left active, or a stale/mixed entries list, depending on completion
   order — a real bug, but secondary to the OOM and fixed by the same coroutine-cancellation
   change (Option below), not a separate change.

9. **(Added after Part 2's manual verification came back partial.) A single `findByPathPrefix()`
   call is itself a non-interruptible unit of work, and nothing before Part 3 bounded how many of
   those could be concurrently in flight.** `activatePlaylist()`'s first-paint step
   (`MukkViewModel.kt:540` at the time) and `FileScanner.scan()`'s own existing-tracks fetch
   (`FileScanner.kt:28`) each call `TrackRepository.findByPathPrefix()`
   (`TrackRepository.kt:136-142`) — a single `transaction(databaseInit.database) { ... }` block
   wrapping one Exposed `find()` call plus `.map { it.toData() }` over up to ~50,535 rows. Like
   the scan loop before Part 2, this has no internal suspension point of its own; unlike the scan
   loop, Part 2's `ensureActive()` doesn't cover it, because it runs *before* the loop even
   starts. If a new click arrives while a previous `findByPathPrefix()` call for a large folder is
   still executing, `Job.cancel()` on the previous coroutine cannot interrupt that call — it must
   run to completion (fully materializing its `List<MediaTrackData>`) before the cancellation
   exception can even be thrown. Rapid enough clicks can therefore still have more than one such
   call — each holding a full-folder `List<MediaTrackData>` — resident at the same time.
   **Verified by reproducing:** with Part 1 and Part 2 both landed, ran a more aggressive scripted
   test (`xdotool`, 20 alternations, ~80ms apart — faster than the Part 2 test, chosen to stress
   this specific gap) between "Default" and "Boss." The JVM process survived, but
   `mukk_run3.log` (this session's scratch capture of the run's stdout/stderr) shows 4
   `OutOfMemoryError`s, one of them inside `TrackRepository$findByPathPrefix$2` →
   `MediaTrackEntity.toData()` → `Entity.getValue()` → `ResultRowCache.cached()` — i.e. inside the
   bulk-fetch/mapping call itself, not the scan loop. `jcmd <pid> GC.heap_info` immediately after
   showed the heap pinned at 479MB/512MB. The process was technically alive but the Compose UI was
   left unresponsive (confirmed live by the user: "app is kinda responsive, for sure i cannot
   select non selected playlist, but no crashes") — a wedged app is not meaningfully better than a
   crashed one from a user's perspective, even though the OS process survives.
   *(Side observation, not acted on: the same stack trace shows Exposed's DAO `Entity` property
   access allocating a `Pair` per field read for its `ResultRowCache` key — `TuplesKt.to()` called
   from `ResultRowCache.key()` on every `MediaTrackEntity.getDiscNumber()`-style access. For a
   ~50k-row × ~9-field mapping that's several hundred thousand small allocations per call, real
   GC-pressure overhead independent of concurrency — but reducing that cost wasn't needed once
   Part 4 bounded concurrency to begin with, so it wasn't pursued.)*

10. **(Added after Part 3's manual verification also came back insufficient.) A fixed-time
    debounce bounds click *rate*, not call *overlap* — it cannot guarantee no overlap when a
    single call can take longer than the debounce window.** A 300ms debounce
    (`PlaylistTabBar.kt`, Part 3) limits how often a *new* switch can start, but does nothing to
    stop two switches from overlapping if one `findByPathPrefix()` call (Finding 9) takes longer
    than 300ms to return — and under GC pressure from a heap already near its ceiling, it can.
    **Verified by reproducing:** with Parts 1-3 landed, re-ran an even more aggressive test
    (`xdotool`, 40 alternations, ~80ms apart, i.e. ~12.5 clicks/sec sustained for ~6.4s — well
    beyond realistic human click speed, chosen deliberately as a worst-case stress test) between
    "Default" and "Boss." Same outcome as Finding 9: process survived, but `mukk_run3.log` logged
    4 `OutOfMemoryError`s and the heap peaked near its ceiling before the app's Compose UI became
    unresponsive to further clicks. The only change that actually closed this: instead of merely
    *requesting* cancellation of the previous switch and racing ahead, make the new switch's
    coroutine `cancelAndJoin()` (suspend until the previous one has fully stopped) before starting
    its own `findByPathPrefix()` call — this strictly serializes the non-interruptible bulk-fetch
    phases, so at most one is ever in flight regardless of click rate (Part 4). Re-ran the same
    40-alternation/80ms test after this change: zero `OutOfMemoryError`s in the run log, heap
    polled every 5s for 50s post-test stayed flat at ~314MB (well under the 512MB ceiling, and
    far below the 479-515MB peaks seen with Parts 1-3 alone), process alive and UI responsive to a
    subsequent deliberate single click (instant, correct switch, no regression versus the
    original single-click behavior).

## Root cause

Three compounding causes, discovered incrementally as each partial fix was stress-tested and
found wanting:

1. `MukkViewModel.activatePlaylist()` started a new, independent `viewModelScope.launch` on every
   call without cancelling any still-running call from a previous invocation (Finding 1). The
   UI's only guard (`PlaylistTabBar.kt:89`) compares against `activePlaylistId`, which flips
   synchronously before the scan even starts, so it did not prevent overlapping scans across a
   rapid sequence of different-tab clicks (Finding 2).
2. Adding cancellation (Part 1) alone wasn't enough: `FileScanner.scan()`'s per-file loop had no
   cancellation checkpoint in its most common branch (Finding 7) — an already-scanned folder's
   "unchanged, skip" path never suspends, so `Job.cancel()` didn't actually stop it.
3. Even with a loop checkpoint (Part 2) and a UI debounce (Part 3), the bulk
   `TrackRepository.findByPathPrefix()` call itself — used both for the instant first-paint and
   inside `FileScanner.scan()` — is one atomic, non-interruptible unit of work (Finding 9).
   Neither cancellation nor a time-based debounce can prevent two such calls from overlapping if
   one takes longer than the gap between clicks (Finding 10); only strictly waiting for the
   previous switch to fully stop before starting the next one's fetch (Part 4) closes that gap
   completely.

Each overlapping/not-fully-stopped call independently holds a `MediaTrackData` copy of a large
playlist's folder for its full duration (Finding 3); enough of them against a big enough playlist
exceeds the app's `-Xmx512m` heap ceiling (Finding 4), throwing `OutOfMemoryError` on whichever
thread happens to allocate next — which is why the report and this issue's own reproductions saw
the crash surface from `FileScanner.scan()`, `TrackRepository.findByPathPrefix()`, the AWT
thread, and a `TimerQueue` handler in different runs: it's wherever the heap happened to run out,
not a defect specific to any one of those call sites.

## Impact

Anyone who switches playlist tabs a few times in quick succession, where at least one of the
playlists involved is large (the user's real "Default" playlist at 50,535 files is well within
range), can crash the whole application with data loss of unsaved... nothing persistent is lost
(DB writes are idempotent, position/volume persist on the next clean close only), but the app
itself dies and has to be relaunched. No workaround short of "don't click playlist tabs quickly,"
which isn't a reasonable ask of a UI that's supposed to feel instant (the entire point of the
linked playlist-switch-lag fix).

## Open questions

- Exact click count/interval needed to trigger the crash wasn't measured (reproduced
  qualitatively, per the original revisit note, not via a scripted/counted repro) — doesn't block
  a decision, since the fix (don't allow more than one in-flight `activatePlaylist()` coroutine)
  removes the failure mode regardless of the exact threshold.
- Finding 4's "tens of MB per concurrent call" is inferred from `MediaTrackData`'s shape and row
  count, not measured with a profiler/heap dump. A precise number isn't needed to justify the fix
  (Finding 1 alone is a real bug — unbounded concurrent duplicate work — independent of exactly
  how many MB each instance costs), but it's the reason "why 512MB specifically" isn't fully
  nailed down.
- Whether to also raise `-Xmx` as defense-in-depth is a separate question from fixing the
  unbounded-concurrency bug — noted here, not decided. Raising the ceiling alone wouldn't fix
  Finding 6's stale-state race, and without also fixing Finding 1, a large enough click-through of
  playlists (or larger playlists in the future) would eventually exhaust any fixed ceiling.

## Options

### Option A — Cancel the in-flight `activatePlaylist()` job before starting a new one (recommended)
Store the `Job` returned by `activatePlaylist()`'s `viewModelScope.launch { ... }` in a new
`private var activatePlaylistJob: Job? = null` field, and call
`activatePlaylistJob?.cancel()` before launching, exactly mirroring the existing `waveformJob`
pattern (`MukkViewModel.kt:614-617`).

- **Pros:** matches an established, already-reviewed pattern in this same file; removes the
  unbounded-concurrency root cause directly (Finding 1); at most one scan's working set is ever
  resident, capping memory regardless of click speed; also fixes Finding 6's stale-watcher/stale-
  entries race as a side effect, since only the latest call's completion can ever write the
  `StateFlow`s or call `startWatching()`.
- **Cons:** a playlist whose reconcile scan gets cancelled mid-way needs a later switch back to
  finish reconciling it (same as today's "app closed mid-scan" case, not a new failure mode, but
  worth naming); doesn't reduce the per-call memory cost itself (Finding 3) — a single very large
  playlist opened once could still be a heavy single scan, just no longer multiplied by
  concurrency.
- **Risk:** low. **Blast radius:** `activatePlaylist()` only; all four call sites
  (`restoreActivePlaylist`, `selectPlaylist`, `createPlaylist`, `deletePlaylist`'s neighbor-
  activation branch) get the fix automatically since they all funnel through the same private
  function.

### Option B — Debounce playlist-tab clicks in the UI
Add a short debounce (e.g. only act on a click if the previous one was >N ms ago) in
`PlaylistTabBar.kt` before calling `onSelectPlaylist`.

- **Pros:** simple, UI-only change.
- **Cons:** treats the symptom, not the cause — doesn't fix Finding 6's race, and any
  debounce window either lets a fast-enough user still trigger overlapping scans (window too
  short) or makes legitimately-fast intentional switching feel laggy (window too long — the
  opposite of what the playlist-switch-lag fix was for). Doesn't help the non-UI call sites
  (`restoreActivePlaylist` on startup, `deletePlaylist`'s auto-activation of a neighbor), which
  can't be "clicked quickly" but could still overlap under other timing (e.g. deleting playlists
  in a scripted/batch way).
- **Risk:** low technically, but doesn't address the actual defect. **Blast radius:**
  `PlaylistTabBar.kt` only.

### Option C — Both A and B
Debounce is a reasonable UX nicety independent of the correctness fix, but not required to close
this issue.

### Recommendation
**A.** It fixes the actual root cause (unbounded concurrent coroutines, Finding 1) using a pattern
already established and working elsewhere in this file (`waveformJob`), fixes Finding 6 for free,
and needs no tuning (no debounce window to pick). B is optional UX polish that could be added
later if rapid-click feel is still a concern after A, but isn't a substitute for it.

## Decision

User approved **Option A** (2026-09-15): cancel any in-flight `activatePlaylist()` job before
starting a new one, mirroring the existing `waveformJob` pattern. Option B (UI debounce) was not
chosen initially — left as optional future polish, not required.

**Revised as each partial fix was stress-tested and found insufficient (all same day, 2026-09-15,
user informed and re-consulted at each step — see Findings 7, 9, 10):**
- After Part 1 alone failed manual verification (Finding 7): proceeded directly to Part 2
  (cancellation checkpoint in the scan loop) as the obvious completion of the originally-approved
  direction, not a new decision.
- After Part 2 also proved only partially sufficient (Finding 9 — process survived instead of
  dying, but still threw `OutOfMemoryError` and left the UI wedged): presented the finding and
  two options (add a UI debounce, or speed up `findByPathPrefix()` itself, or both). User chose
  **debounce** (2026-09-15) → Part 3.
- After Part 3's debounce also proved insufficient under an aggressive stress test (Finding 10 —
  a fixed-time debounce bounds click rate, not call overlap): presented the finding and proposed
  **join-based serialization** (`cancelAndJoin()` instead of `cancel()`) as the fix that actually
  guarantees no overlap regardless of click speed, keeping the debounce as harmless additional UX
  smoothing. User approved (2026-09-15) → Part 4, confirmed working under the same aggressive
  test with no `OutOfMemoryError` and a stable ~314MB heap (see Finding 10).

## Implementation plan

### Part 1 — Cancel the in-flight `activatePlaylist()` job before starting a new one [x]
- Add `private var activatePlaylistJob: Job? = null` alongside the other job fields
  (`MukkViewModel.kt:91-96`, next to `waveformJob`).
- In `activatePlaylist()` (`MukkViewModel.kt:525-551`), call `activatePlaylistJob?.cancel()`
  immediately before `viewModelScope.launch { ... }`, and assign the returned `Job` to
  `activatePlaylistJob` instead of discarding it — same shape as `loadWaveform()`
  (`MukkViewModel.kt:614-617`).
- **Verify:** `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` compiles clean. Manual:
  in the running app, click rapidly back and forth between two playlist tabs (one of them a large
  library) several times in a few seconds — confirm the app stays up (no `OutOfMemoryError` in
  `mukk.log`/console) and that the tab you land on ends up showing the correct, non-stale entries
  and watcher once things settle.
- **Landed:** the code change itself landed exactly as planned. `activatePlaylistJob` field added
  next to `waveformJob` (`MukkViewModel.kt:97`); `activatePlaylist()` now cancels it before
  launching and stores the new `Job` (`MukkViewModel.kt:537-538`).
  `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` ran clean.
  **Manual verify failed:** a scripted rapid-click test (`xdotool`, 20 alternations, ~150ms apart,
  between "Default" and "Boss") still crashed the app with `OutOfMemoryError` — see Finding 7.
  `Job.cancel()` does not preempt `FileScanner.scan()`'s hot loop, so this change alone does not
  fix the reported bug. Not reverted (it's still correct and needed — Part 2 builds on it — and it
  does fix Finding 8's stale-state race on its own), but the issue stays open pending Part 2.

### Part 2 — Give `FileScanner.scan()`'s hot loop a cancellation checkpoint [x]
- In `FileScanner.scan()` (`FileScanner.kt:16-37`), call `ensureActive()` (from
  `kotlinx.coroutines.ensureActive`, available on the `CoroutineScope` that `withContext(Dispatchers.IO) { ... }`
  provides as the implicit receiver) once per iteration inside `audioFiles.forEachIndexed { ... }`,
  before `scanSingleFile(...)`. This makes cancellation actually observable in the branch that
  currently has zero suspension points (Finding 7), at negligible cost (`ensureActive()` is a
  cheap `isActive` check, not a suspension) — the "needs rescan" branch already suspends via
  `metadataReader.read()`/repository writes, so it doesn't need this, but the far more common
  "unchanged, skip" branch does.
- No change to `scanFolder()` (`FileScanner.kt:39-50`, the watcher's single-directory scan) —
  it's called on small, individually-changed directories, not the large recursive walk this issue
  is about; out of scope.
- **Verify:** `./gradlew :core:scanner:compileKotlinJvm :core:scanner:detekt
  :composeApp:jvmMainClasses :composeApp:detekt` compiles clean. Manual: rebuild, relaunch, repeat
  the same scripted rapid-click test used to catch Part 1's gap (`xdotool`, ~20 alternations
  between "Default" and a small playlist, ~150ms apart) — confirm no `OutOfMemoryError` in
  `mukk.log`/console, the process stays alive (`ps aux` after the test), and the final-landed tab
  shows correct, non-stale entries and watcher.
- **Landed:** the code change landed exactly as planned — `ensureActive()` added inside
  `FileScanner.scan()`'s `audioFiles.forEachIndexed { ... }`, before `scanSingleFile(...)`
  (`FileScanner.kt:33`). `./gradlew :core:scanner:compileKotlinJvm :core:scanner:detekt
  :composeApp:jvmMainClasses :composeApp:detekt` ran clean.
  **Manual verify: partial.** Rebuilt, relaunched, repeated the Part-1-gap-catching test
  (`xdotool`, 20 alternations, ~150ms apart). This time the JVM process itself **survived**
  (unlike Part 1 alone, where the whole process died) — a real improvement, since the scan loop
  no longer runs to completion unconditionally. But the run still logged four
  `OutOfMemoryError`s (`AWT-EventQueue-0`, `DefaultDispatcher-worker-9`) and the heap settled at
  479MB/512MB — see Finding 9. The gap this exposed: `ensureActive()` only guards the loop
  *inside* `FileScanner.scan()`; it does nothing for the bulk `findByPathPrefix()` call that
  precedes the loop (and the one in `activatePlaylist()`'s own first-paint step), which is a
  single non-suspending unit of work cancellation cannot preempt. Not reverted — still correct
  and still needed (it's what let the process survive at all instead of dying outright) — but
  insufficient alone. Issue stayed open pending Part 3.

### Part 3 — Debounce playlist-tab clicks in the UI [x]
- In `PlaylistTabBar.kt`, add a `SELECT_DEBOUNCE_MS = 300L` constant and a
  `lastSelectTime` (`mutableLongStateOf`) remembered across the whole tab bar. Gate each tab's
  `onClick` on both the existing same-tab guard and `now - lastSelectTime >= SELECT_DEBOUNCE_MS`,
  updating `lastSelectTime` only when a switch is actually accepted.
- **Verify:** `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` compiles clean. Manual:
  repeat the Finding-9-reproducing stress test (`xdotool`, aggressive alternation) — confirm no
  `OutOfMemoryError` and a healthy heap.
- **Landed:** exactly as planned (`PlaylistTabBar.kt`: `SELECT_DEBOUNCE_MS` constant,
  `lastSelectTime` state, debounce check added to each tab's `onClick`).
  `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` ran clean.
  **Manual verify failed:** re-ran an even more aggressive stress test (`xdotool`, 40
  alternations, ~80ms apart) — still 4 `OutOfMemoryError`s, heap peaked near the 512MB ceiling,
  and the user confirmed live that the UI became unresponsive to further clicks (could not select
  a non-active playlist) even though the process itself stayed alive — see Finding 10. Not
  reverted — harmless as additional UX smoothing for the normal case — but insufficient alone.
  Issue stayed open pending Part 4.

### Part 4 — Serialize playlist switches with `cancelAndJoin()` instead of `cancel()` [x]
- In `activatePlaylist()` (`MukkViewModel.kt`), capture the outgoing job as `previousJob` before
  reassigning `activatePlaylistJob`, and have the new coroutine's first line be
  `previousJob?.cancelAndJoin()` (suspends until the previous switch has fully stopped, not just
  requested-to-stop) before doing any of its own `findByPathPrefix`/scan work. Requires
  `import kotlinx.coroutines.cancelAndJoin`.
- **Verify:** `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` compiles clean. Manual:
  repeat the same aggressive stress test that broke Part 3 (`xdotool`, 40 alternations, ~80ms
  apart) — confirm zero `OutOfMemoryError`s, a stable/bounded heap (`jcmd <pid> GC.heap_info`
  polled over the following ~50s), the process alive, and a subsequent deliberate single click
  still switches instantly and correctly (no regression on the common case).
- **Landed:** exactly as planned (`MukkViewModel.kt`: `previousJob` captured,
  `previousJob?.cancelAndJoin()` as the first line of the new launch's body).
  `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` ran clean.
  **Manual verify passed:** re-ran the 40-alternation/~80ms stress test — zero
  `OutOfMemoryError`s in the run log; `jcmd <pid> GC.heap_info` polled every 5s for 50s afterward
  stayed flat at ~314MB (well under the 512MB ceiling, far below the 479-515MB peaks seen with
  Parts 1-3 alone); process alive throughout. A subsequent deliberate single click on an inactive
  tab switched instantly with correct, consistent folder tree/track list/watcher state — no
  regression versus the pre-existing single-click behavior. (One apparent inconsistency during
  the stress-test screenshots — the highlighted tab not matching the displayed folder/tracks —
  turned out to be the pre-existing, already-logged active-tab-contrast issue from
  `docs/revisit.md`, not a new data bug: the underlying state was internally consistent
  throughout, confirmed by cross-checking `mukk.log`'s final `FileSystemWatcher` line against the
  displayed content.)
