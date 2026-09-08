package org.cardboardpowered;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.entity.EntityType;

import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftEntityTypes;
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.entity.CraftProjectile;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.potion.PotionType;

import org.cardboardpowered.bridge.bukkit.entity.BukkitEntityTypeBridge;

import io.izzel.arclight.api.EnumHelper;

/**
 register_entities * Registry API Util
 *
 * @since 1.21.4
 */
public class RegistryUtil {

    private static final Map<String, EntityType> CUSTOM_ENTITY_NAME_MAP = new HashMap<String, EntityType>();
    private static final Map<Short, EntityType> CUSTOM_ENTITY_ID_MAP = new HashMap<Short, EntityType>();

    public static Map<net.minecraft.world.entity.EntityType<?>, EntityType> MODDED_ENTITIES_MAP = new ConcurrentHashMap<>();
    private static final Map<Identifier, EntityType> MODDED_ENTITIES_BY_KEY = new ConcurrentHashMap<>();

    /**
     * Inject Minecraft builtin registry entries into Bukkit API.
     *
     * @see {@link org.bukkit.craftbukkit.CraftRegistry}
     * @see {@link io.papermc.paper.registry.PaperRegistries}
     */
    public static void inject_into_bukkit_registry(DedicatedServer server) {
        register_potions();
        register_entities();
    }

    public static EntityType getCraftTypeFromMinecraft(net.minecraft.world.entity.EntityType<?> mc) {
        EntityType bukkit = MODDED_ENTITIES_MAP.get(mc);
        if (bukkit != null) {
            return bukkit;
        }

        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mc);
        return id == null ? null : MODDED_ENTITIES_BY_KEY.get(id);
    }

    public static EntityType getCraftTypeFromMinecraft(net.minecraft.world.entity.EntityType<?> mc, Identifier id) {
        EntityType bukkit = MODDED_ENTITIES_MAP.get(mc);
        return bukkit != null ? bukkit : MODDED_ENTITIES_BY_KEY.get(id);
    }

    static void cacheModdedEntityType(net.minecraft.world.entity.EntityType<?> minecraftType, Identifier id, EntityType bukkitType) {
        MODDED_ENTITIES_MAP.put(minecraftType, bukkitType);
        MODDED_ENTITIES_BY_KEY.put(id, bukkitType);
    }

    private static void register_entities() {
        DefaultedRegistry<net.minecraft.world.entity.EntityType<?>> registry = BuiltInRegistries.ENTITY_TYPE;

        for (net.minecraft.world.entity.EntityType<?> entity : registry) {
            Identifier id = registry.getKey(entity);
            NamespacedKey key = CraftNamespacedKey.fromMinecraft(id);
            String entityType = normalizeName(id.toString());
            EntityType bukkitType = org.bukkit.Registry.ENTITY_TYPE.get(key);

            if (bukkitType == null) {
                int typeId = entityType.hashCode();

                bukkitType = EnumHelper.addEnum(
                        EntityType.class,
                        entityType,
                        List.of(String.class, Class.class, Integer.TYPE, Boolean.TYPE),
                        List.of(entityType.toLowerCase(), Entity.class, typeId, false)
                );

                BukkitEntityTypeBridge cb = (BukkitEntityTypeBridge) (Object) bukkitType;

                cb.cardboard$setKey(key);
                cb.cardboard$addToMaps(entityType.toLowerCase(), (short) typeId);
            }

            // Vanilla entity types already have a converter in CraftEntityTypes. Modded
            // types do not, which previously made CraftEntity#getEntity throw an
            // AssertionError as soon as such an entity began Bukkit tracking.
            if (CraftEntityTypes.getEntityTypeData(bukkitType) == null) {
                cacheModdedEntityType(entity, id, bukkitType);
                registerModdedCraftEntityType(bukkitType);
                CardboardMod.LOGGER.info("Registered modded \"" + id + "\" as CraftEntity " + bukkitType);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerModdedCraftEntityType(EntityType bukkitType) {
        try {
            Field field = CraftEntityTypes.class.getDeclaredField("ENTITY_TYPE_DATA");
            field.setAccessible(true);

            Map<EntityType, CraftEntityTypes.EntityTypeData<?, ?>> entityTypeData =
                    (Map<EntityType, CraftEntityTypes.EntityTypeData<?, ?>>) field.get(null);

            CraftEntityTypes.EntityTypeData<Entity, net.minecraft.world.entity.Entity> fallback =
                    new CraftEntityTypes.EntityTypeData<>(
                            bukkitType,
                            Entity.class,
                            RegistryUtil::wrapModdedEntity,
                            null
                    );

            entityTypeData.putIfAbsent(bukkitType, fallback);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to register Bukkit wrapper for modded entity type " + bukkitType, e);
        }
    }

    private static Entity wrapModdedEntity(CraftServer server, net.minecraft.world.entity.Entity entity) {
        // Preserve the most useful Bukkit base contracts when they can be inferred
        // safely from the NMS hierarchy. Farmer's Delight RottenTomatoEntity, for
        // example, extends Projectile and therefore receives a Projectile wrapper.
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            return new CraftProjectile(server, projectile) {
            };
        }

        if (entity instanceof net.minecraft.world.entity.LivingEntity livingEntity) {
            return new CraftLivingEntity(server, livingEntity);
        }

        return new CraftEntity(entity) {
        };
    }

    public static String normalizeName(String name) {
        return name.replace(':', '_')
                .replaceAll("\\s+", "_")
                .replaceAll("\\W", "")
                .toUpperCase(Locale.ENGLISH);
    }

    private static void register_potions() {
        List<PotionType> newTypes = new ArrayList<>();
        for (var potion : BuiltInRegistries.POTION) {
            Identifier location = BuiltInRegistries.POTION.getKey(potion);
            String name = normalizeName(location.toString());
            try {
                PotionType.valueOf(name);
                CardboardMod.LOGGER.info("FOUND POT for " + name);
            } catch (Exception e) {
                NamespacedKey namespacedKey = CraftNamespacedKey.fromMinecraft(location);

                PotionType potionType = EnumHelper.addEnum(
                        PotionType.class,
                        name,
                        List.of(String.class),
                        List.of(namespacedKey.getKey())
                );
                newTypes.add(potionType);
                if (CardboardConfig.DEBUG_VERBOSE_CALLS) {
                    CardboardMod.LOGGER.info("Registered " + location + " as potion type " + potionType);
                }
            }
        }
    }
}
