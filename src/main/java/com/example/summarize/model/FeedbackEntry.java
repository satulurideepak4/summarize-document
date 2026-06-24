package com.example.summarize.model;

public class FeedbackEntry {
    private String id;
    private String queryId;
    private String question;
    private String context;
    private String originalAnswer;
    private String correctedAnswer;
    private int    rating;
    private String createdAt;

    public FeedbackEntry() {}

    public FeedbackEntry(String id, String queryId, String question, String context,
                         String originalAnswer, String correctedAnswer, int rating, String createdAt) {
        this.id              = id;
        this.queryId         = queryId;
        this.question        = question;
        this.context         = context;
        this.originalAnswer  = originalAnswer;
        this.correctedAnswer = correctedAnswer;
        this.rating          = rating;
        this.createdAt       = createdAt;
    }

    public String getId()               { return id;              }
    public void   setId(String v)       { this.id = v;            }

    public String getQueryId()          { return queryId;         }
    public void   setQueryId(String v)  { this.queryId = v;       }

    public String getQuestion()         { return question;        }
    public void   setQuestion(String v) { this.question = v;      }

    public String getContext()          { return context;         }
    public void   setContext(String v)  { this.context = v;       }

    public String getOriginalAnswer()         { return originalAnswer;   }
    public void   setOriginalAnswer(String v) { this.originalAnswer = v; }

    public String getCorrectedAnswer()         { return correctedAnswer;   }
    public void   setCorrectedAnswer(String v) { this.correctedAnswer = v; }

    public int  getRating()         { return rating;         }
    public void setRating(int v)    { this.rating = v;       }

    public String getCreatedAt()        { return createdAt;       }
    public void   setCreatedAt(String v){ this.createdAt = v;     }
}
