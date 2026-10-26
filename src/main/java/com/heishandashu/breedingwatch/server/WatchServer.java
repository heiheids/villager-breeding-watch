package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.i18n.Lang;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.net.Payloads;
import com.heishandashu.breedingwatch.view.Views;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 服务端侧：按玩家偏好扫描村民、推送快照、统计出生/失败、追踪配对。
 *
 * <p>一个玩家一份 {@link Prefs}；扫描间隔、半径、上限都可调，"常开"模式放宽范围。
 * 每次扫描的寻路探测有预算，避免一次评估几百名村民把服务端拖死。</p>
 */
public final class WatchServer {

    public static final StatsStore STATS = new StatsStore();

    /** 配对/出生在统计里存的代码令牌（显示时才翻译）。 */
    public static final String OUT_COURTING = "courting";
    public static final String OUT_FAIL = "fail:";
    public static final String OUT_BIRTH = "birth:#";

    /** 单个玩家的观测偏好。 */
    public record Prefs(boolean enabled, int radius, int interval, boolean allLines, boolean allTags, String lang) {
        public static final Prefs DEFAULT = new Prefs(false, 48, 10, false, false, null);

        /** 任意一层需要显示时必须扫描（常开模式不看总开关）。 */
        public boolean active() {
            return enabled || allLines || allTags;
        }

        /** 常开模式：不受总开关影响，扫描范围与上限都放宽。 */
        public boolean allMode() {
            return allLines || allTags;
        }
    }

    private static final Map<UUID, Prefs> PREFS = new HashMap<>();
    private static final Map<UUID, Integer> DETAIL = new HashMap<>();
    private static final Eval.Probe PROBE = new Eval.Probe(24);
    /**
     * 上次扫描的游戏刻。
     *
     * <p>**不要再用 {@code Long.MIN_VALUE} 当"从未扫描"的哨兵**：这样写会让
     * {@code now - last} 整数溢出成负数，恒小于 gate，于是每一 tick 都被跳过，
     * 扫描永远不会发生 —— 现场表现就是"一直等待服务器数据"、日志里只有 prefs 没有"扫描=N"。
     * 用"条目不存在"本身表达"从未扫描"，值不参与任何算术。</p>
     */
    private static final Map<UUID, Long> LAST_SCAN = new HashMap<>();
    private static final Map<UUID, Boolean> WAS_ACTIVE = new HashMap<>();
    private static final Set<UUID> BIRTH_SEEN = new LinkedHashSetBounded<>(4000);
    private static int scans;

    private WatchServer() {}

    // ------------------------------------------------------------------ 客户端指令

    private record CmdMsg(String c, Boolean enabled, Integer radius, Integer interval, Integer eid,
                          Boolean allLines, Boolean allTags, String code) {}

    /** 玩家进世界：主动要求客户端把开关重发一遍（避免时序上丢包导致服务器一直用默认关）。 */
    public static void onPlayerJoin(ServerPlayer sp) {
        LAST_SCAN.remove(sp.getUUID());     // 移除 = 下一 tick 立刻扫描一次
        send(sp, new Payloads.Ui("{\"c\":\"sync\"}"));
        VbwMod.LOG.info("[VBW] {} 进入世界，已要求客户端重发开关", sp.getName().getString());
    }

    public static void onPlayerLeave(UUID uuid) {
        PREFS.remove(uuid);
        DETAIL.remove(uuid);
        LAST_SCAN.remove(uuid);
        WAS_ACTIVE.remove(uuid);
    }

