package com.swmansion.moqkit.publish.source.internal

/**
 * Accumulated-sample PTS generator for audio capture.
 *
 * The AAC encoder quantizes input to 1024-sample frames and expects input
 * timestamps to advance exactly with the samples delivered; stamping each
 * chunk with the wall clock at read() return makes nearly every chunk land
 * behind the encoder's accumulated timeline (C2SoftAacEnc "Correcting
 * overlapping timestamp" once per chunk, 2026-08-11 실기기 round). This
 * timeline instead anchors once at the first chunk and advances by the
 * samples actually delivered, so it follows the microphone's sample clock.
 *
 * The cumulative-floor arithmetic (`totalFrames * 1_000_000 / sampleRate`)
 * is deliberately computed from totals: per-chunk floors accumulate error,
 * and a timeline that ever steps behind the encoder's own per-chunk
 * accumulation re-trips the clamp. floor(a+b) >= floor(a)+floor(b) keeps
 * this timeline at-or-ahead of any per-chunk-floored expectation while the
 * error against the true rational value stays under 1µs forever.
 */
internal class AudioPtsCounter(
    private val sampleRate: Int,
    private val bytesPerFrame: Int = 2,
) {
    private var basePtsUs = 0L
    private var totalFrames = 0L

    /** One delivered capture chunk: whole-frame byte size + its PTS. */
    data class Chunk(val sizeBytes: Int, val timestampUs: Long)

    /**
     * Converts a raw AudioRecord.read() byte count into whole per-channel
     * frames, advances the timeline by exactly those frames, and returns the
     * whole-frame byte size to deliver downstream — or null when the read
     * holds less than one frame (nothing delivered, nothing advanced). The
     * byte->frame conversion lives here so the PTS advance and the delivered
     * size are tied to the same frame count by construction — a mismatch
     * between the two is exactly what desyncs the encoder timeline.
     */
    fun onChunkBytes(readBytes: Int, nowUs: Long): Chunk? {
        val frames = readBytes / bytesPerFrame
        if (frames <= 0) return null
        return Chunk(frames * bytesPerFrame, onChunk(frames, nowUs))
    }

    /**
     * Returns the presentation timestamp (µs) for a chunk of [frames]
     * per-channel samples whose blocking read returned at [nowUs], and
     * advances the timeline. The first chunk anchors the timeline at its
     * capture START (read-return minus chunk duration).
     */
    fun onChunk(frames: Int, nowUs: Long): Long {
        if (totalFrames == 0L) {
            basePtsUs = nowUs - frames * 1_000_000L / sampleRate
        }
        val ptsUs = basePtsUs + totalFrames * 1_000_000L / sampleRate
        totalFrames += frames
        return ptsUs
    }

    /**
     * Divergence (µs) of the wall clock [nowUs] from the sample timeline's
     * current end — positive when the wall clock runs ahead of the sample
     * clock. Diagnostic only.
     */
    fun wallDeltaUs(nowUs: Long): Long =
        nowUs - (basePtsUs + totalFrames * 1_000_000L / sampleRate)

    /** Per-channel samples delivered so far. */
    val framesDelivered: Long get() = totalFrames
}
