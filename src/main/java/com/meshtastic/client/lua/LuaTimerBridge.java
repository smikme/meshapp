package com.meshtastic.client.lua;

/**
 * Bridge from the Lua sandbox to host-managed script timers.
 * <p>
 * Implementations own scheduling, cancellation, callback delivery, and session
 * lifetime semantics. A scheduled timer represents pending asynchronous work, so
 * the runtime should keep the script session alive until the timer fires or is
 * cancelled.
 */
public interface LuaTimerBridge {

    /**
     * Creates a unique timer id within the current Lua session.
     *
     * @return timer id
     */
    String nextTimerId();

    /**
     * Schedules a Lua timer request for later delivery to {@code on_timer(event)}.
     *
     * @param request immutable timer request
     */
    void scheduleTimer(LuaTimerRequest request);

    /**
     * Cancels one active timer.
     *
     * @param timerId timer id
     * @return {@code true} when an active timer was cancelled
     */
    boolean cancelTimer(String timerId);

    /**
     * Cancels all active timers for the current session.
     *
     * @return number of cancelled timers
     */
    int cancelAllTimers();
}
