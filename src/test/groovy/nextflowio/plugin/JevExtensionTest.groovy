/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflowio.plugin

import nextflow.exception.AbortOperationException
import spock.lang.Specification

class JevExtensionTest extends Specification {

    def ext = new JevExtension()

    def 'should build a noul question' () {
        expect:
        ext.noul('The sample is human.') == [type: 'noul', instructions: 'The sample is human.']
    }

    def 'should build a choice question carrying its criteria' () {
        when:
        def q = ext.choice('Which assay?', ['RNA-seq': 'transcriptome', 'unknown': 'not evidenced'])
        then:
        q.type == 'choice'
        q.instructions == 'Which assay?'
        q.criteria == ['RNA-seq': 'transcriptome', 'unknown': 'not evidenced']
    }

    def 'should build a score question keeping the level order' () {
        when:
        def q = ext.score('How severe?', ['none', 'minor', 'serious'])
        then:
        q.type == 'score'
        q.criteria == ['none', 'minor', 'serious']
    }

    def 'should reject a choice with fewer than two options' () {
        when:
        ext.choice('Which assay?', ['RNA-seq': 'transcriptome'])
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('at least two options')
    }

    def 'should reject a score with fewer than two levels' () {
        when:
        ext.score('How severe?', ['none'])
        then:
        thrown(AbortOperationException)
    }

    def 'should reject empty instructions' () {
        when:
        ext.noul('  ')
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('cannot be empty')
    }
}