    public static void onCommand(Player playerEntity, String json) {
        if (!(playerEntity instanceof ServerPlayer sp)) {
            return;
        }
        CmdMsg m;
        try {
            m = Json.COMPACT.fromJson(json, CmdMsg.class);
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 无法解析客户端指令：{}", e.toString());
            return;
        }
        if (m == null || m.c() == null) {
            return;
        }
        switch (m.c()) {
            case "prefs" -> {
                int radius = clamp(m.radius() == null ? 48 : m.radius(), 8, 112);
                int interval = clamp(m.interval() == null ? 10 : m.interval(), 2, 100);
                Prefs old = PREFS.get(sp.getUUID());
                String lang = m.code() != null ? Lang.normalize(m.code())
                        : (old != null ? old.lang() : null);
                PREFS.put(sp.getUUID(), new Prefs(Boolean.TRUE.equals(m.enabled()), radius, interval,
                        Boolean.TRUE.equals(m.allLines()), Boolean.TRUE.equals(m.allTags()), lang));
                LAST_SCAN.remove(sp.getUUID());     // 开关变了：下一 tick 立刻扫一次，不用等间隔
                VbwMod.LOG.info("[VBW] 收到 {} 的开关：总开关={} 常开射线={} 常开名牌={} 半径={} 间隔={} 语言={}",
                        sp.getName().getString(), Boolean.TRUE.equals(m.enabled()),
                        Boolean.TRUE.equals(m.allLines()), Boolean.TRUE.equals(m.allTags()),
                        radius, interval, lang);
            }
            case "lang" -> {
                Prefs old = PREFS.getOrDefault(sp.getUUID(), Prefs.DEFAULT);
                String lang = Lang.normalize(m.code());
                PREFS.put(sp.getUUID(), new Prefs(old.enabled(), old.radius(), old.interval(), old.allLines(),
                        old.allTags(), lang));
                Lang.use(lang);
                try {
                    sendStats(sp);
                } finally {
                    Lang.release();
                }
            }
            case "detailStart" -> {
                if (m.eid() != null) {
                    DETAIL.put(sp.getUUID(), m.eid());
                    sendDetail(sp, m.eid());
                }
            }
            case "detailStop" -> DETAIL.remove(sp.getUUID());
            case "stats" -> sendStats(sp);
            default -> VbwMod.LOG.debug("[VBW] 未知指令 {}", m.c());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ------------------------------------------------------------------ 扫描与推送

    /** 每 tick 尾部调用：按玩家自己的节奏扫描并推送。 */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getDefaultClockTime();
        STATS.saveIfDirty(now);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Prefs prefs = PREFS.getOrDefault(sp.getUUID(), Prefs.DEFAULT);
            if (!prefs.active()) {
                if (Boolean.TRUE.equals(WAS_ACTIVE.remove(sp.getUUID()))) {
                    // 刚刚全部关闭：推一次空快照，清掉客户端残留的名牌与床线
                    Lang.use(prefs.lang());
                    try {
                        sendEmpty(sp);
                    } finally {
                        Lang.release();
                    }
                }
                continue;
            }
            WAS_ACTIVE.put(sp.getUUID(), Boolean.TRUE);

            // 节拍必须用"服务器 tick 计数"，不能用维度时钟：
            // 下界/末地是 has_fixed_time，getDefaultClockTime() 恒为 0，
            // 用它做闸门会导致"扫一次之后就再也不扫"，而且只在非主世界复现。
            long tickNow = server.getTickCount();
            Long last = LAST_SCAN.get(sp.getUUID());
            int gate = prefs.allMode() ? Math.max(prefs.interval(), 20) : prefs.interval();
            // 没有记录 = 从未扫描过：必须立刻扫。绝不要写成 now - Long.MIN_VALUE（会溢出）
            if (last != null && tickNow - last < gate) {
                continue;
            }
            LAST_SCAN.put(sp.getUUID(), tickNow);
            Lang.use(prefs.lang());
            try {
                scanAndSend(sp, prefs);
            } catch (Throwable t) {
                // 诊断类功能出问题的最大代价应该是"这一轮没有输出"，而不是把服务器带走
                VbwMod.LOG.warn("[VBW] 扫描失败：{}", t.toString());
            } finally {
                Lang.release();
            }
        }
    }

