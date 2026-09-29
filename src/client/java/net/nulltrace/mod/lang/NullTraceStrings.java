package net.nulltrace.mod.lang;

/**
 * String keys for NullTrace UI text. Values are intentionally namespaced like vanilla
 * translation keys but live in a private lookup table (see {@link NullTraceLang}) that
 * bypasses the vanilla {@code Language} map. Server resource packs cannot override
 * these strings because they are never exposed to the vanilla resource manager.
 */
public final class NullTraceStrings {
    private NullTraceStrings() {}

    public static final String CONFIG_TITLE = "nulltrace.config.title";

    public static final String TAB_PROTECTION = "nulltrace.tab.protection";
    public static final String TAB_ACCOUNTS = "nulltrace.tab.accounts";
    public static final String TAB_WHITELIST = "nulltrace.tab.whitelist";
    public static final String TAB_MISC = "nulltrace.tab.misc";

    // Settings-menu section headers (values carry their own §-formatting).
    public static final String SECTION_CLIENT_BRAND = "nulltrace.section.clientBrand";
    public static final String SECTION_RESOURCE_PACK = "nulltrace.section.resourcePack";
    public static final String SECTION_KEY_RESOLUTION = "nulltrace.section.keyResolution";
    public static final String SECTION_PRIVACY = "nulltrace.section.privacy";
    public static final String SECTION_ALERTS = "nulltrace.section.alerts";
    public static final String SECTION_ACCOUNTS = "nulltrace.section.accounts";
    public static final String SECTION_SAVED_ACCOUNTS = "nulltrace.section.savedAccounts";
    public static final String SECTION_ADD_ACCOUNT = "nulltrace.section.addAccount";
    public static final String SECTION_MOD_WHITELIST = "nulltrace.section.modWhitelist";
    public static final String SECTION_INSTALLED_MODS = "nulltrace.section.installedMods";
    public static final String SECTION_RESTART_WARNING = "nulltrace.section.restartWarning";
    public static final String SECTION_DEBUG = "nulltrace.section.debug";
    public static final String ACCOUNT_CURRENT = "nulltrace.account.current";
    public static final String WHITELIST_SUFFIX_CHANNELS = "nulltrace.whitelist.suffix.channels";

    public static final String OPTION_SPOOF_AS_VANILLA = "nulltrace.option.spoofAsVanilla";
    public static final String OPTION_SPOOF_AS_VANILLA_TOOLTIP = "nulltrace.option.spoofAsVanilla.tooltip";

    public static final String OPTION_WHITELIST_MODE = "nulltrace.option.whitelistMode";
    public static final String OPTION_WHITELIST_MODE_LOCKED_TOOLTIP = "nulltrace.option.whitelistMode.locked.tooltip";
    public static final String WHITELIST_SEARCH = "nulltrace.whitelist.search";

    public static final String WHITELIST_MODE_BLOCK_ALL = "nulltrace.whitelist.mode.blockAll";
    public static final String WHITELIST_MODE_AUTO = "nulltrace.whitelist.mode.auto";
    public static final String WHITELIST_MODE_CUSTOM = "nulltrace.whitelist.mode.custom";
    public static final String WHITELIST_MODE_BLOCK_ALL_TOOLTIP = "nulltrace.whitelist.mode.blockAll.tooltip";
    public static final String WHITELIST_MODE_AUTO_TOOLTIP = "nulltrace.whitelist.mode.auto.tooltip";
    public static final String WHITELIST_MODE_CUSTOM_TOOLTIP = "nulltrace.whitelist.mode.custom.tooltip";

    public static final String EP_MANAGED_HEADER = "nulltrace.ep.managed.header";
    public static final String EP_MANAGED_TOOLTIP = "nulltrace.ep.managed.tooltip";

    // Generic "managed by another mod" strings; %s is the managing mod's display name.
    public static final String COMPAT_MANAGED_HEADER = "nulltrace.compat.managed.header";
    public static final String COMPAT_MANAGED_TOOLTIP = "nulltrace.compat.managed.tooltip";

