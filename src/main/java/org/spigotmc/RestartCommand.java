package org.spigotmc;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.CraftServer;

public final class RestartCommand extends Command {
    public RestartCommand(String name) {
        super(name);
        this.description = "Restarts the server";
        this.usageMessage = "/restart";
        this.setPermission("bukkit.command.restart");
    }
    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (this.testPermission(sender)) restart();
        return true;
    }
    public static void restart() { if (Bukkit.getServer() != null) Bukkit.getServer().restart(); }
    /** Arms the current server's stop-completion hook; integrated worlds never launch scripts. */
    public static boolean addShutdownHook(String script) {
        return Bukkit.getServer() instanceof CraftServer server && server.cardboard$armRestartScript(script);
    }
}
