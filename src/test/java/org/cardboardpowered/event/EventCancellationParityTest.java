package org.cardboardpowered.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EventCancellationParityTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/" + path));
    }

    @Test
    void packetLevelPlayerActionsHavePreVanillaCancellationGates() throws Exception {
        String network = source("org/cardboardpowered/mixin/server/network/ServerGamePacketListenerImplMixin.java");
        String actions = source("org/cardboardpowered/mixin/server/network/ServerGamePacketListenerImplMixin_PlayerActionEvents.java");

        assertTrue(network.contains("PlayerToggleSneakEvent"));
        assertTrue(network.contains("method = \"handlePlayerInput\""));
        assertTrue(network.contains("this.player.getLastClientInput().shift() == packet.input().shift()"));
        assertTrue(network.contains("this.player.setLastClientInput(packet.input());"));
        assertTrue(network.contains("@Inject(at = @At(\"HEAD\"), method = \"handlePlayerAbilities\", cancellable = true)"));
        assertTrue(network.contains("double y = poss.y;"));
        assertTrue(network.contains("double z = poss.z;"));
        assertTrue(actions.contains("PlayerSwapHandItemsEvent"));
        assertTrue(actions.contains("PlayerInteractAtEntityEvent"));
        assertTrue(actions.contains("ci.cancel();"));
    }

    @Test
    void inventoryAndWorldActionsCanPreventVanillaMutation() throws Exception {
        String inventory = source("org/cardboardpowered/mixin/world/inventory/AbstractContainerMenuMixin.java");
        String gameMode = source("org/cardboardpowered/mixin/server/level/ServerPlayerGameModeMixin.java");
        String blockItem = source("org/cardboardpowered/mixin/world/item/BlockItemMixin.java");

        assertTrue(inventory.contains("InventoryDragEvent"));
        assertTrue(inventory.contains("containerInput != ContainerInput.QUICK_CRAFT"));
        assertTrue(inventory.contains("this.resetQuickCraft();"));
        assertTrue(gameMode.indexOf("callPlayerInteractEvent(entityplayer, Action.RIGHT_CLICK_BLOCK")
                < gameMode.indexOf("UseBlockCallback.EVENT.invoker().interact"));
        assertTrue(blockItem.contains("cardboard$captureReplacedBlockState"));
        assertTrue(blockItem.contains("CraftEventFactory.callBlockPlaceEvent"));
        assertTrue(blockItem.contains("InteractionResult.FAIL"));
    }

    @Test
    void entityStateEventsHaveAuthoritativeCancellationPaths() throws Exception {
        String living = source("org/cardboardpowered/mixin/world/entity/LivingEntityMixin.java");
        String player = source("org/cardboardpowered/mixin/world/entity/player/PlayerMixin.java");
        String pickup = source("org/cardboardpowered/mixin/world/entity/item/ItemEntityMixin.java");
        String velocity = source("org/cardboardpowered/mixin/server/level/ServerEntityMixin.java");
        String projectile = source("org/cardboardpowered/mixin/server/level/ServerLevelMixin.java");
        String vehicle = source("org/cardboardpowered/mixin/world/entity/EntityMixin_VehicleEvents.java");
        String food = source("org/cardboardpowered/mixin/world/food/FoodDataMixin.java");

        assertTrue(living.contains("EntityDamageByEntityEvent"));
        assertTrue(living.contains("EntityToggleGlideEvent"));
        assertTrue(player.contains("startFallFlying"));
        assertTrue(pickup.contains("playerEvent.setCancelled(!playerEvent.getPlayer().getCanPickupItems())"));
        assertTrue(pickup.contains("ci.cancel();"));
        assertTrue(velocity.contains("PlayerVelocityEvent"));
        assertTrue(velocity.contains("this.entity.hurtMarked = false"));
        assertTrue(projectile.contains("ProjectileLaunchEvent"));
        assertTrue(projectile.contains("cir.setReturnValue(false)"));
        assertTrue(vehicle.contains("VehicleEnterEvent"));
        assertTrue(vehicle.contains("VehicleExitEvent"));
        assertTrue(food.contains("FoodLevelChangeEvent"));
    }
}
