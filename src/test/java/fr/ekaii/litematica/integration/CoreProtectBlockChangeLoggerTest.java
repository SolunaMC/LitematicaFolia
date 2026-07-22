package fr.ekaii.litematica.integration;

import net.coreprotect.CoreProtectAPI;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class CoreProtectBlockChangeLoggerTest {

    private RecordingApi api;
    private CoreProtectBlockChangeLogger logger;

    @BeforeEach
    void setUp() {
        api = new RecordingApi();
        Logger testLogger = Logger.getLogger(getClass().getName());
        testLogger.setUseParentHandlers(false);
        logger = new CoreProtectBlockChangeLogger(api, testLogger);
    }

    @Test
    void airToBlockLogsOnePlacement() {
        logger.logBlockChange("Builder", state(Material.AIR, "minecraft:air"),
                state(Material.STONE, "minecraft:stone"));

        assertEquals(List.of("place:Builder:STONE:minecraft:stone"), api.calls);
    }

    @Test
    void blockReplacementLogsRemovalBeforePlacement() {
        logger.logBlockChange("Builder", state(Material.STONE, "minecraft:stone"),
                state(Material.OAK_LOG, "minecraft:oak_log[axis=x]"));

        assertEquals(List.of(
                "remove:Builder:STONE:minecraft:stone",
                "place:Builder:OAK_LOG:minecraft:oak_log[axis=x]"
        ), api.calls);
    }

    @Test
    void unchangedBlockStateIsNotLogged() {
        logger.logBlockChange("Builder", state(Material.OAK_LOG, "minecraft:oak_log[axis=z]"),
                state(Material.OAK_LOG, "minecraft:oak_log[axis=z]"));

        assertEquals(List.of(), api.calls);
    }

    @Test
    void blockToAirLogsOneRemoval() {
        logger.logBlockChange("Builder", state(Material.GLASS, "minecraft:glass"),
                state(Material.AIR, "minecraft:air"));

        assertEquals(List.of("remove:Builder:GLASS:minecraft:glass"), api.calls);
    }

    @Test
    void removalFailureDoesNotBlockPlacementOrEscapeToPasteCode() {
        api.throwOnRemoval = true;

        assertDoesNotThrow(() -> logger.logBlockChange("Builder",
                state(Material.STONE, "minecraft:stone"),
                state(Material.DIRT, "minecraft:dirt")));
        assertEquals(List.of("place:Builder:DIRT:minecraft:dirt"), api.calls);
    }

    @Test
    void disabledLoggerDoesNotRequestSnapshots() {
        assertFalse(BlockChangeLogger.noOp().isEnabled());
    }

    private static BlockState state(Material material, String blockDataString) {
        BlockData blockData = (BlockData) Proxy.newProxyInstance(
                BlockData.class.getClassLoader(),
                new Class<?>[] { BlockData.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAsString" -> blockDataString;
                    case "getMaterial" -> material;
                    case "toString" -> blockDataString;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });

        return (BlockState) Proxy.newProxyInstance(
                BlockState.class.getClassLoader(),
                new Class<?>[] { BlockState.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getType" -> material;
                    case "getBlockData" -> blockData;
                    case "toString" -> material + "[" + blockDataString + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0f;
        if (type == double.class) return 0.0d;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("unsupported primitive " + type);
    }

    private static final class RecordingApi extends CoreProtectAPI {
        final List<String> calls = new ArrayList<>();
        boolean throwOnRemoval;

        @Override
        public boolean logPlacement(String user, BlockState blockState) {
            calls.add("place:" + user + ":" + blockState.getType() + ":"
                    + blockState.getBlockData().getAsString());
            return true;
        }

        @Override
        public boolean logRemoval(String user, BlockState blockState) {
            if (throwOnRemoval) {
                throw new IllegalStateException("simulated CoreProtect failure");
            }
            calls.add("remove:" + user + ":" + blockState.getType() + ":"
                    + blockState.getBlockData().getAsString());
            return true;
        }
    }
}
