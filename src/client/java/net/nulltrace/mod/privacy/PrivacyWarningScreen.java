package net.nulltrace.mod.privacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.nulltrace.mod.config.NullTraceConfig;
import net.nulltrace.mod.config.NullTraceConfigScreen;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;

import java.util.ArrayList;
import java.util.List;

/**
 * Pre-connection consent screen.
 *
 * <p>Design rules this screen follows (see {@code docs/design/privacy-warning-redesign.md}):
 *
 * <ul>
 *   <li><b>Verdict first.</b> A player reads one sentence and knows whether anything about
 *       them is visible to this server.</li>
 *   <li><b>Plain language over counters.</b> Each row says what the server sees in words
 *       ("not sent", "blocked", "3 of 12 sent"). Exact counts live in the details panel.</li>
 *   <li><b>Two equal paths.</b> "Connect" and "Protect &amp; connect" are both one click;
 *       neither is framed as the wrong choice, and nothing is sent before either.</li>
 *   <li><b>Nothing is a dead end.</b> Settings, details, and a per-server warning mute are
 *       reachable from here.</li>
 * </ul>
 *
 * <p>All text is rendered through {@link StringWidget} with {@link NullTraceLang} lookups:
 * server resource packs cannot rewrite this screen, and non-English players get the keys
 * the bundled locales ship.
 */
public final class PrivacyWarningScreen extends Screen {
    private static final int LINE = 12;
    private static final int MAX_CONTENT_WIDTH = 420;
    private static final int BUTTON_GAP = 6;
    private static final int MIN_BUTTON_WIDTH = 56;
    /** Vertical space reserved for the two button rows, kept clear of the content block. */
    private static final int BUTTON_SPACE = 84;

    private final Screen parent;
    private final Minecraft client;
    private final ServerAddress address;
    private final ServerData serverData;
    private final boolean quickPlay;
    private final net.minecraft.client.multiplayer.TransferState transferState;
    private final ConnectionWarning.Summary summary;
    private final boolean detailsExpanded;

    public PrivacyWarningScreen(Screen parent, Minecraft client, ServerAddress address,
                                ServerData serverData, boolean quickPlay,
                                net.minecraft.client.multiplayer.TransferState transferState,
                                ConnectionWarning.Summary summary) {
        this(parent, client, address, serverData, quickPlay, transferState, summary, false);
    }

    public PrivacyWarningScreen(Screen parent, Minecraft client, ServerAddress address,
                                ServerData serverData, boolean quickPlay,
                                net.minecraft.client.multiplayer.TransferState transferState,
                                ConnectionWarning.Summary summary, boolean detailsExpanded) {
        super(NullTraceLang.component(NullTraceStrings.WARNING_TITLE));
        this.parent = parent;
        this.client = client;
        this.address = address;
        this.serverData = serverData;
        this.quickPlay = quickPlay;
        this.transferState = transferState;
        this.summary = summary;
        this.detailsExpanded = detailsExpanded;
    }

    @Override
    protected void init() {
        ConnectionWarning.Severity severity = summary.severity();

        int contentWidth = Math.min(this.width - 40, MAX_CONTENT_WIDTH);
        int left = (this.width - contentWidth) / 2;

        int rows = ConnectionWarning.Area.values().length;
        String[] details = detailsExpanded ? detailsLines() : new String[0];
        int contentHeight = LINE * 2 + 6 + LINE + 10 + rows * LINE
                + (detailsExpanded ? 8 + LINE + details.length * LINE : 0);

        int y = Math.max(14, (this.height - contentHeight - BUTTON_SPACE) / 2);

        // Verdict first: one sentence a player can act on.
        addText(NullTraceLang.tr(severityKey(severity)), left, y);
        y += LINE;
        addText(NullTraceLang.tr(NullTraceStrings.WARNING_SERVER, summary.server()), left, y);
        y += LINE + 6;
        addText(NullTraceLang.tr(severity == ConnectionWarning.Severity.OK
                ? NullTraceStrings.WARNING_REASSURANCE_OK : NullTraceStrings.WARNING_REASSURANCE), left, y);
        y += LINE + 10;

        // What the server can see, one line per area.
        for (ConnectionWarning.Area area : ConnectionWarning.Area.values()) {
            addText(NullTraceLang.tr(areaLabelKey(area)), left + 6, y);
            String status = statusText(area, summary.isVisible(area));
            addText(status, left + contentWidth - 6 - this.font.width(status), y);
            y += LINE;
        }

        if (detailsExpanded) {
            y += 8;
            addText(NullTraceLang.tr(NullTraceStrings.WARNING_DETAILS_HEADER), left + 6, y);
            y += LINE;
            for (String line : details) {
                addText(line, left + 12, y);
                y += LINE;
            }
        }

        layoutActions(severity);
    }

    // ------------------------------------------------------------------ actions

