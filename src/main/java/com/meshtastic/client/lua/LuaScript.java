package com.meshtastic.client.lua;

public class LuaScript {

    private final long id;
    private String name;
    private String code;
    private boolean enabled;
    private final long createdAt;
    private long updatedAt;
    private long lastRunAt;
    private String lastStatus;
    private String lastError;

    public LuaScript(long id,
                     String name,
                     String code,
                     boolean enabled,
                     long createdAt,
                     long updatedAt,
                     long lastRunAt,
                     String lastStatus,
                     String lastError) {
        this.id = id;
        this.name = name;
        this.code = code;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastRunAt = lastRunAt;
        this.lastStatus = lastStatus;
        this.lastError = lastError;
    }

    public long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

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
