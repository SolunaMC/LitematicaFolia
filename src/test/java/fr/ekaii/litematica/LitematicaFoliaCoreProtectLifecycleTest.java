package fr.ekaii.litematica;

import org.bukkit.event.EventHandler;
import org.bukkit.event.server.PluginEnableEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class LitematicaFoliaCoreProtectLifecycleTest {

    @Test
    void listensForCoreProtectEnablingAfterLitematicaFolia() throws Exception {
        Method handler = LitematicaFolia.class.getMethod(
                "onPluginEnable", PluginEnableEvent.class);

        assertNotNull(handler.getAnnotation(EventHandler.class));
    }
}
