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

    public static ArgumentTypeInfo.Template<?> forClient(ArgumentType<?> type) {
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
