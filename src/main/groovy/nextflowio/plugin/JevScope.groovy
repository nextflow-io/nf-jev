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
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName
import nextflow.script.dsl.Description

/**
 * The {@code jev} configuration scope, as Nextflow's config spec sees it.
 *
 * <p>{@link JevConfig} still resolves these options at runtime (provider inference, the
 * environment fallback for the credential, the timeout as a {@link java.time.Duration}). This
 * class is the declaration Nextflow reflects so a {@code jev.*} setting is recognized instead of
 * logged as an unrecognized config option. The names, types and defaults match that reader: a
 * missing {@code timeout} is 30 seconds, and {@code provider}, {@code endpoint} and {@code model}
 * fall back to the TypeSafe defaults unless the endpoint host selects OpenRouter.
 */
@ScopeName('jev')
@Description('''
    The `jev` scope controls how the nf-jev plugin calls a Jev decisions endpoint.
''')
@CompileStatic
class JevScope implements ConfigScope {

    @ConfigOption
    @Description('''
        Which service answers requests: `typesafe` or `openrouter` (default: `typesafe`).
        Inferred from the host of `endpoint` when unset.
    ''')
    final String provider

    @ConfigOption
    @Description('''
        Credential for the selected provider. When unset, the plugin reads that provider's
        environment variable (`TYPESAFE_API_KEY` or `OPENROUTER_API_KEY`).
    ''')
    final String apiKey

    @ConfigOption
    @Description('''
        Model id (default: `jev-latest` on TypeSafe, `typesafe/jev-1.13` on OpenRouter).
    ''')
    final String model

    @ConfigOption
    @Description('''
        Decisions endpoint (default: `https://api.typesafe.ai/v1/systemone`, or
        `https://openrouter.ai/api/alpha/decisions` when OpenRouter is selected).
    ''')
    final String endpoint

    @ConfigOption
    @Description('''
        Request timeout, in seconds (default: `30`).
    ''')
    final int timeout

    @ConfigOption
    @Description('''
        Directory of cached responses. Unset disables caching.
    ''')
    final String cacheDir

    /* required by the extension point -- do not remove */
    JevScope() {
        this([:])
    }

    /**
     * @param opts the {@code jev} config block, as parsed
     */
    JevScope(Map opts) {
        final resolved = new JevConfig(opts, Collections.<String,String>emptyMap())
        this.provider = resolved.provider.configName
        this.endpoint = resolved.endpoint
        this.model = resolved.model
        this.timeout = (int) resolved.timeout.toSeconds()
        this.apiKey = opts?.get('apiKey')?.toString()
        this.cacheDir = resolved.cacheDir
    }
}
