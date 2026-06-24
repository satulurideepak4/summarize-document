# Document Summarization and Semantic Search

A Spring Boot application that lets you drop PDF files into a folder, index them with one API call, and then ask questions about them in plain English. It finds the most relevant sections using vector search and feeds them to an LLM to generate a grounded, cited answer.

You can swap between Anthropic (Claude), OpenAI (GPT), or Google Gemini by changing two lines in `application.yml`. No code changes needed.

---

## What problem does this solve

Reading through long PDFs to find specific information is slow. This app lets you ask a question like "What are the key risks mentioned in section 3?" and get a direct answer with references to which file and which section it came from. It works across multiple PDFs at once, so you can ask a question that spans several documents and it will pull from whichever ones are relevant.

The technique it uses is called Retrieval-Augmented Generation (RAG). Instead of sending the entire PDF to the LLM (which would be expensive and often impossible due to context limits), it first finds only the relevant excerpts using vector similarity search, then sends just those excerpts to the model along with your question. This keeps costs low and makes the answers more focused.

---

## How the RAG pipeline works

There are two distinct phases: ingestion (happens once) and querying (happens every time you ask a question).

### Phase 1 - Ingestion

```
    PDF Files in files/
          |
          v
    PagePdfDocumentReader       reads the PDF page by page
          |
          v
    TokenTextSplitter           breaks each page into 800-token chunks
          |                     so that each chunk fits within API limits
          v
    Embedding Model             converts each chunk of text into a
          |                     high-dimensional float vector (e.g. 1536 dims)
          v
    SimpleVectorStore           stores every vector in memory alongside
                                the original text and source metadata
```

When you call `POST /api/documents/ingest`, the app scans `src/main/resources/files/` for all PDF files, reads each one, splits the text into chunks, generates an embedding vector for each chunk by calling the embedding API, and stores everything in memory. This is done once per application startup. The vectors are lost when the app restarts, so you need to call ingest again after each restart.

Each chunk also carries metadata like `source_file` and `page_number` so answers can cite where they came from.

### Phase 2 - Querying

```
    Your question
          |
          v
    Embedding Model             converts your question into a vector
          |                     using the same model used at ingest time
          v
    Cosine Similarity Search    compares your question vector against
          |                     all stored chunk vectors and picks the
          |                     top K most similar ones
          v
    Prompt Builder              assembles a prompt that includes the
          |                     retrieved chunks as context and your
          |                     question at the end
          v
    LLM (Claude / GPT / Gemini) reads only the provided context and
          |                     generates an answer grounded in it
          v
    Response                    answer text + list of source chunks
                                with file names and original content
```

When you call `POST /api/query`, the app embeds your question, runs a similarity search to find the top matching chunks, builds a prompt with those chunks as context, calls the LLM, and returns the answer along with the source chunks it used.

### Why this works better than sending the whole PDF

Vector similarity search finds chunks that are semantically similar to your question, not just keyword matches. If you ask "What are the risks?", it will find chunks talking about "potential downsides", "challenges", or "concerns" even if those exact words are not in your question. The LLM then gets only the relevant parts rather than hundreds of pages, which makes it faster, cheaper, and less likely to lose focus.

---

## Prerequisites

- Java 21 or higher
- Maven (or use the included `mvnw` wrapper)
- An API key for at least one provider (see the Provider Comparison section below)

---

## Setup

### 1. Clone and configure providers

Open `application.yml` and set which provider you want to use for chat and for embeddings:

```yaml
app:
  chat-provider:  openai   # anthropic | openai | gemini
  embed-provider: openai   # openai | gemini
```

Note that Anthropic does not offer an embedding API, so if you use Claude for chat you still need either an OpenAI or Gemini key for embeddings.

### 2. Set your API keys in .env

The project uses a `.env` file at the root level to store API keys. Open it and fill in the key for whichever provider you chose:

```
ANTHROPIC_API_KEY=your_anthropic_key_here
OPENAI_API_KEY=your_openai_key_here
GOOGLE_API_KEY=your_google_key_here
```

You only need to fill in the key for the provider you are actually using. The app will throw a clear error at startup if a required key is missing.

