package org.cardboardpowered.impl.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A validated startup file and argument vector; Unix arguments are never shell expressions. */
public record RestartPlan(Path directory, Path script, List<String> command) {
    public RestartPlan { command = List.copyOf(command); }

    public static Optional<RestartPlan> create(Path directory, String configured, boolean windows) {
        if (configured == null || configured.isBlank()) return Optional.empty();
        directory = directory.toAbsolutePath().normalize();
        String value = configured.trim();
        boolean wholeFile = false;
        try { wholeFile = Files.isRegularFile(directory.resolve(value)); }
        catch (java.nio.file.InvalidPathException quotedCommand) { }
        List<String> args = wholeFile ? List.of(value) : arguments(value);
        if (args.isEmpty()) return Optional.empty();
        Path script = directory.resolve(args.getFirst()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) return Optional.empty();
        var command = new ArrayList<String>();
        if (windows) {
            command.addAll(List.of("cmd.exe", "/d", "/c", "start", "\"\"", quoteWindows(script.toString())));
            args.subList(1, args.size()).forEach(arg -> command.add(quoteWindows(arg)));
        } else {
            command.add("sh"); command.add(script.toString()); command.addAll(args.subList(1, args.size()));
        }
        return Optional.of(new RestartPlan(directory, script, command));
    }

    private static String quoteWindows(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    public Process launch() throws IOException {
        return new ProcessBuilder(this.command).directory(this.directory.toFile()).inheritIO().start();
    }

    public static List<String> arguments(String input) {
        var result = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;
        boolean token = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == 0) throw new IllegalArgumentException("Restart command contains a NUL character");
            if (c == '\\' && quote != '\'' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '"' || (quote == 0 && (next == '\'' || Character.isWhitespace(next)))) {
                    current.append(next); i++; token = true; continue;
                }
            }
            if (c == '\'' || c == '"') {
                if (quote == 0) { quote = c; token = true; continue; }
                if (quote == c) { quote = 0; continue; }
            }
            if (quote == 0 && Character.isWhitespace(c)) {
                if (token) { result.add(current.toString()); current.setLength(0); token = false; }
            } else { current.append(c); token = true; }
        }
        if (quote != 0) throw new IllegalArgumentException("Unclosed quote in restart command");
        if (token) result.add(current.toString());
        return List.copyOf(result);
    }
}
