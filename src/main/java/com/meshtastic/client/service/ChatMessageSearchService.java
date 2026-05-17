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
 * Выполняет полнотекстовый поиск сообщений вне JavaFX Application Thread.
 *
 * <p>Сервис держит поколение запросов: результаты старых задач можно безопасно
 * отбросить, если пользователь уже изменил запрос, фильтр или выбранный чат.
 * Вся логика здесь синхронная с точки зрения БД и не зависит от JavaFX; слой UI
 * только передаёт callback-и для публикации результата обратно на FX-поток.
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
     * Направление перехода между найденными сообщениями.
     */
    public enum Direction {
        PREVIOUS,
        NEXT
    }

    /**
     * Снимок параметров первичного поиска сообщений.
     *
     * @param generation поколение запроса, по которому UI отбрасывает старые ответы
     * @param chatType тип чата в БД сообщений
     * @param chatKey ключ чата в БД сообщений
     * @param query поисковая строка
     * @param ownerNodeId nodeId владельца текущего подключения
     * @param fromNodeId необязательный фильтр по отправителю
     * @param previousHighlightedDbId ранее подсвеченное сообщение для сохранения позиции
     * @param previousResultIndex ранее известный индекс результата
     * @param jumpToLatest нужно ли перейти к последнему совпадению
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
     * Результат первичного поиска.
     *
     * @param request исходный запрос
     * @param highlightedDbId id сообщения, которое нужно подсветить
     * @param resultCount количество найденных совпадений с учётом лимита подсчёта
     * @param resultIndex индекс подсвеченного совпадения
     * @param resultCountLimited был ли подсчёт остановлен на лимите
     * @param hasPrevious есть ли совпадение старше текущего
     * @param hasNext есть ли совпадение новее текущего
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
     * Снимок параметров навигации по уже найденному запросу.
     *
     * @param generation поколение запроса, по которому UI отбрасывает старые ответы
     * @param chatType тип чата в БД сообщений
     * @param chatKey ключ чата в БД сообщений
     * @param query поисковая строка
     * @param ownerNodeId nodeId владельца текущего подключения
     * @param fromNodeId необязательный фильтр по отправителю
     * @param currentHighlightedDbId текущее подсвеченное сообщение
     * @param currentResultIndex текущий индекс совпадения
     * @param resultCount известное количество совпадений
     * @param resultCountLimited был ли подсчёт остановлен на лимите
     * @param direction направление перехода
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
     * Результат перехода к соседнему совпадению.
     *
     * @param request исходный запрос навигации
     * @param highlightedDbId id нового подсвеченного сообщения
     * @param resultIndex новый индекс совпадения
     * @param hasPrevious есть ли совпадение старше текущего
     * @param hasNext есть ли совпадение новее текущего
     */
    public record NavigationResult(
            NavigationRequest request,
            long highlightedDbId,
            int resultIndex,
            boolean hasPrevious,
            boolean hasNext) {}

    /**
     * Нормализованная область поиска, общая для первичного запроса и навигации.
     */
    private record SearchScope(
            String chatType,
            String chatKey,
            String query,
            String ownerNodeId,
            String fromNodeId) {}

    /**
     * Позиция текущего совпадения и связанный с ней счётчик результатов.
     */
    private record SearchPosition(
            long highlightedDbId,
            int resultCount,
            int resultIndex,
            boolean resultCountLimited) {

        private static final SearchPosition EMPTY = new SearchPosition(0, 0, -1, false);
    }

    /**
     * Наличие соседних совпадений относительно текущей позиции.
     */
    private record NavigationAvailability(boolean hasPrevious, boolean hasNext) {

        private static final NavigationAvailability NONE = new NavigationAvailability(false, false);
    }

    /**
     * Начинает новую работу и отменяет ещё не завершившийся поисковый запрос.
     */
    public long beginWork() {
        cancelCurrentTask();
        return generation.incrementAndGet();
    }

    /**
     * Инвалидирует текущие ответы без обязательного запуска новой работы.
     */
    public void invalidate() {
        cancelCurrentTask();
        generation.incrementAndGet();
    }

    /**
     * Проверяет, относится ли ответ к последнему известному поколению запроса.
     */
    public boolean isCurrent(long candidateGeneration) {
        return candidateGeneration == generation.get();
    }

    /**
     * Отправляет первичный поиск в однониточный executor.
     */
    public void submitSearch(SearchRequest request,
                             Consumer<SearchResult> onSuccess,
                             BiConsumer<Long, Exception> onFailure) {
        submit(request.generation(), () -> computeSearchResult(request), onSuccess, onFailure);
    }

    /**
     * Отправляет переход к соседнему совпадению в однониточный executor.
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
     * Останавливает текущий поиск и завершает executor сервиса.
     */
    @Override
    public void close() {
        cancelCurrentTask();
        executor.shutdownNow();
    }
}
