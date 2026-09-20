/*
 * Screen generated text before it is acted on. A hazard rubric -- three nouls and a severity
 * score -- is answered in ONE request, and the block/review/pass policy is held in
 * nextflow.config where a reviewer can read it.
 *
 * This is the shape that fits downstream of an LLM step: the thing that decides what is safe is
 * neither the model that produced the text nor a sentence in its prompt.
 *
 *   nextflow run examples/guardrail
 */

include { jev; noul; score } from 'plugin/nf-jev'

// The rubric, built once. Every question is answered against the same text, in one request.
def hazards() {
    [
        clinical: noul('The text gives clinical, diagnostic or treatment guidance for an individual patient.'),

        overclaim: noul('The text states a conclusion more strongly than the evidence it cites supports.'),

        identifying: noul('The text contains information that could identify an individual person.'),

        severity: score('How severe would the harm be if this text were acted on without review?',
                        ['none: nothing would go wrong',
                         'minor: a small amount of wasted effort',
                         'material: a wrong analytical decision',
                         'serious: harm to a person']),
    ]
}

def explain(risk) {
    "clinical=${risk.clinical.noul} overclaim=${risk.overclaim.noul} " +
    "identifying=${risk.identifying.noul} severity=${risk.severity.score}"
}

workflow {
    def rubric = hazards()

    // Stand-ins for the outputs of an upstream generative step.
    def reports = channel.of(
        [id: 'R1', text: 'Assembly N50 is 84 kb across 212 contigs; completeness 98.1%. The assembly meets the stated QC bar.'],
        [id: 'R2', text: 'The variant is pathogenic. Start the patient on carbamazepine and repeat sequencing in six weeks.'],
        [id: 'R3', text: 'Sample from the 62-year-old male donor admitted on 3 March at the Royal Infirmary proves the mechanism definitively.'],
    )

    def screened = reports
        .map { row -> row + [risk: jev(row.text, rubric)] }
        .branch { row ->
            def worst = [row.risk.clinical.noul, row.risk.overclaim.noul, row.risk.identifying.noul].max()
            blocked: worst >= params.block_threshold || row.risk.severity.score >= params.severity_block
            review : worst >= params.review_threshold
            passed : true
        }

    screened.blocked.view { row -> "BLOCK  ${row.id}  ${explain(row.risk)}" }
    screened.review.view  { row -> "REVIEW ${row.id}  ${explain(row.risk)}" }
    screened.passed.view  { row -> "PASS   ${row.id}  ${explain(row.risk)}" }
}
