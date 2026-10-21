package org.cardboardpowered.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.plugin.Plugin;

/** Disposable client fixture: real owner login and two server lifecycles in one JVM. */
public final class CardboardIntegratedSmoke implements ClientModInitializer {
    private volatile int stage;
    private int world;
    private CompletableFuture<Void> verification;
    private Throwable clientProbeFailure;
    private Plugin previousPlugin;
    private Object previousRegistry;
    private int previousMaterialCount = -1;
    private long closeDeadline;
    private static final boolean LAN = Boolean.getBoolean("cardboard.smoke.lan");
    private static final Path EXCHANGE = Path.of(System.getProperty("cardboard.smoke.exchange", "."));
    private final long deadline = System.nanoTime() + 600_000_000_000L;

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("cardboard.smoke.guest")) {
            new IntegratedLanGuest().onInitializeClient();
            return;
        }
        System.out.println("INTEGRATED_SMOKE_START");
        me.lucko.fabric.api.permissions.v0.PermissionCheckEvent.EVENT.register((source, permission) -> {
            if (permission.equals("cardboard.smoke.fabric")) return net.fabricmc.fabric.api.util.TriState.TRUE;
            if (permission.equals("cardboard.smoke.denied")) return net.fabricmc.fabric.api.util.TriState.FALSE;
            return net.fabricmc.fabric.api.util.TriState.DEFAULT;
        });
        Thread.ofPlatform().daemon().name("Integrated smoke watchdog").start(() -> {
            try {
                Thread.sleep(420_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stage != 4) {
                System.err.println("INTEGRATED_SMOKE_WATCHDOG stage=" + stage + " world=" + world);
                Thread.getAllStackTraces().forEach((thread, frames) -> {
                    System.err.println(thread.getName() + " " + thread.getState());
                    for (var frame : frames) System.err.println("  at " + frame);
                });
                System.exit(1);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft client) {
        try {
            if (System.nanoTime() > deadline) throw new AssertionError("Client smoke timed out at stage " + stage);
            if (stage == 0 && client.isGameLoadFinished() && client.gui.overlay() == null) {
                createWorld(client);
            } else if (stage == 1 && client.player != null && client.getSingleplayerServer() != null) {
                clientProbeFailure = null;
                try {
                    exerciseClientCopies(client);
                } catch (Throwable error) {
                    // Run the server probes too, so client failures cannot hide server regressions.
                    clientProbeFailure = error;
                    error.printStackTrace();
                }
                MinecraftServer server = client.getSingleplayerServer();
                UUID owner = client.getUser().getProfileId();
                verification = CompletableFuture.runAsync(() -> verifyServer(server, owner), server);
                stage = 2;
            } else if (stage == 2 && verification.isDone()) {
                verification.join();
                if (clientProbeFailure != null) throw new AssertionError("Client entity probes failed", clientProbeFailure);
                if (LAN) {
                    var server = client.getSingleplayerServer();
                    // Disposable CI profiles have no Microsoft session. Still use vanilla LAN publication and real TCP login.
                    server.setUsesAuthentication(false);
                    int port = 25576 + world;
                    require(server.publishServer(MinecraftServer.MultiplayerScope.LAN, GameType.SURVIVAL, false, port), "LAN publication failed");
                    Files.createDirectories(EXCHANGE);
                    Files.writeString(EXCHANGE.resolve("ready-" + world), Integer.toString(port));
                    System.out.println("INTEGRATED_LAN_HOST_READY " + world);
                    stage = 5;
                } else closeWorld(client);
            } else if (stage == 5 && Files.exists(EXCHANGE.resolve("joined-" + world))) {
                var server = client.getSingleplayerServer();
                verification = CompletableFuture.runAsync(() -> verifyLanGuest(server), server);
                stage = 6;
            } else if (stage == 6 && verification.isDone()) {
                verification.join();
                Files.writeString(EXCHANGE.resolve("verified-" + world), "PASS");
                if (Files.exists(EXCHANGE.resolve("left-" + world))) closeWorld(client);
            } else if (stage == 3 && client.getSingleplayerServer() == null && client.level == null) {
                var remaining = Thread.getAllStackTraces().keySet().stream()
                        .filter(Thread::isAlive).map(Thread::getName)
                        .filter(name -> name.equals("Cardboard metrics")
                                || name.startsWith("Paper Scheduler Thread")
                                || name.startsWith("Craft Async Scheduler Management Thread")
                                || name.startsWith("Folia Async Scheduler Thread")
                                || name.equals("Spigot Watchdog Thread"))
                        .toList();
                if (!remaining.isEmpty()) {
                    require(System.nanoTime() < closeDeadline, "World threads survived close: " + remaining);
                    return;
                }
                if (world < 2) {
                    createWorld(client);
                } else {
                    Files.writeString(Path.of("integrated-smoke.success"), "Two worlds, real owner UUID, one pre-login/login each, fresh plugin command and Fabric permissions verified.\n");
                    System.out.println("INTEGRATED_SMOKE_PASS");
                    stage = 4;
                    client.stop();
                }
            }
        } catch (Throwable error) {
            error.printStackTrace();
            System.err.println("INTEGRATED_SMOKE_FAILED stage=" + stage + " world=" + world);
            System.exit(1);
        }
    }

    private void closeWorld(Minecraft client) {
        client.disconnect(new TitleScreen(), false);
        require(CraftServer.INSTANCE == null && Bukkit.getServer() == null, "Bukkit singleton survived world close");
        require(!org.cardboardpowered.CardboardMod.isAfterWorldLoad, "World-loaded flag survived world close");
        closeDeadline = System.nanoTime() + 5_000_000_000L;
        stage = 3;
    }

    private void verifyLanGuest(MinecraftServer server) {
        try {
            UUID id = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("SmokeGuest");
            var guest = Bukkit.getPlayer(id);
            require(guest != null && guest.isOnline(), "TCP guest did not reach Bukkit with its offline UUID");
            require(Bukkit.getOnlinePlayers().size() == 2, "Owner and guest are not online together");
            require(!guest.isOp(), "LAN guest unexpectedly became an operator");
            Plugin plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
            plugin.getClass().getMethod("verifyLogin", UUID.class).invoke(plugin, id);
            require(guest.hasPermission("cardboard.smoke.fabric"), "Guest Fabric permission grant was lost");
            require(!guest.hasPermission("cardboard.smoke.denied"), "Guest Fabric denial was lost");
            System.out.println("INTEGRATED_LAN_HOST_PASS " + world + " guest=" + id);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private void createWorld(Minecraft client) {
        world++;
        stage = 1;
        String name = "cardboard-smoke-" + world;
        System.out.println("INTEGRATED_SMOKE_CREATE " + name);
        var settings = new LevelSettings(name, GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(42L, false, false),
                WorldPresets::createNormalWorldDimensions, new TitleScreen());
    }

    private void verifyServer(MinecraftServer server, UUID owner) {
        try {
            require(Bukkit.getServer() != null, "Bukkit was not initialized");
            var player = Bukkit.getPlayer(owner);
            require(player != null, "Owner UUID was replaced during login: " + owner);
            require(player.getGameMode() == org.bukkit.GameMode.CREATIVE, "Stored singleplayer game mode was overwritten");
            Plugin plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
            require(plugin != null && plugin.isEnabled(), "Smoke plugin was not enabled");
            Plugin bootstrapPlugin = Bukkit.getPluginManager().getPlugin("IntegratedBootstrap");
            require(bootstrapPlugin != null && bootstrapPlugin.isEnabled(), "Paper fixture was not enabled");
            require(Integer.parseInt(System.getProperty("cardboard.smoke.bootstrap.count", "0")) == world,
                    "Paper bootstrapper did not run once per world");
            require(Integer.parseInt(System.getProperty("cardboard.smoke.compose.count", "0")) == world,
                    "Registry compose handler was late, missing, or retained from the previous world");
            require(plugin != previousPlugin, "Previous world's plugin instance was reused");
            require(Bukkit.getPluginCommand("smokeworld") != null
                    && Bukkit.getPluginCommand("smokeworld").getPlugin() == plugin, "Stale command owner");
            plugin.getClass().getMethod("verifyLogin", UUID.class).invoke(plugin, owner);
            EntityEventSmoke.verifyServer(server.getPlayerList().getPlayer(owner), plugin);
            require(player.hasPermission("cardboard.smoke.fabric"), "Fabric permission grant was lost");
            require(!player.hasPermission("cardboard.smoke.denied"), "Fabric denial was lost");
            // Exercise property-backed API methods against an IntegratedServer.
            Bukkit.getServer().getViewDistance();
            Bukkit.getServer().getSimulationDistance();
            Bukkit.getServer().getWorldType();
            Bukkit.getServer().getMaxWorldSize();
            Bukkit.getServer().getSpawnRadius();
            int recipes = 0;
            var iterator = Bukkit.recipeIterator();
            while (iterator.hasNext()) {
                iterator.next();
                recipes++;
            }
            require(recipes > 0, "Recipe conversion returned no recipes");
            Object registry = io.papermc.paper.registry.PaperRegistryAccess.instance()
                    .getNativeRegistry(net.minecraft.core.registries.Registries.ENCHANTMENT);
            require(registry != null && registry != previousRegistry, "Previous world's native registry was reused");
            int materials = org.bukkit.Material.values().length;
            require(previousMaterialCount == -1 || previousMaterialCount == materials, "Materials were appended again for the second world");
            previousMaterialCount = materials;
            previousRegistry = registry;
            previousPlugin = plugin;
            System.out.println("INTEGRATED_SMOKE_WORLD_PASS " + world + " owner=" + owner);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void exerciseClientCopies(Minecraft client) {
        var inventory = client.player.getInventory();
        new EnchantmentMenu(1, inventory, ContainerLevelAccess.NULL);
        new GrindstoneMenu(2, inventory, ContainerLevelAccess.NULL);
        new LoomMenu(3, inventory, ContainerLevelAccess.NULL);
        new StonecutterMenu(4, inventory, ContainerLevelAccess.NULL);
        client.player.setPose(Pose.CROUCHING);
        client.player.setAirSupply(250);
        require(client.player.getAirSupply() == 250, "Client air prediction was cancelled");
        new ItemEntity(client.level, client.player.getX(), client.player.getY(), client.player.getZ(), new ItemStack(Items.DIRT)).tick();
        EntityEventSmoke.verifyClient(client);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