    private static void scanAndSend(ServerPlayer sp, Prefs prefs) {
        STATS.load(sp.level().getServer());
        ServerLevel level = (ServerLevel) sp.level();
        int radius = prefs.allMode() ? 100000 : prefs.radius();
        int cap = prefs.allMode() ? 600 : 112;
        PROBE.newScan(prefs.allMode() ? 64 : 24);

        AABB box = sp.getBoundingBox().inflate(radius);
        List<Villager> villagers = level.getEntitiesOfClass(Villager.class, box);
        scans++;
        if (scans % 10 == 1 || villagers.isEmpty()) {
            VbwMod.LOG.info("[VBW] 扫描={} 名村民（常开={} 半径={} 上限={} 玩家维度={}）",
                    villagers.size(), prefs.allMode(), radius, cap, level.dimension().identifier());
        }
        villagers.sort(Comparator.comparingDouble(v -> v.distanceToSqr(sp)));
        if (villagers.size() > cap) {
            villagers = new ArrayList<>(villagers.subList(0, cap));
        }
        Integer detailEid = DETAIL.get(sp.getUUID());
        if (detailEid != null) {
            Entity e = level.getEntity(detailEid);
            if (e instanceof Villager dv && dv.isAlive() && !villagers.contains(dv)) {
                villagers.add(dv);
            }
        }

        long now = level.getDefaultClockTime();
        int dayTime = (int) Math.floorMod(now, 24000L);

        // 同床冲突表
        Map<BlockPos, List<Integer>> owners = new HashMap<>();
        for (Villager v : villagers) {
            var h = v.getBrain().getMemory(MemoryModuleType.HOME).orElse(null);
            if (h != null && h.dimension().equals(level.dimension())) {
                owners.computeIfAbsent(h.pos(), k -> new ArrayList<>()).add(STATS.seqOf(v.getUUID()));
            }
        }

        Map<Integer, PairTracker.Courting> couplesNow = PairTracker.tick(level, villagers, now, PROBE, STATS);

        List<Views.VillagerView> views = new ArrayList<>();
        Set<Integer> seqs = new HashSet<>();
        for (Villager v : villagers) {
            Eval.Ctx ctx = new Eval.Ctx(STATS, PROBE, owners, now, dayTime,
                    Eval.currentScheduleActivity(level, v));
            Eval.Outcome outcome = Eval.evaluate(level, v, ctx);
            views.add(withCourting(outcome.view(), couplesNow.get(v.getId())));
            seqs.add(outcome.view().seq());
        }

        Views.Stats stats = STATS.stats(seqs, now);
        sendSnapshot(sp, now, level, villagers.size(), views, stats, prefs.allMode());
    }

    /** 每包最多 80 个村民，避免单包过大。 */
    private static void sendSnapshot(ServerPlayer sp, long gameTime, ServerLevel level, int scanned,
                                     List<Views.VillagerView> views, Views.Stats stats, boolean allMode) {
        int chunk = 80;
        int parts = Math.max(1, (views.size() + chunk - 1) / chunk);
        String dim = level.dimension().identifier().toString();
        long dayTime = level.getDefaultClockTime();
        for (int i = 0; i < parts; i++) {
            int from = i * chunk;
            int to = Math.min(views.size(), from + chunk);
            List<Views.VillagerView> sub = parts == 1 ? views : new ArrayList<>(views.subList(from, to));
            Views.Snapshot snap = new Views.Snapshot(gameTime, dayTime, dim, scanned, sub,
                    i == 0 ? stats : null, i, parts, allMode);
            send(sp, new Payloads.Sync(Json.COMPACT.toJson(snap)));
        }
    }

    private static void sendEmpty(ServerPlayer sp) {
        ServerLevel level = (ServerLevel) sp.level();
        long now = level.getDefaultClockTime();
        Views.Stats stats = STATS.stats(Set.of(), now);
        sendSnapshot(sp, now, level, 0, List.of(), stats, false);
    }

    private static void sendDetail(ServerPlayer sp, int eid) {
        ServerLevel level = (ServerLevel) sp.level();
        STATS.load(sp.level().getServer());
        Entity e = level.getEntity(eid);
        if (!(e instanceof Villager v)) {
            return;
        }
        PROBE.newScan(12);
        Map<BlockPos, List<Integer>> owners = new HashMap<>();
        long now = level.getDefaultClockTime();
        int dayTime = (int) Math.floorMod(now, 24000L);
        Eval.Ctx ctx = new Eval.Ctx(STATS, PROBE, owners, now, dayTime, Eval.currentScheduleActivity(level, v));
        Eval.Outcome outcome = Eval.evaluate(level, v, ctx);
        Views.Stats stats = STATS.stats(Set.of(outcome.view().seq()), now);
        sendSnapshot(sp, now, level, 1, List.of(outcome.view()), stats, false);
    }

