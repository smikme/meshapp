package com.meshtastic.client.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatHistoryLoaderTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(CancelPath.class)
    // This fixture creates its schema in a temporary H2 database, without an IDE data source.
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    void cancellationMustNotCloseTheSharedH2FileChannel(CancelPath path,
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {
        String url = "jdbc:h2:" + home.resolve("history") + ";TRACE_LEVEL_FILE=0";
        CountDownLatch release = new CountDownLatch(1);
        var finishedIo = new java.util.concurrent.CompletableFuture<Void>();
        try (var connection = java.sql.DriverManager.getConnection(url);
             var loader = new ChatHistoryLoader()) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE test_messages(id INT PRIMARY KEY, text VARCHAR)");
                statement.execute("INSERT INTO test_messages VALUES(1, 'before cancellation')");
                statement.execute("CHECKPOINT SYNC");
            }
            var session = (org.h2.engine.SessionLocal) ((org.h2.jdbc.JdbcConnection) connection).getSession();
            var fileStore = (org.h2.mvstore.SingleFileStore) session.getDatabase().getStore().getMvStore().getFileStore();
            CountDownLatch ready = new CountDownLatch(1);
            var result = loader.submit(() -> {
                ready.countDown();
                // Pause immediately before real disk I/O. Unlike await(), park
                // leaves interrupt status set, just as cancellation between two
                // JDBC reads does. The H2 file handle is shared by all sessions.
                while (release.getCount() != 0) java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                try {
                    fileStore.readFully(null, 0, 64);
                    finishedIo.complete(null);
                } catch (Throwable error) {
                    finishedIo.completeExceptionally(error);
                }
                return 1;
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            switch (path) {
                case VIEW_CHANGE -> loader.cancel();
                case FUTURE -> result.cancel(true);
                case CLOSE -> loader.close();
            }
            release.countDown();
            finishedIo.get(5, TimeUnit.SECONDS);
            assertTrue(result.isCancelled());
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO test_messages VALUES(2, 'after cancellation')");
                statement.execute("CHECKPOINT SYNC");
            }
        } finally {
            release.countDown();
        }
        try (var connection = java.sql.DriverManager.getConnection(url);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM test_messages")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1), "writes and previews must remain readable after cancellation and restart");
        }
    }

    enum CancelPath { VIEW_CHANGE, FUTURE, CLOSE }

    @Test void cancellationDiscardsQueuedResultsWithoutInterruptingRunningIo() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        var completed = new java.util.concurrent.CompletableFuture<Boolean>();
        try (var loader = new ChatHistoryLoader()) {
            CountDownLatch running = new CountDownLatch(1);
            var first = loader.submit(() -> {
                running.countDown();
                try { release.await(); completed.complete(Thread.currentThread().isInterrupted()); }
                catch (InterruptedException error) { completed.complete(true); }
                return 1;
            });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            var queued = loader.submit(() -> { fail("cancelled queued work must not run"); return 2; });
            loader.cancel();
            assertTrue(first.isCancelled());
            assertTrue(queued.isCancelled());
            release.countDown();
            assertFalse(completed.get(5, TimeUnit.SECONDS));
            assertEquals(3, loader.submit(() -> 3).get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test void queueIsBoundedAndOverflowIsAnExceptionalResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var loader = new ChatHistoryLoader()) {
            CountDownLatch running = new CountDownLatch(1);
            loader.submit(() -> { running.countDown(); release.await(); return 1; });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 4; i++) loader.submit(() -> 2);
            var excess = loader.submit(() -> 3);
            var error = assertThrows(ExecutionException.class, () -> excess.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, error.getCause());
        } finally {
            release.countDown();
        }
    }
}
