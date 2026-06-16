package com.meshtastic.client.forms;

import atlantafx.base.controls.Message;
import atlantafx.base.controls.RingProgressIndicator;
import atlantafx.base.controls.SegmentedControl;
import atlantafx.base.controls.Spacer;
import atlantafx.base.controls.Tile;
import atlantafx.base.controls.TileBase;
import atlantafx.base.controls.ToggleLabel;
import atlantafx.base.controls.ToggleSwitch;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaFormBridge;
import com.meshtastic.client.lua.LuaFormComponentSpec;
import com.meshtastic.client.lua.LuaFormEvent;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.system.Form;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Embedded application section controlled by a Lua extension script.
 */
public final class LuaExtensionForm extends Form implements LuaFormBridge {

    private static final long FX_WAIT_TIMEOUT_SECONDS = 2;
    private static final AtomicLong COMPONENT_COUNTER = new AtomicLong();

    private final long scriptId;
    private final Consumer<LuaFormEvent> eventSink;
    private final Map<String, Node> components = new LinkedHashMap<>();
    private final Map<String, FormContainer> containers = new LinkedHashMap<>();

    private final Label iconLabel = new Label();
    private final Label titleLabel = new Label();
    private final VBox contentBox = new VBox(10);

    private volatile boolean disposed;
    private boolean updating;
    private String scriptName;
    private String scriptIcon;

    public LuaExtensionForm(LuaScript script, Consumer<LuaFormEvent> eventSink) {
        this.scriptId = script != null ? script.getId() : 0L;
        this.eventSink = eventSink;
        this.scriptName = script != null ? script.getName() : I18n.t("meshIde.extension.titleFallback");
        this.scriptIcon = script != null ? script.getIcon() : LuaScript.DEFAULT_ICON;
        configureLayout();
        updateScript(script);
    }

    public long scriptId() {
        return scriptId;
    }

    public void updateScript(LuaScript script) {
        if (script == null) {
            return;
        }
        scriptName = script.getName();
        scriptIcon = script.getIcon();
        runOnFxAndWait(() -> {
            iconLabel.setText(scriptIcon);
            titleLabel.setText(scriptName);
            return null;
        });
    }

    public void dispose() {
        disposed = true;
        runOnFxAndWait(() -> {
            components.clear();
            containers.clear();
            contentBox.getChildren().clear();
            containers.put("root", new PaneContainer(contentBox));
            return null;
        });
    }

    @Override
    public void formOpen() {
        requestFocus();
    }

    @Override
    public boolean isFormAvailable() {
        return !disposed;
    }

    @Override
    public boolean isFormOpen() {
        return !disposed;
    }

    @Override
    public void showForm() {
        runOnFxAndWait(() -> {
            requestFocus();
            return null;
        });
    }

    @Override
    public void setFormTitle(String title) {
        runOnFxAndWait(() -> {
            titleLabel.setText(title == null || title.isBlank() ? scriptName : title);
            return null;
        });
    }

    @Override
    public void clearForm() {
        runOnFxAndWait(() -> {
            components.clear();
            containers.clear();
            contentBox.getChildren().clear();
            containers.put("root", new PaneContainer(contentBox));
            return null;
        });
    }

    @Override
    public String addFormComponent(LuaFormComponentSpec spec) {
        return runOnFxAndWait(() -> addComponentOnFx(spec));
    }

    @Override
    public void updateFormComponent(String id, LuaFormComponentSpec spec) {
        runOnFxAndWait(() -> {
            updateComponentOnFx(normalizeId(id), spec);
            return null;
        });
    }

    @Override
    public void removeFormComponent(String id) {
        runOnFxAndWait(() -> {
            removeComponentOnFx(normalizeId(id));
            return null;
        });
    }

    @Override
    public Object formComponentValue(String id) {
        return runOnFxAndWait(() -> valueFor(components.get(normalizeId(id))));
    }

