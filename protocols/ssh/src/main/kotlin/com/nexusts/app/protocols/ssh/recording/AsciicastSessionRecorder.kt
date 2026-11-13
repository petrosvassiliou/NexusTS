package com.nexusts.app.protocols.ssh.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AsciicastHeader(
    val version: Int = 2,
    val width: Int = 120,
    val height: Int = 36,
    val timestamp: Long = System.currentTimeMillis() / 1000L,
    val title: String = "NexusTS Audit Session",
    val env: Map<String, String> = mapOf("TERM" to "xterm-256color", "SHELL" to "/bin/bash")
)

data class AsciicastEventFrame(
    val elapsedSeconds: Double,
    val eventType: String,
    val data: String
)

data class ParsedAsciicastRecording(
    val header: AsciicastHeader,
    val frames: List<AsciicastEventFrame>
) {
    val totalDurationSeconds: Double
        get() = frames.lastOrNull()?.elapsedSeconds ?: 0.0
}

/**
 * Standard **asciicast v2** (`.cast` JSON Lines) SSH Session Audit Recorder & Parser.
 * Compatible with `asciinema play` CLI tools, SOC compliance pipelines, and NexusTS in-app replay.
 *
 * Security Note: Raw keystrokes (`"i"`) are NEVER recorded in plaintext to avoid capturing passwords
 * typed at `sudo` or SSH prompts; only terminal stdout (`"o"`) and resize (`"r"`) events are persisted.
 */
class AsciicastSessionRecorder(
    private val sessionTitle: String
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var startNanoTime: Long = 0L
    private var headerSnapshot: AsciicastHeader = AsciicastHeader(title = sessionTitle)
    private val recordedFrames = mutableListOf<AsciicastEventFrame>()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    private val _lastExportedCast = MutableStateFlow<String?>(null)
    val lastExportedCast: StateFlow<String?> = _lastExportedCast.asStateFlow()

    fun startRecording(cols: Int, rows: Int) {
        synchronized(lock) {
            recordedFrames.clear()
            startNanoTime = System.nanoTime()
            headerSnapshot = AsciicastHeader(
                version = 2,
                width = cols.coerceAtLeast(20),
                height = rows.coerceAtLeast(6),
                timestamp = System.currentTimeMillis() / 1000L,
                title = sessionTitle
            )
            _frameCount.value = 0
            _isRecording.value = true
        }
    }

    fun recordOutputChunk(text: String) {
        if (!_isRecording.value || text.isEmpty()) return
        synchronized(lock) {
            if (!_isRecording.value) return
            val elapsedSec = ((System.nanoTime() - startNanoTime).coerceAtLeast(0L)) / 1_000_000_000.0
            recordedFrames += AsciicastEventFrame(
                elapsedSeconds = (Math.round(elapsedSec * 1_000.0) / 1_000.0),
                eventType = "o",
                data = text
            )
            _frameCount.value = recordedFrames.size
        }
    }

    fun recordResize(cols: Int, rows: Int) {
        if (!_isRecording.value) return
        synchronized(lock) {
            if (!_isRecording.value) return
            val elapsedSec = ((System.nanoTime() - startNanoTime).coerceAtLeast(0L)) / 1_000_000_000.0
            recordedFrames += AsciicastEventFrame(
                elapsedSeconds = (Math.round(elapsedSec * 1_000.0) / 1_000.0),
                eventType = "r",
                data = "${cols}x${rows}"
            )
            _frameCount.value = recordedFrames.size
        }
    }

    fun stopAndBuildCastDocument(): String {
        synchronized(lock) {
            _isRecording.value = false
            val serialized = buildCastJsonLines(headerSnapshot, recordedFrames.toList())
            _lastExportedCast.value = serialized
            return serialized
        }
    }

    private fun buildCastJsonLines(header: AsciicastHeader, frames: List<AsciicastEventFrame>): String = buildString {
        appendLine(json.encodeToString(AsciicastHeader.serializer(), header))
        frames.forEach { frame ->
            val arr: JsonArray = buildJsonArray {
                add(JsonPrimitive(frame.elapsedSeconds))
                add(JsonPrimitive(frame.eventType))
                add(JsonPrimitive(frame.data))
            }
            appendLine(arr.toString())
        }
    }

    companion object {
        private val parserJson = Json { ignoreUnknownKeys = true }

        fun parseCastDocument(castJsonLines: String): ParsedAsciicastRecording? {
            val nonBlank = castJsonLines.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (nonBlank.isEmpty()) return null
            val header = runCatching {
                parserJson.decodeFromString(AsciicastHeader.serializer(), nonBlank.first())
            }.getOrNull() ?: return null

            val frames = nonBlank.drop(1).mapNotNull { line ->
                runCatching {
                    val arr = parserJson.parseToJsonElement(line).jsonArray
                    if (arr.size >= 3) {
                        AsciicastEventFrame(
                            elapsedSeconds = arr[0].jsonPrimitive.doubleOrNull ?: 0.0,
                            eventType = arr[1].jsonPrimitive.content,
                            data = arr[2].jsonPrimitive.content
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            }
            return ParsedAsciicastRecording(header = header, frames = frames)
        }
    }
}
