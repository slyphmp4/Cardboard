package org.cardboardpowered.smoke.plugin;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Public Paper API only, including a blocking initial configuration and held reconfiguration. */
public final class ConfigurationDialogSmokeActions implements Listener {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private Plugin plugin;

    public void install(Plugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler public void initial(PlayerConnectionInitialConfigureEvent event) {
        UUID id = event.getConnection().getProfile().getId();
        State state = new State(id);
        try {
            State previous = this.states.put(id, state);
            if (previous != null) {
                require(!previous.connection.isConnected() && "1".equals(previous.values.get("cancelled")), "Reconnected before configuration worker was cancelled");
                state.attempts = previous.attempts + 1;
                state.values.put("aborts", Integer.toString(state.attempts - 1));
            }
            state.values.put("attempts", Integer.toString(state.attempts));
            require(Bukkit.isPrimaryThread() && !event.isAsynchronous(), "Initial configuration thread");
            state.connection = event.getConnection();
            checkConnection(state);
            state.values.put("initial", "1");
            // An early release must not skip registry synchronization or the async event.
            state.connection.completeReconfiguration();
        } catch (Throwable failure) { state.fail(failure); }
    }

    @EventHandler public void configure(AsyncPlayerConnectionConfigureEvent event) {
        State state = this.states.get(event.getConnection().getProfile().getId());
        try {
            require(state != null && state.connection == event.getConnection(), "Configuration connection identity changed");
            require(!Bukkit.isPrimaryThread() && event.isAsynchronous(), "Async configuration ran on server thread");
            require("1".equals(state.values.get("initial")), "Initial event did not run first");
            checkConnection(state);
            state.values.put("async", "1");
            var cookie = new NamespacedKey(this.plugin, "configuration");
            byte[] cookieValue = "configuration-cookie-世界".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            state.connection.storeCookie(cookie, cookieValue);
            require(java.util.Arrays.equals(cookieValue, state.connection.retrieveCookie(cookie).get(10, TimeUnit.SECONDS)),
                    "Cookie response was not routed to the configuration connection");
            state.values.put("cookie", "1");
            show(state, false);
            require(state.answer.await(45, TimeUnit.SECONDS), "Initial configuration dialog was not answered: " + state.values);
            require(state.calls == 1 && state.events == 2 && state.adventureCalls == 1, "Initial callbacks invalid: " + state.values);
            state.values.put("configured", "1");
        } catch (InterruptedException cancelled) {
            state.values.put("cancelled", "1");
            String exchange = System.getProperty("cardboard.smoke.exchange");
            if (exchange != null) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(exchange, "configuration-aborted-" + state.id), "PASS");
                } catch (java.io.IOException failure) { state.fail(failure); }
            }
            System.out.println("CONFIGURATION_CANCELLED_PASS " + state.id);
            Thread.currentThread().interrupt();
        } catch (Throwable failure) { if (state != null) state.fail(failure); else throw new AssertionError(failure); }
    }

    @EventHandler public void reconfigure(PlayerConnectionReconfigureEvent event) {
        State state = this.states.get(event.getConnection().getProfile().getId());
        try {
            require(state != null && state.reentryRequested, "Unrequested reconfiguration");
            require(Bukkit.isPrimaryThread() && !event.isAsynchronous(), "Reconfigure event thread");
            require(state.connection != event.getConnection(), "Initial wrapper reused for reconfiguration");
            state.connection.completeReconfiguration(); // stale initial connection must not release this phase
            state.connection = event.getConnection();
            checkConnection(state);
            state.values.put("reconfigure", Integer.toString(++state.reconfigurations));
            require(state.reconfigurations == 1, "Duplicate reconfiguration acknowledgement");
            // The integrated game can pause when its owner leaves play. Hold the
            // connection using an async timer, independent of that world's clock.
            Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
                try {
                    checkConnection(state);
                    state.values.put("held", "1");
                    show(state, true);
                } catch (Throwable failure) { state.fail(failure); }
            }, 200, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) { if (state != null) state.fail(failure); else throw new AssertionError(failure); }
    }

    private void checkConnection(State state) {
        var connection = state.connection;
        require(connection.isConnected(), "Configuration connection is closed");
        require(connection.getAudience() == connection, "Configuration audience must be the connection");
        require(connection.getAudience() instanceof PlayerConfigurationConnection, "Missing configuration audience type");
        require(state.id.equals(connection.getAudience().get(Identity.UUID).orElse(null)), "Configuration UUID pointer");
        require(connection.getProfile().getName().equals(connection.getAudience().get(Identity.NAME).orElse(null)), "Name pointer");
        require(connection.getAudience().get(Identity.LOCALE).isPresent(), "Locale pointer");
        require(Bukkit.getPlayer(state.id) == null, "Configuration still has an online Bukkit Player");
    }

    private void show(State state, boolean reconfigure) {
        state.events = 0; state.calls = 0;
        var action = DialogAction.customClick((response, audience) -> {
            try {
                require(Bukkit.isPrimaryThread() && audience == state.connection, "Configuration callback actor/thread");
                require(state.events == 1 && ++state.calls == 1, "Callback did not follow its event exactly once");
                require("Привет 世界".equals(response.getText("text")), "Configuration UI text response");
                require(Boolean.TRUE.equals(response.getBoolean("flag")), "Configuration UI bool response");
                require(Float.valueOf(3.5f).equals(response.getFloat("value")), "Configuration UI float response");
                state.values.put(reconfigure ? "reentryCalls" : "initialCalls", "1");
                // Initial owner configuration precedes the world's scheduler heartbeat.
                state.connection.getAudience().showDialog(state.dialog);
            } catch (Throwable failure) { state.fail(failure); state.answer.countDown(); }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build());
        state.action = action.id();
        state.dialog = form(action, reconfigure);
        state.connection.getAudience().showDialog(state.dialog);
    }

    private Dialog form(DialogAction action, boolean reconfigure) {
        return Dialog.create(factory -> factory.empty().base(DialogBase.builder(Component.text("p263-config"))
                .pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(Component.text(reconfigure ? "Reconfiguration" : "Before joining"))))
                .inputs(List.of(DialogInput.text("text", Component.text("Text")).initial("before edit").build(),
                        DialogInput.bool("flag", Component.text("Flag")).initial(true).build(),
                        DialogInput.numberRange("value", Component.text("Number"), 0, 10).initial(3.5f).build())).build())
                .type(DialogType.notice(ActionButton.builder(Component.text("p263-submit")).action(action).build())));
    }

    private void showAdventure(State state) {
        var click = ClickEvent.callback(audience -> {
            try {
                require(Bukkit.isPrimaryThread() && audience == state.connection && state.adventureEvents == 1,
                        "Adventure configuration callback actor/thread/event order");
                require(++state.adventureCalls == 1, "Adventure configuration callback replayed");
                state.values.put(state.reentryRequested ? "reentryAdventure" : "initialAdventure", "1");
                state.connection.getAudience().closeDialog();
                state.connection.clearChat();
                if (state.reentryRequested) {
                    Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
                        state.connection.completeReconfiguration();
                        state.connection.completeReconfiguration();
                    });
                } else state.answer.countDown();
            } catch (Throwable failure) { state.fail(failure); state.answer.countDown(); }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build());
        state.adventureCalls = 0; state.adventureEvents = 0;
        state.adventureAction = ((ClickEvent.Payload.Custom) click.payload()).key();
        state.connection.getAudience().showDialog(form(DialogAction.staticAction(click), state.reentryRequested));
    }

    @EventHandler public void click(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;
        State state = this.states.get(connection.getProfile().getId());
        if (state == null) return;
        if (event.getIdentifier().equals(state.adventureAction)) {
            try {
                require(Bukkit.isPrimaryThread() && connection == state.connection, "Adventure event connection/thread");
                state.adventureEvents++;
            } catch (Throwable failure) { state.fail(failure); }
            return;
        }
        if (!event.getIdentifier().equals(state.action)) return;
        try {
            require(Bukkit.isPrimaryThread() && connection == state.connection, "Custom click connection/thread");
            require("Привет 世界".equals(event.getDialogResponseView().getText("text")), "Custom event lost native UI response");
            int events = ++state.events;
            state.values.put(state.reentryRequested ? "reentryEvents" : "initialEvents", Integer.toString(events));
            if (events == 2) {
                require(state.calls == 1, "Exhausted dialog callback replayed");
                showAdventure(state);
            }
        } catch (Throwable failure) { state.fail(failure); state.answer.countDown(); }
    }

    public void beginReconfiguration(UUID id) {
        State state = this.states.get(id);
        Player player = Bukkit.getPlayer(id);
        require(state != null && "1".equals(state.values.get("configured")) && player != null, "Initial configuration did not finish");
        state.location = player.getLocation();
        state.original = player;
        player.getInventory().setItem(31, new ItemStack(Material.DIAMOND, 7));
        player.getPersistentDataContainer().set(new NamespacedKey(this.plugin, "configuration"), PersistentDataType.STRING, "Привет 世界");
        player.setLevel(17); player.setExp(0.375f);
        state.reentryRequested = true;
        player.getConnection().reenterConfiguration();
        player.getConnection().reenterConfiguration();
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        State state = this.states.get(event.getPlayer().getUniqueId());
        if (state != null && state.reentryRequested) state.values.put("quit", "1");
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        State state = this.states.get(event.getPlayer().getUniqueId());
        if (state == null || !state.reentryRequested) return;
        try {
            Player player = event.getPlayer();
            require(player != state.original && player.getUniqueId().equals(state.id), "Reconfiguration player identity");
            require("1".equals(state.values.get("quit")), "Reconfiguration did not dispatch quit before join");
            ItemStack stack = player.getInventory().getItem(31);
            require(stack != null && stack.getType() == Material.DIAMOND && stack.getAmount() == 7, "Inventory lost across reconfiguration");
            require("Привет 世界".equals(player.getPersistentDataContainer().get(new NamespacedKey(this.plugin, "configuration"), PersistentDataType.STRING)), "PDC lost");
            require(player.getLevel() == 17 && player.getExp() == 0.375f, "XP lost across reconfiguration");
            require(player.getWorld().equals(state.location.getWorld()) && player.getLocation().distanceSquared(state.location) < 0.01, "Position lost");
            require(state.calls == 1 && state.events == 2, "Reconfiguration replay/callback counts");
            state.values.put("returned", "1");
            state.connection.completeReconfiguration(); // stale reconfiguration connection is now inert
        } catch (Throwable failure) { state.fail(failure); }
    }

    public Map<String, String> snapshot(UUID id) {
        State state = this.states.get(id);
        return state == null ? Map.of() : Map.copyOf(state.values);
    }

    public int expectedLoginAttempts(UUID id) { return this.states.get(id).attempts; }

    private static final class State {
        final UUID id;
        final Map<String, String> values = new ConcurrentHashMap<>();
        final CountDownLatch answer = new CountDownLatch(1);
        volatile PlayerConfigurationConnection connection;
        volatile Dialog dialog;
        volatile Key action;
        volatile Key adventureAction;
        volatile int events, calls;
        volatile int adventureEvents, adventureCalls;
        int reconfigurations;
        int attempts = 1;
        volatile boolean reentryRequested;
        Player original;
        Location location;
        State(UUID id) { this.id = id; }
        void fail(Throwable failure) { this.values.put("failure", failure.toString()); failure.printStackTrace(); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
