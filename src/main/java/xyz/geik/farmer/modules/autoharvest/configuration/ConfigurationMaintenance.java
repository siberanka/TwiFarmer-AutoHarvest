package xyz.geik.farmer.modules.autoharvest.configuration;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import xyz.geik.farmer.modules.autoharvest.handlers.CropHarvesting;
import xyz.geik.glib.shades.xseries.XMaterial;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Repairs known module configuration and language entries without discarding
 * unknown entries that may belong to a newer module version.
 *
 * @author siberanka
 * @since 1.2.0
 */
public final class ConfigurationMaintenance {

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");
    private static final String OPTIMIZE_MODULE = "optimize-module";
    private static final String HARVEST = OPTIMIZE_MODULE + ".harvest";
    private static final String HARVEST_DELAY = HARVEST + ".delay-between-harvests";
    private static final String HARVEST_PAUSE = HARVEST + ".pause-after-batch";
    private static final String CROP_SEARCH = OPTIMIZE_MODULE + ".crop-search";
    private static final String SEARCH_TRIGGERS = CROP_SEARCH + ".triggers";
    private static final String SCAN_RADIUS = CROP_SEARCH + ".scan-radius";
    private static final String REPEAT_SEARCH = CROP_SEARCH + ".repeat-search";
    private static final String SEARCH_LIMITS = CROP_SEARCH + ".limits";
    private static final String LOAD_PROTECTION = OPTIMIZE_MODULE + ".server-load-protection";
    private static final String ADVANCED = OPTIMIZE_MODULE + ".advanced";
    private static final String HARVEST_QUEUE = ADVANCED + ".harvest-queue";
    private static final String LOGGING = ADVANCED + ".logging";

    private static final String V9_HARVEST = OPTIMIZE_MODULE + ".harvest-control";
    private static final String V9_HARVEST_DELAY = V9_HARVEST + ".per-harvest-delay";
    private static final String V9_HARVEST_PAUSE = V9_HARVEST + ".batch-pause";
    private static final String V9_QUEUE = OPTIMIZE_MODULE + ".queue";
    private static final String V8_SCOPE_PACING = V9_QUEUE + ".per-scope-pacing";
    private static final String V9_TRACKING = OPTIMIZE_MODULE + ".tracking";
    private static final String V9_CONDITIONS = V9_TRACKING + ".conditions";
    private static final String V9_BACKPRESSURE = OPTIMIZE_MODULE + ".adaptive-backpressure";
    private static final String V9_TELEMETRY = OPTIMIZE_MODULE + ".telemetry";
    private static final String UPDATE_CHECKER = "update-checker";
    private static final String STACKED_CROPS = "stacked-crops";

    private ConfigurationMaintenance() {
    }

    public static @NotNull ConfigSnapshot reconcileConfig(
            @NotNull File target,
            @NotNull InputStream bundledDefaults,
            @NotNull Logger logger
    ) throws IOException {
        byte[] defaultBytes = readBundled(bundledDefaults);
        boolean created = writeBundledIfMissing(target, defaultBytes);
        YamlConfiguration defaults = loadBundled(new ByteArrayInputStream(defaultBytes));
        LoadedYaml loaded = loadExisting(target, logger);
        boolean changed = loaded.malformed;
        changed |= repairConfig(loaded.configuration, defaults);

        if (changed) {
            backupBeforeWrite(target, loaded.existed);
            save(target, loaded.configuration);
            logger.info("AutoHarvest repaired config.yml" + (loaded.existed ? " after creating a backup." : "."));
        }
        else if (created) {
            logger.info("AutoHarvest created config.yml from bundled defaults.");
        }

        return new ConfigSnapshot(
                ConfigFile.from(loaded.configuration),
                StackedCropSettings.from(loaded.configuration),
                readOptimizationSettings(loaded.configuration),
                readTrackingSettings(loaded.configuration),
                readBackpressureSettings(loaded.configuration),
                readTelemetrySettings(loaded.configuration),
                readUpdateSettings(loaded.configuration),
                changed
        );
    }

    public static boolean reconcileLanguageFile(
            @NotNull File target,
            @NotNull InputStream bundledDefaults,
            @NotNull Logger logger
    ) throws IOException {
        byte[] defaultBytes = readBundled(bundledDefaults);
        boolean created = writeBundledIfMissing(target, defaultBytes);
        YamlConfiguration defaults = loadBundled(new ByteArrayInputStream(defaultBytes));
        LoadedYaml loaded = loadExisting(target, logger);
        boolean changed = loaded.malformed;
        changed |= repairLanguage(loaded.configuration, defaults);

        if (changed) {
            backupBeforeWrite(target, loaded.existed);
            save(target, loaded.configuration);
            logger.info("AutoHarvest repaired language file " + target.getName()
                    + (loaded.existed ? " after creating a backup." : "."));
        }
        else if (created) {
            logger.info("AutoHarvest created language file " + target.getName() + " from bundled defaults.");
        }
        return changed;
    }

