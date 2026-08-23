package org.cardboardpowered.mixin.bukkit.entity;

import io.papermc.paper.adventure.PaperAdventure;
import net.kyori.adventure.text.Component;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.conversations.ConversationTracker;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(value = CraftPlayer.class, remap = false)
public abstract class CraftPlayerAdventureMessageMixin {

    @Shadow
    @Final
    private ConversationTracker conversationTracker;

    @Shadow
    public abstract ServerPlayer getHandle();

    /**
     * Preserve Adventure hover/click metadata when plugins send a Component.
     */
    public void sendMessage(Component message) {
        if (message == null || this.conversationTracker.isConversingModaly()) {
            return;
        }

        ServerPlayer handle = this.getHandle();
        if (handle.connection == null) {
            return;
        }

        net.minecraft.network.chat.Component vanilla =
                PaperAdventure.WRAPPER_AWARE_SERIALIZER.serialize(message);
        handle.sendSystemMessage(vanilla);
    }

    public void sendMessage(Component... messages) {
        if (messages == null) {
            return;
        }

        for (Component message : messages) {
            this.sendMessage(message);
        }
    }
}
