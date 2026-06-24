package com.example.summarize.service;

import com.example.summarize.model.IngestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final VectorStore vectorStore;
    private final ResourcePatternResolver resourcePatternResolver;
    private final TokenTextSplitter textSplitter;

    @Value("${document.files-path:classpath:files/}")
    private String filesPath;

    @Value("${document.chunk-size:800}")
    private int chunkSize;

    private final AtomicBoolean ingested = new AtomicBoolean(false);
    private final List<String> ingestedFiles = Collections.synchronizedList(new ArrayList<>());
    private volatile int totalChunksStored = 0;

    public DocumentIngestionService(VectorStore vectorStore,
                                    ResourcePatternResolver resourcePatternResolver) {
        this.vectorStore = vectorStore;
        this.resourcePatternResolver = resourcePatternResolver;
        this.textSplitter = new TokenTextSplitter();
    }

    // Re-ingesting will add duplicate chunks, so restart the app before re-ingesting changed PDFs.
    public IngestResponse ingest() {
        List<String> processedFiles = new ArrayList<>();
        List<String> failedFiles   = new ArrayList<>();
        int chunkCount = 0;

        try {
            String pattern = filesPath.endsWith("/")
                    ? filesPath + "*.pdf"
                    : filesPath + "/*.pdf";

            Resource[] resources = resourcePatternResolver.getResources(pattern);

            if (resources.length == 0) {
                return new IngestResponse(false, List.of(), 0,
                        "No PDF files found at: " + pattern + ". Drop PDFs into src/main/resources/files/");
            }

            for (Resource resource : resources) {
                String filename = resource.getFilename();
                log.info("Ingesting: {}", filename);

                try {
                    List<Document> chunks = processFile(resource, filename);
                    vectorStore.add(chunks);
                    chunkCount += chunks.size();
                    processedFiles.add(filename);
                    log.info("  -> {} chunks embedded and stored", chunks.size());

                } catch (HttpClientErrorException.TooManyRequests e) {
                    // Propagate immediately so the caller sees a rate-limit error, not a silent success.
                    log.error("Rate limit hit while embedding '{}': {}", filename, e.getMessage());
                    throw new RuntimeException(
                            "Embedding API rate limit exceeded while processing '" + filename + "'. "
                            + "The free tier has low per-minute and per-day quotas. "
                            + "Options: (1) wait and retry, (2) switch embed-provider to 'openai' in application.yml, "
                            + "(3) upgrade your Gemini plan.",
                            e);

                } catch (Exception e) {
                    log.error("Failed to ingest '{}': {}", filename, e.getMessage());
                    failedFiles.add(filename);
                }
            }

            ingestedFiles.addAll(processedFiles);
            totalChunksStored += chunkCount;

            if (!processedFiles.isEmpty()) {
                ingested.set(true);
            }

            String message = processedFiles.isEmpty()
                    ? "No files were ingested successfully. Failed: " + failedFiles
                    : "Ingested " + processedFiles.size() + " file(s). "
                      + (failedFiles.isEmpty() ? "" : "Failed: " + failedFiles);

            return new IngestResponse(!processedFiles.isEmpty(), processedFiles, chunkCount, message);

        } catch (IOException e) {
            log.error("Ingestion error", e);
            return new IngestResponse(false, processedFiles, chunkCount,
                    "Ingestion failed: " + e.getMessage());
        }
    }

    private List<Document> processFile(Resource resource, String filename) {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(resource);
        List<Document> pages = pdfReader.get();

        pages.forEach(doc -> doc.getMetadata().put("source_file", filename));

        List<Document> chunks = textSplitter.apply(pages);

        chunks.forEach(chunk -> chunk.getMetadata().putIfAbsent("source_file", filename));

        return chunks;
    }

    public boolean isIngested() {
        return ingested.get();
    }

    public List<String> getIngestedFiles() {
        return Collections.unmodifiableList(ingestedFiles);
    }

    public int getTotalChunksStored() {
        return totalChunksStored;
    }

    public List<String> listAvailableFiles() {
        try {
            String pattern = filesPath.endsWith("/")
                    ? filesPath + "*.pdf"
                    : filesPath + "/*.pdf";
            Resource[] resources = resourcePatternResolver.getResources(pattern);
            return Arrays.stream(resources)
                    .map(Resource::getFilename)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            log.error("Could not list files", e);
            return List.of();
        }
    }
}
