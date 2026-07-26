package com.meshtastic.client.forms.settings;

import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.utils.ConfigValueFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeTableCell;

/**
 * Value-column editor for configuration fields.
 * It selects a JavaFX editor based on the field type and delegates repeated
 * protobuf-field reshaping to the owning form through a small callback.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigValueCell
    extends TreeTableCell<ConfigTreeItem, ConfigTreeItem> {

    private final Consumer<ConfigTreeItem> repeatedEditSynchronizer;

    /**
     * Creates a value editor cell.
     *
     * @param repeatedEditSynchronizer callback invoked after repeated-field edits
     */
    public ConfigValueCell(Consumer<ConfigTreeItem> repeatedEditSynchronizer) {
        this.repeatedEditSynchronizer = repeatedEditSynchronizer;
    }

    @Override
    protected void updateItem(ConfigTreeItem item, boolean empty) {
        super.updateItem(item, empty);
        setText(null);
        setGraphic(null);
        setStyle("");

        if (empty || item == null || item.isCategory()) {
            return;
        }
        if (item.hasAction()) {
            setGraphic(createActionButton(item));
            return;
        }
        if (!item.isEditable()) {
            setText(ConfigValueFormatter.formatValue(item));
            return;
        }

        Class<?> type = item.getValueType();
        if (ConfigValueFormatter.hasBitmaskOptions(item)) {
            setGraphic(createBitmaskEditor(item));
        } else if (type == Boolean.class) {
            setGraphic(createBooleanEditor(item));
        } else if (type == EnumValueDescriptor.class) {
            setGraphic(createEnumEditor(item));
        } else if (isTextEditableType(type)) {
            setGraphic(createTextEditor(item));
        } else {
            setText(ConfigValueFormatter.formatValue(item));
        }
    }

    private Button createActionButton(ConfigTreeItem item) {
        Button button = new Button(item.getActionLabel());
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            if (item.getAction() != null) {
                item.getAction().run();
            }
        });
        return button;
    }

    private CheckBox createBooleanEditor(ConfigTreeItem item) {
        CheckBox checkBox = new CheckBox();
        checkBox.setSelected(item.getValue() instanceof Boolean value && value);
        checkBox
            .selectedProperty()
            .addListener((obs, oldVal, newVal) -> {
                item.setValue(newVal);
                repeatedEditSynchronizer.accept(item);
            });
        return checkBox;
    }

    private ComboBox<EnumValueDescriptor> createEnumEditor(
        ConfigTreeItem item
    ) {
        ComboBox<EnumValueDescriptor> comboBox = new ComboBox<>();
        comboBox
            .getItems()
            .setAll(
                Optional
                    .ofNullable(item.getEnumValues())
                    .stream()
                    .flatMap(List::stream)
                    .filter(EnumValueDescriptor.class::isInstance)
                    .map(EnumValueDescriptor.class::cast)
                    .toList()
            );
        comboBox.setCellFactory(lv -> enumDisplayCell());
        comboBox.setButtonCell(enumDisplayCell());
        if (item.getValue() instanceof EnumValueDescriptor current) {
            comboBox.setValue(current);
        }
        comboBox.setMaxWidth(Double.MAX_VALUE);
        comboBox
            .valueProperty()
            .addListener((obs, oldVal, newVal) -> {
                item.setValue(newVal);
                repeatedEditSynchronizer.accept(item);
            });
        return comboBox;
    }

    /**
     * Creates a text editor for string and numeric fields.
     * If the field has a formatter, the editor receives the existing
     * human-readable representation of the value.
     *
     * @param item configuration item
     * @return text editor
     */
    private TextField createTextEditor(ConfigTreeItem item) {
        TextField textField = new TextField(
            ConfigValueFormatter.formatValue(item)
        );
        textField.setMaxWidth(Double.MAX_VALUE);

        Optional
            .ofNullable(ConfigValueFormatter.promptText(item))
            .filter(prompt -> !prompt.isBlank())
            .ifPresent(textField::setPromptText);
        Optional
            .ofNullable(ConfigValueFormatter.validationHint(item))
            .filter(hint -> !hint.isBlank())
            .map(Tooltip::new)
            .ifPresent(textField::setTooltip);

        textField
            .focusedProperty()
            .addListener((obs, wasFocused, isFocused) -> {
                if (!isFocused) {
                    commitTextValue(item, textField);
                }
            });
        textField.setOnAction(e -> commitTextValue(item, textField));
        return textField;
    }

    /**
     * Creates a selector for bitmask fields stored as numbers but representing
     * a set of independently enabled flags.
     *
     * @param item configuration item
     * @return bitmask menu editor
     */
    private MenuButton createBitmaskEditor(ConfigTreeItem item) {
        MenuButton menuButton = new MenuButton();
        menuButton.setMaxWidth(Double.MAX_VALUE);
        menuButton.setText(ConfigValueFormatter.formatValue(item));

        List<ConfigValueFormatter.BitmaskOption> options =
            ConfigValueFormatter.bitmaskOptions(item);
        List<CheckMenuItem> menuItems = options
            .stream()
            .map(option -> {
                CheckMenuItem menuItem = new CheckMenuItem(option.label());
                menuItem.setSelected(
                    ConfigValueFormatter.isBitmaskOptionSelected(item, option)
                );
                return menuItem;
            })
            .toList();
        menuButton.getItems().addAll(menuItems);

        IntStream
            .range(0, menuItems.size())
            .forEach(index ->
                menuItems
                    .get(index)
                    .selectedProperty()
                    .addListener((obs, oldVal, newVal) -> {
                        List<ConfigValueFormatter.BitmaskOption> selectedOptions =
                            IntStream
                                .range(0, menuItems.size())
                                .filter(i -> menuItems.get(i).isSelected())
                                .mapToObj(options::get)
                                .toList();
                        item.setValue(
                            ConfigValueFormatter.buildBitmaskValue(
                                item,
                                selectedOptions
                            )
                        );
                        repeatedEditSynchronizer.accept(item);
                        menuButton.setText(
                            ConfigValueFormatter.formatValue(item)
                        );
                    })
            );

        return menuButton;
    }

    /**
     * Applies editor text to the field model. On successful parsing it
     * normalizes the displayed value; on failure it highlights the field.
     *
     * @param item      configuration item
     * @param textField editor
     */
    private void commitTextValue(ConfigTreeItem item, TextField textField) {
        try {
            if (isEmptyRepeatedField(item, textField)) {
                item.setValue(null);
                repeatedEditSynchronizer.accept(item);
                textField.setText("");
                textField.setStyle("");
                return;
            }
            item.setValue(
                ConfigValueFormatter.parseTextValue(
                    item,
                    textField.getText()
                )
            );
            repeatedEditSynchronizer.accept(item);
            textField.setText(ConfigValueFormatter.formatValue(item));
            textField.setStyle("");
        } catch (IllegalArgumentException ex) {
            textField.setStyle("-fx-border-color: #E53935;");
        }
    }

    private static ListCell<EnumValueDescriptor> enumDisplayCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(EnumValueDescriptor item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getName());
            }
        };
    }

    private static boolean isTextEditableType(Class<?> type) {
        return type == String.class ||
            type == Integer.class ||
            type == Long.class ||
            type == Float.class ||
            type == Double.class;
    }

    private static boolean isEmptyRepeatedField(
        ConfigTreeItem item,
        TextField textField
    ) {
        return Optional
            .ofNullable(item.getFieldDescriptor())
            .filter(descriptor -> descriptor.isRepeated())
            .filter(ignored -> textField.getText().trim().isEmpty())
            .isPresent();
    }
}
