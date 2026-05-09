package com.meshtastic.client.system;

import com.meshtastic.client.forms.FormConnections;
import com.meshtastic.client.modal.ModalPane;
import com.meshtastic.client.service.ConnectionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Управляет навигацией между формами приложения.
 * <p>
 * Статический менеджер. Инициализируется через {@link #install(RootPane)},
 * после чего {@link #showForm(Form)} переключает активную форму
 * внутри {@link MainForm}. Навигация блокируется при открытом модальном окне.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class FormManager {

    private FormManager() {}

    private static MainForm mainForm;
    private static Form currentForm;
    private static final Map<String, Class<? extends Form>> formByConnectionId = new HashMap<>();

    public static void install(RootPane root) {
        mainForm = root.getMainForm();

        DrawerManager.setDrawerPane(root.getDrawerPane());

        // Показать начальную форму
        Form initialForm = AllForms.getForm(FormConnections.class);
        showForm(initialForm);
        DrawerManager.setSelectedItemClass(FormConnections.class);
    }

    public static void showForm(Form form) {
        showForm(form, true);
    }

    private static void showForm(Form form, boolean rememberForConnection) {
        // Блокировать навигацию, пока открыто модальное окно
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        if (currentForm != null && currentForm != form) {
            currentForm.formClose();
        }
        mainForm.setForm(form);
        currentForm = form;
        form.formOpen();
        DrawerManager.setSelectedItemClass(form.getClass());
        if (rememberForConnection) {
            rememberFormForSelectedConnection(form.getClass());
        }
    }

    /**
     * Переключает приложение на указанное подключение и восстанавливает форму,
     * которая была открыта для него в последний раз.
     * <p>
     * Перед переключением текущая форма запоминается для ранее выбранного
     * подключения. Если для нового подключения форма ещё не запоминалась,
     * остаётся текущая форма и она становится начальным состоянием этого
     * подключения.
     *
     * @param connectionId идентификатор профиля подключения
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

    public static void showAbout() {
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        ModalPane.showAbout();
    }
}
