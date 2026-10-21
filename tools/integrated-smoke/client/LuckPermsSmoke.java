package org.cardboardpowered.smoke;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/** Real Fabric LuckPerms user data -> Fabric API -> Bukkit permission fallback. */
final class LuckPermsSmoke {
    static void verify(Player player) {
        var user = LuckPermsProvider.get().getUserManager().getUser(player.getUniqueId());
        require(user != null, "LuckPerms did not load user " + player.getUniqueId());
        String grant = "cardboard.smoke.luckperms";
        String denied = "cardboard.smoke.luckperms.denied";
        var plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
        var attachment = player.addAttachment(plugin);
        attachment.setPermission(grant, false);
        attachment.setPermission(denied, false);
        var allow = Node.builder(grant).value(true).build();
        var deny = Node.builder(denied).value(false).build();
        user.data().remove(Node.builder(grant).value(false).build());
        user.data().add(allow);
        user.data().add(deny);
        user.getCachedData().invalidate();
        var entity = ((CraftPlayer) player).getHandle();
        require(me.lucko.fabric.api.permissions.v0.Permissions.check(entity, grant), "LuckPerms Fabric grant was lost");
        require(player.hasPermission(grant), "Bukkit did not see LuckPerms grant");
        require(!me.lucko.fabric.api.permissions.v0.Permissions.check(entity, denied), "LuckPerms Fabric denial was lost");
        require(!player.hasPermission(denied), "Bukkit ignored LuckPerms denial");
        user.data().remove(allow);
        user.data().add(Node.builder(grant).value(false).build());
        user.getCachedData().invalidate();
        require(!me.lucko.fabric.api.permissions.v0.Permissions.check(entity, grant), "LuckPerms grant was not revoked");
        require(!player.hasPermission(grant), "Bukkit retained revoked LuckPerms grant");
        System.out.println("LUCKPERMS_FABRIC_PASS " + player.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
