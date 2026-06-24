package com.example.summarize.service;

import com.example.summarize.model.FineTuneRequest;
import com.example.summarize.model.FineTuneStatusResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class FineTuningService {

    private static final Logger log = LoggerFactory.getLogger(FineTuningService.class);

    private final RestClient openAiClient;
    private final TrainingDataService trainingDataService;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:}")
    private String openaiApiKey;

    public FineTuningService(RestClient openAiRestClient,
                             TrainingDataService trainingDataService,
                             ObjectMapper objectMapper) {
        this.openAiClient       = openAiRestClient;
        this.trainingDataService = trainingDataService;
        this.objectMapper        = objectMapper;
    }

    public FineTuneStatusResponse startJob(FineTuneRequest request) {
        requireApiKey();

        Path jsonlPath = null;
        try {
            jsonlPath = trainingDataService.exportToTempFile(request.getMinRating());

            String fileId = uploadTrainingFile(jsonlPath);
            log.info("Uploaded training file to OpenAI: {}", fileId);

            String jobJson = createFineTuneJob(fileId, request);
            return parseJobResponse(jobJson);

        } catch (IOException e) {
            throw new RuntimeException("Failed to export training data: " + e.getMessage(), e);
        } finally {
            if (jsonlPath != null) {
                try { Files.deleteIfExists(jsonlPath); } catch (IOException ignored) {}
            }
        }
    }

    public FineTuneStatusResponse getJobStatus(String jobId) {
        requireApiKey();
        try {
            String body = openAiClient.get()
                    .uri("/v1/fine_tuning/jobs/" + jobId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            return parseJobResponse(body);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Fine-tune job not found: " + jobId);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("OpenAI error: " + e.getResponseBodyAsString(), e);
        }
    }

    public List<FineTuneStatusResponse> listJobs() {
        requireApiKey();
        try {
            String body = openAiClient.get()
                    .uri("/v1/fine_tuning/jobs?limit=20")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);

            Map<?, ?> parsed = objectMapper.readValue(body, Map.class);
            List<?> data = (List<?>) parsed.get("data");

            return data.stream()
                    .map(item -> {
                        try {
                            return parseJobResponse(objectMapper.writeValueAsString(item));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse OpenAI job list: " + e.getMessage(), e);
        }
    }

    public FineTuneStatusResponse cancelJob(String jobId) {
        requireApiKey();
        try {
            String body = openAiClient.post()
                    .uri("/v1/fine_tuning/jobs/" + jobId + "/cancel")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .body(String.class);
            return parseJobResponse(body);
        } catch (HttpClientErrorException.NotFound e) {
            throw new IllegalArgumentException("Fine-tune job not found: " + jobId);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("OpenAI error: " + e.getResponseBodyAsString(), e);
        }
    }

    private String uploadTrainingFile(Path path) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("purpose", "fine-tune");
        parts.add("file", new FileSystemResource(path));

        try {
            String response = openAiClient.post()
                    .uri("/v1/files")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(String.class);

            Map<?, ?> parsed = objectMapper.readValue(response, Map.class);
            return (String) parsed.get("id");
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("File upload failed: " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse upload response: " + e.getMessage(), e);
        }
    }

    private String createFineTuneJob(String fileId, FineTuneRequest request) {
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("training_file", fileId);
            body.put("model", request.getBaseModel());
            if (StringUtils.hasText(request.getSuffix())) {
                body.put("suffix", request.getSuffix());
            }

            return openAiClient.post()
                    .uri("/v1/fine_tuning/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw new RuntimeException("Fine-tune job creation failed: " + e.getResponseBodyAsString(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize job request: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private FineTuneStatusResponse parseJobResponse(String json) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            FineTuneStatusResponse resp = new FineTuneStatusResponse();
            resp.setJobId((String) map.get("id"));
            resp.setStatus((String) map.get("status"));
            resp.setBaseModel((String) map.get("model"));
            resp.setFineTunedModel((String) map.get("fine_tuned_model"));
            resp.setTrainingFileId((String) map.get("training_file"));

            Object createdAt = map.get("created_at");
            if (createdAt instanceof Number n) {
                resp.setCreatedAt(Instant.ofEpochSecond(n.longValue()).toString());
            }

            Object finishedAt = map.get("finished_at");
            if (finishedAt instanceof Number n) {
                resp.setFinishedAt(Instant.ofEpochSecond(n.longValue()).toString());
            }

            Object error = map.get("error");
            if (error instanceof Map<?, ?> errMap) {
                resp.setError((String) errMap.get("message"));
            }

            return resp;
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse OpenAI response: " + e.getMessage(), e);
        }
    }

    private void requireApiKey() {
        if (!StringUtils.hasText(openaiApiKey)) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is required for fine-tuning even when using Anthropic or Gemini for chat. " +
                    "Set it in .env and restart the application.");
        }
    }
}
