package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.modal.ModalPane;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class CrashReportPromptTest {

    @AfterEach
    void cleanupModalPane() {
        onFxThread(() -> {
            ModalPane.install(null);
            return null;
        });
    }

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void requestFocusAfterShowMovesSceneFocusOwnerToCommentArea() {
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<TextArea> commentAreaRef = new AtomicReference<>();

        onFxThread(() -> {
            ModalPane pane = new ModalPane();
            ModalPane.install(pane);

            TextArea commentArea = new TextArea();
            VBox panel = new VBox(commentArea);
            StackPane root = new StackPane(pane);
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 480, 320));
            stage.show();
            stage.requestFocus();
            stageRef.set(stage);
            commentAreaRef.set(commentArea);

            pane.show(panel, false, false);
            CrashReportPrompt.requestFocusAfterShow(panel, commentArea::requestFocus);
            return null;
        });

        awaitCondition(() -> onFxThread(() -> {
            Stage stage = stageRef.get();
            TextArea commentArea = commentAreaRef.get();
            return stage != null && commentArea != null && stage.getScene().getFocusOwner() == commentArea;
        }));

        onFxThread(() -> {
            Stage stage = stageRef.get();
            if (stage != null) {
                stage.hide();
            }
            return null;
        });
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition", e);
            }
        }
        throw new AssertionError("Timed out waiting for condition");
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
