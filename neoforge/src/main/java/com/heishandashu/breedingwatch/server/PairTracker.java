package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.view.Views;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * 配对状态机与失败推断。
 *
 * <p>原版没有"繁殖失败事件"，所以这里只能靠配对状态机倒推：
 * 从双方 {@code BREED_TARGET} 互指开始计时，跑满 {@code VillagerMakeLove} 的窗口后
 * 按当时的状态归类原因。所有结论都是<b>推断</b>，界面上如实标注。</p>
 *
 * <p>与扫描分开成独立类的原因：状态机（配对生命周期、去抖、失败归类）和
 * "扫谁、发给谁"是两件事，混在一个类里会让两边都难改。</p>
 */
public final class PairTracker {

    /** 一对村民的求爱过程。 */
    private static final class Couple {
        final int a;
        final int b;
        final long startTick;
        final int foodA;
        final int foodB;
        long lastSeen;
        boolean closeSeen;
        boolean bedChecked;
        boolean resolved;

        Couple(int a, int b, long startTick, int foodA, int foodB) {
            this.a = a;
            this.b = b;
            this.startTick = startTick;
            this.foodA = foodA;
            this.foodB = foodB;
            this.lastSeen = startTick;
        }
    }

    private static final Map<Integer, Couple> COUPLES = new HashMap<>();
    private static final Map<Integer, Long> LAST_FAIL = new HashMap<>();

    private PairTracker() {}

    /** 一对村民的求爱视图（供 WatchServer 拼进快照）。 */
    public record Courting(long startTick, long expectedBirthTick) {}

    /**
     * 推进所有配对的状态机，并返回 实体id -> 求爱信息（用于视图）。
     *
     * @param probe     寻路探针（取床失败判定要用）
     * @param stats     统计存储
     * @param villagers 本轮扫描到的村民
     */
    public static Map<Integer, Courting> tick(ServerLevel level, List<Villager> villagers, long now,
                                              Eval.Probe probe, StatsStore stats) {
        Map<Integer, Courting> byEid = new HashMap<>();
        Set<Integer> seenKeys = new HashSet<>();

        for (Villager v : villagers) {
            LivingEntity raw = v.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null);
            if (!(raw instanceof Villager t) || !t.isAlive()) {
                continue;
            }
            if (t.getBrain().getMemory(MemoryModuleType.BREED_TARGET).orElse(null) != v) {
                continue;   // 只看双向确认的配对
            }
            int key = pairKey(v.getId(), t.getId());
            seenKeys.add(key);
            Couple c = COUPLES.get(key);
            if (c == null) {
                c = new Couple(v.getId(), t.getId(), now, foodPoints(v), foodPoints(t));
                COUPLES.put(key, c);
                int sa = stats.seqOf(v.getUUID());
                int st = stats.seqOf(t.getUUID());
                stats.notePairing(sa, st, WatchServer.OUT_COURTING, now);
                stats.notePairing(st, sa, WatchServer.OUT_COURTING, now);
            }
            c.lastSeen = now;
            if (v.distanceToSqr(t) <= 5.0) {      // VillagerMakeLove 的判定是 distanceToSqr <= 5
                c.closeSeen = true;
            }
            byEid.put(v.getId(), new Courting(c.startTick, c.startTick + Eval.BIRTH_DELAY_MIN));
            byEid.put(t.getId(), new Courting(c.startTick, c.startTick + Eval.BIRTH_DELAY_MIN));

            if (!c.bedChecked && now >= c.startTick + Eval.BIRTH_DELAY_MIN) {
                c.bedChecked = true;
                Entity ea = level.getEntity(c.a);
                Entity eb = level.getEntity(c.b);
                if (ea instanceof Villager va && eb instanceof Villager vb) {
                    checkBedAtBirth(level, va, vb, c, now, probe, stats);
                }
            }
        }