The `.env` file is listed in `.gitignore` so it will never be accidentally committed.

### 3. Add your PDF files

Copy your PDF files into:

```
src/main/resources/files/
```

The app scans this folder automatically when you call the ingest endpoint. You can add as many PDFs as you want.

### 4. Start the app

```bash
./mvnw spring-boot:run
```

The server starts on port 9999.

### 5. Ingest your documents

```bash
curl -X POST http://localhost:9999/api/documents/ingest
```

This reads all PDFs, chunks them, generates embeddings, and stores the vectors in memory. You will see log output for each file as it processes. Call this once after every startup.

### 6. Start asking questions

```bash
curl -X POST http://localhost:9999/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What are the main findings?"}'
```

---

## API reference

### POST /api/documents/ingest

Scans `src/main/resources/files/` for PDFs, chunks them, generates embeddings, and stores the vectors. Call this once per app startup before making any queries.

**Response:**
```json
{
  "success": true,
  "ingestedFiles": ["transformer-architecture.pdf", "AI_Concepts_Guide.pdf"],
  "totalChunks": 47,
  "message": "Ingested 2 file(s)."
}
```

If a file fails (corrupted PDF, parse error), it is skipped and listed in the message. If the embedding API rate limit is hit, the request fails immediately with a 429 and a descriptive error message.

---

### POST /api/query

Runs a semantic search over the ingested documents and generates a grounded answer.

**Request body:**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `question` | string | yes | - | The question to ask |
| `topK` | integer | no | 5 | How many chunks to retrieve and use as context |

**Example request:**
```json
{
  "question": "How does multi-head attention work?",
  "topK": 5
}
```

**Example response:**
```json
{
  "answer": "Multi-head attention works by running multiple attention functions in parallel. Each head projects the input into a different subspace, allowing the model to capture different types of relationships simultaneously. The outputs are concatenated and projected to produce the final representation.",
  "sources": [
    {
      "content": "Multi-head attention allows the model to jointly attend to information from different representation subspaces...",
      "sourceFile": "transformer-architecture.pdf",
      "metadata": {
        "page_number": 4,
        "source_file": "transformer-architecture.pdf"
      }
    }
  ]
}
```

The `sources` array lists every chunk that was sent to the LLM as context. This lets you verify where the answer came from and read the original text if you want more detail.

---

### GET /api/documents/list

Returns a list of all PDF files currently in the files directory, regardless of whether they have been ingested yet.

**Response:**
```json
["transformer-architecture.pdf", "AI_Concepts_Guide.pdf"]
```

---

### GET /api/documents/status

Returns the current state of the vector store.

**Response:**
```json
{
  "ingested": true,
  "ingestedFiles": ["transformer-architecture.pdf"],
  "totalChunks": 24
}
```

---

## Configuration reference

All configuration lives in `src/main/resources/application.yml`.

```yaml
app:
  chat-provider: openai       # which LLM to use for generating answers
                              # options: anthropic | openai | gemini
  embed-provider: openai      # which model to use for embeddings
                              # options: openai | gemini
                              # (anthropic is not an option here - no public embedding API)

document:
  files-path: classpath*:files/   # where to look for PDFs
                                   # change to file:/absolute/path/ to use an external folder
  chunk-size: 800                 # how many tokens per chunk (affects retrieval granularity)
  top-k: 5                        # default number of chunks to retrieve per query

server:
  port: 9999                      # change this if 9999 is taken on your machine
```

### Changing the chunk size

Smaller chunks (300-500 tokens) give more precise retrieval but less context per chunk. Larger chunks (1000-1500 tokens) give more context but the retrieval may be less focused. 800 is a reasonable default for most documents. If you change this, restart the app and re-ingest so the stored vectors match the new chunk size.

### Using an external files folder

By default the app reads from `src/main/resources/files/` which is bundled into the JAR. If you want to point it at a folder outside the project:

```yaml
document:
  files-path: file:/Users/yourname/documents/pdfs/
```

### Persisting vectors across restarts

The default `SimpleVectorStore` is in-memory and resets on every restart. To persist vectors to disk, update `AIConfig.java`:

