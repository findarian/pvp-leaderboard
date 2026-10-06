package com.pvp.leaderboard.queue;

import com.google.gson.*;

/**
 * Server-push callbacks for the matchmaking queue (Plan 10 Part B / F.1),
 * registered with a {@link QueueService}. Every callback is delivered on
 * the Swing EDT (the {@code WebSocketQueueService} marshals), so panels
 * may mutate Swing components directly. All methods are {@code default}
 * no-ops. A queue match ({@code queue/matched}) is the lobby service's:
 * its {@code lobby/fight_proposed} drives the Confirm Fight view.
 */
public interface QueueEventListener
{
    /** {@code queue/state} — after join / leave / expand / status and every
     *  tick while searching. {@code state == idle} with a {@code reason}
     *  means the worker tore a proposed fight down (expired / declined). */
    default void onQueueState(QueueState state) {}

    /** {@code queue/timeout} — the wait preference elapsed; the user is
     *  out of the queue. */
    default void onQueueTimeout(QueueState state) {}

    /** {@code queue/prefs} — echo of the shared preferences after
     *  {@code queue/set_prefs}. */
    default void onQueuePrefs(JsonObject prefs) {}

    /** {@code error/queue} — a stable error code + message. */
    default void onQueueError(String code, String message) {}
}
