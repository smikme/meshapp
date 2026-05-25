package com.meshtastic.client.lua;

import com.meshtastic.client.service.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Сервис хранения Lua-скриптов MeshApp и их изолированных KV-хранилищ.
 * <p>
 * Инкапсулирует таблицы БД приложения для исходного кода, статусов запуска,
 * ошибок выполнения и key-value данных, принадлежащих конкретному скрипту.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaScriptService {

    private static final Logger log = LoggerFactory.getLogger(LuaScriptService.class);
    private static final String DEFAULT_SCRIPT_CODE = """
            -- mesh: safe application API
            -- Called for every new chat message while the script is running.
            function on_message(msg)
                if msg.outgoing then
                    return
                end
                mesh.log("message from " .. tostring(msg.from) .. ": " .. tostring(msg.text))
            end
            """;

    private static LuaScriptService instance;

    private final Connection dbConnection;

    private LuaScriptService() {
        this.dbConnection = DatabaseProvider.getConnection();
        initDb();
    }

    public static synchronized LuaScriptService getInstance() {
        if (instance == null) {
            instance = new LuaScriptService();
        }
        return instance;
    }

    private void initDb() {
        if (dbConnection == null) {
            return;
        }
        try (Statement stmt = dbConnection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lua_scripts (
                        id          BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name        VARCHAR(120) NOT NULL,
                        code        CLOB NOT NULL,
                        enabled     BOOLEAN NOT NULL DEFAULT TRUE,
                        node_id     VARCHAR(60) NOT NULL DEFAULT '',
                        bot_type    VARCHAR(30) NOT NULL DEFAULT 'AIR_BOT',
                        automation_name VARCHAR(80) NOT NULL DEFAULT '',
                        created_at  BIGINT NOT NULL,
                        updated_at  BIGINT NOT NULL,
                        last_run_at BIGINT DEFAULT 0,
                        last_status VARCHAR(20),
                        last_error  CLOB
                    )
                    """);
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_lua_scripts_name
                    ON lua_scripts (name)
                    """);
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS node_id VARCHAR(60) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS bot_type VARCHAR(30) NOT NULL DEFAULT 'AIR_BOT'");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS automation_name VARCHAR(80) NOT NULL DEFAULT ''");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS lua_script_kv (
                        script_id  BIGINT NOT NULL,
                        key_name   VARCHAR(200) NOT NULL,
                        value_text CLOB,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (script_id, key_name),
                        CONSTRAINT fk_lua_script_kv_script
                            FOREIGN KEY (script_id) REFERENCES lua_scripts(id)
                            ON DELETE CASCADE
                    )
                    """);
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_lua_script_kv_script
                    ON lua_script_kv (script_id)
                    """);
        } catch (SQLException e) {
            log.error("Failed to initialize Lua script tables", e);
        }
    }

    public synchronized List<LuaScript> listScripts() {
        List<LuaScript> result = new ArrayList<>();
        if (dbConnection == null) {
            return result;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT * FROM lua_scripts ORDER BY updated_at DESC, id DESC
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(readScript(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list Lua scripts", e);
        }
        return result;
    }

    public synchronized Optional<LuaScript> findScript(long scriptId) {
        if (dbConnection == null || scriptId <= 0) {
            return Optional.empty();
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT * FROM lua_scripts WHERE id = ?
                """)) {
            ps.setLong(1, scriptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readScript(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to find Lua script {}", scriptId, e);
            return Optional.empty();
        }
    }

    public synchronized LuaScript createScript() {
        return createScript(nextDefaultName(), DEFAULT_SCRIPT_CODE);
    }

    public synchronized LuaScript createDraftScript() {
        long now = nowSeconds();
        return new LuaScript(
                0L,
                nextDefaultName(),
                DEFAULT_SCRIPT_CODE,
                true,
                "",
                LuaScript.BotType.AIR_BOT,
                "",
                now,
                now,
                0L,
                "NEW",
                null);
    }

    public synchronized LuaScript createScript(String name, String code) {
        return createScript(name, code, true, "", LuaScript.BotType.AIR_BOT, "");
    }

    public synchronized LuaScript createScript(String name,
                                               String code,
                                               boolean enabled,
                                               String nodeId,
                                               LuaScript.BotType botType,
                                               String automationName) {
        if (dbConnection == null) {
            throw new IllegalStateException("Database connection is not available");
        }
        LuaScript.BotType normalizedType = botType != null ? botType : LuaScript.BotType.AIR_BOT;
        long now = nowSeconds();
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                INSERT INTO lua_scripts (name, code, enabled, node_id, bot_type, automation_name,
                                         created_at, updated_at, last_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normalizeName(name));
            ps.setString(2, code != null ? code : "");
            ps.setBoolean(3, enabled);
            ps.setString(4, normalizeNodeId(nodeId));
            ps.setString(5, normalizedType.getStorageValue());
            ps.setString(6, normalizeAutomationName(normalizedType, automationName));
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.setString(9, "NEW");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return findScript(keys.getLong(1)).orElseThrow();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create Lua script", e);
        }
        throw new IllegalStateException("Failed to create Lua script");
    }

    public synchronized LuaScript saveScript(long scriptId, String name, String code, boolean enabled) {
        LuaScript existing = findScript(scriptId).orElse(null);
        String nodeId = existing != null ? existing.getNodeId() : "";
        LuaScript.BotType botType = existing != null ? existing.getBotType() : LuaScript.BotType.AIR_BOT;
        String automationName = existing != null ? existing.getAutomationName() : "";
        return saveScript(scriptId, name, code, enabled, nodeId, botType, automationName);
    }

    public synchronized LuaScript saveScript(long scriptId,
                                             String name,
                                             String code,
                                             boolean enabled,
                                             String nodeId,
                                             LuaScript.BotType botType,
                                             String automationName) {
        if (dbConnection == null || scriptId <= 0) {
            throw new IllegalStateException("Database connection is not available");
        }
        LuaScript.BotType normalizedType = botType != null ? botType : LuaScript.BotType.AIR_BOT;
        long now = nowSeconds();
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts
                SET name = ?,
                    code = ?,
                    enabled = ?,
                    node_id = ?,
                    bot_type = ?,
                    automation_name = ?,
                    updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, normalizeName(name));
            ps.setString(2, code != null ? code : "");
            ps.setBoolean(3, enabled);
            ps.setString(4, normalizeNodeId(nodeId));
            ps.setString(5, normalizedType.getStorageValue());
            ps.setString(6, normalizeAutomationName(normalizedType, automationName));
            ps.setLong(7, now);
            ps.setLong(8, scriptId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException("Lua script not found: " + scriptId);
            }
            return findScript(scriptId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save Lua script", e);
        }
    }

    public synchronized LuaScript saveScriptSettings(long scriptId,
                                                     String name,
                                                     boolean autostart,
                                                     String nodeId,
                                                     LuaScript.BotType botType,
                                                     String automationName) {
        LuaScript existing = findScript(scriptId)
                .orElseThrow(() -> new IllegalStateException("Lua script not found: " + scriptId));
        return saveScript(scriptId, name, existing.getCode(), autostart, nodeId, botType, automationName);
    }

    public synchronized void deleteScript(long scriptId) {
        if (dbConnection == null || scriptId <= 0) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                DELETE FROM lua_scripts WHERE id = ?
                """)) {
            ps.setLong(1, scriptId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete Lua script {}", scriptId, e);
        }
    }

    public synchronized void updateRunState(long scriptId, String status, String error) {
        if (dbConnection == null || scriptId <= 0) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts
                SET last_run_at = ?, last_status = ?, last_error = ?
                WHERE id = ?
                """)) {
            ps.setLong(1, nowSeconds());
            ps.setString(2, status);
            ps.setString(3, error);
            ps.setLong(4, scriptId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update Lua script run state {}", scriptId, e);
        }
    }

    public synchronized String getKv(long scriptId, String key) {
        if (dbConnection == null || scriptId <= 0 || key == null) {
            return null;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT value_text FROM lua_script_kv WHERE script_id = ? AND key_name = ?
                """)) {
            ps.setLong(1, scriptId);
            ps.setString(2, normalizeKey(key));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value_text") : null;
            }
        } catch (SQLException e) {
            log.error("Failed to get Lua KV script={}, key={}", scriptId, key, e);
            return null;
        }
    }

    public synchronized void setKv(long scriptId, String key, String value) {
        if (dbConnection == null || scriptId <= 0 || key == null) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                MERGE INTO lua_script_kv (script_id, key_name, value_text, updated_at)
                KEY (script_id, key_name)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setLong(1, scriptId);
            ps.setString(2, normalizeKey(key));
            ps.setString(3, value);
            ps.setLong(4, nowSeconds());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to set Lua KV script={}, key={}", scriptId, key, e);
        }
    }

    public synchronized boolean deleteKv(long scriptId, String key) {
        if (dbConnection == null || scriptId <= 0 || key == null) {
            return false;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                DELETE FROM lua_script_kv WHERE script_id = ? AND key_name = ?
                """)) {
            ps.setLong(1, scriptId);
            ps.setString(2, normalizeKey(key));
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete Lua KV script={}, key={}", scriptId, key, e);
            return false;
        }
    }

    public synchronized Map<String, String> listKv(long scriptId) {
        Map<String, String> result = new TreeMap<>();
        if (dbConnection == null || scriptId <= 0) {
            return result;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT key_name, value_text FROM lua_script_kv
                WHERE script_id = ?
                ORDER BY key_name
                """)) {
            ps.setLong(1, scriptId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("key_name"), rs.getString("value_text"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list Lua KV script={}", scriptId, e);
        }
        return result;
    }

    public synchronized void clearKv(long scriptId) {
        if (dbConnection == null || scriptId <= 0) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                DELETE FROM lua_script_kv WHERE script_id = ?
                """)) {
            ps.setLong(1, scriptId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to clear Lua KV script={}", scriptId, e);
        }
    }

    private LuaScript readScript(ResultSet rs) throws SQLException {
        return new LuaScript(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getBoolean("enabled"),
                rs.getString("node_id"),
                LuaScript.BotType.fromStorage(rs.getString("bot_type")),
                rs.getString("automation_name"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("last_run_at"),
                rs.getString("last_status"),
                rs.getString("last_error")
        );
    }

    private String nextDefaultName() {
        int index = 1;
        List<String> names = listScripts().stream().map(LuaScript::getName).toList();
        while (names.contains("Новый скрипт " + index)) {
            index++;
        }
        return "Новый скрипт " + index;
    }

    private static String normalizeName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Script name is required");
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String normalizeNodeId(String nodeId) {
        String value = nodeId == null ? "" : nodeId.trim();
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private static String normalizeAutomationName(LuaScript.BotType botType, String automationName) {
        String value = automationName == null ? "" : automationName.trim();
        if (botType != LuaScript.BotType.AUTOMATION_BOT) {
            return "";
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Automation bot name is required");
        }
        if (!value.matches("@[\\p{L}\\p{N}_]+")) {
            throw new IllegalArgumentException("Automation bot name must match @имя_бота");
        }
        return value.length() > 80 ? value.substring(0, 80) : value;
    }

    private static String normalizeKey(String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("KV key is required");
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000;
    }
}
