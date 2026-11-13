package org.cardboardpowered.mixin.world.level.block.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignText.Mutable.class)
public abstract class SignTextMutableMixin {
    @Shadow @Final private Component[] filteredMessages;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cardboard$copyFilteredMessages(SignText source, CallbackInfo ci) {
        // 26.3's constructor copies the raw messages into both arrays. Keep the
        // filtered variants when vanilla or a Bukkit sign snapshot copies text.
        var filtered = source.getMessages(true);
        for (int line = 0; line < this.filteredMessages.length; line++) {
            this.filteredMessages[line] = filtered.get(line);
        }
    }
}
