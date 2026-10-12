/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Stands in for the holder that seals declarations, for the tests that drive a commit chain
 * directly instead of through a plan. It answers with a table whose places are the ones the order
 * source of the test resolves for the nodes the test declares, so what is exercised is still the
 * verification core and not a table written by the caller of the reservation.
 *
 * <p>The live generations it reports are the ones the test froze its identities at: it is a host
 * stand-in, and a test that wants drift changes the number it reports. */
public final class WitnessSource implements IdentityWitness.Source {

    private final CommitLog.OrderSource plans;
    private final String worldId;
    private final String domainId;
    private final long chunkPos;
    private final String status;
    private final long worldEpoch;
    private final LongSupplier authorization;
    private final List<String> nodes = new ArrayList<>();
    private final Map<String, FrozenDeclarations.DeclaredChunk> chunks = new LinkedHashMap<>();
    private final Map<String, String> statuses = new LinkedHashMap<>();

    public WitnessSource(CommitLog.OrderSource plans, String worldId, String domainId, long chunkPos,
                         String status, long worldEpoch, long authorization, String... nodes) {
        this(plans, worldId, domainId, chunkPos, status, worldEpoch, () -> authorization, nodes);
    }

    /** The same, for a fixture whose grant is minted by the plan it hands out and moves with it. */
    public WitnessSource(CommitLog.OrderSource plans, String worldId, String domainId, long chunkPos,
                         String status, long worldEpoch, LongSupplier authorization,
                         String... nodes) {
        this.plans = plans;
        this.worldId = worldId;
        this.domainId = domainId;
        this.chunkPos = chunkPos;
        this.status = status;
        this.worldEpoch = worldEpoch;
        this.authorization = authorization;
        this.nodes.addAll(List.of(nodes));
    }

    /** Declares another artefact for one node than the default this source was built with, which is
     * how a fixture drives two chunks or two states of the same number. */
    public WitnessSource declare(String nodeKey, long chunkPos, String status) {
        if (!nodes.contains(nodeKey)) {
            nodes.add(nodeKey);
        }
        chunks.put(nodeKey, new FrozenDeclarations.DeclaredChunk(chunkPos));
        statuses.put(nodeKey, status);
        return this;
    }

    @Override
    public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
        List<FrozenDeclarations.Entry> entries = new ArrayList<>();
        for (String node : nodes) {
            CommitLog.Resolved resolved = plans.resolve(worldId, domainId, node);
            if (resolved == null || resolved.planSequence() != planSequence) {
                continue;
            }
            FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(worldId, domainId,
                planSequence, resolved.position());
            FrozenDeclarations.DeclaredChunk chunk = chunks.get(node);
            entries.add(new FrozenDeclarations.Entry(seat, node, entries.size() + 1,
                chunk == null ? chunkPos : chunk.packedPos(),
                statuses.getOrDefault(node, status)));
        }
        return FrozenDeclarations.of(planSequence, entries);
    }

    @Override
    public long liveWorldEpoch(String worldId) {
        return worldEpoch;
    }

    @Override
    public long liveAuthorization(String worldId, String domainId, String nodeKey) {
        return authorization.getAsLong();
    }

    @Override
    public CommitNamespaces.Namespace currentController() {
        return null;
    }
}
