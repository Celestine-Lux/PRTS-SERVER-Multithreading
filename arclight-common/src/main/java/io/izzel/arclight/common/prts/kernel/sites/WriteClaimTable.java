/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The one place a domain key is translated into the write path keys its own code writes through.
 * It is an explicit list and never a wildcard: a domain the table does not name claims nothing. */
public final class WriteClaimTable {

    public static final String CHUNK_PIPELINE = "chunk-pipeline";

    public static final int DOMAINS = 1;

    public static final int PATHS = 1;

    private static final Map<String, Set<WritePath>> CLAIMS = Map.of(
        CHUNK_PIPELINE, Set.of(WritePath.BLOCK_WRITE));

    private WriteClaimTable() {
    }

    public static Set<String> pathsOf(String domainId) {
        Set<WritePath> paths = domainId == null ? null : CLAIMS.get(domainId);
        if (paths == null) {
            return Set.of();
        }
        Set<String> keys = new TreeSet<>();
        for (WritePath path : paths) {
            keys.add(path.key());
        }
        return keys;
    }

    public static boolean claims(String domainId) {
        return domainId != null && CLAIMS.containsKey(domainId);
    }

    public static Set<String> domains() {
        return new TreeSet<>(CLAIMS.keySet());
    }
}
