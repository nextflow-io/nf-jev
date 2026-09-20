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

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * A content-addressed store of Jev responses, keyed on the request that produced them.
 *
 * <p>Deliberately dumb: no eviction, no expiry, no size limit. A judgment is immutable for a given
 * model, state and question set, an entry is a few hundred bytes, and {@code rm -rf} is the
 * eviction policy.
 *
 * <p>Entries are stored as the raw response TEXT rather than a parsed map, for two reasons. A hit
 * then reports the same usage and resolved model snapshot the live call did, and -- more
 * importantly -- every caller gets its own parsed object. Handing the same map to several dataflow
 * threads would let one item's downstream mutation corrupt another's answers, which is an aliasing
 * bug that only appears under concurrency and reads like a model error.
 *
 * <p>The cache never fails a run. An unwritable directory or an unreadable entry costs an API call,
 * not a pipeline.
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class JevCache {

    private final Path dir

    JevCache(Path dir) {
        this.dir = dir
    }

    /** A cache for the configured directory, or {@code null} when caching is off. */
    static JevCache of(String cacheDir) {
        return cacheDir ? new JevCache(Paths.get(cacheDir).toAbsolutePath()) : null
    }

    /** The stored response for this request, or {@code null} when there is none to be had. */
    String get(String payload) {
        final file = fileFor(payload)
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null
        }
        catch( IOException e ) {
            log.debug "Unable to read the Jev cache entry ${file} (${e.message}) - treating as a miss"
            return null
        }
    }

    /**
     * Store a response.
     *
     * <p>Written to a temporary file <b>in the entry's own directory</b> and published with an
     * atomic move, so a concurrent reader sees either the previous entry or this one and never a
     * partial file. The temporary file has to be a sibling: the default temporary directory is
     * frequently on another filesystem, where an atomic move is not supported at all.
     */
    void put(String payload, String response) {
        final file = fileFor(payload)
        Path tmp = null
        try {
            Files.createDirectories(file.parent)
            tmp = Files.createTempFile(file.parent, '.jev-', '.tmp')
            Files.writeString(tmp, response, StandardCharsets.UTF_8)
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            tmp = null
        }
        catch( Exception e ) {
            // a cache that cannot write is a slower cache, not a failed run
            log.debug "Unable to write the Jev cache entry ${file} - ${e.message}"
        }
        finally {
            if( tmp != null )
                Files.deleteIfExists(tmp)
        }
    }

    protected Path fileFor(String payload) {
        final hash = sha256(payload)
        return dir.resolve(hash.substring(0, 2)).resolve(hash.substring(2) + '.json')
    }

    private static String sha256(String text) {
        final digest = MessageDigest.getInstance('SHA-256').digest(text.getBytes(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(digest)
    }
}
