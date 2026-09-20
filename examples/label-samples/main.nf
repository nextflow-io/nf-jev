/*
 * Label public sequencing metadata against a controlled vocabulary, and split the cohort on a
 * CALIBRATED confidence rather than a number a language model invented about itself.
 *
 * Every question is answered against one sample's metadata, all in a single request. The review
 * gate lives here, in the pipeline, next to every other parameter -- not inside a prompt.
 *
 *   nextflow run examples/label-samples
 */

include { jev; choice; noul; score } from 'plugin/nf-jev'

// The vocabulary, built once and reused for every sample. A question is a value: an institution
// swaps in its own ontology by editing this map, with nothing else to change.
def ontology() {
    [
        organism: choice('Which organism is this sequencing run derived from?',
                         ['Homo sapiens'           : 'human',
                          'Mus musculus'           : 'mouse',
                          'Drosophila melanogaster': 'fruit fly',
                          'unknown'                : 'no organism is evidenced in the metadata']),

        assay: choice('Which assay does this sequencing run describe?',
                      ['RNA-seq' : 'transcriptome sequencing, including total RNA and mRNA',
                       'ATAC-seq': 'chromatin accessibility',
                       'ChIP-seq': 'protein-DNA binding',
                       'WGS'     : 'whole genome sequencing',
                       'unknown' : 'no assay is evidenced in the metadata']),

        is_cell_line: noul('The sample is an immortalised cell line rather than primary tissue.'),

        tissue_evidence: score('How well evidenced is the tissue or cell type of origin?',
                               ['not mentioned at all',
                                'inferable only from a cell-line name',
                                'stated explicitly']),
    ]
}

// Fetch one run's raw metadata from the ENA. Deterministic, no reasoning: an ordinary task that
// caches and resumes like any other.
process META_FETCH {
    tag "$accession"

    input:
    val accession

    output:
    tuple val(accession), stdout

    script:
    def fields = 'run_accession,sample_title,scientific_name,tax_id,library_strategy,' +
                 'library_source,library_selection,instrument_platform,read_count'
    """
    curl --fail --silent --show-error --location --get \\
        --data-urlencode 'accession=${accession}' \\
        --data-urlencode 'result=read_run' \\
        --data-urlencode 'fields=${fields}' \\
        --data-urlencode 'format=json' \\
        'https://www.ebi.ac.uk/ena/portal/api/filereport'
    """
}

workflow {
    def vocabulary = ontology()

    // One request per sample, answering all four questions at once. `jev` is a plain function, so
    // it runs in the driver on the dataflow thread -- fine for a cohort of this size.
    def labelled = META_FETCH(channel.fromList(params.accessions))
        .map { accession, meta -> [accession: accession, answers: jev(meta, vocabulary)] }

    // The gate is code. Both facets must be confidently decided, and a sample the model declines
    // to label goes to a human instead of getting a guess. `as double` because a param given on
    // the command line arrives as a string.
    def floor = params.floor as double
    labelled
        .branch { row ->
            accepted: row.answers.organism.confidence >= floor \
                   && row.answers.assay.confidence >= floor \
                   && row.answers.assay.choice != 'unknown'
            review: true
        }
        .set { triaged }

    triaged.accepted.view { row ->
        "ACCEPT ${row.accession}  ${row.answers.organism.choice} / ${row.answers.assay.choice}" +
        "  (p=${row.answers.assay.probabilities[row.answers.assay.choice]}," +
        " cell line p=${row.answers.is_cell_line.noul})"
    }

    triaged.review.view { row ->
        "REVIEW ${row.accession}  organism=${row.answers.organism.choice}@${row.answers.organism.confidence}" +
        " assay=${row.answers.assay.choice}@${row.answers.assay.confidence}" +
        "  tissue evidence=${row.answers.tissue_evidence.score}"
    }
}
