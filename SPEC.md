# nf-jev — design spec

Status: draft · 2026-09-20

## Summary

`nf-jev` exposes [TypeSafe](https://docs.typesafe.ai) System One judgments as ordinary
Nextflow functions. A pipeline builds typed questions as values, calls `jev(state, questions)`,
and gates on the returned probabilities with plain Nextflow operators.

Four functions, nothing else:

```
noul   (instructions)             -> question    // is this true?
choice (instructions, criteria)   -> question    // which one?
score  (instructions, levels)     -> question    // how much?

jev    (state, questions)         -> answers     // one request, all questions, shared state
```

## Why a plugin, and why functions

TypeSafe's design premise is that **code owns the workflow** and the model supplies semantic
judgment where ordinary code cannot. That is the inverse of the Nextflow `agent` primitive,
where the model owns the control flow and decides which tools to run and for how many turns.

Trying to host Jev inside `agent` means fighting that inversion at every seam — where the
`criteria` of a Choice come from, where the probability distribution goes once the record only
has room for the winner, what `tools` / `goal` / `maxIterations` mean for a model with no
conversation. As plain functions those questions dissolve: a question is a map, an answer is a
map, and the threshold lives in `nextflow.config` next to every other pipeline parameter.

Jev is also not an LLM. It never generates text, holds no conversation, and calls no tools; a
request either answers or fails. A single stateless HTTPS call needs no container, no work
directory, no RPC broker and no tool dispatcher — so the driver is the right place to make it,
and a plugin is the right place to put it.

### What this deliberately gives up

Functions are not tasks, so a judgment sits **outside the lineage graph** and leaves no provenance
trail. The optional cache below recovers replay, but not the audit trail: when a judgment must be
provenanced, put it behind a process. A trace artifact stays out of scope until a real regulated
use case asks for one.

## The API

### Question builders

Each builder returns a plain `Map` in the shape the API expects. They are values: build them
once at the top of a script, reuse them across a whole cohort, pass them around.

```nextflow
noul('The sample is derived from Homo sapiens.')
// [type: 'noul', instructions: '...']

choice('Which assay does this sequencing run describe?',
       ['RNA-seq' : 'transcriptome sequencing',
        'ATAC-seq': 'chromatin accessibility',
        'unknown' : 'no assay is evidenced'])
// [type: 'choice', instructions: '...', criteria: [...]]

score('How well evidenced is the tissue of origin?',
      ['not mentioned', 'inferable only from a cell-line name', 'stated explicitly'])
// [type: 'score', instructions: '...', criteria: [...]]
```

`criteria` for a Choice is an option → meaning map; for a Score it is an **ordered** list of
level descriptions, lowest first. Both are passed through unchanged.

### The call

```nextflow
jev(state, questions)
```

- `state` — the shared context every question is evaluated against. A `String`, or a `Map` /
  `List` serialized to JSON. This is the only place the pipeline's data goes.
- `questions` — question id → question. All questions in one call are evaluated **in parallel
  against the same state** and cannot see one another's answers, which is what makes a
  multi-question rubric cost one request.
- returns — question id → answer, exactly as the service returns it.

### Answer shapes

```groovy
answers.is_human     // [type: 'noul', noul: 0.98]
answers.assay        // [type: 'choice', choice: 'RNA-seq', confidence: 0.99,
                     //  probabilities: ['RNA-seq': 0.99, 'ATAC-seq': 0.0, ...]]
answers.tissue       // [type: 'score', score: 1.97, confidence: 0.96,
                     //  legend: ['0': '...', '1': '...', '2': '...'],
                     //  probabilities: ['0': 0.0, '1': 0.02, '2': 0.98]]
```

A `noul` carries no separate confidence: the probability *is* the answer, and 0.5 means "equally
likely either way", not "medium". `confidence` on a Choice or Score measures how concentrated the
distribution is — not whether the answer is correct.

## Configuration

```groovy
jev {
    apiKey   = secrets.TYPESAFE_API_KEY   // or the TYPESAFE_API_KEY environment variable
    model    = 'jev-latest'               // pin a snapshot for reproducibility
    endpoint = 'https://api.typesafe.ai/v1/systemone'
    timeout  = 30                         // seconds, per request
    cacheDir = null                       // set a path to cache responses; unset = no caching
}
```

`apiKey` falls back to the `TYPESAFE_API_KEY` environment variable and is required; the plugin
fails fast at first use when it is missing.

**On `endpoint`:** configurable so a compatible endpoint can be substituted without touching a
pipeline — a proxy, or a future API version.

**On `model`:** `jev-latest` is a floating alias. Pin the snapshot the response reports (e.g.
`jev-1.13.0`) whenever a result needs to be reproducible across a model update.

## Caching

One knob. Setting `jev.cacheDir` turns caching on; leaving it unset turns it off. A second boolean
beside a path would buy nothing.

The key is `sha256` of the exact request payload — model, state and questions — computed from the
bytes about to be POSTed, so nothing can drift between what is keyed and what is asked. The value
is the **whole response text**, not the parsed answers, which keeps a hit reporting the same usage
and resolved snapshot a live call did. Entries live at `<cacheDir>/<2 hex>/<62 hex>.json`.

Storing text rather than parsed maps is also what keeps the cache safe under concurrency: every
hit is re-parsed, so each caller owns its answers. Handing one shared map to several dataflow
threads would let a downstream mutation on one item corrupt another's — an aliasing bug that
surfaces only under load and reads like a model error.

Deliberately absent: eviction, expiry, size limits, and coalescing of concurrent identical
requests. `rm -rf` is the eviction policy, and two threads that miss the same key at the same
moment simply both ask — last writer wins on equivalent content, for a few thousandths of a cent.

### Concurrency and failure

- **Atomic publish.** An entry is written to a temporary file **in its own directory** and moved
  into place with `ATOMIC_MOVE`, so a concurrent reader sees the old entry or the new one, never a
  partial file. The temporary file must be a sibling: the default temporary directory is often on
  another filesystem, where an atomic move is unsupported. Keep `cacheDir` on a local filesystem --
  the guarantee is weaker on NFS.
- **A bad entry is a miss.** An entry that will not parse, or that carries no `answers`, is
  discarded and the question re-asked. Only a *live* response that fails this way is an error.
  Without this, one truncated file from a killed process poisons a pipeline permanently.
- **The cache never fails a run.** An unwritable directory costs an API call, not a pipeline.
- **Only usable responses are stored**, so a malformed 200 cannot shadow a later good one.

### The two honest caveats

- **The key can only carry the model you configured, not the one that answered.** With
  `model = 'jev-latest'` the key holds a floating alias, so answers keep replaying after the alias
  moves to a new snapshot. Enabling the cache on a floating alias logs a warning; pair `cacheDir`
  with a pinned `model = 'jev-1.13.0'`.
- **Caching a probabilistic judgment freezes one draw.** Repeated live calls on identical input
  vary slightly — an `overclaim` noul measured 0.48 and 0.49 across runs. A hit makes a *run*
  reproducible; it does not make the *judgment* reproducible, and a threshold sitting on a boundary
  stops flapping for the wrong reason.

## Implementation

Five files under `src/main/groovy/nextflowio/plugin/`:

| file | role |
| --- | --- |
| `JevPlugin` | `BasePlugin` entry point (from the scaffold, unchanged) |
| `JevConfig` | reads the `jev` config scope and the environment; resolves endpoint, model, key, timeout |
| `JevClient` | one `POST` via `java.net.http.HttpClient`; JSON in, `answers` out; retries 429/529 |
| `JevCache` | content-addressed response store; atomically published, miss-on-corrupt |
| `JevExtension` | the `@Function` surface — `noul`, `choice`, `score`, `jev` |

The scaffold's `JevFactory` / `JevObserver` are removed: a judgment plugin observes nothing.

### Error handling

- **Missing credential** → `AbortOperationException` at first call, naming both `jev.apiKey` and
  `TYPESAFE_API_KEY`.
- **429 / 529** → retried up to 3 attempts with exponential backoff. These are the two statuses
  the vendor documents as retryable.
- **Any other non-200** → `AbortOperationException` carrying the status and response body. There
  is no partial success to salvage: a decisions call either answers every question or none.
- **Empty `questions`** → rejected before any request is made.

The client is constructed once per session and shared; `java.net.http.HttpClient` is thread-safe,
so concurrent `map` closures reuse the same connection pool.

### Testing

- Question builders: shape assertions, no network.
- `JevConfig`: precedence of config over environment, defaults, missing-key failure.
- `JevClient`: against a stub HTTP server — success, retried 429, fatal 4xx, malformed body.
- No test performs a live API call.

## Examples

Three, under `examples/`, each runnable against released Nextflow:

1. **`label-samples`** — fetch ENA run metadata with a process, label each run against a
   controlled vocabulary, and `branch` into accepted / human-review on the *calibrated*
   confidence rather than a number a language model invented.
2. **`route`** — choose a QC depth per sample and dispatch to different processes, with low
   confidence defaulting to the more thorough branch. No agent, no LLM.
3. **`guardrail`** — screen generated text for hazards with a noul + score rubric in one call,
   and block or pass on thresholds held in `nextflow.config`.

## Out of scope for v1

- Any lineage or trace artifact.
- Coalescing concurrent identical in-flight requests.
- A `@Operator` form with bounded concurrent dispatch and question batching. `jev` inside a
  `map` closure is serial on one dataflow thread; the operator is the answer when cohort size
  makes that matter, and it can be added without changing the function surface.
- Deriving questions from a record type. That needs `enum` in the Nextflow type system, and it
  belongs to the `agent` primitive's story, not this one.
