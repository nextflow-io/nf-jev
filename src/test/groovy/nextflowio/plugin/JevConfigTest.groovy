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

import java.time.Duration

import nextflow.exception.AbortOperationException
import spock.lang.Specification

class JevConfigTest extends Specification {

    def 'should apply the defaults' () {
        when:
        def config = new JevConfig([:], [TYPESAFE_API_KEY: 'sk-env'])
        then:
        config.endpoint == JevConfig.DEFAULT_ENDPOINT
        config.model == JevConfig.DEFAULT_MODEL
        config.timeout == Duration.ofSeconds(30)
        config.apiKey == 'sk-env'
    }

    def 'should prefer the config over the environment' () {
        when:
        def config = new JevConfig(
            [apiKey: 'sk-config', model: 'jev-1.13.0', endpoint: 'https://decisions.example.com/v1/systemone', timeout: 5],
            [TYPESAFE_API_KEY: 'sk-env'])
        then:
        config.apiKey == 'sk-config'
        config.model == 'jev-1.13.0'
        config.endpoint == 'https://decisions.example.com/v1/systemone'
        config.timeout == Duration.ofSeconds(5)
    }

    def 'should tolerate a null scope and a null environment' () {
        when:
        def config = new JevConfig(null, null)
        then:
        config.endpoint == JevConfig.DEFAULT_ENDPOINT
    }

    def 'should fail only when the credential is asked for' () {
        given:
        def config = new JevConfig([:], [:])

        when: 'the scope is read'
        config.endpoint
        then: 'nothing is raised - an unused plugin must not abort a run'
        noExceptionThrown()

        when: 'a request needs the credential'
        config.apiKey
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('jev.apiKey')
        e.message.contains('TYPESAFE_API_KEY')
    }
}
