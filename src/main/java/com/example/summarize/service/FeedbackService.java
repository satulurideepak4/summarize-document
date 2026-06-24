package com.example.summarize.service;

import com.example.summarize.model.FeedbackEntry;
import com.example.summarize.model.FeedbackRequest;
import com.example.summarize.model.QueryCacheEntry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final QueryCacheService queryCacheService;
    private final ObjectMapper objectMapper;

    @Value("${app.feedback.file:feedback.json}")
    private String feedbackFilePath;

    // queryId -> FeedbackEntry; multiple feedback entries per query are allowed
    private final ConcurrentHashMap<String, FeedbackEntry> store = new ConcurrentHashMap<>();

    public FeedbackService(QueryCacheService queryCacheService, ObjectMapper objectMapper) {
        this.queryCacheService = queryCacheService;
        this.objectMapper      = objectMapper;
    }

    @PostConstruct
    public void loadFromDisk() {
        File file = new File(feedbackFilePath);
        if (!file.exists()) return;
        try {
            List<FeedbackEntry> saved = objectMapper.readValue(file, new TypeReference<>() {});
            saved.forEach(e -> store.put(e.getId(), e));
            log.info("Loaded {} feedback entries from {}", saved.size(), feedbackFilePath);
        } catch (IOException e) {
            log.warn("Could not read feedback file {}: {}", feedbackFilePath, e.getMessage());
        }
    }

    public FeedbackEntry record(FeedbackRequest request) {
        QueryCacheEntry cached = queryCacheService.get(request.getQueryId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Query not found for id: " + request.getQueryId()
                        + ". It may have expired (cache holds the last 1000 queries)."));

        FeedbackEntry entry = new FeedbackEntry(
                UUID.randomUUID().toString(),
                request.getQueryId(),
                cached.getQuestion(),
                cached.getContext(),
                cached.getAnswer(),
                request.getCorrectedAnswer(),
                request.getRating(),
                Instant.now().toString()
        );

        store.put(entry.getId(), entry);
        persist();
        return entry;
    }

    public List<FeedbackEntry> getAll() {
        return store.values().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    public List<FeedbackEntry> getEligible(int minRating) {
        return store.values().stream()
                .filter(e -> e.getRating() >= minRating)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    public int countEligible(int minRating) {
        return (int) store.values().stream().filter(e -> e.getRating() >= minRating).count();
    }

    public boolean delete(String id) {
        boolean removed = store.remove(id) != null;
        if (removed) persist();
        return removed;
    }

    private synchronized void persist() {
        try {
            objectMapper.writeValue(new File(feedbackFilePath), store.values());
        } catch (IOException e) {
            log.error("Failed to persist feedback to {}: {}", feedbackFilePath, e.getMessage());
        }
    }
}
