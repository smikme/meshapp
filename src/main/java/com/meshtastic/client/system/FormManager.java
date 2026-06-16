package com.meshtastic.client.system;

import com.meshtastic.client.forms.FormChat;
import com.meshtastic.client.forms.FormConnections;
import com.meshtastic.client.forms.FormDashboard;
import com.meshtastic.client.forms.FormMap;
import com.meshtastic.client.forms.FormNodes;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coordinates navigation between application forms.
 * <p>
 * This static manager is initialized through {@link #install(RootPane)}.
 * Once installed, {@link #showForm(Form)} replaces the active content inside
 * {@link MainForm}. Navigation is ignored while a modal pane is open.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class FormManager {

    private FormManager() {}

    private static MainForm mainForm;
    private static Form currentForm;
    private static final Map<String, Class<? extends Form>> formByConnectionId = new HashMap<>();
    private static final Set<Class<?>> CONFIG_SAVE_BLOCKED_FORMS = Set.of(
            FormChat.class,
            FormNodes.class,
            FormMap.class,
            FormDashboard.class
    );
    private static volatile boolean configSaveNavigationBlocked;

    public static void install(RootPane root) {
        mainForm = root.getMainForm();

        DrawerManager.setDrawerPane(root.getDrawerPane());
        updateDrawerNavigationBlockState();

        // Show the initial form.
        Form initialForm = AllForms.getForm(FormConnections.class);
        showForm(initialForm);
        DrawerManager.setSelectedItemClass(FormConnections.class);
    }

    public static void showForm(Form form) {
        showForm(form, true, form != null ? form.getClass() : null);
    }

    private static void showForm(Form form, boolean rememberForConnection) {
        showForm(form, rememberForConnection, form != null ? form.getClass() : null);
    }

    public static void showDynamicForm(Form form, Object navigationKey) {
        showForm(form, false, navigationKey);
    }

    private static void showForm(Form form, boolean rememberForConnection, Object navigationKey) {
        // Do not navigate away while a modal dialog is active.
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        if (form != currentForm && isConfigSaveNavigationBlockedFor(form.getClass())) {
            Toast.show(Toast.Type.WARNING,
                    I18n.t("drawer.navigationBlocked"));
            return;
        }
        if (currentForm != null && currentForm != form) {
            currentForm.formClose();
        }
        mainForm.setForm(form);
        currentForm = form;
        form.formOpen();
        DrawerManager.setSelectedItemKey(navigationKey != null ? navigationKey : form.getClass());
        if (rememberForConnection) {
            rememberFormForSelectedConnection(form.getClass());
        }
    }

    public static Form getCurrentForm() {
        return currentForm;
    }

    /**
     * Switches the application to the requested connection and restores the form
     * that was last active for it.
     * <p>
     * Before switching, the current form is remembered for the previously
     * selected connection. If the target connection has no saved form yet, the
     * current form remains visible and becomes that connection's initial state.
     *
     * @param connectionId connection profile identifier
     */
    public static void switchToConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        rememberCurrentFormForSelectedConnection();
        ConnectionManager.getInstance().setSelectedConnectionId(connectionId);
        restoreFormForSelectedConnection();
    }

    private static void restoreFormForSelectedConnection() {
        String selectedConnectionId = ConnectionManager.getInstance().getSelectedConnectionId();
        if (selectedConnectionId == null) {
            return;
        }

        Class<? extends Form> formClass = formByConnectionId.get(selectedConnectionId);
        if (formClass == null) {
            rememberCurrentFormForSelectedConnection();
            return;
        }
        if (currentForm != null && Objects.equals(currentForm.getClass(), formClass)) {
            return;
        }
        showForm(AllForms.getForm(formClass), false);
    }

    private static void rememberCurrentFormForSelectedConnection() {
        if (currentForm != null) {
            rememberFormForSelectedConnection(currentForm.getClass());
        }
    }

    private static void rememberFormForSelectedConnection(Class<? extends Form> formClass) {
        String selectedConnectionId = ConnectionManager.getInstance().getSelectedConnectionId();
        if (selectedConnectionId != null) {
            formByConnectionId.put(selectedConnectionId, formClass);
        }
    }

    public static boolean isConfigSaveNavigationBlocked() {
        return configSaveNavigationBlocked;
    }

    public static void setConfigSaveNavigationBlocked(boolean blocked) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setConfigSaveNavigationBlocked(blocked));
            return;
        }

        if (configSaveNavigationBlocked == blocked) {
            return;
        }
        configSaveNavigationBlocked = blocked;
        updateDrawerNavigationBlockState();
    }

    private static boolean isConfigSaveNavigationBlockedFor(Class<?> formClass) {
        return configSaveNavigationBlocked && CONFIG_SAVE_BLOCKED_FORMS.contains(formClass);
    }

    private static void updateDrawerNavigationBlockState() {
        DrawerPane drawerPane = DrawerManager.getDrawerPane();
        if (drawerPane != null) {
            drawerPane.setNavigationBlockedItemClasses(
                    configSaveNavigationBlocked ? CONFIG_SAVE_BLOCKED_FORMS : Set.of());
        }
    }

    public static void showAbout() {
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        ModalPane.showAbout();
    }
}
