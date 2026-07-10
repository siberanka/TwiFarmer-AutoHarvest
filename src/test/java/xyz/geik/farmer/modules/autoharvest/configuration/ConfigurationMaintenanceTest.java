package xyz.geik.farmer.modules.autoharvest.configuration;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationMaintenanceTest {

    private static final Logger LOGGER = Logger.getLogger("ConfigurationMaintenanceTest");

    Path temporaryDirectory;

    @BeforeEach
    void createTestDirectory() throws IOException {
        Path root = Path.of("target", "test-work");
        Files.createDirectories(root);
        temporaryDirectory = Files.createTempDirectory(root, "config-maintenance-");
    }

    @AfterEach
    void removeTestDirectory() throws IOException {
        if (temporaryDirectory == null || !Files.exists(temporaryDirectory)) {
            return;
        }
        try (var files = Files.walk(temporaryDirectory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                }
                catch (IOException exception) {
                    throw new IllegalStateException("Could not clean test path " + path, exception);
                }
            });
        }
    }

    @Test
    void createsACompleteDefaultConfigWithoutCreatingABackup() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);

        assertTrue(target.isFile());
        assertFalse(Files.exists(temporaryDirectory.resolve("backups")));
        assertTrue(Files.readString(target.toPath()).contains("# Farmer AutoHarvest module configuration"));
        assertFalse(snapshot.config().isStatus());
        assertFalse(snapshot.optimization().enabled());
        assertEquals(32, snapshot.optimization().maxJobsPerRun());
        assertEquals(List.of("WHEAT", "CARROT", "POTATO", "PUMPKIN"), snapshot.config().getItems());
    }

    @Test
    void backsUpAndRepairsInvalidKnownConfigEntries() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), """
                status: "enabled"
                requirePiston: false
                checkAllDirections: true
                withoutFarmer: 7
                checkStock: true
                defaultStatus: false
                customPerm: " "
                items: [WHEAT, AIR, MELON, WHEAT]
                optimize-module:
                  enable: "yes"
                  queue:
                    initial-delay-ticks: -1
                    continuation-delay-ticks: 0
                    max-jobs-per-run: 900
                    max-pending-jobs: 3
                    coalesce-duplicates: "true"
                custom-extension: preserved
                """, StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);
        YamlConfiguration repaired = YamlConfiguration.loadConfiguration(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertTrue(Files.readString(firstBackup(temporaryDirectory)).contains("status: \"enabled\""));
        assertFalse(repaired.getBoolean("status"));
        assertFalse(repaired.getBoolean("checkAllDirections"));
        assertEquals("farmer.autoharvest", repaired.getString("customPerm"));
        assertEquals(List.of("WHEAT", "MELON_SLICE"), repaired.getStringList("items"));
        assertFalse(repaired.getBoolean("optimize-module.enable"));
        assertEquals(2, repaired.getInt("optimize-module.queue.initial-delay-ticks"));
        assertEquals(1, repaired.getInt("optimize-module.queue.continuation-delay-ticks"));
        assertEquals(32, repaired.getInt("optimize-module.queue.max-jobs-per-run"));
        assertEquals(4096, repaired.getInt("optimize-module.queue.max-pending-jobs"));
        assertTrue(repaired.getBoolean("optimize-module.queue.coalesce-duplicates"));
        assertEquals("preserved", repaired.getString("custom-extension"));
    }

    @Test
    void backsUpMalformedYamlBeforeRestoringDefaults() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), "status: [unterminated", StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertTrue(Files.readString(firstBackup(temporaryDirectory)).contains("unterminated"));
        assertFalse(snapshot.config().isStatus());
        assertNotNull(YamlConfiguration.loadConfiguration(target).getConfigurationSection("optimize-module.queue"));
    }

    @Test
    void backsUpAndRepairsInvalidKnownLanguageEntries() throws Exception {
        Path languageDirectory = temporaryDirectory.resolve("lang");
        Files.createDirectories(languageDirectory);
        File target = languageDirectory.resolve("en.yml").toFile();
        Files.writeString(target.toPath(), """
                enabled: 9
                disabled: ""
                moduleGui:
                  icon:
                    guiInterface: "invalid"
                    skull: "not-base64!"
                    name: 4
                    lore: invalid
                custom-translation: preserved
                """, StandardCharsets.UTF_8);

        boolean repaired = reconcileLanguage(target, "en");
        YamlConfiguration language = YamlConfiguration.loadConfiguration(target);

        assertTrue(repaired);
        assertEquals(1, backupCount(languageDirectory));
        assertTrue(Files.readString(firstBackup(languageDirectory)).contains("not-base64!"));
        assertEquals("&aEnabled", language.getString("enabled"));
        assertEquals("h", language.getString("moduleGui.icon.guiInterface"));
        assertEquals("&eAuto Harvester", language.getString("moduleGui.icon.name"));
        assertTrue(language.isList("moduleGui.icon.lore"));
        assertEquals("preserved", language.getString("custom-translation"));
    }

    private ConfigurationMaintenance.ConfigSnapshot reconcileConfig(File target) throws IOException {
        try (InputStream defaults = resource("autoharvest/config.yml")) {
            return ConfigurationMaintenance.reconcileConfig(target, defaults, LOGGER);
        }
    }

    private boolean reconcileLanguage(File target, String language) throws IOException {
        try (InputStream defaults = resource("autoharvest/lang/" + language + ".yml")) {
            return ConfigurationMaintenance.reconcileLanguageFile(target, defaults, LOGGER);
        }
    }

    private InputStream resource(String path) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing test resource " + path);
        return stream;
    }

    private long backupCount() throws IOException {
        return backupCount(temporaryDirectory);
    }

    private long backupCount(Path parent) throws IOException {
        Path backups = parent.resolve("backups");
        if (!Files.isDirectory(backups)) {
            return 0;
        }
        try (var files = Files.list(backups)) {
            return files.count();
        }
    }

    private Path firstBackup(Path parent) throws IOException {
        Path backups = parent.resolve("backups");
        try (var files = Files.list(backups)) {
            return files.findFirst().orElseThrow();
        }
    }
}