    public static final String OPTION_ISOLATE_PACK_CACHE = "nulltrace.option.isolatePackCache";
    public static final String OPTION_BLOCK_LOCAL_PACK_URLS = "nulltrace.option.blockLocalPackUrls";
    public static final String OPTION_STRIP_MOD_SHADERS = "nulltrace.option.stripModShaders";
    public static final String OPTION_CLEAR_CACHE = "nulltrace.option.clearCache";
    public static final String OPTION_KEY_RESOLUTION_SPOOFING = "nulltrace.option.keyResolutionSpoofing";
    public static final String OPTION_FAKE_DEFAULT_KEYBINDS = "nulltrace.option.fakeDefaultKeybinds";
    public static final String OPTION_METEOR_FIX = "nulltrace.option.meteorFix";
    public static final String OPTION_PACK_STRIP_MODE = "nulltrace.option.packStripMode";

    public static final String PACKSTRIP_MODE_MANUAL = "nulltrace.packstrip.mode.manual";
    public static final String PACKSTRIP_MODE_ASK = "nulltrace.packstrip.mode.ask";
    public static final String PACKSTRIP_MODE_ALWAYS_ON = "nulltrace.packstrip.mode.alwaysOn";
    public static final String PACKSTRIP_MODE_MANUAL_TOOLTIP = "nulltrace.packstrip.mode.manual.tooltip";
    public static final String PACKSTRIP_MODE_ASK_TOOLTIP = "nulltrace.packstrip.mode.ask.tooltip";
    public static final String PACKSTRIP_MODE_ALWAYS_ON_TOOLTIP = "nulltrace.packstrip.mode.alwaysOn.tooltip";

    public static final String PACKSTRIP_TITLE = "nulltrace.packstrip.title";
    public static final String PACKSTRIP_REQUIRED = "nulltrace.packstrip.required";
    public static final String PACKSTRIP_OPTIONAL = "nulltrace.packstrip.optional";
    public static final String PACKSTRIP_STRIPPED = "nulltrace.packstrip.stripped";
    public static final String PACKSTRIP_CONTINUE = "nulltrace.packstrip.continue";
    public static final String PACKSTRIP_LOAD_REAL = "nulltrace.packstrip.loadReal";

    public static final String OPTION_CHAT_SIGNING = "nulltrace.option.chatSigning";
    public static final String OPTION_DISABLE_TELEMETRY = "nulltrace.option.disableTelemetry";
    public static final String OPTION_NORMALIZE_CLIENT_INFORMATION = "nulltrace.option.normalizeClientInformation";
    public static final String OPTION_NORMALIZED_LANGUAGE = "nulltrace.option.normalizedLanguage";
    public static final String OPTION_NORMALIZED_VIEW_DISTANCE = "nulltrace.option.normalizedViewDistance";
    public static final String OPTION_NORMALIZED_CHAT_VISIBILITY = "nulltrace.option.normalizedChatVisibility";
    public static final String OPTION_NORMALIZE_PARTICLES = "nulltrace.option.normalizeParticles";
    public static final String OPTION_SERVER_PROFILE = "nulltrace.option.serverProfile";
    public static final String OPTION_SERVER_PROFILE_TOOLTIP = "nulltrace.option.serverProfile.tooltip";
    public static final String OPTION_USE_PROXY = "nulltrace.option.useProxy";
    public static final String OPTION_USE_PROXY_TOOLTIP = "nulltrace.option.useProxy.tooltip";
    public static final String OPTION_BLOCK_SERVER_COOKIES = "nulltrace.option.blockServerCookies";
    public static final String OPTION_BLOCK_SERVER_COOKIES_TOOLTIP = "nulltrace.option.blockServerCookies.tooltip";
    public static final String OPTION_BLOCK_SERVER_TRANSFERS = "nulltrace.option.blockServerTransfers";
    public static final String OPTION_BLOCK_SERVER_TRANSFERS_TOOLTIP = "nulltrace.option.blockServerTransfers.tooltip";

