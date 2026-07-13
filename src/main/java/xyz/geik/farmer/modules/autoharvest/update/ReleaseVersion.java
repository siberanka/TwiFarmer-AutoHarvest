package xyz.geik.farmer.modules.autoharvest.update;

import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Small SemVer-compatible comparator for GitHub release tags. */
public record ReleaseVersion(@NotNull List<BigInteger> core, @NotNull List<String> prerelease)
        implements Comparable<ReleaseVersion> {

    private static final int MAX_VERSION_LENGTH = 64;
    private static final int MAX_COMPONENTS = 8;

    public ReleaseVersion {
        core = List.copyOf(core);
        prerelease = List.copyOf(prerelease);
    }

    public static @NotNull Optional<ReleaseVersion> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.length() > MAX_VERSION_LENGTH) {
            return Optional.empty();
        }
        if (value.startsWith("v") || value.startsWith("V")) {
            value = value.substring(1);
        }
        int metadata = value.indexOf('+');
        if (metadata >= 0) {
            value = value.substring(0, metadata);
        }
        String[] releaseParts = value.split("-", 2);
        String[] numericParts = releaseParts[0].split("\\.", -1);
        if (numericParts.length == 0 || numericParts.length > MAX_COMPONENTS) {
            return Optional.empty();
        }
        List<BigInteger> core = new ArrayList<>(numericParts.length);
        for (String part : numericParts) {
            if (!part.matches("0|[1-9][0-9]*")) {
                return Optional.empty();
            }
            core.add(new BigInteger(part));
        }

        List<String> prerelease = List.of();
        if (releaseParts.length == 2) {
            String[] identifiers = releaseParts[1].split("\\.", -1);
            if (identifiers.length == 0 || identifiers.length > MAX_COMPONENTS) {
                return Optional.empty();
            }
            List<String> parsed = new ArrayList<>(identifiers.length);
            for (String identifier : identifiers) {
                if (!identifier.matches("[0-9A-Za-z-]+")) {
                    return Optional.empty();
                }
                if (identifier.matches("[0-9]+") && identifier.length() > 1 && identifier.startsWith("0")) {
                    return Optional.empty();
                }
                parsed.add(identifier.toLowerCase(Locale.ROOT));
            }
            prerelease = parsed;
        }
        return Optional.of(new ReleaseVersion(core, prerelease));
    }

    public static boolean isNewer(String current, String candidate) {
        Optional<ReleaseVersion> currentVersion = parse(current);
        Optional<ReleaseVersion> candidateVersion = parse(candidate);
        return currentVersion.isPresent() && candidateVersion.isPresent()
                && candidateVersion.get().compareTo(currentVersion.get()) > 0;
    }

    @Override
    public int compareTo(@NotNull ReleaseVersion other) {
        int components = Math.max(core.size(), other.core.size());
        for (int index = 0; index < components; index++) {
            BigInteger left = index < core.size() ? core.get(index) : BigInteger.ZERO;
            BigInteger right = index < other.core.size() ? other.core.get(index) : BigInteger.ZERO;
            int comparison = left.compareTo(right);
            if (comparison != 0) {
                return comparison;
            }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return prerelease.isEmpty() == other.prerelease.isEmpty() ? 0 : (prerelease.isEmpty() ? 1 : -1);
        }
        int identifiers = Math.max(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < identifiers; index++) {
            if (index >= prerelease.size()) {
                return -1;
            }
            if (index >= other.prerelease.size()) {
                return 1;
            }
            int comparison = compareIdentifier(prerelease.get(index), other.prerelease.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.matches("[0-9]+");
        boolean rightNumeric = right.matches("[0-9]+");
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
    }
}
