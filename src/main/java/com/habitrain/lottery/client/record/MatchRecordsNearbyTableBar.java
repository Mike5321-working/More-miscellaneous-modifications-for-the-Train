package com.habitrain.lottery.client.record;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Star-Night Express Art Deco live-sync banner rendered at the top of {@code MatchRecordsScreen}
 * whenever the player stands within 16 blocks of an active lobby replay table.
 *
 * <p>Allows direct remote control of the nearby table's progress (via interactive brass scrub rail),
 * play/pause, linear speed ({@code 0.5x/1x/2x/4x}), follow-player cycling, ending the table session,
 * opening the full synced 3D preview screen, or jumping directly to {@code ReplayExportScreen}.</p>
 */
public final class MatchRecordsNearbyTableBar {
    // Star-Night Express palette (mirrors ReplayPalette / RailArt)
    private static final int NIGHT_TOP = 0xF2111A33;
    private static final int NIGHT_BOTTOM = 0xF5060B18;
    private static final int BRASS = 0xFFD0A658;
    private static final int BRASS_DIM = 0xFF8A6A35;
    private static final int BRASS_LIGHT = 0xFFF4D894;
    private static final int CHAMPAGNE = 0xFFFFF4DA;
    private static final int LAMP = 0xFFFFB55E;
    private static final int RUBY = 0xFFFF5A64;
    private static final int TEAL = 0xFF6DCEC0;
    private static final int TEXT = 0xFFEEE8DA;
    private static final int TEXT_MUTED = 0xFF9EA7BE;

    private static final int BAR_HEIGHT = 24;
    private static final int RAIL_H = 3;
    private static final int BTN_H = 14;
    private static final int BTN_GAP = 3;

    private boolean scrubbing;
    private long lastSeekSendMs;
    private double previewScrubFraction = -1.0;

    private record PillButton(String id, int x, int y, int w, int h, Component label, Component tooltip, boolean accent) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private record Layout(int x, int y, int w, int h, int railX, int railY, int railW,
                          List<PillButton> buttons, int infoMaxX) {}

    public void render(GuiGraphics g, Font font, int panelX, int usableWidth, int panelY, int mouseX, int mouseY) {
        MatchReplayTableBridge.NearbyTableState state = MatchReplayTableBridge.pollNearbyTable();
        if (state == null) {
            scrubbing = false;
            previewScrubFraction = -1.0;
            return;
        }
        Layout layout = computeLayout(font, state, panelX, usableWidth, panelY);
        int bx = layout.x();
        int by = layout.y();
        int bw = layout.w();
        int bh = layout.h();

        // Art Deco midnight plate + brass outline + corner chamfers
        g.fillGradient(bx, by, bx + bw, by + bh, NIGHT_TOP, NIGHT_BOTTOM);
        g.renderOutline(bx, by, bw, bh, BRASS);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + 2, 0x33F4D894);
        drawCornerPixel(g, bx + 1, by + 1, 1, 1);
        drawCornerPixel(g, bx + bw - 2, by + 1, -1, 1);
        drawCornerPixel(g, bx + 1, by + bh - 2, 1, -1);
        drawCornerPixel(g, bx + bw - 2, by + bh - 2, -1, -1);

        // Pulsing status lamp + connected label
        double pulse = 0.65 + 0.35 * Math.sin(Util.getMillis() / 320.0);
        int dotBase = state.preparing() ? LAMP : (state.paused() || state.finishedHold() ? BRASS_LIGHT : TEAL);
        int dotColor = withAlpha(dotBase, state.paused() ? 0.85 : pulse);
        int topRowMidY = by + 9;
        g.fill(bx + 6, topRowMidY - 2, bx + 11, topRowMidY + 3, dotColor);

        String titleText = buildStatusLine(state);
        int textX = bx + 15;
        int availW = Math.max(24, layout.infoMaxX() - textX - 4);
        g.drawString(font, font.plainSubstrByWidth(titleText, availW), textX, by + 5, CHAMPAGNE, false);