    public static final String OPTION_SHOW_ALERTS = "nulltrace.option.showAlerts";
    public static final String OPTION_SHOW_TOASTS = "nulltrace.option.showToasts";
    public static final String OPTION_LOG_DETECTIONS = "nulltrace.option.logDetections";
    public static final String OPTION_DEBUG_ALERTS = "nulltrace.option.debugAlerts";
    public static final String OPTION_DEBUG_COMMAND = "nulltrace.option.debugCommand";
    public static final String OPTION_HIDE_INSECURE_INDICATORS = "nulltrace.option.hideInsecureIndicators";
    public static final String OPTION_HIDE_MODIFIED_INDICATORS = "nulltrace.option.hideModifiedIndicators";
    public static final String OPTION_HIDE_SYSTEM_MSG_INDICATORS = "nulltrace.option.hideSystemMsgIndicators";
    public static final String OPTION_HIDE_WARNING_TOAST = "nulltrace.option.hideWarningToast";

    public static final String BRAND_VANILLA = "nulltrace.brand.vanilla";
    public static final String BRAND_FABRIC = "nulltrace.brand.fabric";

    public static final String TRANSLATION_MODE_SPOOF = "nulltrace.translationMode.spoof";
    public static final String TRANSLATION_MODE_BLOCK = "nulltrace.translationMode.block";

    public static final String ACCOUNT_ADD_SESSION = "nulltrace.account.addSession";
    public static final String ACCOUNT_ADD_SESSION_TOOLTIP = "nulltrace.account.addSession.tooltip";
    public static final String ACCOUNT_ADD_OFFLINE = "nulltrace.account.addOffline";
    public static final String ACCOUNT_ADD_OFFLINE_TOOLTIP = "nulltrace.account.addOffline.tooltip";

    public static final String UPDATE_TITLE = "nulltrace.update.title";
    public static final String UPDATE_MESSAGE = "nulltrace.update.message";
    public static final String UPDATE_CURRENT = "nulltrace.update.current";
    public static final String UPDATE_LATEST = "nulltrace.update.latest";
    public static final String UPDATE_DOWNLOAD = "nulltrace.update.download";
    public static final String UPDATE_SKIP = "nulltrace.update.skip";
    public static final String UPDATE_CANCEL = "nulltrace.update.cancel";

    public static final String TAMPER_TITLE = "nulltrace.tamper.title";
    public static final String TAMPER_WARNING = "nulltrace.tamper.warning";
    public static final String TAMPER_MISMATCH = "nulltrace.tamper.mismatch";
    public static final String TAMPER_MALICIOUS = "nulltrace.tamper.malicious";
    public static final String TAMPER_COMPROMISED = "nulltrace.tamper.compromised";
    public static final String TAMPER_ACTION = "nulltrace.tamper.action";
    public static final String TAMPER_EXPECTED = "nulltrace.tamper.expected";
    public static final String TAMPER_ACTUAL = "nulltrace.tamper.actual";
    public static final String TAMPER_DOWNLOAD = "nulltrace.tamper.download";
    public static final String TAMPER_DISMISS_PERMANENT = "nulltrace.tamper.dismiss_permanent";

    // Chat alerts + toast titles (user-visible runtime messages)
    public static final String ALERT_TRACKPACK_PATTERN = "nulltrace.alert.trackpack.pattern";
    public static final String TOAST_TRACKPACK = "nulltrace.toast.trackpack";
    public static final String ALERT_SHADER_STRIP = "nulltrace.alert.shaderStrip";
    public static final String ALERT_SHADER_STRIP_MULTI = "nulltrace.alert.shaderStrip.multi";
    public static final String TOAST_SHADER_STRIP = "nulltrace.toast.shaderStrip";
    public static final String ALERT_PORTSCAN_BLOCKED = "nulltrace.alert.portscan.blocked";
    public static final String ALERT_PORTSCAN_DETECTED = "nulltrace.alert.portscan.detected";
    public static final String TOAST_PORTSCAN = "nulltrace.toast.portscan";
    public static final String ALERT_PORTSCAN_SUMMARY_SINGLE = "nulltrace.alert.portscan.summary.single";
    public static final String ALERT_PORTSCAN_SUMMARY_MULTI = "nulltrace.alert.portscan.summary.multi";
    public static final String ALERT_PORTSCAN_SUMMARY_MORE = "nulltrace.alert.portscan.summary.more";

    // Key-resolution probe alert + toast + one-time hint
    public static final String ALERT_KEYRESOLUTION = "nulltrace.alert.keyresolution";
    public static final String TOAST_KEYRESOLUTION = "nulltrace.toast.keyresolution";
    public static final String HINT_ALERTS_CAN_BE_DISABLED = "nulltrace.hint.alertsCanBeDisabled";

