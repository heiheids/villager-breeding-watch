package com.heishandashu.breedingwatch.client.ui;

import com.heishandashu.breedingwatch.client.ClientHub;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.server.Cond;
import com.heishandashu.breedingwatch.server.WatchServer;
import com.heishandashu.breedingwatch.view.Views;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 统计界面：总览 / 失败明细 / 村民明细 三个标签。 */
public final class StatsScreen extends TextScreen {

    private int tab;

    public StatsScreen() {
        super(Lang.t("ui.stats_title"));
    }

    @Override
    protected void init() {
        super.init();
        int x = this.width / 2 - 150;
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.tab_overview")), b -> {
            this.tab = 0;
            this.scroll = 0;
            this.rebuildWidgets();
        }).bounds(x, 22, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.tab_failures")), b -> {
            this.tab = 1;
            this.scroll = 0;
            this.rebuildWidgets();
        }).bounds(x + 100, 22, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.tab_villagers")), b -> {
            this.tab = 2;
            this.scroll = 0;
            this.rebuildWidgets();
        }).bounds(x + 200, 22, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.back_settings")),
                b -> Minecraft.getInstance().gui.setScreen(new SettingsScreen()))
                .bounds(this.width / 2 + 154, 22, 80, 20).build());
    }

    /** 统计文件里存的是代码令牌，显示时才翻译。 */
    static String outcomeText(String o) {
        if (o == null || o.isEmpty()) {
            return "";
        }
        if (WatchServer.OUT_COURTING.equals(o)) {
            return Lang.t("out.courting");
        }
        if (o.startsWith(WatchServer.OUT_FAIL)) {
            return Lang.f("out.fail", Cond.label(o.substring(WatchServer.OUT_FAIL.length())));
        }
        if (o.startsWith(WatchServer.OUT_BIRTH)) {
            return Lang.f("out.birth", o.substring(WatchServer.OUT_BIRTH.length()));
        }
        return o;
    }


    @Override
    protected List<Line> buildLines() {
        List<Line> out = new ArrayList<>();
        Views.Snapshot snap = ClientHub.DATA.snapshot();
        if (snap == null || snap.stats() == null) {
            out.add(l(Lang.t("st.no_stats"), ORANGE));
            return out;
        }
        Views.Stats st = snap.stats();
        if (tab == 0) {
            out.add(l(Lang.f("st.time", st.dayString()), GRAY));
            out.add(l(Lang.f("st.births", st.totalBirths(), snap.scanned()), GREEN));
            out.add(l(Lang.f("st.failures", st.totalFailures()), RED));
            out.add(l(Lang.f("st.deaths", st.totalDeaths()), WHITE));
            out.add(l(Lang.f("st.tracked", st.trackedVillagers()), WHITE));
            out.add(l("", WHITE));
            out.add(l(Lang.t("legend.title"), YELLOW));
            out.add(l("  " + Lang.t("legend.green"), GREEN));
            out.add(l("  " + Lang.t("legend.orange"), ORANGE));
            out.add(l("  " + Lang.t("legend.red"), RED));
            out.add(l("  " + Lang.t("legend.gray"), GRAY));
            out.add(l("  " + Lang.t("legend.dash"), CYAN));
            out.add(l("  " + Lang.t("legend.cyan"), CYAN));
            out.add(l("  " + Lang.t("legend.sym"), DIM));
            out.add(l("  " + Lang.t("legend.right"), DIM));
            out.add(l("", WHITE));
            out.add(l(Lang.t("st.fail_by_code"), YELLOW));
            if (st.failByCode() == null || st.failByCode().isEmpty()) {
                out.add(l("  " + Lang.t("st.none"), GRAY));
            } else {
                for (var e : st.failByCode().entrySet()) {
                    out.add(l("  " + e.getKey() + " " + Cond.label(e.getKey())
                            + Lang.f("st.times", e.getValue()), RED));
                }
            }
            out.add(l("", WHITE));
            out.add(l(Lang.t("st.recent_births"), YELLOW));
            if (st.births() == null || st.births().isEmpty()) {
                out.add(l("  " + Lang.t("st.none"), GRAY));
            } else {
                for (Views.Birth b : st.births()) {
                    out.add(l("  " + Lang.f("st.birth_line", b.babySeq(), b.babyName(), b.time(), b.tick())
                            + (b.parentsConfirmed() ? "" : " " + Lang.t("st.unconfirmed")), GREEN));
                    for (String p : b.parents()) {
                        out.add(l("      └ " + Lang.f("st.parent", p), WHITE));
                    }
                    out.add(l("      " + Lang.f("st.place", b.levelId(), b.x(), b.y(), b.z()), GRAY));
                }
            }
        } else if (tab == 1) {
            int n = st.failures() == null ? 0 : st.failures().size();
            out.add(l(Lang.f("st.fail_head", n), GRAY));
            if (n == 0) {
                out.add(l("  " + Lang.t("st.none"), GRAY));
            } else {
                for (Views.Failure f : st.failures()) {
                    out.add(l("  " + Lang.f("st.fail_line", f.time(), f.seq(), f.name(), f.label()), RED));
                    out.add(l("    " + Lang.f("st.fail_code", f.code()), GRAY));
                    for (String part : wrap(f.detail(), 380)) {
                        out.add(l("      " + part, 0xFFE0C890));
                    }
                }
            }
        } else {
            out.add(l(Lang.t("st.vill_head"), GRAY));
            if (st.perVillager() == null || st.perVillager().isEmpty()) {
                out.add(l("  " + Lang.t("st.none"), GRAY));
            } else {
                for (Views.VStat v : st.perVillager()) {
                    out.add(l(Lang.f("st.vill_line", v.seq(), v.name(), ProfNames.profession(v.profession()),
                            v.births(), v.failures(),
                            v.presentNow() ? Lang.t("st.present") : Lang.t("st.absent")),
                            v.births() > 0 ? GREEN : WHITE));
                }
            }
        }
        return out;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        String[] keys = {"ui.tab_overview", "ui.tab_failures", "ui.tab_villagers"};
        g.text(this.font, Lang.f("st.tab_now", Lang.t(keys[tab])), 6, 26, CYAN);
    }
}
