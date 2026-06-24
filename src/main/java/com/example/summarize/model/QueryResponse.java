package com.example.summarize.model;

import java.util.List;

public class QueryResponse {

    public String queryId;
    public String answer;
    public List<SourceChunk> sources;

    public QueryResponse(String queryId, String answer, List<SourceChunk> sources) {
        this.queryId  = queryId;
        this.answer   = answer;
        this.sources  = sources;
    }

    public String getQueryId()              { return queryId;          }
    public void   setQueryId(String v)      { this.queryId = v;        }

    public String getAnswer()               { return answer;           }
    public void   setAnswer(String v)       { this.answer = v;         }

    public List<SourceChunk> getSources()              { return sources;          }
    public void              setSources(List<SourceChunk> v) { this.sources = v;  }
}
