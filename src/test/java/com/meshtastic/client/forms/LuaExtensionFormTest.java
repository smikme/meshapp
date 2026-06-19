package com.meshtastic.client.forms;

import atlantafx.base.controls.SegmentedControl;
import atlantafx.base.controls.ToggleLabel;
import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.lua.LuaFormComponentSpec;
import com.meshtastic.client.lua.LuaFormEvent;
import com.meshtastic.client.lua.LuaScript;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaExtensionFormTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void segmentedControlEmitsNewSelectedValue() {
        List<LuaFormEvent> events = new CopyOnWriteArrayList<>();
        LuaExtensionForm form = onFxThread(() -> {
            LuaExtensionForm extensionForm = new LuaExtensionForm(
                    new LuaScript(42L, "extension", "", true, "", LuaScript.BotType.EXTENSION, "",
                            0L, 0L, 0L, "", ""),
                    events::add);
            new Scene(new StackPane(extensionForm));
            return extensionForm;
        });

        form.addFormComponent(new LuaFormComponentSpec(
                "period",
                "segmented_control",
                null,
                null,
                null,
                "6 ч",
                List.of("1 ч", "6 ч", "24 ч"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        SegmentedControl control = assertInstanceOf(
                SegmentedControl.class,
                components(form).get("period"));
        onFxThread(() -> {
            StackPane root = (StackPane) form.getScene().getRoot();
            root.applyCss();
            root.layout();
            return null;
        });
        onFxThread(() -> null);
        assertNotNull(control.getSkin());
        assertEquals("6 ч", form.formComponentValue("period"));

        events.clear();
        onFxThread(() -> {
            ToggleLabel target = control.getSegments().stream()
                    .filter(segment -> "24 ч".equals(segment.getText()))
                    .findFirst()
                    .orElseThrow();
            target.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_PRESSED,
                    0,
                    0,
                    0,
                    0,
                    MouseButton.PRIMARY,
                    1,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null));
            return null;
        });
        onFxThread(() -> null);

        assertTrue(events.stream().anyMatch(event ->
                        event.scriptId() == 42L
                                && "period".equals(event.componentId())
                                && "change".equals(event.type())
                                && "24 ч".equals(event.value())),
                "Segmented control did not emit the newly selected value");
        assertEquals("24 ч", form.formComponentValue("period"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Node> components(LuaExtensionForm form) {
        try {
            Field field = LuaExtensionForm.class.getDeclaredField("components");
            field.setAccessible(true);
            return (Map<String, Node>) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static <T> T onFxThread(FxCallable<T> callable) {
        if (Platform.isFxApplicationThread()) {
            return call(callable);
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(callable.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for JavaFX task");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private static <T> T call(FxCallable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface FxCallable<T> {
        T call() throws Exception;
    }
}
