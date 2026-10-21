package org.cardboardpowered.smoke.plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class IntegratedSmokePlugin extends JavaPlugin implements Listener {
    private final ConcurrentMap<UUID, Integer> preLogins = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Integer> logins = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> entityProbes = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> targetReplacements = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> entityEventCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> entityEventReasons = new ConcurrentHashMap<>();
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

    // These methods cross the fixture plugin classloader using only JDK types.
    public void probeEntity(UUID entity, String behavior, UUID replacement) {
        entityProbes.put(entity, behavior);
        if (replacement == null) targetReplacements.remove(entity);
        else targetReplacements.put(entity, replacement);
    }

    public int probeCount(UUID entity, String kind) {
        return entityEventCounts.getOrDefault(entity + ":" + kind, 0);
    }

    public String probeReason(UUID entity, String kind) {
        return entityEventReasons.get(entity + ":" + kind);
    }

    @EventHandler
    public void regainHealth(EntityRegainHealthEvent event) {
        UUID id = event.getEntity().getUniqueId();
        String behavior = entityProbes.get(id);
        if (behavior == null) return;
        entityEventCounts.merge(id + ":heal", 1, Integer::sum);
        entityEventReasons.put(id + ":heal", event.getRegainReason().name());
        if (behavior.equals("HEAL_CANCEL")) event.setCancelled(true);
        if (behavior.equals("HEAL_MODIFY")) event.setAmount(3.0);
    }

    @EventHandler
    public void target(EntityTargetLivingEntityEvent event) {
        UUID id = event.getEntity().getUniqueId();
        String behavior = entityProbes.get(id);
        if (behavior == null) return;
        entityEventCounts.merge(id + ":target", 1, Integer::sum);
        entityEventReasons.put(id + ":target", event.getReason().name());
        if (behavior.equals("TARGET_CANCEL")) event.setCancelled(true);
        if (behavior.equals("TARGET_REPLACE")) {
            var replacement = getServer().getEntity(targetReplacements.get(id));
            if (!(replacement instanceof org.bukkit.entity.LivingEntity living)) {
                throw new AssertionError("Target replacement was not a live Bukkit entity");
            }
            event.setTarget(living);
        }
    }
}
