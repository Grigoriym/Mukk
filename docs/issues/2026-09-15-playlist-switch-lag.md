# 2026-09-15 — Playlist switching is slow on a large library

**Status:** Approved
**Link:** follow-up to `docs/issues/2026-09-15-playlists-and-queue.md` (Part 4, landed same day)   **Updated:** 2026-09-15

## Report

User, in their own words: "there is a kinda considerable delay when I change the playlist, the
default one consists of this folde /media/gregory/g/music which contains a lot of songs and it
seems we rescan everything on playlist change."

Environment: the user's real "Default" playlist, linked to `/media/gregory/g/music`. Not stated:
exact delay length, or whether it was tried on a small playlist too (open question below).

The playlist feature's own design doc chose "Option B — cached snapshot first, reconcile in the
background" specifically so switching would feel instant even on a large folder, and its Finding 7
already named the risk being reported here: "Rescanning an already-known folder is not free, even
though it skips the expensive part... on a folder with thousands of files, the walk-plus-per-file-
query cost alone is enough to make 'just rescan on every switch' feel slow." The user's report is
that risk materializing, not a surprise unrelated to the design — the question is exactly which
part of the implementation is responsible and how badly.

## Findings

1. **The library is large enough for O(N) per-file DB work to matter.** `find
   /media/gregory/g/music -type f <audio extensions>` returns **50,535** files. `sqlite3
   library.db "SELECT COUNT(*) FROM media_tracks"` returns **142,980** rows, all under that one
   path (`... WHERE file_path LIKE '/media/gregory/g/music/%'` returns the same count). This is
   the exact scale Finding 7 warned about, not a hypothetical.

