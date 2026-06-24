package com.example.summarize.model;

public class QueryCacheEntry {
    private final String question;
    private final String context;
    private final String answer;

    public QueryCacheEntry(String question, String context, String answer) {
        this.question = question;
        this.context = context;
        this.answer = answer;
    }

    public String getQuestion() { return question; }
    public String getContext()  { return context;  }
    public String getAnswer()   { return answer;   }
}
