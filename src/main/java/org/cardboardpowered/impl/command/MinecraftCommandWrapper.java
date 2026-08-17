package org.cardboardpowered.impl.command;

import com.google.common.base.Joiner;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MinecraftCommandWrapper extends BukkitCommand {

    private final Commands dispatcher;
    public final CommandNode<?> vanillaCommand;

    public MinecraftCommandWrapper(
            Commands dispatcher,
            CommandNode<?> vanillaCommand
    ) {
        super(
                vanillaCommand.getName(),
                "A Minecraft provided command",
                vanillaCommand.getUsageText(),
                Collections.emptyList()
        );

        this.dispatcher = dispatcher;
        this.vanillaCommand = vanillaCommand;

        this.setPermission(getPermission(vanillaCommand));
    }

    @Override
    public boolean execute(
            CommandSender sender,
            String commandLabel,
            String[] args
    ) {
        if (!this.testPermission(sender)) {
            return true;
        }

        CommandSourceStack source =
                MinecraftCommandWrapper.getCommandSource(sender);

        if (source == null) {
            return true;
        }

        this.dispatcher.performPrefixedCommand(
                source,
                this.toDispatcher(args, this.getName())
        );

        return true;
    }

    @Override
    public List<String> tabComplete(
            CommandSender sender,
            String alias,
            String[] args,
            Location location
    ) throws IllegalArgumentException {

        CommandSourceStack source =
                MinecraftCommandWrapper.getCommandSource(sender);

        if (source == null) {
            return Collections.emptyList();
        }

        ParseResults<CommandSourceStack> parsed =
                this.dispatcher
                        .getDispatcher()
                        .parse(
                                this.toDispatcher(args, this.getName()),
                                source
                        );

        List<String> results = new ArrayList<>();

        this.dispatcher
                .getDispatcher()
                .getCompletionSuggestions(parsed)
                .thenAccept(
                        suggestions ->
                                suggestions.getList().forEach(
                                        suggestion ->
                                                results.add(suggestion.getText())
                                )
                );

        return results;
    }

    public static String getPermission(
            CommandNode<?> vanillaCommand
    ) {
        while (vanillaCommand.getRedirect() != null) {
            vanillaCommand = vanillaCommand.getRedirect();
        }

        String commandName = vanillaCommand.getName();

        return "minecraft.command."
                + stripDefaultNamespace(commandName);
    }

    private static String stripDefaultNamespace(
            String commandName
    ) {
        final String minecraftPrefix = "minecraft:";

        if (commandName.startsWith(minecraftPrefix)) {
            return commandName.substring(
                    minecraftPrefix.length()
            );
        }

        return commandName;
    }

    private String toDispatcher(
            String[] args,
            String name
    ) {
        return name
                + (
                args.length > 0
                        ? " " + Joiner.on(' ').join(args)
                        : ""
        );
    }

    public static CommandSourceStack getCommandSource(
            CommandSender sender
    ) {
        if (sender instanceof CraftPlayer player) {
            return player
                    .getHandle()
                    .createCommandSourceStack();
        }

        if (sender instanceof CraftEntity entity) {
            return entity
                    .getHandle()
                    .createCommandSourceStackForNameResolution(
                            (ServerLevel) entity
                                    .getHandle()
                                    .level()
                    );
        }

        if (sender instanceof ConsoleCommandSender) {
            return ((CraftServer) sender.getServer())
                    .getServer()
                    .createCommandSourceStack();
        }

        return null;
    }
}