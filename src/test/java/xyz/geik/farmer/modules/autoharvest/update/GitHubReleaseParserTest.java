package xyz.geik.farmer.modules.autoharvest.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleaseParserTest {

    @Test
    void selectsTheValidatedJarDownload() {
        GitHubReleaseParser.ReleaseInfo release = GitHubReleaseParser.parse("""
                {
                  "tag_name": "v1.2.3",
                  "html_url": "https://github.com/siberanka/TwiFarmer-AutoHarvest/releases/tag/v1.2.3",
                  "assets": [{
                    "name": "Farmer-AutoHarvest-1.2.3.jar",
                    "browser_download_url": "https://github.com/siberanka/TwiFarmer-AutoHarvest/releases/download/v1.2.3/Farmer-AutoHarvest-1.2.3.jar"
                  }]
                }
                """).orElseThrow();

        assertEquals("v1.2.3", release.tag());
        assertEquals("https://github.com/siberanka/TwiFarmer-AutoHarvest/releases/download/v1.2.3/Farmer-AutoHarvest-1.2.3.jar",
                release.downloadUrl());
    }

    @Test
    void rejectsMalformedOversizedAndForeignReleaseData() {
        assertTrue(GitHubReleaseParser.parse("not-json").isEmpty());
        assertTrue(GitHubReleaseParser.parse("x".repeat(65_537)).isEmpty());
        assertTrue(GitHubReleaseParser.parse("""
                {
                  "tag_name": "v9.9.9",
                  "html_url": "https://evil.example/releases/v9.9.9",
                  "assets": [{
                    "name": "Farmer-AutoHarvest-9.9.9.jar",
                    "browser_download_url": "https://evil.example/plugin.jar"
                  }]
                }
                """).isEmpty());
    }
}
