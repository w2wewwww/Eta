package fuck.andes.agent.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderUrlsTest {
    @Test
    fun migratesRetiredAnyRouterHostWithoutChangingPathOrOtherHosts() {
        assertEquals(
            "https://anyrouter.win/v1",
            ProviderUrls.normalizeBaseUrl(" https://ANYROUTER.TOP/v1/ "),
        )
        assertEquals(
            "https://api.anyrouter.top/v1",
            ProviderUrls.normalizeBaseUrl("https://api.anyrouter.top/v1"),
        )
        assertEquals(
            "https://example.com/v1",
            ProviderUrls.normalizeBaseUrl("https://example.com/v1"),
        )
    }

    @Test
    fun appendsProviderSpecificPathsToRootBaseUrl() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            ProviderUrls.openAiChatCompletionsUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            ProviderUrls.openAiModelsUrl("https://api.openai.com/v1")
        )
        assertEquals(
            "https://api.openai.com/v1/responses",
            ProviderUrls.openAiResponsesUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/messages",
            ProviderUrls.anthropicMessagesUrl("https://api.anthropic.com/")
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            ProviderUrls.anthropicModelsUrl("https://api.anthropic.com")
        )
        assertEquals(
            "https://anyrouter.win/v1/chat/completions",
            ProviderUrls.openAiChatCompletionsUrl("https://anyrouter.top/v1"),
        )
    }
}
