package com.example.summarize.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public class FeedbackRequest {

    @NotBlank(message = "queryId is required")
    private String queryId;

    @Min(value = 1, message = "rating must be at least 1")
    @Max(value = 5, message = "rating must be at most 5")
    private int rating;

    private String correctedAnswer;

    public String getQueryId()              { return queryId;        }
    public void   setQueryId(String v)      { this.queryId = v;      }

    public int    getRating()               { return rating;         }
    public void   setRating(int v)          { this.rating = v;       }

    public String getCorrectedAnswer()      { return correctedAnswer; }
    public void   setCorrectedAnswer(String v) { this.correctedAnswer = v; }
}
