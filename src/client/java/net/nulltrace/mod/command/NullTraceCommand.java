package net.nulltrace.mod.command;

import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.SpoofSettings;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.nulltrace.mod.tracking.ModRegistry;
import net.nulltrace.mod.privacy.AuditLedger;
import net.nulltrace.mod.privacy.PrivacyAction;
import net.nulltrace.mod.privacy.PrivacyAudit;
import net.nulltrace.mod.privacy.PrivacyDecision;
import net.nulltrace.mod.privacy.PrivacySurface;
import net.nulltrace.mod.privacy.RepeatedDecisions;
import net.nulltrace.mod.privacy.ServerProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;

//?} else {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
*///?}
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;

//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Debug command for NullTrace mod.
 * Usage: /nulltrace info [mod name]
 */
public class NullTraceCommand {
    
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(NullTraceCommand::registerCommands);
    }
    
    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, 
                                          CommandBuildContext context) {
        // Audit is intentionally available even when the optional debug command is
        // disabled: privacy must be inspectable without enabling diagnostics.
        dispatcher.register(
            //? if >=26.1 {
            ClientCommands.literal("nulltrace")
            //?} else {
            /*ClientCommandManager.literal("nulltrace")
            *///?}
                .executes(NullTraceCommand::showHelp)
                .then(
                    //? if >=26.1 {
                    ClientCommands.literal("audit")
                    //?} else {
                    /*ClientCommandManager.literal("audit")
                    *///?}
                    .executes(NullTraceCommand::showAudit))
                .then(
                    //? if >=26.1 {
                    ClientCommands.literal("profile")
                    //?} else {
                    /*ClientCommandManager.literal("profile")
                    *///?}
                    .executes(NullTraceCommand::showProfile)
                    .then(
                        //? if >=26.1 {
                        ClientCommands.argument("mode", StringArgumentType.word())
                        //?} else {
                        /*ClientCommandManager.argument("mode", StringArgumentType.word())
                        *///?}
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                List.of("STANDARD", "STRICT", "MAXIMUM_PRIVACY", "CUSTOM"), builder))
                        .executes(NullTraceCommand::setProfile)))
                .then(
                    //? if >=26.1 {
                    ClientCommands.literal("info")
                    //?} else {
                    /*ClientCommandManager.literal("info")
                    *///?}
                    .requires(source -> NullTraceConfig.getInstance().getSettings().isDebugCommand())
                    .then(
                        //? if >=26.1 {
                        ClientCommands.argument("modName", StringArgumentType.greedyString())
                        //?} else {
                        /*ClientCommandManager.argument("modName", StringArgumentType.greedyString())
                        *///?}
                        .suggests(NullTraceCommand::suggestModNames)
                        .executes(ctx -> showModInfo(ctx, StringArgumentType.getString(ctx, "modName"))))
                    .executes(ctx -> showOverview(ctx)))
                .then(
                    //? if >=26.1 {
                    ClientCommands.literal("channels")
                    //?} else {
                    /*ClientCommandManager.literal("channels")
                    *///?}
                    .requires(source -> NullTraceConfig.getInstance().getSettings().isDebugCommand())
                    .executes(NullTraceCommand::showAllChannels))
        );
    }
    
    /**
     * Show help message.
     *
     * <p>Every registered subcommand is listed, in the order they were registered, and the two that
     * are gated behind Debug Command say so. The previous version listed {@code info} and
     * {@code channels} unconditionally — with Debug Command off, typing exactly what the help told
     * you to type produced nothing at all, because Brigadier hides a {@code .requires}-gated node
     * from a source that does not satisfy it. It also never mentioned {@code profile}, which is
     * always available.
     */
    private static int showHelp(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        boolean debug = NullTraceConfig.getInstance().getSettings().isDebugCommand();

        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_HELP_HEADER)));
        source.sendFeedback(Component.empty());
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_HELP_AUDIT)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_HELP_PROFILE)));
        source.sendFeedback(helpLine(NullTraceStrings.COMMAND_HELP_INFO, !debug));
        source.sendFeedback(helpLine(NullTraceStrings.COMMAND_HELP_CHANNELS, !debug));

        return 1;
    }

    /**
     * One help line, with a dim {@code (require Debug Command)} suffix when the subcommand is
     * currently hidden from this source.
     */
    private static MutableComponent helpLine(String key, boolean gated) {
        MutableComponent line = info(NullTraceLang.tr(key));
        if (gated) {
            line.append(Component.literal("  (" + NullTraceLang.tr(NullTraceStrings.COMMAND_HELP_GATED) + ")")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return line;
    }
    
    /**
     * Suggest mod ids that have any trackable content (translation keys,
     * channels, keybinds, or known-pack triples). Includes JIJ submodules —
     * {@code /nulltrace info} is a debug command, so granularity matters; the
     * whitelist UI is the one that only shows top-level mods.
     */
    private static CompletableFuture<Suggestions> suggestModNames(
            CommandContext<FabricClientCommandSource> context,
            SuggestionsBuilder builder) {

        Set<String> modNames = new LinkedHashSet<>();

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            if (ModRegistry.PLATFORM_MODS.contains(modId)) continue;
            if (ModRegistry.hasTrackableContentIncludingJij(modId)) {
                modNames.add(modId);
            }
        }

        return SharedSuggestionProvider.suggest(modNames, builder);
    }
    
    private static int showProfile(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        NullTraceConfig config = NullTraceConfig.getInstance();
        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_PROFILE_HEADER)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_SERVER,
                config.getCurrentServer())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_PROFILE,
                config.getCurrentProfileMode().name())));
        return 1;
    }

    private static int setProfile(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        String raw = StringArgumentType.getString(ctx, "mode");
        try {
            ServerProfile.Mode mode = ServerProfile.Mode.valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            NullTraceConfig.getInstance().setCurrentProfileMode(mode);
            source.sendFeedback(success(NullTraceLang.tr(NullTraceStrings.COMMAND_PROFILE_SET,
                    NullTraceConfig.getInstance().getCurrentServer(), mode.name())));
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFeedback(error("Unknown profile: " + raw));
            return 0;
        }
    }

    /**
     * Print an explainable snapshot of the active privacy policy and decisions.
     */
    private static int showAudit(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        NullTraceConfig config = NullTraceConfig.getInstance();
        AuditLedger ledger = PrivacyAudit.ledger();
        var policy = config.privacyPolicy();

        int totalChannels = 0;
        for (ModRegistry.ModInfo mod : ModRegistry.getAllMods()) totalChannels += mod.getChannels().size();
        int blockedChannels = policy.actionFor(PrivacySurface.MOD_CHANNEL) == PrivacyAction.BLOCK
                ? totalChannels : 0;
        int allowedChannels = totalChannels - blockedChannels;

        int knownPacks = ModRegistry.isKnownPacksHookPresent() ? ModRegistry.getKnownPackCount() : 0;
        int blockedKnownPacks = policy.actionFor(PrivacySurface.KNOWN_PACK) == PrivacyAction.BLOCK ? knownPacks : 0;
        int translations = ModRegistry.getTranslationKeyCount();
        int blockedTranslations = policy.actionFor(PrivacySurface.TRANSLATION_PROBE) == PrivacyAction.BLOCK ? translations : 0;
        int shaders = ModRegistry.getShaderCount();
        int strippedShaders = config.shouldStripModShaders() ? shaders : 0;

        // On an integrated server (singleplayer, or hosting a LAN world) no client-brand packet is
        // ever sent, so "EXPOSED" was wrong there: it reported a leak that cannot happen, and it
        // inflated Remaining exposure in exactly the situation where nothing leaves the machine.
        boolean integratedServer = Minecraft.getInstance().hasSingleplayerServer();

        long ledgerBlocked = ledger.count(PrivacyAction.BLOCK);
        long ledgerAllowed = ledger.count(PrivacyAction.ALLOW);
        long ledgerWarnings = ledger.count(PrivacyAction.WARN);
        int remaining = ledger.allowedSurfaceCount() + allowedChannels
                + (config.shouldSpoofBrand() || integratedServer ? 0 : 1)
                + (knownPacks - blockedKnownPacks)
                + (translations - blockedTranslations);

        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_HEADER)));
        // "local:e" is the integrated server's socket address, not a host anyone can join; label it
        // so the audit cannot be read as "connected to a server called local:e".
        source.sendFeedback(info(NullTraceLang.tr(
                integratedServer ? NullTraceStrings.COMMAND_AUDIT_SERVER_INTEGRATED
                                : NullTraceStrings.COMMAND_AUDIT_SERVER,
                config.getCurrentServer())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_PROFILE,
                config.getCurrentProfileMode().name())));
        if (integratedServer) {
            source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_BRAND_SINGLEPLAYER)));
        } else {
            source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_BRAND,
                    config.shouldSpoofBrand() ? "BLOCKED" : "EXPOSED")));
        }
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_CHANNELS,
                blockedChannels, allowedChannels)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_KNOWN_PACKS,
                blockedKnownPacks, knownPacks - blockedKnownPacks)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_TRANSLATIONS,
                blockedTranslations, translations - blockedTranslations)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_RESOURCES,
                ledger.counts(PrivacyAction.BLOCK).getOrDefault(PrivacySurface.RESOURCE_PACK, 0).intValue(),
                ledger.counts(PrivacyAction.ALLOW).getOrDefault(PrivacySurface.RESOURCE_PACK, 0).intValue())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_LOCAL,
                ledger.counts(PrivacyAction.BLOCK).getOrDefault(PrivacySurface.LOCAL_REQUEST, 0).intValue(),
                ledger.counts(PrivacyAction.WARN).getOrDefault(PrivacySurface.LOCAL_REQUEST, 0).intValue())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_SHADERS, strippedShaders)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_EXPOSURE, remaining)));
        source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_EVENTS,
                ledgerBlocked, ledgerAllowed, ledgerWarnings)));

        // Collapse consecutive identical decisions. The ledger records one entry per evaluation,
        // and the brand is re-evaluated several times per connection, so the tail used to be the
        // same CLIENT_BRAND line three to eight times — which reads as noise and hides the other
        // decisions behind it. Order is preserved; only runs are folded.
        for (RepeatedDecisions.Run repeated : RepeatedDecisions.fold(ledger.events(), 8)) {
            String line = NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_REASON,
                    repeated.decision().surface(), repeated.decision().subject(),
                    repeated.decision().reason());
            source.sendFeedback(dim(repeated.count() > 1
                    ? NullTraceLang.tr(NullTraceStrings.COMMAND_AUDIT_REASON_REPEAT, line, repeated.count())
                    : line));
        }
        return 1;
    }

    /**
     * Show overview of all tracked mods.
     */
    private static int showOverview(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();

        // Pre-scan: gather displayable mods + per-mod counts in one pass so the
        // header total and the body loop never disagree.
        List<MutableComponent> entries = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            if (ModRegistry.PLATFORM_MODS.contains(modId)) continue;
            if (mod.getContainingMod().isPresent()) continue; // JIJ children roll up to host
            ModRegistry.ContentCounts counts = ModRegistry.aggregateContent(modId);
            if (counts.isEmpty()) continue;
            entries.add(modEntry(mod.getMetadata().getName(), counts));
        }

        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_HEADER)));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_TOTAL_MODS, entries.size())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_VANILLA_KEYS, ModRegistry.getVanillaKeyCount())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_SERVER_KEYS, ModRegistry.getServerPackKeyCount())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_TOTAL_KEYS, ModRegistry.getTranslationKeyCount())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_TOTAL_KEYBINDS, ModRegistry.getKeybindCount())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_TOTAL_SHADERS, ModRegistry.getShaderCount())));
        // Known-packs total is only meaningful where the leak exists. Suppressed on hook-absent clients (same gate as /nulltrace info).
        if (ModRegistry.isKnownPacksHookPresent()) {
            source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_TOTAL_KNOWN_PACKS, ModRegistry.getKnownPackCount())));
        }

        source.sendFeedback(Component.empty());
        source.sendFeedback(subheader(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_MODS_HEADER)));

        if (entries.isEmpty()) {
            source.sendFeedback(warning(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_NO_MODS)));
        } else {
            for (MutableComponent entry : entries) {
                source.sendFeedback(entry);
            }
        }

        source.sendFeedback(Component.empty());
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_OVERVIEW_USE_INFO)));
        
        return 1;
    }
    
    /**
     * Show detailed info for a specific mod.
     */
    private static int showModInfo(CommandContext<FabricClientCommandSource> ctx, String modName) {
        FabricClientCommandSource source = ctx.getSource();

        Resolved resolved = findMod(modName);
        if (resolved == null) {
            source.sendFeedback(error(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_NOT_FOUND, modName)));
            source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_USE_LIST)));
            return 0;
        }

        String modId = resolved.modId();
        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_HEADER, resolved.displayName())));
        source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_ID, modId)));

        // Aggregate includes JIJ descendants so meta jars (e.g. fabric-api) report children's content.
        renderContentSection(source, NullTraceStrings.COMMAND_INFO_TRANSLATION_KEYS,
            ModRegistry.aggregateAllTranslationKeys(modId), 20);
        renderContentSection(source, NullTraceStrings.COMMAND_INFO_KEYBINDS,
            ModRegistry.aggregateAllKeybinds(modId), 20);
        renderContentSection(source, NullTraceStrings.COMMAND_INFO_CHANNELS,
            ModRegistry.aggregateAllChannelIds(modId), 20);
        renderContentSection(source, NullTraceStrings.COMMAND_INFO_SHADERS,
            ModRegistry.aggregateAllShaderPaths(modId), 20);

        // Suppressed when the Fabric hook is absent — the vector is dead there.
        if (ModRegistry.isKnownPacksHookPresent()) {
            List<String> knownPacks = ModRegistry.aggregateAllKnownPackStrings(modId);
            source.sendFeedback(Component.empty());
            source.sendFeedback(subheader(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_KNOWN_PACKS, knownPacks.size())));
            if (knownPacks.isEmpty()) {
                source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_NONE)));
            } else {
                for (String pack : knownPacks) {
                    source.sendFeedback(listItem(pack));
                }
            }
        }

        // Only list JIJ submodules with tracked content; pure library JIJs aren't queryable via /nulltrace info.
        List<? extends ModContainer> trackedContained = ModRegistry.getContainedMods(modId).stream()
            .filter(child -> ModRegistry.getModInfo(child.getMetadata().getId()) != null)
            .toList();
        source.sendFeedback(Component.empty());
        source.sendFeedback(subheader(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_JIJ, trackedContained.size())));
        if (trackedContained.isEmpty()) {
            source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_NONE)));
        } else {
            for (ModContainer child : trackedContained) {
                source.sendFeedback(listItem(child.getMetadata().getId()));
            }
        }

        // Whitelist status
        source.sendFeedback(Component.empty());
        NullTraceConfig nulltraceConfig = NullTraceConfig.getInstance();
        SpoofSettings nulltraceSettings = nulltraceConfig.getSettings();
        SpoofSettings.WhitelistMode whitelistMode = nulltraceSettings.getWhitelistMode();
        ModRegistry.ModInfo info = ModRegistry.getModInfo(modId);
        boolean hasChannels = info != null && info.hasChannels();

        String statusText;
        boolean isAllowed;

        boolean directlyWhitelisted = switch (whitelistMode) {
            case AUTO -> hasChannels;
            case CUSTOM -> nulltraceSettings.isModWhitelisted(modId);
            default -> false;
        };

        if (directlyWhitelisted) {
            isAllowed = true;
            statusText = NullTraceLang.tr(whitelistMode == SpoofSettings.WhitelistMode.AUTO
                ? NullTraceStrings.COMMAND_INFO_STATUS_ALLOWED_AUTO
                : NullTraceStrings.COMMAND_INFO_STATUS_ALLOWED_CUSTOM);
        } else if (ModRegistry.isInDependencyClosure(modId)) {
            isAllowed = true;
            statusText = NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_STATUS_ALLOWED_DEP,
                ModRegistry.resolveRequiringModName(modId));
        } else {
            isAllowed = false;
            statusText = NullTraceLang.tr(switch (whitelistMode) {
                case AUTO -> NullTraceStrings.COMMAND_INFO_STATUS_BLOCKED_AUTO;
                case CUSTOM -> NullTraceStrings.COMMAND_INFO_STATUS_BLOCKED_CUSTOM;
                default -> NullTraceStrings.COMMAND_INFO_STATUS_BLOCKED_OFF;
            });
        }
        source.sendFeedback(isAllowed ? success(statusText) : warning(statusText));
        
        return 1;
    }
    
    
    /**
     * Show all tracked channels.
     */
    private static int showAllChannels(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        
        source.sendFeedback(header(NullTraceLang.tr(NullTraceStrings.COMMAND_CHANNELS_HEADER)));
        
        int totalChannels = 0;
        for (ModRegistry.ModInfo info : ModRegistry.getAllMods()) {
            //? if >=1.21.11 {
            Set<Identifier> channels = info.getChannels();
            //?} else {
            /*Set<ResourceLocation> channels = info.getChannels();
            *///?}
            if (!channels.isEmpty()) {
                source.sendFeedback(subheader(info.getDisplayName() + " (" + info.getModId() + "):"));
                //? if >=1.21.11 {
                for (Identifier channel : channels) {
                //?} else {
                /*for (ResourceLocation channel : channels) {
                *///?}
                    boolean whitelisted = ModRegistry.isWhitelistedChannel(channel);
                    if (whitelisted) {
                        source.sendFeedback(Component.literal("  ✓ " + channel.toString())
                            .withStyle(ChatFormatting.GREEN));
                    } else {
                        source.sendFeedback(Component.literal("  ✗ " + channel.toString())
                            .withStyle(ChatFormatting.RED));
                    }
                    totalChannels++;
                }
            }
        }
        
        if (totalChannels == 0) {
            source.sendFeedback(warning(NullTraceLang.tr(NullTraceStrings.COMMAND_CHANNELS_NONE)));
        } else {
            source.sendFeedback(Component.empty());
            source.sendFeedback(info(NullTraceLang.tr(NullTraceStrings.COMMAND_CHANNELS_TOTAL, totalChannels)));
            source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_CHANNELS_LEGEND)));
        }
        
        return 1;
    }
    
    
    /** Resolution result for {@link #findMod}. {@code modId} is canonical; {@code displayName} comes from ModInfo when present, else the mod's metadata name. */
    private record Resolved(String modId, String displayName) {}

    /**
     * Find a mod by id or display name. Resolves both tracked mods (with a
     * {@link ModRegistry.ModInfo}) and meta-mods (no ModInfo of their own,
     * content only via JIJ descendants — e.g. fabric-api). The exact-id
     * branch hits ModContainer when no ModInfo exists, gated on
     * {@link ModRegistry#hasTrackableContentIncludingJij}.
     */
    private static Resolved findMod(String query) {
        if (query == null || query.isEmpty()) return null;

        // Exact id: prefer tracked ModInfo, then meta-mod via ModContainer.
        ModRegistry.ModInfo info = ModRegistry.getModInfo(query);
        if (info != null) return new Resolved(info.getModId(), info.getDisplayName());
        var container = FabricLoader.getInstance().getModContainer(query);
        if (container.isPresent()) {
            // Canonicalize provides aliases — `getModContainer("fabric")` on older
            // fabric-api returns the fabric-api container; we want subsequent
            // aggregation to use the real id so JIJ walks find everything.
            var meta = container.get().getMetadata();
            String realId = meta.getId();
            if (ModRegistry.hasTrackableContentIncludingJij(realId)) {
                return new Resolved(realId, meta.getName());
            }
        }

        // Case-insensitive id / display-name match against tracked mods.
        for (ModRegistry.ModInfo mod : ModRegistry.getAllMods()) {
            if (mod.getModId().equalsIgnoreCase(query) ||
                mod.getDisplayName().equalsIgnoreCase(query)) {
                return new Resolved(mod.getModId(), mod.getDisplayName());
            }
        }
        // Same against top-level meta-mods (catches "Fabric API" by name when fabric-api itself has no ModInfo).
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (mod.getContainingMod().isPresent()) continue;
            if (ModRegistry.PLATFORM_MODS.contains(id)) continue;
            if (!ModRegistry.hasTrackableContentIncludingJij(id)) continue;
            String name = mod.getMetadata().getName();
            if (id.equalsIgnoreCase(query) || name.equalsIgnoreCase(query)) {
                return new Resolved(id, name);
            }
        }

        return null;
    }
    
    /** Render a /nulltrace info section: subheader+count, then "(none)" or up to {@code limit} items (negative = unlimited). */
    private static void renderContentSection(
        FabricClientCommandSource source,
        String headerKey,
        List<String> items,
        int limit
    ) {
        source.sendFeedback(Component.empty());
        source.sendFeedback(subheader(NullTraceLang.tr(headerKey, items.size())));
        if (items.isEmpty()) {
            source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_NONE)));
            return;
        }
        int shown = 0;
        for (String item : items) {
            if (limit >= 0 && shown >= limit) {
                source.sendFeedback(dim(NullTraceLang.tr(NullTraceStrings.COMMAND_INFO_MORE, items.size() - shown)));
                break;
            }
            source.sendFeedback(listItem(item));
            shown++;
        }
    }

    // ==================== FORMATTING HELPERS ====================

    private static MutableComponent header(String text) {
        return Component.literal("[NullTrace] ")
                .withStyle(ChatFormatting.DARK_PURPLE)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD));
    }

    private static MutableComponent subheader(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent info(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    private static MutableComponent success(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GREEN);
    }

    private static MutableComponent warning(String text) {
        return Component.literal(text).withStyle(ChatFormatting.YELLOW);
    }

    private static MutableComponent error(String text) {
        return Component.literal(text).withStyle(ChatFormatting.RED);
    }

    private static MutableComponent dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static MutableComponent listItem(String text) {
        return Component.literal("  - ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
    }

    private static MutableComponent modEntry(String displayName, ModRegistry.ContentCounts counts) {
        MutableComponent component = Component.literal(displayName).withStyle(ChatFormatting.WHITE);

        List<String> details = new ArrayList<>();
        if (counts.translationKeys() > 0) {
            details.add(NullTraceLang.tr(NullTraceStrings.COMMAND_MODENTRY_KEYS, counts.translationKeys()));
        }
        if (counts.keybinds() > 0) {
            details.add(NullTraceLang.tr(NullTraceStrings.COMMAND_MODENTRY_KEYBINDS, counts.keybinds()));
        }
        if (counts.channels() > 0) {
            details.add(NullTraceLang.tr(NullTraceStrings.COMMAND_MODENTRY_CHANNELS, counts.channels()));
        }
        if (counts.knownPacks() > 0) {
            details.add(NullTraceLang.tr(NullTraceStrings.COMMAND_MODENTRY_KNOWN_PACKS, counts.knownPacks()));
        }
        if (counts.shaders() > 0) {
            details.add(NullTraceLang.tr(NullTraceStrings.COMMAND_MODENTRY_SHADERS, counts.shaders()));
        }

        if (!details.isEmpty()) {
            component.append(Component.literal(" (" + String.join(", ", details) + ")")
                    .withStyle(ChatFormatting.GRAY));
        }

        return component;
    }
}
