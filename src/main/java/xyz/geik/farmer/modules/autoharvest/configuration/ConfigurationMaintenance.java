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
    private static final String QUEUE = OPTIMIZE_MODULE + ".queue";
    private static final String TRACKING = OPTIMIZE_MODULE + ".tracking";

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
                readOptimizationSettings(loaded.configuration),
                readTrackingSettings(loaded.configuration),
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
        changed |= repairInteger(configuration, defaults, "config-version", 3, 3);
        changed |= repairBoolean(configuration, defaults, "status");
        changed |= repairBoolean(configuration, defaults, "requirePiston");
        changed |= repairBoolean(configuration, defaults, "checkAllDirections");
        changed |= repairBoolean(configuration, defaults, "withoutFarmer");
        changed |= repairBoolean(configuration, defaults, "checkStock");
        changed |= repairBoolean(configuration, defaults, "defaultStatus");
        changed |= repairString(configuration, defaults, "customPerm", ConfigurationMaintenance::isValidPermission);
        changed |= repairCropList(configuration, defaults);

        changed |= ensureSection(configuration, OPTIMIZE_MODULE);
        changed |= ensureSection(configuration, QUEUE);
        changed |= ensureSection(configuration, TRACKING);
        changed |= repairBoolean(configuration, defaults, OPTIMIZE_MODULE + ".enable");
        changed |= repairInteger(configuration, defaults, QUEUE + ".initial-delay-ticks", 1, 20);
        changed |= repairInteger(configuration, defaults, QUEUE + ".continuation-delay-ticks", 1, 20);
        changed |= repairInteger(configuration, defaults, QUEUE + ".max-jobs-per-run", 1, 512);
        changed |= repairInteger(configuration, defaults, QUEUE + ".max-pending-jobs", 64, 65_536);
        changed |= repairBoolean(configuration, defaults, QUEUE + ".coalesce-duplicates");
        changed |= repairInteger(configuration, defaults, TRACKING + ".reconcile-interval-ticks", 20, 72_000);
        changed |= repairInteger(configuration, defaults, TRACKING + ".max-chunks-per-cycle", 1, 128);
        changed |= repairInteger(configuration, defaults, TRACKING + ".max-tracked-chunks", 64, 65_536);
        changed |= repairInteger(configuration, defaults, TRACKING + ".max-concurrent-scans", 1, 32);
        changed |= repairInteger(configuration, defaults, TRACKING + ".max-pending-scans", 64, 65_536);
        changed |= repairInteger(configuration, defaults, TRACKING + ".max-candidates-per-scan", 16, 4_096);
        changed |= repairInteger(configuration, defaults, TRACKING + ".purchase-radius-chunks", 1, 32);
        changed |= repairInteger(configuration, defaults, TRACKING + ".bootstrap-radius-chunks", 1, 16);

        if (!configuration.getBoolean("requirePiston") && configuration.getBoolean("checkAllDirections")) {
            configuration.set("checkAllDirections", false);
            changed = true;
        }
        return changed;
    }

    private static boolean repairLanguage(YamlConfiguration configuration, YamlConfiguration defaults) {
        boolean changed = false;
        changed |= ensureSection(configuration, "moduleGui");
        changed |= ensureSection(configuration, "moduleGui.icon");
        changed |= repairString(configuration, defaults, "enabled", value -> !value.isBlank());
        changed |= repairString(configuration, defaults, "disabled", value -> !value.isBlank());
        changed |= repairString(configuration, defaults, "moduleGui.icon.guiInterface",
                value -> value.length() == 1 && !Character.isWhitespace(value.charAt(0)));
        changed |= repairString(configuration, defaults, "moduleGui.icon.skull", ConfigurationMaintenance::isValidBase64);
        changed |= repairString(configuration, defaults, "moduleGui.icon.name", value -> !value.isBlank());
        changed |= repairLore(configuration, defaults, "moduleGui.icon.lore");
        return changed;
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
                configuration.getInt(QUEUE + ".initial-delay-ticks"),
                configuration.getInt(QUEUE + ".continuation-delay-ticks"),
                configuration.getInt(QUEUE + ".max-jobs-per-run"),
                configuration.getInt(QUEUE + ".max-pending-jobs"),
                configuration.getBoolean(QUEUE + ".coalesce-duplicates")
        );
    }

    private static TrackingSettings readTrackingSettings(YamlConfiguration configuration) {
        return new TrackingSettings(
                configuration.getInt(TRACKING + ".reconcile-interval-ticks"),
                configuration.getInt(TRACKING + ".max-chunks-per-cycle"),
                configuration.getInt(TRACKING + ".max-tracked-chunks"),
                configuration.getInt(TRACKING + ".max-concurrent-scans"),
                configuration.getInt(TRACKING + ".max-pending-scans"),
                configuration.getInt(TRACKING + ".max-candidates-per-scan"),
                configuration.getInt(TRACKING + ".purchase-radius-chunks"),
                configuration.getInt(TRACKING + ".bootstrap-radius-chunks")
        );
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
            return YamlConfiguration.loadConfiguration(reader);
        }
        catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled AutoHarvest defaults.", exception);
        }
    }

    private static LoadedYaml loadExisting(File target, Logger logger) throws IOException {
        if (!target.isFile()) {
            return new LoadedYaml(new YamlConfiguration(), false, false);
        }

        YamlConfiguration configuration = new YamlConfiguration();
        try {
            configuration.load(target);
            return new LoadedYaml(configuration, true, false);
        }
        catch (InvalidConfigurationException exception) {
            logger.warning("AutoHarvest found malformed YAML in " + target.getName()
                    + "; the original file will be backed up and repaired: "
                    + compactDiagnostic(exception.getMessage()));
            return new LoadedYaml(new YamlConfiguration(), true, true);
        }
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
            @NotNull OptimizationSettings optimization,
            @NotNull TrackingSettings tracking,
            boolean repaired
    ) {
    }

    private record LoadedYaml(YamlConfiguration configuration, boolean existed, boolean malformed) {
    }
}
