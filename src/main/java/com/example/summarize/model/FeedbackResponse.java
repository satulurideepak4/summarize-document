package com.example.summarize.model;

public class FeedbackResponse {
    private String feedbackId;
    private String queryId;
    private String status;
    private String message;

    public FeedbackResponse(String feedbackId, String queryId, String status, String message) {
        this.feedbackId = feedbackId;
        this.queryId    = queryId;
        this.status     = status;
        this.message    = message;
    }

    public String getFeedbackId()       { return feedbackId; }
    public String getQueryId()          { return queryId;    }
    public String getStatus()           { return status;     }
    public String getMessage()          { return message;    }
}
