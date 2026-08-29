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
                        .put("content", if (normalizeContent) strictTextContent(system) else system)
                )
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) {
                    messages.put(if (normalizeContent) normalizeChatContent(message) else message)
                }
            }
        }
    }

    private fun normalizeChatContent(message: JSONObject): JSONObject {
        val copy = JSONObject(message.toString())
        val content = message.opt("content")
        return copy.put("content", strictTextContent(providerMessageText(content)))
    }

    /**
     * Some nominally OpenAI-compatible gateways reject JSON strings for message.content
     * and only accept the typed text-part array form. This provider opt-in deliberately
     * normalizes every role, including generated system and tool messages, to one shape.
     */
    private fun strictTextContent(text: String): JSONArray =
        JSONArray().put(JSONObject().put("type", "text").put("text", text))

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
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
