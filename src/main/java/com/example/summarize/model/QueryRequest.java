package com.example.summarize.model;

import jakarta.validation.constraints.NotBlank;

public class QueryRequest {
    @NotBlank(message = "question must not be blank")
    public String question;
    public Integer topK;

    public QueryRequest(String question, Integer topK) {
        this.question = question;
        this.topK = topK;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }
}
