package xyz.geik.farmer.modules.autoharvest.update;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpdateCheckerTest {

    @Test
    void permitsOnlyOperatorsAndFarmerAdministrators() {
        Player operator = mock(Player.class);
        Player administrator = mock(Player.class);
        Player regularPlayer = mock(Player.class);
        when(operator.isOp()).thenReturn(true);
        when(administrator.hasPermission(UpdateChecker.ADMIN_PERMISSION)).thenReturn(true);

        assertTrue(UpdateChecker.canNotify(operator));
        assertTrue(UpdateChecker.canNotify(administrator));
        assertFalse(UpdateChecker.canNotify(regularPlayer));
    }

    @Test
    void notificationIncludesModuleVersionsAndDownloadLink() {
        String message = UpdateChecker.formatMessage(
                "[{module}] {current} -> {latest}: {url}",
                "AutoHarvest", "1.2.2", "v1.2.3", "https://github.com/download");

        assertTrue(message.contains("[AutoHarvest]"));
        assertTrue(message.contains("1.2.2 -> v1.2.3"));
        assertTrue(message.contains("https://github.com/download"));
    }
}
