package com.meshtastic.client.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Управление базой данных узлов Meshtastic-сети.
 * <p>
 * Хранит и управляет всеми известными узлами (NodeData).
 * Потокобезопасен через {@link ConcurrentHashMap}.
 * <p>
 * Ответственность:
 * <ul>
 *   <li>Хранение и доступ к узлам по номеру (nodeNum)</li>
 *   <li>Поиск узлов по nodeId</li>
 *   <li>Удаление узлов с оповещением listener'ов</li>
 *   <li>Подсчет количества узлов</li>
 * </ul>
 */
public class NodeDatabase {

    private static final Logger log = LoggerFactory.getLogger(NodeDatabase.class);

    /** Мапа номер узла -> данные узла */
    private final ConcurrentHashMap<Integer, NodeData> nodeDb = new ConcurrentHashMap<>();

    /** Слушатели обновлений узлов */
    private final CopyOnWriteArrayList<java.util.function.IntConsumer> nodeUpdateListeners = new CopyOnWriteArrayList<>();

    /**
     * Возвращает узел по номеру или {@code null}, если не найден.
     *
     * @param nodeNum номер узла
     * @return NodeData или {@code null}
     */
    public NodeData getNode(int nodeNum) {
        return nodeDb.get(nodeNum);
    }

    /**
     * Возвращает узел из базы или создаёт новую атомарно.
     * Использует {@link java.util.concurrent.ConcurrentHashMap#computeIfAbsent},
     * гарантируя что для одного {@code nodeNum} создаётся ровно один объект.
     *
     * @param nodeNum номер узла
     * @return существующая или новая {@link NodeData}
     */
    public NodeData getOrCreateNode(int nodeNum) {
        return nodeDb.computeIfAbsent(nodeNum, NodeData::new);
    }

    /**
     * Удаляет узел из базы и оповещает listener'ов.
     *
     * @param nodeNum номер узла
     */
    public void removeNode(int nodeNum) {
        NodeData removed = nodeDb.remove(nodeNum);
        if (removed != null) {
            fireNodeUpdateListeners(nodeNum);
        }
    }

    /**
     * Возвращает узел по nodeId (перебор nodeDb.values()).
     *
     * @param nodeId nodeId узла в формате !XXXXXXXX
     * @return NodeData или {@code null} если не найден
     */
    public NodeData getNodeByNodeId(String nodeId) {
        if (nodeId == null) { return null; }
        for (NodeData n : nodeDb.values()) {
            if (nodeId.equals(n.getNodeId())) { return n; }
        }
        return null;
    }

    /**
     * Добавляет listener для уведомлений об изменении узлов.
     *
     * @param listener функция, принимающая номер измененного узла
     */
    public void addNodeUpdateListener(java.util.function.IntConsumer listener) {
        nodeUpdateListeners.add(listener);
    }

    /**
     * Удаляет listener для уведомлений об изменении узлов.
     *
     * @param listener ранее добавленный listener
     */
    public void removeNodeUpdateListener(java.util.function.IntConsumer listener) {
        nodeUpdateListeners.remove(listener);
    }

    /**
     * Оповещает всех listener'ов об изменении узла.
     *
     * @param nodeNum номер измененного узла
     */
    public void fireNodeUpdateListeners(int nodeNum) {
        for (java.util.function.IntConsumer l : nodeUpdateListeners) {
            try { l.accept(nodeNum); }
            catch (Exception e) { log.error("Exception in node update listener for !{}", Integer.toHexString(nodeNum), e); }
        }
    }

    /**
     * Возвращает все узлы в виде списка (для UI и сериализации).
     *
     * @return список узлов
     */
    public List<NodeData> getAllNodes() {
        return new ArrayList<>(nodeDb.values());
    }

    /**
     * Возвращает количество узлов в базе.
     *
     * @return количество узлов
     */
    public int getNodeCount() {
        return nodeDb.size();
    }

    /**
     * Очищает всю базу узлов.
     */
    public void clear() {
        nodeDb.clear();
    }

    /**
     * Возвращает внутренний map узлов (для backward compatibility).
     *
     * @return {@code ConcurrentHashMap<Integer, NodeData>}
     */
    public ConcurrentHashMap<Integer, NodeData> getNodeDb() {
        return nodeDb;
    }
}
