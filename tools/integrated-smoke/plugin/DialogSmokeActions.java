package org.cardboardpowered.smoke.plugin;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
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
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/** Only public Paper APIs: real UI submissions must arrive as events before callbacks. */
public final class DialogSmokeActions implements Listener {
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private Plugin plugin;
    private Player player;
    private Dialog dialog;
    private Key dialogKey;
    private int events, calls, chatCalls;
    private int sequence;

    public void install(Plugin plugin) { this.plugin = plugin; Bukkit.getPluginManager().registerEvents(this, plugin); }

    public Map<String, String> begin(UUID id) {
        this.values.clear(); this.events = 0; this.calls = 0; this.chatCalls = 0;
        this.player = Bukkit.getPlayer(id);
        require(this.player != null, "Missing target player");
        // These must be activated by real ticks while the form is being exercised.
        // prepareStop adds another pair which must still be queued at shutdown.
        leaveCallbacks();
        var options = ClickCallback.Options.builder().uses(2).lifetime(Duration.ofMinutes(2)).build();
        var action = DialogAction.customClick((response, audience) -> {
            try {
                require(Bukkit.isPrimaryThread() && audience == this.player, "Dialog actor/thread mismatch");
                require(this.events == this.calls + 1, "Event must precede callback: " + values);
                require("Привет 世界".equals(response.getText("text")), "UI text response: " + response.payload().string());
                require(Boolean.TRUE.equals(response.getBoolean("flag")), "UI checkbox response: " + response.payload().string());
                require(Float.valueOf(3.5f).equals(response.getFloat("value")), "UI slider response: " + response.payload().string());
                this.values.put("calls", Integer.toString(++this.calls));
                Bukkit.getScheduler().runTaskLater(plugin, () -> this.player.showDialog(this.dialog), 2);
            } catch (Throwable failure) { this.values.put("failure", failure.toString()); }
        }, options);
        this.dialogKey = action.id();
        var base = DialogBase.builder(Component.text("p263-dialog"))
                .pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(List.of(DialogBody.plainMessage(Component.text("Callback response probe"))))
                .inputs(List.of(DialogInput.text("text", Component.text("Text")).initial("before edit").build(),
                        DialogInput.bool("flag", Component.text("Flag")).initial(true).build(),
                        DialogInput.numberRange("value", Component.text("Number"), 0, 10).initial(3.5f).build())).build();
        this.dialog = Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.notice(ActionButton.builder(Component.text("p263-submit")).action(action).build())));
        var chat = ClickEvent.callback(audience -> {
            try {
                require(Bukkit.isPrimaryThread() && audience == this.player, "Chat actor/thread mismatch");
                this.values.put("chatCalls", Integer.toString(++this.chatCalls));
            } catch (Throwable failure) { this.values.put("failure", failure.toString()); }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build());
        var custom = (ClickEvent.Payload.Custom) chat.payload();
        this.values.put("chatKey", custom.key().asString()); this.values.put("chatPayload", custom.nbt().string());
        this.values.put("dialogKey", action.id().asString()); this.values.put("dialogPayload", action.additions().string());
        this.values.put("actor", id.toString());
        String chatLabel = "p263-chat-callback-" + this.player.getWorld().getUID() + "-" + ++this.sequence;
        this.values.put("chatLabel", chatLabel);
        this.player.sendMessage(Component.text(chatLabel).clickEvent(chat));
        this.player.showDialog(this.dialog);
        return snapshot();
    }

    @EventHandler
    public void click(PlayerCustomClickEvent event) {
        if (this.player == null) return;
        if (!event.getIdentifier().equals(this.dialogKey)) return;
        try {
            require(Bukkit.isPrimaryThread(), "Custom event on network thread");
            require(event.getCommonConnection() instanceof PlayerGameConnection connection && connection.getPlayer() == this.player,
                    "Custom event actor mismatch");
            require(event.getTag() != null && event.getDialogResponseView() != null, "Custom event response absent");
            if (this.events < 3) {
                require("Привет 世界".equals(event.getDialogResponseView().getText("text")), "Event lost edited UI text");
            }
            this.values.put("events", Integer.toString(++this.events));
        } catch (Throwable failure) { this.values.put("failure", failure.toString()); }
    }

    public Map<String, String> snapshot() { return Map.copyOf(this.values); }
    public void close() { this.player.closeDialog(); }
    public void leaveCallbacks() {
        // Leave active and queued closures behind on purpose: native server stop must remove both.
        ClickEvent.callback(audience -> this.values.put("unexpected-retained-chat", "called"),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build());
        DialogAction.customClick((response, audience) -> this.values.put("unexpected-retained-dialog", "called"),
                ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).build());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
