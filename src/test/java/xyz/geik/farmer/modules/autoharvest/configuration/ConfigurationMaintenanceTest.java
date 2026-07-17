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
        assertTrue(snapshot.tracking().scanEntireLoadedFarmerArea());
        assertTrue(snapshot.tracking().cropPriorityEnabled());
        assertEquals(3, snapshot.tracking().prioritizedScansBeforeNormal());
        assertEquals(35.0, snapshot.backpressure().slowdownAboveMspt());
        assertEquals(10, snapshot.backpressure().minimumWorkPercent());
        assertTrue(snapshot.stackedCrops().enabled());
        assertEquals(List.of("SUGAR_CANE", "CACTUS", "BAMBOO", "KELP"),
                snapshot.stackedCrops().items());
        assertEquals(32, snapshot.stackedCrops().maxSegmentsPerHarvest());
        assertTrue(snapshot.update().enabled());
        assertEquals(6, snapshot.update().checkIntervalHours());
        assertFalse(snapshot.logging().debugEnabled());
        assertEquals(300, snapshot.logging().debugIntervalSeconds());
        assertEquals(5, snapshot.logging().errorMaxSizeMegabytes());
        assertEquals(2, snapshot.logging().errorHistoryFiles());
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
        assertEquals(2, repaired.getInt("optimize-module.advanced.harvest-queue.first-run-delay-ticks"));
        assertEquals(1, repaired.getInt("optimize-module.advanced.harvest-queue.next-run-delay-ticks"));
        assertEquals(8, repaired.getInt("optimize-module.advanced.harvest-queue.harvests-per-run"));
        assertEquals(32, repaired.getInt("optimize-module.harvest.max-harvests-per-tick"));
        assertEquals(8, repaired.getInt("optimize-module.advanced.harvest-queue.region-runs-per-tick"));
        assertEquals(8192, repaired.getInt("optimize-module.advanced.harvest-queue.waiting-harvests"));
        assertTrue(repaired.getBoolean("optimize-module.advanced.harvest-queue.merge-duplicate-blocks"));
        assertFalse(repaired.getBoolean("optimize-module.harvest.delay-between-harvests.enable"));
        assertEquals("FARMER", repaired.getString("optimize-module.harvest.separate-speed-for"));
        assertEquals(2, repaired.getInt("optimize-module.harvest.delay-between-harvests.ticks"));
        assertFalse(repaired.getBoolean("optimize-module.harvest.pause-after-batch.enable"));
        assertEquals(64, repaired.getInt("optimize-module.harvest.pause-after-batch.after-harvests"));
        assertEquals(20, repaired.getInt("optimize-module.harvest.pause-after-batch.ticks"));
        assertFalse(repaired.contains("optimize-module.queue.per-scope-pacing"));
        assertEquals(200, repaired.getInt("optimize-module.crop-search.repeat-search.every-ticks"));
        assertEquals(4096, repaired.getInt("optimize-module.crop-search.limits.waiting-scans"));
        assertEquals("EVENTS", repaired.getString("optimize-module.crop-search.mode"));
        assertTrue(repaired.getBoolean("optimize-module.crop-search.triggers.player-sees-chunk"));
        assertEquals(32, repaired.getInt("optimize-module.crop-search.limits.sections-per-second"));
        assertEquals(512, repaired.getInt("optimize-module.crop-search.limits.crops-found-per-scan"));
        assertEquals(32, repaired.getInt("optimize-module.crop-search.limits.crops-queued-per-tick"));
        assertEquals(35.0, repaired.getDouble("optimize-module.server-load-protection.slow-down-at-mspt"));
        assertEquals(45.0, repaired.getDouble("optimize-module.server-load-protection.stop-at-mspt"));
        assertEquals(40.0, repaired.getDouble("optimize-module.server-load-protection.resume-below-mspt"));
        assertEquals(10, repaired.getInt("optimize-module.server-load-protection.minimum-speed-percent"));
        assertTrue(repaired.getBoolean("update-checker.enable"));
        assertEquals(6, repaired.getInt("update-checker.check-interval-hours"));
        assertEquals(5, repaired.getInt("update-checker.connect-timeout-seconds"));
        assertEquals(8, repaired.getInt("update-checker.request-timeout-seconds"));
        assertEquals(12, repaired.getInt("config-version"));
        assertEquals("preserved", repaired.getString("custom-extension"));
    }

    @Test
    void migratesV8ScopePacingWithoutChangingItsBehavior() throws Exception {
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
        assertEquals(12, migrated.getInt("config-version"));
        assertEquals(47, snapshot.optimization().globalMaxJobsPerTick());
        assertTrue(snapshot.optimization().perHarvestDelayEnabled());
        assertEquals(HarvestPacingScope.REGION, snapshot.optimization().harvestScope());
        assertEquals(7, snapshot.optimization().perHarvestDelayTicks());
        assertFalse(snapshot.optimization().batchPauseEnabled());
        assertFalse(migrated.contains("optimize-module.queue.global-max-jobs-per-tick"));
        assertFalse(migrated.contains("optimize-module.queue.per-scope-pacing"));
        assertEquals(47, migrated.getInt("optimize-module.harvest.max-harvests-per-tick"));
        assertEquals("LAND", migrated.getString("optimize-module.harvest.separate-speed-for"));
        assertEquals("preserved", migrated.getString("custom-extension"));
    }

    @Test
    void migratesV9OptimizationNamesAndPreservesUnknownEntries() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), """
                config-version: 9
                status: true
                optimize-module:
                  enable: true
                  harvest-control:
                    global-max-harvests-per-tick: 47
                    scope: "OWNER"
                    per-harvest-delay:
                      enable: true
                      ticks: 7
                    batch-pause:
                      enable: true
                      harvests-before-pause: 11
                      pause-ticks: 40
                  tracking:
                    mode: "HYBRID"
                    conditions:
                      growth-events: false
                      fertilize-events: false
                      crop-place-events: false
                      scan-on-chunk-load: true
                      scan-on-farmer-purchase: false
                      scan-on-player-join: false
                      scan-on-player-chunk-load: false
                      farmer-regions-only: false
                    reconcile-interval-ticks: 400
                    max-chunks-per-cycle: 3
                    max-tracked-chunks: 9000
                    max-concurrent-scans: 2
                    max-snapshot-captures-per-tick: 2
                    max-scan-starts-per-second: 5
                    max-sections-per-second: 40
                    max-block-checks-per-slice: 6000
                    max-pending-scans: 5000
                    max-candidates-per-scan: 600
                    max-candidate-admissions-per-tick: 40
                    purchase-radius-chunks: 9
                    bootstrap-radius-chunks: 4
                    custom-extension: preserved
                  adaptive-backpressure:
                    enable: false
                    slowdown-above-mspt: 36.0
                    pause-above-mspt: 46.0
                    resume-below-mspt: 41.0
                    minimum-work-percent: 15
                    check-interval-ticks: 30
                    pause-above-region-task-delay-millis: 120
                    region-cooldown-ticks: 140
                  queue:
                    initial-delay-ticks: 3
                    continuation-delay-ticks: 2
                    max-jobs-per-run: 9
                    max-scheduler-submissions-per-tick: 10
                    max-pending-jobs: 9000
                    coalesce-duplicates: false
                  telemetry:
                    enable: false
                    log-interval-seconds: 600
                """, StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertEquals(12, migrated.getInt("config-version"));
        assertEquals(3, snapshot.optimization().initialDelayTicks());
        assertEquals(2, snapshot.optimization().continuationDelayTicks());
        assertEquals(9, snapshot.optimization().maxJobsPerRun());
        assertEquals(47, snapshot.optimization().globalMaxJobsPerTick());
        assertEquals(10, snapshot.optimization().maxSchedulerSubmissionsPerTick());
        assertEquals(9000, snapshot.optimization().maxPendingJobs());
        assertFalse(snapshot.optimization().coalesceDuplicates());
        assertEquals(HarvestPacingScope.OWNER, snapshot.optimization().harvestScope());
        assertTrue(snapshot.optimization().perHarvestDelayEnabled());
        assertEquals(7, snapshot.optimization().perHarvestDelayTicks());
        assertTrue(snapshot.optimization().batchPauseEnabled());
        assertEquals(11, snapshot.optimization().harvestsBeforePause());
        assertEquals(40, snapshot.optimization().batchPauseTicks());
        assertEquals(TrackingMode.HYBRID, snapshot.tracking().mode());
        assertFalse(snapshot.tracking().growthEvents());
        assertFalse(snapshot.tracking().fertilizeEvents());
        assertFalse(snapshot.tracking().cropPlaceEvents());
        assertTrue(snapshot.tracking().scanOnChunkLoad());
        assertFalse(snapshot.tracking().scanOnFarmerPurchase());
        assertFalse(snapshot.tracking().scanOnPlayerJoin());
        assertFalse(snapshot.tracking().scanOnPlayerChunkLoad());
        assertTrue(snapshot.tracking().scanEntireLoadedFarmerArea());
        assertFalse(snapshot.tracking().farmerRegionsOnly());
        assertTrue(snapshot.tracking().cropPriorityEnabled());
        assertEquals(3, snapshot.tracking().prioritizedScansBeforeNormal());
        assertEquals(400, snapshot.tracking().reconcileIntervalTicks());
        assertEquals(3, snapshot.tracking().maxChunksPerCycle());
        assertEquals(9000, snapshot.tracking().maxTrackedChunks());
        assertEquals(2, snapshot.tracking().maxConcurrentScans());
        assertEquals(2, snapshot.tracking().maxSnapshotCapturesPerTick());
        assertEquals(5, snapshot.tracking().maxScanStartsPerSecond());
        assertEquals(40, snapshot.tracking().maxSectionsPerSecond());
        assertEquals(6000, snapshot.tracking().maxBlockChecksPerSlice());
        assertEquals(5000, snapshot.tracking().maxPendingScans());
        assertEquals(600, snapshot.tracking().maxCandidatesPerScan());
        assertEquals(40, snapshot.tracking().maxCandidateAdmissionsPerTick());
        assertEquals(9, snapshot.tracking().purchaseRadiusChunks());
        assertEquals(4, snapshot.tracking().bootstrapRadiusChunks());
        assertFalse(snapshot.backpressure().enabled());
        assertEquals(36.0, snapshot.backpressure().slowdownAboveMspt());
        assertEquals(46.0, snapshot.backpressure().pauseAboveMspt());
        assertEquals(41.0, snapshot.backpressure().resumeBelowMspt());
        assertEquals(15, snapshot.backpressure().minimumWorkPercent());
        assertEquals(30, snapshot.backpressure().checkIntervalTicks());
        assertEquals(120, snapshot.backpressure().pauseAboveRegionTaskDelayMillis());
        assertEquals(140, snapshot.backpressure().regionCooldownTicks());
        assertFalse(snapshot.logging().debugEnabled());
        assertEquals(600, snapshot.logging().debugIntervalSeconds());
        assertEquals(5, snapshot.logging().errorMaxSizeMegabytes());
        assertEquals(2, snapshot.logging().errorHistoryFiles());
        assertEquals("PLAYER", migrated.getString("optimize-module.harvest.separate-speed-for"));
        assertEquals("BOTH", migrated.getString("optimize-module.crop-search.mode"));
        assertEquals(9, migrated.getInt("optimize-module.crop-search.scan-radius.new-farmer-radius-chunks"));
        assertEquals(4, migrated.getInt("optimize-module.crop-search.scan-radius.player-radius-chunks"));
        assertTrue(Files.readString(target.toPath()).contains(
                "# Visible chunk radius searched around players on join, reload and movement."));
        assertEquals("preserved", migrated.getString("optimize-module.tracking.custom-extension"));
        assertFalse(migrated.contains("optimize-module.harvest-control"));
        assertFalse(migrated.contains("optimize-module.adaptive-backpressure"));
        assertFalse(migrated.contains("optimize-module.queue"));
        assertFalse(migrated.contains("optimize-module.telemetry"));
        assertFalse(migrated.contains("optimize-module.advanced.logging"));
        assertFalse(migrated.getBoolean("logging.debug"));
        assertEquals(600, migrated.getInt("logging.debug-interval-seconds"));

        ConfigurationMaintenance.ConfigSnapshot secondPass = reconcileConfig(target);
        assertFalse(secondPass.repaired());
        assertEquals(1, backupCount());
    }

    @Test
    void migratesV10TelemetryWithoutEnablingConsoleDebug() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), """
                config-version: 10
                status: true
                optimize-module:
                  enable: true
                  advanced:
                    logging:
                      enable: true
                      interval-seconds: 450
                custom-extension: preserved
                """, StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertEquals(12, migrated.getInt("config-version"));
        assertFalse(snapshot.logging().debugEnabled());
        assertEquals(450, snapshot.logging().debugIntervalSeconds());
        assertFalse(migrated.contains("optimize-module.advanced.logging"));
        assertEquals("preserved", migrated.getString("custom-extension"));
    }

    @Test
    void migratesV11SearchSettingsWithoutOverwritingExplicitValues() throws Exception {
        File target = temporaryDirectory.resolve("config.yml").toFile();
        Files.writeString(target.toPath(), """
                config-version: 11
                status: true
                optimize-module:
                  enable: true
                  crop-search:
                    triggers:
                      entire-loaded-farmer-area: false
                    priority:
                      enable: false
                      prioritized-scans-before-normal: 5
                custom-extension: preserved
                """, StandardCharsets.UTF_8);

        ConfigurationMaintenance.ConfigSnapshot snapshot = reconcileConfig(target);
        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(target);

        assertTrue(snapshot.repaired());
        assertEquals(1, backupCount());
        assertEquals(12, migrated.getInt("config-version"));
        assertFalse(snapshot.tracking().scanEntireLoadedFarmerArea());
        assertFalse(snapshot.tracking().cropPriorityEnabled());
        assertEquals(5, snapshot.tracking().prioritizedScansBeforeNormal());
        assertEquals("preserved", migrated.getString("custom-extension"));

        ConfigurationMaintenance.ConfigSnapshot secondPass = reconcileConfig(target);
        assertFalse(secondPass.repaired());
        assertEquals(1, backupCount());
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
        assertNotNull(YamlConfiguration.loadConfiguration(target)
                .getConfigurationSection("optimize-module.advanced.harvest-queue"));
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
