package xyz.geik.farmer.api;

/**
 * Test fixture matching the Farmer b123 runtime contract. The production
 * module resolves this class from Farmer's class loader.
 */
public final class FarmerCompatibilityAPI {

    private FarmerCompatibilityAPI() {}

    public static void requireModuleApi(int minimumVersion) {
        if (minimumVersion > 2)
            throw new IllegalStateException("unsupported test module API");
    }
}
