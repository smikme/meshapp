package com.meshtastic.client.forms.settings;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.rpc.RpcConnectionQr;
import io.nayuki.qrcodegen.QrCode;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Window;

/**
 * Displays an RPC connection QR code and explicit clipboard export actions.
 *
 * <p>The dialog takes a snapshot of the settings supplied by its caller. In direct
 * mode the user can edit the exported host address without changing the server's
 * bind address; in cloud mode the cloud URL is fixed and the local port is hidden.
 * Invalid settings produce an inline message and disable both copy actions.
 *
 * <p>The QR image is generated locally from {@link RpcConnectionQr#encode} and
 * contains the access key. Only an explicit copy action writes the image or JSON
 * to the system clipboard. Opening the dialog does not save settings, start an RPC
 * server, or connect to a remote host.
 *
 * <p>Dialog construction and display must run on the JavaFX application thread.
 *
 * @see RpcConnectionQr
 */
public final class RpcConnectionQrDialog {
    /** Prevents instantiation of the dialog factory. */
    private RpcConnectionQrDialog() {}

    /**
     * Opens the connection-sharing dialog without waiting for it to close.
     *
     * <p>Must be called on the JavaFX application thread. Validation failures in
     * the connection settings are shown inside the dialog rather than propagated
     * to the caller.
     *
     * @param owner parent window, or {@code null} for a dialog without an owner
     * @param cloudEnabled whether Cloud External takes precedence over direct TCP
     * @param address initial editable direct host address; ignored in cloud mode
     * @param port direct TCP port as text; ignored in cloud mode
     * @param accessKey access key to include in the QR and copied connection data
     * @throws IllegalStateException if invoked outside the JavaFX application thread
     */
    public static void show(Window owner, boolean cloudEnabled, String address,
                            String port, String accessKey) {
        create(owner, cloudEnabled, address, port, accessKey).show();
    }

    /**
     * Constructs the sharing dialog and validates its initial settings without showing it.
     *
     * <p>Edits to the direct address regenerate the QR immediately. If validation
     * fails, the previous QR is removed and clipboard actions are disabled, so stale
     * connection data cannot be copied. The port and key remain the supplied snapshot.
     *
     * @param owner parent window, or {@code null} to leave the dialog unowned
     * @param cloudEnabled whether to export the cloud URL and omit the direct port
     * @param address initial direct host address; ignored in cloud mode
     * @param port direct TCP port as text; ignored in cloud mode
     * @param accessKey shared access key; invalid or missing keys are reported in the dialog
     * @return initialized dialog with QR preview, validation feedback, and copy actions
     * @throws IllegalStateException if invoked outside the JavaFX application thread
     */
    static Dialog<Void> create(Window owner, boolean cloudEnabled, String address,
                               String port, String accessKey) {
        Dialog<Void> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(I18n.t("settings.remoteRpc.qr.title"));

        Label mode = new Label(I18n.t(cloudEnabled
                ? "connection.remoteRpcMode.router" : "connection.remoteRpcMode.direct"));
        TextField endpoint = new TextField(cloudEnabled
                ? ConnectionEntry.CLOUD_RPC_ROUTER_SERVER : address);
        endpoint.setId("rpc-qr-address");
        endpoint.setEditable(!cloudEnabled);
        endpoint.setPromptText(I18n.t("settings.remoteRpc.qr.addressPrompt"));
        Label addressHint = new Label(I18n.t("settings.remoteRpc.qr.directHint"));
        addressHint.setWrapText(true);
        addressHint.setVisible(!cloudEnabled);
        addressHint.setManaged(!cloudEnabled);
        Label portLabel = new Label(I18n.t("settings.remoteRpc.port") + ": " + port);
        portLabel.setId("rpc-qr-port");
        portLabel.setVisible(!cloudEnabled);
        portLabel.setManaged(!cloudEnabled);

        VBox qrHolder = new VBox();
        qrHolder.setId("rpc-qr-image");
        qrHolder.setAlignment(Pos.CENTER);
        Label error = new Label(I18n.t("settings.remoteRpc.qr.invalid"));
        error.setId("rpc-qr-error");
        error.setWrapText(true);
        Label note = new Label(I18n.t("settings.remoteRpc.qr.note"));
        note.getStyleClass().add("muted-note-label");
        note.setWrapText(true);

        VBox content = new VBox(12, mode, endpoint, portLabel, addressHint, qrHolder, error, note);
        content.setPrefWidth(440);
        dialog.getDialogPane().setContent(content);
        ButtonType copyQr = new ButtonType(I18n.t("settings.remoteRpc.qr.copyImage"),
                ButtonBar.ButtonData.LEFT);
        ButtonType copyData = new ButtonType(I18n.t("settings.remoteRpc.qr.copyData"),
                ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(copyQr, copyData,
                new ButtonType(I18n.t("common.close"), ButtonBar.ButtonData.CANCEL_CLOSE));
        Button copyQrButton = (Button) dialog.getDialogPane().lookupButton(copyQr);
        Button copyDataButton = (Button) dialog.getDialogPane().lookupButton(copyData);
        String[] payload = {null};
        Canvas[] canvas = {null};
        Runnable update = () -> {
            payload[0] = null;
            canvas[0] = null;
            qrHolder.getChildren().clear();
            try {
                String encoded = RpcConnectionQr.encode(cloudEnabled, endpoint.getText(), port, accessKey);
                canvas[0] = render(encoded);
                qrHolder.getChildren().add(canvas[0]);
                payload[0] = encoded;
            } catch (IllegalArgumentException ignored) {
                // Do not log connection data or include it in validation messages.
            }
            boolean invalid = payload[0] == null;
            error.setVisible(invalid);
            error.setManaged(invalid);
            copyQrButton.setDisable(invalid);
            copyDataButton.setDisable(invalid);
            if (dialog.isShowing()) dialog.getDialogPane().getScene().getWindow().sizeToScene();
        };
        endpoint.textProperty().addListener((_, _, _) -> update.run());
        update.run();
        copyQrButton.addEventFilter(ActionEvent.ACTION, event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putImage(canvas[0].snapshot(null, null));
            Clipboard.getSystemClipboard().setContent(clipboard);
            event.consume();
        });
        copyDataButton.addEventFilter(ActionEvent.ACTION, event -> {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(payload[0]);
            Clipboard.getSystemClipboard().setContent(clipboard);
            event.consume();
        });
        return dialog;
    }

    /**
     * Renders a payload as a black-and-white QR image with at least medium error correction.
     *
     * <p>Integer module sizes keep the edges sharp. A four-module white quiet zone
     * surrounds the code independently of the application's light or dark theme.
     *
     * @param payload connection JSON containing the access key
     * @return canvas with the complete QR image, including its quiet zone
     * @throws IllegalArgumentException if the payload exceeds the QR encoder's capacity
     */
    private static Canvas render(String payload) {
        QrCode qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM);
        // Integer module sizes and a four-module white quiet zone remain scan-friendly
        // even when the surrounding application uses a dark theme.
        int scale = Math.max(3, 340 / (qr.size + 8));
        int size = (qr.size + 8) * scale;
        Canvas canvas = new Canvas(size, size);
        var graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.WHITE);
        graphics.fillRect(0, 0, size, size);
        graphics.setFill(Color.BLACK);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    graphics.fillRect((x + 4) * scale, (y + 4) * scale, scale, scale);
                }
            }
        }
        return canvas;
    }
}
