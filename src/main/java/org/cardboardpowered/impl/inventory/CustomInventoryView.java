package org.cardboardpowered.impl.inventory;

import net.minecraft.world.inventory.AbstractContainerMenu;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Inventory;

public class CustomInventoryView extends CraftInventoryView {

    public CustomInventoryView(HumanEntity player, Inventory viewing, AbstractContainerMenu container) {
        super(player != null ? player : cardboard$resolvePlayer(container), viewing, container);
    }

    private static HumanEntity cardboard$resolvePlayer(AbstractContainerMenu container) {
        if (container == null) {
            return null;
        }

        // Generic/modded menus do not always have a dedicated CraftBukkit menu
        // wrapper, so AbstractContainerMenuMixin creates their fallback view
        // without a player. Resolve the active viewer from the live menu before
        // Bukkit events are constructed; InventoryClickEvent#getWhoClicked()
        // and CraftInventoryView#getBottomInventory() both depend on it.
        for (org.bukkit.entity.Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer instanceof CraftPlayer craftPlayer
                    && craftPlayer.getHandle().containerMenu == container) {
                return onlinePlayer;
            }
        }

        return null;
    }
}
