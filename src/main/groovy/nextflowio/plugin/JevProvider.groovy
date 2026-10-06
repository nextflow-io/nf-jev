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

import groovy.transform.CompileStatic
import nextflow.exception.AbortOperationException

/**
 * The services that serve Jev, and what differs between them.
 *
 * <p>Both speak the same request and response JSON, but each has its own host, its own credential,
 * its own model ids and its own idea of a well-formed {@code noul} question. Keeping those
 * differences here means {@code jev.provider} is the single switch a pipeline flips, and the
 * credential for one service is never presented to the other.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
enum JevProvider {

    /** TypeSafe's own System One endpoint. The {@code criteria} of a noul are optional. */
    TYPESAFE('TypeSafe', 'https://api.typesafe.ai/v1/systemone', 'TYPESAFE_API_KEY', 'jev-latest', false),

    /**
     * The OpenRouter Decisions API. Its schema requires {@code criteria} on every noul, and its
     * model ids are namespaced, e.g. {@code typesafe/jev-1.13}.
     */
    OPENROUTER('OpenRouter', 'https://openrouter.ai/api/alpha/decisions', 'OPENROUTER_API_KEY', 'typesafe/jev-1.13', true)

    /**
     * What a noul's {@code true} and {@code false} mean when the pipeline said nothing more than
     * the instructions. Sent only to a provider whose schema demands criteria, so that a request
     * to a provider that does not stays byte-for-byte what the pipeline asked.
     */
    static final Map<String,String> DEFAULT_NOUL_CRITERIA = Collections.unmodifiableMap([
        'true' : 'Yes: the statement holds for the state, or the question is answered yes.',
        'false': 'No: the statement does not hold for the state, or the question is answered no.'] as Map<String,String>)

    final String displayName
    final String defaultEndpoint
    final String apiKeyVar
    final String defaultModel
    final boolean requiresNoulCriteria

    JevProvider(String displayName, String defaultEndpoint, String apiKeyVar, String defaultModel, boolean requiresNoulCriteria) {
        this.displayName = displayName
        this.defaultEndpoint = defaultEndpoint
        this.apiKeyVar = apiKeyVar
        this.defaultModel = defaultModel
        this.requiresNoulCriteria = requiresNoulCriteria
    }

    /** The configuration value naming this provider, e.g. {@code openrouter}. */
    String getConfigName() {
        return name().toLowerCase()
    }

    /**
     * Resolve the {@code jev.provider} setting, failing with the accepted names when it is not one
     * of them.
     */
    static JevProvider fromConfig(Object name) {
        final wanted = name.toString().trim()
        final found = values().find { it.configName.equalsIgnoreCase(wanted) }
        if( found == null )
            throw new AbortOperationException("Unknown Jev provider `${wanted}` - `jev.provider` must be one of: ${values()*.configName.join(', ')}")
        return found
    }

    /**
     * Infer the provider from the host an endpoint points at, so a pipeline that set only
     * {@code jev.endpoint} still gets the right credential and request shape. Anything that is
     * not openrouter.ai -- the TypeSafe API, a proxy in front of it -- is treated as TypeSafe.
     */
    static JevProvider forEndpoint(String endpoint) {
        final host = hostOf(endpoint)
        return host == 'openrouter.ai' || host.endsWith('.openrouter.ai') ? OPENROUTER : TYPESAFE
    }

    private static String hostOf(String endpoint) {
        try {
            return URI.create(endpoint).host?.toLowerCase() ?: ''
        }
        catch( IllegalArgumentException e ) {
            return ''
        }
    }

    /**
     * The questions as this provider must receive them. For a provider whose schema requires
     * noul criteria, every noul that came without any gets {@link #DEFAULT_NOUL_CRITERIA}; the
     * pipeline's own criteria are always passed through untouched. Returns the input itself when
     * nothing needs to change, so the cache key of an unchanged request is unchanged too.
     */
    Map shape(Map questions) {
        if( !requiresNoulCriteria )
            return questions
        final result = new LinkedHashMap(questions)
        for( entry in result.entrySet() ) {
            final question = entry.value
            if( question instanceof Map && question.type == 'noul' && question.criteria == null )
                entry.value = question + [criteria: DEFAULT_NOUL_CRITERIA]
        }
        return result
    }
}
