package com.meshtastic.client.model;

import org.meshtastic.proto.ChannelProtos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;

/**
 * Управление каналами Meshtastic-сети.
 * <p>
 * Хранит и управляет конфигурацией каналов.
 * Потокобезопасен через synchronized блоки.
 * <p>
 * Ответственность:
 * <ul>
 *   <li>Хранение и доступ к каналам по индексу</li>
 *   <li>Добавление/обновление каналов</li>
 *   <li>Поиск доступных слотов для SECONDARY каналов</li>
 *   <li>Проверка наличия активных каналов</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChannelStore {

    /** Список каналов ( synchronizedList для потокобезопасности) */
    private final List<ChannelProtos.Channel> channels = Collections.synchronizedList(new ArrayList<>());

    /** Флаг готовности каталога каналов */
    private volatile boolean channelCatalogReady = false;

    /** Счетчик версии каталога каналов (для кэширования UI) */
    private final AtomicLong channelCatalogEpoch = new AtomicLong(0);

    /**
     * Возвращает все каналы.
     *
     * @return список каналов
     */
    public List<ChannelProtos.Channel> getChannels() {
        return channels;
    }

    /**
     * Добавляет канал. Если канал с таким индексом уже существует - заменяет его.
     *
     * @param channel канал для добавления
     */
    public void addChannel(ChannelProtos.Channel channel) {
        synchronized (channels) {
            for (int i = 0; i < channels.size(); i++) {
                ChannelProtos.Channel existing = channels.get(i);
                if (existing.getIndex() == channel.getIndex()) {
                    channels.set(i, preserveExistingPsk(existing, channel));
                    return;
                }
            }
            channels.add(channel);
        }
    }

    /**
     * Обновляет канал по индексу и оповещает о изменениях.
     *
     * @param channel канал для обновления
     */
    public void updateChannel(ChannelProtos.Channel channel) {
        synchronized (channels) {
            boolean updated = false;
            for (int i = 0; i < channels.size(); i++) {
                if (channels.get(i).getIndex() == channel.getIndex()) {
                    channels.set(i, channel);
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                channels.add(channel);
            }
        }
    }

    /**
     * Возвращает канал по индексу или {@code null}, если не найден.
     *
     * @param channelIndex индекс канала
     * @return ChannelProtos.Channel или {@code null}
     */
    public ChannelProtos.Channel getChannelByIndex(int channelIndex) {
        synchronized (channels) {
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getIndex() == channelIndex) {
                    return ch;
                }
            }
        }
        return null;
    }

    /**
     * Проверяет, существует ли активный канал с указанным индексом.
     *
     * @param channelIndex индекс канала
     * @return {@code true} если канал существует и не отключен
     */
    public boolean hasEnabledChannel(int channelIndex) {
        synchronized (channels) {
            for (ChannelProtos.Channel channel : channels) {
                if (channel.getIndex() == channelIndex
                        && channel.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Находит первый свободный слот для SECONDARY канала (индексы 1-7).
     * Возвращает -1, если все слоты заняты.
     *
     * @return индекс свободного канала или -1
     */
    public int findFirstAvailableChannelSlot() {
        synchronized (channels) {
            Set<Integer> usedIndices = new HashSet<>();
            for (ChannelProtos.Channel ch : channels) {
                if (ch.getRole() != ChannelProtos.Channel.Role.DISABLED) {
                    usedIndices.add(ch.getIndex());
                }
            }
            for (int i = 1; i <= 7; i++) {
                if (!usedIndices.contains(i)) { return i; }
            }
        }
        return -1;
    }

    /**
     * Возвращает флаг готовности каталога каналов.
     *
     * @return {@code true} если catalog ready
     */
    public boolean isChannelCatalogReady() {
        return channelCatalogReady;
    }

    /**
     * Устанавливает флаг готовности каталога каналов.
     *
     * @param channelCatalogReady новое значение
     */
    public void setChannelCatalogReady(boolean channelCatalogReady) {
        this.channelCatalogReady = channelCatalogReady;
    }

    /**
     * Возвращает версию каталога каналов (для кэширования UI).
     *
     * @return channelCatalogEpoch
     */
    public long getChannelCatalogEpoch() {
        return channelCatalogEpoch.get();
    }

    /**
     * Увеличивает epoch каталога каналов (для invalidation кэша UI).
     */
    public void incrementChannelCatalogEpoch() {
        channelCatalogEpoch.incrementAndGet();
    }

    /**
     * Очищает все каналы и сбрасывает флаги.
     */
    public void clear() {
        channels.clear();
        channelCatalogReady = false;
        channelCatalogEpoch.set(0);
    }

    private static ChannelProtos.Channel preserveExistingPsk(ChannelProtos.Channel existing,
                                                             ChannelProtos.Channel incoming) {
        if (!existing.hasSettings() || !incoming.hasSettings()) {
            return incoming;
        }
        if (incoming.getSettings().getPsk().size() != 0 || existing.getSettings().getPsk().size() == 0) {
            return incoming;
        }
        return incoming.toBuilder()
                .setSettings(incoming.getSettings().toBuilder()
                        .setPsk(existing.getSettings().getPsk()))
                .build();
    }
}
