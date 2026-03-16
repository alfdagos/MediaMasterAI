package it.alf.mediarenamer.rename;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable options that control how {@link SmartRenameEngine} generates proposals.
 *
 * @param strategyId         forced strategy ID (e.g. {@code "DATE_LOCATION"});
 *                           {@link Optional#empty()} means auto-select
 * @param collisionPolicy    what to do when the target path already exists
 * @param enrichmentEnabled  whether to call external APIs for metadata enrichment
 * @param dryRun             when {@code true} no filesystem writes are performed
 */
public record RenameOptions(
        Optional<String> strategyId,
        CollisionPolicy  collisionPolicy,
        boolean          enrichmentEnabled,
        boolean          dryRun) {

    public RenameOptions {
        Objects.requireNonNull(strategyId,      "strategyId must not be null");
        Objects.requireNonNull(collisionPolicy, "collisionPolicy must not be null");
    }

    /** Default options: auto strategy, SKIP collisions, enrichment enabled, live mode. */
    public static RenameOptions defaults() {
        return new RenameOptions(Optional.empty(), CollisionPolicy.SKIP, true, false);
    }

    /** Returns a copy with the given strategy ID forced. */
    public RenameOptions withStrategy(String id) {
        return new RenameOptions(Optional.of(id), collisionPolicy, enrichmentEnabled, dryRun);
    }

    /** Returns a copy with the given collision policy. */
    public RenameOptions withCollisionPolicy(CollisionPolicy policy) {
        return new RenameOptions(strategyId, policy, enrichmentEnabled, dryRun);
    }

    /** Returns a dry-run copy of these options. */
    public RenameOptions asDryRun() {
        return new RenameOptions(strategyId, collisionPolicy, enrichmentEnabled, true);
    }

    // ── Nested enum ────────────────────────────────────────────────────────

    /**
     * Determines what happens when the proposed target path already exists.
     */
    public enum CollisionPolicy {
        /** Leave the source file untouched (default). */
        SKIP,
        /** Append {@code _1}, {@code _2}, … to find a free name. */
        SUFFIX,
        /** Overwrite the existing file. Requires explicit opt-in. */
        OVERWRITE
    }
}
