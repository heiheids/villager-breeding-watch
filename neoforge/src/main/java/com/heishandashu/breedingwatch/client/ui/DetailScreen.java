package com.heishandashu.breedingwatch.client.ui;

import com.heishandashu.breedingwatch.client.ClientHub;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.view.Views;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** 单个村民的详情界面：食物 / 床位 / 作息 / 配对 / 全部条件。 */
public final class DetailScreen extends TextScreen {

    private final int eid;

    public DetailScreen(int eid) {
        super(Lang.t("ui.detail_title"));
        this.eid = eid;
    }

    public int eid() {
        return eid;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.btn_refresh")),
                b -> ClientHub.sendCmd("{\"c\":\"detailStart\",\"eid\":" + eid + "}"))
                .bounds(this.width / 2 - 150, 22, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.btn_stats")), b -> {
            ClientHub.sendCmd("{\"c\":\"stats\"}");
            Minecraft.getInstance().gui.setScreen(new StatsScreen());
        }).bounds(this.width / 2 - 48, 22, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal(Lang.t("ui.btn_close")),
                b -> this.onClose()).bounds(this.width / 2 + 54, 22, 96, 20).build());
    }

    @Override
    protected List<Line> buildLines() {
        List<Line> out = new ArrayList<>();
        Views.VillagerView v = ClientHub.DATA.view(eid);
        if (v == null) {
            out.add(l(Lang.t("d.fetching"), YELLOW));
            return out;
        }
        setTitle(Lang.f("d.title", v.seq(), v.name(), ProfNames.profession(v.profession())));
        out.add(l(Lang.f(v.ready() ? "d.ready" : "d.not_ready", v.shortTag()), v.ready() ? GREEN : RED));
        out.add(l(Lang.f("d.ids", v.seq(), v.eid(), v.uuid()), GRAY));
        String agePhrase = v.baby() ? Lang.f("d.age_baby", Json.ticksToHuman(-v.age()))
                : v.age() > 0 ? Lang.f("d.age_cool", Json.ticksToHuman(v.age())) : Lang.t("d.age_ok");
        out.add(l(Lang.f("d.age", v.age(), agePhrase), WHITE));
        out.add(l(Lang.f("d.prof", ProfNames.profession(v.profession()), v.profLevel(), v.offers()), WHITE));
        out.add(l("", WHITE));

        Views.Food f = v.food();
        out.add(l(Lang.f("f.head", f.need(), f.total(), sym(f.total() >= f.need())),
                f.total() >= f.need() ? GREEN : RED));
        out.add(l(Lang.f("f.sub", f.foodLevel(), f.invPoints()), WHITE));
        out.add(l(Lang.t("f.rule"), GRAY));
        out.add(l(Lang.f("f.inv", f.items() == null || f.items().isEmpty() ? Lang.t("d.empty") : f.items()), GRAY));
        if (f.foodPoints() != null && !f.foodPoints().isEmpty()) {
            out.add(l(Lang.f("f.points", f.foodPoints()), GRAY));
        }
        out.add(l("", WHITE));

        Views.Bed b = v.bed();
        out.add(l(Lang.f("b.head", b.bedsTotal(), b.bedsFree(), b.bedsFreeReachable()),
                b.bedsFree() > 0 ? GREEN : RED));
        out.add(l(Lang.f("b.home", b.hasHome() ? Lang.f("b.home_at", b.homePos(), b.homeDim(), b.homeDist())
                : Lang.t("b.no_home")), b.hasHome() && !b.dropRisk() ? GREEN : ORANGE));
        if (b.hasHome()) {
            out.add(l(Lang.f("b.poi", sym(b.homeExists()), b.homeFreeTickets(), sym(b.homeOwned()),
                    b.reachable() == null ? Lang.t("b.not_checked") : sym(b.reachable()), b.reachableHow()), WHITE));
            out.add(l(Lang.f("b.drop", b.dropRisk() ? Lang.t("b.drop_yes") : Lang.t("b.drop_stable"),
                    b.riskReason() == null || b.riskReason().isEmpty() ? Lang.t("b.no_risk") : b.riskReason()),
                    b.dropRisk() ? RED : GREEN));
            if (b.conflictWith() != null && !b.conflictWith().isEmpty()) {
                out.add(l(Lang.f("b.conflict", b.conflictWith()), ORANGE));
            }
        }
        out.add(l(Lang.f("b.nearest", b.nearestFreeBed() == null ? Lang.t("d.none") : b.nearestFreeBed()), GRAY));
        out.add(l("", WHITE));

        Views.BrainInfo bi = v.brain();
        out.add(l(Lang.f("a.head", ProfNames.activity(bi.activity()),
                bi.allActivities().stream().map(ProfNames::activity).toList()),
                "idle".equals(bi.effectiveActivity()) ? GREEN : ORANGE));
        out.add(l(Lang.f("a.sched", ProfNames.activity(bi.scheduleActivity()),
                ProfNames.activity(bi.effectiveActivity())), WHITE));
        out.add(l(Lang.t("a.sched_note"), GRAY));
        out.add(l(Lang.f("a.next", Json.ticksToHuman(bi.ticksToNextActivity()),
                bi.nextIdleInTicks() <= 0 ? Lang.t("a.in_idle") : Json.ticksToHuman(bi.nextIdleInTicks())), WHITE));
        out.add(l(Lang.f("a.time", bi.timeOfDay(), bi.dayTime()), GRAY));
        out.add(l(Lang.f("a.flags", sym(bi.sleeping()), sym(bi.walkBusy()), sym(bi.panicking()),
                bi.panicReason() == null || bi.panicReason().isEmpty() ? "" : Lang.f("a.panic", bi.panicReason())),
                WHITE));
        out.add(l(Lang.f("a.poi", sym(bi.raidNearby()), sym(bi.hasJobSite()), sym(bi.hasMeetingPoint()),
                bi.meetingPoint() == null ? "" : " @ " + bi.meetingPoint()), WHITE));
        out.add(l("", WHITE));

        Views.Pairing p = v.pairing();
        out.add(l(Lang.f("p.head", p.hasTarget()
                ? Lang.f("p.target", p.targetSeq(), p.targetName(),
                        String.format(Locale.ROOT, "%.2f", p.targetDist()))
                : Lang.t("p.no_target")), p.hasTarget() ? GREEN : ORANGE));
        if (p.hasTarget()) {
            out.add(l(Lang.f("p.obj", sym(Boolean.TRUE.equals(p.targetReady())),
                    p.targetMissing().isEmpty() ? ""
                            : Lang.f("p.obj_missing", String.join(Lang.t("list.sep"), p.targetMissing()))), WHITE));
            out.add(l(Lang.t("p.rule"), GRAY));
        }
        out.add(l(Lang.f("p.courting", sym(p.courting()), p.courtingStartTick() == null ? ""
                : Lang.f("p.courting_at", p.courtingStartTick(), p.expectedBirthTick())), CYAN));
        out.add(l(Lang.f("p.last", p.lastPartner() == null ? Lang.t("d.none") : p.lastPartner(),
                p.lastPartnerOutcome() == null ? Lang.t("d.none") : StatsScreen.outcomeText(p.lastPartnerOutcome()),
                p.lastPartnerTick() == null ? "" : Lang.f("p.last_tick", p.lastPartnerTick())), YELLOW));
        out.add(l("", WHITE));

        out.add(l(Lang.t("c.head"), YELLOW));
        for (Views.Condition c : v.conditions()) {
            String sign = c.soft() ? (c.ok() ? "○" : "○!") : sym(c.ok());
            int color = c.soft() ? (c.ok() ? GRAY : ORANGE) : (c.ok() ? GREEN : RED);
            out.add(l("  " + sign + " " + c.label() + " [" + c.code() + "]", color));
            for (String part : wrap(c.detail(), 360)) {
                out.add(l("      " + part, c.ok() ? DIM : color));
            }
        }
        return out;
    }

    @Override
    public void onClose() {
        ClientHub.sendCmd("{\"c\":\"detailStop\"}");
        Minecraft.getInstance().gui.setScreen(null);
    }
}
