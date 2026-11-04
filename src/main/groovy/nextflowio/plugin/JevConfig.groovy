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
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.exception.AbortOperationException
import nextflow.script.dsl.Description

/**
 * Settings of the {@code jev} configuration scope, with environment fallback.
 *
 * <p>This class is the scope Nextflow reflects and the object the plugin reads. The no-arg
 * constructor exists so the extension point can be instantiated; it does not resolve settings.
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
@ScopeName('jev')
@Description('''
    The `jev` scope controls how the nf-jev plugin calls a Jev decisions endpoint.
''')
@CompileStatic
class JevConfig implements ConfigScope {

    static final JevProvider DEFAULT_PROVIDER = JevProvider.TYPESAFE
    static final String DEFAULT_ENDPOINT = DEFAULT_PROVIDER.defaultEndpoint
    static final String DEFAULT_MODEL = DEFAULT_PROVIDER.defaultModel
    static final int DEFAULT_TIMEOUT_SECONDS = 30

    @ConfigOption
    @Description('''
        The credential for the selected provider (default: the `TYPESAFE_API_KEY`
        or `OPENROUTER_API_KEY` environment variable, matching the provider).
    ''')
    private final String apiKey

    @ConfigOption(types=[String])
    @Description('''
        The service that answers requests: `typesafe` (default) or `openrouter`.
        Inferred from the host of `endpoint` when unset.
    ''')
    final JevProvider provider

    @ConfigOption
    @Description('''
        The decisions endpoint (default: the selected provider's endpoint).
    ''')
    final String endpoint

    @ConfigOption
    @Description('''
        The Jev model id (default: the selected provider's current model).
    ''')
    final String model

    @ConfigOption(types=[Integer])
    @Description('''
        The request timeout, in seconds (default: `30`).
    ''')
    final Duration timeout

    @ConfigOption
    @Description('''
        Where to keep cached responses. Unset disables caching.
    ''')
    final String cacheDir

    /* required by extension point -- do not remove */
    JevConfig() {}

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
