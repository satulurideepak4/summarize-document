package com.example.summarize.service;

import com.example.summarize.model.FeedbackEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class TrainingDataService {

    private static final Logger log = LoggerFactory.getLogger(TrainingDataService.class);

    // OpenAI requires at least 10 training examples to accept a fine-tuning file.
    private static final int MIN_TRAINING_EXAMPLES = 10;

    private static final String SYSTEM_MESSAGE =
            "You are an intelligent document assistant. Your job is to answer questions " +
            "accurately using ONLY the context excerpts provided below. " +
            "If the context does not contain enough information, say so clearly. " +
            "Do not hallucinate facts.";

    private final FeedbackService feedbackService;
    private final ObjectMapper objectMapper;

    public TrainingDataService(FeedbackService feedbackService, ObjectMapper objectMapper) {
        this.feedbackService = feedbackService;
        this.objectMapper    = objectMapper;
    }

    public int countEligible(int minRating) {
        return feedbackService.countEligible(minRating);
    }

    // Writes eligible feedback to a temp JSONL file and returns its path.
    // Caller is responsible for deleting the file after use.
    public Path exportToTempFile(int minRating) throws IOException {
        List<FeedbackEntry> eligible = feedbackService.getEligible(minRating);

        if (eligible.size() < MIN_TRAINING_EXAMPLES) {
            throw new IllegalStateException(
                    "Not enough training examples. Found " + eligible.size() +
                    " entries with rating >= " + minRating +
                    ", but OpenAI requires at least " + MIN_TRAINING_EXAMPLES + ".");
        }

        Path tempFile = Files.createTempFile("finetune-", ".jsonl");
        StringBuilder sb = new StringBuilder();

        for (FeedbackEntry entry : eligible) {
            String line = buildJsonlLine(entry);
            if (line == null) continue;
            sb.append(line).append("\n");
        }

        Files.writeString(tempFile, sb.toString(), StandardCharsets.UTF_8);
        log.info("Wrote {} training examples to {}", eligible.size(), tempFile);
        return tempFile;
    }

    private String buildJsonlLine(FeedbackEntry entry) {
        String assistantContent = entry.getCorrectedAnswer() != null
                ? entry.getCorrectedAnswer()
                : entry.getOriginalAnswer();

        if (assistantContent == null || assistantContent.isBlank()) {
            log.warn("Skipping feedback {} - no usable answer text", entry.getId());
            return null;
        }

        String userContent = "Context:\n===\n" + entry.getContext() + "\n===\n\nQuestion: " + entry.getQuestion();

        Map<String, Object> example = Map.of(
                "messages", List.of(
                        Map.of("role", "system",    "content", SYSTEM_MESSAGE),
                        Map.of("role", "user",      "content", userContent),
                        Map.of("role", "assistant", "content", assistantContent)
                )
        );

        try {
            return objectMapper.writeValueAsString(example);
        } catch (JsonProcessingException e) {
            log.warn("Skipping feedback {} - JSON serialization failed: {}", entry.getId(), e.getMessage());
            return null;
        }
    }
}
