package io.github.ddedic.netatmo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The home's heating as last fetched: each room's temperature and target, and whether the boiler runs. */
final class Status {
    static final class Room {
        final String id, name, mode;
        final double measured, target;
        final boolean heating;

        Room(String id, String name, double measured, double target, String mode, boolean heating) {
            this.id = id;
            this.name = name;
            this.measured = measured;
            this.target = target;
            this.mode = mode;
            this.heating = heating;
        }
    }

    final String homeId;
    final List<Room> rooms;
    final boolean boiler;
    final long fetchedAt;

    Status(String homeId, List<Room> rooms, boolean boiler, long fetchedAt) {
        this.homeId = homeId;
        this.rooms = Collections.unmodifiableList(rooms);
        this.boiler = boiler;
        this.fetchedAt = fetchedAt;
    }

    /** The room the pill shows: the chosen one, else the first. Null with no rooms. */
    Room main(String chosenId) {
        for (Room room : rooms) if (room.id.equals(chosenId)) return room;
        return rooms.isEmpty() ? null : rooms.get(0);
    }

    boolean heating() {
        if (boiler) return true;
        for (Room room : rooms) if (room.heating) return true;
        return false;
    }

    /** The home's mode as the rooms show it: away or frost guard when every room is in it, else the schedule. */
    String homeMode() {
        if (rooms.isEmpty()) return "schedule";
        String first = rooms.get(0).mode;
        if (!first.equals("away") && !first.equals("hg")) return "schedule";
        for (Room room : rooms) if (!room.mode.equals(first)) return "schedule";
        return first;
    }

    /** The room now, "21.4°": the pill's text. Its target goes in the pill's badge, see {@link #target}. */
    static String pill(Room room) {
        return room == null || Double.isNaN(room.measured) ? "" : degrees(room.measured);
    }

    /** The room's target, "22°", for the pill's corner tag; empty when Netatmo gives none. */
    static String target(Room room) {
        return room == null || Double.isNaN(room.target) ? "" : degrees(room.target);
    }

    /** 21.4°, 22°: one decimal, none when it is whole. */
    static String degrees(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return (rounded == Math.rint(rounded) ? String.valueOf((long) rounded)
                : String.format(Locale.ROOT, "%.1f", rounded)) + "°";
    }

    /** A target one half-degree step up or down, kept within Netatmo's 7 to 30 °C. */
    static double step(double target, int halfDegrees) {
        double base = Double.isNaN(target) ? 20 : Math.round(target * 2) / 2.0;
        return Math.max(7, Math.min(30, base + halfDegrees * 0.5));
    }

    String toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (Room r : rooms) {
            list.put(new JSONObject().put("id", r.id).put("name", r.name).put("mode", r.mode)
                    .put("measured", Double.isNaN(r.measured) ? JSONObject.NULL : r.measured)
                    .put("target", Double.isNaN(r.target) ? JSONObject.NULL : r.target).put("heating", r.heating));
        }
        return new JSONObject().put("home", homeId).put("boiler", boiler).put("at", fetchedAt).put("rooms", list).toString();
    }

    static Status fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            List<Room> rooms = new ArrayList<>();
            JSONArray list = o.getJSONArray("rooms");
            for (int i = 0; i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                rooms.add(new Room(r.getString("id"), r.getString("name"), r.optDouble("measured"),
                        r.optDouble("target"), r.optString("mode", "schedule"), r.optBoolean("heating")));
            }
            return new Status(o.getString("home"), rooms, o.optBoolean("boiler"), o.optLong("at"));
        } catch (JSONException | RuntimeException broken) {
            return null;
        }
    }
}
