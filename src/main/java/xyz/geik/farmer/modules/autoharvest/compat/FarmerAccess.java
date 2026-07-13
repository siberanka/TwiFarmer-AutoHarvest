package xyz.geik.farmer.modules.autoharvest.compat;

import org.jetbrains.annotations.Nullable;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.model.Farmer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Bridges Farmer versions whose getFarmers method has a covariant binary
 * descriptor (HashMap in b113, Map in b117 and newer).
 */
public final class FarmerAccess {

    private static final MethodHandle FARMERS_GETTER = resolveFarmersGetter();

    private FarmerAccess() {
    }

    public static @Nullable Farmer findByRegionId(@Nullable String regionId) {
        if (regionId == null) {
            return null;
        }
        Object farmer = farmerCache().get(regionId);
        return farmer instanceof Farmer resolved ? resolved : null;
    }

    static Map<?, ?> farmerCache() {
        Object cache;
        try {
            cache = (Object) FARMERS_GETTER.invokeExact();
        }
        catch (Throwable exception) {
            throw new IllegalStateException("Unable to read Farmer's loaded region cache.", exception);
        }
        if (cache instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("FarmerManager.getFarmers() did not return a Map.");
    }

    private static MethodHandle resolveFarmersGetter() {
        try {
            Method getter = FarmerManager.class.getMethod("getFarmers");
            if (!Map.class.isAssignableFrom(getter.getReturnType())) {
                throw new IllegalStateException("FarmerManager.getFarmers() has an unsupported return type: "
                        + getter.getReturnType().getName());
            }
            return MethodHandles.publicLookup().unreflect(getter)
                    .asType(MethodType.methodType(Object.class));
        }
        catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No compatible FarmerManager.getFarmers() method is available.", exception);
        }
    }
}
