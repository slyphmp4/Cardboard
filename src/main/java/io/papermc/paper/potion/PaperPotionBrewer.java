package io.papermc.paper.potion;

import com.google.common.base.Preconditions;
import java.util.Collection;
import net.minecraft.server.MinecraftServer;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionBrewer;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.cardboardpowered.bridge.world.item.alchemy.PotionBrewingBridge;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

@DefaultQualifier(NonNull.class)
public class PaperPotionBrewer implements PotionBrewer {

    private final MinecraftServer minecraftServer;

    public PaperPotionBrewer(final MinecraftServer minecraftServer) {
        this.minecraftServer = minecraftServer;
    }

    @Override
    @Deprecated(forRemoval = true)
    public Collection<PotionEffect> getEffects(PotionType type, boolean upgraded, boolean extended) {
        final NamespacedKey key = type.getKey();

        Preconditions.checkArgument(!key.getKey().startsWith("strong_"), "Strong potion type cannot be used directly, got %s", key);
        Preconditions.checkArgument(!key.getKey().startsWith("long_"), "Extended potion type cannot be used directly, got %s", key);

        NamespacedKey effectiveKey = key;
        if (upgraded) {
            effectiveKey = new NamespacedKey(key.namespace(), "strong_" + key.key());
        } else if (extended) {
            effectiveKey = new NamespacedKey(key.namespace(), "long_" + key.key());
        }

        final PotionType effectivePotionType = org.bukkit.Registry.POTION.get(effectiveKey);
        Preconditions.checkNotNull(effectivePotionType, "Unknown potion type from data %s", effectiveKey.asMinimalString());
        return effectivePotionType.getPotionEffects();
    }

    @Override
    public void addPotionMix(final PotionMix potionMix) {
        ((PotionBrewingBridge) (Object) this.minecraftServer.potionBrewing()).cardboard$addPotionMix(potionMix);
    }

    @Override
    public void removePotionMix(final NamespacedKey key) {
        ((PotionBrewingBridge) (Object) this.minecraftServer.potionBrewing()).cardboard$removePotionMix(key);
    }

    @Override
    public void resetPotionMixes() {
        ((PotionBrewingBridge) (Object) this.minecraftServer.potionBrewing()).cardboard$clearPotionMixes();
    }
}
