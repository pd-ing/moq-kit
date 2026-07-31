package com.swmansion.moqkit

import android.util.Log
import com.swmansion.moqkit.publish.Publisher
import com.swmansion.moqkit.subscribe.BroadcastSubscription
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginProducer
import uniffi.moq.MoqSession as UniMoqSession

/**
 * A connection to one MoQ relay.
 *
 * Create one session for a relay URL and use it as the starting point for discovering
 * broadcasts, publishing media, and sending or receiving raw data tracks. A session owns
 * the native connection and the work created through it, so closing the session also
 * closes active subscriptions and stops active publishers.
 *
 * ### Lifecycle
 * 1. Create a session with the relay [url].
 * 2. Call [connect] to establish the connection.
 * 3. Call [subscribe] to discover live broadcasts, and/or [publish] to announce media.
 * 4. Call [close] to tear down the connection and free all resources.
 *
 * @param url Relay URL, for example `"https://relay.example.com:4443/anon"`.
 * @param parentScope Coroutine scope whose lifetime bounds background session work. In apps,
 *   pass a lifecycle-owned scope such as `lifecycleScope` or `viewModelScope`.
 * @param fingerprintTimeoutMs Connect/read timeout (milliseconds) for the insecure
 *   `http://` certificate-fingerprint bootstrap fetch. The native client performs this
 *   fetch without any timeout, which can stall a connect attempt for minutes on a
 *   blackholed route, so this SDK fetches the fingerprint itself with a bounded budget
 *   and pins it via `setTlsFingerprints` instead.
 * @param connectTimeoutMs Total timeout (milliseconds) for the native QUIC/WebSocket
 *   connect, covering DNS, the QUIC handshake, and the WebTransport/WebSocket setup.
 */