2. **`activatePlaylist()` does the disk-walk-plus-per-file-DB-query work twice, not once.**
   `MukkViewModel.kt:548-574`, after showing the cached list, runs `fileScanner.scan(...)`
   (`core/scanner/.../FileScanner.kt:15-33`) and then calls `loadSelectedFolderEntries(browsePath)`
   (`MukkViewModel.kt:658-684`). Both independently call `dir.walkTopDown()` over the same tree,
   and both issue one `trackRepository.findByPath()` call per audio file
   (`FileScanner.kt:52` inside `scanSingleFile`; `MukkViewModel.kt:719-720` via `lookupTrackData`
   inside `loadSelectedFolderEntries`'s `.map`). For this library that's roughly 2 × 50,535 ≈
   **100,000+ sequential single-row DB lookups** on every switch to or from this playlist — even
   though `TrackRepository.findByPathPrefix()` (`core/data/.../TrackRepository.kt:136-142`) was
   built in Part 1 specifically to answer "what's in this folder" with **one** bulk query, and is
   already used for the *first*, instant paint two lines earlier (`MukkViewModel.kt:560`). The
   post-reconcile refresh just doesn't reuse it.

3. **The second walk runs on the UI thread, not just redundantly but blockingly.**
   `loadSelectedFolderEntries()` has no `withContext(Dispatchers.IO)` wrapper — its
   `dir.walkTopDown().filter{...}.toList()` (`MukkViewModel.kt:664-666`) is synchronous, blocking
   file I/O. It's called from inside `viewModelScope.launch { ... }` (`MukkViewModel.kt:559-573`),
   immediately after `fileScanner.scan()`'s own `withContext(Dispatchers.IO)` block
   (`FileScanner.kt:18`) has already returned — i.e. control is back on `viewModelScope`'s own
   dispatcher when the walk runs. Decompiling `lifecycle-viewmodel-desktop-2.9.4.jar`
   (`androidx.lifecycle.viewmodel.internal.CloseableCoroutineScopeKt.createViewModelScope()`)
   confirms `viewModelScope` is built from `Dispatchers.Main.immediate` (falling back to
   `EmptyCoroutineContext` only if no `Main` dispatcher is registered). `composeApp` depends on
   `kotlinx-coroutines-swing` (`composeApp/build.gradle.kts:38`), which registers `Dispatchers.Main`
   backed by the **Swing/AWT Event Dispatch Thread** — the same thread Compose Desktop renders on
   and dispatches input through. So the second, redundant walk runs directly on the UI thread and
   blocks it for as long as it takes to walk + look up ~50,535 files, once per switch.

4. **Each DB lookup is not just a query — Exposed opens a fresh JDBC connection per call.**
   `DatabaseInit.kt:17-20` calls `Database.connect(url = ..., driver = ...)` with a bare URL and
   driver name, not a `DataSource`/connection pool. Decompiling `exposed-jdbc-1.0.0.jar`
   (`Database$Companion.connect$lambda$7`) shows this overload's connector function calls
   `java.sql.DriverManager.getConnection(url, user, password)` directly — there is no pooling
   layer in front of it, so this is the function Exposed's transaction manager calls to obtain a
   connection for each `transaction(databaseInit.database) { ... }` block (every `TrackRepository`
   method wraps its body in exactly this call). A proxy measurement — 200 separate `sqlite3`
   process invocations against the real `library.db`, one indexed point-lookup each — took 316ms
   total, **~1.6ms per call**, versus ~9µs per lookup for the same 5,000 lookups run inside one
   persistent connection (`.timer on` in one `sqlite3` session: 45ms / 5,000 ≈ 9µs each). The raw
   SQL execution is trivial; per-call connection setup is not. This is a proxy (process-spawn
   overhead isn't identical to in-process JDBC connection setup) but the ~150-200× gap between
   "reuse a connection" and "open one per call" is the right order of magnitude to explain a
   multi-second-to-multi-minute stall across ~100,000 calls.

5. **The `findByPathPrefix()` bulk query itself is not the problem.** `EXPLAIN QUERY PLAN` on
   `SELECT * FROM media_tracks WHERE file_path LIKE '/media/gregory/g/music/%'` shows a full
   `SCAN media_tracks` (SQLite can't use the `file_path` index for a `LIKE` pattern without
   `case_sensitive_like` or a `NOCASE`-free guarantee) — but timed at **11ms** for all 142,980
   rows. Not a contributor worth fixing.

## Root cause

`MukkViewModel.activatePlaylist()` refreshes the visible track list after the background reconcile
scan by calling the general-purpose, disk-walking `loadSelectedFolderEntries()` instead of the
bulk, DB-only `findByPathPrefix()` it already uses for the first paint. On a library this size that
duplicates ~50,000 walk-and-lookup iterations that `fileScanner.scan()` just did, runs the walk
half of that duplicate work directly on the Swing EDT (no `withContext(Dispatchers.IO)`), and each
of the ~100,000 total per-file DB calls opens its own fresh JDBC connection (`Database.connect`
here has no pool) — individually cheap in principle (Finding 5), but not at this multiplier
(Finding 4). The reconcile scan itself (`fileScanner.scan()`, correctly off the EDT) still pays the
~50,000-connection cost too; it just doesn't freeze the window while doing it.

## Impact

Anyone with a playlist backed by a folder with many thousands of files hits a real, multi-second
(plausibly much longer, per the extrapolation in Finding 4) freeze on every switch to or from it —
the exact scenario Option B was chosen to protect against. Small playlists are unaffected in
practice; the user's own "Default" playlist, at 50,535 files, is squarely in the affected range.
`scanDirectory()`, `selectFolder()`, `restoreFolderTreeState()`, and the `AudioFileChanged` watcher
handler all call the same un-dispatched `loadSelectedFolderEntries()` (`MukkViewModel.kt:125,
172-173, 458, 755`) — this is not new to playlists, but playlist switching is the first workflow
built to be done repeatedly and expected to feel instant, which is what makes a pre-existing cost
suddenly the headline complaint.

## Open questions

- I did not reproduce this by clicking through the running app (creating a second playlist needs
  the native folder picker, which I found unreliable to script safely with `xdotool` in the
  previous session, and forcing it again risked writing a wrong `Playlists` row into the user's
  real database). The evidence above is code-level plus two micro-benchmarks against the real
  `library.db`, not a measured end-to-end wall-clock number for an actual switch. Doesn't block a
  decision, but the exact freeze duration is inferred, not measured.
- Whether to also fix the same `loadSelectedFolderEntries()` dispatch gap at its other three call
  sites (Finding above) is a separate question from the playlist-switch complaint — noted here,
  not decided.

## Options

### Option A — Stop redoing the walk after reconcile (targeted, low risk)
In `activatePlaylist()`, replace the post-scan `loadSelectedFolderEntries(browsePath)` call with
the same `buildCachedEntries(trackRepository.findByPathPrefix(browsePath))` already used for the
first paint (`MukkViewModel.kt:560`). Both build the identical `FileEntry` list shape with the same
sort order (`compareBy parent, thenBy trackNumber, thenBy name` — verified identical between
`buildCachedEntries()` at `MukkViewModel.kt:576-586` and `loadSelectedFolderEntries()` at
`:658-684`).

- **Pros:** removes the EDT-blocking walk entirely; halves the per-file DB round trips per switch
  (only `fileScanner.scan()`'s own lookups remain); small, obviously-correct diff; delivers
  exactly what Option B in the original design doc promised for the *refresh* step.
- **Cons:** doesn't touch `fileScanner.scan()`'s own ~50,000 individual `findByPath` calls — the
  reconcile scan itself, still running in the background, could still take a long time on this
  library (Finding 4's connection-overhead multiplier applies there too), so the "Scanning X / Y"
  indicator could still run for a long while, just without freezing the window. A file present on
  disk but not yet inserted by the scan (e.g. a corrupt file `MetadataReader` can't read, still
  inserted with fallback metadata per `FileScanner.kt:86-100`, or a genuinely new file added mid-
  scan) won't appear until the scan's own DB write lands — same as today's behavior, not a
  regression.
- **Risk:** low. **Blast radius:** `activatePlaylist()` only.

### Option B — Batch the reconcile's "does this file need rescanning" check (root scalability fix)
Change `FileScanner.scan()` to fetch all existing tracks for the target folder **once** via
`trackRepository.findByPathPrefix()` into an in-memory map keyed by path, then have
`scanSingleFile()` take the (possibly-null) already-fetched `MediaTrackData` as a parameter instead
of calling `trackRepository.findByPath()` itself. This cuts the reconcile's DB round trips from
O(files) to O(1) for the read side — the overwhelmingly common case on a stable library, where
almost every file is unchanged and today still costs one fresh-connection lookup apiece.

- **Pros:** actually fixes "switching feels instant" for a library this size, which Option A alone
  does not — the background scan itself would no longer be the long pole. Same fix also helps
  `rescan()` and `scanDirectory()`, which pay the identical per-file cost today.
- **Cons:** touches `FileScanner.scan()`/`scanSingleFile()`'s signature and logic — shared code
  used by every scan path (`scanDirectory`, `rescan`, `activatePlaylist`, `restoreFolderTreeState`),
  so it's a wider blast radius than Option A. Needs care to keep `insertIfAbsent`'s "still missing"
  re-check (`TrackRepository.kt:52-54`, guards against a race where the file was inserted by
  another caller between the batch fetch and the write) — that check stays a per-file DB call only
  for the "file not in the map" branch, i.e. genuinely new files, which is the rare case, not the
  common one.
- **Risk:** medium. **Blast radius:** `FileScanner.scan()`, `scanSingleFile()`, all of that class's
  callers (behavior should be unchanged for them, but it's the same code path).

### Option C — Skip the reconcile scan on switch, rely on the watcher going forward
Only scan a playlist's folder on its very first-ever open (or explicit "Rescan" click); trust
`FileSystemWatcher` for everything after that.

- **Pros:** removes all switch-time scan cost, not just the redundant half.
- **Cons:** walks back a decision the user already confirmed for this feature — "the folder link
  is... live (the playlist reflects the folder's current contents, not a snapshot taken at link
  time)" (`docs/issues/2026-09-15-playlists-and-queue.md`, Report section). A folder changed while
  its playlist wasn't the active/watched one (Option B in that doc only watches one root at a
  time) would show stale contents until the next explicit rescan, not "live." Would need the
  user's explicit sign-off to trade that away, not something to fold into a bug fix.
- **Risk:** low technically, but it's a product-behavior regression, not just a perf fix.
  **Blast radius:** `activatePlaylist()`, and the "live" guarantee documented for the whole
  playlist feature.

### Recommendation
**A, then B.** A is small, low-risk, and should land regardless — it's an outright bug (duplicate
work, one half of it blocking the UI thread) independent of any tradeoff. B is what actually closes
out Finding 7's original risk for a library this size; without it, the background scan alone will
still be slow on this playlist, just no longer freezing. C is left on the table only if the user
decides the "live" guarantee isn't worth the cost for very large folders — not recommended as a
default, since it reverses an explicit prior decision.

## Decision

User approved **A, then B** (2026-09-15): fix the duplicate/blocking walk in `activatePlaylist()`
first (Part 1), then batch the reconcile scan's own per-file DB checks (Part 2). Option C (skip
rescanning on switch) was declined — it would walk back the "playlists stay live" decision from
the original design doc, and wasn't something the user chose to trade away.

## Implementation plan

### Part 1 — Stop redoing the walk after reconcile [ ]
- In `MukkViewModel.activatePlaylist()` (`composeApp/src/jvmMain/kotlin/com/grappim/mukk/MukkViewModel.kt:548-574`),
  replace the post-scan `loadSelectedFolderEntries(browsePath)` call with
  `_selectedFolderEntries.value = buildCachedEntries(trackRepository.findByPathPrefix(browsePath))`
  — the same call already used for the first, instant paint two lines earlier. No other call site
  of `loadSelectedFolderEntries()` changes (Finding above notes three others still have the same
  dispatch gap — out of scope for this issue, which is specifically about playlist switching).
- **Verify:** `./gradlew :composeApp:jvmMainClasses :composeApp:detekt` compiles clean. Manual:
  switch to the "Default" playlist (or any large one) and confirm the window stays responsive
  throughout — no freeze/spinner-cursor period — while the "Scanning X / Y" indicator runs; edit a
  tag on a file in a non-active playlist's folder, switch to it, confirm the change still shows up
  once the background reconcile finishes (proves the refresh still picks up scan results, just via
  the bulk query instead of the walk).

### Part 2 — Batch the reconcile scan's per-file DB check [ ]
- In `FileScanner.scan()` (`core/scanner/src/jvmMain/kotlin/com/grappim/mukk/core/model/scanner/FileScanner.kt:15-33`),
  fetch existing tracks for `directory` once via `trackRepository.findByPathPrefix(directory.absolutePath)`
  into a `Map<String, MediaTrackData>` keyed by `filePath`, before the `audioFiles.forEachIndexed`
  loop. Change `scanSingleFile()` (`:51-102`) to accept the looked-up `MediaTrackData?` as a
  parameter instead of calling `trackRepository.findByPath()` itself.
- Keep the per-file DB call only where it's already load-bearing: `insertIfAbsent`'s "still
  missing" recheck (`TrackRepository.kt:52-54`) guards a real race (file inserted by another
  caller between the batch fetch and this write) and must stay as-is — it only executes for files
  the map says are new, which should be the rare case, not the common "already known" one this
  part is optimizing.
- **Verify:** no `jvmTest` source set exists for `core:scanner` yet (per this project's current
  test coverage — only `core:data` has one), so `./gradlew :core:scanner:compileKotlinJvm
  :core:scanner:detekt :composeApp:jvmMainClasses :composeApp:detekt` is the automated check.
  Manual: time (stopwatch, or just note the "Scanning X / Y" indicator's visible duration) a
  switch to the Default playlist before and after this change on the same unchanged library state
  — confirm the reconcile itself completes markedly faster, not just off the UI thread.
