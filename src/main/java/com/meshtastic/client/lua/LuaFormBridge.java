package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to an embedded MeshApp extension form.
 */
public interface LuaFormBridge {

    /**
     * @return {@code true} when the current Lua session owns an active embedded form
     */
    boolean isFormAvailable();

    /**
     * @return {@code true} while the embedded form should keep the Lua session alive
     */
    boolean isFormOpen();

    /**
     * Brings the embedded form to the foreground.
     */
    void showForm();

    /**
     * Sets the embedded form title.
     *
     * @param title title text
     */
    void setFormTitle(String title);

    /**
     * Removes all user-created controls.
     */
    void clearForm();

    /**
     * Adds a new component to the embedded form.
     *
     * @param spec component specification
     * @return stable component id
     */
    String addFormComponent(LuaFormComponentSpec spec);

    /**
     * Updates an existing component.
     *
     * @param id component id
     * @param spec partial component specification
     */
    void updateFormComponent(String id, LuaFormComponentSpec spec);

    /**
     * Removes an existing component.
     *
     * @param id component id
     */
    void removeFormComponent(String id);

    /**
     * Reads a component value.
     *
     * @param id component id
     * @return component value; type depends on control kind
     */
    Object formComponentValue(String id);
}
