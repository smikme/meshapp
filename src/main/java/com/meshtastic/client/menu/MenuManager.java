package com.meshtastic.client.menu;

import com.meshtastic.client.system.AllForms;
import com.meshtastic.client.system.DrawerManager;
import com.meshtastic.client.system.DrawerPane;
import com.meshtastic.client.system.Form;
import com.meshtastic.client.system.FormManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class MenuManager {

    private final List<MenuItem> items = new ArrayList<>();
    private final Map<String, Runnable> customActions = new LinkedHashMap<>();

    public MenuManager add(MenuItem item) {
        items.add(item);
        return this;
    }

    public MenuManager addLabel(String name) {
        items.add(new MenuItem(name, name, null, null, MenuItem.Type.LABEL, null));
        return this;
    }

    public MenuManager addSeparator() {
        items.add(new MenuItem(null, null, null, null, MenuItem.Type.SEPARATOR, null));
        return this;
    }

    public MenuManager clear() {
        items.clear();
        customActions.clear();
        return this;
    }

    public MenuManager registerAction(String itemId, Runnable action) {
        customActions.put(itemId, action);
        return this;
    }

    public void rebuildMenu() {
        rebuildMenu(DrawerManager.getDrawerPane());
    }

    public void rebuildMenu(DrawerPane drawerPane) {
        if (drawerPane == null) { return; }

        List<DrawerPane.DrawerMenuItem> drawerItems = new ArrayList<>();
        for (MenuItem item : items) {
            DrawerPane.DrawerMenuItem.Type type = switch (item.type()) {
                case LABEL -> DrawerPane.DrawerMenuItem.Type.LABEL;
                case SEPARATOR -> DrawerPane.DrawerMenuItem.Type.SEPARATOR;
                case ITEM -> DrawerPane.DrawerMenuItem.Type.ITEM;
            };

            Runnable action = null;
            if (item.type() == MenuItem.Type.ITEM) {
                Runnable customAction = customActions.get(item.id());
                Class<?> formClass = item.formClass();
                if (customAction != null) {
                    action = customAction;
                } else if (formClass != null && Form.class.isAssignableFrom(formClass)) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Form> fc = (Class<? extends Form>) formClass;
                    action = () -> FormManager.showForm(AllForms.getForm(fc));
                }
            }

            drawerItems.add(new DrawerPane.DrawerMenuItem(
                    item.name(), item.iconText(), item.iconPath(), type, item.formClass(), action));
        }

        drawerPane.rebuildMenu(drawerItems);
    }

    public record MenuItem(String id, String name, String iconText, String iconPath, Type type, Class<?> formClass) {
        public MenuItem(String name, String iconText, String iconPath, Type type, Class<?> formClass) {
            this(name, name, iconText, iconPath, type, formClass);
        }

        /** Constructor without {@code iconPath}, kept for backward compatibility. */
        public MenuItem(String name, String iconText, Type type, Class<?> formClass) {
            this(name, name, iconText, null, type, formClass);
        }
        public enum Type { ITEM, LABEL, SEPARATOR }
    }
}
