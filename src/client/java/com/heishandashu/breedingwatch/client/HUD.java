package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.client.ui.ProfNames;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.server.Cond;
import com.heishandashu.breedingwatch.view.Views;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * 左上角 HUD 面板。
 *
 * <p>26.3 的 HUD 钩子是 {@code HudElementRegistry}（{@code HudRenderCallback} 早已删除），
 * 回调形态是 {@code extractRenderState(GuiGraphicsExtractor, DeltaTracker)} —— 依旧是
 * "描述这一帧"，不是直接绘制。</p>
 *
 * <p>面板的意义是把关键状态搬到屏幕上：第三行的"服务器扫到 K 名"能一眼区分
 * "服务器没扫到"还是"客户端没画"，比反复读日志快得多。</p>
 */
public final class HUD implements HudElement {

    private static final int PAD_X = 6;
    private static final int PAD_Y = 6;
    private static final int BOX_W = 246;

    private static final HUD INSTANCE = new HUD();

    private HUD() {}

    public static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(
                com.heishandashu.breedingwatch.VbwMod.MOD_ID, "hud"), INSTANCE);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        ClientSettings s = ClientHub.SETTINGS;
        if (!s.hud) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
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
        // "数据是否新鲜"用客户端自己收到包的墙钟时间判断，不要用维度时钟：
        // 下界/末地是 has_fixed_time，维度时钟恒为 0，会永远显示"数据已停止更新"。
        long ageMs = System.currentTimeMillis() - ClientHub.DATA.lastSnapshotMillis();
        long staleMs = (long) (s.interval + 6) * 50L;   // 1 刻 = 50 ms
        boolean fresh = ageMs <= Math.max(staleMs, 1000L);
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

    /** 供详情页复用：把英文 id 翻成人话。 */
    static String profession(String id) {
        return ProfNames.profession(id);
    }
}
