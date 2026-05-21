package com.meshtastic.client.lua;

/**
 * Модель Lua-скрипта MeshApp, сохраненного в БД приложения.
 * <p>
 * Содержит исходный код, пользовательские параметры включения и метаданные
 * последнего запуска, которые отображаются в списке скриптов MeshApp IDE.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class LuaScript {

    /** Тип сценария, определяющий способ его использования в MeshApp. */
    public enum BotType {
        AIR_BOT("AIR_BOT", "Эфирный бот"),
        AUTOMATION_BOT("AUTOMATION_BOT", "Бот автоматизации");

        private final String storageValue;
        private final String displayName;

        BotType(String storageValue, String displayName) {
            this.storageValue = storageValue;
            this.displayName = displayName;
        }

        public String getStorageValue() { return storageValue; }
        public String getDisplayName() { return displayName; }

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
            return displayName;
        }
    }

    private final long id;
    private String name;
    private String code;
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
        this.id = id;
        this.name = name;
        this.code = code;
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

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
        return name == null || name.isBlank() ? "Lua script " + id : name;
    }
}
