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
  "queryId": "a3f7c2d1-8b4e-4f9a-bc12-1234567890ab",
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

Every response includes a `queryId`. You use this ID when submitting feedback on the answer, which feeds into the fine-tuning pipeline described below.

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

## Fine-tuning

### Why the base model is not always enough

A general-purpose model like GPT-4o-mini has never seen your specific documents, your organization's terminology, or the particular way you need answers formatted. Out of the box, RAG gets you most of the way there by providing relevant context, but the model still has to figure out the right tone, the right level of detail, and how to correctly interpret domain-specific language from the context alone. Every time you ask a question, you are relying on the model's generic training to bridge that gap.

Fine-tuning closes that gap permanently. You collect real queries and their ideal answers over time, then use that data to train a specialized version of the model. The fine-tuned model has seen hundreds of examples of your questions paired with good answers in the context of your documents. It learns your domain vocabulary, the answer format you expect, and how to use the retrieved context most effectively. The result is answers that are noticeably more accurate, more concise, and better suited to your use case without needing to change anything about the RAG pipeline itself.

### How the fine-tuning loop works

The process is iterative. You collect feedback on real answers, accumulate enough high-quality examples, train a new model version, activate it, and keep collecting feedback on the improved model to make the next version even better.

```
    User asks a question
          |
          v
    App returns answer + queryId
          |
          v
    User rates the answer (1 to 5 stars)
    and optionally provides a corrected answer
          |
          v
    Feedback stored in memory and persisted to feedback.json
          |
          v
    Once enough good feedback is collected (10+ examples rated 4 or 5):
          |
          v
    POST /api/finetune/start
          |
          v
    App exports feedback as JSONL and uploads it to OpenAI
          |
          v
    OpenAI trains a fine-tuned model (takes 15 to 60 minutes typically)
          |
          v
    GET /api/finetune/jobs/{jobId} to check when status = "succeeded"
          |
          v
    POST /api/finetune/activate with the returned fine-tuned model ID
          |
          v
    All subsequent queries use the fine-tuned model automatically
          |
          v
    Continue collecting feedback on the improved model
    to make the next training run even better
```

### Step-by-step guide

**Step 1: Ask questions and collect the queryIds**

Every query response now includes a `queryId` field. Save this ID after each query because you will need it to submit feedback.

```bash
curl -X POST http://localhost:9999/api/query \
  -H "Content-Type: application/json" \
  -d '{"question": "What is the attention mechanism?"}'

# Response includes:
# "queryId": "a3f7c2d1-8b4e-4f9a-bc12-1234567890ab"
```

**Step 2: Rate answers and submit feedback**

After reading the answer, rate it from 1 to 5. If the answer was wrong or incomplete, include a corrected version. That corrected answer will be used as the training target instead of the original.

```bash
curl -X POST http://localhost:9999/api/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "queryId": "a3f7c2d1-8b4e-4f9a-bc12-1234567890ab",
    "rating": 5
  }'

# If the answer needed improvement:
curl -X POST http://localhost:9999/api/feedback \
  -H "Content-Type: application/json" \
  -d '{
    "queryId": "a3f7c2d1-8b4e-4f9a-bc12-1234567890ab",
    "rating": 2,
    "correctedAnswer": "The attention mechanism works by computing a weighted sum of values, where the weights are determined by the compatibility between a query and the corresponding keys..."
  }'
```

Ratings of 4 and 5 are considered good enough for training. Ratings of 1 to 3 are stored but excluded from training data by default. You can inspect your collected feedback at any time:

```bash
# See all feedback
curl http://localhost:9999/api/feedback

# See only high-rated feedback (eligible for training)
curl "http://localhost:9999/api/feedback?minRating=4"

# See a summary with counts and average rating
curl http://localhost:9999/api/feedback/summary
```

**Step 3: Check how many training examples you have**

OpenAI requires a minimum of 10 training examples. You can see the count before starting a job:

