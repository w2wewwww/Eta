package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentAppContext
import fuck.andes.agent.runtime.AgentRunController
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.data.model.OpenAiEndpointMode
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.provider.ProviderSourceRegistry
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private const val DIAGNOSTIC_DIRECTORY = "diagnostics/openai-chat"
    private val diagnosticTimestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "Responses API 已预留配置位，但当前运行时仅支持 Chat Completions"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val requestJson = buildRequestJson(config, request.messages, request.tools)
        logFullRequest(requestJson)
        val streaming = requestJson.optBoolean("stream", true)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", if (streaming) "text/event-stream" else "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { CustomHeaderFilter.mergeInto(it, config.customHeaders) }
            .build()

        val requestBody = requestJson.toString().toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(requestBody)
            .build()
        logRequestMetadata(httpRequest, requestJson.toString().toByteArray(StandardCharsets.UTF_8).size)

        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register { call.cancel() }

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)

            call.execute().use { response ->
                val code = response.code
                logResponseMetadata(response.protocol, code, response.headers)
                onEvent(ProviderEvent.ResponseHeaders(code))
                runController.throwIfCancelled()

                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    logFullResponse(code, errorBody)
                    error("模型接口返回 HTTP $code：${errorBody.compactError()}")
                }

                val assistantMessage = if (streaming) {
                    readStreamingAssistantMessage(response.body.byteStream(), code, runController, onEvent)
                } else {
                    val responseBody = response.body.string()
                    logFullResponse(code, responseBody)
                    readNonStreamingAssistantMessage(responseBody, runController, onEvent)
                }
                onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistantMessage)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val sourceType = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        return JSONObject()
            .put("model", config.model)
            .put("stream", config.streamChatCompletions)
            .put("messages", OpenAiRequestMessages.forChatCompletions(
                source = messages,
                normalizeContent = config.normalizeChatContent,
            ))
            .put("tools", tools)
            .put("tool_choice", "auto")
            .also { request ->
                if (sourceType != ProviderSourceTypes.OPENROUTER) {
                    request.put("stream_options", JSONObject().put("include_usage", true))
                }
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                if (!request.optBoolean("stream", true)) {
                    request.remove("stream_options")
                }
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
            }
    }

    /**
     * Persists full, unredacted wire records for local debugging. Logcat only receives the path
     * and byte count because Android truncates long log entries.
     */
    private fun logFullRequest(request: JSONObject) {
        appendDiagnostic("request", request.toString())
    }

    /**
     * Records the final request object after OkHttp has applied its defaults. Authorization is
     * deliberately redacted; request/response bodies remain in their separate local files.
     */
    private fun logRequestMetadata(request: Request, bodyUtf8Bytes: Int) {
        val metadata = JSONObject()
            .put("url", request.url.toString())
            .put("method", request.method)
            .put("body_utf8_bytes", bodyUtf8Bytes)
            .put("body_content_type", request.body?.contentType()?.toString())
            .put("headers", safeHeaders(request.headers))
        appendDiagnostic("request-meta", metadata.toString())
    }

    private fun logResponseMetadata(protocol: Protocol, code: Int, headers: Headers) {
        val metadata = JSONObject()
            .put("protocol", protocol.toString())
            .put("http_code", code)
            .put("headers", safeHeaders(headers))
        appendDiagnostic("response-meta-http-$code", metadata.toString())
    }

    private fun safeHeaders(headers: Headers): JSONObject = JSONObject().also { result ->
        for (index in 0 until headers.size) {
            val name = headers.name(index)
            val value = if (name.equals("Authorization", ignoreCase = true)) {
                "<redacted>"
            } else {
                headers.value(index)
            }
            result.put(name, value)
        }
    }

    private fun logFullResponse(code: Int, body: String) {
        appendDiagnostic("response-http-$code", body)
    }

    private fun appendDiagnostic(kind: String, body: String) {
        val context = AgentAppContext.resolve()
        if (context == null) {
            AndroidAgentLogger.warn("OpenAI chat diagnostic unavailable: application context missing")
            return
        }
        runCatching {
            val directory = File(context.filesDir, DIAGNOSTIC_DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) {
                error("Unable to create diagnostics directory")
            }
            val timestamp = diagnosticTimestamp.format(Date())
            val target = File(directory, "openai-chat-$timestamp-$kind.jsonl")
            FileOutputStream(target, true).bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.append(body)
                if (!body.endsWith('\n')) writer.newLine()
            }
            AndroidAgentLogger.info(
                "OpenAI chat diagnostic saved: path=${target.absolutePath}, bytes=${body.toByteArray(Charsets.UTF_8).size}"
            )
        }.onFailure { throwable ->
            AndroidAgentLogger.warn(
                "OpenAI chat diagnostic write failed: ${throwable.javaClass.simpleName}"
            )
        }
    }

    private fun readNonStreamingAssistantMessage(
        body: String,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): JSONObject {
        runController.throwIfCancelled()
        val root = JSONObject(body)
        throwStreamingErrorIfPresent(root)
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: error("模型接口未返回 choices[0]")
        val source = choice.optJSONObject("message")
            ?: error("模型接口未返回 assistant message")
        val content = source.optString("content")
        val reasoning = source.optString("reasoning_content")
            .ifBlank { source.optString("reasoning") }
        var contentIndex = 0

        fun emit(kind: AssistantBlockKind, text: String) {
            if (text.isEmpty()) return
            val index = contentIndex++
            onEvent(ProviderEvent.BlockStart(kind, index))
            onEvent(ProviderEvent.BlockDelta(kind, index, text))
            onEvent(ProviderEvent.BlockEnd(kind, index, content = text))
        }
        emit(AssistantBlockKind.THINKING, reasoning)
        emit(AssistantBlockKind.TEXT, content)

        val toolCalls = source.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (position in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(position) ?: continue
                val function = call.optJSONObject("function")
                val arguments = function?.optString("arguments").orEmpty()
                val index = contentIndex++
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, index))
                if (arguments.isNotEmpty()) {
                    onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, index, arguments))
                }
                onEvent(ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = index,
                    blockId = call.optString("id").ifBlank { null },
                    name = function?.optString("name")?.ifBlank { null },
                    content = arguments,
                ))
            }
        }

        val usage = parseUsage(root)
        usage?.let { onEvent(ProviderEvent.Usage(it)) }
        return JSONObject(source.toString())
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", choice.optString("finish_reason"))
            .also { message -> usage?.let { message.put("usage", it.toJson()) } }
    }

    private fun readStreamingAssistantMessage(
        stream: java.io.InputStream?,
        responseCode: Int,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        if (stream == null) error("模型接口未返回响应流")
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null
        val rawSseResponse = StringBuilder()
        var nextContentIndex = 0
        var activeVisibleBlock: StreamingVisibleBlock? = null

        fun finishActiveVisibleBlock() {
            val block = activeVisibleBlock ?: return
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = block.kind,
                    index = block.contentIndex,
                    content = block.content.toString(),
                )
            )
            activeVisibleBlock = null
        }

        fun appendVisibleDelta(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            var block = activeVisibleBlock
            if (block?.kind != kind) {
                finishActiveVisibleBlock()
                block = StreamingVisibleBlock(
                    kind = kind,
                    contentIndex = nextContentIndex++,
                ).also { created ->
                    activeVisibleBlock = created
                    onEvent(ProviderEvent.BlockStart(kind, created.contentIndex))
                }
            }
            block.content.append(delta)
            onEvent(ProviderEvent.BlockDelta(kind, block.contentIndex, delta))
        }

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                rawSseResponse.append(line).append('\n')
                if (!line.startsWith("data:")) continue
                sawStreamData = true
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) continue
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                val chunk = JSONObject(payload)
                throwStreamingErrorIfPresent(chunk)
                parseUsage(chunk)?.let { parsedUsage ->
                    usage = parsedUsage
                    onEvent(ProviderEvent.Usage(parsedUsage))
                }
                val choices = chunk.optJSONArray("choices")
                if (choices == null || choices.length() == 0) continue
                val choice = choices.optJSONObject(0) ?: continue
                val reason = choice.optString("finish_reason")
                if (reason.isNotBlank() && reason != "null") {
                    finishReason = reason
                }
                if (reason == "error") {
                    error("模型接口 SSE 以 error 结束")
                }
                val delta = choice.optJSONObject("delta") ?: continue
                val reasoningDelta = when {
                    delta.has("reasoning_content") && !delta.isNull("reasoning_content") ->
                        delta.optString("reasoning_content")
                    delta.has("reasoning") && !delta.isNull("reasoning") ->
                        delta.optString("reasoning")
                    else -> ""
                }
                if (reasoningDelta.isNotEmpty()) {
                    reasoningContent.append(reasoningDelta)
                    appendVisibleDelta(AssistantBlockKind.THINKING, reasoningDelta)
                }
                if (delta.has("content") && !delta.isNull("content")) {
                    val text = delta.optString("content")
                    if (text.isNotEmpty()) {
                        content.append(text)
                        appendVisibleDelta(AssistantBlockKind.TEXT, text)
                    }
                }
                val deltaToolCalls = delta.optJSONArray("tool_calls") ?: continue
                if (deltaToolCalls.length() > 0) finishActiveVisibleBlock()
                for (i in 0 until deltaToolCalls.length()) {
                    val item = deltaToolCalls.optJSONObject(i) ?: continue
                    val index = item.optInt("index", i)
                    val call = toolCalls.getOrPut(index) {
                        StreamingToolCall(
                            index = index,
                            contentIndex = nextContentIndex++,
                        ).also { created ->
                            onEvent(
                                ProviderEvent.BlockStart(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = created.contentIndex,
                                )
                            )
                        }
                    }
                    if (item.has("id") && !item.isNull("id")) call.id = item.optString("id")
                    if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                    val function = item.optJSONObject("function")
                    val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                    val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                    if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                    if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                    if (argsDelta.isNotEmpty()) {
                        onEvent(
                            ProviderEvent.BlockDelta(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = call.contentIndex,
                                delta = argsDelta,
                            )
                        )
                    }
                }
            }
        }

        logFullResponse(responseCode, rawSseResponse.toString())
        if (!sawStreamData) error("模型接口未返回 SSE data chunk")
        if (!sawDone && finishReason == null) error("模型接口 SSE 流未正常结束")

        finishActiveVisibleBlock()
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEachIndexed { position, call ->
                                array.put(call.toJson(position))
                            }
                        }
                    )
                }
            }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(position: Int): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", id ?: "tool_call_$position")
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private data class StreamingVisibleBlock(
        val kind: AssistantBlockKind,
        val contentIndex: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun throwStreamingErrorIfPresent(chunk: JSONObject) {
        val streamError = chunk.optJSONObject("error") ?: return
        val code = streamError.opt("code")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val errorType = streamError.optJSONObject("metadata")
            ?.optString("error_type")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val context = listOfNotNull(
            code?.let { "code=$it" },
            errorType?.let { "type=$it" },
        ).joinToString(", ")
        val message = streamError.optString("message")
            .ifBlank { "未提供错误信息" }
            .compactError()
        error("模型接口 SSE 返回错误${context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}：$message")
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? {
        val usage = chunk.optJSONObject("usage") ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt("cache_read_input_tokens")
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
