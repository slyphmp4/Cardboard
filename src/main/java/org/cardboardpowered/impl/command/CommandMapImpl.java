package org.cardboardpowered.impl.command;

import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.SimpleCommandMap;
//<<<<<<< HEAD
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
//=======
import org.bukkit.command.defaults.PluginsCommand;
///>>>>>>> upstream/ver/1.20
import org.cardboardpowered.CardboardConfig;

public class CommandMapImpl extends SimpleCommandMap {
	private static final Class<?> CARDBOARD_VERSION_COMMAND = VersionCommand.class;
    private final Server cardboard$server;
    private final Map<String, AliasOverlay> cardboard$aliases = new java.util.LinkedHashMap<>();
    private record AliasOverlay(Command installed, Command displaced) { }

	public CommandMapImpl(Server server) {
		// A fresh table per map: a shared static one kept the first singleplayer world's plugin
		// commands, so the same commands from a reloaded plugin lost the label to disabled ones.
		super(server, new HashMap<String, Command>());
        this.cardboard$server = server;
		
		
		registerCardboardCommands();
	}

//<<<<<<< HEAD
	@Override
	public boolean register(@NotNull String label, @NotNull String fallbackPrefix, @NotNull Command command) {
		if(label.equals("version") && fallbackPrefix.equals("bukkit")
				&& !CARDBOARD_VERSION_COMMAND.isInstance(command)) {
			// Let Cardboard version command take priority
			return false;
		}
//=======
        // Register our commands
        /*for (String s : new String[] {"version", "ver", "about"})
            register("bukkit", new VersionCommand(s));
        for (String s : new String[] {"fabricmods"})
            register("cardboard", new ModsCommand(s));*/
        
        //setDefaultCommands();	
		return super.register(label, fallbackPrefix, command);
	}
//    }
    
    private void setDefaultCommands() {
        this.register("bukkit", new VersionCommand("version"));
        this.register("bukkit", new PluginsCommand("plugins"));
    }
//>>>>>>> upstream/ver/1.20



	@Override
	public synchronized void clearCommands() {
		cardboard$clearServerAliases();
		super.clearCommands();
		registerCardboardCommands();
	}

    public synchronized void cardboard$clearServerAliases() {
        this.cardboard$aliases.forEach((label, overlay) -> {
            if (knownCommands.get(label) == overlay.installed()) {
                if (overlay.displaced() == null) knownCommands.remove(label);
                else knownCommands.put(label, overlay.displaced());
            }
        });
        this.cardboard$aliases.clear();
    }

    @Override public synchronized void registerServerAliases() {
        this.cardboard$clearServerAliases();
        Map<String, Command> before = new HashMap<>(knownCommands);
        super.registerServerAliases();
        for (String key : this.cardboard$server.getCommandAliases().keySet()) {
            String label = key.toLowerCase(java.util.Locale.ROOT);
            Command installed = knownCommands.get(label), displaced = before.get(label);
            if (installed != displaced && (installed == null || installed instanceof org.bukkit.command.FormattedCommandAlias)) {
                this.cardboard$aliases.put(label, new AliasOverlay(installed, displaced));
            }
        }
    }

	@Override
	public Map<String, Command> getKnownCommands() {
		return knownCommands;
	}

	private void registerCardboardCommands() {
		register("bukkit", new VersionCommand("version"));
		this.register("bukkit", new PluginsCommand("plugins"));

		if (CardboardConfig.addModsCommand) {
			register("cardboard", new ModsCommand("fabricmods"));
		}

		register("cardboard", new MyCommand());
        register("spigot", new org.spigotmc.RestartCommand("restart"));
	}

}