    // /nulltrace command output
    public static final String COMMAND_HELP_HEADER = "nulltrace.command.help.header";
    public static final String COMMAND_HELP_INFO = "nulltrace.command.help.info";
    public static final String COMMAND_HELP_CHANNELS = "nulltrace.command.help.channels";
    public static final String COMMAND_HELP_AUDIT = "nulltrace.command.help.audit";
    public static final String COMMAND_AUDIT_HEADER = "nulltrace.command.audit.header";
    public static final String COMMAND_AUDIT_SERVER = "nulltrace.command.audit.server";
    public static final String COMMAND_AUDIT_PROFILE = "nulltrace.command.audit.profile";
    public static final String COMMAND_AUDIT_BRAND = "nulltrace.command.audit.brand";
    public static final String COMMAND_AUDIT_CHANNELS = "nulltrace.command.audit.channels";
    public static final String COMMAND_AUDIT_KNOWN_PACKS = "nulltrace.command.audit.knownPacks";
    public static final String COMMAND_AUDIT_TRANSLATIONS = "nulltrace.command.audit.translations";
    public static final String COMMAND_AUDIT_RESOURCES = "nulltrace.command.audit.resources";
    public static final String COMMAND_AUDIT_LOCAL = "nulltrace.command.audit.local";
    public static final String COMMAND_AUDIT_SHADERS = "nulltrace.command.audit.shaders";
    public static final String COMMAND_AUDIT_EXPOSURE = "nulltrace.command.audit.exposure";
    public static final String COMMAND_AUDIT_EVENTS = "nulltrace.command.audit.events";
    public static final String COMMAND_AUDIT_REASON = "nulltrace.command.audit.reason";
    public static final String COMMAND_PROFILE_HEADER = "nulltrace.command.profile.header";
    public static final String COMMAND_PROFILE_SET = "nulltrace.command.profile.set";

    public static final String COMMAND_OVERVIEW_HEADER = "nulltrace.command.overview.header";
    public static final String COMMAND_OVERVIEW_TOTAL_MODS = "nulltrace.command.overview.totalMods";
    public static final String COMMAND_OVERVIEW_VANILLA_KEYS = "nulltrace.command.overview.vanillaKeys";
    public static final String COMMAND_OVERVIEW_SERVER_KEYS = "nulltrace.command.overview.serverKeys";
    public static final String COMMAND_OVERVIEW_TOTAL_KEYS = "nulltrace.command.overview.totalKeys";
    public static final String COMMAND_OVERVIEW_TOTAL_KEYBINDS = "nulltrace.command.overview.totalKeybinds";
    public static final String COMMAND_OVERVIEW_TOTAL_SHADERS = "nulltrace.command.overview.totalShaders";
    public static final String COMMAND_OVERVIEW_TOTAL_KNOWN_PACKS = "nulltrace.command.overview.totalKnownPacks";
    public static final String COMMAND_OVERVIEW_MODS_HEADER = "nulltrace.command.overview.modsHeader";
    public static final String COMMAND_OVERVIEW_NO_MODS = "nulltrace.command.overview.noMods";
    public static final String COMMAND_OVERVIEW_USE_INFO = "nulltrace.command.overview.useInfo";