        // Render Art Deco action pills
        Component hoveredTooltip = null;
        for (PillButton btn : layout.buttons()) {
            boolean hovered = !scrubbing && btn.contains(mouseX, mouseY);
            int border = hovered ? BRASS_LIGHT : (btn.accent() ? RUBY : BRASS_DIM);
            int topBg = btn.accent()
                    ? (hovered ? 0xFF5A1D26 : 0xFF3E141C)
                    : (hovered ? 0xFF283A63 : 0xFF15203C);
            int botBg = btn.accent()
                    ? (hovered ? 0xFF3A1018 : 0xFF260B10)
                    : (hovered ? 0xFF182545 : 0xFF0C1326);
            g.fill(btn.x(), btn.y(), btn.x() + btn.w(), btn.y() + btn.h(), border);
            g.fillGradient(btn.x() + 1, btn.y() + 1, btn.x() + btn.w() - 1, btn.y() + btn.h() - 1, topBg, botBg);
            int textColor = hovered ? CHAMPAGNE : (btn.accent() ? 0xFFFFB8BC : TEXT);
            g.drawCenteredString(font, btn.label(), btn.x() + btn.w() / 2, btn.y() + (btn.h() - font.lineHeight) / 2, textColor);
            if (hovered) {
                hoveredTooltip = btn.tooltip();
            }
        }

        // Interactive brass timeline scrub rail along bottom edge
        int rx = layout.railX();
        int ry = layout.railY();
        int rw = layout.railW();
        double fraction = scrubbing && previewScrubFraction >= 0.0 ? previewScrubFraction : state.progress();
        g.fill(rx, ry, rx + rw, ry + RAIL_H, 0xFF0A1022);
        int fillW = (int) Math.round(rw * Math.clamp(fraction, 0.0, 1.0));
        if (fillW > 0) {
            g.fill(rx, ry, rx + fillW, ry + RAIL_H, state.paused() ? BRASS : BRASS_LIGHT);
        }
        int knobX = rx + Math.clamp(fillW, 1, Math.max(1, rw - 1));
        g.fill(knobX - 1, ry - 1, knobX + 2, ry + RAIL_H + 1, CHAMPAGNE);

        boolean railHovered = isOnRail(layout, mouseX, mouseY);
        if (hoveredTooltip == null && (scrubbing || railHovered) && state.durationTicks() > 0) {
            double hoverFrac = Math.clamp((mouseX - rx) / (double) Math.max(1, rw), 0.0, 1.0);
            double targetTick = hoverFrac * state.durationTicks();
            hoveredTooltip = Component.translatableWithFallback(
                    "screen.habitrain_lottery.records.nearby_table.seek_tip",
                    "拖动或点击同步定位回放桌进度：%1$s / %2$s",
                    formatClock(targetTick), formatClock(state.durationTicks()));
        } else if (hoveredTooltip == null && mouseX >= bx && mouseX < layout.infoMaxX() && mouseY >= by && mouseY < ry) {
            hoveredTooltip = Component.translatableWithFallback(
                    "screen.habitrain_lottery.records.nearby_table.banner_tip",
                    "已自动连接 16 格内的大厅回放桌（距离 %1$s 格），可直接在此同步控制或直达导出",
                    String.format(Locale.ROOT, "%.1f", state.distanceBlocks()));
        }

