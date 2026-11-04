# guardrail

## What this shows

When a pipeline generates text — a report, a summary, an interpretation — something has to decide
whether it is safe to pass on. That something should not be the model that wrote it.

This pipeline takes three reports and screens each one against a short hazard checklist, then
blocks, flags or passes it. The thresholds live in `nextflow.config`, so the policy is a file a
reviewer can read and change, not a sentence buried in a prompt.

```bash
nextflow run examples/guardrail
```

## What gets asked

One request per report, carrying the text and four questions about it:

| question | kind | what comes back |
| --- | --- | --- |
| Does it give clinical guidance for an individual patient? | yes/no | a probability |
| Does it claim more than its evidence supports? | yes/no | a probability |
| Could it identify an individual person? | yes/no | a probability |
| How severe would the harm be if acted on? | a level | `0` none → `3` serious |

All four go in one request, so screening every output costs one call rather than four.

Then the pipeline — not Jev — decides:

```
worst = the highest of the three yes/no probabilities

BLOCK   if worst >= 0.70, or severity >= 2.0
REVIEW  if worst >= 0.35
PASS    otherwise
```

## What you should see

```
REVIEW R1  clinical=0.01 overclaim=0.48 identifying=0.03 severity=1.40
BLOCK  R2  clinical=0.96 overclaim=0.78 identifying=0.07 severity=2.95
BLOCK  R3  clinical=0.12 overclaim=0.76 identifying=0.65 severity=2.40
```

**R1** — *"Assembly N50 is 84 kb across 212 contigs; completeness 98.1%. The assembly meets the
stated QC bar."* Flagged, not blocked, on `overclaim=0.48`. The text quotes three numbers and then
announces a verdict, but never says what the bar actually is. `0.48` is not "slightly
overclaiming" — on a yes/no question, a number near `0.5` means Jev genuinely cannot decide, which
is exactly the case a person should look at.

**R2** — *"The variant is pathogenic. Start the patient on carbamazepine…"* Blocked twice over:
`clinical=0.96` on its own, and `severity=2.95` on its own. Treatment instructions for a named
patient, and a drug where being wrong hurts someone.

**R3** — *"Sample from the 62-year-old male donor admitted on 3 March at the Royal Infirmary proves
the mechanism definitively."* Blocked on **`overclaim=0.76`** — "proves… definitively" from one
sample — and not on the identifiers. `identifying=0.65` is a hedge: age, sex, a date and a hospital
are each harmless alone and quite revealing together.

Numbers move by a few hundredths between runs. That matters for R3, whose `identifying` answer has
landed anywhere from `0.65` to `0.71` — either side of the `0.70` block line. Here it changes
nothing, because `overclaim` blocks the report anyway, but it is the reason to leave a gap between
your review and block thresholds. Setting `jev.cacheDir` (see the main README) pins a given text to
one verdict.

## The point

Jev returned four numbers. Every decision — block, flag, pass — was made by three thresholds in
`nextflow.config`. Lower `block_threshold` to `0.60` and R3 blocks on the identifiers too, with no
new questions asked, because neither the text nor what you asked about it has changed.
