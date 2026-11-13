package com.nexusts.app.core.vault.automation

import com.nexusts.app.core.vault.credentials.TotpCodeGenerator
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
     * Expands Royal TS & NexusTS replacement tokens (`$URI$`, `$Name$`, `$EffectiveUsername$`, `$OS$`, `$CustomProperty.<Key>$`).
     */
    fun renderCommandTask(task: CommandTask, profile: EffectiveConnectionProfile): String {
        val expanded = expandVariables(task.commandTemplate, profile)
        return if (task.appendNewline && !expanded.endsWith("\n")) "$expanded\n" else expanded
    }

    /**
     * Parses a Key Sequence macro into executable steps supporting `{WAIT:300}`, `{ENTER}`, `{TAB}`, `{ESC}`, `{CTRL+C}`.
     *
     * Tokenizes `{SPECIAL_KEY}` and `{WAIT:ms}` segments BEFORE expanding `$EffectivePassword$`, `$EffectiveUsername$`,
     * and `$CustomProperty.*$` so literal `{...}` characters inside passwords or custom property values are never
     * misinterpreted as special key tokens or stripped.
     */
    fun parseKeySequence(task: KeySequenceTask, profile: EffectiveConnectionProfile): List<MacroStep> {
        val rawTemplate = task.macroSequence
        val tokenRegex = Regex("\\{([A-Z0-9+_]+)(?::(\\d+))?\\}")
        val rawSteps = mutableListOf<MacroStep>()
        var cursor = 0

        for (match in tokenRegex.findAll(rawTemplate)) {
            if (match.range.first > cursor) {
                val textSegment = expandVariables(rawTemplate.substring(cursor, match.range.first), profile)
                if (textSegment.isNotEmpty()) {
                    rawSteps.appendCoalescedText(textSegment)
                }
            }
            val token = match.groupValues[1]
            val arg = match.groupValues[2]
            when {
                token == "URI" && arg.isEmpty() -> {
                    if (profile.connection.host.isNotEmpty()) {
                        rawSteps.appendCoalescedText(profile.connection.host)
                    }
                }
                token == "WAIT" -> {
                    rawSteps += MacroStep.PauseDelay(arg.toLongOrNull() ?: 250L)
                }
                else -> {
                    rawSteps += MacroStep.SpecialKey(token)
                }
            }
            cursor = match.range.last + 1
        }

        if (cursor < rawTemplate.length) {
            val trailingText = expandVariables(rawTemplate.substring(cursor), profile)
            if (trailingText.isNotEmpty()) {
                rawSteps.appendCoalescedText(trailingText)
            }
        }
        return rawSteps
    }

    private fun MutableList<MacroStep>.appendCoalescedText(chunk: String) {
        val lastStep = lastOrNull()
        if (lastStep is MacroStep.TextPayload) {
            this[lastIndex] = MacroStep.TextPayload(lastStep.text + chunk)
        } else {
            this += MacroStep.TextPayload(chunk)
        }
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
            ?.let { TotpCodeGenerator.generateTotp(it)?.code }
            .orEmpty()

        // Use Regex.escapeReplacement or literal single-pass token replacement so '$' or '{...}' in secrets never cross-contaminate
        val tokenPattern = Regex(
            "\\$(URI|Name|OS|Notes|EffectiveUsername|EffectiveDomain|EffectivePassword|EffectiveTOTP|CustomProperty\\.[A-Za-z0-9_.-]+)\\$|\\{URI\\}",
            RegexOption.IGNORE_CASE
        )

        return tokenPattern.replace(template) { match ->
            val full = match.value
            if (full.equals("{URI}", ignoreCase = true)) {
                return@replace profile.connection.host
            }
            val inner = match.groupValues[1]
            when {
                inner.equals("URI", ignoreCase = true) -> profile.connection.host
                inner.equals("Name", ignoreCase = true) -> profile.connection.name
                inner.equals("OS", ignoreCase = true) -> profile.connection.osFamily
                inner.equals("Notes", ignoreCase = true) -> profile.connection.notes
                inner.equals("EffectiveUsername", ignoreCase = true) -> profile.effectiveCredential?.username.orEmpty()
                inner.equals("EffectiveDomain", ignoreCase = true) -> profile.effectiveCredential?.domain.orEmpty()
                inner.equals("EffectivePassword", ignoreCase = true) -> profile.effectiveCredential?.secretPassword.orEmpty()
                inner.equals("EffectiveTOTP", ignoreCase = true) -> totpCode
                inner.startsWith("CustomProperty.", ignoreCase = true) -> {
                    val propKey = inner.substringAfter('.')
                    val exact = profile.effectiveCustomProperties[propKey]
                    val resolved = exact ?: profile.effectiveCustomProperties.entries
                        .lastOrNull { it.key.equals(propKey, ignoreCase = true) }
                        ?.value
                    resolved?.value.orEmpty()
                }
                else -> full
            }
        }
    }
}
