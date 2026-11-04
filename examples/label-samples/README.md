# label-samples

## What this shows

Public sequencing metadata is messy free text. This pipeline fetches six real runs from the ENA,
asks Jev to label each one against a fixed vocabulary, and splits the cohort into labels you can
trust and labels a human should look at.

The point is the split. Jev returns a **probability** for each label, so the accept/review decision
is made by the pipeline — a number you can compare in `nextflow.config` — not by asking a model to
rate its own confidence.

```bash
nextflow run examples/label-samples
```

## What gets asked

One request per sample, carrying that sample's raw ENA record and four questions about it:

| question | kind | what comes back |
| --- | --- | --- |
| Which organism is this run derived from? | pick one | `Homo sapiens`, and how sure |
| Which assay does this run describe? | pick one | `RNA-seq`, and how sure |
| Is this an immortalised cell line? | yes/no | a probability, e.g. `0.95` |
| How well evidenced is the tissue of origin? | a level | `0`–`2` on a described scale |

In the script those are the `choice`, `noul` and `score` builders — the three kinds of question Jev
answers. All four are sent together and answered against the same metadata, so a sample costs one
call, not four.

Nothing else is sent: only the ENA record, which is already public.

## What you should see

```
ACCEPT SRR891268    Homo sapiens / ATAC-seq             (p=1.0, cell line p=0.95)
ACCEPT SRR307898    Homo sapiens / RNA-seq              (p=1.0, cell line p=0.96)
ACCEPT SRR3192396   Homo sapiens / RNA-seq              (p=1.0, cell line p=0.93)
ACCEPT SRR1039508   Homo sapiens / RNA-seq              (p=1.0, cell line p=0.75)
ACCEPT SRR5150592   Homo sapiens / RNA-seq              (p=1.0, cell line p=0.76)
ACCEPT SRR031708    Drosophila melanogaster / RNA-seq   (p=1.0, cell line p=0.73)
```

Rows arrive in whatever order the samples finish, and the probabilities move by a hundredth or so
between runs.

Three things worth noticing:

- **`SRR891268` is labelled ATAC-seq.** Its ENA `library_strategy` field says `OTHER` — useless —
  so the assay was recovered from the free-text title alone. That is the case this example exists
  for: the answer is in the prose, not in the structured field.
- **`SRR031708` is a fruit fly**, worked out from the sample name `S2_DRSC_Untreated-1`.
- **The cell-line answers differ in kind from the assay ones.** `0.93`–`0.96` is a confident yes;
  `0.73`–`0.76` is a soft yes worth checking. A yes/no question answers with a probability, so a
  middling number means genuinely unsure — not "a bit of a cell line".

**All six are accepted, and nothing reaches the review queue.** These records are clean enough to
decide, even with the floor raised to `0.999`. The review branch fires when a label's probability
falls below `params.floor`, or when the assay comes back `unknown` because the metadata never said
— which is what you want on a cohort that hasn't been hand-picked.
