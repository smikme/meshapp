package com.meshtastic.client.forms.settings;

import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_CONFIG_TYPE;
import static com.meshtastic.client.forms.settings.ConfigEditorConstants.RINGTONE_FIELD;
import static com.meshtastic.client.forms.settings.ConfigSavePolicy.CONFIG_SAVE_ACK_TIMEOUT_MS;

import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.MessageService;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import org.meshtastic.proto.MeshProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates loading the device ringtone and applying it to the editor tree.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class RingtoneSettingsController {

    private static final Logger log = LoggerFactory.getLogger(
        RingtoneSettingsController.class
    );

    private final Host host;
    private volatile DeviceState listenerState;
    private volatile Runnable listener;
    private volatile DeviceState requestState;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public RingtoneSettingsController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Starts observing ringtone updates from the current device state.
     *
     * @param newState current device state, or {@code null}
     */
    public void observe(DeviceState newState) {
        if (listenerState == newState) {
            return;
        }
        if (listenerState != null && listener != null) {
            listenerState.removeRingtoneListener(listener);
        }
        listenerState = null;
        listener = null;
        requestState = null;

        if (newState == null) {
            return;
        }

        DeviceState observedState = newState;
        listener = () ->
            Platform.runLater(() -> {
                requestState = null;
                applyLoadedRingtoneToEditor(observedState);
            });
        observedState.addRingtoneListener(listener);
        listenerState = observedState;
    }

    /**
     * Requests ringtone data when it is not loaded yet.
     *
     * @param state   current device state
     * @param handler current protocol handler
     */
    public void requestIfNeeded(DeviceState state, ProtocolHandler handler) {
        if (state == null || handler == null || state.isRingtoneLoaded()) {
            return;
        }
        if (requestState == state) {
            return;
        }

        requestState = state;
        MessageService.requestRingtone(handler, state)
            .orTimeout(CONFIG_SAVE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .whenComplete((error, ex) -> {
                if (requestState == state) {
                    requestState = null;
                }
                if (ex != null) {
                    log.debug(
                        "Ringtone request ACK was not observed: {}",
                        ConfigSavePolicy.rootCauseMessage(ex)
                    );
                } else if (
                    error != null && error != MeshProtos.Routing.Error.NONE
                ) {
                    log.warn("Ringtone request returned {}", error);
                }
            });
    }

    private void applyLoadedRingtoneToEditor(DeviceState sourceState) {
        if (
            sourceState == null ||
            sourceState != host.currentState() ||
            !sourceState.isRingtoneLoaded()
        ) {
            return;
        }

        TreeItem<ConfigTreeItem> root = host.currentEditorRoot();
        if (root == null) {
            return;
        }
        TreeItem<ConfigTreeItem> ringtoneSection = ConfigTreeItemSupport
            .findTopLevelSection(root, RINGTONE_CONFIG_TYPE)
            .orElse(null);
        if (
            ringtoneSection == null ||
            ConfigTreeItemSupport.hasModifiedFields(ringtoneSection)
        ) {
            return;
        }

        ringtoneSection
            .getChildren()
            .stream()
            .map(TreeItem::getValue)
            .filter(data ->
                data != null && RINGTONE_FIELD.equals(data.getFieldName())
            )
            .findFirst()
            .ifPresent(data -> {
                data.setValue(sourceState.getRingtone());
                data.resetOriginal();
                host.refreshConfigTreeView();
            });
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        DeviceState currentState();
        TreeItem<ConfigTreeItem> currentEditorRoot();
        void refreshConfigTreeView();
    }
}
