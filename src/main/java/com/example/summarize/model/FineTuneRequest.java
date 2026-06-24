package com.example.summarize.model;

public class FineTuneRequest {

    private String baseModel = "gpt-4o-mini-2024-07-18";

    // Only feedback with rating >= minRating is included in training data.
    private int minRating = 4;

    // Optional suffix tag appended to the fine-tuned model name by OpenAI.
    private String suffix;

    public String getBaseModel()        { return baseModel;  }
    public void   setBaseModel(String v){ this.baseModel = v;}

    public int  getMinRating()          { return minRating;  }
    public void setMinRating(int v)     { this.minRating = v;}

    public String getSuffix()           { return suffix;     }
    public void   setSuffix(String v)   { this.suffix = v;   }
}