        List<Integer> remove = new ArrayList<>();
        for (Map.Entry<Integer, Couple> e : COUPLES.entrySet()) {
            Couple c = e.getValue();
            if (c.resolved) {
                remove.add(e.getKey());
                continue;
            }
            boolean stale = now - c.lastSeen > 60 && !seenKeys.contains(e.getKey());
            long runTicks = now - c.startTick;
            Entity ea = level.getEntity(c.a);
            Entity eb = level.getEntity(c.b);
            boolean aged = ea instanceof Villager va && eb instanceof Villager vb
                    && va.getAge() > 0 && vb.getAge() > 0;
            if (aged) {
                c.resolved = true;                  // 真的生了（冷却已开始）
                remove.add(e.getKey());
            } else if (stale || runTicks > Eval.MAKELOVE_RUN + 80) {
                String code;
                String detail;
                if (!c.closeSeen) {
                    code = Cond.TARGET_LOST;
                    detail = Lang.t("fail.TARGET_LOST.detail");
                } else {
                    code = Cond.MATE_TIMEOUT;
                    detail = Lang.f("fail.MATE_TIMEOUT.detail", Eval.MAKELOVE_RUN);
                }
                if (ea instanceof Villager va && eb instanceof Villager vb && c.closeSeen) {
                    if (va.isSleeping() || vb.isSleeping()) {
                        code = Cond.SLEEPING;
                        detail = Lang.t("fail.SLEEPING.detail");
                    } else if (hasHostile(va) || hasHostile(vb)) {
                        code = Cond.PANIC;
                        detail = Lang.t("fail.PANIC.detail");
                    }
                }
                c.resolved = true;
                if (ea instanceof Villager va && eb instanceof Villager vb) {
                    recordFailure(va, vb, now, code, detail, stats);
                }
                remove.add(e.getKey());
            }
        }
        for (Integer k : remove) {
            COUPLES.remove(k);
        }
        return byEid;
    }

    private static int pairKey(int x, int y) {
        return x < y ? (x * 31 + y) : (y * 31 + x);
    }

    private static boolean hasHostile(Villager v) {
        var brain = v.getBrain();
        return brain.hasMemoryValue(MemoryModuleType.NEAREST_HOSTILE)
                || brain.hasMemoryValue(MemoryModuleType.HURT_BY);
    }

    /**
     * 复刻 VillagerMakeLove 的取床步骤：先吃食物，再 takeVacantBed。
     * 若取床失败 -> 记入失败统计（食物已被吃掉但没有婴儿），
     * 这正是"投喂足够却不生"最隐蔽的原因。
     */
    private static void checkBedAtBirth(ServerLevel level, Villager va, Villager vb, Couple c, long now,
                                        Eval.Probe probe, StatsStore stats) {
        String[] failure = firstBedFailure(level, probe, va, vb);
        if (failure == null) {
            return;
        }
        if (va.getAge() > 0 && vb.getAge() > 0) {
            return;   // 实际已生产
        }
        boolean ateA = foodPoints(va) < c.foodA;
        boolean ateB = foodPoints(vb) < c.foodB;
        String eaten = (ateA || ateB)
                ? Lang.f("fail.eaten", va.getName().getString(), c.foodA, foodPoints(va),
                        vb.getName().getString(), c.foodB, foodPoints(vb))
                : "";
        recordFailure(va, vb, now, failure[0], failure[1] + eaten, stats);
    }

    /** @return null 表示能找到可用床；否则 {code, detail} */
    private static String[] firstBedFailure(ServerLevel level, Eval.Probe probe, Villager... parents) {
        PoiManager pm = level.getPoiManager();
        int totalBeds = 0;
        int freeBeds = 0;
        for (Villager p : parents) {
            BlockPos center = p.blockPosition();
            totalBeds += (int) pm.getCountInRange(
                    h -> h.is(PoiTypes.HOME), center, Eval.POI_RADIUS, PoiManager.Occupancy.ANY);
            freeBeds += (int) pm.getCountInRange(
                    h -> h.is(PoiTypes.HOME), center, Eval.POI_RADIUS, PoiManager.Occupancy.HAS_SPACE);
            if (freeBeds > 0) {
                List<net.minecraft.world.entity.ai.village.poi.PoiRecord> candidates = pm.getInRange(
                                h -> h.is(PoiTypes.HOME), center, Eval.POI_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                        .sorted(Comparator.comparingDouble(r -> r.getPos().distToCenterSqr(p.position())))
                        .limit(4)
                        .toList();
                for (var r : candidates) {
                    Boolean ok = probe.canReach(p, r.getPos(), level.getDefaultClockTime());
                    if (Boolean.TRUE.equals(ok)) {
                        return null;
                    }
                }
            }
        }
        if (totalBeds == 0) {
            return new String[] {Cond.NO_BED, Lang.f("fail.NO_BED.detail", Eval.POI_RADIUS)};
        }
        if (freeBeds == 0) {
            return new String[] {Cond.ALL_BEDS_TAKEN, Lang.f("fail.ALL_BEDS_TAKEN.detail", totalBeds)};
        }
        return new String[] {Cond.BED_UNREACHABLE, Lang.f("fail.BED_UNREACHABLE.detail", freeBeds)};
    }

    /** 记一对失败（双方各一条），同一对村民 30 秒内不重复记。 */
    private static void recordFailure(Villager va, Villager vb, long now, String code, String detail,
                                      StatsStore stats) {
        int key = pairKey(va.getId(), vb.getId());
        Long prev = LAST_FAIL.get(key);
        if (prev != null && now - prev < 600) {
            return;
        }
        LAST_FAIL.put(key, now);
        String time = Json.dayClock(((ServerLevel) va.level()).getDefaultClockTime());
        int sa = stats.seqOf(va.getUUID());
        int sb = stats.seqOf(vb.getUUID());
        stats.addFailure(new Views.Failure(now, time, sa, va.getName().getString(), code,
                Cond.label(code), detail + Lang.f("fail.with_target", sb, vb.getName().getString())));
        stats.addFailure(new Views.Failure(now, time, sb, vb.getName().getString(), code,
                Cond.label(code), detail + Lang.f("fail.with_target", sa, va.getName().getString())));
        stats.notePairing(sa, sb, WatchServer.OUT_FAIL + code, now);
        stats.notePairing(sb, sa, WatchServer.OUT_FAIL + code, now);
    }

    /** 背包内有效食物点（用于判断求爱期间是否已经把食物吃掉）。 */
    static int foodPoints(Villager v) {
        int sum = 0;
        var contents = v.getInventory().getItems();
        for (int i = 0; i < contents.size(); i++) {
            Integer pts = Eval.foodNutrition(contents.get(i));
            if (pts != null && pts > 0) {
                sum += pts * contents.get(i).getCount();
            }
        }
        return sum;
    }
}
