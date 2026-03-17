package it.alf.mediamaster.cli;

import java.io.Console;
import java.util.Objects;

/**
 * ANSI-coloured console output helper.
 *
 * <p>Detects whether the JVM is attached to a real TTY; if not (e.g. piped output,
 * CI environment), ANSI escape codes are omitted so log files stay readable.</p>
 */
public final class ConsoleOutput {

    private static final boolean ANSI_SUPPORTED =
            System.console() != null && !Boolean.getBoolean("media.renamer.noAnsi");

    // ANSI codes
    private static final String RESET  = "\u001B[0m";
    private static final String GREEN  = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED    = "\u001B[31m";
    private static final String CYAN   = "\u001B[36m";

    private ConsoleOutput() {}

    /** Prints a plain informational line. */
    public static void info(String message) {
        System.out.println(message);
    }

    /** Prints a plain line (alias for {@link #info}). */
    public static void print(String message) {
        System.out.println(message);
    }

    /** Prints a green success line. */
    public static void success(String message) {
        System.out.println(ANSI_SUPPORTED ? GREEN + message + RESET : message);
    }

    /** Prints a yellow warning line. */
    public static void warning(String message) {
        System.out.println(ANSI_SUPPORTED ? YELLOW + message + RESET : message);
    }

    /** Prints a red error line to stderr. */
    public static void error(String message) {
        System.err.println(ANSI_SUPPORTED ? RED + message + RESET : message);
    }

    /** Prints a cyan info line. */
    public static void highlight(String message) {
        System.out.println(ANSI_SUPPORTED ? CYAN + message + RESET : message);
    }

    /**
     * Prompts the user with a yes/no question and returns {@code true} if the user typed
     * {@code y} or {@code yes} (case-insensitive).
     *
     * <p>Falls back to {@code false} when there is no interactive console.</p>
     *
     * @param prompt the question to display (no trailing space needed)
     * @return {@code true} if the user confirmed
     */
    public static boolean confirm(String prompt) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Console console = System.console();
        if (console == null) {
            System.out.println("[no interactive console — defaulting to NO]");
            return false;
        }
        String answer = console.readLine(prompt + " [y/N] ").trim();
        return "y".equalsIgnoreCase(answer) || "yes".equalsIgnoreCase(answer);
    }
}
