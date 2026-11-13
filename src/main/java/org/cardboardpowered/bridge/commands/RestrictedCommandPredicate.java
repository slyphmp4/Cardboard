package org.cardboardpowered.bridge.commands;

/** Explicit Paper override for unattended command execution checks. */
public interface RestrictedCommandPredicate {
    boolean restricted();
}
