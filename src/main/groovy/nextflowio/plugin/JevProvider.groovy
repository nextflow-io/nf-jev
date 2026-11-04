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
 * <p>Both speak the same request and response JSON, but each has its own host, its own credential
 * and its own model ids. Keeping those differences here means {@code jev.provider} is the single
 * switch a pipeline flips, and the credential for one service is never presented to the other.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
enum JevProvider {

    /** TypeSafe's own System One endpoint. */
    TYPESAFE('TypeSafe', 'api.typesafe.ai', 'https://api.typesafe.ai/v1/systemone', 'TYPESAFE_API_KEY', 'jev-latest'),

    /** The OpenRouter Decisions API. Its model ids are namespaced, e.g. {@code typesafe/jev-1.13}. */
    OPENROUTER('OpenRouter', 'openrouter.ai', 'https://openrouter.ai/api/alpha/decisions', 'OPENROUTER_API_KEY', 'typesafe/jev-1.13')

    final String displayName
    final String host
    final String defaultEndpoint
    final String apiKeyVar
    final String defaultModel

    JevProvider(String displayName, String host, String defaultEndpoint, String apiKeyVar, String defaultModel) {
        this.displayName = displayName
        this.host = host
        this.defaultEndpoint = defaultEndpoint
        this.apiKeyVar = apiKeyVar
        this.defaultModel = defaultModel
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
     * {@code jev.endpoint} still gets the right credential. Anything that is not a known
     * provider's host -- a proxy, say -- is treated as TypeSafe.
     */
    static JevProvider forEndpoint(String endpoint) {
        return ownerOf(endpoint) ?: TYPESAFE
    }

    /** The provider whose own host an endpoint points at, or {@code null} for any other host. */
    static JevProvider ownerOf(String endpoint) {
        final host = hostOf(endpoint)
        return values().find { host == it.host || host.endsWith('.' + it.host) }
    }

    private static String hostOf(String endpoint) {
        try {
            return URI.create(endpoint).host?.toLowerCase() ?: ''
        }
        catch( IllegalArgumentException e ) {
            return ''
        }
    }
}
