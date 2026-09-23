package org.cardboardpowered.ci;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.StringArgument;
import dev.jorel.commandapi.executors.CommandExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** Checks real CommandAPI command registration and execution on the server. */
public final class CommandAPIProbe extends JavaPlugin {
    @Override
    public void onEnable() {
        new CommandAPICommand("cardboardcommandapitest")
            .withArguments(new StringArgument("value"))
            .executes((CommandExecutor) (sender, args) -> sender.sendMessage("COMMANDAPI_PROBE_OK:" + args.get("value")))
            .register();
    }
}