    public static final String COMMAND_INFO_NOT_FOUND = "nulltrace.command.info.notFound";
    public static final String COMMAND_INFO_USE_LIST = "nulltrace.command.info.useList";
    public static final String COMMAND_INFO_HEADER = "nulltrace.command.info.header";
    public static final String COMMAND_INFO_ID = "nulltrace.command.info.id";
    public static final String COMMAND_INFO_TRANSLATION_KEYS = "nulltrace.command.info.translationKeys";
    public static final String COMMAND_INFO_NONE = "nulltrace.command.info.none";
    public static final String COMMAND_INFO_MORE = "nulltrace.command.info.more";
    public static final String COMMAND_INFO_KEYBINDS = "nulltrace.command.info.keybinds";
    public static final String COMMAND_INFO_CHANNELS = "nulltrace.command.info.channels";
    public static final String COMMAND_INFO_SHADERS = "nulltrace.command.info.shaders";
    public static final String COMMAND_INFO_KNOWN_PACKS = "nulltrace.command.info.knownPacks";
    public static final String COMMAND_INFO_JIJ = "nulltrace.command.info.jij";
    public static final String WHITELIST_REQUIRING_FALLBACK = "nulltrace.whitelist.requiringFallback";
    public static final String WHITELIST_SUFFIX_REQUIRED = "nulltrace.whitelist.suffix.required";
    public static final String WHITELIST_TOOLTIP_REQUIRED_BY = "nulltrace.whitelist.tooltip.requiredBy";
    public static final String COMMAND_INFO_STATUS_ALLOWED_AUTO = "nulltrace.command.info.statusAllowedAuto";
    public static final String COMMAND_INFO_STATUS_BLOCKED_AUTO = "nulltrace.command.info.statusBlockedAuto";
    public static final String COMMAND_INFO_STATUS_ALLOWED_CUSTOM = "nulltrace.command.info.statusAllowedCustom";
    public static final String COMMAND_INFO_STATUS_ALLOWED_DEP = "nulltrace.command.info.statusAllowedDep";
    public static final String COMMAND_INFO_STATUS_BLOCKED_CUSTOM = "nulltrace.command.info.statusBlockedCustom";
    public static final String COMMAND_INFO_STATUS_BLOCKED_OFF = "nulltrace.command.info.statusBlockedOff";

    public static final String COMMAND_CHANNELS_HEADER = "nulltrace.command.channels.header";
    public static final String COMMAND_CHANNELS_NONE = "nulltrace.command.channels.none";
    public static final String COMMAND_CHANNELS_TOTAL = "nulltrace.command.channels.total";
    public static final String COMMAND_CHANNELS_LEGEND = "nulltrace.command.channels.legend";

    public static final String COMMAND_MODENTRY_KEYS = "nulltrace.command.modEntry.keys";
    public static final String COMMAND_MODENTRY_KEYBINDS = "nulltrace.command.modEntry.keybinds";
    public static final String COMMAND_MODENTRY_CHANNELS = "nulltrace.command.modEntry.channels";
    public static final String COMMAND_MODENTRY_SHADERS = "nulltrace.command.modEntry.shaders";
    public static final String COMMAND_MODENTRY_KNOWN_PACKS = "nulltrace.command.modEntry.knownPacks";

    // AddAccountScreen (session token)
    public static final String ACCOUNT_SCREEN_SESSION_TITLE = "nulltrace.account.screen.session.title";
    public static final String ACCOUNT_SCREEN_SESSION_LABEL = "nulltrace.account.screen.sessionLabel";
    public static final String ACCOUNT_SCREEN_SESSION_HINT = "nulltrace.account.screen.sessionHint";
    public static final String ACCOUNT_SCREEN_REFRESH_LABEL = "nulltrace.account.screen.refreshLabel";
    public static final String ACCOUNT_SCREEN_REFRESH_HINT = "nulltrace.account.screen.refreshHint";
    public static final String ACCOUNT_SCREEN_ADD_BUTTON = "nulltrace.account.screen.addButton";
    public static final String ACCOUNT_SCREEN_CANCEL_BUTTON = "nulltrace.account.screen.cancelButton";
    public static final String ACCOUNT_ERROR_EMPTY_TOKEN = "nulltrace.account.error.emptyToken";
    public static final String ACCOUNT_STATUS_VALIDATING = "nulltrace.account.status.validating";
    public static final String ACCOUNT_SUCCESS_ADDED = "nulltrace.account.success.added";
    public static final String ACCOUNT_SUCCESS_REFRESH_SUFFIX = "nulltrace.account.success.refreshSuffix";
    public static final String ACCOUNT_ERROR_INVALID_TOKEN = "nulltrace.account.error.invalidToken";

    // AddCrackedAccountScreen (offline)
    public static final String ACCOUNT_SCREEN_OFFLINE_TITLE = "nulltrace.account.screen.offline.title";
    public static final String ACCOUNT_SCREEN_USERNAME_LABEL = "nulltrace.account.screen.usernameLabel";
    public static final String ACCOUNT_SCREEN_USERNAME_HINT = "nulltrace.account.screen.usernameHint";
    public static final String ACCOUNT_ERROR_EMPTY_USERNAME = "nulltrace.account.error.emptyUsername";
    public static final String ACCOUNT_STATUS_ADDING = "nulltrace.account.status.adding";
    public static final String ACCOUNT_SUCCESS_ADDED_OFFLINE = "nulltrace.account.success.addedOffline";
    public static final String ACCOUNT_ERROR_FAILED_ADD = "nulltrace.account.error.failedToAdd";

