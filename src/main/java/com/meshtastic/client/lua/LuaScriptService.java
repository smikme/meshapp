package com.meshtastic.client.lua;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.meshtastic.client.service.DatabaseProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

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
    private static final String EXPORT_FORMAT = "meshapp-lua-script";
    private static final int EXPORT_VERSION = 1;
    private static final Gson SCRIPT_JSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();
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
                        guid        VARCHAR(36) NOT NULL DEFAULT '',
                        icon        VARCHAR(32) NOT NULL DEFAULT '🤖',
                        name        VARCHAR(120) NOT NULL,
                        code        CLOB NOT NULL,
                        version     BIGINT NOT NULL DEFAULT 1,
                        description CLOB NOT NULL,
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
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS guid VARCHAR(36) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS icon VARCHAR(32) NOT NULL DEFAULT '🤖'");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 1");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS description CLOB");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS node_id VARCHAR(60) NOT NULL DEFAULT ''");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS bot_type VARCHAR(30) NOT NULL DEFAULT 'AIR_BOT'");
            stmt.execute("ALTER TABLE lua_scripts ADD COLUMN IF NOT EXISTS automation_name VARCHAR(80) NOT NULL DEFAULT ''");
            backfillScriptGuids();
            backfillScriptIcons();
            backfillScriptDescriptions();
            stmt.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_lua_scripts_guid
                    ON lua_scripts (guid)
                    """);
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

    public synchronized Optional<LuaScript> findScriptByGuid(String guid) {
        String normalizedGuid = normalizeGuid(guid);
        if (dbConnection == null || normalizedGuid.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                SELECT * FROM lua_scripts WHERE guid = ?
                """)) {
            ps.setString(1, normalizedGuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readScript(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to find Lua script by GUID {}", normalizedGuid, e);
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
                "",
                LuaScript.DEFAULT_ICON,
                nextDefaultName(),
                DEFAULT_SCRIPT_CODE,
                LuaScript.DEFAULT_VERSION,
                "",
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
        return createScript(name, code, enabled, LuaScript.DEFAULT_ICON, nodeId, botType, automationName);
    }

    public synchronized LuaScript createScript(String name,
                                               String code,
                                               boolean enabled,
                                               String icon,
                                               String nodeId,
                                               LuaScript.BotType botType,
                                               String automationName) {
        return createScript(name, code, enabled, icon, nodeId, botType, automationName, "");
    }

    public synchronized LuaScript createScript(String name,
                                               String code,
                                               boolean enabled,
                                               String icon,
                                               String nodeId,
                                               LuaScript.BotType botType,
                                               String automationName,
                                               String description) {
        return createScriptWithGuid(newGuid(), name, code, enabled, icon, nodeId, botType, automationName, description);
    }

    private LuaScript createScriptWithGuid(String preferredGuid,
                                           String name,
                                           String code,
                                           boolean enabled,
                                           String icon,
                                           String nodeId,
                                           LuaScript.BotType botType,
                                           String automationName) {
        return createScriptWithGuid(preferredGuid, name, code, enabled, icon, nodeId, botType, automationName,
                LuaScript.DEFAULT_VERSION, "");
    }

    private LuaScript createScriptWithGuid(String preferredGuid,
                                           String name,
                                           String code,
                                           boolean enabled,
                                           String icon,
                                           String nodeId,
                                           LuaScript.BotType botType,
                                           String automationName,
                                           String description) {
        return createScriptWithGuid(preferredGuid, name, code, enabled, icon, nodeId, botType, automationName,
                LuaScript.DEFAULT_VERSION, description);
    }

    private LuaScript createScriptWithGuid(String preferredGuid,
                                           String name,
                                           String code,
                                           boolean enabled,
                                           String icon,
                                           String nodeId,
                                           LuaScript.BotType botType,
                                           String automationName,
                                           long version,
                                           String description) {
        if (dbConnection == null) {
            throw new IllegalStateException("Database connection is not available");
        }
        LuaScript.BotType normalizedType = botType != null ? botType : LuaScript.BotType.AIR_BOT;
        long now = nowSeconds();
        String guid = normalizeGuid(preferredGuid);
        if (guid.isBlank()) {
            guid = newGuid();
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                INSERT INTO lua_scripts (guid, icon, name, code, version, description,
                                         enabled, node_id, bot_type, automation_name,
                                         created_at, updated_at, last_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, guid);
            ps.setString(2, normalizeIcon(icon));
            ps.setString(3, normalizeName(name));
            ps.setString(4, normalizeCode(code));
            ps.setLong(5, LuaScript.normalizeVersion(version));
            ps.setString(6, normalizeDescription(description));
            ps.setBoolean(7, enabled);
            ps.setString(8, normalizeNodeId(normalizedType, nodeId));
            ps.setString(9, normalizedType.getStorageValue());
            ps.setString(10, normalizeAutomationName(normalizedType, automationName));
            ps.setLong(11, now);
            ps.setLong(12, now);
            ps.setString(13, "NEW");
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

    public synchronized void exportScript(long scriptId, Path targetPath) throws IOException {
        LuaScript script = findScript(scriptId)
                .orElseThrow(() -> new IllegalStateException("Lua script not found: " + scriptId));
        LuaScriptExportFile exportFile = LuaScriptExportFile.from(script);
        Files.writeString(targetPath, SCRIPT_JSON.toJson(exportFile), StandardCharsets.UTF_8);
    }

    public synchronized ScriptImportResult importScript(Path sourcePath) throws IOException {
        LuaScriptExportFile exportFile;
        try {
            exportFile = SCRIPT_JSON.fromJson(Files.readString(sourcePath, StandardCharsets.UTF_8),
                    LuaScriptExportFile.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Некорректный JSON-файл скрипта", e);
        }
        return importScript(exportFile);
    }

    private ScriptImportResult importScript(LuaScriptExportFile exportFile) {
        if (exportFile == null) {
            throw new IllegalArgumentException("Файл скрипта пуст");
        }
        if (!EXPORT_FORMAT.equals(exportFile.format())) {
            throw new IllegalArgumentException("Файл не является экспортом Lua-скрипта MeshApp");
        }
        if (exportFile.version() > EXPORT_VERSION) {
            throw new IllegalArgumentException("Версия файла скрипта не поддерживается: " + exportFile.version());
        }

        String guid = normalizeGuid(exportFile.guid());
        String name = normalizeName(exportFile.name());
        String icon = normalizeIcon(exportFile.icon());
        long scriptVersion = LuaScript.normalizeVersion(exportFile.scriptVersion());
        String description = normalizeDescription(exportFile.description());
        LuaScript.BotType botType = LuaScript.BotType.fromStorage(exportFile.botType());
        String nodeId = normalizeNodeId(botType, exportFile.nodeId());
        String automationName = normalizeAutomationName(botType, exportFile.automationName());
        String code = exportFile.codeText();
        boolean autostart = Boolean.TRUE.equals(exportFile.autostart());

        if (!guid.isBlank()) {
            Optional<LuaScript> existing = findScriptByGuid(guid);
            if (existing.isPresent()) {
                LuaScript saved = saveScript(
                        existing.get().getId(),
                        name,
                        code,
                        autostart,
                        icon,
                        nodeId,
                        botType,
                        automationName,
                        description);
                return new ScriptImportResult(saved, true);
            }
        }

        LuaScript created = createScriptWithGuid(
                guid,
                name,
                code,
                autostart,
                icon,
                nodeId,
                botType,
                automationName,
                scriptVersion,
                description);
        return new ScriptImportResult(created, false);
    }

    public synchronized LuaScript saveScript(long scriptId, String name, String code, boolean enabled) {
        LuaScript existing = findScript(scriptId).orElse(null);
        String nodeId = existing != null ? existing.getNodeId() : "";
        LuaScript.BotType botType = existing != null ? existing.getBotType() : LuaScript.BotType.AIR_BOT;
        String automationName = existing != null ? existing.getAutomationName() : "";
        String icon = existing != null ? existing.getIcon() : LuaScript.DEFAULT_ICON;
        String description = existing != null ? existing.getDescription() : "";
        return saveScript(scriptId, name, code, enabled, icon, nodeId, botType, automationName, description);
    }

    public synchronized LuaScript saveScript(long scriptId,
                                             String name,
                                             String code,
                                             boolean enabled,
                                             String nodeId,
                                             LuaScript.BotType botType,
                                             String automationName) {
        LuaScript existing = findScript(scriptId).orElse(null);
        String icon = existing != null ? existing.getIcon() : LuaScript.DEFAULT_ICON;
        String description = existing != null ? existing.getDescription() : "";
        return saveScript(scriptId, name, code, enabled, icon, nodeId, botType, automationName, description);
    }

    public synchronized LuaScript saveScript(long scriptId,
                                             String name,
                                             String code,
                                             boolean enabled,
                                             String icon,
                                             String nodeId,
                                             LuaScript.BotType botType,
                                             String automationName) {
        LuaScript existing = findScript(scriptId).orElse(null);
        String description = existing != null ? existing.getDescription() : "";
        return saveScript(scriptId, name, code, enabled, icon, nodeId, botType, automationName, description);
    }

    public synchronized LuaScript saveScript(long scriptId,
                                             String name,
                                             String code,
                                             boolean enabled,
                                             String icon,
                                             String nodeId,
                                             LuaScript.BotType botType,
                                             String automationName,
                                             String description) {
        if (dbConnection == null || scriptId <= 0) {
            throw new IllegalStateException("Database connection is not available");
        }
        LuaScript.BotType normalizedType = botType != null ? botType : LuaScript.BotType.AIR_BOT;
        LuaScript existing = findScript(scriptId)
                .orElseThrow(() -> new IllegalStateException("Lua script not found: " + scriptId));
        String normalizedName = normalizeName(name);
        String normalizedCode = normalizeCode(code);
        String normalizedIcon = normalizeIcon(icon);
        String normalizedNodeId = normalizeNodeId(normalizedType, nodeId);
        String normalizedAutomationName = normalizeAutomationName(normalizedType, automationName);
        String normalizedDescription = normalizeDescription(description);
        boolean modified = !Objects.equals(existing.getName(), normalizedName)
                || !Objects.equals(existing.getCode(), normalizedCode)
                || existing.isEnabled() != enabled
                || !Objects.equals(existing.getIcon(), normalizedIcon)
                || !Objects.equals(existing.getNodeId(), normalizedNodeId)
                || existing.getBotType() != normalizedType
                || !Objects.equals(existing.getAutomationName(), normalizedAutomationName)
                || !Objects.equals(existing.getDescription(), normalizedDescription);
        if (!modified) {
            return existing;
        }
        long now = nowSeconds();
        long nextVersion = existing.getVersion() + 1;
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts
                SET name = ?,
                    code = ?,
                    enabled = ?,
                    icon = ?,
                    node_id = ?,
                    bot_type = ?,
                    automation_name = ?,
                    description = ?,
                    version = ?,
                    updated_at = ?
                WHERE id = ?
                """)) {
            ps.setString(1, normalizedName);
            ps.setString(2, normalizedCode);
            ps.setBoolean(3, enabled);
            ps.setString(4, normalizedIcon);
            ps.setString(5, normalizedNodeId);
            ps.setString(6, normalizedType.getStorageValue());
            ps.setString(7, normalizedAutomationName);
            ps.setString(8, normalizedDescription);
            ps.setLong(9, nextVersion);
            ps.setLong(10, now);
            ps.setLong(11, scriptId);
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
        return saveScript(scriptId, name, existing.getCode(), autostart, existing.getIcon(), nodeId, botType, automationName,
                existing.getDescription());
    }

    public synchronized LuaScript saveScriptSettings(long scriptId,
                                                     String name,
                                                     boolean autostart,
                                                     String icon,
                                                     String nodeId,
                                                     LuaScript.BotType botType,
                                                     String automationName) {
        LuaScript existing = findScript(scriptId)
                .orElseThrow(() -> new IllegalStateException("Lua script not found: " + scriptId));
        return saveScript(scriptId, name, existing.getCode(), autostart, icon, nodeId, botType, automationName,
                existing.getDescription());
    }

    public synchronized LuaScript saveScriptSettings(long scriptId,
                                                     String name,
                                                     boolean autostart,
                                                     String icon,
                                                     String nodeId,
                                                     LuaScript.BotType botType,
                                                     String automationName,
                                                     String description) {
        LuaScript existing = findScript(scriptId)
                .orElseThrow(() -> new IllegalStateException("Lua script not found: " + scriptId));
        return saveScript(scriptId, name, existing.getCode(), autostart, icon, nodeId, botType, automationName,
                description);
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
                rs.getString("guid"),
                rs.getString("icon"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getLong("version"),
                rs.getString("description"),
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

    private void backfillScriptGuids() throws SQLException {
        List<GuidUpdate> updates = new ArrayList<>();
        Set<String> usedGuids = new HashSet<>();
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, guid FROM lua_scripts ORDER BY id")) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String rawGuid = rs.getString("guid");
                String normalizedGuid = normalizeGuid(rawGuid);
                if (normalizedGuid.isBlank() || !usedGuids.add(normalizedGuid)) {
                    updates.add(new GuidUpdate(id, newGuid(usedGuids)));
                } else if (!normalizedGuid.equals(rawGuid)) {
                    updates.add(new GuidUpdate(id, normalizedGuid));
                }
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts SET guid = ? WHERE id = ?
                """)) {
            for (GuidUpdate update : updates) {
                ps.setString(1, update.guid());
                ps.setLong(2, update.scriptId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void backfillScriptIcons() throws SQLException {
        List<Long> invalidIconScriptIds = new ArrayList<>();
        try (Statement stmt = dbConnection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, icon FROM lua_scripts ORDER BY id")) {
            while (rs.next()) {
                String icon = rs.getString("icon");
                if (!LuaScript.isEmojiIcon(icon)) {
                    invalidIconScriptIds.add(rs.getLong("id"));
                }
            }
        }
        if (!invalidIconScriptIds.isEmpty()) {
            try (PreparedStatement ps = dbConnection.prepareStatement("""
                    UPDATE lua_scripts SET icon = ? WHERE id = ?
                    """)) {
                for (Long scriptId : invalidIconScriptIds) {
                    ps.setString(1, LuaScript.DEFAULT_ICON);
                    ps.setLong(2, scriptId);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts SET icon = ?
                WHERE icon IS NULL OR TRIM(icon) = ''
                """)) {
            ps.setString(1, LuaScript.DEFAULT_ICON);
            ps.executeUpdate();
        }
    }

    private void backfillScriptDescriptions() throws SQLException {
        try (PreparedStatement ps = dbConnection.prepareStatement("""
                UPDATE lua_scripts SET description = ''
                WHERE description IS NULL
                """)) {
            ps.executeUpdate();
        }
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

    private static String normalizeIcon(String icon) {
        return LuaScript.requireValidIcon(icon);
    }

    private static String normalizeCode(String code) {
        return code != null ? code : "";
    }

    private static String normalizeDescription(String description) {
        return LuaScript.normalizeDescription(description);
    }

    private static String normalizeNodeId(String nodeId) {
        String value = nodeId == null ? "" : nodeId.trim();
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private static String normalizeNodeId(LuaScript.BotType botType, String nodeId) {
        if (botType == LuaScript.BotType.AUTOMATION_BOT) {
            return "";
        }
        return normalizeNodeId(nodeId);
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

    private static String normalizeGuid(String guid) {
        if (guid == null || guid.isBlank()) {
            return "";
        }
        try {
            return UUID.fromString(guid.trim()).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String newGuid() {
        return UUID.randomUUID().toString();
    }

    private static String newGuid(Set<String> usedGuids) {
        String guid;
        do {
            guid = newGuid();
        } while (!usedGuids.add(guid));
        return guid;
    }

    private record GuidUpdate(long scriptId, String guid) {}

    public record ScriptImportResult(LuaScript script, boolean updated) {}

    /**
     * JSON-представление Lua-скрипта для переноса между установками MeshApp.
     *
     * @author Konstantin A. Smirnov (ks@privatepractice.app)
     */
    public record LuaScriptExportFile(String format,
                                      int version,
                                      long scriptVersion,
                                      String guid,
                                      String icon,
                                      String name,
                                      String description,
                                      String code,
                                      List<String> codeLines,
                                      Boolean autostart,
                                      String nodeId,
                                      String botType,
                                      String automationName) {
        private String codeText() {
            if (codeLines != null) {
                return String.join("\n", codeLines);
            }
            return code != null ? code : "";
        }

        private static LuaScriptExportFile from(LuaScript script) {
            return new LuaScriptExportFile(
                    EXPORT_FORMAT,
                    EXPORT_VERSION,
                    script.getVersion(),
                    script.getGuid(),
                    script.getIcon(),
                    script.getName(),
                    script.getDescription(),
                    null,
                    splitCodeLines(script.getCode()),
                    script.isAutostart(),
                    script.getNodeId(),
                    script.getBotType().getStorageValue(),
                    script.getAutomationName());
        }

        private static List<String> splitCodeLines(String code) {
            String value = code != null ? code : "";
            return List.of(value.split("\\R", -1));
        }
    }
}
