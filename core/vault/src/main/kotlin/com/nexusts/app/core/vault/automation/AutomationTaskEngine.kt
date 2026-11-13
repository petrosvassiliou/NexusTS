package com.nexusts.app.core.vault.automation

import com.nexusts.app.core.vault.inheritance.EffectiveConnectionProfile
import com.nexusts.app.core.vault.model.CommandTask
import com.nexusts.app.core.vault.model.KeySequenceTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface MacroStep {
    data class TextPayload(val text: String) : MacroStep
    data class SpecialKey(val keyToken: String) : MacroStep
    data class PauseDelay(val durationMs: Long) : MacroStep
}

data class BroadcastTerminalPacket(
    val payloadBytes: ByteArray,
    val originTabId: String?,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Executes Command Tasks, Key Sequence Macros, Connect/Disconnect triggers,
 * and simultaneous Multi-Terminal Broadcast Input fan-out.
 */
class AutomationTaskEngine {
    private val _broadcastBus = MutableSharedFlow<BroadcastTerminalPacket>(extraBufferCapacity = 128)
    val broadcastBus: SharedFlow<BroadcastTerminalPacket> = _broadcastBus.asSharedFlow()

    fun emitBroadcastBytes(bytes: ByteArray, originTabId: String? = null) {
        _broadcastBus.tryEmit(BroadcastTerminalPacket(payloadBytes = bytes, originTabId = originTabId))
    }

    /**
     * Expands Royal TS & NexusTS replacement tokens (`$URI$`, `$Name$`, `$EffectiveUsername$`, `$OS$`).
     */
    fun renderCommandTask(task: CommandTask, profile: EffectiveConnectionProfile): String {
        val expanded = expandVariables(task.commandTemplate, profile)
        return if (task.appendNewline && !expanded.endsWith("\n")) "$expanded\n" else expanded
    }

    /**
     * Parses a Key Sequence macro into executable steps supporting `{WAIT:300}`, `{ENTER}`, `{TAB}`, `{ESC}`, `{CTRL+C}`.
     */
    fun parseKeySequence(task: KeySequenceTask, profile: EffectiveConnectionProfile): List<MacroStep> {
        val expanded = expandVariables(task.macroSequence, profile)
        val regex = Regex("\\{([A-Z0-9+_]+)(?::(\\d+))?\\}")
        val steps = mutableListOf<MacroStep>()
        var cursor = 0

        for (match in regex.findAll(expanded)) {
            if (match.range.first > cursor) {
                steps += MacroStep.TextPayload(expanded.substring(cursor, match.range.first))
            }
            val token = match.groupValues[1]
            val arg = match.groupValues[2]
            if (token == "WAIT") {
                steps += MacroStep.PauseDelay(arg.toLongOrNull() ?: 250L)
            } else {
                steps += MacroStep.SpecialKey(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < expanded.length) {
            steps += MacroStep.TextPayload(expanded.substring(cursor))
        }
        return steps
    }

    suspend fun executeKeySequence(
        task: KeySequenceTask,
        profile: EffectiveConnectionProfile,
        sendText: suspend (String) -> Unit,
        sendSpecialKey: suspend (String) -> Unit
    ) {
        val steps = parseKeySequence(task, profile)
        for (step in steps) {
            when (step) {
                is MacroStep.TextPayload -> {
                    for (ch in step.text) {
                        sendText(ch.toString())
                        if (task.keystrokeDelayMs > 0) delay(task.keystrokeDelayMs.toLong())
                    }
                }
                is MacroStep.SpecialKey -> {
                    sendSpecialKey(step.keyToken)
                    if (task.keystrokeDelayMs > 0) delay(task.keystrokeDelayMs.toLong())
                }
                is MacroStep.PauseDelay -> delay(step.durationMs)
            }
        }
    }

    private fun expandVariables(template: String, profile: EffectiveConnectionProfile): String {
        val totpCode = profile.effectiveCredential?.totpSecretBase32
            ?.takeIf { it.isNotBlank() }
            ?.let { com.nexusts.app.core.vault.credentials.TotpCodeGenerator.generateTotp(it)?.code }
            .orEmpty()

        var result = template
            .replace("\$URI\$", profile.connection.host)
            .replace("{URI}", profile.connection.host)
            .replace("\$Name\$", profile.connection.name)
            .replace("\$OS\$", profile.connection.osFamily)
            .replace("\$Notes\$", profile.connection.notes)
            .replace("\$EffectiveUsername\$", profile.effectiveCredential?.username.orEmpty())
            .replace("\$EffectiveDomain\$", profile.effectiveCredential?.domain.orEmpty())
            .replace("\$EffectivePassword\$", profile.effectiveCredential?.secretPassword.orEmpty())
            .replace("\$EffectiveTOTP\$", totpCode)

        val customPropRegex = Regex("\\\$CustomProperty\\.([A-Za-z0-9_.-]+)\\\$")
        result = customPropRegex.replace(result) { match ->
            val propKey = match.groupValues[1]
            profile.effectiveCustomProperties[propKey]?.value.orEmpty()
        }
        return result
    }
}
