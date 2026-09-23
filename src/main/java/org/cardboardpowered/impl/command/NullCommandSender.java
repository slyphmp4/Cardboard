package org.cardboardpowered.impl.command;

import java.lang.reflect.Proxy;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;

/** A permission-free sender for vanilla's synthetic command-tree inspection source. */
public final class NullCommandSender {
    private NullCommandSender() {}

    public static final CommandSender INSTANCE = (CommandSender) Proxy.newProxyInstance(
            CommandSender.class.getClassLoader(),
            new Class<?>[] {CommandSender.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission", "isPermissionSet", "isOp" -> false;
                case "getEffectivePermissions" -> Set.of();
                case "getName" -> "CommandSource.NULL";
                case "getServer" -> CraftServer.INSTANCE;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "CommandSource.NULL sender";
                default -> {
                    // This source is never a real command executor. In particular,
                    // ignore messages and permission mutations made by plugins.
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) yield false;
                    if (type == int.class) yield 0;
                    if (type == long.class) yield 0L;
                    if (type == float.class) yield 0F;
                    if (type == double.class) yield 0D;
                    yield null;
                }
            }
    );
}