```bash
curl http://localhost:9999/api/finetune/status

# Response:
# {
#   "overrideActive": false,
#   "activeModelId": "none (using base model)",
#   "chatProvider": "openai",
#   "eligibleExamples": 23
# }
```

You can also preview exactly what will be sent to OpenAI by downloading the training file first. This is useful for checking that the examples look correct before spending money on a training run.

```bash
curl "http://localhost:9999/api/finetune/export?minRating=4" \
  --output training-preview.jsonl

cat training-preview.jsonl
```

**Step 4: Start a fine-tuning job**

```bash
curl -X POST http://localhost:9999/api/finetune/start \
  -H "Content-Type: application/json" \
  -d '{
    "minRating": 4,
    "baseModel": "gpt-4o-mini-2024-07-18",
    "suffix": "my-docs-v1"
  }'

# Response:
# {
#   "jobId": "ftjob-abc123xyz",
#   "status": "validating_files",
#   "baseModel": "gpt-4o-mini-2024-07-18",
#   "fineTunedModel": null,
#   "trainingFileId": "file-xyz789",
#   "createdAt": "2025-01-15T10:30:00Z"
# }
```

The job returns immediately. Training happens asynchronously on OpenAI's side and typically takes between 15 minutes and a few hours depending on how many examples you have.

**Step 5: Monitor the job until it finishes**

```bash
curl http://localhost:9999/api/finetune/jobs/ftjob-abc123xyz

# Keep checking until status = "succeeded"
# At that point, fineTunedModel will have a value like:
# "fineTunedModel": "ft:gpt-4o-mini-2024-07-18:your-org:my-docs-v1:abc123"
```

You can list all your jobs at once to track multiple runs:

```bash
curl http://localhost:9999/api/finetune/jobs
```

**Step 6: Activate the fine-tuned model**

Once the job status shows `"succeeded"`, copy the `fineTunedModel` ID and activate it. All queries from this point forward will use the fine-tuned model instead of the base model.

```bash
curl -X POST http://localhost:9999/api/finetune/activate \
  -H "Content-Type: application/json" \
  -d '{"modelId": "ft:gpt-4o-mini-2024-07-18:your-org:my-docs-v1:abc123"}'

# Response:
# {
#   "message": "Fine-tuned model activated.",
#   "modelId": "ft:gpt-4o-mini-2024-07-18:your-org:my-docs-v1:abc123",
#   "provider": "openai"
# }
```

No restart is needed. The model switch happens live. To revert back to the base model at any time:

```bash
curl -X DELETE http://localhost:9999/api/finetune/activate
```

### Why fine-tuning gives more accurate results

The reason fine-tuning works is that it shifts responsibility. With a base model, the model has to figure out from the system prompt alone how to behave: how to interpret your documents, how much detail to give, how to handle ambiguous context, and what tone to use. With a fine-tuned model, all of that has been baked in through examples. The model has seen your actual questions paired with correct answers hundreds of times, so it does not have to guess.

Specifically, fine-tuning helps in these ways:

**Domain vocabulary.** If your documents use specialized terms that a general model might misinterpret or paraphrase incorrectly, a fine-tuned model learns what those terms mean in your context. After seeing enough examples, it uses them precisely the way your documents do.

**Handling context correctly.** The RAG system always provides retrieved chunks as context, but a base model does not always use that context in the most effective way. It might over-rely on its general training knowledge instead. A fine-tuned model has been trained specifically on examples where good answers came from correctly reading the provided context, so it learns to prioritize the context over its prior knowledge.

**Consistent answer format.** If you want answers in a specific structure (bullet points, concise summaries, numbered steps), the corrected answers you provide during feedback teach the model exactly what format you prefer. After training, it applies that format consistently without needing it spelled out in every system prompt.

**Fewer hallucinations.** A base model that is unsure about something may fill in gaps with plausible-sounding but incorrect information. A fine-tuned model that has seen many examples of "I don't have enough information to answer this from the provided context" learns that saying so is the right behavior, not a failure.

