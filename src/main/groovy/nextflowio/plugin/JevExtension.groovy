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
import nextflow.Session
import nextflow.SysEnv
import nextflow.exception.AbortOperationException
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint

/**
 * The {@code nf-jev} function surface: three question builders and the call that answers them.
 *
 * <p>Questions are values. Build them once, reuse them across a cohort, and send the ones that
 * share a context together -- they are evaluated in parallel against that one state, so a rubric
 * costs a single request.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@CompileStatic
class JevExtension extends PluginExtensionPoint {

    // written once by init(), read by every dataflow thread that calls jev(); volatile so the
    // publication is guaranteed by the field rather than by Nextflow's start-up ordering
    private volatile JevClient client

    @Override
    protected void init(Session session) {
        final opts = (Map) session?.config?.get('jev')
        this.client = new JevClient(new JevConfig(opts, SysEnv.get()))
    }

    /**
     * A yes/no judgment. The answer is the probability that the statement holds, so 0.5 means
     * "equally likely either way" -- not "moderately".
     *
     * @param instructions the statement to evaluate, e.g. {@code 'The sample is human.'}
     */
    @Function
    Map noul(String instructions) {
        requireText(instructions, 'noul')
        return [type: 'noul', instructions: instructions]
    }

    /**
     * A selection from a closed set of options. The answer carries the winner plus the
     * distribution over every option, so code can gate on how clear the win was.
     *
     * @param instructions the question to answer
     * @param criteria     option to what that option means; at least two
     */
    @Function
    Map choice(String instructions, Map criteria) {
        requireText(instructions, 'choice')
        if( !criteria || criteria.size() < 2 )
            throw new AbortOperationException("A choice question needs at least two options - got ${criteria?.size() ?: 0}")
        return [type: 'choice', instructions: instructions, criteria: criteria]
    }

    /**
     * A position along ordered levels. The answer can fall between two levels, weighted by the
     * probability of each.
     *
     * @param instructions the dimension to rate
     * @param levels       the levels in order, lowest first; at least two
     */
    @Function
    Map score(String instructions, List levels) {
        requireText(instructions, 'score')
        if( !levels || levels.size() < 2 )
            throw new AbortOperationException("A score question needs at least two levels - got ${levels?.size() ?: 0}")
        return [type: 'score', instructions: instructions, criteria: levels]
    }

    /**
     * Answer every question against the shared state, in one request.
     *
     * @param state     the context to judge; a string, or a map or list rendered as JSON
     * @param questions question id to a question built by {@link #noul}, {@link #choice} or
     *                  {@link #score}
     * @return the answers, keyed by the same question ids
     */
    @Function
    Map jev(Object state, Map questions) {
        return client.ask(state, questions)
    }

    private static void requireText(String instructions, String kind) {
        if( !instructions?.trim() )
            throw new AbortOperationException("The `instructions` of a ${kind} question cannot be empty")
    }
}
