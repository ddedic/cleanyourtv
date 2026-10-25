package io.github.ddedic.netatmo;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to Netatmo on one background thread, only when asked: the launcher asks each time Home comes to the front,
 * the panel when it opens and after each change. No background service and no timer.
 */
final class Fetcher {
    static final long STALE_MS = 5 * 60 * 1000L;
    static final long RETRY_MS = 2 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    interface Command {
        void run(Netatmo api, String token, String homeId) throws Exception;
    }

    private Fetcher() { }

    static boolean isRunning() { return RUNNING.get(); }

    static void refresh(Context context, boolean force, Runnable done) {
        Store store = new Store(context);
        store.importCredentials(context);
        if (!store.signedIn()) return;
        if (!force && System.currentTimeMillis() - store.lastFetch() < STALE_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        run(context, null, done);
    }

    /** Sends a change (a room's target, the home's mode), then fetches the status it led to. */
    static void send(Context context, Command command, Runnable done) {
        RUNNING.set(true);
        run(context, command, done);
    }

    private static void run(Context context, Command command, Runnable done) {
        Context app = context.getApplicationContext();
        Store store = new Store(app);
        IO.execute(() -> {
            try {
                Netatmo api = new Netatmo();
                String token = token(api, store);
                Netatmo.Home home = store.home();
                if (home == null) {
                    home = api.home(token);
                    store.setHome(home);
                }
                if (command != null) command.run(api, token, home.id);
                store.fetched(api.status(token, home), "");
            } catch (Exception failed) {
                store.failed(failed.getClass().getSimpleName() + ": " + failed.getMessage());
            } finally {
                RUNNING.set(false);
                app.getContentResolver().notifyChange(StatusProvider.STATUS, null);
                if (done != null) done.run();
            }
        });
    }

    /** The access token, refreshed a minute before it runs out. */
    private static String token(Netatmo api, Store store) throws Exception {
        if (!store.accessToken().isEmpty() && System.currentTimeMillis() < store.expiresAt() - 60_000) {
            return store.accessToken();
        }
        Netatmo.Tokens tokens = api.refresh(store.clientId(), store.clientSecret(), store.refreshToken());
        store.setTokens(tokens);
        return tokens.access;
    }
}
