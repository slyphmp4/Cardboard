package org.cardboardpowered.impl.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Converts plugin Brigadier arguments into types the vanilla client can decode. */
public final class ClientCommandArgumentSerialization {
    private static final Logger LOGGER = LogManager.getLogger("Cardboard|Commands");
    private static final Set<Class<?>> REPORTED = ConcurrentHashMap.newKeySet();

    private ClientCommandArgumentSerialization() {}

    /** Resolve only declared client types; parsing stays on the original API argument. */
    public static ArgumentType<?> nativeType(ArgumentType<?> type) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ArgumentType<?>, Boolean>());
        for (int depth = 0; depth < 64; depth++) {
            if (type == null || !seen.add(type)) throw new IllegalArgumentException("Cyclic or null native argument type");
            if (type instanceof io.papermc.paper.command.brigadier.argument.VanillaArgumentProviderImpl.NativeWrapperArgumentType<?, ?> wrapper) {
                type = wrapper.nativeNmsArgumentType();
            } else if (type instanceof io.papermc.paper.command.brigadier.argument.CustomArgumentType<?, ?> custom) {
                type = custom.getNativeType();
            } else return type;
        }
        throw new IllegalArgumentException("Native argument type nesting exceeds 64 wrappers");
    }

    public static ArgumentTypeInfo.Template<?> forClient(ArgumentType<?> type) {
        type = nativeType(type);
        try {
            return ArgumentTypeInfos.unpack(type);
        } catch (IllegalArgumentException exception) {
            if (!exception.getMessage().startsWith("Unrecognized argument type ")) {
                throw exception;
            }
            // The server continues to parse the original plugin argument.
            // Only the client-side command tree gets a vanilla string type.
            if (REPORTED.add(type.getClass())) {
                LOGGER.warn("Plugin argument {} has no client serializer; sending it as a string", type.getClass().getName());
            }
            return ArgumentTypeInfos.unpack(StringArgumentType.string());
        }
    }
}

