package com.example.summarize.controller;

import com.example.summarize.model.QueryRequest;
import com.example.summarize.model.QueryResponse;
import com.example.summarize.service.QueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = queryService.query(request.getQuestion(), request.getTopK());
        return ResponseEntity.ok(response);
    }
}
