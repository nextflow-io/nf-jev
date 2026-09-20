# nf-jev

[![build](https://github.com/nextflow-io/nf-jev/actions/workflows/build.yml/badge.svg)](https://github.com/nextflow-io/nf-jev/actions/workflows/build.yml)

> **Beta.** This plugin is early and under active development. The function names, the shape of the
> answers and the `jev` configuration scope may all change between releases, and there is no
> deprecation cycle yet. Pin a version, and expect to revisit pipelines that use it.

## Summary

`nf-jev` exposes [TypeSafe](https://docs.typesafe.ai) System One judgments as ordinary Nextflow
functions. A pipeline builds typed questions as values, asks them, and gates on the returned
probabilities with plain Nextflow operators.

Jev is not a language model. It generates no text, holds no conversation and calls no tools: it
answers typed questions about the state you give it, and returns a probability distribution over
the answers you allowed. That makes it something a pipeline can *branch on* — unlike a number a
language model reports about its own confidence, which it invented.

Four functions, nothing else:

```
noul   (instructions)             // is this true?      -> probability
choice (instructions, criteria)   // which one?         -> winner + distribution
score  (instructions, levels)     // how much?          -> position + distribution

jev    (state, questions)         // answer them all against one state, in one request
```

See [`SPEC.md`](SPEC.md) for the design, and what it deliberately leaves out.

## Get Started

Enable the plugin in your pipeline `nextflow.config`:

```groovy
plugins {
    id 'nf-jev@0.1.0'
}
```

Set your credential — either `jev.apiKey` in the configuration, or the `TYPESAFE_API_KEY`
environment variable:

```bash
export TYPESAFE_API_KEY="..."
```

Then ask a question:

```nextflow
include { jev; noul } from 'plugin/nf-jev'

workflow {
    channel.of('The assembly meets the stated QC bar.')
        .map { text -> jev(text, [clinical: noul('The text gives clinical guidance.')]) }
        .view { answers -> "clinical: ${answers.clinical.noul}" }
}
```

### Configuration

```groovy
jev {
    apiKey   = secrets.TYPESAFE_API_KEY                  // or $TYPESAFE_API_KEY
    model    = 'jev-latest'                              // pin a snapshot to fix a result
    endpoint = 'https://api.typesafe.ai/v1/systemone'
    timeout  = 30                                        // seconds, per request
    cacheDir = "$projectDir/.jev-cache"                  // unset = no caching
}
```

### Caching

Set `jev.cacheDir` and responses are cached, keyed on a SHA-256 of the exact request — model,
state and questions. Unset it and nothing is cached. Entries are published atomically, so
concurrent runs can share a directory; an entry that will not parse is treated as a miss and the
question re-asked, so a truncated file can never poison a pipeline. There is no eviction: `rm -rf`
the directory.

Two things to know before turning it on:

- **Pin the model.** With the default `model = 'jev-latest'` the key holds a floating alias, so
  cached answers keep being replayed after the alias moves to a newer snapshot. The plugin warns
  about this; pair `cacheDir` with `model = 'jev-1.13.0'`.
- **A cache hit freezes one draw.** Repeated live calls on identical input vary a little. Caching
  makes a *run* reproducible, not the judgment — a threshold sitting exactly on a boundary will
  stop flapping for the wrong reason.

Keep `cacheDir` on a local filesystem; the atomic-publish guarantee is weaker on NFS.


### Reading an answer

```groovy
answers.is_human   // [type: 'noul', noul: 0.98]
answers.assay      // [type: 'choice', choice: 'RNA-seq', confidence: 0.99, probabilities: [...]]
answers.tissue     // [type: 'score', score: 1.97, confidence: 0.96, legend: [...], probabilities: [...]]
```

A `noul` has no separate confidence — the probability *is* the answer, and 0.5 means "equally
likely either way", not "moderately". On a `choice` or `score`, `confidence` says how concentrated
the distribution is, not whether the answer is right.

Questions sharing a state should be sent together: they are evaluated in parallel and cannot see
one another's answers, so a whole rubric costs one request.

## Examples

Three runnable pipelines under [`examples/`](examples), each against the live API. Every
example has its own README explaining what it asks and what the output means.

**[`label-samples`](examples/label-samples/README.md)** — fetch ENA run metadata with an ordinary process,
label each run against a controlled vocabulary, and split the cohort on a calibrated confidence:

```bash
nextflow run examples/label-samples
```
```
ACCEPT SRR891268   Homo sapiens / ATAC-seq   (p=1.0, cell line p=0.95)
ACCEPT SRR031708   Drosophila melanogaster / RNA-seq   (p=1.0, cell line p=0.73)
```

Note `SRR891268`: its ENA `library_strategy` is the useless `OTHER`, and the assay is recovered
from the free-text title alone.

**[`route`](examples/route/README.md)** — pick a QC depth per sample and dispatch to different processes. No
agent and no generated text; a low-confidence pick falls through to the more thorough branch,
which is one readable line rather than a hope expressed in a prompt.

**[`guardrail`](examples/guardrail/README.md)** — screen generated text with a hazard rubric (three nouls and
a severity score) in a single request, with the block/review/pass thresholds in `nextflow.config`:

```
REVIEW R1  clinical=0.01 overclaim=0.48 identifying=0.04 severity=1.37
BLOCK  R2  clinical=0.96 overclaim=0.78 identifying=0.08 severity=2.95
BLOCK  R3  clinical=0.12 overclaim=0.74 identifying=0.67 severity=2.40
```

## Plugin development

Built from the [Nextflow plugin template](https://www.nextflow.io/docs/latest/guides/gradle-plugin.html).

```bash
make assemble   # build
make test       # unit tests; no test makes a live API call
make install    # install into the local Nextflow plugins dir
make release    # publish to the Nextflow Registry
```

CI builds and runs the unit tests on every push and pull request. It then installs the plugin and
runs all three examples against the live API, asserting how many samples each one decided — never
the probabilities themselves, which move slightly between runs. That job needs a `TYPESAFE_API_KEY`
repository secret; without one it reports a notice and skips, and it does not run for pull requests
opened from a fork, which have no access to secrets.

## License

Apache License 2.0. See the [`COPYING`](COPYING) file for details.
