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

import nextflow.config.parser.v2.ConfigParserV2
import nextflow.config.spec.ScopeName
import nextflow.config.spec.SpecNode
import nextflow.script.dsl.Description
import spock.lang.Specification

class JevScopeTest extends Specification {

    def 'should declare jev as a config scope Nextflow can recognize' () {
        given:
        def described = JevScope.getAnnotation(Description)?.value()?.trim()
        def node = SpecNode.Scope.of(JevScope, described)

        expect:
        JevScope.getAnnotation(ScopeName).value() == 'jev'
        described

        when:
        def options = node.children()

        then:
        options.keySet() as Set == ['provider', 'apiKey', 'model', 'endpoint', 'timeout', 'cacheDir'] as Set
        (options.provider as SpecNode.Option).type() == String
        (options.apiKey as SpecNode.Option).type() == String
        (options.model as SpecNode.Option).type() == String
        (options.endpoint as SpecNode.Option).type() == String
        (options.timeout as SpecNode.Option).type() == int
        (options.cacheDir as SpecNode.Option).type() == String
        options.values().every { it.description()?.trim() }
    }

    def 'should parse a jev block into the options the plugin reads' () {
        given:
        def parsed = new ConfigParserV2().parse('''
            jev {
                provider = 'openrouter'
                apiKey   = 'sk-test'
                model    = 'typesafe/jev-1.13'
                endpoint = 'https://openrouter.ai/api/alpha/decisions'
                timeout  = 5
                cacheDir = '/tmp/jev-cache'
            }
        ''')
        def scope = new JevScope(parsed.jev as Map)
        def config = new JevConfig(parsed.jev as Map, [TYPESAFE_API_KEY: 'sk-env'])

        expect:
        scope.provider == 'openrouter'
        scope.apiKey == 'sk-test'
        scope.model == 'typesafe/jev-1.13'
        scope.endpoint == 'https://openrouter.ai/api/alpha/decisions'
        scope.timeout == 5
        scope.cacheDir == '/tmp/jev-cache'

        config.provider == JevProvider.OPENROUTER
        config.apiKey == scope.apiKey
        config.model == scope.model
        config.endpoint == scope.endpoint
        config.timeout == Duration.ofSeconds(scope.timeout)
        config.cacheDir == scope.cacheDir
    }

    def 'should apply the same defaults the reader uses' () {
        when:
        def scope = new JevScope([:])
        def config = new JevConfig([:], [:])

        then:
        scope.provider == JevProvider.TYPESAFE.configName
        scope.endpoint == JevConfig.DEFAULT_ENDPOINT
        scope.model == JevConfig.DEFAULT_MODEL
        scope.timeout == JevConfig.DEFAULT_TIMEOUT_SECONDS
        scope.apiKey == null
        scope.cacheDir == null

        config.provider == JevProvider.TYPESAFE
        config.endpoint == scope.endpoint
        config.model == scope.model
        config.timeout == Duration.ofSeconds(scope.timeout)
        config.cacheDir == null
    }
}
