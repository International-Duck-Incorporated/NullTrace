package net.nulltrace.mod.privacy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip and migration tests for the per-server profile, including the defensive-copy path
 * {@code NullTraceConfig#getServerProfile} relies on.
 */
class ServerProfileJsonTest {

    @Test
    void roundTripKeepsEveryField() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.CUSTOM);
        profile.setNormalizeClientInformation(true);
        profile.setBlockLocalRequests(true);
        profile.setBlockServerCookies(false);
        profile.setBlockServerTransfers(true);
        profile.setAllowedChannels(java.util.List.of("a:one", "b:two"));

        ServerProfile copy = ServerProfile.fromJson("example.com", profile.toJson());

        assertEquals(profile.server(), copy.server());
        assertEquals(profile.mode(), copy.mode());
        assertTrue(copy.normalizeClientInformation());
        assertTrue(copy.blockLocalRequests());
        assertFalse(copy.blockServerCookies());
        assertTrue(copy.blockServerTransfers());
        assertTrue(copy.allowedChannels().contains("a:one"));
        assertTrue(copy.allowedChannels().contains("b:two"));
    }

    @Test
    void defensiveCopyDoesNotShareState() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setMode(ServerProfile.Mode.STRICT);
        ServerProfile copy = ServerProfile.fromJson("example.com", profile.toJson());

        copy.setMode(ServerProfile.Mode.MAXIMUM_PRIVACY);
        profile.setMode(ServerProfile.Mode.STANDARD);
        assertEquals(ServerProfile.Mode.MAXIMUM_PRIVACY, copy.mode());
        assertEquals(ServerProfile.Mode.STANDARD, profile.mode());
    }

    @Test
    void missingOrBlankServerBecomesTheDefaultProfile() {
        assertEquals("default", new ServerProfile(null).server());
        assertEquals("default", new ServerProfile("   ").server());
    }

    @Test
    void unknownModeFallsBackToStandardInsteadOfThrowing() {
        JsonObject json = JsonParser.parseString("{\"mode\":\"EXPERIMENTAL_FUTURE_MODE\"}").getAsJsonObject();
        ServerProfile profile = ServerProfile.fromJson("example.com", json);
        assertEquals(ServerProfile.Mode.STANDARD, profile.mode());
    }

    @Test
    void nullJsonYieldsAFreshProfile() {
        ServerProfile profile = ServerProfile.fromJson("example.com", null);
        assertNotNull(profile);
        assertEquals(ServerProfile.Mode.STANDARD, profile.mode());
        assertTrue(profile.allowedChannels().isEmpty());
    }

    @Test
    void channelsAreTrimmedAndBlankEntriesDropped() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setAllowedChannels(java.util.List.of("  keep:me  ", "", "   ", "also:kept"));
        assertEquals(2, profile.allowedChannels().size());
        assertTrue(profile.allowedChannels().contains("keep:me"));
        assertTrue(profile.allowedChannels().contains("also:kept"));
    }

    @Test
    void allowedChannelsAccessorIsUnmodifiable() {
        ServerProfile profile = new ServerProfile("example.com");
        profile.setAllowedChannels(java.util.List.of("a:one"));
        try {
            profile.allowedChannels().add("b:two");
            org.junit.jupiter.api.Assertions.fail("allowedChannels() must be a snapshot, not a live set");
        } catch (UnsupportedOperationException expected) {
            // expected: callers cannot mutate the profile's internal state
        }
    }
}
