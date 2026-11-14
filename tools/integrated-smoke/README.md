# Integrated client smoke

The connected owner also exercises `DRAGON_BATTLE_TICK_PASS` in each world:
controlled native End fight states advance an empty-crystal API respawn through
the real tick, repeat after saved-data codec decoding, and retain the ordinary
missing-crystal abort. Boss-bar membership/state, fight fields, entities and
the native dragon chunk ticket are restored before the existing lifecycle
checks continue. This is not a full player-driven dragon combat test.

Run `./gradlew -PintegratedSmoke runClient` with Java 25 and a working graphics environment. Linux CI uses Xvfb and software rendering. The test creates two disposable worlds under `run-integrated-smoke`, closes each, and exits the game after success. Delete that directory before repeating the test.

The test requires a normal client exit, the exact `INTEGRATED_SMOKE_PASS` log marker and `run-integrated-smoke/integrated-smoke.success`. It checks the owner's real profile UUID, exactly one async pre-login and login event per world, fresh plugin instances and command ownership, a Fabric permission check against a live player, client menu/entity guards, and singleton teardown. It also exercises API getters formerly tied to dedicated server properties, recipe conversion, fresh native registries and stable Material enum size.

A Paper fixture registers an enchantment compose handler during bootstrap. Its counters must advance exactly once per world, proving that bootstrappers run before registry loading and that handlers from the previous world are cleared. The Bukkit fixture exercises both Bukkit and Folia async schedulers; normal JVM exit catches leaked scheduler, watchdog and metrics threads.

The fixture provider and Bukkit plugin are separate source sets. They are not packaged in the production Cardboard JAR. Every possible item interaction remains outside this smoke's scope.

The 26.3 server probes check native player-drop cancellation/restoration and successful spawning, sign cancellation, plugin edits, editor ownership and waxing. Unchanged filtered sign lines must survive both a native update and a Bukkit snapshot edit; the snapshot check also covers colour/glow and source isolation. This exercises the transformed `SignText.Mutable` constructor rather than an untransformed unit-test copy.

Each LAN JVM uses its own LWJGL native-library extraction directory. CI also uploads `hs_err_pid*.log` files if a JVM aborts, so native startup failures remain diagnosable. Nonzero exits and missing success markers still fail the smoke.

Minecraft 26.3 defaults the dedicated whitelist to enabled. The disposable LuckPerms server keeps it enabled and admits only the fixture's offline `SmokeGuest` profile. The integrated and dedicated probes also require `WHITELIST_SETTINGS_PASS`, checking that Bukkit whitelist state and enforcement independently match the native flags and restore their initial values.

The entity event probes directly call real transformed `LivingEntity.heal` and `Mob.setTarget` methods on client-world pigs, including the reasoned heal overload and both target bridges. They verify health changes, maximum/dead health behavior, target assignment/clearing and vanilla invalid-target filtering. Client passenger probes also cover eventful mounting, packet-style mounting followed by ejecting, and vehicle transfers, checking both sides of the passenger relationship. The host's Bukkit fixture counts events for the probe UUIDs and requires zero client-side events; the separate LAN guest repeats the calls without a local Bukkit singleton. On the integrated server, a real Bukkit listener changes healing amounts, cancels healing and target changes, and replaces targets. Assertions check the resulting NMS health/target, exact event counts, explicit reasons, no-op targets and the no-event target bridge. Cancelled target assignment and clearing must not fall through to vanilla. Server mounting and dismounting must still fire exactly one Bukkit vehicle enter/exit event. Both worlds must log `CLIENT_ENTITY_EVENTS_PASS` and `SERVER_ENTITY_EVENTS_PASS` before the existing success markers.

For the additional LAN and LuckPerms checks, run `./gradlew -PintegratedSmoke -PlanSmoke prepareIntegratedLanSmoke`, then `xvfb-run -a python3 tools/integrated-smoke/run_lan.py` on Linux with fresh disposable run directories. An owner and a separate Minecraft guest process exercise two worlds published through vanilla LAN support, including both players' login events and Fabric-backed Bukkit permissions. The guest then connects to a dedicated server with real LuckPerms-Fabric 5.5.85 and verifies Bukkit permission grant, denial and revocation. LuckPerms declares `environment=server` and a dedicated-server entrypoint, so it is tested on that supported platform rather than forced into an integrated server. All three processes must exit successfully. Connections use TCP loopback and offline authentication because CI profiles have no Microsoft session; Microsoft session authentication and connectivity across physical machines are not exercised.
