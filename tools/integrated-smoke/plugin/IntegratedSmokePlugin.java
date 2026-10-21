package org.cardboardpowered.smoke.plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegratedSmokePlugin extends JavaPlugin implements Listener {
    private final ConcurrentMap<UUID, Integer> preLogins = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> logins = new ConcurrentHashMap<>();
    private volatile boolean bukkitAsyncRan;
    private volatile boolean foliaAsyncRan;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskAsynchronously(this, () -> bukkitAsyncRan = true);
        getServer().getAsyncScheduler().runNow(this, task -> foliaAsyncRan = true);
        // Start the timer executor too; it must be released when the world closes.
        getServer().getAsyncScheduler().runDelayed(this, task -> {}, 1, java.util.concurrent.TimeUnit.HOURS);
    }

    @EventHandler
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        preLogins.merge(event.getUniqueId(), 1, Integer::sum);
    }

    @EventHandler
    public void login(PlayerLoginEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (preLogins.getOrDefault(id, 0) != 1) {
            throw new AssertionError("Pre-login was missing, duplicated, or had a different UUID");
        }
        logins.merge(id, 1, Integer::sum);
        // Keep the fixture's unknown permission denied even for the owner/operator.
        event.getPlayer().addAttachment(this, "cardboard.smoke.denied", false);
        event.getPlayer().addAttachment(this, "cardboard.smoke.fabric", false);
    }

    public void verifyLogin(UUID owner) {
        if (!bukkitAsyncRan || !foliaAsyncRan) throw new AssertionError("Async schedulers did not execute their tasks");
        if (preLogins.getOrDefault(owner, 0) != 1 || logins.getOrDefault(owner, 0) != 1) {
            throw new AssertionError("Expected exactly one pre-login and login for " + owner + ": " + preLogins + " / " + logins);
        }
    }
}
