package io.github.ddedic.plexfeed;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One refresh at a time, on request only: the launcher asks each time Home comes to the front, the setup screen
 * when a choice changes. No background service and no timer.
 */
final class Fetcher {
    static final long STALE_MS = 15 * 60 * 1000L;
    /** After a failure, try again on a Home visit after this long rather than on every one. */
    private static final long RETRY_MS = 2 * 60 * 1000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private Fetcher() { }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static void refresh(Context context, boolean force, Runnable done) {
        Store store = new Store(context);
        if (!store.hasServer()) return;
        if (!force && System.currentTimeMillis() - store.lastFetch() < STALE_MS) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try {
                fetch(store);
            } finally {
                RUNNING.set(false);
                app.getContentResolver().notifyChange(FeedProvider.ITEMS, null);
                if (done != null) done.run();
            }
        });
    }

    private static void fetch(Store store) {
        Plex plex = new Plex(store.clientId());
        try {
            fetchFrom(plex, store, store.serverUri());
        } catch (Exception first) {
            // A remote server's address changes now and then: look it up again on plex.tv once.
            try {
                String uri = rediscover(plex, store);
                if (uri == null) throw first;
                store.setServerUri(uri);
                fetchFrom(plex, store, uri);
            } catch (Exception failed) {
                store.fetched(System.currentTimeMillis() - STALE_MS + RETRY_MS, store.itemCount(),
                        failed.getClass().getSimpleName() + ": " + failed.getMessage());
            }
        }
    }

    private static String rediscover(Plex plex, Store store) throws Exception {
        for (Plex.Server server : plex.servers(store.accountToken())) {
            if (server.machineId.equals(store.serverId())) return plex.reachable(server);
        }
        return null;
    }

    private static void fetchFrom(Plex plex, Store store, String base) throws Exception {
        String token = store.serverToken();
        int count = store.perSection();
        List<Plex.Entry> onDeck = store.section(Store.CONTINUE) ? plex.onDeck(base, token, count)
                : Collections.emptyList();
        List<Plex.Entry> movies = new ArrayList<>(), episodes = new ArrayList<>();
        boolean wantMovies = store.section(Store.MOVIES), wantEpisodes = store.section(Store.EPISODES);
        if (wantMovies || wantEpisodes) {
            for (String[] section : plex.sections(base, token)) {
                if (wantMovies && "movie".equals(section[1])) {
                    movies.addAll(plex.recentlyAdded(base, token, section[0], 1, count));
                } else if (wantEpisodes && "show".equals(section[1])) {
                    // Three per show on average, so a busy show does not crowd the others out of the page.
                    episodes.addAll(plex.recentlyAdded(base, token, section[0], 4, count * 3));
                }
            }
            episodes.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        }
        List<Feed.Item> items = Feed.build(onDeck, movies, episodes, store.serverId(), count);

        File dir = store.artDir();
        List<String> files = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (Feed.Item item : items) {
            String name = item.art.isEmpty() ? "" : "a" + Integer.toHexString(item.art.hashCode()) + ".jpg";
            if (!name.isEmpty()) {
                File file = new File(dir, name);
                if (!file.isFile()) {
                    try {
                        plex.art(base, token, item.art, file);
                    } catch (Exception noArt) {
                        name = "";   // the item still shows, on the plain background
                    }
                }
            }
            if (!name.isEmpty()) keep.add(name);
            files.add(name);
        }
        File[] old = dir.listFiles();
        if (old != null) for (File file : old) if (!keep.contains(file.getName())) //noinspection ResultOfMethodCallIgnored
            file.delete();
        store.writeItems(items, files);
        store.fetched(System.currentTimeMillis(), items.size(), "");
    }
}
