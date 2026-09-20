# route

## What this shows

Sometimes the decision a pipeline needs is "which of these should I run?", and the answer is in
prose a human would read in a second but no `if` statement can parse.

Here three samples arrive with nothing but a title, a library strategy and a read count. Jev picks
the QC depth each one deserves, and the pipeline dispatches to a different process accordingly.

There is no agent here, and nothing is generated. One question, one answer, an ordinary `branch`.

```bash
nextflow run examples/route
```

## What gets asked

One question per sample, carrying that sample's record:

> **Which QC depth does this sequencing run warrant?**
> - `light` — clean and unremarkable: a well described sample, a standard library, a healthy read count
> - `full` — messy, ambiguous or unfamiliar metadata that deserves the full battery
> - `reject` — unusable: the wrong organism, a truncated run, or a library that cannot be analysed

Jev answers with one of those three, plus how sure it is. The options and their meanings are
written in the script — Jev can only answer with something you listed, never with a fourth thing
it made up.

## What you should see

```
[PROCESS b4/4ae6e0] QC_LIGHT (S1)
light QC on S1
[PROCESS d6/14efa4] QC_FULL (S2)
full QC on S2
[PROCESS 27/99273a] QC_LIGHT (S3)
light QC on S3
```

- **S1** — *"GM12878 lymphoblastoid RNA-seq, poly-A selected"*, 41M reads → **light**. A named cell
  line, a standard library, a healthy depth.
- **S2** — *"untitled sample 7"*, strategy `OTHER`, 190k reads → **full**. Nothing is described and
  the run is thin, so it gets the full battery.
- **S3** — *"E. coli K-12 whole genome, MiSeq"*, 2.4M reads → **light**. Not human, but nothing is
  wrong with it either; note it is *not* rejected, because "the wrong organism" is not the same as
  "an organism you didn't expect".

## The line worth reading

```groovy
def chosen = pick.confidence < params.floor ? 'full' : pick.choice
```

When Jev isn't sure, the sample does **not** go to a coin flip — it falls through to the more
thorough branch. Uncertainty is a reason to do the careful thing, and here that policy is one
readable line with a threshold in `nextflow.config`, rather than a hope expressed in a prompt.
