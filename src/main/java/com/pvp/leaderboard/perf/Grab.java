// Sync from PvP Leaderboard: off in this release. To turn it back on, uncomment this file, GrabTest and every line marked SYNC.
// package com.pvp.leaderboard.perf;

// import com.google.gson.*;
// import com.pvp.leaderboard.game.*;
// import com.pvp.leaderboard.service.*;
// import com.pvp.leaderboard.service.socket.*;
// import java.io.*;
// import java.util.*;
// import java.util.concurrent.*;
// import java.util.function.*;
// import javax.inject.*;
// import lombok.*;
// import lombok.extern.slf4j.*;
// import net.runelite.client.config.*;
// import static com.pvp.leaderboard.util.JsonLenient.*;

// /** The sync: the account's fights from PvP Leaderboard, once a day, in one gzip download that replaces the grabbed
//  *  series. The button's state comes from the server's status (read once a login session when the tab opens), this
//  *  computer's last sync and the socket: without one the button is greyed, with no line of its own. */
// @Slf4j
// @Singleton
// public class Grab
// {
//     static final String BUSY = "Syncing...", LATER = "Try again later", FAILED = "Sync failed; try again later",
//         MISMATCH = "Error, please contact Toyco on discord to retrieve fights", DONE = "Synced";
//     private static final String AT = "perfGrabAt";
//     private static final String[] KEYS = {"op", "ed", "d", "mh", "pr", "hp", "rh", "ko", "gb"};

//     private final PvpApi api;
//     private final SocketMgr socket;
//     private final ConfigManager config;
//     private final FightStore store;
//     private final ScheduledExecutorService executor;
//     LongSupplier clock = System::currentTimeMillis;
//     @Setter private volatile Supplier<String> name = () -> null;
//     @Setter private volatile Runnable listener;
//     private volatile String note = "";
//     private volatile boolean busy;
//     /** When the server says the grab is available again, and this computer's last grab (-1: not read yet), epoch s. */
//     private volatile long until, last = -1;
//     /** The status was read this login session (and no claim answered since). */
//     private boolean asked;
//     private Future<?> retry;

//     @Inject
//     public Grab(PvpApi api, SocketMgr socket, ConfigManager config, FightStore store, ScheduledExecutorService executor)
//     {
//         this.api = api;
//         this.socket = socket;
//         this.config = config;
//         this.store = store;
//         this.executor = executor;
//         socket.addConnectListener(this::changed);
//     }

//     /** The state line under the button; "" when none applies. */
//     public String text()
//     {
//         if (busy) return BUSY;
//         if (!note.isEmpty()) return note;
//         long s = Math.max(until, last() + 86_400) - clock.getAsLong() / 1000, m = (s + 59) / 60;
//         return s <= 0 ? "" : "Available again in " + (m < 60 ? m + " min" : (s + 3599) / 3600 + " h");
//     }

//     public boolean ready()
//     {
//         String t = text();
//         return socket.isConnected() && name.get() != null && (t.isEmpty() || t.equals(LATER) || t.equals(FAILED));
//     }

//     /** The tab opened: the server's status, once a login session and again after a claim's answer, never while the
//      *  button is greyed here. */
//     public synchronized void opened()
//     {
//         if (note.startsWith(DONE))
//         {
//             note = "";
//             changed();
//         }
//         if (asked || !ready()) return;
//         asked = true;
//         api.grabStatus(name.get()).whenComplete((j, e) ->
//         {
//             if (j != null && j.has("available") && optBool(j, "available", false)) note = "";
//             else if (j != null && j.has("available")) refused(j);
//             else note = LATER;
//             changed();
//         });
//     }

//     /** The button: one request that claims the day and brings the fights. */
//     public synchronized void click()
//     {
//         if (!ready()) return;
//         busy = true;
//         note = "";
//         changed();
//         claim(name.get(), 0);
//     }

