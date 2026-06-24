package com.example.summarize.service;

import com.example.summarize.model.QueryCacheEntry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class QueryCacheService {

    private static final int MAX_SIZE = 1000;

    // insertion-order LinkedHashMap capped at MAX_SIZE entries
    private final Map<String, QueryCacheEntry> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, QueryCacheEntry> eldest) {
            return size() > MAX_SIZE;
        }
    };

    public synchronized void put(String queryId, QueryCacheEntry entry) {
        cache.put(queryId, entry);
    }

    public synchronized Optional<QueryCacheEntry> get(String queryId) {
        return Optional.ofNullable(cache.get(queryId));
    }

    public synchronized int size() {
        return cache.size();
    }
}
