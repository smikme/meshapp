package com.meshtastic.client.system;

import javafx.scene.layout.StackPane;

/**
 * Base class for application screens.
 * <p>
 * Subclasses override lifecycle methods and are displayed inside {@link MainForm}
 * through {@link FormManager#showForm(Form)}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class Form extends StackPane {

    /** Initializes the form once after instance creation. */
    public void formInit() {
        // no-op
    }

    /** Called every time navigation switches to this form. */
    public void formOpen() {
        // no-op
    }

    /** Called when navigation leaves this form. */
    public void formClose() {
        // no-op
    }

    /** Refreshes form data after device state changes. */
    public void formRefresh() {
        // no-op
    }
}
