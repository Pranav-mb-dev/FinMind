# FinMind Evaluation Plan
Scope: `ai-service` router-shaped agentic RAG (`agent_rag.py`) and the document retrieval it reuses from `naive_rag.py`.

## 1. Goal

Be able to answer, with numbers, three questions about FinMind's AI service:

1. **Is it fast and affordable?** Latency, tokens and cost per request, per stage.
2. **Does it make the right decisions?** The router picks the right sources and date ranges.
3. **Are the answers right and grounded?** Numbers match Spring's data; document answers are supported by the retrieved chunks.

Learning goal: understand LangSmith, LangChain and RAGAS by using them on this pipeline, one small slice at a time.

## 2. How the pipeline maps to metrics

```
question
  -> [LLM call 1: router]            -> routing metrics
  -> [code: fetch sources]
       documents  (Qdrant, top 5)    -> retrieval metrics, RAGAS
       transactions / summary / budgets (Spring)  -> deterministic number checks
  -> [LLM call 2: answer]            -> faithfulness, correctness
every stage                          -> latency, tokens, cost
```

Rule: use free, deterministic checks first. Spend LLM-judge calls only where nothing else works (document answers).

## 3. Constraints and decisions to make first

| Item | Why it matters | Decision |
|---|---|---|
| Gemini quota | Router + answer = 2 calls per question, and RAGAS judging adds several more per sample. A tight free-tier quota limits the test-set size per day. | Start with ~15 questions. Run in batches across days, or cache outputs. Check the current limit in your Google AI console. |
| Judge model for RAGAS | The default judge may not be Gemini, and judge calls cost quota or money. | Decide in Phase 6: Gemini with a small sample, or a local model. |
| Run location | The GCP VM is paused. | Run everything locally with Docker Compose. No GCP needed. |
| Test data | Evals need stable data. | Create a seeded test user with fixed transactions, budgets and one sample statement PDF. |
| Code version | `agent_rag.py` has uncommitted changes. | Commit or note the current state, then evaluate against that exact commit. Record the commit hash with every result. |

## 4. Folder layout (proposed)

```
ai-service/
  evals/
    datasets/
      routing.json          # question -> expected sources, expected date range
      answers.json          # question -> expected number(s), from seeded data
      documents.json        # question -> reference answer, relevant chunk ids
    run_routing_eval.py
    run_answer_checks.py
    run_retrieval_eval.py
    run_ragas_eval.py
    results/                # one dated JSON/MD per run
  tests/                    # existing pytest folder; fast deterministic checks live here
```

## 5. Phases

Each phase ends with a checkpoint: you explain it back before moving on. A phase is done only when its "Done when" line is true.

### Phase 0: Baseline and setup

- Record the commit hash, model name (`settings.gemini_model`), `chunk_size`, `chunk_overlap`, vector-search candidates, reranker, and final k.
- Create the seeded test user and sample PDF.
- Create the `evals/` folders.

**Done when:** a one-page "configuration snapshot" exists and the seeded data can be loaded repeatably.

### Phase 1: Operational metrics (free)

- Time each stage inside `run()`: router call, fetch (per source), answer call, total.
- Read token counts from each Gemini response's usage metadata. Compute cost from the current model pricing.
- Log one structured JSON line per request.
- Count fallbacks: router JSON parse failures, Spring calls returning `None`.

**Metrics:** p50/p95 latency per stage, input/output tokens, cost per request, fallback rate.

**Done when:** 20 requests produce a table of per-stage latency, tokens and cost.

### Phase 2: Routing accuracy

- Write 15 to 20 labeled questions in `routing.json`. Cover: spending totals, merchant lookups, budget questions, statement-content questions, multi-source questions, small talk, general advice, and date phrases ("last month", "this week", no period given).
- For each, store the expected source set and expected date range.
- Run only the router call (one Gemini call per question). Pass the raw output through `_parse_route`.

**Metrics:** exact source-set match; per-source precision and recall (over-fetching vs. under-fetching); date-range accuracy; fallback rate.