**Better use of corrections.** When you provide a corrected answer for a low-rated response, you are directly telling the model where it went wrong. That correction becomes a training example that makes the model less likely to make the same mistake again. Over multiple training runs, the model gets systematically better on exactly the kinds of questions your users actually ask.

### Important notes about fine-tuning

Fine-tuning is only available when `app.chat-provider` is set to `openai`. This is because only OpenAI exposes a public fine-tuning API that works the way this pipeline is designed. Anthropic and Gemini do not support the same workflow. You still need an OpenAI API key even if you are using a different provider for regular queries.

OpenAI charges for fine-tuning based on the number of training tokens. At current pricing, a dataset of 50 examples averages roughly $0.10 to $0.30 for a gpt-4o-mini fine-tuning run. The fine-tuned model also costs slightly more per query than the base model. Check OpenAI's pricing page for current rates.

Feedback is saved to `feedback.json` in the project root and survives restarts. The model override set via `/api/finetune/activate` does not survive restarts. After a restart you need to call `/api/finetune/activate` again with the same model ID if you want to continue using the fine-tuned model.

---

## Fine-tuning API reference

### POST /api/feedback

Submits feedback on a query response.

| Field | Type | Required | Description |
|---|---|---|---|
| `queryId` | string | yes | The queryId from the query response |
| `rating` | integer | yes | Score from 1 (bad) to 5 (perfect) |
| `correctedAnswer` | string | no | A better answer to use as the training target |

```json
{
  "feedbackId": "fb-123",
  "queryId": "a3f7c2d1-...",
  "status": "recorded",
  "message": "Feedback saved. Rating: 5/5."
}
```

---

### GET /api/feedback

Lists all stored feedback. Pass `?minRating=4` to filter to training-eligible entries only.

---

### GET /api/feedback/summary

Returns counts and averages across all collected feedback.

```json
{
  "total": 47,
  "averageRating": 3.8,
  "ratingDistribution": { "1": 3, "2": 5, "3": 8, "4": 18, "5": 13 },
  "eligibleForTraining": 31
}
```

---

### DELETE /api/feedback/{id}

Removes a specific feedback entry from the store and from `feedback.json`.

---

### POST /api/finetune/start

Exports eligible feedback as JSONL, uploads it to OpenAI, and starts a fine-tuning job.

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `minRating` | integer | no | 4 | Minimum rating to include in training |
| `baseModel` | string | no | gpt-4o-mini-2024-07-18 | Base model to fine-tune from |
| `suffix` | string | no | none | Optional label appended to the model name |

Returns the initial job status. The job runs asynchronously.

---

### GET /api/finetune/jobs

Lists the 20 most recent fine-tuning jobs.

---

### GET /api/finetune/jobs/{jobId}

Returns the current status of a specific job. Poll this until `status` is `"succeeded"` or `"failed"`.

---

### POST /api/finetune/jobs/{jobId}/cancel

Cancels a job that is still running.

---

### GET /api/finetune/export

Downloads the training data that would be sent to OpenAI as a `.jsonl` file. Does not start a job. Useful for previewing or auditing the training examples before committing.

| Param | Default | Description |
|---|---|---|
| `minRating` | 4 | Minimum rating to include |

---

### POST /api/finetune/activate

Activates a fine-tuned model for all subsequent queries. No restart needed.

```json
{ "modelId": "ft:gpt-4o-mini-2024-07-18:your-org:suffix:abc123" }
```

---

### DELETE /api/finetune/activate

Reverts all queries back to the base model.

---

### GET /api/finetune/status

