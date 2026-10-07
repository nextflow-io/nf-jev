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
        config.provider == JevProvider.TYPESAFE
        config.endpoint == JevConfig.DEFAULT_ENDPOINT
        config.model == JevConfig.DEFAULT_MODEL
        config.timeout == Duration.ofSeconds(30)
        config.apiKey == 'sk-env'
    }

    def 'should select the provider by name, with its own endpoint, model and credential' () {
        when:
        def config = new JevConfig([provider: 'openrouter'], [TYPESAFE_API_KEY: 'sk-typesafe', OPENROUTER_API_KEY: 'sk-or'])
        then:
        config.provider == JevProvider.OPENROUTER
        config.endpoint == 'https://openrouter.ai/api/alpha/decisions'
        config.model == 'typesafe/jev-1.13'
        config.apiKey == 'sk-or'
    }

    def 'should infer the provider from the endpoint host when none is named' () {
        expect:
        new JevConfig([endpoint: endpoint], [:]).provider == provider

        where:
        endpoint                                          | provider
        'https://api.typesafe.ai/v1/systemone'            | JevProvider.TYPESAFE
        'https://decisions.example.com/v1/systemone'      | JevProvider.TYPESAFE
        'https://openrouter.ai/api/alpha/decisions'       | JevProvider.OPENROUTER
        'https://OpenRouter.ai/api/alpha/decisions'       | JevProvider.OPENROUTER
        'not a url'                                       | JevProvider.TYPESAFE
    }

    def 'should let a named provider win over the endpoint host' () {
        when: 'a proxy in front of OpenRouter'
        def config = new JevConfig([provider: 'openrouter', endpoint: 'https://proxy.example.com/decisions'], [OPENROUTER_API_KEY: 'sk-or'])
        then:
        config.provider == JevProvider.OPENROUTER
        config.endpoint == 'https://proxy.example.com/decisions'
        config.apiKey == 'sk-or'
    }

    def 'should refuse a named provider pointed at the other provider\'s host' () {
        when:
        new JevConfig([provider: provider, endpoint: endpoint], [TYPESAFE_API_KEY: 'sk-typesafe', OPENROUTER_API_KEY: 'sk-or'])
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('Conflicting')
        e.message.contains(endpoint)

        where:
        provider     | endpoint
        'typesafe'   | 'https://openrouter.ai/api/alpha/decisions'
        'openrouter' | 'https://api.typesafe.ai/v1/systemone'
    }

    def 'should accept a named provider pointed at its own host' () {
        expect:
        new JevConfig([provider: 'openrouter', endpoint: 'https://openrouter.ai/api/alpha/decisions'], [:]).provider == JevProvider.OPENROUTER
        new JevConfig([provider: 'typesafe', endpoint: 'https://api.typesafe.ai/v1/systemone'], [:]).provider == JevProvider.TYPESAFE
    }

    def 'should reject an unknown provider' () {
        when:
        new JevConfig([provider: 'cloudflare'], [:])
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('cloudflare')
        e.message.contains('typesafe, openrouter')
    }

    def 'should never present the TypeSafe key to OpenRouter' () {
        given: 'the common setup - only TYPESAFE_API_KEY exported, and no OpenRouter key'
        def config = new JevConfig([endpoint: 'https://openrouter.ai/api/alpha/decisions', apiKey: null], [TYPESAFE_API_KEY: 'sk-typesafe'])

        when:
        config.apiKey
        then: 'the request is refused, naming the variable that is actually missing'
        def e = thrown(AbortOperationException)
        e.message.contains('OpenRouter')
        e.message.contains('OPENROUTER_API_KEY')
        !e.message.contains('TYPESAFE_API_KEY')
    }

    def 'should likewise ignore an OpenRouter key when talking to TypeSafe' () {
        when:
        new JevConfig([:], [OPENROUTER_API_KEY: 'sk-or']).apiKey
        then:
        def e = thrown(AbortOperationException)
        e.message.contains('TYPESAFE_API_KEY')
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
        e.message.contains('TypeSafe')
        e.message.contains('jev.apiKey')
        e.message.contains('TYPESAFE_API_KEY')
    }
}
