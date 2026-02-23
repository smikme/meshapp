package com.meshtastic.client.system;

import javafx.scene.layout.StackPane;

/**
 * Базовый класс для экранов (форм) приложения.
 * <p>
 * Наследники (FormChat, FormNodes, FormDashboard и др.) переопределяют
 * методы жизненного цикла. Форма отображается внутри {@link MainForm}
 * через {@link FormManager#showForm(Form)}.
 */
public class Form extends StackPane {

    public Form() {
    }

    /** Инициализация формы. Вызывается один раз при создании экземпляра. */
    public void formInit() {
    }

    /** Вызывается каждый раз при переключении на эту форму. */
    public void formOpen() {
    }

    /** Обновление данных формы. Вызывается при изменении состояния устройства. */
    public void formRefresh() {
    }
}
