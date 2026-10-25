package io.github.ddedic.netatmo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class StatusTest {
    private static final String HOMESDATA = "{\"body\":{\"homes\":["
            + "{\"id\":\"weather\",\"rooms\":[{\"id\":\"9\",\"name\":\"Garden\"}],\"modules\":[{\"type\":\"NAMain\"}]},"
            + "{\"id\":\"h1\",\"rooms\":[{\"id\":\"1\",\"name\":\"Living room\"},{\"id\":\"2\",\"name\":\"Bedroom\"}],"
            + "\"modules\":[{\"type\":\"NAPlug\"},{\"type\":\"NATherm1\"}]}]}}";
    private static final String HOMESTATUS = "{\"body\":{\"home\":{\"id\":\"h1\","
            + "\"modules\":[{\"type\":\"NAPlug\"},{\"type\":\"NATherm1\",\"boiler_status\":true}],"
            + "\"rooms\":[{\"id\":\"1\",\"therm_measured_temperature\":21.4,\"therm_setpoint_temperature\":22,"
            + "\"therm_setpoint_mode\":\"manual\"},{\"id\":\"2\",\"therm_measured_temperature\":19,"
            + "\"therm_setpoint_temperature\":18.5,\"therm_setpoint_mode\":\"schedule\"},{\"id\":\"3\"}]}}}";

    @Test public void theHomeWithHeatingIsChosenAndRoomsGetTheirNames() throws Exception {
        Netatmo.Home home = Netatmo.parseHome(new JSONObject(HOMESDATA));
        assertEquals("h1", home.id);
        Status status = Netatmo.parseStatus(new JSONObject(HOMESTATUS), home, 5);
        assertEquals(2, status.rooms.size());
        assertEquals("Living room", status.rooms.get(0).name);
        assertTrue(status.heating());
        assertEquals("schedule", status.homeMode());
        assertEquals("Bedroom", status.main("2").name);
        assertEquals("Living room", status.main("gone").name);
    }

    @Test public void thePillShowsNowThenTarget() {
        Status.Room room = new Status.Room("1", "Living room", 21.44, 22, "manual", false);
        assertEquals("21.4° → 22°", Status.pill(room));
        assertEquals("19°", Status.pill(new Status.Room("2", "Bed", 19, Double.NaN, "schedule", false)));
        assertEquals("", Status.pill(null));
        assertEquals("", Status.pill(new Status.Room("3", "X", Double.NaN, 20, "schedule", false)));
    }

    @Test public void targetsMoveInHalfDegreesWithinNetatmosRange() {
        assertEquals(22.5, Status.step(22, 1), 0);
        assertEquals(21.5, Status.step(21.8, -1), 0);   // snaps to the half-degree grid first
        assertEquals(30, Status.step(30, 1), 0);
        assertEquals(7, Status.step(7, -1), 0);
        assertEquals(20.5, Status.step(Double.NaN, 1), 0);
    }

    @Test public void awayOnlyWhenEveryRoomIsAway() {
        Map<String, String> names = new LinkedHashMap<>();
        Status away = new Status("h", java.util.Arrays.asList(new Status.Room("1", "A", 18, 12, "away", false),
                new Status.Room("2", "B", 18, 12, "away", false)), false, 0);
        assertEquals("away", away.homeMode());
        assertFalse(away.heating());
        Status mixed = new Status("h", java.util.Arrays.asList(new Status.Room("1", "A", 18, 12, "away", false),
                new Status.Room("2", "B", 18, 20, "manual", true)), false, 0);
        assertEquals("schedule", mixed.homeMode());
        assertTrue(mixed.heating());
        assertTrue(names.isEmpty());
    }

    @Test public void theCachedStatusSurvivesARoundTrip() throws Exception {
        Status status = Netatmo.parseStatus(new JSONObject(HOMESTATUS), Netatmo.parseHome(new JSONObject(HOMESDATA)), 42);
        Status back = Status.fromJson(status.toJson());
        assertEquals(42, back.fetchedAt);
        assertEquals("21.4° → 22°", Status.pill(back.main("")));
        assertNull(Status.fromJson("{broken"));
    }

    @Test public void refreshedTokensReplaceBothAndExpireOnTime() throws Exception {
        Netatmo.Tokens t = Netatmo.parseTokens(new JSONObject(
                "{\"access_token\":\"a\",\"refresh_token\":\"r2\",\"expires_in\":10800}"), 1000);
        assertEquals("r2", t.refresh);
        assertEquals(1000 + 10800 * 1000L, t.expiresAt);
    }
}
