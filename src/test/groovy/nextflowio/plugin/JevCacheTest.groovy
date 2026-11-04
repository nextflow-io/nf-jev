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

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import spock.lang.Specification
import spock.lang.TempDir

class JevCacheTest extends Specification {

    @TempDir
    Path folder

    def 'should be null when no directory is configured' () {
        expect:
        JevCache.of(null) == null
        JevCache.of('') == null
        JevCache.of(folder.toString()) != null
    }

    def 'should round-trip a response' () {
        given:
        def cache = JevCache.of(folder.toString())

        when:
        cache.put('{"model":"jev","state":"a"}', '{"answers":{}}')

        then:
        cache.get('{"model":"jev","state":"a"}') == '{"answers":{}}'
    }

    def 'should miss on an unknown request' () {
        expect:
        JevCache.of(folder.toString()).get('never asked') == null
    }

    def 'should key on the exact request payload' () {
        given:
        def cache = JevCache.of(folder.toString())

        when:
        cache.put('payload one', 'first')
        cache.put('payload two', 'second')

        then:
        cache.get('payload one') == 'first'
        cache.get('payload two') == 'second'
    }

    def 'should shard entries by the leading hash bytes' () {
        given:
        def cache = JevCache.of(folder.toString())

        when:
        cache.put('some payload', 'stored')

        then: 'one two-character directory holding one entry'
        def shards = Files.list(folder).toList()
        shards.size() == 1
        shards[0].fileName.toString().length() == 2
        Files.list(shards[0]).toList().size() == 1
    }

    def 'should leave no temporary file behind' () {
        given:
        def cache = JevCache.of(folder.toString())

        when:
        cache.put('some payload', 'stored')

        then:
        Files.walk(folder).filter { it.fileName.toString().endsWith('.tmp') }.toList().isEmpty()
    }

    def 'should not fail the run when the directory cannot be written' () {
        given: 'a path whose parent is a file, so no directory can be created under it'
        def blocker = Files.writeString(folder.resolve('blocker'), 'x')
        def cache = JevCache.of(blocker.resolve('cache').toString())

        when:
        cache.put('some payload', 'stored')

        then: 'the failure is swallowed - a cache that cannot write is a slower cache'
        noExceptionThrown()

        and: 'and reading it back is simply a miss'
        cache.get('some payload') == null
    }

    def 'should survive concurrent writers of the same key' () {
        given:
        def cache = JevCache.of(folder.toString())
        def pool = Executors.newFixedThreadPool(8)
        def start = new CountDownLatch(1)
        def payload = '{"model":"jev","state":"contended"}'

        when: '8 threads publish the same entry at once, then it is read back'
        def tasks = (1..8).collect { i ->
            pool.submit({ ->
                start.await()
                cache.put(payload, '{"answers":{"q":{"noul":0.9}}}')
            } as Runnable)
        }
        start.countDown()
        tasks.each { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        then: 'the entry is whole, not a torn mixture of the writes'
        cache.get(payload) == '{"answers":{"q":{"noul":0.9}}}'

        and: 'and no temporary files are stranded'
        Files.walk(folder).filter { it.fileName.toString().endsWith('.tmp') }.toList().isEmpty()
    }
}
