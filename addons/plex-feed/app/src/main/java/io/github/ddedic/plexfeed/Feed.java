package io.github.ddedic.plexfeed;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The shelf's items from what the server returned: Continue watching first, then the newest arrivals. */
final class Feed {
    private Feed() { }

    static final String BADGE_CONTINUE = "Continue watching";
    static final String BADGE_MOVIE = "Recently added";
    static final String BADGE_EPISODES = "New episodes";
    static final String BADGE_EPISODE = "New episode";

    static final class Item {
        String key = "", title = "", subtitle = "", summary = "", badge = "", art = "", link = "";
        long addedAt;
    }

    /**
     * @param perSection how many items each section gives at most
     * @param episodes newest first; grouped here into one item per show
     */
    static List<Item> build(List<Plex.Entry> continueWatching, List<Plex.Entry> movies, List<Plex.Entry> episodes,
                            String machineId, int perSection) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Plex.Entry e : continueWatching) {
            if (out.size() >= perSection) break;
            Item item = "episode".equals(e.type) ? episode(e, machineId, e.ratingKey) : movie(e, machineId);
            item.badge = BADGE_CONTINUE;
            String left = remaining(e.duration, e.viewOffset);
            item.subtitle = join(item.subtitle, left);
            if (seen.add(item.key)) out.add(item);
            // A show you are watching does not come back as "New episodes" further on.
            if (!e.showKey.isEmpty()) seen.add("show:" + e.showKey);
        }

        List<Item> recent = new ArrayList<>();
        List<Plex.Entry> sortedMovies = new ArrayList<>(movies);
        sortedMovies.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        for (int i = 0; i < sortedMovies.size() && i < perSection; i++) {
            Item item = movie(sortedMovies.get(i), machineId);
            item.badge = BADGE_MOVIE;
            item.subtitle = join(item.subtitle, runtime(sortedMovies.get(i).duration));
            recent.add(item);
        }
        recent.addAll(shows(episodes, machineId, perSection));
        recent.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
        for (Item item : recent) if (seen.add(item.key)) out.add(item);
        return out;
    }

    /** One item per show, its newest episode first, with how many new episodes the fetched page held. */
    static List<Item> shows(List<Plex.Entry> episodes, String machineId, int perSection) {
        Map<String, List<Plex.Entry>> byShow = new LinkedHashMap<>();
        for (Plex.Entry e : episodes) {
            String show = e.showKey.isEmpty() ? e.show : e.showKey;
            byShow.computeIfAbsent(show, k -> new ArrayList<>()).add(e);
        }
        List<Item> out = new ArrayList<>();
        for (List<Plex.Entry> group : byShow.values()) {
            if (out.size() >= perSection) break;
            group.sort((a, b) -> Long.compare(b.addedAt, a.addedAt));
            Plex.Entry newest = group.get(0);
            Item item = episode(newest, machineId, newest.showKey.isEmpty() ? newest.ratingKey : newest.showKey);
            item.key = "show:" + (newest.showKey.isEmpty() ? newest.show : newest.showKey);
            if (group.size() > 1) {
                item.badge = BADGE_EPISODES;
                item.subtitle = group.size() + " new episodes · latest " + seasonEpisode(newest);
            } else {
                item.badge = BADGE_EPISODE;
            }
            out.add(item);
        }
        return out;
    }

    private static Item movie(Plex.Entry e, String machineId) {
        Item item = new Item();
        item.key = e.ratingKey;
        item.title = e.title;
        item.subtitle = e.year > 0 ? String.valueOf(e.year) : "";
        item.summary = e.summary;
        item.art = e.art;
        item.addedAt = e.addedAt;
        item.link = link(machineId, e.ratingKey);
        return item;
    }

    private static Item episode(Plex.Entry e, String machineId, String linkKey) {
        Item item = new Item();
        item.key = e.ratingKey;
        item.title = e.show.isEmpty() ? e.title : e.show;
        item.subtitle = e.show.isEmpty() ? seasonEpisode(e) : join(seasonEpisode(e), e.title);
        item.summary = e.summary;
        item.art = e.art;
        item.addedAt = e.addedAt;
        item.link = link(machineId, linkKey);
        return item;
    }

    /** The Plex app's own link to an item on a server; the app opens its details page. */
    static String link(String machineId, String ratingKey) {
        if (machineId.isEmpty() || ratingKey.isEmpty()) return "";
        return "plex://server://" + machineId + "/com.plexapp.plugins.library/library/metadata/" + ratingKey;
    }

    static String seasonEpisode(Plex.Entry e) {
        if (e.season <= 0 && e.episode <= 0) return "";
        return "S" + e.season + " · E" + e.episode;
    }

    /** "1h 52m", "47m", or empty. */
    static String runtime(long durationMs) {
        long minutes = durationMs / 60000;
        if (minutes <= 0) return "";
        return minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m";
    }

    /** "23 min left", or empty when the length or the position is unknown. */
    static String remaining(long durationMs, long offsetMs) {
        if (durationMs <= 0 || offsetMs <= 0 || offsetMs >= durationMs) return "";
        long minutes = Math.max(1, (durationMs - offsetMs) / 60000);
        return minutes + " min left";
    }

    static String join(String a, String b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }
}
