package com.example.summarize.controller;

import com.example.summarize.model.IngestResponse;
import com.example.summarize.service.DocumentIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;

    public DocumentController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest() {
        IngestResponse result = ingestionService.ingest();
        return result.isSuccess()
                ? ResponseEntity.ok(result)
                : ResponseEntity.unprocessableEntity().body(result);
    }

    @GetMapping("/list")
    public ResponseEntity<List<String>> listFiles() {
        return ResponseEntity.ok(ingestionService.listAvailableFiles());
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "ingested", ingestionService.isIngested(),
                "ingestedFiles", ingestionService.getIngestedFiles(),
                "totalChunks", ingestionService.getTotalChunksStored()
        ));
    }
}
