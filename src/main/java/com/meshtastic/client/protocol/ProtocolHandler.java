package com.meshtastic.client.protocol;

import org.meshtastic.proto.MeshProtos;
import com.google.protobuf.InvalidProtocolBufferException;
import com.meshtastic.client.connection.MeshtasticConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Диспетчер протокола Meshtastic. Принимает сырые protobuf-payload из
 * {@link MeshtasticConnection}, парсит {@code FromRadio} и распределяет
 * по зарегистрированным {@link FromRadioListener}-ам.
 * <p>
 * Также предоставляет метод отправки {@code ToRadio} на устройство
 * через фреймирование ({@link PacketFramer}) и транспорт.
 */
public class ProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(ProtocolHandler.class);

    private final MeshtasticConnection connection;
    private final List<FromRadioListener> listeners = new CopyOnWriteArrayList<>();

    public ProtocolHandler(MeshtasticConnection connection) {
        this.connection = connection;
        connection.setDataListener(this::handleRawPacket);
    }

    /**
     * Регистрирует слушателя входящих {@code FromRadio} сообщений.
     *
     * @param listener слушатель для регистрации
     */
    public void addListener(FromRadioListener listener) {
        listeners.add(listener);
    }

    /**
     * Удаляет ранее зарегистрированного слушателя.
     *
     * @param listener слушатель для удаления
     */
    public void removeListener(FromRadioListener listener) {
        listeners.remove(listener);
    }

    /**
     * Отправляет {@code ToRadio} сообщение на устройство.
     * Сериализует protobuf, оборачивает в фрейм и передаёт через транспорт.
     *
     * @param toRadio сообщение для отправки
     */
    public void sendToRadio(MeshProtos.ToRadio toRadio) {
        byte[] frame = PacketFramer.frame(toRadio);
        log.debug("Sending ToRadio: {} ({} bytes framed)", toRadio.getPayloadVariantCase(), frame.length);
        connection.sendBytes(frame);
    }

    private void handleRawPacket(byte[] data) {
        try {
            MeshProtos.FromRadio fromRadio = MeshProtos.FromRadio.parseFrom(data);
            dispatchFromRadio(fromRadio);
        } catch (InvalidProtocolBufferException e) {
            log.warn("Failed to parse FromRadio ({} bytes): {}", data.length, e.getMessage());
        } catch (Exception e) {
            log.error("Error processing FromRadio ({} bytes)", data.length, e);
        }
    }

    private void dispatchFromRadio(MeshProtos.FromRadio fromRadio) {
        switch (fromRadio.getPayloadVariantCase()) {
            case MY_INFO -> {
                log.info("Received MyNodeInfo: nodeNum={}", fromRadio.getMyInfo().getMyNodeNum());
                notifyListeners(l -> l.onMyNodeInfo(fromRadio.getMyInfo()));
            }
            case NODE_INFO -> {
                log.debug("Received NodeInfo: num={}", fromRadio.getNodeInfo().getNum());
                notifyListeners(l -> l.onNodeInfo(fromRadio.getNodeInfo()));
            }
            case CONFIG -> {
                log.debug("Received Config: {}", fromRadio.getConfig().getPayloadVariantCase());
                notifyListeners(l -> l.onConfig(fromRadio.getConfig()));
            }
            case MODULECONFIG -> {
                log.debug("Received ModuleConfig: {}", fromRadio.getModuleConfig().getPayloadVariantCase());
                notifyListeners(l -> l.onModuleConfig(fromRadio.getModuleConfig()));
            }
            case CHANNEL -> {
                log.debug("Received Channel: index={}", fromRadio.getChannel().getIndex());
                notifyListeners(l -> l.onChannel(fromRadio.getChannel()));
            }
            case CONFIG_COMPLETE_ID -> {
                log.info("Received config_complete_id: {}", fromRadio.getConfigCompleteId());
                notifyListeners(l -> l.onConfigComplete(fromRadio.getConfigCompleteId()));
            }
            case PACKET -> {
                MeshProtos.MeshPacket pkt = fromRadio.getPacket();
                log.debug("Received MeshPacket: from={} to={} portnum={}",
                        String.format("!%08x", pkt.getFrom()),
                        String.format("!%08x", pkt.getTo()),
                        pkt.hasDecoded() ? pkt.getDecoded().getPortnum() : "encrypted");
                notifyListeners(l -> l.onMeshPacket(pkt));
            }
            case LOG_RECORD -> {
                log.trace("Received LogRecord: {}", fromRadio.getLogRecord().getMessage());
                notifyListeners(l -> l.onLogRecord(fromRadio.getLogRecord()));
            }
            case QUEUESTATUS -> {
                var qs = fromRadio.getQueueStatus();
                log.debug("QueueStatus: res={} free={}/{} meshPacketId={}",
                        qs.getRes(), qs.getFree(), qs.getMaxlen(), qs.getMeshPacketId());
                notifyListeners(l -> l.onQueueStatus(qs));
            }
            default -> log.debug("Unhandled FromRadio variant: {}", fromRadio.getPayloadVariantCase());
        }
    }

    private void notifyListeners(java.util.function.Consumer<FromRadioListener> action) {
        for (FromRadioListener l : listeners) {
            try {
                action.accept(l);
            } catch (Exception e) {
                log.error("Error in FromRadioListener {}", l.getClass().getSimpleName(), e);
            }
        }
    }
}
