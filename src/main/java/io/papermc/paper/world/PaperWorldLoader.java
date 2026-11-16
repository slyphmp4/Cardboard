package io.papermc.paper.world;

import io.papermc.paper.world.migration.WorldFolderMigration;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Main;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.cardboardpowered.bridge.server.MinecraftServerBridge;
import org.cardboardpowered.bridge.world.level.storage.PrimaryLevelDataBridge;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import com.mojang.serialization.DataResult;

import static java.util.Objects.requireNonNull;

@NullMarked
public record PaperWorldLoader(MinecraftServer server, String levelId) {
    public static PaperWorldLoader create(final MinecraftServer server, final String levelId) {
        return new PaperWorldLoader(server, levelId);
    }

    public record WorldLoadingInfo(
        World.Environment environment,
        ResourceKey<LevelStem> stemKey,
        ResourceKey<Level> dimensionKey,
        boolean enabled
    ) {}

    public record LoadedWorldData(
        String bukkitName,
        UUID uuid,
        @Nullable PaperWorldPDC pdc,
        PaperLevelOverrides levelOverrides
    ) {}

    public record WorldLoadingInfoAndData(WorldLoadingInfo info, LoadedWorldData data) {}

    private @Nullable WorldLoadingInfoAndData getWorldInfoAndData(final LevelStem stem) {
        final WorldLoadingInfo info = this.getWorldInfo(stem);
        if (!info.enabled()) {
            return null;
        }

        final String defaultName = defaultWorldName(this.levelId, info.stemKey());

        try {
            WorldFolderMigration.migrateStartupWorld(this.server.storageSource, this.server.registryAccess(), defaultName, info.stemKey(), info.dimensionKey());
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to migrate world storage for " + defaultName, ex);
        }

        final LoadedWorldData loadedWorldData = loadWorldData(
            this.server,
            info.dimensionKey(),
            defaultName
        );

        return new WorldLoadingInfoAndData(info, loadedWorldData);
    }

    public static ResourceKey<Level> dimensionKey(final ResourceKey<LevelStem> stemKey) {
        return ResourceKey.create(Registries.DIMENSION, stemKey.identifier());
    }