```java
return SimpleVectorStore.builder(embedding)
        .storageFile(new java.io.File("vector-store.json"))
        .build();
```

The vectors will be saved to `vector-store.json` in the working directory and loaded automatically on the next startup.

---

## Provider comparison

| Chat provider | Embed provider | Keys needed | Rough cost per query | Notes |
|---|---|---|---|---|
| openai | openai | OPENAI_API_KEY | ~$0.001 | Cheapest, one key covers both |
| anthropic | openai | Both keys | ~$0.004 | Better answer quality |
| gemini | gemini | GOOGLE_API_KEY | Free tier | Shared quota, see below |
| anthropic | gemini | Both keys | ~$0.003 | Claude answers, free embeddings |

### Gemini free tier quota

The free tier for Gemini allows 15 requests per minute and 1500 requests per day. `gemini-embedding-001` counts against the same quota as `gemini-2.0-flash`. When you ingest a large PDF, the app sends one embedding request per chunk. A 50-page document might produce 50+ chunks and can hit the per-minute limit quickly.

The app handles this with automatic retry and exponential backoff (retries up to 4 times with delays of 10s, 20s, 40s, 60s). If the daily limit is exhausted, retrying will not help. In that case, switch to `embed-provider: openai` in `application.yml` and restart.

---

## Project structure

```
src/
  main/
    java/com/example/summarize/
      config/
        AIConfig.java                 picks and wires the chat and embed providers at startup
      controller/
        DocumentController.java       handles /api/documents/* endpoints
        QueryController.java          handles /api/query
        GlobalExceptionHandler.java   turns exceptions into proper HTTP responses
      model/
        QueryRequest.java             request body for /api/query
        QueryResponse.java            response body with answer and sources
        SourceChunk.java              one retrieved chunk with its metadata
        IngestResponse.java           response body for /api/documents/ingest
      service/
        DocumentIngestionService.java reads PDFs, chunks them, stores embeddings
        QueryService.java             runs similarity search and calls the LLM
    resources/
      application.yml                 all configuration
      files/                          drop your PDFs here
  test/
    java/com/example/summarize/
      SummarizeDocumentApplicationTests.java
```

---

## Troubleshooting

**"No PDF files found"**
The files directory is empty or the path is wrong. Check that your PDFs are in `src/main/resources/files/` and that `document.files-path` in `application.yml` is set correctly.

**"Provider X is selected but spring.ai.X.api-key is not set"**
Open `.env` at the project root and fill in the API key for the provider you selected. The app validates this at startup before doing anything else.

**429 rate limit errors during ingest**
You hit the per-minute limit on the embedding API. If you are using Gemini, the app will retry automatically. If retries are exhausted or you hit the daily limit, switch `embed-provider` to `openai` in `application.yml`.

**"No relevant content found"**
The vector store is empty. Call `POST /api/documents/ingest` first, then retry your query.

**Answers seem wrong or hallucinated**
The LLM is instructed to answer only from the provided context. If it seems to be making things up, check the `sources` array in the response to see which chunks were actually sent. If the relevant information is not in those chunks, try increasing `topK` in your request (e.g. `"topK": 10`) so more context is retrieved.

**Vectors lost after restart**
This is expected. The in-memory vector store does not persist to disk by default. Call `/api/documents/ingest` again after each restart, or configure a storage file as described in the Configuration section above.

---

## Things worth knowing

Re-ingesting the same files will add duplicate chunks to the vector store because the app does not check for existing entries before inserting. If you need to re-ingest (because you changed a file or the chunk size), restart the app first to clear the store, then call `/ingest` again.

The LLM only sees the chunks that were retrieved by the similarity search. If your question is about something that is not in the indexed PDFs, the model will say it does not have enough information rather than guessing. This is intentional behavior controlled by the system prompt.

The `topK` parameter controls how many chunks are retrieved per query. Higher values give the LLM more context to work with but also increase the number of tokens sent and the cost per query. The default of 5 works well for most questions. For broad summary questions, try 8-10. For very specific factual questions, 3-5 is usually enough.
