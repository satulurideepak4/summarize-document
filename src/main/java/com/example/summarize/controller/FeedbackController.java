package com.example.summarize.controller;

import com.example.summarize.model.FeedbackEntry;
import com.example.summarize.model.FeedbackRequest;
import com.example.summarize.model.FeedbackResponse;
import com.example.summarize.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;

    public FeedbackController(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping
    public ResponseEntity<FeedbackResponse> submit(@Valid @RequestBody FeedbackRequest request) {
        FeedbackEntry entry = feedbackService.record(request);
        FeedbackResponse response = new FeedbackResponse(
                entry.getId(),
                entry.getQueryId(),
                "recorded",
                "Feedback saved. Rating: " + entry.getRating() + "/5."
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<FeedbackEntry>> list(
            @RequestParam(value = "minRating", required = false) Integer minRating) {
        List<FeedbackEntry> results = minRating != null
                ? feedbackService.getEligible(minRating)
                : feedbackService.getAll();
        return ResponseEntity.ok(results);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        List<FeedbackEntry> all = feedbackService.getAll();
        int total = all.size();
        double avgRating = total == 0 ? 0.0
                : all.stream().mapToInt(FeedbackEntry::getRating).average().orElse(0.0);

        Map<Integer, Long> distribution = new java.util.TreeMap<>();
        for (FeedbackEntry e : all) {
            distribution.merge(e.getRating(), 1L, Long::sum);
        }

        return ResponseEntity.ok(Map.of(
                "total",               total,
                "averageRating",       Math.round(avgRating * 10.0) / 10.0,
                "ratingDistribution",  distribution,
                "eligibleForTraining", feedbackService.countEligible(4)
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable String id) {
        boolean deleted = feedbackService.delete(id);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Feedback not found: " + id));
        }
        return ResponseEntity.ok(Map.of("message", "Deleted feedback " + id));
    }
}
