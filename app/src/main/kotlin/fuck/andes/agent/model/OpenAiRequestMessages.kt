package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 将 Eta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(
        source: JSONArray,
        normalizeContent: Boolean = false,
    ): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", system)
                )
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) {
                    // Chat Completions does not accept Responses API's input_text/output_text
                    // part names. Canonicalize them even when the optional strict-server
                    // flattening switch is off: historical transcripts can contain them.
                    messages.put(normalizeChatContent(message, flatten = normalizeContent))
                }
            }
        }
    }

    private fun normalizeChatContent(message: JSONObject, flatten: Boolean): JSONObject {
        val content = message.opt("content")
        if (content !is JSONArray) return message
        if (flatten) {
            return JSONObject(message.toString()).put("content", providerMessageText(content))
        }
        var changed = false
        val canonical = JSONArray()
        for (index in 0 until content.length()) {
            val part = content.opt(index)
            val objectPart = part as? JSONObject
            if (objectPart?.optString("type") in RESPONSES_ONLY_TEXT_TYPES) {
                canonical.put(JSONObject(objectPart.toString()).put("type", "text"))
                changed = true
            } else {
                canonical.put(part)
            }
        }
        return if (changed) JSONObject(message.toString()).put("content", canonical) else message
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_ONLY_TEXT_TYPES = setOf("input_text", "output_text")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
