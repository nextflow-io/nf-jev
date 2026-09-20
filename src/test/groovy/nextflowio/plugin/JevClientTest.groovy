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

import static com.github.tomakehurst.wiremock.client.WireMock.*

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.stubbing.Scenario
import groovy.json.JsonSlurper
import nextflow.exception.AbortOperationException
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the client against a WireMock stub. No test performs a live API call.
 */
class JevClientTest extends Specification {

    static final String PATH = '/decisions'

    static final String OK_BODY = '''
        {"model":"jev-1.13.0",
         "answers":{"assay":{"type":"choice","choice":"unknown","confidence":0.99,
                             "probabilities":{"RNA-seq":0.0,"unknown":0.99}},
                    "is_human":{"type":"noul","noul":0.98}},
         "usage":{"input_tokens":489,"output_tokens":89}}
    '''

    @TempDir
    Path cacheFolder

    @Shared
    WireMockServer wireMockServer

    def setupSpec() {
        wireMockServer = new WireMockServer(0)
        wireMockServer.start()
    }

    def cleanupSpec() {
        wireMockServer.stop()
    }

    def setup() {
        wireMockServer.resetAll()
    }

    private JevClient clientWith(Map opts = [:]) {
        final endpoint = "http://localhost:${wireMockServer.port()}${PATH}"
        new JevClient(new JevConfig([endpoint: endpoint, apiKey: 'sk-test'] + opts, [:]))
    }

    /** The bodies the stub received, parsed. */
    private List<Map> requests() {
        wireMockServer.findAll(postRequestedFor(urlEqualTo(PATH)))
            .collect { new JsonSlurper().parseText(it.bodyAsString) as Map }
    }

