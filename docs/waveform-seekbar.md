# Waveform Seekbar

Replace the flat progress bar in `TransportBar` with an amplitude waveform — vertical bars showing loud/quiet sections across the track duration, with played/unplayed tinting.

## Visual Target

```
|  played  | thumb |        unplayed         |
████▅▃▆█▇▄▂▃▅▇█▆▄▃  ░░▂▄▆█▇▅▃▂▄▆▇█▅▃▂▄▆▇█▅░░
0:00                ^                      4:32
```

Bars to the left of the playhead use the primary color; bars to the right use a muted/dim color. Clicking or dragging anywhere seeks to that position (same behavior as the current seekbar).

---

## Implementation Plan

### Step 1 — PCM extraction via GStreamer

Create `WaveformExtractor` in `core/scanner` (or a new `core/player` module).

Build a GStreamer decode-only pipeline:

```
filesrc → decodebin → audioconvert → audioresample → capsfilter → appsink
```

- `capsfilter` forces output to `audio/x-raw, format=S16LE, channels=1, rate=44100`
- `appsink` captures each buffer; collect all samples into a `ShortArray`
- Tear down the pipeline after EOS or on error

From the `ShortArray`, compute `N` amplitude buckets (N = bar count, e.g. 400):

```kotlin
fun computePeaks(samples: ShortArray, buckets: Int): FloatArray {
    val size = samples.size / buckets
    return FloatArray(buckets) { i ->
        val slice = samples.slice(i * size until minOf((i + 1) * size, samples.size))
        slice.maxOfOrNull { abs(it.toInt()) }?.toFloat()?.div(Short.MAX_VALUE) ?: 0f
    }
}
```

**Key GStreamer notes:**
- Use `AppSink.setEmitSignals(false)` and poll via `AppSink.pullSample()` in a loop
- Set `AppSink.setSync(false)` so extraction runs faster than real-time
- The pipeline state must be set to `PLAYING`, then drained to `EOS`, then set to `NULL` before disposal
- Run entirely on `Dispatchers.IO`

---

### Step 2 — Caching

Store computed peaks in SQLite via a new `WaveformCache` table:

| Column | Type | Notes |
|--------|------|-------|
| `file_path` | TEXT PK | absolute path |
| `peaks` | BLOB | `FloatArray` serialized as little-endian bytes |
| `bucket_count` | INTEGER | number of bars (e.g. 400) |
| `computed_at` | INTEGER | epoch ms — invalidate if `file.lastModified` is newer |

Add `WaveformRepository` in `core/data` following the same pattern as `TrackRepository`.

On cache hit, skip GStreamer entirely and deserialize the BLOB directly.

---

### Step 3 — ViewModel integration

Add to `MukkViewModel`:

```kotlin
private val _waveformPeaks = MutableStateFlow<FloatArray?>(null)
```

Expose via `MukkUiState`. Trigger loading in `loadNowPlayingExtras()`:

```kotlin
viewModelScope.launch(Dispatchers.IO) {
    _waveformPeaks.value = waveformRepository.getCached(filePath)
        ?: waveformExtractor.extract(File(filePath))
            .also { waveformRepository.put(filePath, it) }
}
```

Clear `_waveformPeaks` on `stop()`.

---

### Step 4 — WaveformSeekBar composable

Replace `SeekBar` in `TransportBar` with `WaveformSeekBar`. When `peaks` is null, fall back to the existing flat bar (covers the loading window).

```kotlin
@Composable
fun WaveformSeekBar(
    peaks: FloatArray?,           // null = loading / unavailable
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
)
```

Draw with `Canvas`:
- Bar width: `(canvasWidth - barGap * (N-1)) / N`
- Bar height: `peaks[i] * canvasHeight` (centered vertically, mirrored top+bottom for classic waveform look — or single direction, simpler)
- Color: `primary` for bars where `i / N < progress`, `onSurface.copy(alpha = 0.25f)` otherwise
- Seek on pointer down + drag: `onSeek((x / canvasWidth * durationMs).toLong())`

---

### Step 5 — Wire up & test

1. Register `WaveformExtractor` and `WaveformRepository` in `AppModule` as singletons
2. Add `waveformPeaks: FloatArray?` to `MukkUiState`
3. Thread it through `App.kt → MainLayout → TransportBar → WaveformSeekBar`
4. Verify: seek accuracy, cache hit on re-open, no leak on rapid track switching, graceful fallback for corrupt files

---

## Risk / Mitigation

| Risk | Mitigation |
|------|-----------|
| GStreamer `AppSink` blocks or leaks pipeline | Always transition to `NULL` state in a `finally` block; set a timeout |
| Extraction is slow on large FLAC files | Show flat bar until peaks arrive; cache persists across restarts |
| `appsink` buffer API differs across gst1-java-core versions | Pin to `1.4.0` (already in use); test with one format first |
| Corrupt file causes extractor to hang | Add a coroutine timeout (`withTimeout(30_000)`) around extraction |
