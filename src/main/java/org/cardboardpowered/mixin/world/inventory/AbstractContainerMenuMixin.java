package org.cardboardpowered.mixin.world.inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.inventory.RemoteSlot;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.inventory.CraftInventory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryView;
import org.cardboardpowered.impl.inventory.CustomInventoryView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.cardboardpowered.CardboardMod;
import org.cardboardpowered.bridge.world.ContainerBridge;
import org.cardboardpowered.bridge.world.inventory.AbstractContainerMenuBridge;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin implements AbstractContainerMenuBridge {

    public boolean checkReachable = true;

    @Override
    public InventoryView getBukkitView() {
        CraftInventory cbi = new CraftInventory(new SimpleContainer( ((AbstractContainerMenu)(Object)this).getItems().toArray(new ItemStack[0]) ));
        return new CustomInventoryView(null, cbi, ((AbstractContainerMenu)(Object)this));
    }

    @Shadow
    @Final
    @Mutable
    public NonNullList<ItemStack> lastSlots;

    /**
     * field_29206
     * 1.21.4: previousTrackedStacks
     * 1.21.8: trackedSlots
     */
    @Shadow
    @Final
    @Mutable
    public NonNullList<ItemStack> remoteSlots;
    
    @Shadow
    @Final
    @Mutable
    public NonNullList<Slot> slots;

    @Shadow
    public ItemStack getCarried() {
        return null;
    }

    @Shadow
    public abstract void setCarried(ItemStack stack);

    @Shadow
    private int quickcraftStatus;

    @Shadow
    private int quickcraftType;

    @Shadow
    @Final
    private Set<Slot> quickcraftSlots;

    @Shadow
    protected void resetQuickCraft() {
        throw new AssertionError();
    }

    @Shadow
    private RemoteSlot remoteCarried;

    @Shadow
    private @Nullable ContainerSynchronizer synchronizer;

    @Override
    public void transferTo(AbstractContainerMenu other, CraftHumanEntity player) {
        InventoryView source = this.getBukkitView(), destination = ((AbstractContainerMenuBridge)other).getBukkitView();

        // Fallback views (CustomInventoryView) have no player attached, so their bottom
        // inventory can not be resolved - skip them instead of throwing a NullPointerException.
        if (source instanceof CustomInventoryView || destination instanceof CustomInventoryView
                || source.getPlayer() == null || destination.getPlayer() == null) {
            return;
        }

        openOrClose( ((CraftInventory) source.getTopInventory()).getInventory(), player, false);
        openOrClose( ((CraftInventory) source.getBottomInventory()).getInventory(), player, false);
        openOrClose( ((CraftInventory) destination.getTopInventory()).getInventory(), player, true);
        openOrClose( ((CraftInventory) destination.getBottomInventory()).getInventory(), player, true);
    }

    public void openOrClose(Container in, CraftHumanEntity plr, boolean open) {
        if (in instanceof ContainerBridge) {
            ContainerBridge imi = (ContainerBridge) in;
            if (open) {
                imi.onOpen(plr);
            } else {
                imi.onClose(plr);
            }
        } else {
            if (FabricLoader.getInstance().isDevelopmentEnvironment())
                CardboardMod.LOGGER.info("Debug: " + in + " is not of type IMixinInventory");
        }
    }

    private Component title_cb;

    @Override
    public final Component getTitle() {
        if (null == this.title_cb)
            this.title_cb = Component.nullToEmpty(" nul ");
        return this.title_cb;
    }

    @Override
    public void setTitle(Component title) {
        this.title_cb = title;
    }

    @Override
    public NonNullList<ItemStack> getTrackedStacksBF() {
        return lastSlots;
    }
    
    @Override
    public NonNullList<ItemStack> cardboard_previousTrackedStacks() {
        return remoteSlots;
    }
    
    @Override
    public void cardboard_previousTrackedStacks(NonNullList<ItemStack> s) {
        this.remoteSlots = s;
    }

    @Override
    public void setTrackedStacksBF(NonNullList<ItemStack> trackedStacks) {
       this.lastSlots = trackedStacks;
    }

    @Override
    public void cardboard_setSlots(NonNullList<Slot> slots) {
        this.slots = slots;
    }

    @Override
    public void cardboard$setCheckReachable(boolean bl) {
        this.checkReachable = bl;
    }

    /**
     * CraftBukkit/Paper parity for QUICK_CRAFT. The vanilla quick-craft state is
     * built over several packets; the Bukkit event belongs to the final packet,
     * before any slot mutation is committed.
     */
    @Inject(method = "clicked", at = @At("HEAD"), cancellable = true)
    private void cardboard$inventoryDragEvent(int slotIndex, int buttonNum, ContainerInput containerInput, Player player, CallbackInfo ci) {
        if (containerInput != ContainerInput.QUICK_CRAFT) {
            return;
        }

        final int header = buttonNum & 3;
        // QUICKCRAFT_HEADER_END = 2, QUICKCRAFT_HEADER_CONTINUE = 1
        if (header != 2 || this.quickcraftStatus != 1 || this.quickcraftSlots.isEmpty() || this.getCarried().isEmpty()) {
            return;
        }

        final ItemStack oldCarried = this.getCarried().copy();
        final ItemStack source = oldCarried.copy();
        int remaining = source.getCount();
        final Map<Integer, org.bukkit.inventory.ItemStack> eventMap = new HashMap<>();

        for (Slot slot : this.quickcraftSlots) {
            ItemStack current = slot.getItem();
            int existing = current.isEmpty() ? 0 : current.getCount();
            int placed;
            switch (this.quickcraftType) {
                case 0 -> placed = source.getCount() / this.quickcraftSlots.size(); // left drag / even split
                case 1 -> placed = 1; // right drag / one per slot
                case 2 -> placed = source.getMaxStackSize(); // creative clone drag
                default -> {
                    return;
                }
            }

            int maxSize = Math.min(source.getMaxStackSize(), slot.getMaxStackSize(source));
            int newCount = Math.min(existing + placed, maxSize);
            remaining -= newCount - existing;
            eventMap.put(slot.index, CraftItemStack.asBukkitCopy(source.copyWithCount(newCount)));
        }

        org.bukkit.inventory.ItemStack newCursor = CraftItemStack.asBukkitCopy(source);
        newCursor.setAmount(Math.max(remaining, 0));

        // Match CraftBukkit's ordering: update cursor before calling plugins so a
        // plugin closing the inventory from the event cannot duplicate the stack.
        this.setCarried(CraftItemStack.asNMSCopy(newCursor));

        InventoryView view = this.getBukkitView();
        InventoryDragEvent event = new InventoryDragEvent(
                view,
                newCursor,
                CraftItemStack.asBukkitCopy(oldCarried),
                this.quickcraftType == 1,
                eventMap
        );
        Bukkit.getPluginManager().callEvent(event);

        if (event.getResult() != Event.Result.DENY) {
            for (Map.Entry<Integer, org.bukkit.inventory.ItemStack> entry : eventMap.entrySet()) {
                view.setItem(entry.getKey(), entry.getValue());
            }
            this.setCarried(CraftItemStack.asNMSCopy(event.getCursor()));
        } else {
            this.setCarried(oldCarried);
        }

        this.resetQuickCraft();
        ((AbstractContainerMenu)(Object)this).sendAllDataToRemote();
        ci.cancel();
    }

    // CraftBukkit start - from synchronizeCarriedToRemote
    @Override
    public void cardboard$broadcastCarriedItem() {
        ItemStack carried = this.getCarried();
        this.remoteCarried.force(carried);
        if (this.synchronizer != null) {
            this.synchronizer.sendCarriedChange((AbstractContainerMenu)(Object)this, carried.copy());
        }
    }
    // CraftBukkit end
}
