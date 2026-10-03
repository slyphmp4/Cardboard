package org.cardboardpowered.library;

import java.io.File;
import java.util.List;
import org.cardboardpowered.CardboardConfig;

import net.fabricmc.loader.api.FabricLoader;

public class Libraries {

	/**
	 * List of our/Paper Libraries to download/load.
	 * Including: Paper-API, Adventure, Bungee-api, etc.
	 * 
	 * @implNote "paper-api" version number != Paper server version number.
	 * @see https://artifactory.papermc.io/ui/native/universe/io/papermc/paper/paper-api/
	 * @see https://github.com/PaperMC/Paper/blob/main/paper-api/build.gradle.kts
	 */
	public static List<Library> getLibraries() {
        // TODO: Keep Adventure version in check
        String adventureVersion = "5.2.0"; // 26.2: Paper 26.2 ships Adventure 5

        // Paper API
        //Library paperApi = Library.of("io.papermc", "paper-api", "1.21.11-R0.1-20260120.191825-59")
        //		.withSha1("223f4b673a6cefe155849a18d7a82b422bf45335")
        //		.overrideRepo("https://repo.papermc.io/repository/maven-snapshots/");
        
        Library paperApi = Library.of("io.papermc.paper", "paper-api", "26.2.build.129-stable")
        		.withSha256("f469d110d164a4ee7cb2428710cd6e433108eca8d8e0aebd6452547f408469a6")
        		.overrideRepo("https://repo.papermc.io/repository/maven-public/");

        List<Library> libraries = List.of(
        	paperApi,
        	// Paper API Libraries
        	Library.of("org.xerial", "sqlite-jdbc", "3.53.4.0"),
        	Library.of("com.mysql", "mysql-connector-j", "26.7.0"),
        	Library.of("com.google.protobuf", "protobuf-java", "4.31.1"),
        	Library.of("commons-lang", "commons-lang", "2.6", "0ce1edb914c94ebc388f086c6827e8bdeec71ac2"),
        	Library.of("org.apache.commons", "commons-collections4", "4.6.0"),
        	Library.of("commons-collections", "commons-collections", "3.2.1", "761ea405b9b37ced573d2df0d1e3a4e0f9edc668"),
        	Library.of("net.md-5", "bungeecord-chat", "1.21-R0.2-deprecated+build.21")
                    .overrideRepo("https://repo.papermc.io/repository/maven-public/"),
        	// Adventure
        	Library.of("net.kyori", "adventure-api", adventureVersion, "3e2ef126f3e3c3456995643aa49767af3b39ac34"),
        	Library.of("net.kyori", "adventure-key", adventureVersion, "32cf2afc230c0a932c71c30a86762246f23f345d") ,
        	Library.of("net.kyori", "adventure-text-serializer-gson", adventureVersion, "64921b6da90b2b4aa42e09342e12fd048783749f") ,
        	Library.of("net.kyori", "adventure-text-serializer-json", adventureVersion, "5afc1c7538e3625fb5d87926c20a96b428881e6d") ,
        	Library.of("net.kyori", "adventure-text-serializer-commons", adventureVersion, "bd00ab0ec93e5a326a0d9ce48b27c7025ce3a760") ,
        	Library.of("net.kyori", "adventure-text-serializer-legacy", adventureVersion, "29351ad8bac77a694aec074a63d89c3af08a1ada") ,
        	Library.of("net.kyori", "adventure-text-serializer-plain", adventureVersion, "eb0d8304dd9457246b6d4cae6c333e0a29375305") ,
        	Library.of("net.kyori", "adventure-text-minimessage", adventureVersion, "8435e812c70784ba7ccbd46210dccbcc576a18d5") ,
        	Library.of("net.kyori", "adventure-text-logger-slf4j", adventureVersion, "0efddd8e1faa2edae2d948b5d10be1a8f35c817a") ,
        	Library.of("net.kyori", "option", "1.1.0", "593fecb9c42688eebc7d8da5d6ea127f4d4c92a2"),
        	
        	// Complete Maven & Resolver Stack
        	Library.of("org.apache.maven", "maven-artifact", "3.9.16"),
        	Library.of("org.apache.maven", "maven-builder-support", "3.9.16"),
        	Library.of("org.apache.maven", "maven-model", "3.9.16"),
        	Library.of("org.apache.maven", "maven-model-builder", "3.9.16"),
        	Library.of("org.apache.maven", "maven-resolver-provider", "3.9.16"),
		Library.of("org.apache.maven", "maven-repository-metadata", "3.9.16"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-api", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-connector-basic", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-impl", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-named-locks", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-spi", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-supplier", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-transport-file", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-transport-http", "1.9.27"),
        	Library.of("org.apache.maven.resolver", "maven-resolver-util", "1.9.27"),
        	Library.of("org.codehaus.plexus", "plexus-interpolation", "1.29"),
        	Library.of("org.codehaus.plexus", "plexus-utils", "3.6.1")
        );

        // Set WorldEdit adapter class name here
        // as this provides more verbose stacktraces.
        // System.setProperty("worldedit.bukkit.adapter", "com.sk89q.worldedit.bukkit.adapter.impl.v1_21_11.PaperweightAdapter");

        return libraries;
    }

	/**
	 * Runs a new LibraryManager with the {@link #getLibraries()} list,
	 */
	public static void loadLibs() {
    	List<Library> libraries = getLibraries();

    	LibraryManager man = new LibraryManager("lib", true, 2, libraries);
    	man.run();

		// These jars are added to Knot's classpath at runtime, after the JVM's
		// JDBC service scan may already have happened. Initialize Paper's bundled
		// drivers explicitly so DriverManager can serve plugin-side pools.
		loadJdbcDriver("org.sqlite.JDBC");
		loadJdbcDriver("com.mysql.cj.jdbc.Driver");
    }

	private static void loadJdbcDriver(String className) {
		try {
			Class.forName(className, true,
					net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader());
		} catch (ClassNotFoundException | LinkageError error) {
			LibraryManager.logger.error("Could not initialize bundled JDBC driver " + className, error);
		}
	}

    /**
     * Add a jar file to Fabric's Knot Classloader.
     * 
     * @implSpec If Dev Env, will skip adding, assuming already in dev classpath.
     * @implNote If debug print is True, file name will be logged.
     * @return True, or False if Exception thrown.
     */
    public static boolean propose(File file) {
        try {
        	if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            	net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().addToClassPath(file.toPath(), LibraryManager.readPackagesFromJar(file));
            }

            if (CardboardConfig.DEBUG_VERBOSE_CALLS) {
            	LibraryManager.logger.info("Debug: Loading library " + file.getName());
            }
            return true;
        } catch (Exception e) {
            LibraryManager.logger.error("ERR: \"" + e.getMessage() + "\" while accessing Fabric Loader.");
            return false;
        }
    }

}
