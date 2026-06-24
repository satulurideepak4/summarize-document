package com.example.summarize.model;

import jakarta.validation.constraints.NotBlank;

public class ActivateModelRequest {

    @NotBlank(message = "modelId is required")
    private String modelId;

    public String getModelId()        { return modelId;  }
    public void   setModelId(String v){ this.modelId = v;}
}
