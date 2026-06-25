package com.example.summarize.service;

import com.example.summarize.model.QueryCacheEntry;
import com.example.summarize.model.QueryResponse;
import com.example.summarize.model.SourceChunk;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final MeterRegistry meterRegistry;

    private static final int    DEFAULT_TOP_K    = 5;
    private static final int    MAX_RETRIES      = 3;
    private static final long   BASE_BACKOFF_MS  = 1000;

    @Value("${app.chat-provider:openai}")
    private String chatProvider;

    @Value("${document.max-context-chars:12000}")
    private int maxContextChars;

    @Value("${document.similarity-threshold:0.70}")
    private double similarityThreshold;

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
                        ModelOverrideHolder modelOverrideHolder,
                        MeterRegistry meterRegistry) {
        this.vectorStore         = vectorStore;
        this.chatClient          = chatClient;
        this.queryCacheService   = queryCacheService;
        this.modelOverrideHolder = modelOverrideHolder;
        this.meterRegistry       = meterRegistry;
    }

    public QueryResponse query(String question, Integer topKOverride) {
        String queryId = UUID.randomUUID().toString();
        int topK = resolveTopK(topKOverride);
        log.info("Query | queryId={} topK={} question={}", queryId, topK, question);

        Timer.Sample total = Timer.start(meterRegistry);

        List<Document> relevantDocs = retrieveDocuments(question, topK);

        if (relevantDocs.isEmpty()) {
            meterRegistry.counter("query.empty_results").increment();
            total.stop(meterRegistry.timer("query.latency", "result", "empty"));
            return new QueryResponse(queryId,
                    "No relevant content found. Please ingest documents first.",
                    List.of());
        }

        String context = buildContextWithLimit(relevantDocs);
        String answer  = generateAnswerWithRetry(question, context);
        List<SourceChunk> sources = mapSources(relevantDocs);

        queryCacheService.put(queryId, new QueryCacheEntry(question, context, answer));
        meterRegistry.counter("query.success").increment();
        total.stop(meterRegistry.timer("query.latency", "result", "success"));

        return new QueryResponse(queryId, answer, sources);
    }

    private int resolveTopK(Integer override) {
        return (override != null && override > 0) ? override : DEFAULT_TOP_K;
    }

    private List<Document> retrieveDocuments(String question, int topK) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            return vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(question)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold)
                            .build()
            );
        } finally {
            sample.stop(meterRegistry.timer("retrieval.latency"));
        }
    }

    private String buildContextWithLimit(List<Document> docs) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String text = doc.getText();
            String file = (String) doc.getMetadata().getOrDefault("source_file", "unknown");
            String chunk = "[Chunk " + (i + 1) + " | Source: " + file + "]\n" + text + "\n\n";

            if (sb.length() + chunk.length() > maxContextChars) break;
            sb.append(chunk);
        }

        return sb.toString().trim();
    }

    private String generateAnswerWithRetry(String question, String context) {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                Timer.Sample sample = Timer.start(meterRegistry);
                try {
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

                    // Fine-tuned model override is OpenAI-specific.
                    if ("openai".equalsIgnoreCase(chatProvider)) {
                        var override = modelOverrideHolder.get();
                        if (override.isPresent()) {
                            log.debug("Using fine-tuned model: {}", override.get());
                            spec = spec.options(OpenAiChatOptions.builder()
                                    .model(override.get())
                                    .build());
                        }
                    }

                    String result = spec.call().content();
                    sample.stop(meterRegistry.timer("llm.latency", "attempt", String.valueOf(attempt + 1)));
                    return result;

                } catch (Exception ex) {
                    sample.stop(meterRegistry.timer("llm.latency", "attempt", String.valueOf(attempt + 1)));
                    throw ex;
                }

            } catch (Exception ex) {
                if (isRateLimitError(ex)) {
                    long backoff = (long) Math.pow(2, attempt) * BASE_BACKOFF_MS;
                    log.warn("Rate limit hit on attempt {}. Retrying in {} ms.", attempt + 1, backoff);
                    meterRegistry.counter("llm.rate_limit_hits").increment();
                    sleep(backoff);
                } else {
                    log.error("LLM call failed on attempt {}", attempt + 1, ex);
                    throw ex;
                }
            }
        }

        throw new RuntimeException("LLM call failed after " + MAX_RETRIES + " retries due to rate limiting.");
    }

    private boolean isRateLimitError(Exception ex) {
        String msg = ex.getMessage();
        return msg != null && (msg.contains("429") || msg.toLowerCase().contains("rate limit"));
    }

    private void sleep(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for rate limit backoff", e);
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
