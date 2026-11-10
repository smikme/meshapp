package com.meshtastic.client.utils;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Low-overhead stage timings, recorded only while Java Flight Recorder is active. */
@Name("com.meshapp.ChatHistoryStage")
@Label("Chat history stage")
@Category("MeshApp")
@StackTrace(false)
public final class ChatHistoryTrace extends Event implements AutoCloseable {
    @Label("Stage") public String stage;

    public static ChatHistoryTrace start(String stage) {
        ChatHistoryTrace event = new ChatHistoryTrace();
        event.stage = stage;
        event.begin();
        return event;
    }

    @Override public void close() {
        end();
        commit();
    }
}
