package net.nulltrace.mod.config;

import net.nulltrace.mod.accounts.AccountManager;
import net.nulltrace.mod.accounts.CrackedAccount;
import net.nulltrace.mod.lang.NullTraceLang;
import net.nulltrace.mod.lang.NullTraceStrings;
import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Screen for adding a new cracked/offline account via username only.
 */
public class AddCrackedAccountScreen extends Screen {

    private final Screen parent;
    private EditBox usernameInput;
    private Button addButton;
    private Button cancelButton;
    private Component statusMessage;
    private boolean isAdding = false;
    private StringWidget titleLabel;
    private StringWidget statusLabel;

    public AddCrackedAccountScreen(Screen parent) {
        super(NullTraceLang.component(NullTraceStrings.ACCOUNT_SCREEN_OFFLINE_TITLE));
        this.parent = parent;
        this.statusMessage = Component.literal("");
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // Title label
        int titleWidth = this.font.width(this.title);
        this.titleLabel = new StringWidget(centerX - titleWidth / 2, centerY - 55, titleWidth, 20, this.title, this.font);
        this.addRenderableWidget(this.titleLabel);

        // Username input field
        this.usernameInput = new EditBox(
                this.font,
                centerX - 150,
                centerY - 30,
                300,
                20,
                NullTraceLang.component(NullTraceStrings.ACCOUNT_SCREEN_USERNAME_LABEL)
        );
        this.usernameInput.setMaxLength(16);
        this.usernameInput.setHint(NullTraceLang.component(NullTraceStrings.ACCOUNT_SCREEN_USERNAME_HINT));
        this.addRenderableWidget(this.usernameInput);

        // Status label
        this.statusLabel = new StringWidget(centerX - 150, centerY + 35, 300, 20, Component.literal(""), this.font);
        this.addRenderableWidget(this.statusLabel);

        // Add button
        this.addButton = Button.builder(NullTraceLang.component(NullTraceStrings.ACCOUNT_SCREEN_ADD_BUTTON), button -> {
            addAccount();
        }).bounds(centerX - 105, centerY + 5, 100, 20).build();
        this.addRenderableWidget(this.addButton);

        // Cancel button
        this.cancelButton = Button.builder(NullTraceLang.component(NullTraceStrings.ACCOUNT_SCREEN_CANCEL_BUTTON), button -> {
            this.onClose();
        }).bounds(centerX + 5, centerY + 5, 100, 20).build();
        this.addRenderableWidget(this.cancelButton);

        // Focus the username input
        this.setInitialFocus(this.usernameInput);
    }

    private void addAccount() {
        String username = usernameInput.getValue().trim();

        if (username.isEmpty()) {
            statusMessage = NullTraceLang.component(NullTraceStrings.ACCOUNT_ERROR_EMPTY_USERNAME);
            if (statusLabel != null) {
                statusLabel.setMessage(statusMessage);
            }
            return;
        }

        if (isAdding) {
            return;
        }

        isAdding = true;
        addButton.active = false;
        statusMessage = NullTraceLang.component(NullTraceStrings.ACCOUNT_STATUS_ADDING);
        if (statusLabel != null) {
            statusLabel.setMessage(statusMessage);
        }

        CompletableFuture.runAsync(() -> {
            CrackedAccount account = new CrackedAccount(username);

            boolean loginSuccess = account.login();

            Minecraft.getInstance().execute(() -> {
                isAdding = false;
                addButton.active = true;

                if (loginSuccess) {
                    AccountManager.getInstance().add(account);
                    AccountManager.getInstance().setActiveAccountUuid(account.getUuid());

                    statusMessage = Component.literal(NullTraceLang.tr(NullTraceStrings.ACCOUNT_SUCCESS_ADDED_OFFLINE, account.getUsername()));
                    if (statusLabel != null) {
                        statusLabel.setMessage(statusMessage);
                    }

                    Minecraft.getInstance().execute(this::onClose);
                } else {
                    statusMessage = NullTraceLang.component(NullTraceStrings.ACCOUNT_ERROR_FAILED_ADD);
                    if (statusLabel != null) {
                        statusLabel.setMessage(statusMessage);
                    }
                }
            });
        });
    }

    //? if >=26.1 {
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // Update status label position to keep it centered
        if (statusLabel != null && statusMessage != null && !statusMessage.getString().isEmpty()) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int statusWidth = this.font.width(statusMessage);
            statusLabel.setX(centerX - statusWidth / 2);
            statusLabel.setY(centerY + 35);
            statusLabel.setWidth(statusWidth);
        }
    }
    //?} elif <1.21.6 && >=1.20.2 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);

        // Update status label position to keep it centered
        if (statusLabel != null && statusMessage != null && !statusMessage.getString().isEmpty()) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int statusWidth = this.font.width(statusMessage);
            statusLabel.setX(centerX - statusWidth / 2);
            statusLabel.setY(centerY + 35);
            statusLabel.setWidth(statusWidth);
        }
    }*/
    //?} elif <1.20.2 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 1.20.1: Screen.renderBackground takes a single GuiGraphics arg.
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (statusLabel != null && statusMessage != null && !statusMessage.getString().isEmpty()) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int statusWidth = this.font.width(statusMessage);
            statusLabel.setX(centerX - statusWidth / 2);
            statusLabel.setY(centerY + 35);
            statusLabel.setWidth(statusWidth);
        }
    }*/
    //?} else {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        // Update status label position to keep it centered
        if (statusLabel != null && statusMessage != null && !statusMessage.getString().isEmpty()) {
            int centerX = this.width / 2;
            int centerY = this.height / 2;
            int statusWidth = this.font.width(statusMessage);
            statusLabel.setX(centerX - statusWidth / 2);
            statusLabel.setY(centerY + 35);
            statusLabel.setWidth(statusWidth);
        }
    }
    *///?}

    @Override
    public void onClose() {
        Screen grandParent = null;
        if (parent instanceof NullTraceConfigScreen configScreen) {
            grandParent = configScreen.getParent();
        }
        //? if >=26.2 {
        this.minecraft.setScreenAndShow(new NullTraceConfigScreen(grandParent, 3, 0));
        //?} else if >=1.21.6 {
        /*this.minecraft.setScreen(new NullTraceConfigScreen(grandParent, 3, 0));
        *///?} else {
        /*this.minecraft.setScreen(new NullTraceConfigScreen(grandParent, 3));*/
        //?}
    }

    //? if <1.21.9 {
    /*@Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 && !isAdding) { // GLFW_KEY_ENTER
            addAccount();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    *///?}
}