**Done when:** a confusion-style table shows which sources the router gets wrong and on which question types.

### Phase 3: Deterministic answer checks

- With the seeded data, write questions whose answers are known numbers (total expenses for a period, spend in a category, budget limit).
- Assert the number in the answer against the Spring response. Extract numbers with a regex, allow for formatting (commas, currency symbols).
- Add the known-risk cases on purpose:
  - A period with more than 60 transactions (the `MAX_TRANSACTION_ROWS` cap).
  - A category question that depends on summing rows.
  - Spring returning an error (does the user get a misleading "no data" message?).
  - A question when no data exists.

**Metrics:** numeric accuracy, refusal/no-data correctness, behavior under truncation.

**Done when:** each known-risk case has a pass/fail result and, for failures, a note on which stage caused it.

### Phase 4: Tracing with LangSmith

- Set the LangSmith environment variables and send a handful of requests.
- Wrap non-LLM steps with `@traceable` (`retrieve_chunks`, `_spring_get`, `_gather_context`) so the trace shows what was fetched, not just the two LLM calls.
- Read 10 traces by hand. Note surprises.

**Done when:** one trace shows router output, each fetched section, and the final prompt for a single question.

### Phase 5: Document retrieval metrics

- For the sample PDF, write 10 questions and mark which chunk(s) contain the answer.
- Compute recall@k and MRR, before and after the reranker. These need no judge model.

**Done when:** you can say whether the reranker helps, with numbers.

### Phase 6: RAGAS on document questions

- Change what the pipeline exposes so the evaluator can see the retrieved contexts and the final answer (today `run()` returns only document chunks as `sources`).
- Build the RAGAS dataset: question, answer, retrieved contexts, reference answer.
- Run: faithfulness, answer relevancy, context precision, context recall.
- Choose the judge model deliberately (see Section 3). Start with 5 to 10 samples.
- Read the judge's verdicts on a few samples to check that the scores make sense.

**Scope note:** RAGAS applies to the document path only. Transactions, summary and budgets are structured data and are covered by Phase 3.

**Done when:** a baseline score table exists, and you've checked a few scores by hand.

### Phase 7: Experiments

Change one thing at a time, rerun the affected phases, and record the result. Candidates:

- Chunk size and overlap
- Number of candidates and final k
- Reranker on/off
- Router prompt wording
- Passing pre-aggregated category totals to the answer model instead of raw rows

Treat small score changes as noise. Look for large moves, then open the LangSmith trace to explain them.

**Done when:** at least one change is justified by before/after numbers and a trace.

### Later (not in this plan's first pass)

- Prompt versioning
- Production monitoring and sampling of real traces
- User feedback metrics (thumbs, rephrase rate)
- Self-hosted alternatives (Langfuse, Phoenix) if data privacy matters

## 6. Results log template

Copy one block per run into `evals/results/`.

```
Date:
Commit:
Model:
Config: chunk_size=?, overlap=?, candidates=?, k=?, reranker=?
Test set: (file, number of questions)

Operational:  p50/p95 latency (router | fetch | answer | total), avg tokens in/out, avg cost
Routing:      exact match ?/?, over-fetch ?, under-fetch ?, date accuracy ?/?, fallbacks ?
Answers:      numeric accuracy ?/?, truncation cases ?/?
Retrieval:    recall@k ?, MRR ?
RAGAS:        faithfulness ?, answer relevancy ?, context precision ?, context recall ?
Notes / surprises:
```

## 7. Checkpoint questions

1. If the router picks only `["documents"]` for "how much did I spend on food last month?", which stage failed, and would faithfulness catch it?
2. Why is a numeric check a better test than an LLM judge for a spending total?
3. Chunk size goes from 500 to 1000: context precision drops and faithfulness stays flat. What likely happened, and where do you look to confirm it?
4. Why must the evaluation record the commit hash and configuration with every result?

## 8. Suggested order of work

Phase 0, then 1, then 2, then 3, then 4, then 5, then 6, then 7. Phases 1 to 3 need no judge model and give the most information for the least quota, so they come first.