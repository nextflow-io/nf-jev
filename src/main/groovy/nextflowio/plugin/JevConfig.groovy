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

import groovy.transform.CompileStatic
import nextflow.exception.AbortOperationException

/**
 * Settings of the {@code jev} configuration scope, with environment fallback.
 *
 * <p>Nextflow learns the option names from {@link JevScope}. This class is what the plugin
 * reads: it resolves the provider, applies that provider's defaults, and falls back to the
 * matching environment variable for the credential.
 *
 * <p>The provider comes from {@code jev.provider}, or failing that from the host of
 * {@code jev.endpoint}, and decides the defaults and -- crucially -- which environment variable
 * the credential falls back to. Only that provider's variable is ever read, so a TypeSafe key
 * cannot be presented to openrouter.ai merely because an OpenRouter key was not set. A provider
 * named together with an endpoint on the other provider's own host is refused for the same reason.
 *
 * <p>The credential is resolved eagerly but validated lazily: a pipeline that enables the plugin
 * without ever asking a question must not fail for want of a key it never uses.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class JevConfig {

    static final JevProvider DEFAULT_PROVIDER = JevProvider.TYPESAFE
    static final String DEFAULT_ENDPOINT = DEFAULT_PROVIDER.defaultEndpoint
    static final String DEFAULT_MODEL = DEFAULT_PROVIDER.defaultModel
    static final int DEFAULT_TIMEOUT_SECONDS = 30

    private final String apiKey

    final JevProvider provider
    final String endpoint
    final String model
    final Duration timeout

    /** Where to keep cached responses; {@code null} -- the default -- disables caching entirely. */
    final String cacheDir

    JevConfig(Map opts, Map<String,String> env) {
        final config = opts ?: Collections.emptyMap()
        final sysEnv = env ?: Collections.<String,String>emptyMap()
        this.provider = resolveProvider(config)
        this.endpoint = (config.endpoint ?: provider.defaultEndpoint).toString()
        this.model = (config.model ?: provider.defaultModel).toString()
        this.timeout = Duration.ofSeconds((config.timeout ?: DEFAULT_TIMEOUT_SECONDS) as long)
        this.apiKey = (config.apiKey ?: sysEnv.get(provider.apiKeyVar))?.toString()
        this.cacheDir = config.cacheDir?.toString() ?: null
    }

    private static JevProvider resolveProvider(Map config) {
        if( config.provider ) {
            final named = JevProvider.fromConfig(config.provider)
            // a proxy host is fine, but another provider's own host would get this provider's key
            final owner = config.endpoint ? JevProvider.ownerOf(config.endpoint.toString()) : null
            if( owner != null && owner != named )
                throw new AbortOperationException("Conflicting Jev settings - `jev.provider` is `${named.configName}` but `jev.endpoint` points at ${owner.displayName} (${config.endpoint}); remove one of them")
            return named
        }
        if( config.endpoint )
            return JevProvider.forEndpoint(config.endpoint.toString())
        return DEFAULT_PROVIDER
    }

    /**
     * The credential to present, failing when none was configured. Accessed per request so the
     * error surfaces at the first question asked, not at plugin load.
     */
    String getApiKey() {
        if( !apiKey )
            throw new AbortOperationException("Missing ${provider.displayName} credential - set `jev.apiKey` in the Nextflow configuration, or the ${provider.apiKeyVar} environment variable")
        return apiKey
    }
}
