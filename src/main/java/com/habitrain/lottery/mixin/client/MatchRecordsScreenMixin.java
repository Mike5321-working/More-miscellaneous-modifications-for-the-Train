package com.habitrain.lottery.mixin.client;

import com.habitrain.lottery.client.record.MatchRecordsDetailView;
import com.habitrain.lottery.client.record.MatchRecordsNearbyTableBar;
import com.habitrain.lottery.client.record.MatchRecordClient;
import com.habitrain.lottery.client.record.MatchReplayTableBridge;
import net.exmo.sre.record.MatchRecord;
import net.exmo.sre.record.client.ClientMatchRecordCache;
import net.exmo.sre.record.client.MatchRecordsScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Adds a "查看详情" / "收起详情" toggle button (and {@code D} hotkey) to {@link MatchRecordsScreen}
 * while keeping the un-toggled view identical to upstream.
 */
@Mixin(MatchRecordsScreen.class)
public abstract class MatchRecordsScreenMixin extends Screen implements MatchRecordClient.Status {

    @Shadow(remap = false)
    private String selectedMatchId;

    @Shadow(remap = false)
    private int selectedIndex;

    @Shadow(remap = false)
    private MatchRecord selectedRecord;

    @Shadow(remap = false)
    @Final
    private List<FormattedCharSequence> detailLines;

    @Shadow(remap = false)
    private int usableWidth;

    @Shadow(remap = false)
    private int panelX;

    @Shadow(remap = false)
    private int rightX;

    @Shadow(remap = false)
    private int rightW;

    @Shadow(remap = false)
    private int panelY;

    @Shadow(remap = false)
    private int detailScrollOffset;

    @Shadow(remap = false)
    private int maxDetailScroll;

    @Shadow(remap = false)
    private int listScrollOffset;

    @Shadow(remap = false)
    private void rebuildDetailLines() {
    }

    @Shadow(remap = false)
    private int detailContentH() {
        return 0;
    }

    @Shadow(remap = false)
    private void selectIndex(int index) {
    }

    @Shadow(remap = false)
    public abstract void showRecord(MatchRecord record);

    @Unique
    private boolean habi$showAllDetails = false;

    @Unique
    private boolean habi$recordUnavailable;

    @Unique private Button habi$viewReplay;
    @Unique private Button habi$exportReplay;
    @Unique private final MatchRecordsNearbyTableBar habi$nearbyTableBar = new MatchRecordsNearbyTableBar();

