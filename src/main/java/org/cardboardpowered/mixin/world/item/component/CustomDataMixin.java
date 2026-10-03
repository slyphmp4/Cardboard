package org.cardboardpowered.mixin.world.item.component;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.component.CustomData;
import org.cardboardpowered.bridge.world.item.component.CustomDataBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CustomData.class)
public class CustomDataMixin implements CustomDataBridge {
    @Shadow @Final @Mutable
    public static Codec<CompoundTag> COMPOUND_TAG_CODEC;

    @Shadow @Final @Mutable
    public static Codec<CustomData> CODEC;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void cardboard$replaceSerializationCodec(CallbackInfo ci) {
        COMPOUND_TAG_CODEC = Codec.either(CompoundTag.CODEC, TagParser.FLATTENED_CODEC)
                .xmap(
                        com.mojang.datafixers.util.Either::unwrap,
                        data -> org.cardboardpowered.util.CustomDataSerialization.serializeAsSnbt()
                                ? com.mojang.datafixers.util.Either.right(data)
                                : com.mojang.datafixers.util.Either.left(data)
                );
        CODEC = COMPOUND_TAG_CODEC.xmap(CustomData::of, CustomData::copyTag);
    }

    @Shadow
    @Final
    private CompoundTag tag;

    // Paper start - expose unsafe internal compound tag for read only access
    @Deprecated
    @Override
    public CompoundTag cardboard$getUnsafe() {
        return this.tag;
    }

    @Override
    public boolean cardboard$contains(String key) {
        return this.tag.contains(key);
    }
    // Paper end - expose unsafe internal compound tag for read only access
}
