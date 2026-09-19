package net.nulltrace.mod.mixin.client;

import net.nulltrace.mod.config.NullTraceConfigScreen;
import net.nulltrace.mod.config.UpdateChecker;
import net.nulltrace.mod.config.UpdateScreen;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an NullTrace settings button to the multiplayer server list screen.
 * Aligned with the list's left edge, or its right edge when Wurst is installed
 * (Wurst's own "Last Server" button occupies the top-left header slot).
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {

    @Unique private static final int BUTTON_HEIGHT = 20;
    @Unique private static final int BUTTON_PADDING = 8;
    /** Half of ServerSelectionList.getRowWidth() (305), used to align with the list's left edge. */
    @Unique private static final int SERVER_LIST_HALF_ROW_WIDTH = 152;
    /** Wurst parks its "Last Server" button at the top-left header slot; right-align to dodge it. */
    @Unique private static final boolean WURST_LOADED = FabricLoader.getInstance().isModLoaded("wurst");
    
    @Unique private Button nulltrace$settingsButton;
    
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }
    
    @Inject(method = "init", at = @At("TAIL"))
    private void nulltrace$addSettingsButton(CallbackInfo ci) {
        this.nulltrace$settingsButton = Button.builder(
            Component.literal("NullTrace"),
            button -> {
                if (this.minecraft != null) {
                    //? if >=26.2 {
                    this.minecraft.setScreenAndShow(new NullTraceConfigScreen(this));
                    //?} else {
                    /*this.minecraft.setScreen(new NullTraceConfigScreen(this));
                    *///?}
                }
            }
        )
        .tooltip(Tooltip.create(NullTraceLang.component(NullTraceStrings.BUTTON_SETTINGS_TOOLTIP)))
        .build();
        
        nulltrace$updateButtonPosition();
        this.addRenderableWidget(this.nulltrace$settingsButton);

        // Show update screen if an update is available
        if (this.minecraft != null && UpdateChecker.isUpdateAvailable()) {
            UpdateChecker.markShown();
            //? if >=26.2 {
            this.minecraft.setScreenAndShow(new UpdateScreen(this));
            //?} else {
            /*this.minecraft.setScreen(new UpdateScreen(this));
            *///?}
        }
    }
    
    //? if >=1.21.9 {
    @Inject(method = "repositionElements", at = @At("TAIL"))
    private void nulltrace$onRepositionElements(CallbackInfo ci) {
        nulltrace$updateButtonPosition();
    }
    //?}
    
    @Unique
    private void nulltrace$updateButtonPosition() {
        if (this.nulltrace$settingsButton != null) {
            int textWidth = this.font.width(this.nulltrace$settingsButton.getMessage());
            int buttonWidth = textWidth + BUTTON_PADDING;
            int x = WURST_LOADED
                ? this.width / 2 + SERVER_LIST_HALF_ROW_WIDTH - buttonWidth
                : this.width / 2 - SERVER_LIST_HALF_ROW_WIDTH;
            this.nulltrace$settingsButton.setX(x);
            this.nulltrace$settingsButton.setY(6);
            this.nulltrace$settingsButton.setWidth(buttonWidth);
            //? if >=1.20.2 {
            this.nulltrace$settingsButton.setHeight(BUTTON_HEIGHT);
            //?}
            // 1.20.1: AbstractWidget.setHeight didn't exist; Button defaults to height=20
            // (matches BUTTON_HEIGHT) — height isn't user-resizable on this era.
        }
    }
}

