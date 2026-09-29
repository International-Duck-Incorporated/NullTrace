package net.nulltrace.mod.privacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

/** A small blocking consent screen shown before a connection with exposure. */
public final class PrivacyWarningScreen extends Screen {
    private final Screen parent;
    private final Minecraft client;
    private final ServerAddress address;
    private final ServerData serverData;
    private final boolean quickPlay;
    private final net.minecraft.client.multiplayer.TransferState transferState;
    private final ConnectionWarning.Summary summary;

    public PrivacyWarningScreen(Screen parent, Minecraft client, ServerAddress address,
                                ServerData serverData, boolean quickPlay,
                                net.minecraft.client.multiplayer.TransferState transferState,
                                ConnectionWarning.Summary summary) {
        super(Component.literal("NullTrace privacy warning"));
        this.parent = parent;
        this.client = client;
        this.address = address;
        this.serverData = serverData;
        this.quickPlay = quickPlay;
        this.transferState = transferState;
        this.summary = summary;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        addLabel(Component.literal("Privacy exposure detected").withStyle(net.minecraft.ChatFormatting.YELLOW), 36);
        addLabel(Component.literal("Server: " + summary.server()), 60);
        addLabel(Component.literal(summary.installedMods() + " installed mods are potentially identifiable"), 78);
        addLabel(Component.literal(summary.exposedChannels() + " network channels will be exposed"), 96);
        addLabel(Component.literal(summary.probes() + " fingerprint surfaces are tracked"), 114);
        addLabel(Component.literal("Remaining exposure: " + summary.remainingExposure()), 132);

        Button cancel = Button.builder(Component.literal("Cancel"), button -> {
            //? if >=26.2 {
            client.setScreenAndShow(parent);
            //?} else {
            /*client.setScreen(parent);
            *///?}
        }).width(120).build();
        cancel.setX(center - 125);
        cancel.setY(this.height - 45);
        addRenderableWidget(cancel);

        Button connect = Button.builder(Component.literal("Connect Anyway"), button -> {
            ConnectionWarning.allowNextConnection();
            ConnectScreen.startConnecting(parent, client, address, serverData, quickPlay, transferState);
        }).width(130).build();
        connect.setX(center + 5);
        connect.setY(this.height - 45);
        addRenderableWidget(connect);
    }

    private void addLabel(Component text, int y) {
        StringWidget label = new StringWidget(this.width - 40, 18, text, this.font);
        label.setX(20);
        label.setY(y);
        addRenderableWidget(label);
    }
}
