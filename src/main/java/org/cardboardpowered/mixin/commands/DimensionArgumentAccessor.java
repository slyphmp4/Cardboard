package org.cardboardpowered.mixin.commands;

import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.arguments.DimensionArgument;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DimensionArgument.class)
public interface DimensionArgumentAccessor {
    @Accessor("ERROR_INVALID_VALUE")
    static DynamicCommandExceptionType cardboard$invalidValue() { throw new AssertionError(); }
}
