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
        assertEquals(8, snapshot.optimization().maxJobsPerRun());
        assertEquals(32, snapshot.optimization().globalMaxJobsPerTick());
        assertEquals(HarvestPacingScope.FARMER, snapshot.optimization().harvestScope());
        assertFalse(snapshot.optimization().perHarvestDelayEnabled());
        assertEquals(2, snapshot.optimization().perHarvestDelayTicks());
        assertFalse(snapshot.optimization().batchPauseEnabled());
        assertEquals(64, snapshot.optimization().harvestsBeforePause());
        assertEquals(20, snapshot.optimization().batchPauseTicks());
        assertEquals(1, snapshot.tracking().maxConcurrentScans());
        assertEquals(512, snapshot.tracking().maxCandidatesPerScan());
        assertEquals(32, snapshot.tracking().maxCandidateAdmissionsPerTick());
        assertEquals(TrackingMode.EVENT_DRIVEN, snapshot.tracking().mode());
        assertTrue(snapshot.tracking().scanOnPlayerChunkLoad());
        assertEquals(35.0, snapshot.backpressure().slowdownAboveMspt());
        assertEquals(10, snapshot.backpressure().minimumWorkPercent());
        assertTrue(snapshot.stackedCrops().enabled());
        assertEquals(List.of("SUGAR_CANE", "CACTUS", "BAMBOO", "KELP"),
                snapshot.stackedCrops().items());
        assertEquals(32, snapshot.stackedCrops().maxSegmentsPerHarvest());
        assertTrue(snapshot.update().enabled());
        assertEquals(6, snapshot.update().checkIntervalHours());
        assertEquals(List.of("WHEAT", "CARROT", "POTATO", "PUMPKIN"), snapshot.config().getItems());
    }

    @Test
    void preservesEnabledStatusWhileAddingMissingEntries() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), "status: true\n", StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);

        assertTrue(snapshot.repaired());
        assertTrue(snapshot.config().isStatus());
        assertTrue(YamlConfiguration.loadConfiguration(target).getBoolean("status"));
        assertEquals(1, backupCount());
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
                stacked-crops:
                  enable: "yes"
                  items: [BAMBOO, AIR, KELP_PLANT, BAMBOO]
                  max-segments-per-harvest: 999
                update-checker:
                  enable: "yes"
                  check-interval-hours: 0
                  connect-timeout-seconds: 100
                  request-timeout-seconds: 1
                optimize-module:
                  enable: "yes"
                  queue:
                    initial-delay-ticks: -1
                    continuation-delay-ticks: 0
                    max-jobs-per-run: 900
                    global-max-jobs-per-tick: 900
                    max-scheduler-submissions-per-tick: 900
                    max-pending-jobs: 3
                    coalesce-duplicates: "true"
                    per-scope-pacing:
                      enable: "yes"
                      scope: "SERVER"
                      delay-ticks: 0
                  tracking:
                    mode: "BROKEN"
                    max-sections-per-second: 9999
                    max-candidate-admissions-per-tick: 9999
                    conditions:
                      scan-on-player-chunk-load: "yes"
                  adaptive-backpressure:
                    slowdown-above-mspt: 60
                    pause-above-mspt: 30
                    resume-below-mspt: 40
                    minimum-work-percent: 0
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
        assertTrue(repaired.getBoolean("stacked-crops.enable"));
        assertEquals(List.of("BAMBOO", "KELP"), repaired.getStringList("stacked-crops.items"));
        assertEquals(32, repaired.getInt("stacked-crops.max-segments-per-harvest"));
        assertFalse(repaired.getBoolean("optimize-module.enable"));
        assertEquals(2, repaired.getInt("optimize-module.queue.initial-delay-ticks"));
        assertEquals(1, repaired.getInt("optimize-module.queue.continuation-delay-ticks"));
        assertEquals(8, repaired.getInt("optimize-module.queue.max-jobs-per-run"));
        assertEquals(32, repaired.getInt("optimize-module.harvest-control.global-max-harvests-per-tick"));
        assertEquals(8, repaired.getInt("optimize-module.queue.max-scheduler-submissions-per-tick"));
        assertEquals(8192, repaired.getInt("optimize-module.queue.max-pending-jobs"));
        assertTrue(repaired.getBoolean("optimize-module.queue.coalesce-duplicates"));
        assertFalse(repaired.getBoolean("optimize-module.harvest-control.per-harvest-delay.enable"));
        assertEquals("FARMER", repaired.getString("optimize-module.harvest-control.scope"));
        assertEquals(2, repaired.getInt("optimize-module.harvest-control.per-harvest-delay.ticks"));
        assertFalse(repaired.getBoolean("optimize-module.harvest-control.batch-pause.enable"));
        assertEquals(64, repaired.getInt(
                "optimize-module.harvest-control.batch-pause.harvests-before-pause"));
        assertEquals(20, repaired.getInt("optimize-module.harvest-control.batch-pause.pause-ticks"));
        assertFalse(repaired.contains("optimize-module.queue.per-scope-pacing"));
        assertEquals(200, repaired.getInt("optimize-module.tracking.reconcile-interval-ticks"));
        assertEquals(4096, repaired.getInt("optimize-module.tracking.max-pending-scans"));
        assertEquals("EVENT_DRIVEN", repaired.getString("optimize-module.tracking.mode"));
        assertTrue(repaired.getBoolean(
                "optimize-module.tracking.conditions.scan-on-player-chunk-load"));
        assertEquals(32, repaired.getInt("optimize-module.tracking.max-sections-per-second"));
        assertEquals(512, repaired.getInt("optimize-module.tracking.max-candidates-per-scan"));
        assertEquals(32, repaired.getInt("optimize-module.tracking.max-candidate-admissions-per-tick"));
        assertEquals(35.0, repaired.getDouble("optimize-module.adaptive-backpressure.slowdown-above-mspt"));
        assertEquals(45.0, repaired.getDouble("optimize-module.adaptive-backpressure.pause-above-mspt"));
        assertEquals(40.0, repaired.getDouble("optimize-module.adaptive-backpressure.resume-below-mspt"));
        assertEquals(10, repaired.getInt("optimize-module.adaptive-backpressure.minimum-work-percent"));
        assertTrue(repaired.getBoolean("update-checker.enable"));
        assertEquals(6, repaired.getInt("update-checker.check-interval-hours"));
        assertEquals(5, repaired.getInt("update-checker.connect-timeout-seconds"));
        assertEquals(8, repaired.getInt("update-checker.request-timeout-seconds"));
        assertEquals(9, repaired.getInt("config-version"));
        assertEquals("preserved", repaired.getString("custom-extension"));
    }

    @Test
    void migratesLegacyScopePacingWithoutChangingItsBehavior() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), """
                config-version: 8
                status: true
                optimize-module:
                  enable: true
                  queue:
                    global-max-jobs-per-tick: 47
                    per-scope-pacing:
                      enable: true
                      scope: "REGION"
                      delay-ticks: 7
                custom-extension: preserved
                """, StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertEquals(9, migrated.getInt("config-version"));
        assertEquals(47, snapshot.optimization().globalMaxJobsPerTick());
        assertTrue(snapshot.optimization().perHarvestDelayEnabled());
        assertEquals(HarvestPacingScope.REGION, snapshot.optimization().harvestScope());
        assertEquals(7, snapshot.optimization().perHarvestDelayTicks());
        assertFalse(snapshot.optimization().batchPauseEnabled());
        assertFalse(migrated.contains("optimize-module.queue.global-max-jobs-per-tick"));
        assertFalse(migrated.contains("optimize-module.queue.per-scope-pacing"));
        assertEquals("preserved", migrated.getString("custom-extension"));
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
        assertTrue(language.getString("update.available").contains("{module}"));
        assertTrue(language.getString("update.available").contains("{url}"));
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
