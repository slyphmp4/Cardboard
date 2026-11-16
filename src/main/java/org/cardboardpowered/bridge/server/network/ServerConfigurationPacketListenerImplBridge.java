package org.cardboardpowered.bridge.server.network;

import io.papermc.paper.connection.PaperPlayerConfigurationConnection;
import net.minecraft.server.level.ClientInformation;

public interface ServerConfigurationPacketListenerImplBridge {
    PaperPlayerConfigurationConnection cardboard$configurationConnection();
    ClientInformation cardboard$clientInformation();
    void cardboard$beginReconfiguration();
    void cardboard$completeReconfiguration();
}
