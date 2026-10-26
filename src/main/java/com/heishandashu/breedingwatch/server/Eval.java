package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.view.Views;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.food.VillagerFood;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 单个村民的繁殖条件评估。
 *
 * <p>判定依据 26.3 的原版代码（实际读 jar 核对过，不是照抄 1.21.1 的记忆）：</p>
 * <ul>
 *   <li>{@code Villager#canBreed()}：foodLevel + 背包营养点 &gt;= 12 且 不在睡觉 且 age == 0</li>
 *   <li>背包营养点：{@code DataComponents.VILLAGER_FOOD -> VillagerFood.nutrition() * count}
 *       （1.21.1 的 {@code Villager.FOOD_POINTS} 静态表在 26.3 已删除）</li>
 *   <li>{@code VillagerMakeLove}：350 刻上限，出生在 开始+275+rand(50)，且需距离平方 &lt;= 5（约 2.24 格）</li>
 *   <li>{@code takeVacantBed}：HOME POI、半径 48、maxTickets=1、createPath(pos,1).canReach()</li>
 *   <li>16 格外 HOME 记忆会在夜间校验时被丢弃（ValidateNearbyPoi）</li>
 *   <li>作息来自数据包 {@code data/minecraft/timeline/villager_schedule.json}：
 *       10 IDLE / 2000 WORK / 9000 MEET / 11000 IDLE / 12000 REST</li>
 * </ul>
 */
public final class Eval {

    public static final int FOOD_NEED = Villager.BREEDING_FOOD_THRESHOLD;   // 12
    public static final int POI_RADIUS = 48;
    public static final int HOME_VALIDATE_DIST = 16;
    public static final int PAIR_RANGE = 8;
    public static final int MAKELOVE_RUN = 350;
    public static final int BIRTH_DELAY_MIN = 275;
    public static final int BIRTH_DELAY_RAND = 50;
    public static final int BIRTH_COOLDOWN = 6000;
    public static final int BABY_GROW = 24000;

    /**
     * 原版村民作息的分段表（与 jar 内 villager_schedule.json 的 keyframes 一致）。
     * 26.3 把它移到了数据包，这里保留一份"只读快照"用于算"还有多久到空闲期"，
     * 数据包改过时数字会不准，但不会影响正确性以外的任何东西。
     */
    private static final int[] SEG_START = {10, 2000, 9000, 11000, 12000};
    private static final String[] SEG_ACT = {"idle", "work", "meet", "idle", "rest"};

    private static Field foodField;

    private Eval() {}

    /** 评估上下文：统计存储、寻路探针（带预算与缓存）、同床冲突表、当前时间。 */
    public record Ctx(StatsStore stats, Probe probe, Map<BlockPos, List<Integer>> homeOwners,
                      long now, int dayTime, String schedAct) {}

    /** 评估结果，便于服务端后续（配对/失败统计）复用。 */
    public record Outcome(Views.VillagerView view, List<Views.Condition> hard, boolean ready, BedScan bed) {}

    /**
     * 寻路探针：带缓存与每次扫描的预算。
     * 一次扫描评估几百名村民时，A* 是唯一可能拖垮服务端的东西，必须限流。
     */
    public static final class Probe {
        private static final long TTL = 200L;
        private final Map<String, Boolean> cache = new HashMap<>();
        private final Map<String, Long> stamped = new HashMap<>();
        private int budget;

        public Probe(int budgetPerScan) {
            this.budget = budgetPerScan;
        }

        public void newScan(int budgetPerScan) {
            this.budget = budgetPerScan;
        }

        /** @return null 表示本次未检测（预算耗尽），不能当成 false */
        public Boolean canReach(Mob mob, BlockPos pos, long now) {
            String key = mob.getId() + "|" + pos.asLong();
            Long at = stamped.get(key);
            if (at != null && now - at < TTL) {
                return cache.get(key);
            }
            if (budget <= 0) {
                return null;
            }
            budget--;
            boolean ok;
            try {
                Path p = mob.getNavigation().createPath(pos, 1);
                ok = p != null && p.canReach();
            } catch (Throwable t) {
                ok = false;
            }
            cache.put(key, ok);
            stamped.put(key, now);
            if (cache.size() > 4000) {
                cache.clear();
                stamped.clear();
            }
            return ok;
        }
    }

    /** 一次扫描得到的床位信息。 */
    public record BedScan(int bedsTotal, int bedsFree, int checked, int reachable, boolean measured,
                          String nearestFreeBed, boolean hasHome, BlockPos homePos, boolean homeExists,
                          int homeTickets, int homeDist, Boolean homeReachable, boolean occupiedByOther,
                          boolean dropRisk, String riskReason) {}

    public static Outcome evaluate(ServerLevel level, Villager v, Ctx ctx) {
        Brain<Villager> brain = v.getBrain();
        List<Views.Condition> conds = new ArrayList<>();
        List<Views.Condition> hard = new ArrayList<>();

        // ---------- 食物 ----------
        int foodLevel = readFoodLevel(v);
        Map<String, Integer> items = new LinkedHashMap<>();
        Map<String, Integer> foodPoints = new LinkedHashMap<>();
        int invPoints = 0;
        var inv = v.getInventory();
        int filled = 0;
        var contents = inv.getItems();
        for (int i = 0; i < contents.size(); i++) {
            ItemStack st = contents.get(i);
            if (st.isEmpty()) {
                continue;
            }
            filled++;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            items.merge(id, st.getCount(), Integer::sum);
            Integer pts = foodNutrition(st);
            if (pts != null && pts > 0) {
                int add = pts * st.getCount();
                foodPoints.merge(id, add, Integer::sum);
                invPoints += add;
            }
        }
        int total = (foodLevel < 0 ? 0 : foodLevel) + invPoints;
        boolean invFull = filled >= inv.getContainerSize();

        // ---------- 床位 ----------
        BedScan bed = scanBeds(level, v, ctx, brain);

        // ---------- 活动 / 作息 ----------
        String currentAct = brain.getActiveNonCoreActivity().map(a -> nullToEmpty(a.getName())).orElse("");
        List<String> allActs = brain.getActiveActivities().stream().map(a -> nullToEmpty(a.getName())).sorted().toList();
        String schedAct = nullToEmpty(ctx.schedAct());
        boolean hasJobSite = brain.hasMemoryValue(MemoryModuleType.JOB_SITE);
        boolean hasMeeting = brain.hasMemoryValue(MemoryModuleType.MEETING_POINT);
        String effAct = effectiveActivity(schedAct, hasJobSite, hasMeeting);
        int ticksToNextAct = ticksToNextChange(ctx.dayTime());
        int nextIdle = ticksToIdle(ctx.dayTime(), a -> effectiveActivity(a, hasJobSite, hasMeeting));
        boolean sleeping = v.isSleeping();
        boolean walkBusy = brain.hasMemoryValue(MemoryModuleType.WALK_TARGET);
        boolean cantReach = brain.hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE);

        // ---------- 惊慌 / 袭击 ----------
        String panicReason = "";
        boolean panicking = false;
        LivingEntity hostile = brain.getMemory(MemoryModuleType.NEAREST_HOSTILE).orElse(null);
        if (hostile != null && hostile.isAlive()) {
            panicking = true;
            double d = Math.sqrt(hostile.distanceToSqr(v));
            panicReason = Lang.f("ev.panic_hostile", hostile.getName().getString(),
                    String.format(Locale.ROOT, "%.1f", d));
        }
        if (brain.hasMemoryValue(MemoryModuleType.HURT_BY)) {
            panicking = true;
            panicReason = panicReason.isEmpty() ? Lang.t("ev.hurt") : panicReason + Lang.t("ev.hurt_plus");
        }
        boolean raidNearby = level.getRaidAt(v.blockPosition()) != null;

        // ---------- 配对 ----------
        Integer targetSeq = null;
        String targetName = null;
        Double targetDist = null;
        Boolean targetReady = null;
        List<String> targetMissing = List.of();
        boolean hasTarget = false;
        AgeableMob rawTarget = brain.getMemory(MemoryModuleType.BREED_TARGET).orElse(null);
        if (rawTarget instanceof Villager tv && tv.isAlive()) {
            hasTarget = true;
            targetSeq = ctx.stats().seqOf(tv.getUUID());
            targetName = tv.getName().getString();
            targetDist = Math.sqrt(tv.distanceToSqr(v));
            targetReady = tv.canBreed();
            List<String> missing = new ArrayList<>();
            if (tv.getAge() != 0) {
                missing.add(Cond.shortLabel(tv.getAge() > 0 ? Cond.COOLDOWN : Cond.ADULT));
            }
            if (!tv.canBreed() && tv.getAge() == 0) {
                missing.add(Lang.t("cd.target_food"));
            }
            targetMissing = missing;
        }
        int seq = ctx.stats().seqOf(v.getUUID());
        ctx.stats().note(seq, v.getName().getString(), professionName(v));
        Integer lastSeq = ctx.stats().lastPartnerSeq(seq);

        // ---------- 硬性条件 ----------
        int age = v.getAge();
        hard.add(age <= 0
                ? Cond.ok(Cond.ADULT, Lang.t("cd.adult_ok"))
                : Cond.bad(Cond.ADULT, Lang.f("cd.adult_baby", Json.ticksToHuman(-age))));
        hard.add(age > 0
                ? Cond.bad(Cond.COOLDOWN, Lang.f("cd.cool_bad", Json.ticksToHuman(age)))
                : Cond.ok(Cond.COOLDOWN, Lang.f("cd.cool_ok", Json.ticksToHuman(BIRTH_COOLDOWN))));
        hard.add(total >= FOOD_NEED
                ? Cond.ok(Cond.FOOD, Lang.f("cd.food_ok", total, foodLevel, invPoints, FOOD_NEED))
                : Cond.bad(Cond.FOOD, Lang.f("cd.food_bad", total, foodLevel, invPoints, FOOD_NEED,
                        FOOD_NEED - total)));
        hard.add(sleeping
                ? Cond.bad(Cond.AWAKE, Lang.t("cd.awake_bad"))
                : Cond.ok(Cond.AWAKE, Lang.t("cd.awake_ok")));
        hard.add("idle".equals(effAct)
                ? Cond.ok(Cond.ACTIVITY, Lang.f("cd.act_ok", actCn(currentAct), actCn(effAct)))
                : Cond.bad(Cond.ACTIVITY, Lang.f("cd.act_bad", actCn(currentAct), actCn(schedAct), actCn(effAct),
                        nextIdle < 0 ? "?" : Json.ticksToHuman(nextIdle))));
        hard.add(!panicking
                ? Cond.ok(Cond.CALM, Lang.t("cd.calm_ok"))
                : Cond.bad(Cond.CALM, Lang.f("cd.calm_bad", panicReason)));
        hard.add(!raidNearby
                ? Cond.ok(Cond.RAID, Lang.t("cd.raid_ok"))
                : Cond.bad(Cond.RAID, Lang.t("cd.raid_bad")));
        hard.add(bed.bedsTotal() == 0
                ? Cond.bad(Cond.BED_FREE, Lang.f("cd.bedfree_none", POI_RADIUS))
                : bed.bedsFree() == 0
                        ? Cond.bad(Cond.BED_FREE, Lang.f("cd.bedfree_full", POI_RADIUS, bed.bedsTotal()))
                        : Cond.ok(Cond.BED_FREE, Lang.f("cd.bedfree_ok", POI_RADIUS, bed.bedsTotal(),
                                bed.bedsFree())));
        if (bed.bedsFree() > 0) {
            if (bed.measured() && bed.reachable() == 0) {
                hard.add(Cond.bad(Cond.BED_REACH,
                        Lang.f("cd.bedreach_none", bed.bedsFree(), bed.checked())));
            } else if (bed.reachable() > 0) {
                hard.add(Cond.ok(Cond.BED_REACH,
                        Lang.f("cd.bedreach_ok", bed.reachable(), bed.checked())));
            } else {
                hard.add(Cond.soft(false, Cond.BED_REACH, Lang.t("cd.bedreach_skip")));
            }
        }

        boolean ready = hard.stream().allMatch(Views.Condition::ok);

        // ---------- 软性提示 ----------
        conds.addAll(hard);
        String dist = targetDist == null ? "?" : String.format(Locale.ROOT, "%.1f", targetDist);
        conds.add(Cond.soft(hasTarget, Cond.TARGET, hasTarget
                ? Lang.f("cd.target_has", targetSeq, targetName, dist, PAIR_RANGE)
                : Lang.f("cd.target_none", PAIR_RANGE)));
        conds.add(Cond.soft(!walkBusy, Cond.WALK_BUSY, walkBusy
                ? Lang.t("cd.walk_busy") : Lang.t("cd.walk_free")));
        conds.add(Cond.soft(bed.hasHome() && !bed.dropRisk(), Cond.HOME_BIND, bed.hasHome()
                ? (bed.dropRisk() ? Lang.f("cd.home_risk", bed.riskReason())
                        : Lang.f("cd.home_ok", posStr(bed.homePos())))
                : Lang.t("cd.home_none")));
        conds.add(Cond.soft(hasMeeting, Cond.BELL, hasMeeting
                ? Lang.f("cd.bell_ok", posStr(brain.getMemory(MemoryModuleType.MEETING_POINT)
                        .map(GlobalPos::pos).orElse(null)))
                : Lang.t("cd.bell_bad")));
        conds.add(Cond.soft(!invFull, Cond.INV_FULL, invFull
                ? Lang.f("cd.inv_full", inv.getContainerSize(), invPoints)
                : Lang.f("cd.inv_free", filled, inv.getContainerSize())));
        List<Integer> coOwners = bed.homePos() == null ? List.of()
                : ctx.homeOwners().getOrDefault(bed.homePos(), List.of()).stream().filter(s -> s != seq).toList();
        conds.add(Cond.soft(coOwners.isEmpty(), Cond.BED_CONFLICT, coOwners.isEmpty()
                ? Lang.t("cd.conflict_ok")
                : Lang.f("cd.conflict_bad", coOwners.stream().map(s -> "#" + s).toList(), posStr(bed.homePos()))));
        conds.add(Cond.soft(!cantReach, Cond.CANT_REACH, cantReach
                ? Lang.t("cd.reach_bad") : Lang.t("cd.reach_ok")));

        String shortTag = ready
                ? (hasTarget ? Lang.t("cd.tag_ready") : Lang.t("cd.tag_ready_no_target"))
                : firstBadTag(hard, Math.max(0, FOOD_NEED - total));
        String shortTagEn = shortTag;

        Views.Food foodView = new Views.Food(foodLevel, invPoints, total, FOOD_NEED,
                invPoints >= 2 * FOOD_NEED, invPoints < FOOD_NEED, items, foodPoints);
        String homeDim = bed.hasHome()
                ? brain.getMemory(MemoryModuleType.HOME).map(g -> g.dimension().identifier().toString()).orElse(null)
                : null;
        Views.Bed bedView = new Views.Bed(bed.hasHome(), bed.homePos() == null ? null : posStr(bed.homePos()),
                homeDim, bed.homeDist(), bed.homeExists(), bed.homeTickets(),
                bed.hasHome() && bed.homeTickets() == 0, bed.homeReachable(),
                bed.homeReachable() == null ? Lang.t("b.reach_skip")
                        : (bed.homeReachable() ? Lang.t("b.reach_yes") : Lang.t("b.reach_no")),
                bed.dropRisk(), bed.riskReason(),
                bed.bedsTotal(), bed.bedsFree(), bed.reachable(), bed.nearestFreeBed(), coOwners);
        Views.BrainInfo brainView = new Views.BrainInfo(currentAct, allActs, schedAct, effAct,
                ticksToNextAct, nextIdle, ctx.dayTime(), Json.dayClock(level.getDefaultClockTime()),
                sleeping, walkBusy, panicking, panicReason, raidNearby, hasJobSite, hasMeeting,
                posStr(brain.getMemory(MemoryModuleType.MEETING_POINT).map(GlobalPos::pos).orElse(null)), cantReach);
        Views.Pairing pairingView = new Views.Pairing(hasTarget, targetSeq, targetName, targetDist, targetReady,
                targetMissing, false, null, null,
                lastSeq == null ? null : ("#" + lastSeq + " " + ctx.stats().lastPartnerName(seq)),
                ctx.stats().lastPartnerOutcome(seq), ctx.stats().lastPartnerTick(seq));

        Views.VillagerView view = new Views.VillagerView(seq, v.getId(), v.getUUID().toString(),
                v.getName().getString(), professionName(v), v.getVillagerData().level(),
                v.getOffers() == null ? 0 : v.getOffers().size(),
                v.isBaby(), age, shortTag, shortTagEn, ready, foodView, bedView, brainView, pairingView, conds,
                v.getX(), v.getY(), v.getZ());
        return new Outcome(view, hard, ready, bed);
    }

    // ------------------------------------------------------------------ 床位扫描

    private static BedScan scanBeds(ServerLevel level, Villager v, Ctx ctx, Brain<Villager> brain) {
        PoiManager pm = level.getPoiManager();
        BlockPos center = v.blockPosition();
        int bedsTotal = (int) pm.getCountInRange(
                h -> h.is(PoiTypes.HOME), center, POI_RADIUS, PoiManager.Occupancy.ANY);
        int bedsFree = (int) pm.getCountInRange(
                h -> h.is(PoiTypes.HOME), center, POI_RADIUS, PoiManager.Occupancy.HAS_SPACE);

        List<PoiRecord> checked = pm.getInRange(
                        h -> h.is(PoiTypes.HOME), center, POI_RADIUS, PoiManager.Occupancy.HAS_SPACE)
                .sorted(Comparator.comparingDouble(r -> r.getPos().distToCenterSqr(v.position())))
                .limit(3)
                .toList();
        int reachable = 0;
        int measured = 0;
        String nearest = null;
        for (PoiRecord r : checked) {
            if (nearest == null) {
                nearest = posStr(r.getPos());
            }
            Boolean ok = ctx.probe().canReach(v, r.getPos(), ctx.now());
            if (ok == null) {
                continue;
            }
            measured++;
            if (ok) {
                reachable++;
            }
        }

        GlobalPos home = brain.getMemory(MemoryModuleType.HOME).orElse(null);
        boolean hasHome = home != null;
        BlockPos homePos = hasHome ? home.pos() : null;
        boolean exists = false;
        int tickets = -1;
        int dist = -1;
        Boolean homeReachable = null;
        boolean occupiedOther = false;
        if (hasHome) {
            boolean sameDim = home.dimension().equals(level.dimension());
            exists = sameDim && pm.exists(homePos, h -> h.is(PoiTypes.HOME));
            dist = (int) Math.round(Math.sqrt(homePos.distToCenterSqr(v.position())));
            if (exists) {
                // 26.3 没有 PoiManager#getFreeTickets，改读 DebugPoiInfo（原版调试通道，语义等同）
                var info = pm.getDebugPoiInfo(homePos);
                tickets = info == null ? -1 : info.freeTicketCount();
                BlockState st = level.getBlockState(homePos);
                occupiedOther = st.is(BlockTags.BEDS) && st.getValue(AbstractBedBlock.OCCUPIED) && !v.isSleeping();
            }
            homeReachable = exists ? ctx.probe().canReach(v, homePos, ctx.now()) : Boolean.FALSE;
        }
        boolean dropRisk = false;
        String reason = "";
        if (hasHome) {
            List<String> why = new ArrayList<>();
            if (!exists) {
                why.add(Lang.t("risk.poi_gone"));
            }
            if (dist > HOME_VALIDATE_DIST) {
                why.add(Lang.f("risk.far", dist, HOME_VALIDATE_DIST));
            }
            if (occupiedOther) {
                why.add(Lang.t("risk.occupied"));
            }
            if (tickets > 0) {
                why.add(Lang.f("risk.tickets", tickets));
            }
            if (Boolean.FALSE.equals(homeReachable)) {
                why.add(Lang.t("risk.unreachable"));
            }
            dropRisk = !why.isEmpty();
            reason = String.join(Lang.t("list.sep2"), why);
        }
        return new BedScan(bedsTotal, bedsFree, measured, reachable, measured > 0, nearest,
                hasHome, homePos, exists, tickets, dist, homeReachable, occupiedOther, dropRisk, reason);
    }

    // ------------------------------------------------------------------ 作息辅助

    /** 当前日程活动（26.3 从数据包的 timeline 读，位置相关）。 */
    public static String currentScheduleActivity(ServerLevel level, Villager v) {
        Activity a = level.environmentAttributes()
                .getValue(EnvironmentAttributes.VILLAGER_ACTIVITY, v.position());
        return a == null ? "" : nullToEmpty(a.getName());
    }

    private static int segIndex(int dayTime) {
        int idx = 0;
        for (int i = 0; i < SEG_START.length; i++) {
            if (SEG_START[i] <= dayTime) {
                idx = i;
            }
        }
        return idx;
    }

    private static int ticksToNextChange(int dayTime) {
        for (int start : SEG_START) {
            if (start > dayTime) {
                return start - dayTime;
            }
        }
        return 24000 - dayTime + SEG_START[0];
    }

    private static int ticksToIdle(int dayTime, java.util.function.Function<String, String> effective) {
        int idx = segIndex(dayTime);
        if ("idle".equals(effective.apply(SEG_ACT[idx]))) {
            return 0;
        }
        for (int step = 1; step <= SEG_START.length; step++) {
            int i = (idx + step) % SEG_START.length;
            int base = i > idx ? 0 : 24000;
            if ("idle".equals(effective.apply(SEG_ACT[i]))) {
                return base + SEG_START[i] - dayTime;
            }
        }
        return -1;
    }

    /** WORK 需要 JOB_SITE 记忆、MEET 需要 MEETING_POINT 记忆，否则 Brain 回落到默认活动 IDLE。 */
    private static String effectiveActivity(String schedAct, boolean hasJobSite, boolean hasMeeting) {
        if ("work".equals(schedAct) && !hasJobSite) {
            return "idle";
        }
        if ("meet".equals(schedAct) && !hasMeeting) {
            return "idle";
        }
        return schedAct;
    }

    // ------------------------------------------------------------------ 小工具

    private static String firstBadTag(List<Views.Condition> hard, int missingFood) {
        for (Views.Condition c : hard) {
            if (!c.ok()) {
                if (Cond.FOOD.equals(c.code())) {
                    return Lang.f("tag.food_missing", missingFood);
                }
                return Cond.shortLabel(c.code());
            }
        }
        return Lang.t("cd.tag_ready");
    }

    public static String actCn(String name) {
        String t = Lang.peek("act." + name);
        return t != null ? t : name;
    }

    public static String professionName(Villager v) {
        try {
            Holder<?> h = v.getVillagerData().profession();
            var key = h.unwrapKey();
            return key.map(k -> k.identifier().getPath()).orElse("none");
        } catch (Throwable t) {
            return "none";
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String posStr(BlockPos p) {
        return p == null ? null : p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /** 单个物品的村民营养点（26.3：数据组件，不再是静态 Map）。 */
    public static Integer foodNutrition(ItemStack st) {
        VillagerFood food = st.get(DataComponents.VILLAGER_FOOD);
        return food == null ? null : food.nutrition();
    }

    /**
     * {@code Villager#foodLevel} 仍是 private int（26.3 没给访问器），只能反射读。
     * 失败时返回 -1，界面会显示"未知"而不是编一个数字。
     */
    private static int readFoodLevel(Villager v) {
        try {
            if (foodField == null) {
                Field f = Villager.class.getDeclaredField("foodLevel");
                f.setAccessible(true);
                foodField = f;
            }
            Object o = foodField.get(v);
            return o instanceof Number n ? n.intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
