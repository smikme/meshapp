package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.service.DatabaseResetService;
import java.util.Objects;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates destructive reset of locally stored application data.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DatabaseResetController {

    private static final Logger log = LoggerFactory.getLogger(
        DatabaseResetController.class
    );

    private final Host host;

    /**
     * Creates a controller bound to a settings form host.
     *
     * @param host host callbacks
     */
    public DatabaseResetController(Host host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /**
     * Shows confirmation UI and starts the reset when the user confirms it.
     */
    public void requestReset() {
        ModalPane pane = ModalPane.getInstance();
        if (pane == null) {
            return;
        }
        pane.show(
            DatabaseResetConfirmationPanelFactory.create(this::performReset)
        );
    }

    private void performReset() {
        host.setResetDatabaseButtonDisabled(true);
        host.setStatus(I18n.t("settings.databaseReset.inProgress"));

        Thread resetThread = new Thread(() -> {
            try {
                DatabaseResetService.resetAllData();
                Platform.runLater(() -> {
                    host.reloadCache();
                    host.reloadConfigTree();
                    host.setStatus(
                        I18n.t("settings.databaseReset.successStatus")
                    );
                    host.setResetDatabaseButtonDisabled(false);
                    Toast.show(
                        Toast.Type.SUCCESS,
                        I18n.t("settings.databaseReset.successToast")
                    );
                });
            } catch (Exception e) {
                log.error("Database reset failed", e);
                Platform.runLater(() -> {
                    host.setResetDatabaseButtonDisabled(false);
                    host.setStatus(
                        I18n.t(
                            "settings.databaseReset.errorStatus",
                            host.errorDetail(e)
                        )
                    );
                    ModalPane.showError(
                        I18n.t("settings.databaseReset.errorTitle"),
                        e.getMessage() != null
                            ? e.getMessage()
                            : I18n.t("settings.databaseReset.errorFallback")
                    );
                });
            }
        }, "database-reset");
        resetThread.setDaemon(true);
        resetThread.start();
    }

    /**
     * Host callbacks implemented by the settings form.
     */
    public interface Host {
        void setResetDatabaseButtonDisabled(boolean disabled);
        void setStatus(String status);
        void reloadCache();
        void reloadConfigTree();
        String errorDetail(Exception error);
    }
}
