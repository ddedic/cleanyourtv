package io.github.ddedic.ringdoor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class RingTest {
    @Test public void webhooksBecomeEvents() {
        RingApi.Event ring = RingApi.parseWebhook("{\"meta\":{\"request_id\":\"r1\"},\"data\":{\"id\":\"d1_button_press_1\","
                + "\"type\":\"button_press\",\"attributes\":{\"source\":\"d1\",\"source_type\":\"devices\",\"timestamp\":1771027372393}}}");
        assertTrue(ring.ding());
        assertEquals("d1", ring.deviceId);
        assertEquals(1771027372393L, ring.at);
        RingApi.Event person = RingApi.parseWebhook("{\"data\":{\"id\":\"m\",\"type\":\"motion_detected\","
                + "\"attributes\":{\"source\":\"d1\",\"timestamp\":5,\"sub_type\":\"human\"}}}");
        assertTrue(person.person());
        assertNull(RingApi.parseWebhook("{\"data\":{\"type\":\"device_online\",\"attributes\":{\"source\":\"d1\"}}}"));
        assertNull(RingApi.parseWebhook("not json"));
    }

    @Test public void devicesKnowTheChimeByItsAudioSlots() throws Exception {
        String json = "{\"data\":[{\"type\":\"devices\",\"id\":\"cam\",\"attributes\":{\"name\":\"Front Door\"},"
                + "\"relationships\":{\"status\":{\"data\":{\"type\":\"device-status\",\"id\":\"s1\"}}}},"
                + "{\"type\":\"devices\",\"id\":\"ch\",\"attributes\":{\"name\":\"Upstairs\"},\"relationships\":{"
                + "\"capabilities\":{\"data\":{\"type\":\"device-capabilities\",\"id\":\"c2\"}},"
                + "\"configurations\":{\"data\":{\"type\":\"device-configurations\",\"id\":\"g2\"}}}}],"
                + "\"included\":[{\"type\":\"device-status\",\"id\":\"s1\",\"attributes\":{\"online\":false}},"
                + "{\"type\":\"device-capabilities\",\"id\":\"c2\",\"attributes\":{\"audio\":{\"customizable_slots\":2}}},"
                + "{\"type\":\"device-configurations\",\"id\":\"g2\",\"attributes\":{\"audio\":{\"volume\":7}}}]}";
        List<RingApi.Device> devices = RingApi.parseDevices(new JSONObject(json));
        assertEquals(2, devices.size());
        assertFalse(devices.get(0).chime);
        assertFalse(devices.get(0).online);
        assertTrue(devices.get(1).chime);
        assertEquals(7, devices.get(1).volume);
    }

    @Test public void theLinkNonceIsUrlSafeBase64OfTheHmac() throws Exception {
        String nonce = RingApi.nonce("key", "1771130906289", "ava1.ring.account.X");
        assertEquals(43, nonce.length());   // 32 bytes, no padding
        assertFalse(nonce.contains("=") || nonce.contains("+") || nonce.contains("/"));
        assertEquals(nonce, RingApi.nonce("key", "1771130906289", "ava1.ring.account.X"));
        assertFalse(nonce.equals(RingApi.nonce("key", "1771130906289", "ava1.ring.account.Y")));
    }

    @Test public void quietHoursMayRunPastMidnight() {
        assertTrue(Store.inQuiet(23, 22, 7));
        assertTrue(Store.inQuiet(3, 22, 7));
        assertFalse(Store.inQuiet(12, 22, 7));
        assertTrue(Store.inQuiet(5, 0, 8));
        assertFalse(Store.inQuiet(8, 0, 8));
        assertFalse(Store.inQuiet(10, 9, 9));
    }

    @Test public void tokensKeepTheirLifetime() throws Exception {
        RingApi.Tokens t = RingApi.parseTokens(new JSONObject("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":14400}"), 1000);
        assertEquals(1000 + 14400 * 1000L, t.expiresAt);
    }
}
