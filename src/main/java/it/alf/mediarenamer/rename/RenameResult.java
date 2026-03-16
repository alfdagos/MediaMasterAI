package it.alf.mediarenamer.rename;

import it.alf.mediarenamer.model.MediaFile;

import java.nio.file.Path;

/**
 * Sealed hierarchy representing the result of a rename proposal.
 *
 * <p>Use pattern matching to handle each outcome:</p>
 * <pre>{@code
 * switch (result) {
 *     case RenameResult.Success s    -> console.success("✓ " + s.targetPath().getFileName());
 *     case RenameResult.DryRun d     -> console.info("  " + d.targetPath().getFileName() + " [preview]");
 *     case RenameResult.Skipped sk   -> console.warning("⚠ Skipped: " + sk.reason());
 *     case RenameResult.Failed f     -> console.error("✗ Failed: " + f.cause().getMessage());
 * }
 * }</pre>
 */
public sealed interface RenameResult
        permits RenameResult.Success,
                RenameResult.DryRun,
                RenameResult.Skipped,
                RenameResult.Failed {

    /** The source file that was (or would have been) renamed. */
    MediaFile file();

    // ─────────────────────────────────────────────────────────────────────

    /**
     * The rename was executed successfully and the file now exists at {@link #targetPath()}.
     */
    record Success(MediaFile file, Path targetPath, String strategyUsed)
            implements RenameResult {}

    /**
     * Dry-run result: the rename would succeed; the file was not actually moved.
     */
    record DryRun(MediaFile file, Path targetPath, String strategyUsed)
            implements RenameResult {}

    /**
     * The rename was deliberately skipped (no matching strategy, collision policy, etc.).
     */
    record Skipped(MediaFile file, String reason)
            implements RenameResult {}

    /**
     * The rename failed with an unexpected I/O error.
     */
    record Failed(MediaFile file, Exception cause)
            implements RenameResult {}
}
