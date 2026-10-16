package net.nulltrace.mod.config;

import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.nulltrace.mod.proxy.ProxyEntry;
import net.nulltrace.mod.proxy.ProxySettings;
import net.nulltrace.mod.proxy.ProxyTester;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Manage the saved proxy list: paste one in, select the active one, order and remove them,
 * and probe the selected endpoint for reachability.
 *
 * <p>Layout is deliberately plain (text widgets plus buttons, no custom rendering) so it
 * behaves identically across every target; the screen is rebuilt after a mutation, which is
 * why the in-progress text of the input box is carried through {@code prefill} —
 * retyping an endpoint after pressing "Use" on an unrelated row would be obnoxious.
 */
public class ProxyScreen extends Screen {
    private static final int PER_PAGE = 5;
    private static final int ROW_HEIGHT = 22;
    private static final int ROW_WIDTH = 302;
    private static final int GAP = 4;
    private static final int ROW_TOP = 70;

    private final Screen parent;
    private final String prefill;
    private final int page;
    private final Component status;

    private EditBox proxyInput;
    private StringWidget statusLabel;

    public ProxyScreen(Screen parent) {
        this(parent, "", 0, Component.empty());
    }

    public ProxyScreen(Screen parent, String prefill, int page, Component status) {
        super(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_TITLE));
        this.parent = parent;
        this.prefill = prefill == null ? "" : prefill;
        this.page = Math.max(0, page);
        this.status = status == null ? Component.empty() : status;
    }

    @Override
    protected void init() {
        ProxySettings proxy = NullTraceConfig.getInstance().proxySettings();
        List<ProxyEntry> entries = proxy.entries();

        int cx = this.width / 2;
        int rowLeft = cx - ROW_WIDTH / 2;

        addCenteredText(this.title, 10);

        // One line doing double duty: the hint while there is nothing to report, the
        // last result afterwards. Keeps a status message visible without stealing rows.
        if (this.status.getString().isEmpty()) {
            addCenteredText(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_HINT), 26);
        } else {
            addCenteredText(this.status, 26);
        }

        // Paste box + add
        this.proxyInput = new EditBox(this.font, rowLeft, 42, 220, 20,
                NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_INPUT_LABEL));
        this.proxyInput.setMaxLength(320);
        this.proxyInput.setHint(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_INPUT_HINT));
        this.proxyInput.setValue(this.prefill);
        this.addRenderableWidget(this.proxyInput);

        Button addButton = Button.builder(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_ADD),
                        button -> addFromInput(proxy))
                .bounds(rowLeft + 226, 42, 76, 20)
                .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_ADD_TOOLTIP)))
                .build();
        this.addRenderableWidget(addButton);

        if (entries.isEmpty()) {
            addCenteredText(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_EMPTY), ROW_TOP + 20);
        } else {
            addRows(proxy, entries, rowLeft);
        }

        addFooter(proxy, entries, cx);
        this.setInitialFocus(this.proxyInput);
    }

    private void addRows(ProxySettings proxy, List<ProxyEntry> entries, int rowLeft) {
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int current = Math.min(this.page, pages - 1);
        int start = current * PER_PAGE;
        int end = Math.min(start + PER_PAGE, entries.size());

        for (int i = start; i < end; i++) {
            ProxyEntry entry = entries.get(i);
            int y = ROW_TOP + (i - start) * ROW_HEIGHT;
            boolean selected = entry.id().equals(proxy.selectedId());

            Button useButton = Button.builder(
                            NullTraceLang.component(selected ? NullTraceStrings.PROXY_SCREEN_IN_USE
                                    : NullTraceStrings.PROXY_SCREEN_USE),
                            button -> {
                                proxy.select(entry);
                                NullTraceConfig.getInstance().save();
                                reopen(NullTraceLang.tr(NullTraceStrings.PROXY_STATUS_USING, entry.display()));
                            })
                    .bounds(rowLeft, y, 76, 20)
                    .build();
            useButton.active = !selected;
            this.addRenderableWidget(useButton);

            int labelWidth = this.font.width(entry.display());
            StringWidget label = new StringWidget(labelWidth, 20,
                    Component.literal((selected ? "\u00A7a" : "\u00A77") + entry.display()), this.font);
            label.setX(rowLeft + 80);
            label.setY(y + 6);
            this.addRenderableWidget(label);

            int controlsX = rowLeft + ROW_WIDTH - 3 * (20 + GAP);
            this.addRenderableWidget(Button.builder(Component.literal("\u2191"),
                            button -> move(proxy, entry, -1))
                    .bounds(controlsX, y, 20, 20)
                    .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_MOVE_UP_TOOLTIP)))
                    .build());
            this.addRenderableWidget(Button.builder(Component.literal("\u2193"),
                            button -> move(proxy, entry, 1))
                    .bounds(controlsX + 24, y, 20, 20)
                    .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_MOVE_DOWN_TOOLTIP)))
                    .build());
            this.addRenderableWidget(Button.builder(Component.literal("\u00D7"),
                            button -> {
                                proxy.remove(entry);
                                NullTraceConfig.getInstance().save();
                                reopen(NullTraceLang.tr(NullTraceStrings.PROXY_STATUS_REMOVED, entry.display()));
                            })
                    .bounds(controlsX + 48, y, 20, 20)
                    .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_REMOVE_TOOLTIP)))
                    .build());
        }

        if (pages > 1) {
            int y = ROW_TOP + PER_PAGE * ROW_HEIGHT + 2;
            Button prev = Button.builder(Component.literal("\u25C0"), button ->
                            reopen("", Math.max(0, current - 1), this.status))
                    .bounds(this.width / 2 - 44, y, 20, 20)
                    .build();
            prev.active = current > 0;
            this.addRenderableWidget(prev);

            String pageText = NullTraceLang.tr(NullTraceStrings.PROXY_SCREEN_PAGE, current + 1, pages);
            int pageWidth = this.font.width(pageText);
            StringWidget pageLabel = new StringWidget(pageWidth, 20,
                    Component.literal("\u00A77" + pageText), this.font);
            pageLabel.setX(this.width / 2 - pageWidth / 2);
            pageLabel.setY(y + 6);
            this.addRenderableWidget(pageLabel);

            Button next = Button.builder(Component.literal("\u25B6"), button ->
                            reopen("", current + 1, this.status))
                    .bounds(this.width / 2 + 24, y, 20, 20)
                    .build();
            next.active = current < pages - 1;
            this.addRenderableWidget(next);
        }
    }

    private void addFooter(ProxySettings proxy, List<ProxyEntry> entries, int cx) {
        int y = ROW_TOP + PER_PAGE * ROW_HEIGHT + (entries.size() > PER_PAGE ? 24 : 0) + 6;

        Button test = Button.builder(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_TEST),
                        button -> testSelected(proxy))
                .bounds(cx - 104, y, 100, 20)
                .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_TEST_TOOLTIP)))
                .build();
        test.active = proxy.selected() != null;
        this.addRenderableWidget(test);

        this.addRenderableWidget(Button.builder(NullTraceLang.component(NullTraceStrings.PROXY_SCREEN_BACK),
                        button -> this.onClose())
                .bounds(cx + 4, y, 100, 20)
                .build());
    }

    // ------------------------------------------------------------------ actions

    private void addFromInput(ProxySettings proxy) {
        String raw = this.proxyInput.getValue();
        ProxyEntry.ParseResult result = ProxyEntry.parse(raw);
        if (!result.ok()) {
            // Keep what the user typed so they can fix it in place.
            reopen(raw, this.page, NullTraceLang.component(result.errorKey()));
            return;
        }
        ProxyEntry entry = result.entry();
        if (!proxy.add(entry)) {
            reopen("", this.page, NullTraceLang.component(NullTraceStrings.PROXY_STATUS_EXISTS, entry.display()));
            return;
        }
        if (proxy.mode() != ProxySettings.Mode.SELECTED) {
            // Adding an endpoint to use it, then having to find the mode switch, is a
            // needless second step — but it is announced rather than done silently.
            proxy.setMode(ProxySettings.Mode.SELECTED);
            NullTraceConfig.getInstance().save();
            reopen("", this.page, NullTraceLang.component(NullTraceStrings.PROXY_STATUS_ADDED_SELECTED, entry.display()));
            return;
        }
        NullTraceConfig.getInstance().save();
        reopen("", this.page, NullTraceLang.component(NullTraceStrings.PROXY_STATUS_ADDED, entry.display()));
    }

    private void move(ProxySettings proxy, ProxyEntry entry, int delta) {
        proxy.move(entry, delta);
        NullTraceConfig.getInstance().save();
        reopen("", this.page, this.status);
    }

    private void testSelected(ProxySettings proxy) {
        ProxyEntry entry = proxy.selected();
        if (entry == null) {
            showStatus(NullTraceLang.component(NullTraceStrings.PROXY_STATUS_TEST_NO_SELECTION));
            return;
        }
        showStatus(NullTraceLang.component(NullTraceStrings.PROXY_STATUS_TESTING, entry.display()));
        ProxyTester.test(entry).thenAccept(result -> Minecraft.getInstance().execute(() -> {
            if (result.ok()) {
                showStatus(NullTraceLang.component(NullTraceStrings.PROXY_STATUS_TEST_OK,
                        result.latencyMs(), result.detail()));
            } else {
                showStatus(NullTraceLang.component(NullTraceStrings.PROXY_STATUS_TEST_FAIL,
                        result.detail() == null ? "unknown error" : result.detail()));
            }
        }));
    }

    private void showStatus(Component message) {
        if (this.statusLabel != null) {
            this.statusLabel.setMessage(message);
        }
    }

    /** Rebuilds the screen, carrying the input text, page and a new status line. */
    private void reopen(String prefill, int page, Component status) {
        //? if >=26.2 {
        this.minecraft.setScreenAndShow(new ProxyScreen(this.parent, prefill, page, status));
        //?} else {
        /*this.minecraft.setScreen(new ProxyScreen(this.parent, prefill, page, status));
        *///?}
    }

    private void reopen(String statusText) {
        reopen("", this.page, Component.literal(statusText));
    }

    @Override
    public void onClose() {
        // Back to the Advanced tab, where the proxy mode and this list live.
        //? if >=26.2 {
        this.minecraft.setScreenAndShow(new NullTraceConfigScreen(this.parent,
                NullTraceConfigScreen.TAB_ADVANCED, 0));
        //?} else if >=1.21.6 {
        /*this.minecraft.setScreen(new NullTraceConfigScreen(this.parent,
                NullTraceConfigScreen.TAB_ADVANCED, 0));
        *///?} else {
        /*this.minecraft.setScreen(new NullTraceConfigScreen(this.parent,
                NullTraceConfigScreen.TAB_ADVANCED));
        *///?}
    }

    private void addCenteredText(Component text, int y) {
        int textWidth = this.font.width(text);
        StringWidget widget = new StringWidget(textWidth, 12, text, this.font);
        widget.setX(this.width / 2 - textWidth / 2);
        widget.setY(y);
        this.addRenderableWidget(widget);
        if (this.statusLabel == null && !this.status.getString().isEmpty() && y == 26) {
            this.statusLabel = widget;
        }
    }
}