    private void configureLayout() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));

        HBox titleRow = new HBox(10);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        iconLabel.setStyle("-fx-font-size: 22px;");
        titleLabel.getStyleClass().add("form-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        titleRow.getChildren().addAll(iconLabel, titleLabel, spacer);

        contentBox.setFillWidth(true);
        containers.put("root", new PaneContainer(contentBox));

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        content.getChildren().addAll(titleRow, scrollPane);
        getChildren().add(content);
    }

    private String addComponentOnFx(LuaFormComponentSpec spec) {
        if (disposed) {
            throw new IllegalStateException("Extension form is closed");
        }
        LuaFormComponentSpec safeSpec = spec != null ? spec : emptySpec();
        String type = normalizeType(safeSpec.type());
        String id = normalizeId(safeSpec.id());
        if (id.isBlank()) {
            id = "component_" + COMPONENT_COUNTER.incrementAndGet();
        }
        if (components.containsKey(id) || containers.containsKey(id)) {
            throw new IllegalArgumentException("mesh.form.add: duplicate component id: " + id);
        }

        Node node = createNode(id, type, safeSpec);
        applyCommonProperties(node, safeSpec);

        FormContainer parent = parentFor(safeSpec.parentId());
        parent.add(node);
        components.put(id, node);
        FormContainer container = containerFor(node, type);
        if (container != null) {
            containers.put(id, container);
        }
        return id;
    }

    private Node createNode(String id, String type, LuaFormComponentSpec spec) {
        return switch (type) {
            case "card" -> createCard();
            case "vbox" -> createVBox();
            case "hbox" -> createHBox();
            case "split_pane", "splitpane", "split" -> createSplitPane(spec);
            case "scroll_pane", "scrollpane", "scroll" -> createScrollPane();
            case "button" -> createButton(id, spec);
            case "text_field", "textfield", "input" -> createTextField(id, spec);
            case "password_field", "password", "passwordfield" -> createPasswordField(id, spec);
            case "text_area", "textarea" -> createTextArea(id, spec);
            case "checkbox", "check_box" -> createCheckBox(id, spec);
            case "toggle_switch", "toggleswitch", "switch" -> createToggleSwitch(id, spec);
            case "combo_box", "combobox", "select" -> createComboBox(id, spec);
            case "segmented_control", "segmented", "segments" -> createSegmentedControl(id, spec);
            case "list_view", "listview", "list" -> createListView(id, spec);
            case "slider" -> createSlider(id, spec);
            case "progress_bar", "progress" -> createProgressBar(spec);
            case "ring_progress", "ring_progress_indicator", "ringprogress" -> createRingProgress(spec);
            case "message" -> createMessage(id, spec);
            case "tile" -> createTile(id, spec);
            case "spacer" -> createSpacer(spec);
            case "separator" -> new Separator(orientation(spec, Orientation.HORIZONTAL));
            case "label", "" -> createLabel(spec);
            default -> throw new IllegalArgumentException("mesh.form.add: unsupported component type: " + type);
        };
    }

    private VBox createCard() {
        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.getStyleClass().add("connection-card");
        card.setFillWidth(true);
        return card;
    }

    private VBox createVBox() {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        return box;
    }

    private HBox createHBox() {
        HBox box = new HBox(8);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private SplitPane createSplitPane(LuaFormComponentSpec spec) {
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(orientation(spec, Orientation.HORIZONTAL));
        splitPane.setDividerPositions(0.24);
        splitPane.setMaxWidth(Double.MAX_VALUE);
        splitPane.setMaxHeight(Double.MAX_VALUE);
        return splitPane;
    }

    private ScrollPane createScrollPane() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setMaxWidth(Double.MAX_VALUE);
        scrollPane.setMaxHeight(Double.MAX_VALUE);
        return scrollPane;
    }

    private Label createLabel(LuaFormComponentSpec spec) {
        Label label = new Label(text(spec));
        label.setWrapText(true);
        return label;
    }

    private Button createButton(String id, LuaFormComponentSpec spec) {
        Button button = new Button(text(spec));
        button.setOnAction(event -> emitComponentEvent(id, "action"));
        if ("accent".equalsIgnoreCase(safe(spec.style()))) {
            button.getStyleClass().add("accent");
        }
        return button;
    }

    private TextField createTextField(String id, LuaFormComponentSpec spec) {
        TextField field = new TextField(stringValue(spec.value()));
        field.setPromptText(safe(spec.prompt()));
        field.setMaxWidth(Double.MAX_VALUE);
        field.setOnAction(event -> emitComponentEvent(id, "action"));
        field.textProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return field;
    }

    private PasswordField createPasswordField(String id, LuaFormComponentSpec spec) {
        PasswordField field = new PasswordField();
        field.setText(stringValue(spec.value()));
        field.setPromptText(safe(spec.prompt()));
        field.setMaxWidth(Double.MAX_VALUE);
        field.setOnAction(event -> emitComponentEvent(id, "action"));
        field.textProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return field;
    }

    private TextArea createTextArea(String id, LuaFormComponentSpec spec) {
        TextArea area = new TextArea(stringValue(spec.value()));
        area.setPromptText(safe(spec.prompt()));
        area.setWrapText(true);
        area.setPrefRowCount(5);
        area.setMaxWidth(Double.MAX_VALUE);
        area.textProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return area;
    }

    private CheckBox createCheckBox(String id, LuaFormComponentSpec spec) {
        CheckBox checkBox = new CheckBox(text(spec));
        checkBox.setSelected(booleanValue(spec.value()));
        checkBox.selectedProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return checkBox;
    }

    private ToggleSwitch createToggleSwitch(String id, LuaFormComponentSpec spec) {
        ToggleSwitch toggleSwitch = new ToggleSwitch(text(spec));
        toggleSwitch.setSelected(booleanValue(spec.value()));
        toggleSwitch.selectedProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return toggleSwitch;
    }

    private ComboBox<String> createComboBox(String id, LuaFormComponentSpec spec) {
        ComboBox<String> comboBox = new ComboBox<>();
        comboBox.getItems().setAll(spec.items() != null ? spec.items() : List.of());
        comboBox.setPromptText(safe(spec.prompt()));
        comboBox.setMaxWidth(Double.MAX_VALUE);
        String value = stringValue(spec.value());
        if (!value.isBlank()) {
            comboBox.getSelectionModel().select(value);
        }
        comboBox.valueProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return comboBox;
    }

    private ListView<String> createListView(String id, LuaFormComponentSpec spec) {
        ListView<String> listView = new ListView<>();
        listView.getItems().setAll(spec.items() != null ? spec.items() : List.of());
        listView.setMaxWidth(Double.MAX_VALUE);
        listView.setMaxHeight(Double.MAX_VALUE);
        String value = stringValue(spec.value());
        if (!value.isBlank()) {
            listView.getSelectionModel().select(value);
        }
        listView.getSelectionModel().selectedItemProperty()
                .addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return listView;
    }

    private SegmentedControl createSegmentedControl(String id, LuaFormComponentSpec spec) {
        SegmentedControl control = new SegmentedControl();
        control.setMaxWidth(Double.MAX_VALUE);
        setSegments(control, spec.items(), stringValue(spec.value()), id);
        return control;
    }

    private Slider createSlider(String id, LuaFormComponentSpec spec) {
        double min = spec.min() != null ? spec.min() : 0.0;
        double max = spec.max() != null ? spec.max() : 100.0;
        Slider slider = new Slider(min, max, clamp(numberValue(spec.value(), min), min, max));
        slider.setShowTickLabels(true);
        slider.setMaxWidth(Double.MAX_VALUE);
        slider.valueProperty().addListener((obs, oldValue, newValue) -> emitComponentEvent(id, "change"));
        return slider;
    }

    private ProgressBar createProgressBar(LuaFormComponentSpec spec) {
        ProgressBar progressBar = new ProgressBar(clamp(numberValue(spec.value(), 0.0), 0.0, 1.0));
        progressBar.setMaxWidth(Double.MAX_VALUE);
        return progressBar;
    }

    private RingProgressIndicator createRingProgress(LuaFormComponentSpec spec) {
        RingProgressIndicator progress = new RingProgressIndicator(clamp(numberValue(spec.value(), 0.0), 0.0, 1.0));
        progress.setPrefSize(54, 54);
        progress.setMinSize(38, 38);
        return progress;
    }

    private Message createMessage(String id, LuaFormComponentSpec spec) {
        Message message = new Message(text(spec), stringValue(spec.value()));
        message.setActionHandler(() -> emitComponentEvent(id, "action"));
        message.setOnClose(event -> emitComponentEvent(id, "close"));
        message.setMaxWidth(Double.MAX_VALUE);
        return message;
    }

    private Tile createTile(String id, LuaFormComponentSpec spec) {
        Tile tile = new Tile(text(spec), stringValue(spec.value()));
        tile.setActionHandler(() -> emitComponentEvent(id, "action"));
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private Spacer createSpacer(LuaFormComponentSpec spec) {
        double size = spec != null && spec.value() instanceof Number number ? number.doubleValue() : 8.0;
        return new Spacer(size, orientation(spec, Orientation.HORIZONTAL));
    }

    private void updateComponentOnFx(String id, LuaFormComponentSpec spec) {
        Node node = components.get(id);
        if (node == null) {
            throw new IllegalArgumentException("mesh.form.set: component not found: " + id);
        }
        updating = true;
        try {
            applyCommonProperties(node, spec);
            if (spec == null) {
                return;
            }
            if (spec.text() != null && node instanceof Labeled labeled) {
                labeled.setText(spec.text());
            } else if (spec.text() != null && node instanceof TileBase tileBase) {
                tileBase.setTitle(spec.text());
            }
            if (spec.prompt() != null && node instanceof TextInputControl input) {
                input.setPromptText(spec.prompt());
            } else if (spec.prompt() != null && node instanceof ComboBox<?> comboBox) {
                comboBox.setPromptText(spec.prompt());
            }
            if (spec.value() != null) {
                setValue(node, spec.value());
            }
            if (spec.items() != null && !spec.items().isEmpty() && node instanceof ComboBox<?> comboBox) {
                @SuppressWarnings("unchecked")
                ComboBox<String> stringCombo = (ComboBox<String>) comboBox;
                Object selected = stringCombo.getValue();
                stringCombo.getItems().setAll(spec.items());
                if (selected instanceof String selectedText && stringCombo.getItems().contains(selectedText)) {
                    stringCombo.getSelectionModel().select(selectedText);
                }
            }
            if (spec.items() != null && !spec.items().isEmpty() && node instanceof ListView<?> listView) {
                @SuppressWarnings("unchecked")
                ListView<String> stringList = (ListView<String>) listView;
                Object selected = stringList.getSelectionModel().getSelectedItem();
                stringList.getItems().setAll(spec.items());
                if (selected instanceof String selectedText && stringList.getItems().contains(selectedText)) {
                    stringList.getSelectionModel().select(selectedText);
                }
            }
            if (spec.items() != null && !spec.items().isEmpty() && node instanceof SegmentedControl segmentedControl) {
                Object selected = valueFor(segmentedControl);
                String value = spec.value() != null ? stringValue(spec.value()) : selected != null ? selected.toString() : "";
                setSegments(segmentedControl, spec.items(), value, id);
            }
            if (node instanceof Slider slider) {
                if (spec.min() != null) {
                    slider.setMin(spec.min());
                }
                if (spec.max() != null) {
                    slider.setMax(spec.max());
                }
            }
            if (spec.rows() != null && node instanceof TextArea area) {
                area.setPrefRowCount(Math.max(1, spec.rows()));
            }
        } finally {
            updating = false;
        }
    }

    private void removeComponentOnFx(String id) {
        Node node = components.remove(id);
        containers.remove(id);
        if (node != null) {
            for (FormContainer container : containers.values()) {
                container.remove(node);
            }
        }
    }

    private void applyCommonProperties(Node node, LuaFormComponentSpec spec) {
        if (node instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.setMaxHeight(Double.MAX_VALUE);
            if (node instanceof TextInputControl || node instanceof ComboBox<?> || node instanceof ProgressBar) {
                VBox.setVgrow(region, Priority.NEVER);
            }
        }
        if (spec == null) {
            return;
        }
        applySizeProperties(node, spec);
        applyGrowProperties(node, spec);
        if (spec.readOnly() != null && node instanceof TextInputControl input) {
            input.setEditable(!spec.readOnly());
        }
        if (spec.wrap() != null) {
            if (node instanceof TextArea area) {
                area.setWrapText(spec.wrap());
            } else if (node instanceof Label label) {
                label.setWrapText(spec.wrap());
            }
        }
        if (spec.rows() != null && node instanceof TextArea area) {
            area.setPrefRowCount(Math.max(1, spec.rows()));
        }
        if (Boolean.TRUE.equals(spec.monospace()) || styleContains(spec, "monospace")) {
            appendStyle(node, "-fx-font-family: 'Menlo', 'Consolas', 'Monospaced';");
        }
        if (spec.disabled() != null) {
            node.setDisable(spec.disabled());
        }
        if (spec.visible() != null) {
            node.setVisible(spec.visible());
            node.setManaged(spec.visible());
        }
    }

    private void setValue(Node node, Object value) {
        if (node instanceof TextInputControl input) {
            input.setText(stringValue(value));
        } else if (node instanceof CheckBox checkBox) {
            checkBox.setSelected(booleanValue(value));
        } else if (node instanceof ToggleSwitch toggleSwitch) {
            toggleSwitch.setSelected(booleanValue(value));
        } else if (node instanceof Slider slider) {
            slider.setValue(clamp(numberValue(value, slider.getMin()), slider.getMin(), slider.getMax()));
        } else if (node instanceof ProgressIndicator progressIndicator) {
            progressIndicator.setProgress(clamp(numberValue(value, 0.0), 0.0, 1.0));
        } else if (node instanceof ComboBox<?> comboBox) {
            @SuppressWarnings("unchecked")
            ComboBox<String> stringCombo = (ComboBox<String>) comboBox;
            stringCombo.getSelectionModel().select(stringValue(value));
        } else if (node instanceof ListView<?> listView) {
            @SuppressWarnings("unchecked")
            ListView<String> stringList = (ListView<String>) listView;
            stringList.getSelectionModel().select(stringValue(value));
        } else if (node instanceof SegmentedControl segmentedControl) {
            selectSegment(segmentedControl, stringValue(value));
        } else if (node instanceof TileBase tileBase) {
            tileBase.setDescription(stringValue(value));
        } else if (node instanceof Button) {
            return;
        } else if (node instanceof Labeled labeled) {
            labeled.setText(stringValue(value));
        }
    }

    private Object valueFor(Node node) {
        if (node instanceof TextInputControl input) {
            return input.getText();
        }
        if (node instanceof CheckBox checkBox) {
            return checkBox.isSelected();
        }
        if (node instanceof ToggleSwitch toggleSwitch) {
            return toggleSwitch.isSelected();
        }
        if (node instanceof Slider slider) {
            return slider.getValue();
        }
        if (node instanceof ProgressIndicator progressIndicator) {
            return progressIndicator.getProgress();
        }
        if (node instanceof ComboBox<?> comboBox) {
            return comboBox.getValue();
        }
        if (node instanceof ListView<?> listView) {
            return listView.getSelectionModel().getSelectedItem();
        }
        if (node instanceof SegmentedControl segmentedControl) {
            return selectedSegment(segmentedControl);
        }
        if (node instanceof TileBase tileBase) {
            return tileBase.getDescription();
        }
        if (node instanceof Button) {
            return null;
        }
        if (node instanceof Labeled labeled) {
            return labeled.getText();
        }
        return null;
    }

    private String textFor(Node node) {
        if (node instanceof TextInputControl input) {
            return input.getText();
        }
        if (node instanceof Labeled labeled) {
            return labeled.getText();
        }
        if (node instanceof TileBase tileBase) {
            return tileBase.getTitle();
        }
        Object value = valueFor(node);
        return value != null ? value.toString() : "";
    }

    private FormContainer parentFor(String parentId) {
        String id = normalizeId(parentId);
        if (id.isBlank()) {
            id = "root";
        }
        FormContainer parent = containers.get(id);
        if (parent == null) {
            throw new IllegalArgumentException("mesh.form.add: parent container not found: " + id);
        }
        return parent;
    }

    private FormContainer containerFor(Node node, String type) {
        if (!isContainerType(type)) {
            return null;
        }
        if (node instanceof SplitPane splitPane) {
            return new SplitPaneContainer(splitPane);
        }
        if (node instanceof ScrollPane scrollPane) {
            return new ScrollPaneContainer(scrollPane);
        }
        if (node instanceof Pane pane) {
            return new PaneContainer(pane);
        }
        return null;
    }

    private void setSegments(SegmentedControl control, List<String> items, String selected, String id) {
        ToggleGroup group = new ToggleGroup();
        control.setToggleGroup(group);
        control.getSegments().clear();
        for (String item : items != null ? items : List.<String>of()) {
            ToggleLabel label = new ToggleLabel(item);
            label.setToggleGroup(group);
            label.selectedProperty().addListener((obs, oldValue, newValue) -> {
                if (newValue) {
                    emitComponentEvent(id, "change");
                }
            });
            control.getSegments().add(label);
            if (item.equals(selected)) {
                label.setSelected(true);
            }
        }
    }

    private static void selectSegment(SegmentedControl control, String value) {
        for (ToggleLabel segment : control.getSegments()) {
            if (segment.getText().equals(value)) {
                segment.setSelected(true);
                return;
            }
        }
        ToggleGroup group = control.getToggleGroup();
        if (group != null) {
            group.selectToggle(null);
        }
    }

    private static String selectedSegment(SegmentedControl control) {
        for (ToggleLabel segment : control.getSegments()) {
            if (segment.isSelected()) {
                return segment.getText();
            }
        }
        return null;
    }

    private void emitComponentEvent(String id, String type) {
        if (updating || disposed || eventSink == null) {
            return;
        }
        Node node = components.get(id);
        if (node == null) {
            return;
        }
        eventSink.accept(new LuaFormEvent(scriptId, id, type, valueFor(node), textFor(node)));
    }

    private static boolean isContainerType(String type) {
        return "card".equals(type)
                || "vbox".equals(type)
                || "hbox".equals(type)
                || "split_pane".equals(type)
                || "splitpane".equals(type)
                || "split".equals(type)
                || "scroll_pane".equals(type)
                || "scrollpane".equals(type)
                || "scroll".equals(type);
    }

    private static LuaFormComponentSpec emptySpec() {
        return new LuaFormComponentSpec(null, null, null, null, null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static Orientation orientation(LuaFormComponentSpec spec, Orientation fallback) {
        String value = spec != null ? safe(spec.orientation()).trim().toLowerCase(Locale.ROOT) : "";
        return switch (value) {
            case "vertical", "v" -> Orientation.VERTICAL;
            case "horizontal", "h" -> Orientation.HORIZONTAL;
            default -> fallback;
        };
    }

    private static void applySizeProperties(Node node, LuaFormComponentSpec spec) {
        if (!(node instanceof Region region)) {
            return;
        }
        if (spec.width() != null) {
            region.setPrefWidth(spec.width());
        }
        if (spec.height() != null) {
            region.setPrefHeight(spec.height());
        }
        if (spec.minWidth() != null) {
            region.setMinWidth(spec.minWidth());
        }
        if (spec.minHeight() != null) {
            region.setMinHeight(spec.minHeight());
        }
        if (spec.maxWidth() != null) {
            region.setMaxWidth(spec.maxWidth());
        }
        if (spec.maxHeight() != null) {
            region.setMaxHeight(spec.maxHeight());
        }
    }

    private static void applyGrowProperties(Node node, LuaFormComponentSpec spec) {
        Priority priority = priority(spec.grow());
        if (priority == null) {
            return;
        }
        VBox.setVgrow(node, priority);
        HBox.setHgrow(node, priority);
        SplitPane.setResizableWithParent(node, priority != Priority.NEVER);
    }

    private static Priority priority(String value) {
        String normalized = safe(value).trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "always", "true", "yes", "1", "both", "fill", "grow" -> Priority.ALWAYS;
            case "sometimes" -> Priority.SOMETIMES;
            case "never", "false", "no", "0", "none" -> Priority.NEVER;
            default -> null;
        };
    }

    private static boolean styleContains(LuaFormComponentSpec spec, String token) {
        String style = spec != null ? safe(spec.style()).toLowerCase(Locale.ROOT) : "";
        String normalizedToken = safe(token).toLowerCase(Locale.ROOT);
        for (String part : style.split("[,;\\s]+")) {
            if (part.equals(normalizedToken)) {
                return true;
            }
        }
        return false;
    }

    private static void appendStyle(Node node, String style) {
        String current = safe(node.getStyle()).trim();
        if (current.contains(style)) {
            return;
        }
        if (!current.isBlank() && !current.endsWith(";")) {
            current += ";";
        }
        node.setStyle(current.isBlank() ? style : current + " " + style);
    }

    private interface FormContainer {
        void add(Node node);

        void remove(Node node);
    }

    private record PaneContainer(Pane pane) implements FormContainer {
        @Override
        public void add(Node node) {
            pane.getChildren().add(node);
        }

        @Override
        public void remove(Node node) {
            pane.getChildren().remove(node);
        }
    }

    private record SplitPaneContainer(SplitPane splitPane) implements FormContainer {
        @Override
        public void add(Node node) {
            splitPane.getItems().add(node);
        }

        @Override
        public void remove(Node node) {
            splitPane.getItems().remove(node);
        }
    }

    private record ScrollPaneContainer(ScrollPane scrollPane) implements FormContainer {
        @Override
        public void add(Node node) {
            Node content = scrollPane.getContent();
            if (content == null) {
                scrollPane.setContent(node);
                return;
            }
            if (content instanceof Pane pane) {
                pane.getChildren().add(node);
                return;
            }
            VBox box = new VBox(8);
            box.setFillWidth(true);
            box.getChildren().addAll(content, node);
            scrollPane.setContent(box);
        }

        @Override
        public void remove(Node node) {
            Node content = scrollPane.getContent();
            if (content == node) {
                scrollPane.setContent(null);
            } else if (content instanceof Pane pane) {
                pane.getChildren().remove(node);
            }
        }
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    private static String text(LuaFormComponentSpec spec) {
        String text = spec != null ? spec.text() : null;
        return text != null ? text : "";
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String stringValue(Object value) {
        return value != null ? value.toString() : "";
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0.0;
        }
        String text = stringValue(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    private static double numberValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(stringValue(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static <T> T runOnFxAndWait(java.util.concurrent.Callable<T> task) {
        if (Platform.isFxApplicationThread()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(task.call());
            } catch (Throwable throwable) {
                error.set(throwable);
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(FX_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("JavaFX form update timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("JavaFX form update interrupted", e);
        }
        Throwable throwable = error.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable != null) {
            throw new IllegalStateException(throwable);
        }
        return result.get();
    }
}