    private static boolean repairConfig(YamlConfiguration configuration, YamlConfiguration defaults) {
        boolean changed = false;
        changed |= migrateV8ScopePacing(configuration);
        changed |= migrateV9OptimizationPaths(configuration);
        changed |= normalizeFriendlyOptionNames(configuration);
        changed |= repairInteger(configuration, defaults, "config-version", 10, 10);
        changed |= repairBoolean(configuration, defaults, "status");
        changed |= repairBoolean(configuration, defaults, "requirePiston");
        changed |= repairBoolean(configuration, defaults, "checkAllDirections");
        changed |= repairBoolean(configuration, defaults, "withoutFarmer");
        changed |= repairBoolean(configuration, defaults, "checkStock");
        changed |= repairBoolean(configuration, defaults, "defaultStatus");
        changed |= repairString(configuration, defaults, "customPerm", ConfigurationMaintenance::isValidPermission);
        changed |= repairCropList(configuration, defaults);
        changed |= ensureSection(configuration, STACKED_CROPS);
        changed |= repairBoolean(configuration, defaults, STACKED_CROPS + ".enable");
        changed |= repairStackedCropList(configuration, defaults);
        changed |= repairInteger(configuration, defaults,
                STACKED_CROPS + ".max-segments-per-harvest", 1, 256);
        changed |= ensureSection(configuration, UPDATE_CHECKER);
        changed |= repairBoolean(configuration, defaults, UPDATE_CHECKER + ".enable");
        changed |= repairInteger(configuration, defaults, UPDATE_CHECKER + ".check-interval-hours", 1, 168);
        changed |= repairInteger(configuration, defaults, UPDATE_CHECKER + ".connect-timeout-seconds", 2, 30);
        changed |= repairInteger(configuration, defaults, UPDATE_CHECKER + ".request-timeout-seconds", 3, 60);

        changed |= ensureSection(configuration, OPTIMIZE_MODULE);
        changed |= ensureSection(configuration, HARVEST);
        changed |= ensureSection(configuration, HARVEST_DELAY);
        changed |= ensureSection(configuration, HARVEST_PAUSE);
        changed |= ensureSection(configuration, CROP_SEARCH);
        changed |= ensureSection(configuration, SEARCH_TRIGGERS);
        changed |= ensureSection(configuration, SCAN_RADIUS);
        changed |= ensureSection(configuration, REPEAT_SEARCH);
        changed |= ensureSection(configuration, SEARCH_LIMITS);
        changed |= ensureSection(configuration, LOAD_PROTECTION);
        changed |= ensureSection(configuration, ADVANCED);
        changed |= ensureSection(configuration, HARVEST_QUEUE);
        changed |= ensureSection(configuration, LOGGING);
        changed |= repairBoolean(configuration, defaults, OPTIMIZE_MODULE + ".enable");
        changed |= repairInteger(configuration, defaults, HARVEST + ".max-harvests-per-tick", 1, 256);
        changed |= repairString(configuration, defaults, HARVEST + ".separate-speed-for",
                ConfigurationMaintenance::isFriendlyHarvestScope);
        changed |= repairBoolean(configuration, defaults, HARVEST_DELAY + ".enable");
        changed |= repairInteger(configuration, defaults, HARVEST_DELAY + ".ticks", 1, 200);
        changed |= repairBoolean(configuration, defaults, HARVEST_PAUSE + ".enable");
        changed |= repairInteger(configuration, defaults, HARVEST_PAUSE + ".after-harvests", 1, 10_000);
        changed |= repairInteger(configuration, defaults, HARVEST_PAUSE + ".ticks", 1, 72_000);
        changed |= repairString(configuration, defaults, CROP_SEARCH + ".mode",
                ConfigurationMaintenance::isFriendlyTrackingMode);
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".natural-growth");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".bone-meal");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".crop-placement");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".chunk-load");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".new-farmer");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".player-join");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".player-sees-chunk");
        changed |= repairBoolean(configuration, defaults, SEARCH_TRIGGERS + ".farmer-areas-only");
        changed |= repairInteger(configuration, defaults, SCAN_RADIUS + ".new-farmer-radius-chunks", 1, 32);
        changed |= repairInteger(configuration, defaults, SCAN_RADIUS + ".player-radius-chunks", 1, 16);
        changed |= repairInteger(configuration, defaults, REPEAT_SEARCH + ".every-ticks", 20, 72_000);
        changed |= repairInteger(configuration, defaults, REPEAT_SEARCH + ".chunks-per-run", 1, 32);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".remembered-chunks", 64, 32_768);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".scans-at-once", 1, 4);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".snapshots-per-tick", 1, 4);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".new-scans-per-second", 1, 20);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".sections-per-second", 1, 256);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".blocks-per-async-task", 256, 16_384);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".waiting-scans", 64, 16_384);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".crops-found-per-scan", 16, 1_024);
        changed |= repairInteger(configuration, defaults, SEARCH_LIMITS + ".crops-queued-per-tick", 1, 128);
        changed |= repairBoolean(configuration, defaults, LOAD_PROTECTION + ".enable");
        changed |= repairDouble(configuration, defaults, LOAD_PROTECTION + ".slow-down-at-mspt", 10.0, 99.0);
        changed |= repairDouble(configuration, defaults, LOAD_PROTECTION + ".stop-at-mspt", 20.0, 100.0);
        changed |= repairDouble(configuration, defaults, LOAD_PROTECTION + ".resume-below-mspt", 10.0, 99.0);
        changed |= repairInteger(configuration, defaults, LOAD_PROTECTION + ".minimum-speed-percent", 1, 100);
        changed |= repairInteger(configuration, defaults, LOAD_PROTECTION + ".check-every-ticks", 1, 200);
        changed |= repairInteger(configuration, defaults, LOAD_PROTECTION + ".region-delay-limit-millis", 50, 5_000);
        changed |= repairInteger(configuration, defaults, LOAD_PROTECTION + ".region-recovery-ticks", 20, 1_200);
        changed |= repairInteger(configuration, defaults, HARVEST_QUEUE + ".first-run-delay-ticks", 1, 20);
        changed |= repairInteger(configuration, defaults, HARVEST_QUEUE + ".next-run-delay-ticks", 1, 20);
        changed |= repairInteger(configuration, defaults, HARVEST_QUEUE + ".harvests-per-run", 1, 64);
        changed |= repairInteger(configuration, defaults, HARVEST_QUEUE + ".region-runs-per-tick", 1, 64);
        changed |= repairInteger(configuration, defaults, HARVEST_QUEUE + ".waiting-harvests", 64, 16_384);
        changed |= repairBoolean(configuration, defaults, HARVEST_QUEUE + ".merge-duplicate-blocks");
        changed |= repairBoolean(configuration, defaults, LOGGING + ".enable");
        changed |= repairInteger(configuration, defaults, LOGGING + ".interval-seconds", 30, 3_600);

        if (configuration.getDouble(LOAD_PROTECTION + ".resume-below-mspt")
                >= configuration.getDouble(LOAD_PROTECTION + ".stop-at-mspt")
                || configuration.getDouble(LOAD_PROTECTION + ".slow-down-at-mspt")
                >= configuration.getDouble(LOAD_PROTECTION + ".stop-at-mspt")) {
            configuration.set(LOAD_PROTECTION + ".slow-down-at-mspt",
                    defaults.getDouble(LOAD_PROTECTION + ".slow-down-at-mspt"));
            configuration.set(LOAD_PROTECTION + ".stop-at-mspt",
                    defaults.getDouble(LOAD_PROTECTION + ".stop-at-mspt"));
            configuration.set(LOAD_PROTECTION + ".resume-below-mspt",
                    defaults.getDouble(LOAD_PROTECTION + ".resume-below-mspt"));
            changed = true;
        }

        if (!configuration.getBoolean("requirePiston") && configuration.getBoolean("checkAllDirections")) {
            configuration.set("checkAllDirections", false);
            changed = true;
        }
        changed |= addMissingComments(configuration, defaults, OPTIMIZE_MODULE);
        return changed;
    }

    private static boolean repairLanguage(YamlConfiguration configuration, YamlConfiguration defaults) {
        boolean changed = false;
        changed |= ensureSection(configuration, "moduleGui");
        changed |= ensureSection(configuration, "moduleGui.icon");
        changed |= ensureSection(configuration, "update");
        changed |= repairString(configuration, defaults, "enabled", value -> !value.isBlank());
        changed |= repairString(configuration, defaults, "disabled", value -> !value.isBlank());
        changed |= repairString(configuration, defaults, "moduleGui.icon.guiInterface",
                value -> value.length() == 1 && !Character.isWhitespace(value.charAt(0)));
        changed |= repairString(configuration, defaults, "moduleGui.icon.skull", ConfigurationMaintenance::isValidBase64);
        changed |= repairString(configuration, defaults, "moduleGui.icon.name", value -> !value.isBlank());
        changed |= repairLore(configuration, defaults, "moduleGui.icon.lore");
        changed |= repairString(configuration, defaults, "update.available",
                value -> hasPlaceholders(value, "{module}", "{current}", "{latest}", "{url}"));
        return changed;
    }

    private static boolean migrateV8ScopePacing(YamlConfiguration configuration) {
        boolean changed = false;
        String legacyGlobalLimit = V9_QUEUE + ".global-max-jobs-per-tick";
        String globalLimit = V9_HARVEST + ".global-max-harvests-per-tick";
        if (configuration.contains(legacyGlobalLimit)) {
            copyIfMissing(configuration, legacyGlobalLimit, globalLimit);
            configuration.set(legacyGlobalLimit, null);
            changed = true;
        }

        if (configuration.isConfigurationSection(V8_SCOPE_PACING)) {
            copyIfMissing(configuration, V8_SCOPE_PACING + ".scope", V9_HARVEST + ".scope");
            copyIfMissing(configuration, V8_SCOPE_PACING + ".enable", V9_HARVEST_DELAY + ".enable");
            copyIfMissing(configuration, V8_SCOPE_PACING + ".delay-ticks", V9_HARVEST_DELAY + ".ticks");
            configuration.set(V8_SCOPE_PACING, null);
            changed = true;
        }
        return changed;
    }

    private static boolean migrateV9OptimizationPaths(YamlConfiguration configuration) {
        boolean changed = false;
        changed |= move(configuration, V9_HARVEST + ".global-max-harvests-per-tick",
                HARVEST + ".max-harvests-per-tick");
        changed |= move(configuration, V9_HARVEST + ".scope", HARVEST + ".separate-speed-for");
        changed |= move(configuration, V9_HARVEST_DELAY + ".enable", HARVEST_DELAY + ".enable");
        changed |= move(configuration, V9_HARVEST_DELAY + ".ticks", HARVEST_DELAY + ".ticks");
        changed |= move(configuration, V9_HARVEST_PAUSE + ".enable", HARVEST_PAUSE + ".enable");
        changed |= move(configuration, V9_HARVEST_PAUSE + ".harvests-before-pause",
                HARVEST_PAUSE + ".after-harvests");
        changed |= move(configuration, V9_HARVEST_PAUSE + ".pause-ticks", HARVEST_PAUSE + ".ticks");

        changed |= move(configuration, V9_TRACKING + ".mode", CROP_SEARCH + ".mode");
        changed |= move(configuration, V9_CONDITIONS + ".growth-events", SEARCH_TRIGGERS + ".natural-growth");
        changed |= move(configuration, V9_CONDITIONS + ".fertilize-events", SEARCH_TRIGGERS + ".bone-meal");
        changed |= move(configuration, V9_CONDITIONS + ".crop-place-events", SEARCH_TRIGGERS + ".crop-placement");
        changed |= move(configuration, V9_CONDITIONS + ".scan-on-chunk-load", SEARCH_TRIGGERS + ".chunk-load");
        changed |= move(configuration, V9_CONDITIONS + ".scan-on-farmer-purchase",
                SEARCH_TRIGGERS + ".new-farmer");
        changed |= move(configuration, V9_CONDITIONS + ".scan-on-player-join", SEARCH_TRIGGERS + ".player-join");
        changed |= move(configuration, V9_CONDITIONS + ".scan-on-player-chunk-load",
                SEARCH_TRIGGERS + ".player-sees-chunk");
        changed |= move(configuration, V9_CONDITIONS + ".farmer-regions-only",
                SEARCH_TRIGGERS + ".farmer-areas-only");
        changed |= move(configuration, V9_TRACKING + ".purchase-radius-chunks",
                SCAN_RADIUS + ".new-farmer-radius-chunks");
        changed |= move(configuration, V9_TRACKING + ".bootstrap-radius-chunks",
                SCAN_RADIUS + ".player-radius-chunks");
        changed |= move(configuration, V9_TRACKING + ".reconcile-interval-ticks", REPEAT_SEARCH + ".every-ticks");
        changed |= move(configuration, V9_TRACKING + ".max-chunks-per-cycle", REPEAT_SEARCH + ".chunks-per-run");
        changed |= move(configuration, V9_TRACKING + ".max-tracked-chunks", SEARCH_LIMITS + ".remembered-chunks");
        changed |= move(configuration, V9_TRACKING + ".max-concurrent-scans", SEARCH_LIMITS + ".scans-at-once");
        changed |= move(configuration, V9_TRACKING + ".max-snapshot-captures-per-tick",
                SEARCH_LIMITS + ".snapshots-per-tick");
        changed |= move(configuration, V9_TRACKING + ".max-scan-starts-per-second",
                SEARCH_LIMITS + ".new-scans-per-second");
        changed |= move(configuration, V9_TRACKING + ".max-sections-per-second",
                SEARCH_LIMITS + ".sections-per-second");
        changed |= move(configuration, V9_TRACKING + ".max-block-checks-per-slice",
                SEARCH_LIMITS + ".blocks-per-async-task");
        changed |= move(configuration, V9_TRACKING + ".max-pending-scans", SEARCH_LIMITS + ".waiting-scans");
        changed |= move(configuration, V9_TRACKING + ".max-candidates-per-scan",
                SEARCH_LIMITS + ".crops-found-per-scan");
        changed |= move(configuration, V9_TRACKING + ".max-candidate-admissions-per-tick",
                SEARCH_LIMITS + ".crops-queued-per-tick");

        changed |= move(configuration, V9_BACKPRESSURE + ".enable", LOAD_PROTECTION + ".enable");
        changed |= move(configuration, V9_BACKPRESSURE + ".slowdown-above-mspt",
                LOAD_PROTECTION + ".slow-down-at-mspt");
        changed |= move(configuration, V9_BACKPRESSURE + ".pause-above-mspt",
                LOAD_PROTECTION + ".stop-at-mspt");
        changed |= move(configuration, V9_BACKPRESSURE + ".resume-below-mspt",
                LOAD_PROTECTION + ".resume-below-mspt");
        changed |= move(configuration, V9_BACKPRESSURE + ".minimum-work-percent",
                LOAD_PROTECTION + ".minimum-speed-percent");
        changed |= move(configuration, V9_BACKPRESSURE + ".check-interval-ticks",
                LOAD_PROTECTION + ".check-every-ticks");
        changed |= move(configuration, V9_BACKPRESSURE + ".pause-above-region-task-delay-millis",
                LOAD_PROTECTION + ".region-delay-limit-millis");
        changed |= move(configuration, V9_BACKPRESSURE + ".region-cooldown-ticks",
                LOAD_PROTECTION + ".region-recovery-ticks");

        changed |= move(configuration, V9_QUEUE + ".initial-delay-ticks",
                HARVEST_QUEUE + ".first-run-delay-ticks");
        changed |= move(configuration, V9_QUEUE + ".continuation-delay-ticks",
                HARVEST_QUEUE + ".next-run-delay-ticks");
        changed |= move(configuration, V9_QUEUE + ".max-jobs-per-run", HARVEST_QUEUE + ".harvests-per-run");
        changed |= move(configuration, V9_QUEUE + ".max-scheduler-submissions-per-tick",
                HARVEST_QUEUE + ".region-runs-per-tick");
        changed |= move(configuration, V9_QUEUE + ".max-pending-jobs", HARVEST_QUEUE + ".waiting-harvests");
        changed |= move(configuration, V9_QUEUE + ".coalesce-duplicates",
                HARVEST_QUEUE + ".merge-duplicate-blocks");
        changed |= move(configuration, V9_TELEMETRY + ".enable", LOGGING + ".enable");
        changed |= move(configuration, V9_TELEMETRY + ".log-interval-seconds", LOGGING + ".interval-seconds");

        changed |= removeIfEmpty(configuration, V9_HARVEST_DELAY);
        changed |= removeIfEmpty(configuration, V9_HARVEST_PAUSE);
        changed |= removeIfEmpty(configuration, V9_HARVEST);
        changed |= removeIfEmpty(configuration, V9_CONDITIONS);
        changed |= removeIfEmpty(configuration, V9_TRACKING);
        changed |= removeIfEmpty(configuration, V9_BACKPRESSURE);
        changed |= removeIfEmpty(configuration, V9_QUEUE);
        changed |= removeIfEmpty(configuration, V9_TELEMETRY);
        return changed;
    }

    private static boolean normalizeFriendlyOptionNames(YamlConfiguration configuration) {
        boolean changed = normalizeOption(configuration, HARVEST + ".separate-speed-for",
                "OWNER", "PLAYER", "REGION", "LAND");
        changed |= normalizeOption(configuration, CROP_SEARCH + ".mode",
                "EVENT_DRIVEN", "EVENTS", "PERIODIC_LOADED_CHUNKS", "TIMER", "HYBRID", "BOTH");
        return changed;
    }

    private static boolean normalizeOption(YamlConfiguration configuration, String path, String... replacements) {
        Object raw = configuration.get(path);
        if (!(raw instanceof String value)) {
            return false;
        }
        for (int index = 0; index < replacements.length; index += 2) {
            if (replacements[index].equalsIgnoreCase(value.trim())) {
                configuration.set(path, replacements[index + 1]);
                return true;
            }
        }
        return false;
    }

    private static boolean move(YamlConfiguration configuration, String source, String target) {
        if (!configuration.contains(source)) {
            return false;
        }
        copyIfMissing(configuration, source, target);
        configuration.set(source, null);
        return true;
    }

    private static boolean removeIfEmpty(YamlConfiguration configuration, String path) {
        var section = configuration.getConfigurationSection(path);
        if (section == null || !section.getKeys(false).isEmpty()) {
            return false;
        }
        configuration.set(path, null);
        return true;
    }

    private static boolean addMissingComments(
            YamlConfiguration configuration,
            YamlConfiguration defaults,
            String root
    ) {
        var defaultsSection = defaults.getConfigurationSection(root);
        if (defaultsSection == null) {
            return false;
        }
        boolean changed = copyCommentsIfMissing(configuration, defaults, root);
        for (String relative : defaultsSection.getKeys(true)) {
            changed |= copyCommentsIfMissing(configuration, defaults, root + '.' + relative);
        }
        return changed;
    }

    private static boolean copyCommentsIfMissing(
            YamlConfiguration configuration,
            YamlConfiguration defaults,
            String path
    ) {
        List<String> comments = defaults.getComments(path);
        if (comments.isEmpty() || !configuration.getComments(path).isEmpty()) {
            return false;
        }
        configuration.setComments(path, comments);
        return true;
    }

    private static void copyIfMissing(YamlConfiguration configuration, String source, String target) {
        if (!configuration.contains(target) && configuration.contains(source)) {
            configuration.set(target, configuration.get(source));
        }
    }

    private static boolean repairBoolean(YamlConfiguration configuration, YamlConfiguration defaults, String path) {
        if (configuration.get(path) instanceof Boolean) {
            return false;
        }
        configuration.set(path, defaults.getBoolean(path));
        return true;
    }

    private static boolean repairInteger(
            YamlConfiguration configuration,
            YamlConfiguration defaults,
            String path,
            int minimum,
            int maximum
    ) {
        Object raw = configuration.get(path);
        if (raw instanceof Number number) {
            long value = number.longValue();
            if (number.doubleValue() == value && value >= minimum && value <= maximum) {
                return false;
            }
        }
        configuration.set(path, defaults.getInt(path));
        return true;
    }

    private static boolean repairDouble(
            YamlConfiguration configuration,
            YamlConfiguration defaults,
            String path,
            double minimum,
            double maximum
    ) {
        Object raw = configuration.get(path);
        if (raw instanceof Number number) {
            double value = number.doubleValue();
            if (Double.isFinite(value) && value >= minimum && value <= maximum) {
                return false;
            }
        }
        configuration.set(path, defaults.getDouble(path));
        return true;
    }

    private static boolean repairString(
            YamlConfiguration configuration,
            YamlConfiguration defaults,
            String path,
            Predicate<String> validator
    ) {
        Object raw = configuration.get(path);
        if (raw instanceof String value && validator.test(value)) {
            return false;
        }
        configuration.set(path, defaults.getString(path));
        return true;
    }

    private static boolean repairCropList(YamlConfiguration configuration, YamlConfiguration defaults) {
        Object raw = configuration.get("items");
        List<String> fallback = normalizeCrops(defaults.getList("items"));
        if (!(raw instanceof List<?> rawItems)) {
            configuration.set("items", fallback);
            return true;
        }

        List<String> normalized = normalizeCrops(rawItems);
        if (normalized.isEmpty()) {
            normalized = fallback;
        }

        if (!normalized.equals(rawItems)) {
            configuration.set("items", normalized);
            return true;
        }
        return false;
    }

    private static List<String> normalizeCrops(List<?> rawItems) {
        Set<String> normalized = new LinkedHashSet<>();
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof String crop) || crop.isBlank()) {
                continue;
            }
            XMaterial.matchXMaterial(crop.trim())
                    .map(CropHarvesting::normalize)
                    .filter(CropHarvesting::isSupportedCrop)
                    .map(Enum::name)
                    .ifPresent(normalized::add);
        }
        return new ArrayList<>(normalized);
    }

    private static boolean repairStackedCropList(
            YamlConfiguration configuration,
            YamlConfiguration defaults
    ) {
        String path = STACKED_CROPS + ".items";
        Object raw = configuration.get(path);
        List<String> fallback = normalizeStackedCrops(defaults.getList(path));
        if (!(raw instanceof List<?> rawItems)) {
            configuration.set(path, fallback);
            return true;
        }

        List<String> normalized = normalizeStackedCrops(rawItems);
        if (normalized.isEmpty()) {
            normalized = fallback;
        }
        if (!normalized.equals(rawItems)) {
            configuration.set(path, normalized);
            return true;
        }
        return false;
    }

    private static List<String> normalizeStackedCrops(List<?> rawItems) {
        Set<String> normalized = new LinkedHashSet<>();
        for (Object rawItem : rawItems) {
            if (!(rawItem instanceof String crop) || crop.isBlank()) {
                continue;
            }
            XMaterial.matchXMaterial(crop.trim())
                    .map(CropHarvesting::normalize)
                    .filter(CropHarvesting::isStackCrop)
                    .map(Enum::name)
                    .ifPresent(normalized::add);
        }
        return new ArrayList<>(normalized);
    }

    private static boolean repairLore(YamlConfiguration configuration, YamlConfiguration defaults, String path) {
        Object raw = configuration.get(path);
        if (raw instanceof List<?> lore && lore.stream().allMatch(String.class::isInstance)) {
            return false;
        }
        configuration.set(path, defaults.getStringList(path));
        return true;
    }

    private static boolean ensureSection(YamlConfiguration configuration, String path) {
        if (configuration.isConfigurationSection(path)) {
            return false;
        }
        configuration.set(path, null);
        configuration.createSection(path);
        return true;
    }

    private static OptimizationSettings readOptimizationSettings(YamlConfiguration configuration) {
        return new OptimizationSettings(
                configuration.getBoolean(OPTIMIZE_MODULE + ".enable"),
                configuration.getInt(HARVEST_QUEUE + ".first-run-delay-ticks"),
                configuration.getInt(HARVEST_QUEUE + ".next-run-delay-ticks"),
                configuration.getInt(HARVEST_QUEUE + ".harvests-per-run"),
                configuration.getInt(HARVEST + ".max-harvests-per-tick"),
                configuration.getInt(HARVEST_QUEUE + ".region-runs-per-tick"),
                configuration.getInt(HARVEST_QUEUE + ".waiting-harvests"),
                configuration.getBoolean(HARVEST_QUEUE + ".merge-duplicate-blocks"),
                HarvestPacingScope.parse(configuration.getString(HARVEST + ".separate-speed-for")),
                configuration.getBoolean(HARVEST_DELAY + ".enable"),
                configuration.getInt(HARVEST_DELAY + ".ticks"),
                configuration.getBoolean(HARVEST_PAUSE + ".enable"),
                configuration.getInt(HARVEST_PAUSE + ".after-harvests"),
                configuration.getInt(HARVEST_PAUSE + ".ticks")
        );
    }

    private static TrackingSettings readTrackingSettings(YamlConfiguration configuration) {
        return new TrackingSettings(
                TrackingMode.parse(configuration.getString(CROP_SEARCH + ".mode")),
                configuration.getBoolean(SEARCH_TRIGGERS + ".natural-growth"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".bone-meal"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".crop-placement"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".chunk-load"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".new-farmer"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".player-join"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".player-sees-chunk"),
                configuration.getBoolean(SEARCH_TRIGGERS + ".farmer-areas-only"),
                configuration.getInt(REPEAT_SEARCH + ".every-ticks"),
                configuration.getInt(REPEAT_SEARCH + ".chunks-per-run"),
                configuration.getInt(SEARCH_LIMITS + ".remembered-chunks"),
                configuration.getInt(SEARCH_LIMITS + ".scans-at-once"),
                configuration.getInt(SEARCH_LIMITS + ".snapshots-per-tick"),
                configuration.getInt(SEARCH_LIMITS + ".new-scans-per-second"),
                configuration.getInt(SEARCH_LIMITS + ".sections-per-second"),
                configuration.getInt(SEARCH_LIMITS + ".blocks-per-async-task"),
                configuration.getInt(SEARCH_LIMITS + ".waiting-scans"),
                configuration.getInt(SEARCH_LIMITS + ".crops-found-per-scan"),
                configuration.getInt(SEARCH_LIMITS + ".crops-queued-per-tick"),
                configuration.getInt(SCAN_RADIUS + ".new-farmer-radius-chunks"),
                configuration.getInt(SCAN_RADIUS + ".player-radius-chunks")
        );
    }

    private static BackpressureSettings readBackpressureSettings(YamlConfiguration configuration) {
        return new BackpressureSettings(
                configuration.getBoolean(LOAD_PROTECTION + ".enable"),
                configuration.getDouble(LOAD_PROTECTION + ".slow-down-at-mspt"),
                configuration.getDouble(LOAD_PROTECTION + ".stop-at-mspt"),
                configuration.getDouble(LOAD_PROTECTION + ".resume-below-mspt"),
                configuration.getInt(LOAD_PROTECTION + ".minimum-speed-percent"),
                configuration.getInt(LOAD_PROTECTION + ".check-every-ticks"),
                configuration.getInt(LOAD_PROTECTION + ".region-delay-limit-millis"),
                configuration.getInt(LOAD_PROTECTION + ".region-recovery-ticks")
        );
    }

    private static TelemetrySettings readTelemetrySettings(YamlConfiguration configuration) {
        return new TelemetrySettings(
                configuration.getBoolean(LOGGING + ".enable"),
                configuration.getInt(LOGGING + ".interval-seconds")
        );
    }

    private static UpdateSettings readUpdateSettings(YamlConfiguration configuration) {
        return new UpdateSettings(
                configuration.getBoolean(UPDATE_CHECKER + ".enable"),
                configuration.getInt(UPDATE_CHECKER + ".check-interval-hours"),
                configuration.getInt(UPDATE_CHECKER + ".connect-timeout-seconds"),
                configuration.getInt(UPDATE_CHECKER + ".request-timeout-seconds")
        );
    }

    private static boolean hasPlaceholders(String value, String... placeholders) {
        if (value == null || value.isBlank() || value.length() > 1_024) {
            return false;
        }
        for (String placeholder : placeholders) {
            if (!value.contains(placeholder)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFriendlyTrackingMode(String value) {
        return matchesOption(value, "EVENTS", "TIMER", "BOTH");
    }

    private static boolean isFriendlyHarvestScope(String value) {
        return matchesOption(value, "PLAYER", "FARMER", "LAND", "CHUNK");
    }

    private static boolean matchesOption(String value, String... allowed) {
        if (value != null) {
            for (String option : allowed) {
                if (option.equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isValidPermission(String value) {
        return value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    }

    private static boolean isValidBase64(String value) {
        if (value.isBlank()) {
            return false;
        }
        try {
            Base64.getDecoder().decode(value);
            return true;
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static byte[] readBundled(InputStream input) throws IOException {
        Objects.requireNonNull(input, "Bundled defaults must be present.");
        try (InputStream stream = input) {
            return stream.readAllBytes();
        }
    }

    private static YamlConfiguration loadBundled(InputStream input) {
        Objects.requireNonNull(input, "Bundled defaults must be present.");
        try (InputStream stream = input; Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration configuration = commentAwareConfiguration();
            configuration.load(reader);
            return configuration;
        }
        catch (IOException | InvalidConfigurationException exception) {
            throw new IllegalStateException("Unable to read bundled AutoHarvest defaults.", exception);
        }
    }

    private static LoadedYaml loadExisting(File target, Logger logger) throws IOException {
        if (!target.isFile()) {
            return new LoadedYaml(commentAwareConfiguration(), false, false);
        }

        YamlConfiguration configuration = commentAwareConfiguration();
        try {
            configuration.load(target);
            return new LoadedYaml(configuration, true, false);
        }
        catch (InvalidConfigurationException exception) {
            logger.warning("AutoHarvest found malformed YAML in " + target.getName()
                    + "; the original file will be backed up and repaired: "
                    + compactDiagnostic(exception.getMessage()));
            return new LoadedYaml(commentAwareConfiguration(), true, true);
        }
    }

    private static YamlConfiguration commentAwareConfiguration() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.options().parseComments(true);
        return configuration;
    }

    private static String compactDiagnostic(String message) {
        if (message == null || message.isBlank()) {
            return "no parser details available";
        }
        String compact = message.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 240 ? compact : compact.substring(0, 237) + "...";
    }

    private static void backupBeforeWrite(File target, boolean existed) throws IOException {
        if (!existed) {
            return;
        }

        Path source = target.toPath();
        Path backupDirectory = source.getParent().resolve("backups");
        Files.createDirectories(backupDirectory);

        String timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP);
        Path backup = backupDirectory.resolve(target.getName() + "." + timestamp + ".bak");
        int attempt = 1;
        while (Files.exists(backup)) {
            backup = backupDirectory.resolve(target.getName() + "." + timestamp + "." + attempt++ + ".bak");
        }
        Files.copy(source, backup);
    }

    private static void save(File target, YamlConfiguration configuration) throws IOException {
        writeAtomically(target.toPath(), configuration.saveToString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean writeBundledIfMissing(File target, byte[] contents) throws IOException {
        if (target.isFile()) {
            return false;
        }
        writeAtomically(target.toPath(), contents);
        return true;
    }

    private static void writeAtomically(Path target, byte[] contents) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("AutoHarvest configuration target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record ConfigSnapshot(
            @NotNull ConfigFile config,
            @NotNull StackedCropSettings stackedCrops,
            @NotNull OptimizationSettings optimization,
            @NotNull TrackingSettings tracking,
            @NotNull BackpressureSettings backpressure,
            @NotNull TelemetrySettings telemetry,
            @NotNull UpdateSettings update,
            boolean repaired
    ) {
    }

    private record LoadedYaml(YamlConfiguration configuration, boolean existed, boolean malformed) {
    }
}
