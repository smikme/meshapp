package com.meshtastic.client.forms.settings;

/**
 * Stable configuration tree identifiers used by the settings editor.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigEditorConstants {

    public static final String OWNER_INFO_CONFIG_TYPE = "owner_info";
    public static final String OWNER_LONG_NAME_FIELD = "long_name";
    public static final String OWNER_SHORT_NAME_FIELD = "short_name";
    public static final String OWNER_IS_LICENSED_FIELD = "is_licensed";
    public static final String FIXED_POSITION_CONFIG_TYPE = "fixed_position";
    public static final String FIXED_POSITION_LATITUDE_FIELD = "latitude";
    public static final String FIXED_POSITION_LONGITUDE_FIELD = "longitude";
    public static final String FIXED_POSITION_ALTITUDE_FIELD = "altitude";
    public static final String RINGTONE_CONFIG_TYPE = "ringtone";
    public static final String RINGTONE_FIELD = "ringtone";
    public static final String CONFIG_ROOT_TYPE = "config";
    public static final String MODULE_CONFIG_ROOT_TYPE = "module_config";

    private ConfigEditorConstants() {}
}
