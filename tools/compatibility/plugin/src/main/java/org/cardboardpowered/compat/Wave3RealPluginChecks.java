package org.cardboardpowered.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Point 4 Wave 3: functional checks against real installed plugins.
 *
 * <p>No compile-time dependency on the tested plugins is used. Their public
 * APIs are reached through their own plugin class loaders so this probe remains
 * buildable against Paper API alone and can later be reused on reference Paper.
 *
 * <p>CoreProtect receives one synthetic API audit entry under a unique actor.
 * No world block is modified. PlaceholderAPI and UltraPermissions checks are
 * otherwise read-only.
 */
final class Wave3RealPluginChecks {

    private static final int COREPROTECT_LOOKUP_TIMEOUT_TICKS = 200;
    private static final int COREPROTECT_LOOKUP_INTERVAL_TICKS = 10;

    private Wave3RealPluginChecks() {
    }

    static void start(
        JavaPlugin probe,
        BiConsumer<String, String> pass,
        BiConsumer<String, String> fail,
        BiConsumer<String, String> skip,
        Runnable completion
    ) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(
                probe,
                () -> start(probe, pass, fail, skip, completion)
            );
            return;
        }

        new Runner(
            probe,
            pass,
            fail,
            skip,
            completion
        ).start();
    }

    private static final class Runner {

        private final JavaPlugin probe;
        private final BiConsumer<String, String> pass;
        private final BiConsumer<String, String> fail;
        private final BiConsumer<String, String> skip;
        private final Runnable completion;

        private Object coreProtectApi;
        private Method coreProtectHasPlaced;
        private String coreProtectActor;
        private Block coreProtectBlock;
        private BlockData coreProtectOriginalData;
        private boolean coreProtectLookupPending;

        private Runner(
            JavaPlugin probe,
            BiConsumer<String, String> pass,
            BiConsumer<String, String> fail,
            BiConsumer<String, String> skip,
            Runnable completion
        ) {
            this.probe = probe;
            this.pass = pass;
            this.fail = fail;
            this.skip = skip;
            this.completion = completion;
        }

        private void start() {
            runPlaceholderApi();
            runUltraPermissions();
            runCoreProtect();

            if (!coreProtectLookupPending) {
                completion.run();
            }
        }

        // -------------------------------------------------------------
        // PlaceholderAPI
        // -------------------------------------------------------------

        private void runPlaceholderApi() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");

            if (plugin == null) {
                skip.accept(
                    "placeholderapi.plugin",
                    "PlaceholderAPI is not installed"
                );
                skip.accept(
                    "placeholderapi.registry",
                    "PlaceholderAPI is not installed"
                );
                skip.accept(
                    "placeholderapi.detect",
                    "PlaceholderAPI is not installed"
                );
                skip.accept(
                    "placeholderapi.uperms-substitution",
                    "PlaceholderAPI is not installed"
                );
                return;
            }

            if (!plugin.isEnabled()) {
                fail.accept(
                    "placeholderapi.plugin",
                    "PlaceholderAPI is installed but disabled"
                );
                skip.accept(
                    "placeholderapi.registry",
                    "PlaceholderAPI is disabled"
                );
                skip.accept(
                    "placeholderapi.detect",
                    "PlaceholderAPI is disabled"
                );
                skip.accept(
                    "placeholderapi.uperms-substitution",
                    "PlaceholderAPI is disabled"
                );
                return;
            }

            pass.accept(
                "placeholderapi.plugin",
                "PlaceholderAPI " + plugin.getPluginMeta().getVersion()
            );

            try {
                Class<?> apiClass = plugin.getClass()
                    .getClassLoader()
                    .loadClass("me.clip.placeholderapi.PlaceholderAPI");

                Method isRegistered = apiClass.getMethod(
                    "isRegistered",
                    String.class
                );

                Method getIdentifiers = apiClass.getMethod(
                    "getRegisteredIdentifiers"
                );

                boolean registered = Boolean.TRUE.equals(
                    invoke(isRegistered, null, "uperms")
                );

                Object identifiersObject = invoke(
                    getIdentifiers,
                    null
                );

                boolean identifiersContain = false;

                if (identifiersObject instanceof Set<?> identifiers) {
                    identifiersContain = identifiers.contains("uperms");
                }

                if (registered && identifiersContain) {
                    pass.accept(
                        "placeholderapi.registry",
                        "uperms expansion is registered"
                    );
                } else {
                    fail.accept(
                        "placeholderapi.registry",
                        "uperms expansion was not visible in PlaceholderAPI registry"
                    );
                }

                Method contains = apiClass.getMethod(
                    "containsPlaceholders",
                    String.class
                );

                boolean detected = Boolean.TRUE.equals(
                    invoke(
                        contains,
                        null,
                        "prefix=%uperms_prefix% rank=%uperms_rank%"
                    )
                );

                if (detected) {
                    pass.accept(
                        "placeholderapi.detect",
                        "PlaceholderAPI detected uperms placeholder syntax"
                    );
                } else {
                    fail.accept(
                        "placeholderapi.detect",
                        "PlaceholderAPI did not detect uperms placeholder syntax"
                    );
                }

                OfflinePlayer target = chooseOfflinePlayer();

                Method setPlaceholders = apiClass.getMethod(
                    "setPlaceholders",
                    OfflinePlayer.class,
                    String.class
                );

                String prefixInput = "%uperms_prefix%";
                String rankInput = "%uperms_rank%";

                Object prefixResultObject = invoke(
                    setPlaceholders,
                    null,
                    target,
                    prefixInput
                );

                Object rankResultObject = invoke(
                    setPlaceholders,
                    null,
                    target,
                    rankInput
                );

                String prefixResult = prefixResultObject == null
                    ? null
                    : prefixResultObject.toString();

                String rankResult = rankResultObject == null
                    ? null
                    : rankResultObject.toString();

                boolean prefixReplaced = prefixResult != null
                    && !prefixInput.equals(prefixResult);

                boolean rankReplaced = rankResult != null
                    && !rankInput.equals(rankResult);

                if (prefixReplaced || rankReplaced) {
                    pass.accept(
                        "placeholderapi.uperms-substitution",
                        "uperms hook performed actual placeholder substitution"
                    );
                } else {
                    fail.accept(
                        "placeholderapi.uperms-substitution",
                        "uperms hook was registered but both known placeholders remained unchanged"
                    );
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "placeholderapi.registry",
                    describe(throwable)
                );
                fail.accept(
                    "placeholderapi.detect",
                    "PlaceholderAPI API access failed before detection completed"
                );
                fail.accept(
                    "placeholderapi.uperms-substitution",
                    "PlaceholderAPI API access failed before substitution completed"
                );
            }
        }

        private OfflinePlayer chooseOfflinePlayer() {
            OfflinePlayer[] known = Bukkit.getOfflinePlayers();

            if (known.length > 0) {
                return known[0];
            }

            UUID uuid = UUID.nameUUIDFromBytes(
                "CardboardCompatProbe-Wave3".getBytes(StandardCharsets.UTF_8)
            );

            return Bukkit.getOfflinePlayer(uuid);
        }

        // -------------------------------------------------------------
        // UltraPermissions
        // -------------------------------------------------------------

        private void runUltraPermissions() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(
                "UltraPermissions"
            );

            if (plugin == null) {
                skip.accept(
                    "ultrapermissions.plugin",
                    "UltraPermissions is not installed"
                );
                skip.accept(
                    "ultrapermissions.api",
                    "UltraPermissions is not installed"
                );
                skip.accept(
                    "ultrapermissions.collections",
                    "UltraPermissions is not installed"
                );
                skip.accept(
                    "ultrapermissions.default-group-option",
                    "UltraPermissions is not installed"
                );
                return;
            }

            if (!plugin.isEnabled()) {
                fail.accept(
                    "ultrapermissions.plugin",
                    "UltraPermissions is installed but disabled"
                );
                skip.accept(
                    "ultrapermissions.api",
                    "UltraPermissions is disabled"
                );
                skip.accept(
                    "ultrapermissions.collections",
                    "UltraPermissions is disabled"
                );
                skip.accept(
                    "ultrapermissions.default-group-option",
                    "UltraPermissions is disabled"
                );
                return;
            }

            pass.accept(
                "ultrapermissions.plugin",
                "UltraPermissions " + plugin.getPluginMeta().getVersion()
            );

            try {
                Class<?> mainClass = plugin.getClass()
                    .getClassLoader()
                    .loadClass(
                        "me.TechsCode.UltraPermissions.UltraPermissions"
                    );

                Method getApi = mainClass.getMethod("getAPI");

                Object api = invoke(
                    getApi,
                    null
                );

                if (api == null) {
                    fail.accept(
                        "ultrapermissions.api",
                        "UltraPermissions.getAPI() returned null"
                    );
                    skip.accept(
                        "ultrapermissions.collections",
                        "UltraPermissions API unavailable"
                    );
                    skip.accept(
                        "ultrapermissions.default-group-option",
                        "UltraPermissions API unavailable"
                    );
                    return;
                }

                pass.accept(
                    "ultrapermissions.api",
                    api.getClass().getName()
                );

                Object groups = invoke(
                    api.getClass().getMethod("getGroups"),
                    api
                );

                Object users = invoke(
                    api.getClass().getMethod("getUsers"),
                    api
                );

                Object permissions = invoke(
                    api.getClass().getMethod("getPermissions"),
                    api
                );

                if (
                    groups != null
                        && users != null
                        && permissions != null
                ) {
                    pass.accept(
                        "ultrapermissions.collections",
                        "groups=" + sizeLike(groups)
                            + " users=" + sizeLike(users)
                            + " permissions=" + sizeLike(permissions)
                    );
                } else {
                    fail.accept(
                        "ultrapermissions.collections",
                        "one or more live storage collections were null"
                    );
                }

                Method optionMethod = api.getClass().getMethod(
                    "getDefaultGroupAssignOption"
                );

                Object option = invoke(
                    optionMethod,
                    api
                );

                if (option != null) {
                    pass.accept(
                        "ultrapermissions.default-group-option",
                        option.toString()
                    );
                } else {
                    fail.accept(
                        "ultrapermissions.default-group-option",
                        "getDefaultGroupAssignOption() returned null"
                    );
                }
            } catch (Throwable throwable) {
                fail.accept(
                    "ultrapermissions.api",
                    describe(throwable)
                );
                fail.accept(
                    "ultrapermissions.collections",
                    "UltraPermissions API access failed"
                );
                fail.accept(
                    "ultrapermissions.default-group-option",
                    "UltraPermissions API access failed"
                );
            }
        }

        // -------------------------------------------------------------
        // CoreProtect
        // -------------------------------------------------------------

        private void runCoreProtect() {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");

            if (plugin == null) {
                skipCoreProtect("CoreProtect is not installed");
                return;
            }

            if (!plugin.isEnabled()) {
                fail.accept(
                    "coreprotect.plugin",
                    "CoreProtect is installed but disabled"
                );
                skip.accept(
                    "coreprotect.api",
                    "CoreProtect is disabled"
                );
                skip.accept(
                    "coreprotect.api-version",
                    "CoreProtect is disabled"
                );
                skip.accept(
                    "coreprotect.log-placement",
                    "CoreProtect is disabled"
                );
                skip.accept(
                    "coreprotect.lookup-placement",
                    "CoreProtect is disabled"
                );
                skip.accept(
                    "coreprotect.no-world-mutation",
                    "CoreProtect is disabled"
                );
                return;
            }

            pass.accept(
                "coreprotect.plugin",
                "CoreProtect " + plugin.getPluginMeta().getVersion()
            );

            if (Bukkit.getWorlds().isEmpty()) {
                fail.accept(
                    "coreprotect.api",
                    "no world available for CoreProtect functional test"
                );
                skip.accept(
                    "coreprotect.api-version",
                    "no world available"
                );
                skip.accept(
                    "coreprotect.log-placement",
                    "no world available"
                );
                skip.accept(
                    "coreprotect.lookup-placement",
                    "no world available"
                );
                skip.accept(
                    "coreprotect.no-world-mutation",
                    "no world available"
                );
                return;
            }

            try {
                Method getApi = plugin.getClass().getMethod("getAPI");

                coreProtectApi = invoke(
                    getApi,
                    plugin
                );

                if (coreProtectApi == null) {
                    fail.accept(
                        "coreprotect.api",
                        "CoreProtect#getAPI() returned null"
                    );
                    skip.accept(
                        "coreprotect.api-version",
                        "CoreProtect API unavailable"
                    );
                    skip.accept(
                        "coreprotect.log-placement",
                        "CoreProtect API unavailable"
                    );
                    skip.accept(
                        "coreprotect.lookup-placement",
                        "CoreProtect API unavailable"
                    );
                    skip.accept(
                        "coreprotect.no-world-mutation",
                        "CoreProtect API unavailable"
                    );
                    return;
                }

                pass.accept(
                    "coreprotect.api",
                    coreProtectApi.getClass().getName()
                );

                Method apiVersion = coreProtectApi.getClass()
                    .getMethod("APIVersion");

                Method apiEnabled = coreProtectApi.getClass()
                    .getMethod("isEnabled");

                Object versionObject = invoke(
                    apiVersion,
                    coreProtectApi
                );

                Object enabledObject = invoke(
                    apiEnabled,
                    coreProtectApi
                );

                int version = ((Number) versionObject).intValue();
                boolean enabled = Boolean.TRUE.equals(enabledObject);

                if (version > 0 && enabled) {
                    pass.accept(
                        "coreprotect.api-version",
                        "API=" + version + " enabled=true"
                    );
                } else {
                    fail.accept(
                        "coreprotect.api-version",
                        "API=" + version + " enabled=" + enabled
                    );
                }

                coreProtectBlock = chooseExistingBlock(
                    Bukkit.getWorlds().get(0)
                );

                if (coreProtectBlock == null) {
                    fail.accept(
                        "coreprotect.log-placement",
                        "could not find an existing non-air block"
                    );
                    skip.accept(
                        "coreprotect.lookup-placement",
                        "no block available"
                    );
                    skip.accept(
                        "coreprotect.no-world-mutation",
                        "no block available"
                    );
                    return;
                }

                coreProtectOriginalData = coreProtectBlock
                    .getBlockData()
                    .clone();

                coreProtectActor = "CBProbe-"
                    + UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 8);

                Method logPlacement = coreProtectApi
                    .getClass()
                    .getMethod(
                        "logPlacement",
                        String.class,
                        org.bukkit.Location.class,
                        Material.class,
                        BlockData.class
                    );

                boolean accepted = Boolean.TRUE.equals(
                    invoke(
                        logPlacement,
                        coreProtectApi,
                        coreProtectActor,
                        coreProtectBlock.getLocation(),
                        coreProtectBlock.getType(),
                        coreProtectBlock.getBlockData()
                    )
                );

                if (!accepted) {
                    fail.accept(
                        "coreprotect.log-placement",
                        "CoreProtect API rejected synthetic placement log"
                    );
                    skip.accept(
                        "coreprotect.lookup-placement",
                        "placement log was rejected"
                    );
                    verifyNoWorldMutation();
                    return;
                }

                pass.accept(
                    "coreprotect.log-placement",
                    "synthetic placement audit row accepted"
                );

                coreProtectHasPlaced = coreProtectApi
                    .getClass()
                    .getMethod(
                        "hasPlaced",
                        String.class,
                        Block.class,
                        int.class,
                        int.class
                    );

                coreProtectLookupPending = true;

                pollCoreProtectLookup(0);
            } catch (Throwable throwable) {
                fail.accept(
                    "coreprotect.api",
                    describe(throwable)
                );
                fail.accept(
                    "coreprotect.api-version",
                    "CoreProtect API access failed"
                );
                fail.accept(
                    "coreprotect.log-placement",
                    "CoreProtect API access failed"
                );
                skip.accept(
                    "coreprotect.lookup-placement",
                    "CoreProtect placement log did not complete"
                );

                if (coreProtectBlock != null) {
                    verifyNoWorldMutation();
                } else {
                    skip.accept(
                        "coreprotect.no-world-mutation",
                        "no CoreProtect test block selected"
                    );
                }
            }
        }

        private void pollCoreProtectLookup(int elapsedTicks) {
            if (!coreProtectLookupPending) {
                return;
            }

            try {
                boolean found = Boolean.TRUE.equals(
                    invoke(
                        coreProtectHasPlaced,
                        coreProtectApi,
                        coreProtectActor,
                        coreProtectBlock,
                        300,
                        0
                    )
                );

                if (found) {
                    coreProtectLookupPending = false;

                    pass.accept(
                        "coreprotect.lookup-placement",
                        "synthetic actor was found through CoreProtect lookup"
                    );

                    verifyNoWorldMutation();

                    completion.run();
                    return;
                }
            } catch (Throwable throwable) {
                coreProtectLookupPending = false;

                fail.accept(
                    "coreprotect.lookup-placement",
                    describe(throwable)
                );

                verifyNoWorldMutation();

                completion.run();
                return;
            }

            if (elapsedTicks >= COREPROTECT_LOOKUP_TIMEOUT_TICKS) {
                coreProtectLookupPending = false;

                fail.accept(
                    "coreprotect.lookup-placement",
                    "synthetic placement was not queryable within "
                        + COREPROTECT_LOOKUP_TIMEOUT_TICKS
                        + " ticks"
                );

                verifyNoWorldMutation();

                completion.run();
                return;
            }

            Bukkit.getScheduler().runTaskLater(
                probe,
                () -> pollCoreProtectLookup(
                    elapsedTicks + COREPROTECT_LOOKUP_INTERVAL_TICKS
                ),
                COREPROTECT_LOOKUP_INTERVAL_TICKS
            );
        }

        private void verifyNoWorldMutation() {
            if (
                coreProtectBlock != null
                    && coreProtectOriginalData != null
                    && coreProtectOriginalData.matches(
                        coreProtectBlock.getBlockData()
                    )
            ) {
                pass.accept(
                    "coreprotect.no-world-mutation",
                    "CoreProtect API test did not modify the world block"
                );
            } else {
                fail.accept(
                    "coreprotect.no-world-mutation",
                    "world block changed during CoreProtect API test"
                );
            }
        }

        private Block chooseExistingBlock(World world) {
            int x = world.getSpawnLocation().getBlockX();
            int z = world.getSpawnLocation().getBlockZ();

            int top = Math.min(
                world.getHighestBlockYAt(x, z),
                world.getMaxHeight() - 1
            );

            int bottom = Math.max(
                world.getMinHeight(),
                top - 16
            );

            for (int y = top; y >= bottom; y--) {
                Block block = world.getBlockAt(x, y, z);

                if (
                    !block.getType().isAir()
                        && !block.isLiquid()
                ) {
                    return block;
                }
            }

            return null;
        }

        private void skipCoreProtect(String reason) {
            skip.accept("coreprotect.plugin", reason);
            skip.accept("coreprotect.api", reason);
            skip.accept("coreprotect.api-version", reason);
            skip.accept("coreprotect.log-placement", reason);
            skip.accept("coreprotect.lookup-placement", reason);
            skip.accept("coreprotect.no-world-mutation", reason);
        }

        // -------------------------------------------------------------
        // Helpers
        // -------------------------------------------------------------

        private int sizeLike(Object value) {
            if (value instanceof Collection<?> collection) {
                return collection.size();
            }

            try {
                Method size = value.getClass().getMethod("size");
                Object result = invoke(size, value);

                if (result instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
            }

            if (value instanceof Iterable<?> iterable) {
                int count = 0;

                for (Object ignored : iterable) {
                    count++;
                }

                return count;
            }

            return -1;
        }

        private static Object invoke(
            Method method,
            Object target,
            Object... arguments
        ) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException throwable) {
                Throwable cause = throwable.getCause();

                if (cause != null) {
                    throw cause;
                }

                throw throwable;
            }
        }

        private static String describe(Throwable throwable) {
            String message = throwable.getMessage();

            return throwable.getClass().getName()
                + (
                    message == null || message.isBlank()
                        ? ""
                        : ": " + message
                );
        }
    }
}
