package com.example.summarize.model;

public class FineTuneStatusResponse {
    private String jobId;
    private String status;
    private String baseModel;
    private String fineTunedModel;
    private String trainingFileId;
    private String createdAt;
    private String finishedAt;
    private String error;

    public FineTuneStatusResponse() {}

    public String getJobId()            { return jobId;         }
    public void   setJobId(String v)    { this.jobId = v;       }

    public String getStatus()           { return status;        }
    public void   setStatus(String v)   { this.status = v;      }

    public String getBaseModel()        { return baseModel;     }
    public void   setBaseModel(String v){ this.baseModel = v;   }

    public String getFineTunedModel()         { return fineTunedModel;   }
    public void   setFineTunedModel(String v) { this.fineTunedModel = v; }

    public String getTrainingFileId()         { return trainingFileId;   }
    public void   setTrainingFileId(String v) { this.trainingFileId = v; }

    public String getCreatedAt()         { return createdAt;    }
    public void   setCreatedAt(String v) { this.createdAt = v;  }

    public String getFinishedAt()         { return finishedAt;   }
    public void   setFinishedAt(String v) { this.finishedAt = v; }

    public String getError()           { return error;      }
    public void   setError(String v)   { this.error = v;    }
}
