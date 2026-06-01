package com.meshtastic.client.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.Optional;

/**
 * Runs full-text message search away from the JavaFX Application Thread.
 *
 * <p>The service tracks a monotonically increasing request generation, allowing
 * the UI to discard stale results when the query, sender filter, or selected
 * chat has already changed. Database work stays synchronous and UI-agnostic;
 * callers provide callbacks to publish results back to the FX layer.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChatMessageSearchService implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chat-message-search");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong generation = new AtomicLong();
    private volatile Future<?> currentTask;

    /**
     * Direction used when moving between search matches.
     */
    public enum Direction {
        PREVIOUS,
        NEXT
    }

    /**
     * Immutable input for the initial message search.
     *
     * @param generation request generation used to discard stale UI responses
     * @param chatType message database chat type
     * @param chatKey message database chat key
     * @param query search text
     * @param ownerNodeId node id of the active connection owner
     * @param fromNodeId optional sender filter
     * @param previousHighlightedDbId previously highlighted message, used to preserve position
     * @param previousResultIndex previously known match index
     * @param jumpToLatest whether the search should jump to the newest match
     */
    public record SearchRequest(
            long generation,
            String chatType,
            String chatKey,
            String query,
            String ownerNodeId,
            String fromNodeId,
            long previousHighlightedDbId,
            int previousResultIndex,
            boolean jumpToLatest) {}

    /**
     * Result of the initial message search.
     *
     * @param request original request
     * @param highlightedDbId database id of the message that should be highlighted
     * @param resultCount number of matches, respecting the count limit
     * @param resultIndex index of the highlighted match
     * @param resultCountLimited whether counting stopped at the configured limit
     * @param hasPrevious whether an older match exists
     * @param hasNext whether a newer match exists
     */
    public record SearchResult(
            SearchRequest request,
            long highlightedDbId,
            int resultCount,
            int resultIndex,
            boolean resultCountLimited,
            boolean hasPrevious,
            boolean hasNext) {}

    /**
     * Immutable input for navigating within an existing search result set.
     *
     * @param generation request generation used to discard stale UI responses
     * @param chatType message database chat type
     * @param chatKey message database chat key
     * @param query search text
     * @param ownerNodeId node id of the active connection owner
     * @param fromNodeId optional sender filter
     * @param currentHighlightedDbId currently highlighted message
     * @param currentResultIndex current match index
     * @param resultCount known match count
     * @param resultCountLimited whether counting stopped at the configured limit
     * @param direction navigation direction
     */
    public record NavigationRequest(
            long generation,
            String chatType,
            String chatKey,
            String query,
            String ownerNodeId,
            String fromNodeId,
            long currentHighlightedDbId,
            int currentResultIndex,
            int resultCount,
            boolean resultCountLimited,
            Direction direction) {}

    /**
     * Result of moving to an adjacent search match.
     *
     * @param request original navigation request
     * @param highlightedDbId database id of the newly highlighted message
     * @param resultIndex new match index
     * @param hasPrevious whether an older match exists
     * @param hasNext whether a newer match exists
     */
    public record NavigationResult(
            NavigationRequest request,
            long highlightedDbId,
            int resultIndex,
            boolean hasPrevious,
            boolean hasNext) {}

    /**
     * Normalized search scope shared by initial search and navigation.
     */
    private record SearchScope(
            String chatType,
            String chatKey,
            String query,
            String ownerNodeId,
            String fromNodeId) {}

    /**
     * Current match position together with the associated match count.
     */
    private record SearchPosition(
            long highlightedDbId,
            int resultCount,
            int resultIndex,
            boolean resultCountLimited) {

        private static final SearchPosition EMPTY = new SearchPosition(0, 0, -1, false);
    }

    /**
     * Availability of neighboring matches around the current position.
     */
    private record NavigationAvailability(boolean hasPrevious, boolean hasNext) {

        private static final NavigationAvailability NONE = new NavigationAvailability(false, false);
    }

    /**
     * Starts a new generation and cancels any unfinished search task.
     */
    public long beginWork() {
        cancelCurrentTask();
        return generation.incrementAndGet();
    }

    /**
     * Invalidates pending responses without necessarily starting new work.
     */
    public void invalidate() {
        cancelCurrentTask();
        generation.incrementAndGet();
    }

    /**
     * Returns whether a response still belongs to the latest request generation.
     */
    public boolean isCurrent(long candidateGeneration) {
        return candidateGeneration == generation.get();
    }

    /**
     * Submits the initial search to the single-thread executor.
     */
    public void submitSearch(SearchRequest request,
                             Consumer<SearchResult> onSuccess,
                             BiConsumer<Long, Exception> onFailure) {
        submit(request.generation(), () -> computeSearchResult(request), onSuccess, onFailure);
    }

    /**
     * Submits adjacent-match navigation to the single-thread executor.
     */
    public void submitNavigation(NavigationRequest request,
                                 Consumer<NavigationResult> onSuccess,
                                 BiConsumer<Long, Exception> onFailure) {
        submit(request.generation(), () -> computeNavigationResult(request), onSuccess, onFailure);
    }

    private <T> void submit(long requestGeneration, Supplier<T> task, Consumer<T> onSuccess,
                            BiConsumer<Long, Exception> onFailure) {
        currentTask = executor.submit(() -> {
            try {
                onSuccess.accept(task.get());
            } catch (Exception e) {
                if (isCurrent(requestGeneration)) {
                    onFailure.accept(requestGeneration, e);
                }
            }
        });
    }

    private SearchResult computeSearchResult(SearchRequest request) {
        MessageDbService db = MessageDbService.getInstance();
        SearchPosition position = resolveSearchPosition(db, request);
        NavigationAvailability availability = navigationAvailability(db, scope(request), position.highlightedDbId());

        return new SearchResult(
                request,
                position.highlightedDbId(),
                position.resultCount(),
                position.resultIndex(),
                position.resultCountLimited(),
                availability.hasPrevious(),
                availability.hasNext());
    }

    private NavigationResult computeNavigationResult(NavigationRequest request) {
        MessageDbService db = MessageDbService.getInstance();
        long dbId = findNavigationMatch(db, request);
        int resultIndex = dbId <= 0 ? request.currentResultIndex() : navigationResultIndex(request);
        NavigationAvailability availability = navigationAvailability(db, scope(request), dbId);
        return new NavigationResult(request, dbId, resultIndex, availability.hasPrevious(), availability.hasNext());
    }

    private SearchPosition resolveSearchPosition(MessageDbService db, SearchRequest request) {
        if (canReusePreviousMatch(db, request)) {
            return previousSearchPosition(db, request);
        }

        long highlightedDbId = db.findLatestMessageSearchMatch(
                request.chatType(),
                request.chatKey(),
                request.query(),
                request.ownerNodeId(),
                request.fromNodeId());
        return highlightedDbId <= 0 ? SearchPosition.EMPTY : latestSearchPosition(db, request, highlightedDbId);
    }

    private boolean canReusePreviousMatch(MessageDbService db, SearchRequest request) {
        return !request.jumpToLatest()
                && request.previousHighlightedDbId() > 0
                && db.messageMatchesSearch(
                        request.chatType(),
                        request.chatKey(),
                        request.query(),
                        request.ownerNodeId(),
                        request.fromNodeId(),
                        request.previousHighlightedDbId());
    }

    private SearchPosition previousSearchPosition(MessageDbService db, SearchRequest request) {
        MessageDbService.MessageSearchCount count = countMatches(db, request);
        int resultCount = Math.max(count.count(), 1);
        int resultIndex = count.limited()
                ? 0
                : Math.min(Math.max(request.previousResultIndex(), 0), Math.max(0, resultCount - 1));
        return new SearchPosition(request.previousHighlightedDbId(), resultCount, resultIndex, count.limited());
    }

    private SearchPosition latestSearchPosition(MessageDbService db, SearchRequest request, long highlightedDbId) {
        MessageDbService.MessageSearchCount count = countMatches(db, request);
        int resultCount = Math.max(count.count(), 1);
        int resultIndex = count.limited() ? 0 : Math.max(0, resultCount - 1);
        return new SearchPosition(highlightedDbId, resultCount, resultIndex, count.limited());
    }

    private MessageDbService.MessageSearchCount countMatches(MessageDbService db, SearchRequest request) {
        return db.countMessageSearchMatchesLimited(
                request.chatType(),
                request.chatKey(),
                request.query(),
                request.ownerNodeId(),
                request.fromNodeId());
    }

    private long findNavigationMatch(MessageDbService db, NavigationRequest request) {
        return switch (request.direction()) {
            case PREVIOUS -> db.findPreviousMessageSearchMatch(
                    request.chatType(),
                    request.chatKey(),
                    request.query(),
                    request.ownerNodeId(),
                    request.fromNodeId(),
                    request.currentHighlightedDbId());
            case NEXT -> db.findNextMessageSearchMatch(
                    request.chatType(),
                    request.chatKey(),
                    request.query(),
                    request.ownerNodeId(),
                    request.fromNodeId(),
                    request.currentHighlightedDbId());
        };
    }

    private int navigationResultIndex(NavigationRequest request) {
        if (request.resultCountLimited()) {
            return request.currentResultIndex();
        }
        return switch (request.direction()) {
            case PREVIOUS -> Math.max(0, request.currentResultIndex() - 1);
            case NEXT -> Math.min(request.resultCount() - 1, request.currentResultIndex() + 1);
        };
    }

    private SearchScope scope(SearchRequest request) {
        return new SearchScope(
                request.chatType(),
                request.chatKey(),
                request.query(),
                request.ownerNodeId(),
                request.fromNodeId());
    }

    private SearchScope scope(NavigationRequest request) {
        return new SearchScope(
                request.chatType(),
                request.chatKey(),
                request.query(),
                request.ownerNodeId(),
                request.fromNodeId());
    }

    private NavigationAvailability navigationAvailability(MessageDbService db, SearchScope scope, long dbId) {
        return dbId <= 0
                ? NavigationAvailability.NONE
                : new NavigationAvailability(
                        hasPreviousMatch(db, scope, dbId),
                        hasNextMatch(db, scope, dbId));
    }

    private boolean hasPreviousMatch(MessageDbService db, SearchScope scope, long dbId) {
        return db.findPreviousMessageSearchMatch(
                scope.chatType(),
                scope.chatKey(),
                scope.query(),
                scope.ownerNodeId(),
                scope.fromNodeId(),
                dbId) > 0;
    }

    private boolean hasNextMatch(MessageDbService db, SearchScope scope, long dbId) {
        return db.findNextMessageSearchMatch(
                scope.chatType(),
                scope.chatKey(),
                scope.query(),
                scope.ownerNodeId(),
                scope.fromNodeId(),
                dbId) > 0;
    }

    private void cancelCurrentTask() {
        Optional.ofNullable(currentTask)
                .filter(task -> !task.isDone())
                .ifPresent(task -> task.cancel(true));
    }

    /**
     * Stops the active search and shuts down the service executor.
     */
    @Override
    public void close() {
        cancelCurrentTask();
        executor.shutdownNow();
    }
}
