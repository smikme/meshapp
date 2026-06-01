package com.meshtastic.client.lua;

import com.meshtastic.client.i18n.I18n;

/**
 * Database model of a MeshApp Lua script.
 * <p>
 * Stores a stable GUID, emoji icon, source code, user enablement settings, and
 * last-run metadata shown by the MeshApp IDE script list.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class LuaScript {

    public static final String DEFAULT_ICON = "🤖";
    public static final long DEFAULT_VERSION = 1L;
    private static final int MAX_ICON_LENGTH = 32;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_TEXT = 0xFE0E;
    private static final int VARIATION_SELECTOR_EMOJI = 0xFE0F;
    private static final int KEYCAP_MARK = 0x20E3;

    /**
     * Returns whether a value is one emoji symbol or one emoji ZWJ sequence.
     *
     * @param icon string to validate
     * @return {@code true} when the string contains exactly one emoji icon
     */
    public static boolean isEmojiIcon(String icon) {
        String value = icon == null ? "" : icon.trim();
        if (value.isEmpty() || value.length() > MAX_ICON_LENGTH) {
            return false;
        }
        int[] codePoints = value.codePoints().toArray();
        return isKeycapSequence(codePoints)
                || isRegionalFlagSequence(codePoints)
                || isEmojiZwjSequence(codePoints);
    }

    /**
     * Returns the default icon for blank or invalid input.
     *
     * @param icon source icon
     * @return valid emoji icon
     */
    public static String normalizeIcon(String icon) {
        String value = icon == null ? "" : icon.trim();
        return isEmojiIcon(value) ? value : DEFAULT_ICON;
    }

    /**
     * Normalizes blank values to the default icon and rejects non-emoji text.
     *
     * @param icon source icon
     * @return valid emoji icon
     */
    public static String requireValidIcon(String icon) {
        String value = icon == null ? "" : icon.trim();
        if (value.isEmpty()) {
            return DEFAULT_ICON;
        }
        if (!isEmojiIcon(value)) {
            throw new IllegalArgumentException(I18n.t("meshIde.service.iconEmoji"));
        }
        return value;
    }

    public static long normalizeVersion(long version) {
        return version > 0 ? version : DEFAULT_VERSION;
    }

    public static String normalizeDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.replace("\r\n", "\n").replace('\r', '\n');
    }

    public static String normalizeAuthor(String author) {
        if (author == null) {
            return "";
        }
        String value = author.replace("\r\n", " ")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static boolean isEmojiZwjSequence(int[] codePoints) {
        boolean expectingEmoji = true;
        boolean sawEmoji = false;
        boolean lastWasEmoji = false;
        for (int i = 0; i < codePoints.length; i++) {
            int codePoint = codePoints[i];
            if (isVariationSelector(codePoint) || isEmojiModifier(codePoint)) {
                if (!lastWasEmoji) {
                    return false;
                }
                continue;
            }
            if (codePoint == ZERO_WIDTH_JOINER) {
                if (!lastWasEmoji || i == codePoints.length - 1) {
                    return false;
                }
                expectingEmoji = true;
                lastWasEmoji = false;
                continue;
            }
            if (!isEmojiBase(codePoint) || !expectingEmoji) {
                return false;
            }
            sawEmoji = true;
            expectingEmoji = false;
            lastWasEmoji = true;
        }
        return sawEmoji && !expectingEmoji;
    }

    private static boolean isKeycapSequence(int[] codePoints) {
        if (codePoints.length == 2) {
            return isKeycapBase(codePoints[0]) && codePoints[1] == KEYCAP_MARK;
        }
        return codePoints.length == 3
                && isKeycapBase(codePoints[0])
                && codePoints[1] == VARIATION_SELECTOR_EMOJI
                && codePoints[2] == KEYCAP_MARK;
    }

    private static boolean isRegionalFlagSequence(int[] codePoints) {
        return codePoints.length == 2
                && isRegionalIndicator(codePoints[0])
                && isRegionalIndicator(codePoints[1]);
    }

    private static boolean isEmojiBase(int codePoint) {
        return (codePoint >= 0x1F300 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || codePoint == 0x00A9
                || codePoint == 0x00AE
                || codePoint == 0x203C
                || codePoint == 0x2049
                || codePoint == 0x2122
                || codePoint == 0x2139
                || codePoint == 0x3030
                || codePoint == 0x303D
                || codePoint == 0x3297
                || codePoint == 0x3299;
    }

    private static boolean isVariationSelector(int codePoint) {
        return codePoint == VARIATION_SELECTOR_TEXT || codePoint == VARIATION_SELECTOR_EMOJI;
    }

    private static boolean isEmojiModifier(int codePoint) {
        return codePoint >= 0x1F3FB && codePoint <= 0x1F3FF;
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF;
    }

    private static boolean isKeycapBase(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9') || codePoint == '#' || codePoint == '*';
    }

    /** Script type that determines how MeshApp uses it. */
    public enum BotType {
        AIR_BOT("AIR_BOT", "meshIde.botType.air"),
        AUTOMATION_BOT("AUTOMATION_BOT", "meshIde.botType.automation");

        private final String storageValue;
        private final String displayKey;

        BotType(String storageValue, String displayKey) {
            this.storageValue = storageValue;
            this.displayKey = displayKey;
        }

        public String getStorageValue() { return storageValue; }
        public String getDisplayName() { return I18n.t(displayKey); }

        public static BotType fromStorage(String value) {
            if (value != null) {
                for (BotType type : values()) {
                    if (type.storageValue.equalsIgnoreCase(value.trim())) {
                        return type;
                    }
                }
            }
            return AIR_BOT;
        }

        @Override
        public String toString() {
            return getDisplayName();
        }
    }

    private final long id;
    private final String guid;
    private String icon;
    private String name;
    private String code;
    private long version;
    private String description;
    private String author;
    private boolean enabled;
    private String nodeId;
    private BotType botType;
    private String automationName;
    private final long createdAt;
    private long updatedAt;
    private long lastRunAt;
    private String lastStatus;
    private String lastError;

    public LuaScript(long id,
                     String guid,
                     String icon,
                     String name,
                     String code,
                     long version,
                     String description,
                     String author,
                     boolean enabled,
                     String nodeId,
                     BotType botType,
                     String automationName,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this.id = id;
        this.guid = guid != null ? guid : "";
        this.icon = normalizeIcon(icon);
        this.name = name;
        this.code = code;
        this.version = normalizeVersion(version);
        this.description = normalizeDescription(description);
        this.author = normalizeAuthor(author);
        this.enabled = enabled;
        this.nodeId = nodeId;
        this.botType = botType != null ? botType : BotType.AIR_BOT;
        this.automationName = automationName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastRunAt = lastRunAt;
        this.lastStatus = lastStatus;
        this.lastError = lastError;
    }

    public LuaScript(long id,
                     String guid,
                     String icon,
                     String name,
                     String code,
                     long version,
                     String description,
                     boolean enabled,
                     String nodeId,
                     BotType botType,
                     String automationName,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this(id, guid, icon, name, code, version, description, "", enabled, nodeId, botType, automationName,
                createdAt, updatedAt, lastRunAt, lastStatus, lastError);
    }

    public LuaScript(long id,
                     String guid,
                     String icon,
                     String name,
                     String code,
                     boolean enabled,
                     String nodeId,
                     BotType botType,
                     String automationName,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this(id, guid, icon, name, code, DEFAULT_VERSION, "", "", enabled, nodeId, botType, automationName,
                createdAt, updatedAt, lastRunAt, lastStatus, lastError);
    }

    public LuaScript(long id,
                     String guid,
                     String name,
                     String code,
                     boolean enabled,
                     String nodeId,
                     BotType botType,
                     String automationName,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this(id, guid, DEFAULT_ICON, name, code, enabled, nodeId, botType, automationName,
                createdAt, updatedAt, lastRunAt, lastStatus, lastError);
    }

    public LuaScript(long id,
                     String name,
                     String code,
                     boolean enabled,
                     String nodeId,
                     BotType botType,
                     String automationName,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this(id, "", DEFAULT_ICON, name, code, enabled, nodeId, botType, automationName,
                createdAt, updatedAt, lastRunAt, lastStatus, lastError);
    }

    public LuaScript(long id,
                     String name,
                     String code,
                     boolean enabled,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this(id, name, code, enabled, "", BotType.AIR_BOT, "", createdAt, updatedAt, lastRunAt, lastStatus, lastError);
    }

    public long getId() { return id; }

    public String getGuid() { return guid; }

    public String getIcon() { return normalizeIcon(icon); }
    public void setIcon(String icon) { this.icon = requireValidIcon(icon); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public long getVersion() { return normalizeVersion(version); }
    public void setVersion(long version) { this.version = normalizeVersion(version); }

    public String getDescription() { return normalizeDescription(description); }
    public void setDescription(String description) { this.description = normalizeDescription(description); }

    public String getAuthor() { return normalizeAuthor(author); }
    public void setAuthor(String author) { this.author = normalizeAuthor(author); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isAutostart() { return enabled; }
    public void setAutostart(boolean autostart) { this.enabled = autostart; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public BotType getBotType() { return botType != null ? botType : BotType.AIR_BOT; }
    public void setBotType(BotType botType) { this.botType = botType != null ? botType : BotType.AIR_BOT; }

    public String getAutomationName() { return automationName; }
    public void setAutomationName(String automationName) { this.automationName = automationName; }

    public long getCreatedAt() { return createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(long lastRunAt) { this.lastRunAt = lastRunAt; }

    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    @Override
    public String toString() {
        return name == null || name.isBlank() ? I18n.t("meshIde.script.fallback", Long.toString(id)) : name;
    }
}