    protected MatchRecordsScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void habi$refreshOnFirstOpen(CallbackInfo ci) {
        if (this.selectedMatchId == null && this.selectedIndex < 0) {
            ClientMatchRecordCache.resetWindows();
        }
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void habi$addReplayButtons(CallbackInfo ci) {
        int gap = 6, buttonWidth = Math.min(118, (width - 26) / 2);
        int x = (width - buttonWidth * 2 - gap) / 2;
        int y = height - 42;
        habi$viewReplay = addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_core.replay_history.view_button"), b -> habi$openReplay(false))
                .bounds(x, y, buttonWidth, 17).build());
        habi$viewReplay.setTooltip(Tooltip.create(Component.translatable("screen.habitrain_core.replay_history.view_tip")));
        habi$exportReplay = addRenderableWidget(Button.builder(
                Component.translatable("screen.habitrain_core.replay_history.export"), b -> habi$openReplay(true))
                .bounds(x + buttonWidth + gap, y, buttonWidth, 17).build());
        habi$exportReplay.setTooltip(Tooltip.create(Component.translatable("screen.habitrain_core.replay_history.export_tip")));
        habi$updateReplayButtons();
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void habi$refreshReplayButtons(GuiGraphics g, int x, int y, float delta, CallbackInfo ci) {
        habi$updateReplayButtons();
    }

    @Unique
    private void habi$updateReplayButtons() {
        if (habi$viewReplay != null) habi$viewReplay.active = habi$exportReplay.active = this.selectedMatchId != null;
    }

    @Unique
    private void habi$openReplay(boolean export) {
        if (this.minecraft != null && this.selectedMatchId != null) {
            this.minecraft.setScreen(MatchReplayTableBridge.openReplay(this, this.selectedMatchId, export));
        }
    }

    @Inject(method = "rebuildDetailLines", at = @At("HEAD"), cancellable = true, remap = false)
    private void habi$rebuildExpandedDetails(CallbackInfo ci) {
        int textW = Math.max(1, this.rightW - MatchRecordsDetailView.PANEL_PAD * 2 - MatchRecordsDetailView.SCROLL_W - 4);
        if (this.selectedRecord == null && this.habi$recordUnavailable) {
            this.detailLines.clear();
            this.detailLines.addAll(this.font.split(Component.translatable(
                    "screen.habitrain_lottery.records.unavailable").withStyle(ChatFormatting.GRAY), textW));
            this.maxDetailScroll = this.detailScrollOffset = 0;
            ci.cancel();
            return;
        }
        if (!this.habi$showAllDetails || this.selectedMatchId == null || this.selectedRecord == null) {
            return;
        }
        this.maxDetailScroll = MatchRecordsDetailView.buildDetailedContent(
                this.font,
                this.selectedRecord,
                this.detailLines,
                textW,
                this.detailContentH()
        );
        this.detailScrollOffset = Mth.clamp(this.detailScrollOffset, 0, this.maxDetailScroll);
        ci.cancel();
    }

    @Override
    public void habitrain$acceptRecord(MatchRecord record) {
        if (record.matchId.equals(this.selectedMatchId)) {
            this.habi$recordUnavailable = false;
            this.showRecord(record);
        }
    }

    @Override
    public void habitrain$recordUnavailable(String matchId) {
        if (matchId.equals(this.selectedMatchId) && this.selectedRecord == null) {
            this.habi$recordUnavailable = true;
            this.rebuildDetailLines();
        }
    }

    @Inject(method = "selectIndex", at = @At("HEAD"), remap = false)
    private void habi$clearLoadError(int index, CallbackInfo ci) {
        this.habi$recordUnavailable = false;
    }

    @Inject(method = "onWindowUpdated", at = @At("RETURN"), remap = false)
    private void habi$selectWhenPageArrives(CallbackInfo ci) {
        var summary = ClientMatchRecordCache.getAt(this.selectedIndex);
        if (summary != null && summary.matchId != null && !summary.matchId.equals(this.selectedMatchId)) {
            this.selectIndex(this.selectedIndex);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void habi$renderDetailToggleButton(
            GuiGraphics guiGraphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        MatchRecordsDetailView.renderToggleButton(
                guiGraphics,
                this.font,
                this.selectedMatchId,
                this.selectedRecord,
                this.rightX,
                this.rightW,
                this.panelY,
                mouseX,
                mouseY,
                this.habi$showAllDetails
        );
        this.habi$nearbyTableBar.render(
                guiGraphics,
                this.font,
                this.panelX,
                this.usableWidth,
                this.panelY,
                mouseX,
                mouseY
        );
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void habi$clickDetailToggleButton(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.habi$nearbyTableBar.mouseClicked(
                this,
                this.minecraft,
                this.font,
                this.panelX,
                this.usableWidth,
                this.panelY,
                mouseX,
                mouseY,
                button
        )) {
            cir.setReturnValue(true);
            return;
        }
        if (button != 0) {
            return;
        }
        if (MatchRecordsDetailView.isClickOnToggleButton(
                this.font,
                this.selectedMatchId,
                this.selectedRecord,
                this.rightX,
                this.rightW,
                this.panelY,
                mouseX,
                mouseY
        )) {
            this.habi$toggleDetailMode();
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void habi$dragNearbyTableBar(
            double mouseX,
            double mouseY,
            int button,
            double dragX,
            double dragY,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.habi$nearbyTableBar.mouseDragged(
                this.font,
                this.panelX,
                this.usableWidth,
                this.panelY,
                mouseX,
                mouseY,
                button
        )) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void habi$releaseNearbyTableBar(
            double mouseX,
            double mouseY,
            int button,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.habi$nearbyTableBar.mouseReleased(
                this.font,
                this.panelX,
                this.usableWidth,
                this.panelY,
                mouseX,
                mouseY,
                button
        )) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void habi$hotkeyDetailToggle(
            int keyCode,
            int scanCode,
            int modifiers,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            this.selectedMatchId = null;
            this.selectedRecord = null;
            this.habi$recordUnavailable = false;
            this.detailScrollOffset = this.listScrollOffset = 0;
            this.rebuildDetailLines();
        } else if (this.selectedRecord != null && MatchRecordsDetailView.isToggleHotkey(keyCode)) {
            this.habi$toggleDetailMode();
            cir.setReturnValue(true);
        }
    }

    @Unique
    private void habi$toggleDetailMode() {
        this.habi$showAllDetails = !this.habi$showAllDetails;
        this.detailScrollOffset = 0;
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
        this.rebuildDetailLines();
    }
}