    public static ResourceKey<Level> dimensionKey(final NamespacedKey key) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(key.namespace(), key.value()));
    }

    private WorldLoadingInfo getWorldInfo(final LevelStem stem) {
        final ResourceKey<LevelStem> stemKey = this.server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM).getResourceKey(stem).orElseThrow();
        final ResourceKey<Level> dimensionKey = dimensionKey(stemKey);
        boolean enabled = true;
        final World.Environment environment;
        if (stemKey == LevelStem.NETHER) {
            environment = World.Environment.NETHER;
            enabled = CraftServer.INSTANCE.getAllowNether();
        } else if (stemKey == LevelStem.END) {
            environment = World.Environment.THE_END;
            enabled = CraftServer.INSTANCE.getAllowEnd();
        } else if (stemKey == LevelStem.OVERWORLD) {
            environment = World.Environment.NORMAL;
        } else {
            environment = World.Environment.CUSTOM;
        }

        return new WorldLoadingInfo(environment, stemKey, dimensionKey, enabled);
    }

    public static LoadedWorldData loadWorldData(
        final MinecraftServer server,
        final ResourceKey<Level> dimension,
        final String defaultName
    ) {
        final var storageSource = server.storageSource;
        final var registryAccess = server.registryAccess();

        final SavedDataStorage tempStorage = new SavedDataStorage(storageSource.getDimensionPath(dimension).resolve(LevelResource.DATA.id()), DataFixers.getDataFixer(), registryAccess);
        final PaperWorldMetadata metadata = tempStorage.get(PaperWorldMetadata.TYPE);
        final PaperWorldPDC pdc = tempStorage.get(PaperWorldPDC.TYPE);
        final PaperLevelOverrides levelOverrides = tempStorage.get(PaperLevelOverrides.TYPE);

        final LoadedWorldData data = new LoadedWorldData(
            defaultName,
            metadata == null ? UUID.randomUUID() : metadata.uuid(),
            pdc,
            levelOverrides == null ? PaperLevelOverrides.createFromLiveLevelData((PrimaryLevelData) server.getWorldData()) : levelOverrides
        );

        data.levelOverrides().attach((PrimaryLevelData) server.getWorldData(), dimension);

        return data;
    }

    public void loadInitialWorlds() {
        final var levelStemRegistry = this.server.registryAccess().lookupOrThrow(Registries.LEVEL_STEM);
        final boolean hasWorldData = this.server.storageSource.hasWorldData();
        final LevelStem overworldStem = requireNonNull(levelStemRegistry.getValue(LevelStem.OVERWORLD), "Overworld stem missing");
        this.loadInitialWorld(overworldStem, hasWorldData);
        for (final LevelStem stem : levelStemRegistry) {
            if (stem == overworldStem) {
                continue;
            }
            this.loadInitialWorld(stem, hasWorldData);
        }

        // ((DedicatedServer) this.server).forceDifficulty();

        for (ServerLevel serverLevel : this.server.getAllLevels()) {
            // this.server.prepareLevel(serverLevel);
        	this.server.cardboard$prepareLevel(serverLevel);
        }
    }

    private void loadInitialWorld(final LevelStem stem, final boolean hasWorldData) {
        final WorldLoadingInfoAndData loading = this.getWorldInfoAndData(stem);
        if (loading == null) {
            return;
        }

        final WorldGenSettings worldGenSettings = !hasWorldData
            ? this.server.getWorldGenSettings()
            : loadWorldGenSettings(
            this.server.storageSource,
            ((MinecraftServerBridge)this.server).cardboard$worldLoaderContext().datapackWorldRegistries(),
            loading.info().dimensionKey()
        );
        final var worldDataAndGenSettings = new LevelDataAndDimensions.WorldDataAndGenSettings(this.server.getWorldData(), worldGenSettings);

        if (loading.info().dimensionKey() == Level.OVERWORLD) {
            final var primaryLevelData = ((PrimaryLevelData) this.server.getWorldData());
            ((PrimaryLevelDataBridge)primaryLevelData).checkName(loading.data().bukkitName());
            primaryLevelData.setModdedInfo(this.server.getServerModName(), this.server.getModdedStatus().shouldReportAsModified());
        }

        // TODO: add forceUpgrade
        /*
        if (this.server.options.has("forceUpgrade")) {
            Main.forceUpgrade(this.server.storageSource, DataFixers.getDataFixer(), this.server.options.has("eraseCache"), () -> true, this.server.registryAccess(), this.server.options.has("recreateRegionFiles"));
        }
        */

        this.server.createLevel(stem, loading, worldDataAndGenSettings);
    }

    public static WorldGenSettings loadWorldGenSettings(
        final LevelStorageSource.LevelStorageAccess access, final net.minecraft.core.HolderLookup.Provider registryAccess, final ResourceKey<Level> dimension
    ) {
        return LevelStorageSource_readExistingSavedData(access, dimension, registryAccess, WorldGenSettings.TYPE)
            .getOrThrow(err -> new IllegalStateException("Unable to read or access the world gen settings file for dimension " + dimension.identifier() + ". " + err));
    }
    
    // Extract from LevelStorageSource
    public static <T extends SavedData> DataResult<T> LevelStorageSource_readExistingSavedData(
			final LevelStorageSource.LevelStorageAccess access,
			final ResourceKey<Level> dimension, // Paper - add final ResourceKey<Level> dimension
			final Provider registryAccess,
			final SavedDataType<T> savedDataType) {
		
    	// Path dataLocation = savedDataType.id().withSuffix(".dat").resolveAgainst(access.getLevelPath(LevelResource.DATA));
		// Paper - use dimension scoped data (we 'demote' some data from global and add our own dimension scoped data)
		Path dataLocation = savedDataType.id().withSuffix(".dat").resolveAgainst(access.getDimensionPath(dimension).resolve(LevelResource.DATA.id()));

		CompoundTag fileContents;
		try {
			fileContents = NbtIo.readCompressed(dataLocation, NbtAccounter.create(NbtAccounter.DEFAULT_NBT_QUOTA * 50L)); // Paper - Old: NbtAccounter.defaultQuota()
		} catch (IOException var6) {
			return DataResult.error(var6::getMessage);
		}

		return savedDataType.codec().parse(RegistryOps.create(NbtOps.INSTANCE, registryAccess),
				fileContents.getCompoundOrEmpty("data"));
	}

    private static String defaultWorldName(final String levelId, final ResourceKey<LevelStem> stemKey) {
        if (stemKey == LevelStem.OVERWORLD) {
            return levelId;
        }
        return levelId + "_" + worldType(stemKey);
    }

    private static String worldType(final ResourceKey<LevelStem> stemKey) {
        if (stemKey == LevelStem.NETHER) {
            return World.Environment.NETHER.toString().toLowerCase(Locale.ROOT);
        }
        if (stemKey == LevelStem.END) {
            return World.Environment.THE_END.toString().toLowerCase(Locale.ROOT);
        }
        if (stemKey == LevelStem.OVERWORLD) {
            return World.Environment.NORMAL.toString().toLowerCase(Locale.ROOT);
        }
        return stemKey.identifier().getNamespace() + "_" + stemKey.identifier().getPath();
    }

}
