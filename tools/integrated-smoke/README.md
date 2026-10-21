# Integrated client smoke

Run `./gradlew -PintegratedSmoke runClient` with Java 25 and a working graphics environment. Linux CI uses Xvfb and software rendering. The test creates two disposable worlds under `run-integrated-smoke`, closes each, and exits the game after success. Delete that directory before repeating the test.

The test requires the exact `INTEGRATED_SMOKE_PASS` log marker and `run-integrated-smoke/integrated-smoke.success`. It checks the owner's real profile UUID, exactly one async pre-login and login event per world, fresh plugin instances and command ownership, a Fabric permission check against a live player, client menu/entity guards, and singleton teardown. It also exercises API getters formerly tied to dedicated server properties.

The fixture provider and Bukkit plugin are separate source sets. They are not packaged in the production Cardboard JAR. This smoke covers the in-memory owner connection and repeated world lifecycle; a remote LAN guest and every item interaction remain separate manual scenarios.
