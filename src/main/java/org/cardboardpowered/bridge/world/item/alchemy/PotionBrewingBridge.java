package org.cardboardpowered.bridge.world.item.alchemy;

import io.papermc.paper.potion.PotionMix;
import org.bukkit.NamespacedKey;

public interface PotionBrewingBridge {

    void cardboard$addPotionMix(PotionMix mix);

    boolean cardboard$removePotionMix(NamespacedKey key);

    void cardboard$clearPotionMixes();
}
