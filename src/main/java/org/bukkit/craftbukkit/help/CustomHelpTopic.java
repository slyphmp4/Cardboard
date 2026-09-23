package org.bukkit.craftbukkit.help;

import org.cardboardpowered.impl.util.HelpYamlReader;

/** The CraftBukkit help topic used by plugins that register Brigadier commands. */
public class CustomHelpTopic extends HelpYamlReader.CustomHelpTopic {

    public CustomHelpTopic(String name, String shortText, String fullText, String permissionNode) {
        super(name, shortText, fullText, permissionNode);
    }
}