    private void layoutActions(ConnectionWarning.Severity severity) {
        int mainY = this.height - 66;
        int secondY = this.height - 40;
        boolean allClear = severity == ConnectionWarning.Severity.OK;

        List<Button> main = new ArrayList<>();
        main.add(button(NullTraceStrings.WARNING_BUTTON_BACK, NullTraceStrings.WARNING_BUTTON_BACK_TOOLTIP,
                pressed -> this.onClose()));
        main.add(button(NullTraceStrings.WARNING_BUTTON_CONNECT, NullTraceStrings.WARNING_BUTTON_CONNECT_TOOLTIP,
                pressed -> connect(false)));
        if (!allClear) {
            main.add(button(NullTraceStrings.WARNING_BUTTON_PROTECT, NullTraceStrings.WARNING_BUTTON_PROTECT_TOOLTIP,
                    pressed -> connect(true)));
        }
        layoutRow(mainY, main);

        NullTraceConfig config = NullTraceConfig.getInstance();
        boolean muted = config.getSettings().isWarningMuted(summary.serverKey());

        List<Button> secondary = new ArrayList<>();
        secondary.add(button(detailsExpanded ? NullTraceStrings.WARNING_BUTTON_DETAILS_HIDE
                        : NullTraceStrings.WARNING_BUTTON_DETAILS_SHOW,
                NullTraceStrings.WARNING_BUTTON_DETAILS_TOOLTIP,
                pressed -> reopen(!detailsExpanded)));
        secondary.add(button(muted ? NullTraceStrings.WARNING_BUTTON_MUTE_OFF
                        : NullTraceStrings.WARNING_BUTTON_MUTE_ON,
                NullTraceStrings.WARNING_BUTTON_MUTE_TOOLTIP,
                pressed -> {
                    config.getSettings().setWarningMuted(summary.serverKey(), !muted);
                    config.save();
                    reopen(detailsExpanded);
                }));
        // Settings is the first thing dropped on a cramped screen: it is reachable from the
        // multiplayer screen anyway, while details and the mute toggle are only useful here.
        if (rowWidth(secondary, 16) + 12 <= this.width) {
            secondary.add(0, button(NullTraceStrings.WARNING_BUTTON_SETTINGS,
                    NullTraceStrings.WARNING_BUTTON_SETTINGS_TOOLTIP,
                    pressed -> openSettings()));
        }
        layoutRow(secondY, secondary);
    }

    private void connect(boolean protectFirst) {
        if (protectFirst) {
            NullTraceConfig.getInstance().setProfileMode(summary.serverKey(), ServerProfile.Mode.STRICT);
            PrivacyAudit.record(PrivacySurface.PROTOCOL_BEHAVIOR, summary.server(),
                    PrivacyAction.BLOCK, "Strict server profile applied from the pre-connection warning");
        }
        ConnectionWarning.allowNextConnection();
        ConnectScreen.startConnecting(parent, client, address, serverData, quickPlay, transferState);
    }

    /** Opens the config screen on the Protection tab, editing this server's profile. */
    private void openSettings() {
        NullTraceConfig config = NullTraceConfig.getInstance();
        // The profile editor targets "the current server", so point it here first.
        config.setCurrentServer(summary.serverKey());
        //? if >=26.2 {
        this.client.setScreenAndShow(new NullTraceConfigScreen(this, 0));
        //?} else {
        /*this.client.setScreen(new NullTraceConfigScreen(this, 0));
        *///?}
    }

    /** Re-opens this screen with a new local UI state (expanded details / changed mute). */
    private void reopen(boolean expanded) {
        PrivacyWarningScreen next = new PrivacyWarningScreen(parent, client, address, serverData,
                quickPlay, transferState, summary, expanded);
        //? if >=26.2 {
        this.client.setScreenAndShow(next);
        //?} else {
        /*this.client.setScreen(next);
        *///?}
    }

    @Override
    public void onClose() {
        //? if >=26.2 {
        this.client.setScreenAndShow(parent);
        //?} else {
        /*this.client.setScreen(parent);
        *///?}
    }

    // ------------------------------------------------------------------ text

    private static String severityKey(ConnectionWarning.Severity severity) {
        switch (severity) {
            case EXPOSED: return NullTraceStrings.WARNING_HEADLINE_EXPOSED;
            case NOTICE: return NullTraceStrings.WARNING_HEADLINE_NOTICE;
            default: return NullTraceStrings.WARNING_HEADLINE_OK;
        }
    }

    private static String areaLabelKey(ConnectionWarning.Area area) {
        switch (area) {
            case CLIENT_BRAND: return NullTraceStrings.WARNING_AREA_BRAND;
            case MOD_CHANNELS: return NullTraceStrings.WARNING_AREA_CHANNELS;
            case PACK_IDENTIFIERS: return NullTraceStrings.WARNING_AREA_PACK_IDS;
            case TEXT_PROBES: return NullTraceStrings.WARNING_AREA_TEXT_PROBES;
            case CLIENT_INFO: return NullTraceStrings.WARNING_AREA_CLIENT_INFO;
            case PACK_REQUESTS: return NullTraceStrings.WARNING_AREA_PACK_REQUESTS;
            default: return NullTraceStrings.WARNING_AREA_TRACKING;
        }
    }