//     private void claim(String n, int attempt)
//     {
//         api.grab(n).whenComplete((j, e) ->
//         {
//             Throwable t = e instanceof CompletionException && e.getCause() != null ? e.getCause() : e;
//             synchronized (this)
//             {
//                 if (!busy) return;
//                 if (t instanceof IOException && attempt < 2)
//                 {
//                     retry = executor.schedule(() -> claim(n, attempt + 1), 5, TimeUnit.SECONDS);
//                     return;
//                 }
//                 busy = false;
//                 asked = false;
//                 if (t instanceof IOException) note = FAILED;
//                 else if (j != null && optBool(j, "ok", false) && j.get("fights") instanceof JsonArray) got(j);
//                 else if (j != null && j.has("ok")) refused(j);
//                 else note = LATER;
//             }
//             changed();
//         });
//     }

//     private void got(JsonObject j)
//     {
//         List<FightLog.Fight> fights = fights(j.getAsJsonArray("fights"));
//         store.replaceGrab(fights);
//         last = clock.getAsLong() / 1000;
//         config.setConfiguration(FightMonitor.CONFIG_GROUP, AT, String.valueOf(last));
//         until = optLong(j, "available_at", 0);
//         note = String.format(Locale.US, DONE + " %,d fight%s", fights.size(), fights.size() == 1 ? "" : "s");
//     }

//     private void refused(JsonObject j)
//     {
//         String r = optString(j, "reason", "");
//         long at = optLong(j, "available_at", 0);
//         if ("used".equals(r) && at > 0) until = at;
//         note = "used".equals(r) && at > 0 ? "" : "mismatch".equals(r) ? MISMATCH : LATER;
//     }

//     /** A login: the status may be read once more. */
//     public synchronized void loggedIn()
//     {
//         asked = false;
//     }

//     /** The plugin stops: a retry waiting is cancelled. */
//     public synchronized void stop()
//     {
//         if (retry != null) retry.cancel(false);
//         retry = null;
//         busy = false;
//     }

//     private long last()
//     {
//         if (last < 0)
//         {
//             try
//             {
//                 last = Long.parseLong(config.getConfiguration(FightMonitor.CONFIG_GROUP, AT));
//             }
//             catch (RuntimeException e)
//             {
//                 last = 0;
//             }
//         }
//         return last;
//     }

//     private void changed()
//     {
//         Runnable l = listener;
//         if (l != null) l.run();
//     }

//     /** The body's fights, version 1 (when, name played as, opponent, result, style, v3 division after, the opponent's,
//      *  v3 change, perf, sync, perf_opp), oldest first; one that does not read is left out. */
//     static List<FightLog.Fight> fights(JsonArray rows)
//     {
//         List<FightLog.Fight> out = new ArrayList<>();
//         for (JsonElement e : rows)
//         {
//             try
//             {
//                 JsonArray a = e.getAsJsonArray();
//                 JsonObject perf = obj(a, 8);
//                 String[] mine = new String[9], theirs = new String[9];
//                 Arrays.fill(theirs, "-");
//                 for (int i = 0; i < 9; i++) mine[i] = FightMerge.number(optString(perf, KEYS[i], "-")) ? optString(perf, KEYS[i]) : "-";
//                 boolean synced = FightMerge.join(mine, theirs, obj(a, 10), obj(a, 9));
//                 FightLog.Fight f = new FightLog.Fight((long) a.get(0).getAsDouble(), str(a, 2), str(a, 3), 0, mine, theirs);
//                 f.self = str(a, 1);
//                 f.style = str(a, 4);
//                 f.rank = str(a, 5);
//                 f.oppRank = str(a, 6);
//                 f.change = str(a, 7);
//                 f.marks = optString(perf, "v", "").replaceAll("[^0-9]", "") + (synced ? "s" : "");
//                 out.add(f);
//             }
//             catch (RuntimeException ex)
//             {
//                 log.debug("[Perf] skipped a grabbed fight: {}", ex.getMessage());
//             }
//         }
//         Collections.reverse(out);
//         return out;
//     }

//     private static JsonObject obj(JsonArray a, int i)
//     {
//         return i < a.size() && a.get(i).isJsonObject() ? a.get(i).getAsJsonObject() : null;
//     }

//     private static String str(JsonArray a, int i)
//     {
//         return i < a.size() && a.get(i).isJsonPrimitive() ? a.get(i).getAsString() : "";
//     }
// }
