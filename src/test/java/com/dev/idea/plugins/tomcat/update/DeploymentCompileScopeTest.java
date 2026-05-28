package com.dev.idea.plugins.tomcat.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link DeploymentCompileScope#transitiveClosure} — the pure graph walk
 * that expands the deployment's seed modules into the full set that must be
 * compiled (the modules plus their transitive upstream dependencies).
 *
 * <p>The scope correctness contract rests entirely on this closure being
 * <em>complete</em> (every reachable module included) and <em>terminating</em>
 * (real module graphs contain cycles and diamonds). Both are verified here with
 * plain string graphs, so the guarantee is locked down without a live
 * {@code Project} or module fixtures.
 */
class DeploymentCompileScopeTest {

    /** Builds an edge function from an adjacency map; absent keys have no successors. */
    private static Function<String, Collection<String>> graph(Map<String, List<String>> adjacency) {
        return node -> adjacency.getOrDefault(node, List.of());
    }

    @Test
    @DisplayName("empty seed set → empty closure")
    void emptySeeds() {
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of(), graph(Map.of()));
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("seed with no dependencies → just the seed")
    void singleNodeNoEdges() {
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("web"), graph(Map.of()));
        assertEquals(Set.of("web"), result);
    }

    @Test
    @DisplayName("linear chain → all modules upstream of the seed")
    void linearChain() {
        // web -> service -> dao -> common
        var adjacency = Map.of(
                "web", List.of("service"),
                "service", List.of("dao"),
                "dao", List.of("common"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("web"), graph(adjacency));
        assertEquals(Set.of("web", "service", "dao", "common"), result);
    }

    @Test
    @DisplayName("diamond → shared dependency appears exactly once")
    void diamondDeduplicates() {
        // web -> {a, b}; a -> common; b -> common
        var adjacency = Map.of(
                "web", List.of("a", "b"),
                "a", List.of("common"),
                "b", List.of("common"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("web"), graph(adjacency));
        assertEquals(Set.of("web", "a", "b", "common"), result);
        assertEquals(4, result.size()); // common counted once
    }

    @Test
    @DisplayName("cycle → terminates and includes every node in the cycle")
    void cycleTerminates() {
        // a -> b -> c -> a  (pathological but legal module graph)
        var adjacency = Map.of(
                "a", List.of("b"),
                "b", List.of("c"),
                "c", List.of("a"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("a"), graph(adjacency));
        assertEquals(Set.of("a", "b", "c"), result);
    }

    @Test
    @DisplayName("self-edge → terminates and includes the node once")
    void selfEdgeTerminates() {
        var adjacency = Map.of("a", List.of("a"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("a"), graph(adjacency));
        assertEquals(Set.of("a"), result);
    }

    @Test
    @DisplayName("multiple seeds → union of their closures, deduplicated")
    void multipleSeedsUnion() {
        // web1 -> common; web2 -> common; web2 -> extra
        var adjacency = Map.of(
                "web1", List.of("common"),
                "web2", List.of("common", "extra"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("web1", "web2"), graph(adjacency));
        assertEquals(Set.of("web1", "web2", "common", "extra"), result);
    }

    @Test
    @DisplayName("seed reachable from another seed → present once, no duplication")
    void seedReachableFromSeed() {
        // web -> service, and service is also passed as a seed
        var adjacency = Map.of("web", List.of("service"));
        Set<String> result = DeploymentCompileScope.transitiveClosure(List.of("web", "service"), graph(adjacency));
        assertEquals(Set.of("web", "service"), result);
        assertEquals(2, result.size());
    }
}
