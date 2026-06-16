package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaRemoteAdminBridge;
import com.meshtastic.client.lua.LuaRemoteAdminRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.meshtastic.proto.AdminProtos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuaRemoteAdminApiTest {

    private RecordingRemoteAdminBridge bridge;
    private LuaTable admin;

    @BeforeEach
    void setUp() {
        bridge = new RecordingRemoteAdminBridge();
        admin = new LuaRemoteAdminApi(new LuaSandboxContext(
                7L,
                "test",
                null,
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                bridge,
                null,
                null,
                null)).create();
    }

    @Test
    void restoreAcceptsConfirmOptionsAsSecondArgument() {
        LuaTable options = new LuaTable();
        options.set("confirm", LuaValue.TRUE);
        options.set("name", "restore-default");

        String requestId = admin.get("restore")
                .invoke(LuaValue.varargsOf(LuaValue.valueOf("!abcdef12"), options))
                .arg1()
                .checkjstring();

        LuaRemoteAdminRequest request = bridge.requests.get(0);
        assertEquals("req-1", requestId);
        assertEquals(LuaRemoteAdminRequest.Action.RESTORE, request.action());
        assertEquals(AdminProtos.AdminMessage.BackupLocation.FLASH, request.backupLocation());
        assertEquals("restore-default", request.name());
        assertEquals("!abcdef12", request.targetNodeId());
    }

    @Test
    void delayedCommandsAcceptOptionsAsSecondArgument() {
        LuaTable options = new LuaTable();
        options.set("name", "restart-later");

        admin.get("reboot")
                .invoke(LuaValue.varargsOf(LuaValue.valueOf("!abcdef12"), options));

        LuaRemoteAdminRequest request = bridge.requests.get(0);
        assertEquals(LuaRemoteAdminRequest.Action.REBOOT, request.action());
        assertEquals(5, request.delaySeconds());
        assertEquals("restart-later", request.name());
    }

    @Test
    void destructiveCommandRequiresConfirm() {
        assertThrows(LuaError.class, () -> admin.get("restore").call(LuaValue.valueOf("!abcdef12")));
    }

    private static final class RecordingRemoteAdminBridge implements LuaRemoteAdminBridge {
        private final AtomicInteger counter = new AtomicInteger();
        private final List<LuaRemoteAdminRequest> requests = new ArrayList<>();

        @Override
        public boolean isRemoteAdminAvailable() {
            return true;
        }

        @Override
        public String nextRemoteAdminRequestId() {
            return "req-" + counter.incrementAndGet();
        }

        @Override
        public void requestRemoteAdmin(LuaRemoteAdminRequest request) {
            requests.add(request);
        }
    }
}
