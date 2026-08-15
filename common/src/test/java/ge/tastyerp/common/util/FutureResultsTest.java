package ge.tastyerp.common.util;

import ge.tastyerp.common.exception.DataStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOR-81 finding B-3 regression: the interrupt flag must survive exactly one
 * failure mode (a real interruption) and must never be set by an
 * {@link java.util.concurrent.ExecutionException}; a failed store call must
 * throw, never quietly return.
 */
class FutureResultsTest {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        // Never leak an interrupt flag between tests.
        Thread.interrupted();
    }

    @Test
    @DisplayName("Completed future returns its value")
    void returnsValue() {
        assertEquals("ok", FutureResults.await(CompletableFuture.completedFuture("ok"), "read"));
    }

    @Test
    @DisplayName("ExecutionException becomes DataStoreException(503) and does NOT set the interrupt flag")
    void executionExceptionDoesNotInterrupt() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("FAILED_PRECONDITION: index missing"));

        DataStoreException ex = assertThrows(DataStoreException.class,
                () -> FutureResults.await(failed, "load audit mappings"));

        assertFalse(Thread.currentThread().isInterrupted(),
                "an ExecutionException is not an interruption — the flag must stay clear "
                        + "so the next Firestore call on this thread still works");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("TASTY_ERR_503", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("load audit mappings"), ex.getMessage());
        assertTrue(ex.getMessage().contains("index missing"), ex.getMessage());
        assertNotNull(ex.getCause());
        assertSame(IllegalStateException.class, ex.getCause().getClass());
    }

    @Test
    @DisplayName("A genuine interruption preserves the interrupt flag and throws")
    void interruptionPreservesFlag() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean flagAfter = new AtomicBoolean(false);
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Future<?> worker = pool.submit(() -> {
            CompletableFuture<String> never = new CompletableFuture<>();
            waiting.countDown();
            try {
                FutureResults.await(never, "wait forever", Duration.ofSeconds(30));
            } catch (Throwable t) {
                thrown.set(t);
            }
            flagAfter.set(Thread.currentThread().isInterrupted());
        });

        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        pool.shutdownNow(); // interrupts the worker mid-await
        try {
            worker.get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // shutdownNow may surface as CancellationException — the assertions below are what matter
        }
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(thrown.get() instanceof DataStoreException, String.valueOf(thrown.get()));
        assertTrue(flagAfter.get(), "interrupt flag must be preserved for the thread owner");
    }

    @Test
    @DisplayName("Timeout throws, cancels the future, and does not interrupt the caller")
    void timeoutThrowsAndCancels() {
        CompletableFuture<String> stalled = new CompletableFuture<>();
        DataStoreException ex = assertThrows(DataStoreException.class,
                () -> FutureResults.await(stalled, "read stalled doc", Duration.ofMillis(50)));
        assertTrue(ex.getMessage().contains("Timed out"), ex.getMessage());
        assertTrue(stalled.isCancelled(), "stalled work must be cancelled, not left running");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    @DisplayName("rootMessage digs to the deepest cause and falls back to the class name")
    void rootMessage() {
        RuntimeException deep = new RuntimeException(new IllegalArgumentException(new NullPointerException("npe!")));
        assertEquals("npe!", FutureResults.rootMessage(deep));
        assertEquals("NullPointerException", FutureResults.rootMessage(new NullPointerException()));
    }
}
