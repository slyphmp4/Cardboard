package org.cardboardpowered.ci;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** An in-game check for the inventory event and the API path used by passive enchants. */
public final class PassiveEffectsProbe extends JavaPlugin implements Listener {
    private final Map<UUID, Probe> pending = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Run this command as a player.");
            return true;
        }
        if (pending.containsKey(player.getUniqueId())) {
            sender.sendMessage("Probe already running.");
            return true;
        }

        ItemStack previous = player.getInventory().getHelmet();
        ItemStack helmet = new ItemStack(Material.NETHERITE_HELMET);
        ItemMeta meta = helmet.getItemMeta();
        meta.setDisplayName("Cardboard passive probe");
        helmet.setItemMeta(meta);

        Probe probe = new Probe(previous == null ? new ItemStack(Material.AIR) : previous.clone(), helmet);
        pending.put(player.getUniqueId(), probe);
        player.getInventory().setHelmet(helmet);
        getServer().getScheduler().runTaskLater(this, () -> finish(player, probe), 3L);
        return true;
    }

    @EventHandler
    public void onSlotChanged(PlayerInventorySlotChangeEvent event) {
        Probe probe = pending.get(event.getPlayer().getUniqueId());
        if (probe != null && event.getRawSlot() == 5 && probe.helmet.equals(event.getNewItemStack())) {
            probe.slotEvent = probe.previous.equals(event.getOldItemStack());
        }
    }

    private void finish(Player player, Probe probe) {
        pending.remove(player.getUniqueId());
        boolean effects = checkEffect(player, PotionEffectType.NIGHT_VISION)
                & checkEffect(player, PotionEffectType.HEALTH_BOOST);
        // Restore only if our helmet is still equipped; never overwrite a player's later change.
        if (probe.helmet.equals(player.getInventory().getHelmet())) {
            player.getInventory().setHelmet(probe.previous);
        }
        boolean success = probe.slotEvent && effects;
        String result = (success ? "PASSIVE_PROBE_OK" : "PASSIVE_PROBE_FAIL")
                + ":slot=" + probe.slotEvent + ",effects=" + effects;
        getLogger().info(result + " player=" + player.getName());
        player.sendMessage(result);
    }

    private boolean checkEffect(Player player, PotionEffectType type) {
        PotionEffect previous = player.getPotionEffect(type);
        PotionEffect expected = new PotionEffect(type, 120, 1, false, false, false);
        try {
            boolean added = player.addPotionEffect(expected);
            PotionEffect read = player.getPotionEffect(type);
            boolean matched = added && read != null && read.getAmplifier() == expected.getAmplifier()
                    && read.getDuration() == expected.getDuration() && !read.hasParticles() && !read.hasIcon();
            player.removePotionEffect(type);
            return matched && !player.hasPotionEffect(type);
        } finally {
            player.removePotionEffect(type);
            if (previous != null) {
                player.addPotionEffect(previous);
            }
        }
    }

    private static final class Probe {
        final ItemStack previous;
        final ItemStack helmet;
        boolean slotEvent;

        Probe(ItemStack previous, ItemStack helmet) {
            this.previous = previous;
            this.helmet = helmet;
        }
    }
}
