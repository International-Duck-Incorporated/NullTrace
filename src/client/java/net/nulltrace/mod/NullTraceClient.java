package net.nulltrace.mod;

import net.nulltrace.mod.accounts.AccountManager;
import net.nulltrace.mod.command.NullTraceCommand;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.JarIntegrityChecker;
import net.nulltrace.mod.config.UpdateChecker;
import net.nulltrace.mod.protection.PackStripOverlay;
import net.nulltrace.mod.protection.ShaderStripTracker;
import net.nulltrace.mod.tracking.ModRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if >=1.20.2 {
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
//?}
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;

//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Client-side initialization for the NullTrace mod.
 * Loads configuration and initializes protection systems.
 */
public class NullTraceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// Log mod initialization
		NullTrace.LOGGER.info("{} v{} - Privacy protection for Minecraft", NullTrace.MOD_NAME, NullTrace.getVersion());
		NullTrace.LOGGER.info("Protecting against: TrackPack, Key Resolution Exploit, Client Fingerprinting");
		
		NullTraceConfig.getInstance();
		NullTraceCommand.register();
		AccountManager.getInstance(); // Load saved accounts

		// Both checks are outbound HTTP requests. They are proxy-aware and user-controlled
		// (Advanced tab -> Network): a privacy tool should never make network calls the player
		// cannot see or switch off.
		if (NullTraceConfig.getInstance().getSettings().isCheckUpdates()) {
			UpdateChecker.checkForUpdate(); // non-blocking
		} else {
			NullTrace.LOGGER.debug("[NullTrace] Startup update check disabled by configuration");
		}

		if (NullTraceConfig.getInstance().getSettings().isVerifyJarIntegrity()) {
			JarIntegrityChecker.checkIntegrity(); // non-blocking
		} else {
			NullTrace.LOGGER.debug("[NullTrace] Jar integrity check disabled by configuration");
		}

		// Scan for registered channels after all mods have initialized
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			ModRegistry.inferJijNamespaceAliases();  // must run before scanRegisteredChannels
			scanRegisteredChannels();
			// Fallback: scan mods for language files if mixin didn't catch them
			scanModsForLanguageFiles();
			scanModsForShaders();
			ModRegistry.indexKnownPackOwners();
			// Initial closure seed; further rebuilds run via NullTraceConfig.save().
			ModRegistry.rebuildDependencyClosure();
		});

		ClientTickEvents.END_CLIENT_TICK.register(PackStripOverlay::tryShowNext);
		ClientTickEvents.END_CLIENT_TICK.register(client -> ShaderStripTracker.flushPending());

		NullTrace.LOGGER.info("NullTrace client protection initialized");
	}
	
	/**
	 * Scan for all registered channels from Fabric API.
	 * This runs after all mods have initialized, so we capture
	 * channels before the user opens the whitelist menu.
	 */
	private void scanRegisteredChannels() {
		int channelCount = 0;

		channelCount += scanChannelSource(ClientPlayNetworking::getGlobalReceivers, "play channels");
		//? if >=1.20.2 {
		channelCount += scanChannelSource(ClientConfigurationNetworking::getGlobalReceivers, "config channels");
		//?}
		channelCount += scanChannelSource(ClientPlayNetworking::getReceived, "play received channels");
		channelCount += scanChannelSource(ClientPlayNetworking::getSendable, "play sendable channels");
		//? if >=1.20.2 {
		channelCount += scanChannelSource(ClientConfigurationNetworking::getReceived, "config received channels");
		channelCount += scanChannelSource(ClientConfigurationNetworking::getSendable, "config sendable channels");
		//?}

		NullTrace.LOGGER.debug("[NullTrace] Scanned {} mod channels at startup", channelCount);
	}

	//? if >=1.21.11 {
	private int scanChannelSource(Supplier<Set<Identifier>> source, String label) {
	//?} else {
	/*private int scanChannelSource(Supplier<Set<ResourceLocation>> source, String label) {
	*///?}
		try {
			int count = 0;
			for (var channel : source.get()) {
				// null = vanilla minecraft: channel (nothing to track); else the owning mod.
				String owner = ModRegistry.resolveOwningModForChannel(channel.getNamespace(), channel.getPath());
				if (owner == null) continue;
				ModRegistry.recordChannel(owner, channel);
				count++;
			}
			return count;
		} catch (Exception e) {
			NullTrace.LOGGER.debug("[NullTrace] Could not scan {}: {}", label, e.getMessage());
			return 0;
		}
	}
	
	/**
	 * Fallback: Scan all mods for language files and register them.
	 * This handles cases where the ClientLanguageMixin doesn't work
	 * (e.g., if the method signature changed in a new Minecraft version).
	 */
	private void scanModsForLanguageFiles() {
		int modsWithLang = 0;
		int modsAdded = 0;
		
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String modId = mod.getMetadata().getId();

			if (ModRegistry.PLATFORM_MODS.contains(modId)) continue;
			// Skip JIJ children: their lang already loads via their host's mixin.
			if (mod.getContainingMod().isPresent()) continue;
			
			// Check if this mod already has translation keys tracked
			ModRegistry.ModInfo existingInfo = ModRegistry.getModInfo(modId);
			if (existingInfo != null && existingInfo.hasTranslationKeys()) {
				modsWithLang++;
				continue;
			}
			
			// Check if this mod has a language file
			boolean found = false;
			for (Path rootPath : mod.getRootPaths()) {
				Path langFile = rootPath.resolve("assets/" + modId + "/lang/en_us.json");
				if (Files.exists(langFile)) {
					found = true;
					// This mod has a language file - register it
					try (InputStreamReader reader = new InputStreamReader(Files.newInputStream(langFile))) {
						JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
						int keyCount = 0;
						for (String key : json.keySet()) {
							ModRegistry.recordTranslationKey(modId, key);
							keyCount++;
						}
						if (keyCount > 0) {
							NullTrace.LOGGER.debug("[NullTrace] Fallback: Registered {} translation keys for mod '{}'", keyCount, modId);
							modsWithLang++;
							modsAdded++;
						}
					} catch (Exception e) {
						NullTrace.LOGGER.debug("[NullTrace] Could not read language file for {}: {}", modId, e.getMessage());
					}
					break; // Only check first root path
				}
			}
			
			// If no language file found, still count if mod has channels
			if (!found && existingInfo != null && existingInfo.hasChannels()) {
				modsWithLang++;
			}
		}
		
		NullTrace.LOGGER.debug("[NullTrace] Fallback scan added {} mods with translation keys", modsAdded);
		NullTrace.LOGGER.debug("[ModRegistry] Total: {} whitelistable mods, {} translation keys, {} keybinds",
			modsWithLang, ModRegistry.getTranslationKeyCount(), ModRegistry.getKeybindCount());
	}

	/** Record each mod's {@code assets/<ns>/shaders/} files (for {@code /nulltrace info} + the strip's owner cache). */
	private void scanModsForShaders() {
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String modId = mod.getMetadata().getId();
			if (ModRegistry.PLATFORM_MODS.contains(modId)) continue;
			if (mod.getContainingMod().isPresent()) continue; // JIJ children roll up to host

			for (Path rootPath : mod.getRootPaths()) {
				Path assets = rootPath.resolve("assets");
				if (!Files.isDirectory(assets)) continue;
				try (var namespaces = Files.list(assets)) {
					namespaces.filter(Files::isDirectory).forEach(nsDir -> {
						String ns = nsDir.getFileName().toString().replace("/", "");
						if ("minecraft".equals(ns)) return; // vanilla shaders are never per-mod stripped
						Path shadersDir = nsDir.resolve("shaders");
						if (!Files.isDirectory(shadersDir)) return;
						try (var files = Files.walk(shadersDir)) {
							files.filter(Files::isRegularFile).forEach(f ->
								ModRegistry.recordShader(modId, ns,
									"shaders/" + shadersDir.relativize(f).toString().replace('\\', '/')));
						} catch (Exception e) {
							NullTrace.LOGGER.debug("[NullTrace] shader walk failed for {} ({}): {}", modId, ns, e.getMessage());
						}
					});
				} catch (Exception e) {
					NullTrace.LOGGER.debug("[NullTrace] shader scan failed for {}: {}", modId, e.getMessage());
				}
			}
		}
	}
}
