package com.meshtastic.client.protocol.meshcore;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

class MeshCoreCommandQueueTest {
    final MeshCoreTestClock clock = new MeshCoreTestClock();
    final List<Integer> writes = new ArrayList<>();
    final List<Throwable> fatal = new ArrayList<>();
    final MeshCoreCommandQueue queue =
            new MeshCoreCommandQueue(
                    clock,
                    b -> {
                        writes.add(b[0] & 255);
                        return CompletableFuture.completedFuture(null);
                    },
                    fatal::add,
                    new Object());

    @Test
    void progressResetsInactivityAndPushDoesNotConsumeRequest() {
        var list = queue.submit(new byte[] {4}, 4, 100, p -> {}, 2, 3);
        queue.accept(new byte[] {2, 2, 0, 0, 0});
        clock.advance(90);
        queue.accept(new byte[] {3});
        clock.advance(90);
        assertFalse(list.isDone());
        assertFalse(queue.accept(new byte[] {(byte) 0x88, 0, 0}));
        queue.accept(new byte[] {4, 1, 0, 0, 0});
        assertTrue(list.isDone());
        assertTrue(fatal.isEmpty());
        assertEquals(0, clock.pending());
    }

    @Test
    void timeoutAbortsAmbiguousSessionAndAllQueuedWork() {
        var first = queue.submit(new byte[] {3}, 0, 100, p -> {});
        var second = queue.submit(new byte[] {32}, 0, 100, p -> {});
        clock.advance(100);
        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(List.of(3), writes);
        assertEquals(1, fatal.size());
        assertFalse(queue.accept(new byte[] {0}));
    }

    @Test
    void explicitErrorOnlyFailsCurrentOperation() {
        var a = queue.submit(new byte[] {66}, 29, 100, p -> {});
        var b = queue.submit(new byte[] {5}, 9, 100, p -> {});
        queue.accept(new byte[] {1, 1});
        assertTrue(a.isCompletedExceptionally());
        assertFalse(b.isDone());
        queue.accept(new byte[] {9, 1, 0, 0, 0});
        assertTrue(b.isDone());
        assertTrue(fatal.isEmpty());
    }

    @Test
    void queuedCancellationAndCapacityNeverDropAcceptedCommands() {
        queue.submit(new byte[] {3}, 0, 100, p -> {});
        var cancelled = queue.submit(new byte[] {32}, 0, 100, p -> {});
        cancelled.cancel(false);
        for (int i = 0; i < MeshCoreCommandQueue.CAPACITY; i++)
            assertFalse(queue.submit(new byte[] {5}, 9, 100, p -> {}).isCompletedExceptionally());
        assertTrue(queue.submit(new byte[] {7}, 0, 100, p -> {}).isCompletedExceptionally());
        queue.accept(new byte[] {0});
        assertEquals(List.of(3, 5), writes);
        for (int i = 0; i < MeshCoreCommandQueue.CAPACITY; i++) queue.accept(new byte[] {9});
        assertEquals(129, writes.size());
        assertEquals(0, clock.pending());
    }

    @Test
    void writeFailureAndActiveCancellationFailTheSession() {
        var failed =
                new MeshCoreCommandQueue(
                        clock,
                        b ->
                                CompletableFuture.failedFuture(
                                        new IllegalStateException("write failed")),
                        fatal::add,
                        new Object());
        assertTrue(failed.submit(new byte[] {1}, 5, 100, p -> {}).isCompletedExceptionally());
        assertEquals(1, fatal.size());
        queue.submit(new byte[] {3}, 0, 100, p -> {}).cancel(false);
        assertEquals(2, fatal.size());
        assertEquals(0, clock.pending());
    }

    @Test
    void malformedResponseCannotSatisfyHandshake() {
        var response =
                queue.submit(
                        new byte[] {1},
                        5,
                        100,
                        p -> MeshCoreCompanionProtocolRuntime.requireLength(p, 58));
        assertFalse(queue.accept(new byte[] {5}));
        assertFalse(response.isDone());
        byte[] valid = new byte[58];
        valid[0] = 5;
        assertTrue(queue.accept(valid));
        assertTrue(response.isDone());
    }

    @Test
    void rebootCompletesOnWriteWithoutWaitingForAnImpossibleAck() {
        assertTrue(queue.submit(new byte[] {19}, -2, 100, p -> {}).isDone());
        assertEquals(0, clock.pending());
    }
}
