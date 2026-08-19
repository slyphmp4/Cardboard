package org.cardboardpowered.impl.command;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.rcon.RconConsoleSource;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;

/**
 * Bukkit RemoteConsoleCommandSender backed by Minecraft's
 * active RconConsoleSource.
 *
 * Unlike the normal server console sender, messages written here
 * are appended to the active RCON response buffer.
 */
public final class CardboardRemoteConsoleCommandSender
        implements RemoteConsoleCommandSender {

    private final RconConsoleSource source;

    private final SocketAddress address =
            new InetSocketAddress("0.0.0.0", 0);

    private PermissibleBase perm;

    public CardboardRemoteConsoleCommandSender(RconConsoleSource source) {
        this.source = source;
    }

    public RconConsoleSource getListener() {
        return this.source;
    }

    private PermissibleBase permissions() {
        if (this.perm == null) {
            this.perm = new PermissibleBase(this);
        }

        return this.perm;
    }

    @Override
    public @NotNull SocketAddress getAddress() {
        return this.address;
    }

    @Override
    public @NotNull String getName() {
        return "Rcon";
    }

    @Override
    public @NotNull Component name() {
        return Component.text("Rcon");
    }

    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public void sendMessage(@NotNull String message) {
        this.source.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(message + "\n")
        );
    }

    @Override
    public void sendMessage(@NotNull String... messages) {
        for (String message : messages) {
            this.sendMessage(message);
        }
    }

    @Override
    public void sendMessage(UUID sender, @NotNull String message) {
        this.sendMessage(message);
    }

    @Override
    public void sendMessage(UUID sender, @NotNull String... messages) {
        this.sendMessage(messages);
    }

    public void sendRawMessage(String message) {
        this.sendMessage(message);
    }

    public void sendRawMessage(UUID sender, String message) {
        this.sendMessage(message);
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public void setOp(boolean value) {
        throw new UnsupportedOperationException(
                "Cannot change operator status of remote controller."
        );
    }

    @Override
    public boolean isPermissionSet(String name) {
        return this.permissions().isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission permission) {
        return this.permissions().isPermissionSet(permission);
    }

    @Override
    public boolean hasPermission(String name) {
        return this.permissions().hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission permission) {
        return this.permissions().hasPermission(permission);
    }

    @Override
    public PermissionAttachment addAttachment(
            Plugin plugin,
            String name,
            boolean value
    ) {
        return this.permissions().addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(
            Plugin plugin,
            String name,
            boolean value,
            int ticks
    ) {
        return this.permissions().addAttachment(
                plugin,
                name,
                value,
                ticks
        );
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return this.permissions().addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(
            Plugin plugin,
            int ticks
    ) {
        return this.permissions().addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        this.permissions().removeAttachment(attachment);
    }

    @Override
    public void recalculatePermissions() {
        this.permissions().recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return this.permissions().getEffectivePermissions();
    }

    private final CommandSender.Spigot spigot =
            new CommandSender.Spigot() {

        @Override
        public void sendMessage(BaseComponent component) {
            CardboardRemoteConsoleCommandSender.this.sendMessage(
                    TextComponent.toLegacyText(component)
            );
        }

        @Override
        public void sendMessage(BaseComponent... components) {
            CardboardRemoteConsoleCommandSender.this.sendMessage(
                    TextComponent.toLegacyText(components)
            );
        }

        @Override
        public void sendMessage(
                UUID sender,
                BaseComponent... components
        ) {
            this.sendMessage(components);
        }

        @Override
        public void sendMessage(
                UUID sender,
                BaseComponent component
        ) {
            this.sendMessage(component);
        }
    };

    @Override
    public CommandSender.Spigot spigot() {
        return this.spigot;
    }
}