    // Multiplayer-screen NullTrace button
    public static final String BUTTON_SETTINGS_TOOLTIP = "nulltrace.button.settings.tooltip";

    // Config screen tooltips and section buttons
    public static final String BUTTON_RESET_TOOLTIP = "nulltrace.button.reset.tooltip";
    public static final String BUTTON_RESET_DISABLED_TOOLTIP = "nulltrace.button.reset.disabled.tooltip";
    public static final String VERSION_TOOLTIP_OUTDATED = "nulltrace.version.tooltip.outdated";
    public static final String VERSION_TOOLTIP_UPTODATE = "nulltrace.version.tooltip.upToDate";

    public static final String TOOLTIP_ISOLATE_PACK_CACHE = "nulltrace.option.isolatePackCache.tooltip";
    public static final String TOOLTIP_BLOCK_LOCAL_PACK_URLS = "nulltrace.option.blockLocalPackUrls.tooltip";
    public static final String TOOLTIP_STRIP_MOD_SHADERS = "nulltrace.option.stripModShaders.tooltip";
    public static final String TOOLTIP_CLEAR_CACHE = "nulltrace.option.clearCache.tooltip";
    public static final String TOOLTIP_KEY_RESOLUTION_SPOOFING = "nulltrace.option.keyResolutionSpoofing.tooltip";
    public static final String TOOLTIP_FAKE_DEFAULT_KEYBINDS = "nulltrace.option.fakeDefaultKeybinds.tooltip";
    public static final String TOOLTIP_METEOR_FIX = "nulltrace.option.meteorFix.tooltip";
    public static final String TOOLTIP_DISABLE_TELEMETRY = "nulltrace.option.disableTelemetry.tooltip";
    public static final String TOOLTIP_SHOW_ALERTS = "nulltrace.option.showAlerts.tooltip";
    public static final String TOOLTIP_SHOW_TOASTS = "nulltrace.option.showToasts.tooltip";
    public static final String TOOLTIP_LOG_DETECTIONS = "nulltrace.option.logDetections.tooltip";
    public static final String TOOLTIP_DEBUG_ALERTS = "nulltrace.option.debugAlerts.tooltip";
    public static final String TOOLTIP_DEBUG_COMMAND = "nulltrace.option.debugCommand.tooltip";

    // Account-management buttons
    public static final String BUTTON_REFRESH_ALL = "nulltrace.button.refreshAll";
    public static final String BUTTON_REFRESHING = "nulltrace.button.refreshing";
    public static final String BUTTON_REFRESH_TOOLTIP = "nulltrace.button.refresh.tooltip";
    public static final String BUTTON_REMOVE = "nulltrace.button.remove";
    public static final String BUTTON_REMOVE_TOOLTIP = "nulltrace.button.remove.tooltip";
    public static final String BUTTON_IMPORT = "nulltrace.button.import";
    public static final String BUTTON_IMPORT_TOOLTIP = "nulltrace.button.import.tooltip";
    public static final String BUTTON_EXPORT = "nulltrace.button.export";
    public static final String BUTTON_EXPORT_TOOLTIP = "nulltrace.button.export.tooltip";

    // Whitelist tab bulk-action buttons
    public static final String BUTTON_ENABLE_ALL = "nulltrace.button.enableAll";
    public static final String BUTTON_ENABLE_ALL_TOOLTIP = "nulltrace.button.enableAll.tooltip";
    public static final String BUTTON_DISABLE_ALL = "nulltrace.button.disableAll";
    public static final String BUTTON_DISABLE_ALL_TOOLTIP = "nulltrace.button.disableAll.tooltip";

    // Chat-signing mode tooltips
    public static final String CHATSIGNING_SIGN_TOOLTIP = "nulltrace.chatSigning.sign.tooltip";
    public static final String CHATSIGNING_OFF_TOOLTIP = "nulltrace.chatSigning.off.tooltip";
}