    /** One short verdict per row. Counts are passed only where the copy uses them. */
    private String statusText(ConnectionWarning.Area area, boolean visible) {
        switch (area) {
            case CLIENT_BRAND:
                // "protected" for the brand means replaced, not omitted.
                if (!visible && summary.spoofedBrand() != null) {
                    return NullTraceLang.tr(NullTraceStrings.WARNING_STATUS_BRAND_SPOOFED,
                            summary.spoofedBrand());
                }
                return NullTraceLang.tr(visible ? NullTraceStrings.WARNING_STATUS_BRAND_VISIBLE
                        : NullTraceStrings.WARNING_STATUS_BRAND_HIDDEN);
            case MOD_CHANNELS:
                return NullTraceLang.tr(visible ? NullTraceStrings.WARNING_STATUS_CHANNELS_VISIBLE
                                : NullTraceStrings.WARNING_STATUS_CHANNELS_HIDDEN,
                        summary.exposedChannels(), summary.channelCount());
            case TEXT_PROBES:
                return NullTraceLang.tr(visible ? NullTraceStrings.WARNING_STATUS_PROBES_VISIBLE
                                : NullTraceStrings.WARNING_STATUS_PROBES_HIDDEN,
                        summary.translationKeys() + summary.keybindCount());
            default:
                return NullTraceLang.tr(visible ? statusVisibleKey(area) : statusHiddenKey(area));
        }
    }

    private static String statusVisibleKey(ConnectionWarning.Area area) {
        switch (area) {
            case CLIENT_BRAND: return NullTraceStrings.WARNING_STATUS_BRAND_VISIBLE;
            case PACK_IDENTIFIERS: return NullTraceStrings.WARNING_STATUS_PACK_IDS_VISIBLE;
            case CLIENT_INFO: return NullTraceStrings.WARNING_STATUS_CLIENT_INFO_VISIBLE;
            case PACK_REQUESTS: return NullTraceStrings.WARNING_STATUS_PACK_REQUESTS_VISIBLE;
            default: return NullTraceStrings.WARNING_STATUS_TRACKING_VISIBLE;
        }
    }

    private static String statusHiddenKey(ConnectionWarning.Area area) {
        switch (area) {
            case CLIENT_BRAND: return NullTraceStrings.WARNING_STATUS_BRAND_HIDDEN;
            case PACK_IDENTIFIERS: return NullTraceStrings.WARNING_STATUS_PACK_IDS_HIDDEN;
            case CLIENT_INFO: return NullTraceStrings.WARNING_STATUS_CLIENT_INFO_HIDDEN;
            case PACK_REQUESTS: return NullTraceStrings.WARNING_STATUS_PACK_REQUESTS_HIDDEN;
            default: return NullTraceStrings.WARNING_STATUS_TRACKING_HIDDEN;
        }
    }

    /** The counters the old screen showed up front now live here, on request. */
    private String[] detailsLines() {
        return new String[] {
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_MODS, summary.installedMods()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_CHANNELS,
                        summary.exposedChannels(), summary.channelCount()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_KEYS, summary.translationKeys()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_KEYBINDS, summary.keybindCount()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_PACK_IDS, summary.knownPackCount()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_SHADERS, summary.shaderCount()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_EXPOSURE, summary.visibleAreaCount()),
                NullTraceLang.tr(NullTraceStrings.WARNING_DETAIL_AUDIT)
        };
    }

    // ------------------------------------------------------------------ layout

    private void addText(String text, int x, int y) {
        StringWidget widget = new StringWidget(this.font.width(text), 12,
                Component.literal(text), this.font);
        widget.setX(x);
        widget.setY(y);
        addRenderableWidget(widget);
    }

    private Button button(String labelKey, String tooltipKey, Button.OnPress action) {
        return Button.builder(NullTraceLang.component(labelKey), action)
                .tooltip(Tooltip.create(NullTraceLang.component(tooltipKey)))
                .build();
    }

    /** Centers a row of buttons, tightening the label padding until the row fits the screen. */
    private void layoutRow(int y, List<Button> buttons) {
        int padding = 16;
        int total = rowWidth(buttons, padding);
        while (padding > 4 && total + 12 > this.width) {
            padding -= 4;
            total = rowWidth(buttons, padding);
        }
        int x = (this.width - total) / 2;
        for (Button button : buttons) {
            int width = Math.max(MIN_BUTTON_WIDTH, this.font.width(button.getMessage()) + padding);
            button.setWidth(width);
            button.setX(x);
            button.setY(y);
            x += width + BUTTON_GAP;
            addRenderableWidget(button);
        }
    }

    private int rowWidth(List<Button> buttons, int padding) {
        int total = 0;
        for (Button button : buttons) {
            total += Math.max(MIN_BUTTON_WIDTH, this.font.width(button.getMessage()) + padding) + BUTTON_GAP;
        }
        return Math.max(0, total - BUTTON_GAP);
    }
}
