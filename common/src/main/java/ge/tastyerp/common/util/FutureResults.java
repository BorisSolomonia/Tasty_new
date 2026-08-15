package ge.tastyerp.common.util;

import ge.tastyerp.common.exception.DataStoreException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The one correct way to block on a Firestore {@code ApiFuture} (or any
 * {@link Future}) inside a request thread.
 *
 * <p>Established after BOR-81 finding B-3. The idiom this replaces —
 * <pre>
 *   try { snapshot = query.get().get(); }
 *   catch (InterruptedException | ExecutionException e) {
 *       log.error(...); Thread.currentThread().interrupt();   // return empty
 *   }
 * </pre>
 * — has two independent defects that appeared in 20+ places across three
 * services:</p>
 * <ol>
 *   <li>It sets the thread's interrupt flag on an {@link ExecutionException},
 *       which is <b>not</b> an interruption. Every later blocking call on that
 *       Tomcat thread then fails instantly with {@link InterruptedException},
 *       so one transient {@code DEADLINE_EXCEEDED} cascades through the rest of
 *       the request.</li>
 *   <li>It returns "no data" for "the store failed", so an outage renders as an
 *       empty ledger — a clean-looking wrong answer.</li>
 * </ol>
 *
 * <p>This helper: preserves the interrupt flag <em>only</em> on a genuine
 * interruption; converts every failure into a {@link DataStoreException}
 * (HTTP 503) that carries the root cause; and bounds the wait so a stalled
 * store cannot pin request threads forever.</p>
 */
public final class FutureResults {

    /** Generous default: a full-collection read of a few tens of thousands of docs. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private FutureResults() {
    }

    /**
     * Wait for the future with {@link #DEFAULT_TIMEOUT}.
     *
     * @param future  the pending call
     * @param context what the call was doing, in the imperative ("load audit mappings");
     *                becomes part of the exception message
     */
    public static <T> T await(Future<T> future, String context) {
        return await(future, context, DEFAULT_TIMEOUT);
    }

    public static <T> T await(Future<T> future, String context, Duration timeout) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(timeout, "timeout");
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // A genuine interruption: preserve the flag for whoever owns the thread.
            Thread.currentThread().interrupt();
            throw new DataStoreException("Interrupted while trying to " + context, e);
        } catch (ExecutionException e) {
            // NOT an interruption — do not touch the interrupt flag.
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new DataStoreException(
                    "Failed to " + context + ": " + rootMessage(cause), cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new DataStoreException(
                    "Timed out after " + timeout.toSeconds() + "s trying to " + context, e);
        } catch (CancellationException e) {
            throw new DataStoreException("Cancelled while trying to " + context, e);
        }
    }

    /** The most useful single line to log: the deepest cause's message, or its class. */
    public static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null && !message.isBlank() ? message : root.getClass().getSimpleName();
    }
}
