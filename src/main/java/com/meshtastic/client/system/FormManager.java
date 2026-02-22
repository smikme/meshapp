package com.meshtastic.client.system;

import com.meshtastic.client.forms.FormConnections;
import com.meshtastic.client.modal.ModalPane;

public class FormManager {

    private static MainForm mainForm;

    public static void install(RootPane root) {
        mainForm = root.getMainForm();

        DrawerManager.setDrawerPane(root.getDrawerPane());

        // Показать начальную форму
        Form initialForm = AllForms.getForm(FormConnections.class);
        showForm(initialForm);
        DrawerManager.setSelectedItemClass(FormConnections.class);
    }

    public static void showForm(Form form) {
        // Блокировать навигацию, пока открыто модальное окно
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        form.formOpen();
        mainForm.setForm(form);
        DrawerManager.setSelectedItemClass(form.getClass());
    }

    public static void showAbout() {
        ModalPane modal = ModalPane.getInstance();
        if (modal != null && modal.isVisible()) {
            return;
        }
        ModalPane.showAbout();
    }
}
