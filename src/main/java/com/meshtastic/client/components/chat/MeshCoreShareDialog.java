package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;

import io.nayuki.qrcodegen.QrCode;

import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/** Sharing is explicit: the link/QR can contain a channel secret and is rendered locally. */
public final class MeshCoreShareDialog {
    private MeshCoreShareDialog() {}

    public static void show(String link) {
        QrCode qr = QrCode.encodeText(link, QrCode.Ecc.MEDIUM);
        int scale = Math.max(3, 300 / (qr.size + 8));
        int size = (qr.size + 8) * scale;
        Canvas canvas = new Canvas(size, size);
        var gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, size, size);
        gc.setFill(Color.BLACK);
        for (int y = 0; y < qr.size; y++)
            for (int x = 0; x < qr.size; x++)
                if (qr.getModule(x, y)) gc.fillRect((x + 4) * scale, (y + 4) * scale, scale, scale);
        TextArea text = new TextArea(link);
        text.setWrapText(true);
        text.setEditable(false);
        text.setPrefRowCount(4);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.t("meshcore.shareLink"));
        dialog.getDialogPane().setContent(new VBox(10, canvas, text));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }
}
