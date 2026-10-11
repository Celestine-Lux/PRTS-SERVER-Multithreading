/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Reviews the write rights a planning period handed out against the declarations that produced them.
 *
 * <p>A right is reviewed on three faces. Which credential is in place for which world and domain,
 * and whether the version that credential names is the version the slot of that key carries. Whether
 * the domains actually held are covered by the write sets the frozen graph declared: a right no
 * declaration covers is a right that reached past its own job. And whether the four counts of the
 * registry close, so a right that was held is accounted for by an acquisition and by exactly one of
 * a release and a reclamation.
 *
 * <p>What a review is not: a right is not a write. Nothing here counts a landing, and a domain whose
 * writes cannot be attributed to it from the write face is reported as unattributed instead of being
 * read as a set of zero writes.
 */
public final class PipelineRightReview {

    private PipelineRightReview() {
    }

    /** One reading of the review face: the lines a readout publishes, plus the verdicts they carry so
     * a caller does not have to re-parse the text it just produced. An empty input - no declaration
     * of any domain - is reported as not executable rather than as a gate that passed or failed. */
    public record Review(List<String> lines, boolean activeFromDeclaration, boolean versionMatchesSlot,
                         boolean observedWithinDeclared, boolean conservationHolds, boolean executable) {
    }

    /** Reads the face. The declared sets come from the frozen graph itself, the held set comes from
     * the registry, and the two are compared here instead of being taken from a counter that already
     * decided the answer. */
    public static Review of(TickPlan plan, OwnerRegistry owners, WriteVersionSlots versions,
                            OwnerGrantPoint grants, long hostWrites, int claimSlots) {
        Map<String, String> declared = new TreeMap<>();
        Map<String, List<String>> declaringNodes = new TreeMap<>();
        Map<String, Integer> declaringDemands = new TreeMap<>();
        Map<String, Integer> declaringOutside = new TreeMap<>();
        long demands = 0L;
        long outside = 0L;
        if (plan != null && plan.graph() != null) {
            for (JobGraph.Node node : plan.graph().nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    String key = keyOf(ref.worldId(), ref.domainId());
                    declared.putIfAbsent(key, ref.worldId() + "/" + ref.domainId() + "@" + ref.level());
                    declaringNodes.computeIfAbsent(key, ignored -> new ArrayList<>()).add(node.key());
                }
                for (JobDeclaration.OwnerDemand demand : node.ownerDemands()) {
                    demands++;
                    String key = keyOf(demand.worldId(), demand.domainId());
                    declaringDemands.merge(key, 1, Integer::sum);
                    if (!coveredBy(node, demand)) {
                        outside++;
                        declaringOutside.merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        List<Map.Entry<OwnershipDomain, OwnerToken>> held =
            new ArrayList<>(owners.snapshot().entrySet());
        held.sort(Comparator
            .comparing((Map.Entry<OwnershipDomain, OwnerToken> entry) -> entry.getKey().worldId())
            .thenComparing(entry -> entry.getKey().level().name())
            .thenComparing(entry -> entry.getKey().domainId()));
        Set<String> observed = new TreeSet<>();
        List<String> rows = new ArrayList<>();
        int matches = 0;
        int mismatches = 0;
        int uncovered = 0;
        for (Map.Entry<OwnershipDomain, OwnerToken> entry : held) {
            OwnershipDomain domain = entry.getKey();
            OwnerToken token = entry.getValue();
            String key = keyOf(domain.worldId(), domain.domainId());
            long slot = versions.peek(domain.worldId(), domain.level(), domain.domainId());
            boolean versionMatch = token.expectedVersion() > WriteVersionSlots.NOT_CARRIED
                && token.expectedVersion() == slot;
            boolean inDeclared = declared.containsKey(key);
            if (versionMatch) {
                matches++;
            } else {
                mismatches++;
            }
            if (!inDeclared) {
                uncovered++;
            }
            observed.add(key);
            List<String> nodes = declaringNodes.get(key);
            rows.add("world=" + domain.worldId() + "|level=" + domain.level().name() + "|domain="
                + domain.domainId() + "|holder=" + token.holderSiteId() + "|epoch=" + token.epoch()
                + "|expected_version=" + token.expectedVersion() + "|slot_version=" + slot
                + "|version_match=" + (versionMatch ? 1 : 0) + "|in_declared=" + (inDeclared ? 1 : 0)
                + "|declared_write_set=" + declared.getOrDefault(key, "-")
                + "|write_set_digest=" + WriteControlledSlots.fold(List.of(key))
                + "|declaring_nodes=" + (nodes == null || nodes.isEmpty() ? "-" : String.join(",", nodes))
                + "|declaring_demands=" + declaringDemands.getOrDefault(key, 0)
                + "|declaring_outside_declared=" + declaringOutside.getOrDefault(key, 0));
        }

        boolean executable = demands > 0L || !declared.isEmpty();
        boolean activeFromDeclaration = grants.enabled() && owners.activeTokens() > 0
            && owners.activeTokens() == observed.size() && uncovered == 0
            && owners.activeTokens() == grants.heldCount();
        boolean versionMatchesSlot = owners.activeTokens() > 0 && matches > 0 && mismatches == 0;
        boolean observedWithinDeclared = executable && uncovered == 0 && outside == 0;
        boolean conservationHolds = owners.conservationHolds();

        List<String> lines = new ArrayList<>();
        add(lines, "r02.rights.enabled", grants.enabled() ? 1 : 0);
        add(lines, "r02.rights.active", owners.activeTokens());
        add(lines, "r02.rights.held", grants.heldCount());
        add(lines, "r02.rights.declared_count", grants.declaredCount());
        add(lines, "r02.rights.granted_count", grants.grantedCount());
        add(lines, "r02.rights.released_by_plan", grants.releasedByPlanCount());
        add(lines, "r02.rights.refused", grants.refusedCount());
        add(lines, "r02.rights.demands_in_plan", demands);
        add(lines, "r02.rights.demands_outside_declared", outside);
        add(lines, "r02.rights.declared_keys", declared.size());
        add(lines, "r02.rights.declared_digest", WriteControlledSlots.fold(declared.keySet()));
        add(lines, "r02.rights.observed_keys", observed.size());
        add(lines, "r02.rights.observed_digest", WriteControlledSlots.fold(observed));
        add(lines, "r02.rights.observed_outside_declared", uncovered);
        add(lines, "r02.rights.version_match", matches);
        add(lines, "r02.rights.version_mismatch", mismatches);
        add(lines, "r02.rights.subset_ok", observedWithinDeclared ? 1 : 0);
        add(lines, "r02.rights.version_ok", versionMatchesSlot ? 1 : 0);
        add(lines, "r02.rights.sole_path_ok", activeFromDeclaration ? 1 : 0);
        add(lines, "r02.rights.conservation_ok", conservationHolds ? 1 : 0);
        add(lines, "r02.rights.host_writes_observed", hostWrites);
        add(lines, "r02.rights.claim_slots", claimSlots);
        add(lines, "r02.rights.physical_attribution_available", claimSlots > 0 ? 1 : 0);
        add(lines, "r02.rights.not_executable", executable ? 0 : 1);
        add(lines, "r02.rights.rows", rows.size());
        for (int index = 0; index < rows.size(); index++) {
            add(lines, "r02.right." + (index + 1), rows.get(index));
        }
        return new Review(List.copyOf(lines), activeFromDeclaration, versionMatchesSlot,
            observedWithinDeclared, conservationHolds, executable);
    }

    /** Whether the job the demand belongs to declares the very right the demand asks for. The level
     * is not compared: a right is held at the level the plan keeps the version of a declared domain
     * at, which is the level the token has to name. */
    private static boolean coveredBy(JobGraph.Node node, JobDeclaration.OwnerDemand demand) {
        for (JobDeclaration.DomainRef ref : node.writeSet()) {
            if (ref.worldId().equals(demand.worldId()) && ref.domainId().equals(demand.domainId())) {
                return true;
            }
        }
        return false;
    }

    /** The key a declared set and a held set are compared on: world and domain, without the level. */
    private static String keyOf(String worldId, String domainId) {
        return worldId + "/" + domainId;
    }

    private static void add(List<String> lines, String name, Object value) {
        lines.add(name + "=" + value);
    }
}
