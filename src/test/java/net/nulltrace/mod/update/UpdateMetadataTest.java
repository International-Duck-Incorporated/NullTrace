package net.nulltrace.mod.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class UpdateMetadataTest {
    @Test
    void curseForgeUsesModVersionFromFilenameNotMinecraftVersionField() {
        JsonObject file = file(42, "nulltrace-26.2+v2.1.0.jar", "release", "Client", "Fabric", "26.2");
        file.addProperty("version", "26.2");
        file.addProperty("url", "https://untrusted.example/download.jar");

        UpdateMetadata.Release release = UpdateMetadata.fromCurseForge(project(file), "26.2");
        assertNotNull(release);
        assertEquals("2.1.0", release.version());
        assertEquals(UpdateMetadata.CURSEFORGE_PROJECT_URL + "/files/42", release.url());
    }

    @Test
    void curseForgeChoosesNewestVersionRatherThanFirstOrMostRecentlyUploadedFile() {
        String body = project(
                file(1, "nulltrace-26.2+v2.0.2.jar", "release", "Fabric", "26.2"),
                file(2, "nulltrace-26.2+v2.10.0.jar", "release", "Fabric", "26.2"),
                file(3, "nulltrace-26.2+v2.9.0.jar", "release", "Fabric", "26.2"));
        assertEquals("2.10.0", UpdateMetadata.fromCurseForge(body, "26.2").version());
    }

    @Test
    void curseForgeFiltersMinecraftLoaderReleaseTypeAndPrereleases() {
        String body = project(
                file(1, "nulltrace-26.3+v3.0.0.jar", "release", "Fabric", "26.3"),
                file(2, "nulltrace-26.2+v4.0.0.jar", "release", "Forge", "26.2"),
                file(3, "nulltrace-26.2+v5.0.0.jar", "beta", "Fabric", "26.2"),
                file(4, "nulltrace-26.2+v6.0.0.jar", "alpha", "Fabric", "26.2"),
                file(5, "nulltrace-26.2+v7.0.0-rc.1.jar", "release", "Fabric", "26.2"),
                file(6, "nulltrace-26.2+v2.1.0.jar", "release", "Fabric", "26.2"));
        assertEquals("2.1.0", UpdateMetadata.fromCurseForge(body, "26.2").version());
    }

    @Test
    void curseForgeRequiresBothMinecraftTagsAndMatchingJarRange() {
        // A 26.3 JAR accidentally tagged for 26.1 must not be offered to either client.
        String mislabeled = project(file(9031046, "nulltrace-26.3+v2.1.0.jar", "release",
                "Fabric", "26.1", "26.1.1", "26.1.2"));
        assertNull(UpdateMetadata.fromCurseForge(mislabeled, "26.1"));
        assertNull(UpdateMetadata.fromCurseForge(mislabeled, "26.3"));
    }

    @Test
    void curseForgeSupportsExplicitMinecraftRanges() {
        String body = project(file(1, "nulltrace-26.1-26.1.2+v2.1.0.jar", "release",
                "Fabric", "26.1", "26.1.1", "26.1.2", "26.1.3"));
        for (String version : new String[]{"26.1", "26.1.1", "26.1.2"}) {
            assertNotNull(UpdateMetadata.fromCurseForge(body, version));
        }
        assertNull(UpdateMetadata.fromCurseForge(body, "26.1.3"));
        assertNull(UpdateMetadata.fromCurseForge(body, "26.2"));
    }

    @Test
    void curseForgeDoesNotTreatTheWholeMinecraftMinorLineAsAnExactMatch() {
        String body = project(file(1, "nulltrace-1.21.11+v2.1.0.jar", "release",
                "Fabric", "1.21.1", "1.21.11"));
        assertNull(UpdateMetadata.fromCurseForge(body, "1.21.1"));
        assertNotNull(UpdateMetadata.fromCurseForge(body, "1.21.11"));
    }

    @Test
    void curseForgeRejectsAnotherProject() {
        JsonObject project = com.google.gson.JsonParser.parseString(project(
                file(1, "nulltrace-26.2+v2.1.0.jar", "release", "Fabric", "26.2"))).getAsJsonObject();
        project.addProperty("id", 1);
        assertNull(UpdateMetadata.fromCurseForge(project.toString(), "26.2"));
    }

    @Test
    void curseForgeSkipsMalformedFilesWithoutLosingAValidRelease() {
        JsonObject missingId = file(1, "nulltrace-26.2+v9.0.0.jar", "release", "Fabric", "26.2");
        missingId.remove("id");
        JsonObject negativeId = file(-1, "nulltrace-26.2+v9.0.0.jar", "release", "Fabric", "26.2");
        JsonObject invalidName = file(2, "nulltrace-26.2+v9.0.0.jar", "release", "Fabric", "26.2");
        invalidName.add("name", new JsonObject());
        JsonObject invalidVersions = file(3, "nulltrace-26.2+v9.0.0.jar", "release", "Fabric", "26.2");
        invalidVersions.add("versions", JsonNull.INSTANCE);
        JsonObject invalidType = file(4, "nulltrace-26.2+v9.0.0.jar", "release", "Fabric", "26.2");
        invalidType.add("type", new JsonArray());
        String body = project(JsonNull.INSTANCE, new JsonArray(), missingId, negativeId,
                invalidName, invalidVersions, invalidType,
                file(5, "nulltrace-26.2+v2.1.0.jar", "release", "Fabric", "26.2"));
        assertEquals("2.1.0", UpdateMetadata.fromCurseForge(body, "26.2").version());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nulltrace-26.2+vunknown.jar", "nulltrace-26.2+v2.1.0-sources.jar",
            "nulltrace-26.2+v2.1.0-dev.jar", "other-26.2+v2.1.0.jar", "nulltrace-26.2+v2.1.0.zip"})
    void curseForgeRejectsNonReleaseJarNames(String name) {
        assertNull(UpdateMetadata.fromCurseForge(project(file(1, name, "release", "Fabric", "26.2")), "26.2"));
    }

    @Test
    void gitLabUsesTagAndConstructsAnOfficialReleasePageUrl() {
        JsonObject release = gitLab("V2.1.0", "nulltrace-26.2+v2.1.0.jar");
        JsonObject links = new JsonObject();
        links.addProperty("self", "https://untrusted.example/release");
        release.add("_links", links);
        UpdateMetadata.Release result = UpdateMetadata.fromGitLab(release.toString(), "26.2");
        assertNotNull(result);
        assertEquals("2.1.0", result.version());
        assertEquals(UpdateMetadata.GITLAB_RELEASES_URL + "/V2.1.0", result.url());
    }

    @Test
    void gitLabFiltersIncompatibleOrMissingJarAssets() {
        assertNull(UpdateMetadata.fromGitLab(gitLab("v2.1.0", "nulltrace-26.3+v2.1.0.jar").toString(), "26.2"));
        assertNull(UpdateMetadata.fromGitLab(gitLab("v2.1.0", "nulltrace-26.2+v2.1.0.jar.sha256").toString(), "26.2"));
        assertNull(UpdateMetadata.fromGitLab(gitLab("v2.1.0", "nulltrace-26.2+v2.0.2.jar").toString(), "26.2"));
        assertNull(UpdateMetadata.fromGitLab("{\"tag_name\":\"v2.1.0\"}", "26.2"));
    }

    @Test
    void gitLabSupportsExplicitMinecraftRanges() {
        String body = gitLab("v2.1.0", "nulltrace-26.1-26.1.2+v2.1.0.jar").toString();
        assertNotNull(UpdateMetadata.fromGitLab(body, "26.1.1"));
        assertNull(UpdateMetadata.fromGitLab(body, "26.1.3"));
    }

    @Test
    void gitLabSupportsInitialDatedReleaseAssetNames() {
        String body = gitLab("v2.0.0", "NullTrace-26.3-2026.09.20-335b28f.jar").toString();
        assertEquals("2.0.0", UpdateMetadata.fromGitLab(body, "26.3").version());
        assertNull(UpdateMetadata.fromGitLab(body, "26.2"));
    }

    @Test
    void gitLabSkipsUpcomingAndPrereleaseTags() {
        JsonObject upcoming = gitLab("v3.0.0", "nulltrace-26.2+v3.0.0.jar");
        upcoming.addProperty("upcoming_release", true);
        assertNull(UpdateMetadata.fromGitLab(upcoming.toString(), "26.2"));
        assertNull(UpdateMetadata.fromGitLab(gitLab("v3.0.0-beta.1",
                "nulltrace-26.2+v3.0.0-beta.1.jar").toString(), "26.2"));
    }

    @Test
    void gitLabSkipsMalformedAssetsWithoutLosingAValidRelease() {
        JsonObject release = gitLab("v2.1.0", "nulltrace-26.2+v2.1.0.jar");
        JsonArray assets = release.getAsJsonObject("assets").getAsJsonArray("links");
        assets.add(JsonNull.INSTANCE);
        assets.add(new JsonObject());
        JsonObject invalid = new JsonObject();
        invalid.add("name", new JsonObject());
        assets.add(invalid);
        assertNotNull(UpdateMetadata.fromGitLab(release.toString(), "26.2"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not json", "null", "[]", "{}", "{\"files\":null}",
            "{\"id\":{},\"files\":[]}", "{\"tag_name\":[],\"assets\":null}"})
    void malformedFeedsFailClosed(String body) {
        assertNull(UpdateMetadata.fromCurseForge(body, "26.2"));
        assertNull(UpdateMetadata.fromGitLab(body, "26.2"));
    }

    @ParameterizedTest
    @CsvSource({"2.1.0, 2.0.2, true", "2.10.0, 2.9.0, true", "3.0.0, 2.99.99, true",
            "v2.1.0, V2.0.2, true", "2.1.0, 2.1.0-rc.1, true", "2.0.0, 2.0.2, false",
            "2.1.0, 2.1.0, false", "2.1.0-rc.1, 2.1.0, false",
            "2.1.0+build.2, 2.1.0+build.1, false", "unknown, 2.1.0, false", "2.1.0, unknown, false"})
    void versionComparisonIsSemanticAndNeverRecommendsADowngrade(String candidate, String current, boolean newer) {
        assertEquals(newer, UpdateMetadata.isNewerVersion(candidate, current));
    }

    @Test
    void missingVersionsDoNotTriggerAnUpdate() {
        assertFalse(UpdateMetadata.isNewerVersion(null, "2.0.2"));
        assertFalse(UpdateMetadata.isNewerVersion("2.1.0", null));
    }

    @Test
    void eitherSourceCanReportUpdatesIndependentlyAndCurseForgeWinsATie() {
        UpdateMetadata.Release curseForge = new UpdateMetadata.Release("2.1.0", "curseforge");
        UpdateMetadata.Release gitLab = new UpdateMetadata.Release("2.1.0", "gitlab");
        UpdateMetadata.Release newerGitLab = new UpdateMetadata.Release("2.2.0", "gitlab-newer");
        assertSame(curseForge, UpdateMetadata.newest(curseForge, gitLab));
        assertSame(newerGitLab, UpdateMetadata.newest(curseForge, newerGitLab));
        assertSame(curseForge, UpdateMetadata.newest(curseForge, null));
        assertSame(gitLab, UpdateMetadata.newest(null, gitLab));
        assertNull(UpdateMetadata.newest(null, null));
    }

    private static JsonObject file(long id, String name, String type, String... versions) {
        JsonObject file = new JsonObject();
        file.addProperty("id", id);
        file.addProperty("name", name);
        file.addProperty("type", type);
        JsonArray tags = new JsonArray();
        for (String version : versions) tags.add(version);
        file.add("versions", tags);
        return file;
    }

    private static String project(JsonElement... files) {
        JsonObject project = new JsonObject();
        project.addProperty("id", UpdateMetadata.CURSEFORGE_PROJECT_ID);
        JsonArray array = new JsonArray();
        for (JsonElement file : files) array.add(file);
        project.add("files", array);
        return project.toString();
    }

    private static JsonObject gitLab(String tag, String... names) {
        JsonObject release = new JsonObject();
        release.addProperty("tag_name", tag);
        JsonObject assets = new JsonObject();
        JsonArray links = new JsonArray();
        for (String name : names) {
            JsonObject link = new JsonObject();
            link.addProperty("name", name);
            links.add(link);
        }
        assets.add("links", links);
        release.add("assets", assets);
        return release;
    }
}