Shows which model is currently active, how many eligible training examples exist, and which chat provider is configured.

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

  feedback:
    file: feedback.json       # where to persist feedback across restarts
                              # relative to working directory, or use an absolute path

  finetune:
    base-model: gpt-4o-mini-2024-07-18   # default base model for fine-tuning jobs
    min-rating: 4                         # default minimum rating for training data export

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
        OpenAiRestClientConfig.java   RestClient bean used for OpenAI fine-tuning API calls
      controller/
        DocumentController.java       handles /api/documents/* endpoints
        QueryController.java          handles /api/query
        FeedbackController.java       handles /api/feedback/* endpoints
        FineTuningController.java     handles /api/finetune/* endpoints
        GlobalExceptionHandler.java   turns exceptions into proper HTTP responses
      model/
        QueryRequest.java             request body for /api/query
        QueryResponse.java            response body (answer, sources, queryId)
        QueryCacheEntry.java          internal cache entry holding question, context, answer
        SourceChunk.java              one retrieved chunk with its metadata
        IngestResponse.java           response body for /api/documents/ingest
        FeedbackRequest.java          request body for POST /api/feedback
        FeedbackEntry.java            stored feedback record (persisted to feedback.json)
        FeedbackResponse.java         response body for POST /api/feedback
        FineTuneRequest.java          request body for POST /api/finetune/start
        FineTuneStatusResponse.java   fine-tuning job status from OpenAI
        ActivateModelRequest.java     request body for POST /api/finetune/activate
      service/
        DocumentIngestionService.java reads PDFs, chunks them, stores embeddings
        QueryService.java             runs similarity search, calls the LLM, caches queries
        QueryCacheService.java        bounded in-memory cache of the last 1000 queries
        FeedbackService.java          stores feedback in memory, persists to feedback.json
        TrainingDataService.java      exports feedback as OpenAI fine-tuning JSONL
        FineTuningService.java        calls OpenAI file upload and fine-tuning APIs
        ModelOverrideHolder.java      thread-safe holder for the active fine-tuned model ID
    resources/
      application.yml                 all configuration
      files/                          drop your PDFs here
  test/
    java/com/example/summarize/
      SummarizeDocumentApplicationTests.java

feedback.json                         feedback persisted across restarts (created at runtime)
.env                                  API keys (never committed)
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

**"Query not found for id" when submitting feedback**
The query cache holds the last 1000 queries in memory and is cleared on restart. If the server was restarted since you made the query, the queryId is no longer available. Make the query again and submit feedback before the next restart.

**"Not enough training examples" when starting a fine-tune job**
You need at least 10 feedback entries with a rating of 4 or 5. Check the count at `GET /api/finetune/status`. Keep asking questions and rating answers until you reach the threshold.

**"OPENAI_API_KEY is required for fine-tuning"**
Fine-tuning always uses OpenAI's API regardless of which chat provider you use for regular queries. Set `OPENAI_API_KEY` in your `.env` file.

**"Model override only works when app.chat-provider=openai"**
Fine-tuned models are OpenAI models and can only be used when the chat provider is set to openai. If you are using Anthropic or Gemini for chat, you cannot activate a fine-tuned GPT model as an override. You would need to switch `app.chat-provider` to `openai` first.

**Fine-tuned model override lost after restart**
The active model override is stored in memory and does not survive restarts. After restarting, call `POST /api/finetune/activate` again with your model ID. The model ID is available from `GET /api/finetune/jobs/{jobId}` anytime after the job succeeds.

**Fine-tuning job shows status "failed"**
Check the `error` field in the job status response for the reason. Common causes are: the training file had fewer than 10 valid examples, the base model name was incorrect, or the OpenAI account hit a fine-tuning quota. Fix the issue and start a new job.

---

## Things worth knowing

Re-ingesting the same files will add duplicate chunks to the vector store because the app does not check for existing entries before inserting. If you need to re-ingest (because you changed a file or the chunk size), restart the app first to clear the store, then call `/ingest` again.

The LLM only sees the chunks that were retrieved by the similarity search. If your question is about something that is not in the indexed PDFs, the model will say it does not have enough information rather than guessing. This is intentional behavior controlled by the system prompt.

The `topK` parameter controls how many chunks are retrieved per query. Higher values give the LLM more context to work with but also increase the number of tokens sent and the cost per query. The default of 5 works well for most questions. For broad summary questions, try 8-10. For very specific factual questions, 3-5 is usually enough.
