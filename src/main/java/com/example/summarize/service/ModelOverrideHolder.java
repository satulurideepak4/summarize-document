package com.example.summarize.service;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ModelOverrideHolder {

    private final AtomicReference<String> activeModelId = new AtomicReference<>(null);

    public void set(String modelId) {
        activeModelId.set(modelId);
    }

    public Optional<String> get() {
        return Optional.ofNullable(activeModelId.get());
    }

    public void clear() {
        activeModelId.set(null);
    }

    public boolean isActive() {
        return activeModelId.get() != null;
    }
}
