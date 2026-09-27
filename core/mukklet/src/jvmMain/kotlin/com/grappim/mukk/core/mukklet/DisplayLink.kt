package com.grappim.mukk.core.mukklet

import com.grappim.mukk.core.model.MukkLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage

/** What the display should show. `null` in the input flow means "not known yet, send nothing". */
data class NowPlayingSnapshot(
    val track: DisplayTrack?,
    val next: NextTrack?,
    val state: DisplayState
)

/**
 * WebSocket client for the Mukklet display (`../esp32-mukklet/docs/PROTOCOL.md`). Connects in
 * the background, retries with backoff, and never blocks the caller. Sends `track`, a
 * `cover` with `none: true`, and `state`; emits the display's `cmd`s on [commands].
 */
class DisplayLink {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            MukkLogger.error(TAG, "Display link failed", e)
        }
    )
    private var job: Job? = null
    private val httpClient: HttpClient by lazy { HttpClient.newHttpClient() }

    private val _commands = MutableSharedFlow<DisplayCommand>(
        extraBufferCapacity = COMMAND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val commands: Flow<DisplayCommand> = _commands.asSharedFlow()

    /** Connects to `ws://[host]/ws`; replaces any running connection. [host] may carry a port. */
    @Synchronized
    fun start(host: String, snapshots: StateFlow<NowPlayingSnapshot?>) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            val uri = try {
                URI("ws://$host/ws").also { requireNotNull(it.host) { "no host in $it" } }
            } catch (e: URISyntaxException) {
                MukkLogger.warn(TAG, "Invalid display host: $host", e)
                return@launch
            } catch (e: IllegalArgumentException) {
                MukkLogger.warn(TAG, "Invalid display host: $host", e)
                return@launch
            }
            connectLoop(uri, snapshots)
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun connectLoop(uri: URI, snapshots: StateFlow<NowPlayingSnapshot?>) {
        var attempt = 0
        while (true) {
            var greeted = false
            try {
                val session = openSession(uri)
                greeted = true
                MukkLogger.info(TAG, "Connected to '${session.hello.device}' at $uri")
                try {
                    stream(session, snapshots)
                } finally {
                    session.ws.abort()
                }
            } catch (e: IOException) {
                MukkLogger.debug(TAG, "Display link to $uri: ${e.message}")
            }
            if (greeted) attempt = 0
            val wait = reconnectDelayMs(attempt++)
            MukkLogger.debug(TAG, "Reconnecting in ${wait / MS_PER_SECOND} s")
            delay(wait)
        }
    }

    private suspend fun openSession(uri: URI): Session {
        val listener = Listener()
        val ws = httpClient.newWebSocketBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .buildAsync(uri, listener)
            .await()
        val hello = withTimeoutOrNull(HELLO_TIMEOUT_MS) { listener.hello.await() }
        if (hello == null) {
            ws.abort()
            throw IOException("no hello within ${HELLO_TIMEOUT_MS / MS_PER_SECOND} s")
        }
        return Session(ws, listener, hello)
    }

    /** Runs until the display closes the connection or a send fails. */
    private suspend fun stream(session: Session, snapshots: StateFlow<NowPlayingSnapshot?>): Nothing = coroutineScope {
        val sending = launch { sendLoop(session, snapshots) }
        val reason = session.listener.closed.await()
        sending.cancel()
        throw reason
    }

    /**
     * The only place that sends. Each send is awaited before the next one starts, as the JDK
     * `WebSocket` requires; this also keeps a cover's frames from interleaving with anything.
     * The first snapshot after connect sends the full `track` → `cover` → `state` sequence.
     */
    private suspend fun sendLoop(session: Session, snapshots: StateFlow<NowPlayingSnapshot?>) {
        var sentTrack: Pair<DisplayTrack?, NextTrack?>? = null
        var sentState: SentState? = null
        val ticks = flow {
            while (true) {
                emit(snapshots.value)
                delay(TICK_MS)
            }
        }
        merge(snapshots, ticks).filterNotNull().collect { snapshot ->
            val trackInfo = snapshot.track to snapshot.next
            if (trackInfo != sentTrack) {
                session.send(ProtocolMessages.track(snapshot.track, snapshot.next))
                if (snapshot.track != null && session.hello.cover.format != CoverFormat.NONE) {
                    session.send(ProtocolMessages.coverNone(snapshot.track.id))
                }
                sentTrack = trackInfo
            }
            val now = monotonicMs()
            if (isStateDue(sentState, snapshot.state, now)) {
                session.send(ProtocolMessages.state(snapshot.state))
                sentState = SentState(snapshot.state, now)
            }
        }
    }

    private class Session(
        val ws: WebSocket,
        val listener: Listener,
        val hello: IncomingMessage.Hello
    ) {
        suspend fun send(text: String) {
            withTimeoutOrNull(SEND_TIMEOUT_MS) { ws.sendText(text, true).await() }
                ?: throw IOException("send timed out after ${SEND_TIMEOUT_MS / MS_PER_SECOND} s")
        }
    }

    private inner class Listener : WebSocket.Listener {
        val hello = CompletableDeferred<IncomingMessage.Hello>()
        val closed = CompletableDeferred<IOException>()
        private val text = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                handle(text.toString())
                text.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            fail(IOException("display closed the connection ($statusCode $reason)"))
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            fail(IOException("connection error", error))
        }

        private fun fail(e: IOException) {
            closed.complete(e)
            hello.completeExceptionally(e)
        }

        private fun handle(message: String) {
            when (val parsed = ProtocolMessages.parse(message)) {
                is IncomingMessage.Hello -> if (!hello.complete(parsed)) {
                    MukkLogger.debug(TAG, "Ignoring repeated hello")
                }
                is IncomingMessage.Command -> _commands.tryEmit(parsed.command)
                null -> Unit
            }
        }
    }

    private companion object {
        const val TAG = "DisplayLink"
        const val COMMAND_BUFFER = 64
        const val TICK_MS = 1_000L
        const val HELLO_TIMEOUT_MS = 10_000L
        const val SEND_TIMEOUT_MS = 10_000L
        const val MS_PER_SECOND = 1_000L
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    }
}
