package com.example.summarize.model;

import java.util.Map;

public class SourceChunk{
    public String content;
    public String sourceFile;
    public Map<String, Object> metadata;

    public SourceChunk(String content, String sourceFile, Map<String, Object> metadata) {
        this.content = content;
        this.sourceFile = sourceFile;
        this.metadata = metadata;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
}
