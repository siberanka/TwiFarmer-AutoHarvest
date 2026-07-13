package xyz.geik.farmer.modules.autoharvest.compat;

import org.junit.jupiter.api.Test;
import xyz.geik.farmer.api.managers.FarmerManager;
import xyz.geik.farmer.model.Farmer;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FarmerAccessTest {

    @Test
    void adaptsTheFarmerCacheReturnDescriptorToMap() throws Exception {
        Method getter = FarmerManager.class.getMethod("getFarmers");
        assertTrue(Map.class.isAssignableFrom(getter.getReturnType()));
        assertSame(getter.invoke(null), FarmerAccess.farmerCache());
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesFarmersWithoutAStaticDescriptorCall() {
        Map<String, Farmer> cache = (Map<String, Farmer>) FarmerAccess.farmerCache();
        String regionId = "autoharvest-test-" + UUID.randomUUID();
        Farmer farmer = mock(Farmer.class);
        cache.put(regionId, farmer);
        try {
            assertSame(farmer, FarmerAccess.findByRegionId(regionId));
            assertNull(FarmerAccess.findByRegionId(null));
        }
        finally {
            cache.remove(regionId);
        }
    }
}
