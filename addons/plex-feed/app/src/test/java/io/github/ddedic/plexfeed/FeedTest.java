package io.github.ddedic.plexfeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class FeedTest {
    private static Plex.Entry episode(String key, String show, String showKey, int season, int ep, long addedAt) {
        Plex.Entry e = new Plex.Entry();
        e.type = "episode";
        e.ratingKey = key;
        e.title = "Episode " + ep;
        e.show = show;
        e.showKey = showKey;
        e.season = season;
        e.episode = ep;
        e.addedAt = addedAt;
        return e;
    }

    private static Plex.Entry movie(String key, String title, long addedAt) {
        Plex.Entry e = new Plex.Entry();
        e.type = "movie";
        e.ratingKey = key;
        e.title = title;
        e.year = 2024;
        e.duration = 112 * 60000L;
        e.addedAt = addedAt;
        return e;
    }

    @Test public void serversKeepHttpsOnlyAndPutDirectRemoteBeforeLocalAndRelay() throws Exception {
        JSONArray resources = new JSONArray("[{\"name\":\"Home\",\"provides\":\"server\",\"clientIdentifier\":\"m1\","
                + "\"accessToken\":\"t\",\"owned\":false,\"connections\":["
                + "{\"uri\":\"https://relay.plex.direct:8443\",\"relay\":true,\"local\":false},"
                + "{\"uri\":\"https://local.plex.direct:32400\",\"relay\":false,\"local\":true},"
                + "{\"uri\":\"http://10.0.0.2:32400\",\"relay\":false,\"local\":true},"
                + "{\"uri\":\"https://remote.plex.direct:32400\",\"relay\":false,\"local\":false}]},"
                + "{\"name\":\"Phone\",\"provides\":\"client,player\",\"connections\":[]}]");
        List<Plex.Server> servers = Plex.parseServers(resources);
        assertEquals(1, servers.size());
        assertEquals(Arrays.asList("https://remote.plex.direct:32400", "https://local.plex.direct:32400",
                "https://relay.plex.direct:8443"), servers.get(0).uris);
        assertEquals("m1", servers.get(0).machineId);
    }

    @Test public void episodesUseTheShowsBackdropAndOtherTypesAreSkipped() throws Exception {
        JSONObject container = new JSONObject("{\"Metadata\":["
                + "{\"type\":\"episode\",\"ratingKey\":\"9\",\"title\":\"Pilot\",\"grandparentTitle\":\"Severance\","
                + "\"grandparentRatingKey\":\"5\",\"parentIndex\":2,\"index\":1,\"art\":\"/ep/art\","
                + "\"grandparentArt\":\"/show/art\",\"addedAt\":100},"
                + "{\"type\":\"season\",\"ratingKey\":\"7\",\"title\":\"Season 2\"},"
                + "{\"type\":\"movie\",\"ratingKey\":\"3\",\"title\":\"Dune\",\"thumb\":\"/poster\"}]}");
        List<Plex.Entry> entries = Plex.parseEntries(container);
        assertEquals(2, entries.size());
        assertEquals("/show/art", entries.get(0).art);
        assertEquals("/poster", entries.get(1).art);
    }

    @Test public void newEpisodesAreGroupedByShowWithTheNewestFirst() {
        List<Plex.Entry> episodes = Arrays.asList(episode("11", "Severance", "5", 2, 3, 300),
                episode("10", "Severance", "5", 2, 2, 200), episode("20", "Andor", "6", 1, 1, 250));
        List<Feed.Item> shows = Feed.shows(episodes, "m1", 6);
        assertEquals(2, shows.size());
        assertEquals("Severance", shows.get(0).title);
        assertEquals("2 new episodes · latest S2 · E3", shows.get(0).subtitle);
        assertEquals(Feed.BADGE_EPISODES, shows.get(0).badge);
        assertEquals("plex://server://m1/com.plexapp.plugins.library/library/metadata/5", shows.get(0).link);
        assertEquals(Feed.BADGE_EPISODE, shows.get(1).badge);
    }

    @Test public void continueWatchingComesFirstThenNewestArrivalsWithoutRepeats() {
        Plex.Entry watching = movie("3", "Dune", 50);
        watching.viewOffset = 65 * 60000L;
        List<Feed.Item> items = Feed.build(Collections.singletonList(watching),
                Arrays.asList(movie("3", "Dune", 50), movie("4", "Alien", 400)),
                Collections.singletonList(episode("11", "Severance", "5", 2, 3, 300)), "m1", 6);
        assertEquals(Arrays.asList("Dune", "Alien", "Severance"),
                Arrays.asList(items.get(0).title, items.get(1).title, items.get(2).title));
        assertEquals(Feed.BADGE_CONTINUE, items.get(0).badge);
        assertEquals("2024 · 47 min left", items.get(0).subtitle);
        assertEquals("2024 · 1h 52m", items.get(1).subtitle);
        assertEquals(3, items.size());
    }

    @Test public void aShowInContinueWatchingIsNotRepeatedAsNewEpisodes() {
        List<Feed.Item> items = Feed.build(Collections.singletonList(episode("11", "Severance", "5", 2, 1, 100)),
                Collections.emptyList(), Arrays.asList(episode("12", "Severance", "5", 2, 2, 300),
                        episode("20", "Andor", "6", 1, 1, 250)), "m1", 6);
        assertEquals(Arrays.asList("Severance", "Andor"), Arrays.asList(items.get(0).title, items.get(1).title));
        assertEquals(2, items.size());
    }

    @Test public void lengthsReadNaturally() {
        assertEquals("47m", Feed.runtime(47 * 60000L));
        assertEquals("2h 0m", Feed.runtime(120 * 60000L));
        assertEquals("", Feed.runtime(0));
        assertEquals("", Feed.remaining(1000, 0));
        assertTrue(Feed.link("", "5").isEmpty());
    }

    @Test public void watchLinksNameTheFilmTheEpisodeOrTheShow() {
        Feed.Item film = new Feed.Item();
        film.guid = "plex://movie/5d776d1847dd6e001f6f00a4";
        assertEquals("https://watch.plex.tv/movie/dune-part-two", Feed.watchLink(film, "dune-part-two"));
        Feed.Item ep = new Feed.Item();
        ep.guid = "plex://show/5e1632df2d4d84003e48e54e";
        ep.season = 4;
        ep.episode = 1;
        assertEquals("https://watch.plex.tv/show/tulsa-king/season/4/episode/1", Feed.watchLink(ep, "tulsa-king"));
        ep.season = 0;
        assertEquals("https://watch.plex.tv/show/tulsa-king", Feed.watchLink(ep, "tulsa-king"));
        assertEquals("", Feed.watchLink(ep, ""));
        assertEquals("", Feed.watchLink(ep, "../x?y"));
        assertEquals("", Feed.watchLink(new Feed.Item(), "dune"));
    }

    @Test public void entriesKeepTheFilmsOrTheShowsCatalogueGuidOnly() throws Exception {
        JSONObject container = new JSONObject("{\"Metadata\":["
                + "{\"type\":\"episode\",\"ratingKey\":\"9\",\"title\":\"Pilot\",\"guid\":\"plex://episode/e1\","
                + "\"grandparentGuid\":\"plex://show/s1\"},"
                + "{\"type\":\"movie\",\"ratingKey\":\"3\",\"title\":\"Home video\",\"guid\":\"local://3\"}]}");
        List<Plex.Entry> entries = Plex.parseEntries(container);
        assertEquals("plex://show/s1", entries.get(0).guid);
        assertEquals("", entries.get(1).guid);
    }
}
