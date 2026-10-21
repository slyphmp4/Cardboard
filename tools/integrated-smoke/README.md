# Integrated client smoke

Run `./gradlew -PintegratedSmoke runClient` with Java 25 and a working graphics environment. Linux CI uses Xvfb and software rendering. The test creates two disposable worlds under `run-integrated-smoke`, closes each, and exits the game after success. Delete that directory before repeating the test.

The test requires a normal client exit, the exact `INTEGRATED_SMOKE_PASS` log marker and `run-integrated-smoke/integrated-smoke.success`. It checks the owner's real profile UUID, exactly one async pre-login and login event per world, fresh plugin instances and command ownership, a Fabric permission check against a live player, client menu/entity guards, and singleton teardown. It also exercises API getters formerly tied to dedicated server properties, recipe conversion, fresh native registries and stable Material enum size.

A Paper fixture registers an enchantment compose handler during bootstrap. Its counters must advance exactly once per world, proving that bootstrappers run before registry loading and that handlers from the previous world are cleared. The Bukkit fixture exercises both Bukkit and Folia async schedulers; normal JVM exit catches leaked scheduler, watchdog and metrics threads.

The fixture provider and Bukkit plugin are separate source sets. They are not packaged in the production Cardboard JAR. Every possible item interaction remains outside this smoke's scope.

For the additional two-client LAN check, run `./gradlew -PintegratedSmoke -PlanSmoke prepareIntegratedLanSmoke`, then `xvfb-run -a python3 tools/integrated-smoke/run_lan.py` on Linux with fresh disposable run directories. This loads real LuckPerms-Fabric 5.5.85, runs an owner and a separate Minecraft guest process, publishes each world through vanilla LAN support, and checks both players' login events and Bukkit permissions after LuckPerms grants, denies and revokes a node. Both clients must exit normally. The guest connects over TCP loopback with offline authentication because CI profiles have no Microsoft session; Microsoft session authentication and connectivity across physical machines are not exercised.