        if (hoveredTooltip != null) {
            g.renderTooltip(font, hoveredTooltip, mouseX, mouseY);
        }
    }

    public boolean mouseClicked(Screen screen, @Nullable Minecraft mc, Font font,
                                int panelX, int usableWidth, int panelY,
                                double mouseX, double mouseY, int button) {
        MatchReplayTableBridge.NearbyTableState state = MatchReplayTableBridge.pollNearbyTable();
        if (state == null) return false;
        Layout layout = computeLayout(font, state, panelX, usableWidth, panelY);
        if (mouseX < layout.x() || mouseX >= layout.x() + layout.w()
                || mouseY < layout.y() || mouseY >= layout.y() + layout.h()) {
            return false;
        }

        if (button == 0 && isOnRail(layout, mouseX, mouseY) && state.durationTicks() > 0) {
            scrubbing = true;
            sendScrub(layout, state, mouseX, true);
            playClick(mc);
            return true;
        }

        for (PillButton btn : layout.buttons()) {
            if (!btn.contains(mouseX, mouseY)) continue;
            if (button != 0 && !(button == 1 && "follow".equals(btn.id()))) continue;
            boolean handled = handleButton(screen, mc, state, btn.id(), button);
            if (handled) playClick(mc);
            return handled;
        }
        return true;
    }

    public boolean mouseDragged(Font font, int panelX, int usableWidth, int panelY, double mouseX, double mouseY, int button) {
        if (!scrubbing || button != 0) return false;
        MatchReplayTableBridge.NearbyTableState state = MatchReplayTableBridge.pollNearbyTable();
        if (state == null || state.durationTicks() <= 0) {
            scrubbing = false;
            previewScrubFraction = -1.0;
            return false;
        }
        Layout layout = computeLayout(font, state, panelX, usableWidth, panelY);
        sendScrub(layout, state, mouseX, false);
        return true;
    }

    public boolean mouseReleased(Font font, int panelX, int usableWidth, int panelY, double mouseX, double mouseY, int button) {
        if (!scrubbing) return false;
        MatchReplayTableBridge.NearbyTableState state = MatchReplayTableBridge.pollNearbyTable();
        if (state != null && state.durationTicks() > 0) {
            Layout layout = computeLayout(font, state, panelX, usableWidth, panelY);
            sendScrub(layout, state, mouseX, true);
        }
        scrubbing = false;
        previewScrubFraction = -1.0;
        return true;
    }

    private void sendScrub(Layout layout, MatchReplayTableBridge.NearbyTableState state, double mouseX, boolean force) {
        double frac = Math.clamp((mouseX - layout.railX()) / (double) Math.max(1, layout.railW()), 0.0, 1.0);
        previewScrubFraction = frac;
        long now = Util.getMillis();
        if (force || now - lastSeekSendMs >= 80L) {
            lastSeekSendMs = now;
            MatchReplayTableBridge.control("seek", frac * state.durationTicks(), null, false);
        }
    }

    private boolean handleButton(Screen screen, @Nullable Minecraft mc, MatchReplayTableBridge.NearbyTableState state,
                                 String id, int mouseButton) {
        return switch (id) {
            case "play_pause" -> MatchReplayTableBridge.control("toggle_pause", 0.0, null, false);
            case "speed" -> MatchReplayTableBridge.control("cycle_speed", 0.0, null, false);
            case "follow" -> {
                if (mouseButton == 1) {
                    yield MatchReplayTableBridge.control("follow", 0.0, "", false);
                }
                yield MatchReplayTableBridge.control("cycle_follow", 1.0, null, false);
            }
            case "end" -> MatchReplayTableBridge.control("end", 0.0, null, false);
            case "preview" -> {
                Screen next = MatchReplayTableBridge.openNearbyTablePreview(screen);
                if (next != null && mc != null) {
                    mc.setScreen(next);
                    yield true;
                }
                yield false;
            }
            case "export" -> {
                Screen next = MatchReplayTableBridge.openNearbyTableExport(screen);
                if (next != null && mc != null) {
                    mc.setScreen(next);
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
    }

    private Layout computeLayout(Font font, MatchReplayTableBridge.NearbyTableState state,
                                 int panelX, int usableWidth, int panelY) {
        int bw = Math.max(240, usableWidth);
        int bx = panelX;
        int by = Math.max(19, panelY - BAR_HEIGHT - 3);
        int bh = Math.min(BAR_HEIGHT, Math.max(20, panelY - by - 2));

        List<PillSpec> specs = new ArrayList<>();
        specs.add(new PillSpec("play_pause",
                Component.literal(state.paused() || state.finishedHold() ? "▶" : "⏸"),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.play_pause_tip",
                        state.paused() ? "继续桌面回放" : "暂停桌面回放"),
                false));
        specs.add(new PillSpec("speed",
                Component.literal(formatSpeed(state.speed())),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.speed_tip",
                        "切换回放桌倍速（0.5x / 1x / 2x / 4x）"),
                false));

        String followText = state.hasFollowPlayer()
                ? Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.follow_short",
                        "跟随:%s", truncateName(state.followPlayerName(), 6)).getString()
                : Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.global_short",
                        "全局").getString();
        specs.add(new PillSpec("follow",
                Component.literal(followText),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.follow_tip",
                        "左键切换下一位跟随玩家 · 右键恢复全局沙盘"),
                false));
        specs.add(new PillSpec("preview",
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.sync_screen", "大屏同步"),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.sync_screen_tip",
                        "在背包 3D 回放大屏直接同步预览并精调该桌面会话"),
                false));
        specs.add(new PillSpec("export",
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.export", "导出视频"),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.export_tip",
                        "一键将当前大厅回放桌对局直达视频导出界面"),
                false));
        specs.add(new PillSpec("end",
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.end", "⏹结束"),
                Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.end_tip",
                        "结束附近回放桌本次播放并恢复待机雕塑"),
                true));

        int btnY = by + 3;
        int cursorRight = bx + bw - 5;
        List<PillButton> buttons = new ArrayList<>(specs.size());
        for (int i = specs.size() - 1; i >= 0; i--) {
            PillSpec spec = specs.get(i);
            int pw = Math.max(18, font.width(spec.label()) + 8);
            int px = cursorRight - pw;
            if (px < bx + 92 && buttons.size() >= 3) break;
            buttons.add(new PillButton(spec.id(), px, btnY, pw, BTN_H, spec.label(), spec.tooltip(), spec.accent()));
            cursorRight = px - BTN_GAP;
        }
        int railX = bx + 6;
        int railW = Math.max(20, bw - 12);
        int railY = by + bh - RAIL_H - 2;
        return new Layout(bx, by, bw, bh, railX, railY, railW, buttons, cursorRight);
    }

    private record PillSpec(String id, Component label, Component tooltip, boolean accent) {}

    private static boolean isOnRail(Layout layout, double mx, double my) {
        return mx >= layout.railX() - 2 && mx <= layout.railX() + layout.railW() + 2
                && my >= layout.railY() - 3 && my <= layout.y() + layout.h();
    }

    private static String buildStatusLine(MatchReplayTableBridge.NearbyTableState state) {
        String map = state.mapName().isEmpty() ? state.roundId().substring(0, Math.min(8, state.roundId().length())) : state.mapName();
        String clock = state.durationTicks() > 0
                ? formatClock(state.currentTick()) + "/" + formatClock(state.durationTicks())
                : "--:--";
        String stateTag = state.preparing()
                ? Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.preparing", "载入中").getString()
                : state.finishedHold()
                ? Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.finished", "终局").getString()
                : state.paused()
                ? Component.translatableWithFallback("screen.habitrain_lottery.records.nearby_table.paused", "暂停").getString()
                : "";
        String prefix = Component.translatableWithFallback(
                "screen.habitrain_lottery.records.nearby_table.connected", "已连接附近大厅回放桌").getString();
        return stateTag.isEmpty()
                ? prefix + " · " + map + " " + clock
                : prefix + " · " + map + " " + clock + " [" + stateTag + "]";
    }

    private static String formatSpeed(float speed) {
        if (Math.abs(speed - Math.round(speed)) < 0.05f) {
            return Math.round(speed) + "x";
        }
        return String.format(Locale.ROOT, "%.1fx", speed);
    }

    private static String formatClock(double ticks) {
        int totalSec = Math.max(0, (int) Math.floor(ticks / 20.0));
        return String.format(Locale.ROOT, "%02d:%02d", totalSec / 60, totalSec % 60);
    }

    private static String truncateName(String name, int maxChars) {
        if (name == null || name.isEmpty()) return "?";
        return name.length() <= maxChars ? name : name.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private static void drawCornerPixel(GuiGraphics g, int x, int y, int dx, int dy) {
        g.fill(x, y, x + 1, y + 1, BRASS_LIGHT);
        g.fill(x + dx, y, x + dx + 1, y + 1, BRASS_DIM);
        g.fill(x, y + dy, x + 1, y + dy + 1, BRASS_DIM);
    }

    private static int withAlpha(int rgb, double alpha) {
        int a = (int) Math.round(Math.clamp(alpha, 0.0, 1.0) * 255.0);
        return (rgb & 0x00FFFFFF) | (a << 24);
    }

    private static void playClick(@Nullable Minecraft mc) {
        if (mc != null) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
