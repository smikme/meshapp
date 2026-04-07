package com.meshtastic.client.model;

/**
 * Узел дерева пакета с привязкой к диапазону байт в сериализованном {@code MeshPacket}.
 * Диапазон хранится как полуинтервал {@code [startByte, endByte)} и используется
 * для подсветки соответствующего фрагмента в HEX/ASCII предпросмотре.
 */
public class PacketTreeNode {

    private final String label;
    private final int startByte;
    private final int endByte;

    /**
     * Создаёт узел без диапазона байт.
     * Такой узел участвует только в визуализации текста и не должен инициировать подсветку.
     *
     * @param label подпись узла
     */
    public PacketTreeNode(String label) {
        this(label, -1, -1);
    }

    /**
     * Создаёт узел с диапазоном байт.
     *
     * @param label     подпись узла
     * @param startByte начало диапазона включительно
     * @param endByte   конец диапазона исключительно
     */
    public PacketTreeNode(String label, int startByte, int endByte) {
        this.label = label;
        this.startByte = startByte;
        this.endByte = endByte;
    }

    public String getLabel() {
        return label;
    }

    public int getStartByte() {
        return startByte;
    }

    public int getEndByte() {
        return endByte;
    }

    /**
     * @return {@code true}, если узел действительно связан с байтами пакета и может подсвечиваться
     */
    public boolean hasByteRange() {
        return startByte >= 0 && endByte > startByte;
    }

    @Override
    public String toString() {
        return label;
    }
}