    def 'should send state and questions, and return the answers' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson(OK_BODY)))
        def client = clientWith(model: 'jev-1.13.0')

        when:
        def answers = client.ask([sample_title: 'GM12878'], [is_human: [type: 'noul', instructions: 'Human?']])

        then: 'the answers come back keyed by question id'
        answers.keySet() == ['assay', 'is_human'] as Set
        answers.is_human.noul == 0.98
        answers.assay.choice == 'unknown'
        answers.assay.probabilities['unknown'] == 0.99

        and: 'the request carried the credential'
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH))
            .withHeader('Authorization', equalTo('Bearer sk-test'))
            .withHeader('Content-Type', equalTo('application/json')))

        and: 'and the model, the state and the questions'
        def sent = requests()
        sent.size() == 1
        sent[0].model == 'jev-1.13.0'
        sent[0].state == [sample_title: 'GM12878']
        sent[0].questions.is_human.instructions == 'Human?'
    }

    def 'should retry a rate-limited request and then succeed' () {
        given:
        wireMockServer.stubFor(post(PATH).inScenario('retry')
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withBody('{"error":"slow down"}'))
            .willSetStateTo('recovered'))
        wireMockServer.stubFor(post(PATH).inScenario('retry')
            .whenScenarioStateIs('recovered')
            .willReturn(okJson(OK_BODY)))

        when:
        def answers = clientWith().ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then:
        answers.is_human.noul == 0.98
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(PATH)))
    }

    def 'should give up after exhausting the retries' () {
        given:
        wireMockServer.stubFor(post(PATH)
            .willReturn(aResponse().withStatus(529).withBody('{"error":"overloaded"}')))

        when:
        clientWith().ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then:
        def e = thrown(AbortOperationException)
        e.message.contains('after 3 attempts')
        wireMockServer.verify(3, postRequestedFor(urlEqualTo(PATH)))
    }

    def 'should abort on a non-retryable status without retrying' () {
        given:
        wireMockServer.stubFor(post(PATH)
            .willReturn(aResponse().withStatus(422).withBody('{"error":"malformed question"}')))

        when:
        clientWith().ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then:
        def e = thrown(AbortOperationException)
        e.message.contains('422')
        e.message.contains('malformed question')
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)))
    }

    def 'should abort on a response that is not JSON' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(ok('not json at all')))

        when:
        clientWith().ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then:
        def e = thrown(AbortOperationException)
        e.message.contains('Unable to parse')
    }

    def 'should abort on a response carrying no answers' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson('{"model":"jev-1.13.0","usage":{}}')))

        when:
        clientWith().ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then:
        def e = thrown(AbortOperationException)
        e.message.contains('answers')
    }

    def 'should serve a repeated question from the cache without calling out' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson(OK_BODY)))
        def client = clientWith(model: 'jev-1.13.0', cacheDir: cacheFolder.toString())
        def question = [is_human: [type: 'noul', instructions: 'Human?']]

        when: 'the same question is asked twice'
        def first = client.ask([sample: 'GM12878'], question)
        def second = client.ask([sample: 'GM12878'], question)

        then: 'both answer the same, from one request'
        first == second
        first.is_human.noul == 0.98
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)))

        and: 'each caller got its own object, so one cannot corrupt the other'
        !first.is(second)
    }

    def 'should treat a different state as a different question' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson(OK_BODY)))
        def client = clientWith(cacheDir: cacheFolder.toString())
        def question = [is_human: [type: 'noul', instructions: 'Human?']]

        when:
        client.ask([sample: 'GM12878'], question)
        client.ask([sample: 'HeLa'], question)

        then:
        wireMockServer.verify(2, postRequestedFor(urlEqualTo(PATH)))
    }

    def 'should ask again when the cached entry is unusable' () {
        given: 'a cache primed with garbage for the request about to be made'
        def client = clientWith(model: 'jev-1.13.0', cacheDir: cacheFolder.toString())
        def question = [is_human: [type: 'noul', instructions: 'Human?']]
        def payload = groovy.json.JsonOutput.toJson([model: 'jev-1.13.0', state: 'a state', questions: question])
        JevCache.of(cacheFolder.toString()).put(payload, corrupt)
        wireMockServer.stubFor(post(PATH).willReturn(okJson(OK_BODY)))

        when:
        def answers = client.ask('a state', question)

        then: 'the entry is discarded, the question re-asked, and the run is not aborted'
        answers.is_human.noul == 0.98
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)))

        and: 'and the bad entry has been replaced'
        client.ask('a state', question).is_human.noul == 0.98
        wireMockServer.verify(1, postRequestedFor(urlEqualTo(PATH)))

        where:
        corrupt << ['{"model":"jev-1.13.0"', '{"model":"jev-1.13.0","usage":{}}', '']
    }

    def 'should not cache a response that carries no answers' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson('{"model":"jev-1.13.0","usage":{}}')))
        def client = clientWith(cacheDir: cacheFolder.toString())

        when:
        client.ask('a state', [q: [type: 'noul', instructions: 'Yes?']])
        then:
        thrown(AbortOperationException)

        and: 'nothing was written, so a later good response is not shadowed'
        !Files.exists(cacheFolder) || Files.walk(cacheFolder).filter { Files.isRegularFile(it) }.toList().isEmpty()
    }

    def 'should abort plainly when the calling thread is interrupted' () {
        given:
        wireMockServer.stubFor(post(PATH).willReturn(okJson(OK_BODY)))
        def client = clientWith()

        when: 'the run is shutting down as the call is made'
        Thread.currentThread().interrupt()
        client.ask('some state', [q: [type: 'noul', instructions: 'Yes?']])

        then: 'the abort names the cause instead of leaking an InterruptedException'
        def e = thrown(AbortOperationException)
        e.message.contains('interrupted')

        cleanup: 'clear the flag so it cannot leak into the next test'
        Thread.interrupted()
    }

    def 'should reject an empty question set before making a request' () {
        when:
        clientWith().ask('some state', [:])

        then:
        thrown(AbortOperationException)
        wireMockServer.verify(0, postRequestedFor(urlEqualTo(PATH)))
    }
}
