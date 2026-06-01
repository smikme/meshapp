package com.meshtastic.client.components;

import atlantafx.base.controls.Calendar;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.SvgIconLoader;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Popup;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Комбинированный picker даты и времени для фильтров.
 * Контракт:
 * - визуально ведёт себя как единый control на базе {@link DatePicker};
 * - popup содержит календарь и нижнюю панель выбора времени через слайдеры;
 * - шаг часов и минут всегда равен {@code 1};
 * - изменение значения коммитится только по кнопке {@code Применить};
 * - отсутствие времени трактуется как режим {@code Весь день}.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class DateTimePicker extends HBox {

    private static final double DATE_WIDTH = 138;
    private static final double SLIDER_WIDTH = 220;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final DatePicker displayPicker;
    private final Label timeSummaryLabel;
    private final Button clearButton;
    private final Popup popup;
    private final VBox popupRoot;
    private final Calendar calendar;
    private final CheckBox allDayCheckBox;
    private final Slider hourSlider;
    private final Slider minuteSlider;
    private final Label hourValueLabel;
    private final Label minuteValueLabel;
    private final Runnable onValueChanged;
    private final ReadOnlyBooleanWrapper popupShowing = new ReadOnlyBooleanWrapper(this, "popupShowing", false);

    private LocalDate dateValue;
    private LocalTime timeValue;
    private LocalDate draftDate;
    private LocalTime draftTime;
    private boolean draftAllDay;
    private boolean adjusting;

    /**
     * @param promptText     placeholder для даты
     * @param onValueChanged callback после подтверждённого изменения значения
     */
    public DateTimePicker(String promptText, Runnable onValueChanged) {
        this.onValueChanged = onValueChanged;

        displayPicker = createDisplayDatePicker(promptText);

        timeSummaryLabel = new Label();
        timeSummaryLabel.getStyleClass().add("packet-monitor-date-time-summary");

        clearButton = new Button();
        clearButton.getStyleClass().add("packet-monitor-date-time-clear");
        clearButton.setFocusTraversable(false);
        clearButton.setTooltip(new Tooltip(I18n.t("packetMonitor.date.clear")));

        SVGPath clearIcon = SvgIconLoader.load("/icons/clear.svg", 12);
        if (clearIcon != null) {
            clearButton.setGraphic(clearIcon);
            clearButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        } else {
            clearButton.setText("x");
            clearButton.setContentDisplay(ContentDisplay.TEXT_ONLY);
        }
        clearButton.setOnAction(event -> clear());

        setAlignment(Pos.CENTER_LEFT);
        setSpacing(4);
        getStyleClass().add("packet-monitor-date-time-box");
        getChildren().addAll(displayPicker, timeSummaryLabel, clearButton);

        popup = new Popup();
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(true);
        popup.showingProperty().addListener((obs, oldValue, showing) -> popupShowing.set(showing));

        calendar = new Calendar();
        calendar.setShowWeekNumbers(false);
        calendar.valueProperty().addListener((obs, oldValue, newValue) -> draftDate = newValue);

        allDayCheckBox = new CheckBox(I18n.t("packetMonitor.date.allDay"));
        allDayCheckBox.getStyleClass().add("packet-monitor-date-time-popup-all-day");
        allDayCheckBox.selectedProperty().addListener((obs, oldValue, newValue) -> {
            draftAllDay = Boolean.TRUE.equals(newValue);
            updateDraftControlsState();
        });

        hourSlider = createSlider(23);
        minuteSlider = createSlider(59);
        hourValueLabel = createSliderValueLabel();
        minuteValueLabel = createSliderValueLabel();
        hourSlider.valueProperty().addListener((obs, oldValue, newValue) -> updateDraftTimeFromSliders());
        minuteSlider.valueProperty().addListener((obs, oldValue, newValue) -> updateDraftTimeFromSliders());

        calendar.setBottomNode(createPopupBottom());

        popupRoot = new VBox(calendar);
        popupRoot.getStyleClass().add("packet-monitor-date-time-popup");
        popup.getContent().add(popupRoot);

        installOpenHandlers();
        updateCommittedDisplay();
    }

    /**
     * @return выбранная дата или {@code null}, если фильтр даты выключен
     */
    public LocalDate getDate() {
        return dateValue;
    }

    /**
     * @return выбранное время или {@code null}, если активен режим {@code Весь день}
     */
    public LocalTime getTime() {
        return timeValue;
    }

    /**
     * @return read-only флаг видимости popup-календаря
     */
    public ReadOnlyBooleanProperty popupShowingProperty() {
        return popupShowing.getReadOnlyProperty();
    }

    /**
     * @return {@code true}, если popup-календарь сейчас открыт
     */
    public boolean isPopupShowing() {
        return popupShowing.get();
    }

    /**
     * Полностью очищает выбранную дату и время.
     */
    public void clear() {
        dateValue = null;
        timeValue = null;
        updateCommittedDisplay();
        notifyValueChanged();
    }

    /**
     * Создаёт внешнее поле date-picker, которое служит только триггером popup.
     * Контракт:
     * - ручной ввод отключён;
     * - стандартный popup JavaFX подавляется, потому что используется кастомный popup;
     * - отображаемое значение всегда синхронизируется только с подтверждённым состоянием control.
     */
    private DatePicker createDisplayDatePicker(String promptText) {
        DatePicker datePicker = new DatePicker();
        datePicker.setEditable(false);
        datePicker.setFocusTraversable(false);
        datePicker.setPromptText(promptText);
        datePicker.setPrefWidth(DATE_WIDTH);
        datePicker.setMinWidth(DATE_WIDTH);
        datePicker.setMaxWidth(DATE_WIDTH);
        datePicker.setConverter(new StringConverter<>() {
            @Override
            public String toString(LocalDate value) {
                return value == null ? "" : DATE_FORMAT.format(value);
            }

            @Override
            public LocalDate fromString(String text) {
                return null;
            }
        });
        datePicker.showingProperty().addListener((obs, oldValue, showing) -> {
            if (Boolean.TRUE.equals(showing)) {
                datePicker.hide();
            }
        });
        return datePicker;
    }

    /**
     * Строит нижнюю часть popup с переключателем "Весь день", слайдерами и кнопками.
     * Кнопка {@code Сбросить} очищает уже подтверждённое значение, а
     * {@code Применить} переводит draft-состояние в committed-состояние control.
     */
    private VBox createPopupBottom() {
        HBox hourRow = createSliderRow(I18n.t("packetMonitor.date.hours"), hourSlider, hourValueLabel);
        HBox minuteRow = createSliderRow(I18n.t("packetMonitor.date.minutes"), minuteSlider, minuteValueLabel);

        Button clearPopupButton = new Button(I18n.t("packetMonitor.date.reset"));
        clearPopupButton.getStyleClass().add("packet-monitor-date-time-popup-action");
        clearPopupButton.setOnAction(event -> {
            clear();
            popup.hide();
        });

        Button applyButton = new Button(I18n.t("packetMonitor.date.apply"));
        applyButton.getStyleClass().add("packet-monitor-date-time-popup-action");
        applyButton.setDefaultButton(true);
        applyButton.setOnAction(event -> applyDraftAndHide());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox actions = new HBox(8, clearPopupButton, spacer, applyButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("packet-monitor-date-time-popup-actions");

        VBox bottom = new VBox(10, allDayCheckBox, hourRow, minuteRow, actions);
        bottom.setPadding(Insets.EMPTY);
        bottom.getStyleClass().add("packet-monitor-date-time-popup-bottom");
        return bottom;
    }

    /**
     * Создаёт одну строку выбора времени: подпись, слайдер и текущее числовое значение.
     */
    private HBox createSliderRow(String title, Slider slider, Label valueLabel) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("packet-monitor-date-time-popup-label");

        HBox row = new HBox(10, titleLabel, slider, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("packet-monitor-date-time-popup-row");
        HBox.setHgrow(slider, Priority.ALWAYS);
        return row;
    }

    /**
     * Создаёт слайдер времени с дискретным шагом {@code 1}.
     * Контракт: control не использует крупные шаги, потому что пользователь должен
     * иметь доступ к каждому часу и каждой минуте без пропусков.
     */
    private Slider createSlider(int max) {
        Slider slider = new Slider(0, max, 0);
        slider.setSnapToTicks(true);
        slider.setShowTickLabels(false);
        slider.setShowTickMarks(false);
        slider.setMinorTickCount(0);
        slider.setMajorTickUnit(1);
        slider.setBlockIncrement(1);
        slider.setPrefWidth(SLIDER_WIDTH);
        slider.setMinWidth(SLIDER_WIDTH);
        slider.getStyleClass().add("packet-monitor-date-time-slider");
        return slider;
    }

    /**
     * @return метка для отображения текущего положения соответствующего слайдера в формате {@code 00}
     */
    private Label createSliderValueLabel() {
        Label label = new Label("00");
        label.getStyleClass().add("packet-monitor-date-time-popup-value");
        return label;
    }

    /**
     * Подключает открытие popup к визуальным частям control.
     * Контракт: mouse-событие поглощается, чтобы стандартный DatePicker не пытался
     * открыть собственный popup поверх кастомного.
     */
    private void installOpenHandlers() {
        displayPicker.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            togglePopup();
            event.consume();
        });
        timeSummaryLabel.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            togglePopup();
            event.consume();
        });
    }

    /**
     * Переключает видимость кастомного popup-календаря.
     */
    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
        } else {
            showPopup();
        }
    }

    /**
     * Показывает popup под control и перед этим синхронизирует draft-состояние
     * с уже подтверждённым значением.
     */
    private void showPopup() {
        if (getScene() == null) {
            return;
        }

        syncDraftFromCommitted();
        syncPopupTheme();

        Bounds bounds = localToScreen(getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        popup.show(this, bounds.getMinX(), bounds.getMaxY() + 4);
    }

    /**
     * Наследует тему и stylesheet'ы текущей сцены для popup-контента.
     * Это гарантирует совпадение внешнего вида popup с основным окном.
     */
    private void syncPopupTheme() {
        if (getScene() == null) {
            return;
        }
        popupRoot.getStylesheets().setAll(getScene().getStylesheets());
        if (getScene().getRoot().getStyleClass().contains("light-theme")) {
            if (!popupRoot.getStyleClass().contains("light-theme")) {
                popupRoot.getStyleClass().add("light-theme");
            }
        } else {
            popupRoot.getStyleClass().remove("light-theme");
        }
    }

    /**
     * Копирует подтверждённое значение control в draft-модель popup.
     * Контракт:
     * - изменение draft-полей не должно генерировать callback наружу;
     * - при отсутствии времени popup стартует в режиме {@code Весь день}.
     */
    private void syncDraftFromCommitted() {
        adjusting = true;
        try {
            draftDate = dateValue;
            draftTime = timeValue != null ? timeValue : LocalTime.MIDNIGHT;
            draftAllDay = timeValue == null;

            calendar.setValue(draftDate);
            allDayCheckBox.setSelected(draftAllDay);
            hourSlider.setValue(draftTime.getHour());
            minuteSlider.setValue(draftTime.getMinute());

            updateDraftTimeLabels();
            updateDraftControlsState();
        } finally {
            adjusting = false;
        }
    }

    /**
     * Обновляет draft-time по положениям слайдеров.
     * Игнорирует события, возникшие во время программной синхронизации draft-модели.
     */
    private void updateDraftTimeFromSliders() {
        if (adjusting) {
            return;
        }
        draftTime = LocalTime.of((int) Math.round(hourSlider.getValue()), (int) Math.round(minuteSlider.getValue()));
        updateDraftTimeLabels();
    }

    /**
     * Синхронизирует текстовые значения справа от слайдеров с их текущими позициями.
     */
    private void updateDraftTimeLabels() {
        hourValueLabel.setText(formatTimePart((int) Math.round(hourSlider.getValue())));
        minuteValueLabel.setText(formatTimePart((int) Math.round(minuteSlider.getValue())));
    }

    /**
     * Отключает или включает слайдеры в зависимости от режима {@code Весь день}.
     */
    private void updateDraftControlsState() {
        boolean disableTimeSelection = draftAllDay;
        hourSlider.setDisable(disableTimeSelection);
        minuteSlider.setDisable(disableTimeSelection);
        hourValueLabel.setDisable(disableTimeSelection);
        minuteValueLabel.setDisable(disableTimeSelection);
    }

    /**
     * Подтверждает текущее draft-состояние popup и закрывает его.
     * Контракт: если дата снята или выбран режим {@code Весь день}, время в committed-модели становится {@code null}.
     */
    private void applyDraftAndHide() {
        dateValue = draftDate;
        timeValue = dateValue == null || draftAllDay
                ? null
                : draftTime.withSecond(0).withNano(0);
        updateCommittedDisplay();
        popup.hide();
        notifyValueChanged();
    }

    /**
     * Обновляет внешний вид control по committed-состоянию.
     * Отображаемое время не использует draft-значения, пока пользователь не нажал {@code Применить}.
     */
    private void updateCommittedDisplay() {
        displayPicker.setValue(dateValue);
        if (dateValue == null) {
            timeSummaryLabel.setText(I18n.t("packetMonitor.date.allDay"));
            if (!getStyleClass().contains("packet-monitor-date-time-box-empty")) {
                getStyleClass().add("packet-monitor-date-time-box-empty");
            }
        } else if (timeValue == null) {
            timeSummaryLabel.setText(I18n.t("packetMonitor.date.allDay"));
            getStyleClass().remove("packet-monitor-date-time-box-empty");
        } else {
            timeSummaryLabel.setText(TIME_FORMAT.format(timeValue));
            getStyleClass().remove("packet-monitor-date-time-box-empty");
        }
        clearButton.setDisable(dateValue == null && timeValue == null);
    }

    /**
     * Вызывает внешний callback только после committed-изменений control.
     */
    private void notifyValueChanged() {
        if (onValueChanged != null) {
            onValueChanged.run();
        }
    }

    /**
     * Форматирует часы или минуты в двухсимвольный вид {@code 00}.
     */
    private static String formatTimePart(int value) {
        return String.format("%02d", value);
    }
}
