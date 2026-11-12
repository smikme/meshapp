package com.meshtastic.client.platform;

import com.meshtastic.client.TestEnvironmentSupport;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledOnOs(OS.MAC)
class NativeMacOsWindowControlTest {

    @Test
    void headlessWindowIdsAreNeverExposedAsCocoaPointers() throws Exception {
        assumeTrue("Headless".equalsIgnoreCase(System.getProperty("glass.platform")));
        TestEnvironmentSupport.ensureJavaFxStarted();
        FutureTask<long[]> handles = new FutureTask<>(() -> {
            Stage stage = new Stage();
            stage.show();
            try {
                var control = new NativeMacOsWindowControl(stage);
                return new long[]{control.getNativeWindowHandle(), control.getNativeViewHandle()};
            } finally {
                stage.close();
            }
        });
        Platform.runLater(handles);
        assertArrayEquals(new long[]{0, 0}, handles.get(5, TimeUnit.SECONDS));
    }
}
