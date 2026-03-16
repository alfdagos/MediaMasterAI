package it.alf.mediarenamer.rename;

import it.alf.mediarenamer.model.MediaFile;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Sealed hierarchy representing either an approved or a skipped rename proposal.
 *
 * <p>A proposal is always produced by the {@link SmartRenameEngine} and consumed
 * by {@link SmartRenameEngine#executeProposals}.</p>
 */
public sealed interface RenameProposal
        permits RenameProposal.Approved, RenameProposal.Skipped {

    /** The source file. */
    MediaFile file();

    /** Convenience factory for an approved proposal. */
    static RenameProposal approved(MediaFile file, Path targetPath, String strategyUsed) {
        return new Approved(file, targetPath, strategyUsed);
    }

    /** Convenience factory for a skipped proposal. */
    static RenameProposal skipped(MediaFile file, String reason) {
        return new Skipped(file, reason);
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * A proposal that has a computed, collision-free target path.
     *
     * @param file         the source media file
     * @param targetPath   absolute, sanitised, collision-free destination path
     * @param strategyUsed the {@link RenameStrategy#id()} that generated the stem
     */
    record Approved(MediaFile file, Path targetPath, String strategyUsed)
            implements RenameProposal {

        public Approved {
            Objects.requireNonNull(file,         "file must not be null");
            Objects.requireNonNull(targetPath,   "targetPath must not be null");
            Objects.requireNonNull(strategyUsed, "strategyUsed must not be null");
        }
    }

    /**
     * A proposal that was rejected before reaching the execution phase.
     *
     * @param file   the source media file
     * @param reason human-readable explanation of why this file was skipped
     */
    record Skipped(MediaFile file, String reason)
            implements RenameProposal {

        public Skipped {
            Objects.requireNonNull(file,   "file must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
        }
    }
}
