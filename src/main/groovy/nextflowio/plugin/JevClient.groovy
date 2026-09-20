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

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.exception.AbortOperationException

/**
 * Client for the TypeSafe System One decisions endpoint.
 *
 * <p>One request carries the shared {@code state} plus a map of typed questions. Every question is
 * evaluated against that same state, in parallel and independently of the others, which is what
 * makes a whole rubric cost a single call. This is not a chat model: it generates no text and
 * holds no conversation, so a request either answers every question or fails outright -- there is
 * no partial result to salvage and no turn to retry.
 *
 * <p>The endpoint is configurable, so a compatible one can be substituted -- a proxy, or a future
 * API version -- without touching a pipeline.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class JevClient {

    /** The statuses the vendor documents as retryable: rate limited, and overloaded. */
    private static final Set<Integer> RETRYABLE = Set.of(429, 529)
    private static final int MAX_ATTEMPTS = 3
    private static final long BACKOFF_MILLIS = 250

    /** Model ids that follow the newest build rather than naming a fixed one. */
    private static final Set<String> FLOATING_ALIASES = Set.of('jev-latest', 'jev-preview')

    private final JevConfig config
    private final HttpClient httpClient
    private final JevCache cache

    JevClient(JevConfig config) {
        this.config = config
        this.cache = JevCache.of(config.cacheDir)
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        if( cache != null && FLOATING_ALIASES.contains(config.model) )
            log.warn "Jev caching is enabled with the floating model alias `${config.model}` - cached answers will keep being replayed after the alias moves to a newer snapshot; pin `jev.model` to a version such as `jev-1.13.0`"
    }

    /**
     * Evaluate every question against the shared state in one request.
     *
     * @param state     the context the questions are answered against; a string, or a map or list
     *                  rendered as JSON
     * @param questions question id to question definition
     * @return the answers, keyed by the same question ids
     */
    Map ask(Object state, Map questions) {
        if( !questions )
            throw new AbortOperationException("At least one question is required to call Jev")

        final payload = JsonOutput.toJson([model: config.model, state: state, questions: questions])

        // A cache entry that will not parse, or that carries no answers, is a miss and nothing
        // worse: the run re-asks the question. Only a LIVE response that fails this way is an error.
        final cached = cache?.get(payload)
        if( cached != null ) {
            final body = parseOrNull(cached)
            if( body?.answers != null ) {
                log.debug "Jev cache hit model=${body.model}"
                return body.answers as Map
            }
            log.debug "Discarding an unusable Jev cache entry - asking again"
        }

        final response = post(payload)
        final body = parse(response)
        final answers = body.answers as Map
        if( answers == null )
            throw new AbortOperationException("Unexpected Jev response - expected an `answers` object, got: ${body.keySet()}")

        cache?.put(payload, response)
        log.debug "Jev model=${body.model} usage=${body.usage}"
        return answers
    }

    private Map parse(String text) {
        final result = parseOrNull(text)
        if( result == null )
            throw new AbortOperationException("Unable to parse the Jev response - ${abbreviate(text)}")
        return result
    }

    /**
     * Parse a response, or {@code null} when it is not usable JSON. A new {@code JsonSlurper} per
     * call on purpose: it is not thread safe, and several dataflow threads parse concurrently.
     */
    private Map parseOrNull(String text) {
        try {
            return new JsonSlurper().parseText(text) as Map
        }
        catch( Exception e ) {
            return null
        }
    }

    private static String abbreviate(String text) {
        return text != null && text.length() > 200 ? text.substring(0, 200) + '...' : text
    }

    private String post(String payload) {
        final request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .timeout(config.timeout)
            .header('Authorization', "Bearer ${config.apiKey}")
            .header('Content-Type', 'application/json')
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        String failure = null
        for( int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ ) {
            try {
                final response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                if( response.statusCode() == 200 )
                    return response.body()
                if( !RETRYABLE.contains(response.statusCode()) )
                    throw new AbortOperationException("Jev request failed with status ${response.statusCode()} - ${response.body()}")
                failure = "status ${response.statusCode()}"
            }
            catch( InterruptedException e ) {
                // the run is shutting down; restore the flag and say so plainly, rather than
                // letting a bare InterruptedException mask whatever actually failed
                Thread.currentThread().interrupt()
                throw new AbortOperationException("Jev request interrupted")
            }
            catch( IOException e ) {
                failure = e.message
            }
            log.debug "Jev request failed (${failure}) - attempt ${attempt} of ${MAX_ATTEMPTS}"
            if( attempt < MAX_ATTEMPTS )
                Thread.sleep(BACKOFF_MILLIS << (attempt - 1))
        }
        throw new AbortOperationException("Jev request failed after ${MAX_ATTEMPTS} attempts - ${failure}")
    }
}
