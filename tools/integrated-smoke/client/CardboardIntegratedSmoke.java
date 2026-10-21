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
    private Plugin previousPlugin;
    private Object previousRegistry;
    private int previousMaterialCount = -1;
    private final long deadline = System.nanoTime() + 600_000_000_000L;

    @Override
    public void onInitializeClient() {
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
            if (stage < 4) {
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
                exerciseClientCopies(client);
                MinecraftServer server = client.getSingleplayerServer();
                UUID owner = client.getUser().getProfileId();
                verification = CompletableFuture.runAsync(() -> verifyServer(server, owner), server);
                stage = 2;
            } else if (stage == 2 && verification.isDone()) {
                verification.join();
                client.disconnect(new TitleScreen(), false);
                require(CraftServer.INSTANCE == null && Bukkit.getServer() == null, "Bukkit singleton survived world close");
                require(!org.cardboardpowered.CardboardMod.isAfterWorldLoad, "World-loaded flag survived world close");
                stage = 3;
            } else if (stage == 3 && client.getSingleplayerServer() == null && client.level == null) {
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
            require(plugin != previousPlugin, "Previous world's plugin instance was reused");
            require(Bukkit.getPluginCommand("smokeworld") != null
                    && Bukkit.getPluginCommand("smokeworld").getPlugin() == plugin, "Stale command owner");
            plugin.getClass().getMethod("verifyLogin", UUID.class).invoke(plugin, owner);
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

    private void exerciseClientCopies(Minecraft client) {
        var inventory = client.player.getInventory();
        new EnchantmentMenu(1, inventory, ContainerLevelAccess.NULL);
        new GrindstoneMenu(2, inventory, ContainerLevelAccess.NULL);
        new LoomMenu(3, inventory, ContainerLevelAccess.NULL);
        new StonecutterMenu(4, inventory, ContainerLevelAccess.NULL);
        client.player.setPose(Pose.CROUCHING);
        client.player.setAirSupply(250);
        require(client.player.getAirSupply() == 250, "Client air prediction was cancelled");
        new ItemEntity(client.level, client.player.getX(), client.player.getY(), client.player.getZ(), new ItemStack(Items.DIRT)).tick();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