class Session(
    private val url: String,
    parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val fingerprintTimeoutMs: Long = DEFAULT_FINGERPRINT_TIMEOUT_MS,
    private val connectTimeoutMs: Long = DEFAULT_CONNECT_TIMEOUT_MS,
) {
    companion object {
        private const val TAG = "Session"

        /** Default connect/read timeout for the `http://` fingerprint bootstrap fetch. */
        const val DEFAULT_FINGERPRINT_TIMEOUT_MS = 10_000L

        /** Default timeout for the native QUIC/WebSocket connect. */
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L

        /** A hex-encoded SHA-256 fingerprint, as served at `/certificate.sha256`. */
        private val FINGERPRINT_HEX_REGEX = Regex("[0-9a-f]{64}")
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())

    /** Connection state machine for this session. */
    sealed class State {
        /** Session has not been started yet. */
        object Idle : State()
        /** QUIC handshake is in progress. */
        object Connecting : State()
        /** Handshake complete; the session may now publish and subscribe. */
        object Connected : State()
        /** The session ended due to a transport or protocol error.
         * @property message Human-readable error description. */
        data class Error(val message: String) : State()
        /** [Session.close] was called and all resources have been released. */
        object Closed : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /**
     * Current connection state. Starts at [State.Idle] and progresses through
     * [State.Connecting] → [State.Connected] → [State.Closed] (or [State.Error]).
     */
    val state: StateFlow<State> = _state.asStateFlow()

    private var session: UniMoqSession? = null
    private var client: MoqClient? = null
    private var consumeOrigin: MoqOriginProducer? = null
    private var publishOrigin: MoqOriginProducer? = null
    private var monitorJob: Job? = null

    private val activeSubscriptions = mutableMapOf<String, BroadcastSubscription>()
    private val activePublishers = mutableMapOf<String, Publisher>()

    /**
     * Opens the QUIC connection.
     *
     * Suspends until the handshake is complete. Once this function returns, [state] is
     * [State.Connected] and the session may publish or create broadcast subscriptions.
     *
     * A session can be connected only once. Create a new [Session] after [close].
     *
     * @throws IllegalStateException if called on a session that has already been started.
     * @throws Exception if the connection attempt fails (state becomes [State.Error]).
     */
    suspend fun connect() {
        check(_state.value == State.Idle) { "Session already started" }
        _state.value = State.Connecting
        Log.d(TAG, "Connecting to $url")
        try {
            // For http:// URLs the native client fetches the certificate fingerprint
            // without any timeout (observed stalls of ~130s per attempt). Fetch it
            // here with a bounded budget and pin it, so the native fetch is skipped.
            val fingerprints = resolveFingerprints()

            val newConsumeOrigin = MoqOriginProducer()
            consumeOrigin = newConsumeOrigin
            Log.d(TAG, "Consume origin created")

            val newPublishOrigin = MoqOriginProducer()
            publishOrigin = newPublishOrigin
            Log.d(TAG, "Publish origin created")

            val newClient = MoqClient()
            if (fingerprints != null) {
                // Fingerprint pinning bypasses CA verification and cannot be
                // combined with system roots.
                newClient.setTlsSystemRoots(false)
                newClient.setTlsFingerprints(fingerprints)
            } else {
                newClient.setTlsSystemRoots(true)
            }
            client = newClient
            newClient.setConsume(newConsumeOrigin)
            newClient.setPublish(newPublishOrigin)

            Log.d(TAG, "connect: QUIC/WS connect start (timeout ${connectTimeoutMs}ms)")
            val connectStartMs = System.currentTimeMillis()
            val newSession = try {
                withTimeout(connectTimeoutMs) { newClient.connect(url) }
            } catch (e: TimeoutCancellationException) {
                throw Exception("QUIC/WS connect timed out after ${connectTimeoutMs}ms", e)
            }
            Log.d(TAG, "connect: QUIC/WS connected in ${System.currentTimeMillis() - connectStartMs}ms")
            session = newSession
            _state.value = State.Connected
            Log.d(TAG, "Connected successfully")

            monitorJob = scope.launch {
                try {
                    newSession.closed()
                } catch (e: Exception) {
                    Log.w(TAG, "Session closed with error: $e")
                    _state.compareAndSet(State.Connected, State.Error("Session ended: $e"))
                    close()
                    return@launch
                }

                if (_state.value == State.Connected) {
                    Log.w(TAG, "Session ended unexpectedly")
                    _state.value = State.Error("Session ended unexpectedly")
                    close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            _state.value = State.Error(e.message ?: "Connection failed")
            tearDown()
            throw e
        }
    }

    /**
     * Starts watching for broadcast announcements under the supplied prefix.
     *
     * Collect [BroadcastSubscription.broadcasts] to receive each matching broadcast as it is
     * announced by the relay. Each prefix may have only one active subscription on a session.
     *
     * @param prefix Only broadcasts whose path starts with this string will be surfaced.
     *   Pass `""` (the default) to receive all broadcasts.
     * @throws IllegalStateException if the session is not connected or the exact prefix
     *   already has an active subscription.
     */
    fun subscribe(prefix: String = ""): BroadcastSubscription {
        check(_state.value == State.Connected) { "Session must be connected before subscribing" }

        return synchronized(activeSubscriptions) {
            check(_state.value == State.Connected) {
                "Session must be connected before subscribing"
            }

            val existing = activeSubscriptions[prefix]
            check(existing == null || existing.isClosed) {
                "Already subscribed to prefix '$prefix'"
            }

            val sessionConsumeOrigin = consumeOrigin
                ?: error("Consume origin not available")
            val originConsumer = sessionConsumeOrigin.consume()
            try {
                val announced = originConsumer.announced(prefix)
                var subscriptionRef: BroadcastSubscription? = null
                val subscription = BroadcastSubscription(
                    prefix = prefix,
                    originConsumer = originConsumer,
                    announced = announced,
                    onClosed = {
                        synchronized(activeSubscriptions) {
                            if (activeSubscriptions[prefix] === subscriptionRef) {
                                activeSubscriptions.remove(prefix)
                            }
                        }
                    },
                )
                subscriptionRef = subscription
                activeSubscriptions[prefix] = subscription
                subscription
            } catch (t: Throwable) {
                try {
                    originConsumer.close()
                } catch (closeError: Exception) {
                    Log.w(TAG, "Failed to close origin consumer for prefix '$prefix'", closeError)
                }
                throw t
            }
        }
    }

    /**
     * Announces a publisher at the given broadcast path.
     *
     * Configure the [Publisher] first, call this method to register the broadcast with the
     * relay, then call [Publisher.start] to begin sending media or data.
     *
     * @param path Broadcast path on the relay (e.g. `"live/my-stream"`).
     * @param publisher A configured [Publisher] with at least one track added.
     * @throws IllegalStateException if the session is not connected or already publishes at
     *   the same [path].
     */
    fun publish(path: String, publisher: Publisher) {
        check(_state.value == State.Connected) { "Session must be connected before publishing" }
        check(!activePublishers.containsKey(path)) {
            "Already publishing at '$path'. Call unpublish() first."
        }
        val origin = publishOrigin ?: error("Publish origin not available")
        Log.d(TAG, "Publishing broadcast at '$path'")
        origin.publish(path, publisher.broadcast)
        activePublishers[path] = publisher
    }

    /**
     * Stops publishing at the given path.
     *
     * If [path] is active, this calls [Publisher.stop] for the associated publisher. If the
     * path is not active, this method does nothing.
     */
    fun unpublish(path: String) {
        val publisher = activePublishers.remove(path) ?: return
        Log.d(TAG, "Unpublishing broadcast at '$path'")
        publisher.stop()
    }

    /**
     * Closes the session and releases all resources.
     *
     * Safe to call from any thread. No-op if the session is already [State.Closed].
     * After this returns, [state] is [State.Closed] and background work owned by this
     * session is cancelled.
     */
    fun close() {
        val wasConnected = _state.compareAndSet(State.Connected, State.Closed)
        val wasConnecting = if (!wasConnected) {
            _state.compareAndSet(State.Connecting, State.Closed)
        } else {
            false
        }
        val wasError = if (!wasConnected && !wasConnecting) {
            val current = _state.value
            if (current is State.Error) {
                _state.compareAndSet(current, State.Closed)
            } else {
                false
            }
        } else {
            false
        }

        if (!wasConnected && !wasConnecting && !wasError) {
            Log.d(TAG, "close() called but already in state ${_state.value}")
            return
        }

        Log.d(
            TAG,
            "Closing session (was: connected=$wasConnected connecting=$wasConnecting error=$wasError)",
        )
        tearDown()
    }

    /**
     * Returns the pinned fingerprint for `http://` URLs, or null for other schemes.
     *
     * Mirrors the native insecure bootstrap: for `http://` relays the certificate
     * fingerprint is served over plain HTTP at `/certificate.sha256`. Fetching it here
     * with explicit connect/read timeouts keeps a dead route from stalling the whole
     * connect attempt, and pinning the result makes the native client skip its own
     * unbounded fetch.
     */
    private suspend fun resolveFingerprints(): List<String>? {
        val fingerprintUrl = httpFingerprintUrl() ?: return null
        Log.d(TAG, "connect: fingerprint fetch start $fingerprintUrl (timeout ${fingerprintTimeoutMs}ms)")
        val startMs = System.currentTimeMillis()
        val hex = try {
            fetchFingerprint(fingerprintUrl)
        } catch (e: TimeoutCancellationException) {
            throw Exception("failed to fetch fingerprint: timed out after ${fingerprintTimeoutMs}ms", e)
        } catch (e: Exception) {
            throw Exception("failed to fetch fingerprint: ${e.message}", e)
        }
        Log.d(TAG, "connect: fingerprint fetched in ${System.currentTimeMillis() - startMs}ms")
        return listOf(hex)
    }

    /** The `/certificate.sha256` URL for [url], or null when [url] is not `http://`. */
    private fun httpFingerprintUrl(): String? {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return null
        }
        if (!parsed.protocol.equals("http", ignoreCase = true)) return null
        return URL(parsed.protocol, parsed.host, parsed.port, "/certificate.sha256").toString()
    }

    private suspend fun fetchFingerprint(fingerprintUrl: String): String = withContext(Dispatchers.IO) {
        withTimeout(fingerprintTimeoutMs) {
            val connection = (URL(fingerprintUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = fingerprintTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                readTimeout = fingerprintTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                instanceFollowRedirects = false
            }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw Exception("fingerprint request failed with HTTP $status")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val hex = body.trim().lowercase()
                if (!hex.matches(FINGERPRINT_HEX_REGEX)) {
                    throw Exception("invalid fingerprint payload")
                }
                hex
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun tearDown() {
        val subscriptions = synchronized(activeSubscriptions) {
            activeSubscriptions.values.toList().also { activeSubscriptions.clear() }
        }
        Log.d(TAG, "Tearing down: ${subscriptions.size} active subscriptions")

        subscriptions.forEach { subscription ->
            try {
                subscription.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close broadcast subscription '${subscription.prefix}'", e)
            }
        }

        activePublishers.values.forEach { publisher ->
            try {
                publisher.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop publisher during teardown", e)
            }
        }
        activePublishers.clear()

        monitorJob?.cancel()
        monitorJob = null

        session?.cancel(0u)
        try {
            session?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close session", e)
        }
        session = null

        client?.cancel()
        try {
            client?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close client", e)
        }
        client = null

        try {
            publishOrigin?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close publish origin", e)
        }
        publishOrigin = null

        try {
            consumeOrigin?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to close consume origin", e)
        }
        consumeOrigin = null

        scope.cancel()
        Log.d(TAG, "Teardown complete")
    }
}
