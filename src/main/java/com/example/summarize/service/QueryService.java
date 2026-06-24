package com.example.summarize.service;

import com.example.summarize.model.QueryCacheEntry;
import com.example.summarize.model.QueryResponse;
import com.example.summarize.model.SourceChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final QueryCacheService queryCacheService;
    private final ModelOverrideHolder modelOverrideHolder;

    private static final int DEFAULT_TOP_K       = 5;
    private static final int MAX_CONTEXT_CHARS   = 4000;
    private static final int MAX_RETRIES         = 3;
    private static final long BASE_BACKOFF_MS    = 1000;

    // Gemini free tier allows 15 RPM, so we space calls out to avoid hitting the limit.
    private long lastCallTime = 0;
    private static final long MIN_INTERVAL_MS = 15000;

    @Value("${app.chat-provider:openai}")
    private String chatProvider;

    private static final String SYSTEM_PROMPT = """
            You are an intelligent document assistant. Your job is to answer questions
            accurately using ONLY the context excerpts provided below.

            Rules:
            - Base your answer strictly on the provided context.
            - If the context does not contain enough information to answer, say so clearly.
            - When relevant, mention which document the information came from.
            - Be concise but thorough.
            - Do not hallucinate facts.
            """;

    public QueryService(VectorStore vectorStore,
                        ChatClient chatClient,
                        QueryCacheService queryCacheService,
                        ModelOverrideHolder modelOverrideHolder) {
        this.vectorStore        = vectorStore;
        this.chatClient         = chatClient;
        this.queryCacheService  = queryCacheService;
        this.modelOverrideHolder = modelOverrideHolder;
    }

    public QueryResponse query(String question, Integer topKOverride) {
        String queryId = UUID.randomUUID().toString();
        int topK = resolveTopK(topKOverride);
        log.info("Query received | queryId={} | topK={} | question={}", queryId, topK, question);

        List<Document> relevantDocs = retrieveDocuments(question, topK);

        if (relevantDocs.isEmpty()) {
            return new QueryResponse(queryId,
                    "No relevant content found. Please ingest documents first.",
                    List.of());
        }

        String context = buildContextWithLimit(relevantDocs);
        String answer  = generateAnswerWithRetry(question, context);
        List<SourceChunk> sources = mapSources(relevantDocs);

        queryCacheService.put(queryId, new QueryCacheEntry(question, context, answer));

        return new QueryResponse(queryId, answer, sources);
    }

    private int resolveTopK(Integer override) {
        return (override != null && override > 0) ? override : DEFAULT_TOP_K;
    }

    private List<Document> retrieveDocuments(String question, int topK) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .build()
        );
    }

    private String buildContextWithLimit(List<Document> docs) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String text = doc.getText();

            if (text.length() > 800) {
                text = text.substring(0, 800);
            }

            String file  = (String) doc.getMetadata().getOrDefault("source_file", "unknown");
            String chunk = "[Chunk " + (i + 1) + " | Source: " + file + "]\n" + text + "\n\n";

            if (sb.length() + chunk.length() > MAX_CONTEXT_CHARS) break;
            sb.append(chunk);
        }

        return sb.toString().trim();
    }

    private String generateAnswerWithRetry(String question, String context) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                enforceRateLimit();

                var spec = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(u -> u.text("""
                                Context:
                                ===
                                {context}
                                ===

                                Question: {question}
                                """)
                                .param("context", context)
                                .param("question", question));

                // Apply fine-tuned model override when available and provider supports it.
                // OpenAiChatOptions is OpenAI-specific; silently skip for other providers.
                if ("openai".equalsIgnoreCase(chatProvider)) {
                    var override = modelOverrideHolder.get();
                    if (override.isPresent()) {
                        log.debug("Using fine-tuned model: {}", override.get());
                        spec = spec.options(OpenAiChatOptions.builder()
                                .model(override.get())
                                .build());
                    }
                }

                return spec.call().content();

            } catch (Exception ex) {
                if (isRateLimitError(ex)) {
                    long backoff = (long) Math.pow(2, attempt) * BASE_BACKOFF_MS;
                    log.warn("Rate limit hit. Retrying in {} ms (attempt {})", backoff, attempt + 1);
                    sleep(backoff);
                } else {
                    log.error("LLM call failed", ex);
                    throw ex;
                }
            }
        }

        throw new RuntimeException("Failed after retries due to rate limiting.");
    }

    private void enforceRateLimit() {
        long now      = System.currentTimeMillis();
        long waitTime = MIN_INTERVAL_MS - (now - lastCallTime);
        if (waitTime > 0) sleep(waitTime);
        lastCallTime = System.currentTimeMillis();
    }

    private boolean isRateLimitError(Exception ex) {
        return ex.getMessage() != null && ex.getMessage().contains("429");
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private List<SourceChunk> mapSources(List<Document> docs) {
        return docs.stream()
                .map(doc -> new SourceChunk(
                        doc.getText(),
                        (String) doc.getMetadata().getOrDefault("source_file", "unknown"),
                        doc.getMetadata()
                ))
                .toList();
    }
}
