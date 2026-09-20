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
 * <p>The credential is resolved eagerly but validated lazily: a pipeline that enables the plugin
 * without ever asking a question must not fail for want of a key it never uses.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class JevConfig {

    static final String DEFAULT_ENDPOINT = 'https://api.typesafe.ai/v1/systemone'
    static final String DEFAULT_MODEL = 'jev-latest'
    static final int DEFAULT_TIMEOUT_SECONDS = 30

    static final String API_KEY_VAR = 'TYPESAFE_API_KEY'

    private final String apiKey

    final String endpoint
    final String model
    final Duration timeout

    /** Where to keep cached responses; {@code null} -- the default -- disables caching entirely. */
    final String cacheDir

    JevConfig(Map opts, Map<String,String> env) {
        final config = opts ?: Collections.emptyMap()
        final sysEnv = env ?: Collections.<String,String>emptyMap()
        this.endpoint = (config.endpoint ?: DEFAULT_ENDPOINT).toString()
        this.model = (config.model ?: DEFAULT_MODEL).toString()
        this.timeout = Duration.ofSeconds((config.timeout ?: DEFAULT_TIMEOUT_SECONDS) as long)
        this.apiKey = (config.apiKey ?: sysEnv.get(API_KEY_VAR))?.toString()
        this.cacheDir = config.cacheDir?.toString() ?: null
    }

    /**
     * The credential to present, failing when none was configured. Accessed per request so the
     * error surfaces at the first question asked, not at plugin load.
     */
    String getApiKey() {
        if( !apiKey )
            throw new AbortOperationException("Missing TypeSafe credential - set `jev.apiKey` in the Nextflow configuration, or the ${API_KEY_VAR} environment variable")
        return apiKey
    }
}
