package io.papermc.paper.adventure.providers;

public class ComponentLoggerProviderImpl implements net.kyori.adventure.text.logger.slf4j.ComponentLoggerProvider {
    @Override
    public net.kyori.adventure.text.logger.slf4j.ComponentLogger logger(LoggerHelper helper, String name) {
        return helper.delegating(org.slf4j.LoggerFactory.getLogger(name), message ->
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(
                        net.kyori.adventure.translation.GlobalTranslator.render(message, java.util.Locale.getDefault())));
    }
}
