/*
 * Route each sample to the QC depth its metadata warrants. No agent, no language model, no
 * generated text -- one judgment per sample, and the dispatch is an ordinary `branch`.
 *
 * Note where the uncertainty goes: a low-confidence pick does not become a coin flip, it falls
 * through to the more thorough branch. That policy is one line of Groovy, and it is reviewable.
 *
 *   nextflow run examples/route
 */

include { jev; choice } from 'plugin/nf-jev'

def depth() {
    choice('Which QC depth does this sequencing run warrant?',
                   ['light' : 'clean and unremarkable: a well described sample, a standard library, a healthy read count',
                    'full'  : 'messy, ambiguous or unfamiliar metadata that deserves the full battery',
                    'reject': 'unusable: the wrong organism, a truncated run, or a library that cannot be analysed'])
}

process QC_LIGHT {
    tag "$row.id"
    input:  val row
    output: stdout
    script: "echo 'light QC on ${row.id}'"
}

process QC_FULL {
    tag "$row.id"
    input:  val row
    output: stdout
    script: "echo 'full QC on ${row.id}'"
}

workflow {
    def question = depth()

    def runs = channel.of(
        [id: 'S1', title: 'GM12878 lymphoblastoid RNA-seq, poly-A selected', strategy: 'RNA-Seq', reads: 41_000_000],
        [id: 'S2', title: 'untitled sample 7',                               strategy: 'OTHER',   reads: 190_000],
        [id: 'S3', title: 'E. coli K-12 whole genome, MiSeq',                strategy: 'WGS',     reads: 2_400_000],
    )

    def routed = runs
        .branch { row ->
            def pick = jev(row, [depth: question]).depth
            // Uncertainty is not a third answer -- it is a reason to do the thorough thing.
            def chosen = pick.confidence < params.floor ? 'full' : pick.choice
            light : chosen == 'light'
            full  : chosen == 'full'
            reject: true
        }

    QC_LIGHT(routed.light).view()
    QC_FULL(routed.full).view()
    routed.reject.view { row -> "REJECT ${row.id}: ${row.title}" }
}
