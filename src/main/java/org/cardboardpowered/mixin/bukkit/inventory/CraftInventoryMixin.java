package org.cardboardpowered.mixin.bukkit.inventory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.Container;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CraftInventory.class, remap = false)
public abstract class CraftInventoryMixin {

    @Shadow
    public abstract Container getInventory();

    @Unique
    private static final Set<String> cardboard$warnedNullContents = ConcurrentHashMap.newKeySet();

    @Inject(method = "asCraftMirror", at = @At("HEAD"), cancellable = true, remap = false)
    private void cardboard$recoverNullContents(
            List<net.minecraft.world.item.ItemStack> mcItems,
            CallbackInfoReturnable<org.bukkit.inventory.ItemStack[]> cir
    ) {
        if (mcItems != null) {
            return;
        }

        Container container = this.getInventory();
        String containerClass = container.getClass().getName();
        if (cardboard$warnedNullContents.add(containerClass)) {
            Bukkit.getLogger().warning(
                    "ContainerBridge#getContents() returned null for " + containerClass
                            + "; rebuilding Bukkit inventory contents from container slots"
            );
        }

        // Recover the actual backing slots instead of pretending a malformed bridge is empty.
        int size = container.getContainerSize();
        org.bukkit.inventory.ItemStack[] contents = new org.bukkit.inventory.ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            net.minecraft.world.item.ItemStack item = container.getItem(slot);
            contents[slot] = item == null || item.isEmpty() ? null : CraftItemStack.asCraftMirror(item);
        }

        cir.setReturnValue(contents);
    }
}
