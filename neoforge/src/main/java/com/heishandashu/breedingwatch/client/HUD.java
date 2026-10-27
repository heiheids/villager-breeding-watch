package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.server.Cond;
import com.heishandashu.breedingwatch.view.Views;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * 左上角 HUD 面板（NeoForge 侧）。
 *
 * <p>26.3 的 NeoForge 用 {@code GuiLayer} + {@code RegisterGuiLayersEvent}：
 * 注册期事件（mod bus）里把 layer 挂到某个原版 layer 之上。
 * 回调形态与 Fabric 的 {@code HudElement} 完全一致：
 * {@code render(GuiGraphicsExtractor, DeltaTracker)}。</p>
 */
public final class HUD {

    private static final int PAD_X = 6;
    private static final int PAD_Y = 6;
    private static final int BOX_W = 246;

    private HUD() {}

    public static void onRegisterLayers(RegisterGuiLayersEvent event) {
        // 挂在原版「经验条/热键栏」之上，保证在 HUD 最上层
        event.registerAbove(VanillaGuiLayers.HOTBAR,
                Identifier.fromNamespaceAndPath(VbwMod.MOD_ID, "hud"),
                HUD::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        ClientSettings s = ClientHub.SETTINGS;
        if (!s.hud) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        try {
            int x = PAD_X;
            int y = PAD_Y;
            int lineH = mc.font.lineHeight + 2;
            boolean anyOn = s.enabled || s.allLines || s.allTags;
            int rows = anyOn ? 6 : 3;
            g.fill(x - 2, y - 2, x + BOX_W, y - 2 + rows * lineH + 4, 0x70000000);

            if (!anyOn) {
                g.text(mc.font, Lang.t("hud.off1"), x, y, 0xFFB0B0B0, true);
                g.text(mc.font, Lang.t("hud.legend"), x, y + lineH, 0xFF8FD8A0, true);
                g.text(mc.font, Lang.t("hud.keys"), x, y + lineH * 2, 0xFF9FB8D8, true);
                return;
            }
            g.text(mc.font, Lang.f("hud.state", s.enabled ? Lang.t("state.on") : Lang.t("state.off"),
                            s.allLines ? Lang.t("hud.force_lines") : "",
                            s.allTags ? Lang.t("hud.force_tags") : "",
                            s.radius, s.interval),
                    x, y, s.enabled ? 0xFF8FD8A0 : 0xFFE8D24A, true);
            y += lineH;
            g.text(mc.font, Lang.t("hud.legend"), x, y, 0xFF8FD8A0, true);
            y += lineH;

            Views.Snapshot snap = ClientHub.DATA.snapshot();
            if (snap == null) {
                g.text(mc.font, Lang.t("hud.waiting"), x, y, 0xFFE0C890, true);
                return;
            }
            int ready = 0;
            int total = 0;
            for (Views.VillagerView v : ClientHub.DATA.all()) {
                total++;
                if (v.ready()) {
                    ready++;
                }
            }
            // 新鲜度用墙钟，不用世界时间（固定时间维度会恒 0）
            long ageMs = System.currentTimeMillis() - ClientHub.DATA.lastSnapshotMillis();
            long staleMs = Math.max((long) (s.interval + 6) * 50L, 1000L);
            boolean fresh = ageMs <= staleMs;

            g.text(mc.font, Lang.f("hud.count", total, ready)
                            + (snap.allMode() ? "　" + Lang.t("hud.allmode") : "")
                            + "　" + Lang.f("hud.server_scan", snap.scanned())
                            + (fresh ? "" : "　" + Lang.t("hud.stale")),
                    x, y, fresh ? 0xFFFFFFFF : 0xFFB0B0B0, true);
            y += lineH;

            Views.Stats st = snap.stats();
            if (st != null) {
                g.text(mc.font, Lang.f("hud.totals", st.totalBirths(), st.totalFailures(), st.totalDeaths()),
                        x, y, fresh ? 0xFFFFFFFF : 0xFFB0B0B0, true);
                y += lineH;
                String worst = worstFailure(st);
                g.text(mc.font, worst == null ? Lang.t("hud.no_fail") : Lang.f("hud.worst", worst),
                        x, y, fresh ? 0xFFF2A63C : 0xFFB0B0B0, true);
                y += lineH;
            }
            g.text(mc.font, Lang.t("hud.keys"), x, y, 0xFF9FB8D8, true);
        } catch (Throwable t) {
            // 诊断功能出问题的代价必须是「这一帧没有面板」，不能拖垮 HUD
            VbwMod.LOG.warn("[VBW] HUD 绘制失败：{}", t.toString());
        }
    }

    private static String worstFailure(Views.Stats st) {
        if (st.failByCode() == null || st.failByCode().isEmpty()) {
            return null;
        }
        String best = null;
        int bestCount = -1;
        for (var e : st.failByCode().entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = Cond.label(e.getKey()) + " ×" + e.getValue();
            }
        }
        return best;
    }
}
