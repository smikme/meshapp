package com.meshtastic.client.lua;

/**
 * Parameters for one host-managed Lua timer.
 * <p>
 * Requests are created by {@code mesh.timer.after} and {@code mesh.timer.every}
 * and consumed by the runtime scheduler. The runtime echoes these values into
 * the callback event where appropriate, so script authors can identify the timer
 * that fired without keeping additional Java-side state.
 *
 * @param scriptId script id
 * @param timerId unique timer id inside the session
 * @param source API function that created the timer
 * @param name optional caller-provided name
 * @param seconds delay or repeat interval in seconds
 * @param repeating whether the timer repeats
 * @param align repeat alignment mode: {@code interval} schedules relative to the
 *              previous planned fire time, {@code wall} aligns to local
 *              wall-clock boundaries
 * @param immediate whether a repeating timer should fire immediately once
 */
public record LuaTimerRequest(long scriptId,
                              String timerId,
                              String source,
                              String name,
                              double seconds,
                              boolean repeating,
                              String align,
                              boolean immediate) {}
