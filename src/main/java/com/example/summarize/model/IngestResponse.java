package com.example.summarize.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

public class IngestResponse {
    public boolean success;
    public List<String> ingestedFiles;
    public int totalChunks;
    public String message;

    public IngestResponse(boolean success, List<String> ingestedFiles, int totalChunks, String message) {
        this.success = success;
        this.ingestedFiles = ingestedFiles;
        this.totalChunks = totalChunks;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public List<String> getIngestedFiles() {
        return ingestedFiles;
    }

    public void setIngestedFiles(List<String> ingestedFiles) {
        this.ingestedFiles = ingestedFiles;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(int totalChunks) {
        this.totalChunks = totalChunks;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
