package io.papermc.paper.datapack;

import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.world.flag.PaperFeatureFlagProviderImpl;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.bukkit.FeatureFlag;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class PaperDiscoveredDatapack implements DiscoveredDatapack {

    private final Pack pack;

    PaperDiscoveredDatapack(final Pack pack) {
        this.pack = pack;
    }

    @Override
    public String getName() {
        return this.pack.getId();
    }

    @Override
    public Component getTitle() {
        return PaperAdventure.asAdventure(this.pack.getTitle());
    }

    @Override
    public Component getDescription() {
        return PaperAdventure.asAdventure(this.pack.getDescription());
    }

    @Override
    public boolean isRequired() {
        return this.pack.isRequired();
    }

    @Override
    public Datapack.Compatibility getCompatibility() {
        return Datapack.Compatibility.valueOf(this.pack.getCompatibility().name());
    }

    @Override
    public Set<FeatureFlag> getRequiredFeatures() {
        return PaperFeatureFlagProviderImpl.fromNms(this.pack.getRequestedFeatures());
    }

    @Override
    public DatapackSource getSource() {
        PackSource source = this.pack.location().source();
        if (source == PackSource.DEFAULT) return DatapackSource.DEFAULT;
        if (source == PackSource.BUILT_IN) return DatapackSource.BUILT_IN;
        if (source == PackSource.FEATURE) return DatapackSource.FEATURE;
        if (source == PackSource.WORLD) return DatapackSource.WORLD;
        if (source == PackSource.SERVER) return DatapackSource.SERVER;
        return DatapackSource.PLUGIN;
    }
}