    private static void sendStats(ServerPlayer sp) {
        ServerLevel level = (ServerLevel) sp.level();
        STATS.load(sp.level().getServer());
        long now = level.getDefaultClockTime();
        Views.Stats stats = STATS.stats(Set.of(), now);
        sendSnapshot(sp, now, level, 0, List.of(), stats, false);
    }

    private static void send(ServerPlayer sp, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(sp, payload.type())) {
            ServerPlayNetworking.send(sp, payload);
        }
    }

    private static Views.VillagerView withCourting(Views.VillagerView v, PairTracker.Courting courting) {
        if (courting == null) {
            return v;
        }
        Views.Pairing p = v.pairing();
        Views.Pairing patched = new Views.Pairing(p.hasTarget(), p.targetSeq(), p.targetName(), p.targetDist(),
                p.targetReady(), p.targetMissing(), true, courting.startTick(),
                courting.expectedBirthTick(), p.lastPartner(), p.lastPartnerOutcome(),
                p.lastPartnerTick());
        return new Views.VillagerView(v.seq(), v.eid(), v.uuid(), v.name(), v.profession(), v.profLevel(),
                v.offers(), v.baby(), v.age(), v.shortTag(), v.shortTagEn(), v.ready(), v.food(), v.bed(),
                v.brain(), patched, v.conditions(), v.x(), v.y(), v.z());
    }


    // ------------------------------------------------------------------ 出生 / 死亡

    /** 婴儿被加入世界时调用（区块加载回来的成年村民不会走到这里）。 */
    public static void onEntityJoinServer(Entity entity, ServerLevel level) {
        if (!(entity instanceof Villager baby) || !baby.isBaby()) {
            return;
        }
        if (baby.getAge() > -23990) {
            return;   // 只统计刚出生的（区块加载回来的婴儿 age 已经往下走了）
        }
        UUID id = baby.getUUID();
        if (!BIRTH_SEEN.add(id)) {
            return;
        }
        STATS.load(level.getServer());
        long now = level.getDefaultClockTime();
        List<Villager> near = level.getEntitiesOfClass(Villager.class, baby.getBoundingBox().inflate(4),
                x -> x != baby && !x.isBaby() && x.getAge() > 5000);
        near.sort(Comparator.comparingDouble(x -> x.distanceToSqr(baby)));
        List<String> parents = new ArrayList<>();
        List<Integer> parentSeqs = new ArrayList<>();
        for (Villager p : near) {
            if (parents.size() >= 2) {
                break;
            }
            int ps = STATS.seqOf(p.getUUID());
            parentSeqs.add(ps);
            parents.add("#" + ps + " " + p.getName().getString());
        }
        if (parents.isEmpty()) {
            return;   // 僵尸村民治愈 / 袭击 / 村庄初始生成等非繁殖来源
        }
        boolean confirmed = parents.size() == 2;
        BlockPos pos = baby.blockPosition();
        STATS.addBirth(new Views.Birth(now, Json.dayClock(now), STATS.seqOf(id),
                baby.getName().getString(), parents, level.dimension().identifier().toString(),
                pos.getX(), pos.getY(), pos.getZ(), confirmed), parentSeqs);
        if (confirmed) {
            String token = OUT_BIRTH + STATS.seqOf(id);
            STATS.notePairing(parentSeqs.get(0), parentSeqs.get(1), token, now);
            STATS.notePairing(parentSeqs.get(1), parentSeqs.get(0), token, now);
        }
    }

    public static void onVillagerDeath(Villager v) {
        STATS.addDeath(STATS.seqOf(v.getUUID()));
    }

    public static Prefs prefsOf(UUID uuid) {
        return PREFS.getOrDefault(uuid, Prefs.DEFAULT);
    }

    public static void saveNow() {
        STATS.saveNow();
    }

    /** 有界 LinkedHashSet（防止出生记录无限增长）。 */
    private static final class LinkedHashSetBounded<E> extends java.util.LinkedHashSet<E> {
        private final int cap;

        LinkedHashSetBounded(int cap) {
            this.cap = cap;
        }

        @Override
        public boolean add(E e) {
            boolean added = super.add(e);
            if (size() > cap) {
                var it = iterator();
                it.next();
                it.remove();
            }
            return added;
        }
    }
}
