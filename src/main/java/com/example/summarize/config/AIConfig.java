package com.example.summarize.config;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

@Configuration
public class AIConfig {

    // Base URL is the host only - paths are set explicitly so Spring's RestClient
    // doesn't strip "/v1beta/openai" when resolving absolute paths.
    private static final String GEMINI_BASE_URL  = "https://generativelanguage.googleapis.com";
    private static final String GEMINI_CHAT_PATH  = "/v1beta/openai/chat/completions";
    private static final String GEMINI_EMBED_PATH = "/v1beta/openai/embeddings";

    @Value("${app.chat-provider:anthropic}")
    private String chatProvider;   // anthropic | openai | gemini

    @Value("${app.embed-provider:openai}")
    private String embedProvider;  // openai | gemini

    @Value("${spring.ai.anthropic.api-key:}")
    private String anthropicApiKey;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    @Value("${spring.ai.gemini.api-key:}")
    private String googleApiKey;

    // Only the selected provider is instantiated, so only its API key needs to be set.
    @Bean
    public ChatClient chatClient() {
        ChatModel active = switch (chatProvider.toLowerCase()) {
            case "anthropic" -> anthropicChatModel();
            case "openai"    -> openAiChatModel();
            case "gemini"    -> geminiChatModel();
            default -> throw new IllegalStateException(
                    "Unknown app.chat-provider '%s'. Valid values: anthropic | openai | gemini"
                            .formatted(chatProvider));
        };
        return ChatClient.builder(active).build();
    }

    // Anthropic has no public embedding API, so embeddings always use openai or gemini.
    @Bean
    public VectorStore vectorStore() {
        EmbeddingModel embedding = switch (embedProvider.toLowerCase()) {
            case "openai" -> openAiEmbeddingModel();
            case "gemini" -> geminiEmbeddingModel();
            default -> throw new IllegalStateException(
                    "Unknown app.embed-provider '%s'. Valid values: openai | gemini"
                            .formatted(embedProvider));
        };
        return SimpleVectorStore.builder(embedding).build();
    }

    private AnthropicChatModel anthropicChatModel() {
        requireKey(anthropicApiKey, "anthropic", "spring.ai.anthropic.api-key");
        return AnthropicChatModel.builder()
                .anthropicApi(AnthropicApi.builder()
                        .apiKey(anthropicApiKey)
                        .build())
                .defaultOptions(AnthropicChatOptions.builder()
                        .model("claude-sonnet-4-6")
                        .maxTokens(2048)
                        .build())
                .build();
    }

    private OpenAiChatModel openAiChatModel() {
        requireKey(openaiApiKey, "openai", "spring.ai.openai.api-key");
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .apiKey(openaiApiKey)
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .build())
                .build();
    }

    private OpenAiEmbeddingModel openAiEmbeddingModel() {
        requireKey(openaiApiKey, "openai", "spring.ai.openai.api-key");
        return new OpenAiEmbeddingModel(
                OpenAiApi.builder()
                        .apiKey(openaiApiKey)
                        .build(),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("text-embedding-3-small")
                        .build(),
                embeddingRetryTemplate());
    }

    // Gemini exposes an OpenAI-compatible endpoint, so we reuse OpenAiChatModel
    // and OpenAiEmbeddingModel pointed at Google's base URL. No OpenAI key needed.
    private OpenAiChatModel geminiChatModel() {
        requireKey(googleApiKey, "gemini", "spring.ai.gemini.api-key");
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(GEMINI_BASE_URL)
                        .completionsPath(GEMINI_CHAT_PATH)
                        .apiKey(googleApiKey)
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gemini-2.0-flash")
                        .build())
                .build();
    }

    private OpenAiEmbeddingModel geminiEmbeddingModel() {
        requireKey(googleApiKey, "gemini", "spring.ai.gemini.api-key");
        return new OpenAiEmbeddingModel(
                OpenAiApi.builder()
                        .baseUrl(GEMINI_BASE_URL)
                        .embeddingsPath(GEMINI_EMBED_PATH)
                        .apiKey(googleApiKey)
                        .build(),
                MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model("gemini-embedding-001")
                        .build(),
                embeddingRetryTemplate());
    }

    // gemini-embedding-001 shares the gemini-2.0-flash free tier quota (15 RPM).
    // A large PDF can hit this instantly, so we back off and retry automatically.
    private RetryTemplate embeddingRetryTemplate() {
        return RetryTemplate.builder()
                .maxAttempts(4)
                .exponentialBackoff(10_000, 2, 60_000)
                .build();
    }

    private void requireKey(String key, String provider, String property) {
        if (!StringUtils.hasText(key)) {
            throw new IllegalStateException(
                    "Provider '%s' is selected but '%s' is not set. "
                    .formatted(provider, property)
                    + "Export the matching environment variable or set the property in application.yml.");
        }
    }
}
