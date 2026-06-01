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
 * Combined date and time picker used by filters.
 * It behaves visually as a single {@link DatePicker}-based control. The popup
 * contains a calendar and a bottom time-selection panel with sliders. Hours and
 * minutes always move in steps of {@code 1}; value changes are committed only by
 * the Apply action, and a missing time is treated as an all-day selection.
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
     * @param promptText     date placeholder
     * @param onValueChanged callback invoked after a committed value change
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
     * @return selected date, or {@code null} when the date filter is disabled
     */
    public LocalDate getDate() {
        return dateValue;
    }

    /**
     * @return selected time, or {@code null} when all-day mode is active
     */
    public LocalTime getTime() {
        return timeValue;
    }

    /**
     * @return read-only visibility flag for the calendar popup
     */
    public ReadOnlyBooleanProperty popupShowingProperty() {
        return popupShowing.getReadOnlyProperty();
    }

    /**
     * @return {@code true} while the calendar popup is open
     */
    public boolean isPopupShowing() {
        return popupShowing.get();
    }

    /**
     * Clears the selected date and time completely.
     */
    public void clear() {
        dateValue = null;
        timeValue = null;
        updateCommittedDisplay();
        notifyValueChanged();
    }

    /**
     * Creates the visible date-picker field, used only as the popup trigger.
     * Manual input is disabled. The standard JavaFX popup is suppressed because
     * this control provides its own popup, and the displayed value is synchronized
     * only from the committed control state.
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
     * Builds the popup footer with the all-day toggle, sliders, and actions.
     * Reset clears the already committed value, while Apply promotes the draft
     * state to the committed control state.
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
     * Creates one time-selection row: label, slider, and current numeric value.
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
     * Creates a time slider with a discrete {@code 1} step.
     * Larger steps are deliberately avoided so the user can reach every hour and
     * minute without gaps.
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
     * @return label that shows the matching slider position in {@code 00} format
     */
    private Label createSliderValueLabel() {
        Label label = new Label("00");
        label.getStyleClass().add("packet-monitor-date-time-popup-value");
        return label;
    }

    /**
     * Wires popup opening to the visible parts of the control.
     * Mouse events are consumed so the standard DatePicker popup cannot open on
     * top of the custom popup.
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
     * Toggles the custom calendar popup.
     */
    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
        } else {
            showPopup();
        }
    }

    /**
     * Shows the popup below the control after synchronizing draft state from the
     * committed value.
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
     * Inherits the current scene theme and stylesheets for popup content.
     * This keeps the popup visually aligned with the main window.
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
     * Copies the committed control value into the popup draft model.
     * Draft-field changes must not fire the external callback, and when no time
     * is selected the popup starts in all-day mode.
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
     * Updates draft time from the slider positions.
     * Events raised during programmatic draft synchronization are ignored.
     */
    private void updateDraftTimeFromSliders() {
        if (adjusting) {
            return;
        }
        draftTime = LocalTime.of((int) Math.round(hourSlider.getValue()), (int) Math.round(minuteSlider.getValue()));
        updateDraftTimeLabels();
    }

    /**
     * Synchronizes the value labels next to the sliders with current slider positions.
     */
    private void updateDraftTimeLabels() {
        hourValueLabel.setText(formatTimePart((int) Math.round(hourSlider.getValue())));
        minuteValueLabel.setText(formatTimePart((int) Math.round(minuteSlider.getValue())));
    }

    /**
     * Enables or disables the sliders according to all-day mode.
     */
    private void updateDraftControlsState() {
        boolean disableTimeSelection = draftAllDay;
        hourSlider.setDisable(disableTimeSelection);
        minuteSlider.setDisable(disableTimeSelection);
        hourValueLabel.setDisable(disableTimeSelection);
        minuteValueLabel.setDisable(disableTimeSelection);
    }

    /**
     * Commits the current popup draft state and closes the popup.
     * If the date is cleared or all-day mode is selected, committed time becomes {@code null}.
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
     * Updates the control presentation from committed state.
     * The displayed time does not use draft values until the user applies them.
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
     * Invokes the external callback only after committed control changes.
     */
    private void notifyValueChanged() {
        if (onValueChanged != null) {
            onValueChanged.run();
        }
    }

    /**
     * Formats an hour or minute value as a two-character {@code 00} string.
     */
    private static String formatTimePart(int value) {
        return String.format("%02d", value);
    }
}
