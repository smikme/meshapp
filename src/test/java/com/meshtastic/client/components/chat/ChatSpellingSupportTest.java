package com.meshtastic.client.components.chat;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.themes.ThemeManager;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ChatSpellingSupportTest {
    @BeforeAll
    static void startJavaFx() { TestEnvironmentSupport.ensureJavaFxStarted(); }

    @Test
    void checksInBackgroundAndClearsUnderlinesWhenDraftChanges() throws Exception {
        CountDownLatch underlined = new CountDownLatch(1);
        Scene scene = fx(() -> {
            EmojiTextField field = new EmojiTextField();
            StackPane root = new StackPane(field);
            Scene created = new Scene(root, 460, 130);
            ThemeManager.applyTheme(created, false);
            field.setText("Превет 😀 hello Nachrciht");
            root.resize(460, 130);
            root.applyCss();
            root.layout();
            field.needsLayoutProperty().addListener((_, _, needsLayout) -> {
                if (needsLayout) { Platform.runLater(root::layout); }
            });
            field.getChildren().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    if (change.getAddedSubList().stream().anyMatch(node ->
                            node.getStyleClass().contains("chat-spelling-underline"))) {
                        underlined.countDown();
                    }
                }
            });
            return created;
        });
        try {
            assertTrue(underlined.await(20, TimeUnit.SECONDS),
                    "misspellings should be underlined after the background check");
            fx(() -> {
                EmojiTextField field = (EmojiTextField) ((StackPane) scene.getRoot()).getChildren().getFirst();
                field.clear();
                scene.getRoot().layout();
                assertTrue(scene.getRoot().lookupAll(".chat-spelling-underline").isEmpty());
                return null;
            });
        } finally {
            fx(() -> { ThemeManager.unregisterScene(scene); scene.setRoot(new StackPane()); return null; });
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
