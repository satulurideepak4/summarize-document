package com.example.summarize.controller;

import com.example.summarize.model.ActivateModelRequest;
import com.example.summarize.model.FineTuneRequest;
import com.example.summarize.model.FineTuneStatusResponse;
import com.example.summarize.service.FineTuningService;
import com.example.summarize.service.ModelOverrideHolder;
import com.example.summarize.service.TrainingDataService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/finetune")
public class FineTuningController {

    private final FineTuningService fineTuningService;
    private final TrainingDataService trainingDataService;
    private final ModelOverrideHolder modelOverrideHolder;

    @Value("${app.chat-provider:openai}")
    private String chatProvider;

    public FineTuningController(FineTuningService fineTuningService,
                                TrainingDataService trainingDataService,
                                ModelOverrideHolder modelOverrideHolder) {
        this.fineTuningService   = fineTuningService;
        this.trainingDataService = trainingDataService;
        this.modelOverrideHolder = modelOverrideHolder;
    }

    @PostMapping("/start")
    public ResponseEntity<FineTuneStatusResponse> start(@RequestBody(required = false) FineTuneRequest request) {
        if (request == null) request = new FineTuneRequest();
        FineTuneStatusResponse job = fineTuningService.startJob(request);
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/jobs")
    public ResponseEntity<List<FineTuneStatusResponse>> listJobs() {
        return ResponseEntity.ok(fineTuningService.listJobs());
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<FineTuneStatusResponse> getJobStatus(@PathVariable String jobId) {
        return ResponseEntity.ok(fineTuningService.getJobStatus(jobId));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<FineTuneStatusResponse> cancelJob(@PathVariable String jobId) {
        return ResponseEntity.ok(fineTuningService.cancelJob(jobId));
    }

    // Downloads the current training data as a JSONL file without starting a job.
    // Useful for inspecting what will be sent to OpenAI before committing.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportTrainingData(
            @RequestParam(value = "minRating", defaultValue = "4") int minRating) throws IOException {

        Path tempFile = trainingDataService.exportToTempFile(minRating);
        try {
            byte[] content = Files.readAllBytes(tempFile);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"training.jsonl\"")
                    .body(content);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // Activates a fine-tuned model for all subsequent queries.
    // Only works when app.chat-provider=openai.
    @PostMapping("/activate")
    public ResponseEntity<Map<String, String>> activate(@Valid @RequestBody ActivateModelRequest request) {
        if (!"openai".equalsIgnoreCase(chatProvider)) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Model override only works when app.chat-provider=openai. " +
                             "Current provider: " + chatProvider));
        }
        modelOverrideHolder.set(request.getModelId());
        return ResponseEntity.ok(Map.of(
                "message",  "Fine-tuned model activated.",
                "modelId",  request.getModelId(),
                "provider", chatProvider
        ));
    }

    // Reverts to the base model configured in application.yml.
    @DeleteMapping("/activate")
    public ResponseEntity<Map<String, String>> deactivate() {
        String previous = modelOverrideHolder.get().orElse("none");
        modelOverrideHolder.clear();
        return ResponseEntity.ok(Map.of(
                "message",       "Reverted to base model.",
                "previousModel", previous
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> activeStatus() {
        return ResponseEntity.ok(Map.of(
                "overrideActive",   modelOverrideHolder.isActive(),
                "activeModelId",    modelOverrideHolder.get().orElse("none (using base model)"),
                "chatProvider",     chatProvider,
                "eligibleExamples", trainingDataService.countEligible(4)
        ));
    }
}
