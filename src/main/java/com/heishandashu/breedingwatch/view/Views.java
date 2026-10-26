package com.heishandashu.breedingwatch.view;

import java.util.List;
import java.util.Map;

/**
 * 服务端 -> 客户端 的快照视图（Gson 直接序列化，字段名即 JSON 字段名）。
 *
 * <p>全部是不可变 record。约定：这些类型一律 public，禁止 private —— 历史上 Gson
 * 反射私有 record 会抛 IllegalAccessException，表现是"存盘静默失败"。</p>
 */
public final class Views {

    private Views() {}

    /** 一次完整快照（超过 80 名村民时按 part/parts 分片）。 */
    public record Snapshot(
            long gameTime,
            long dayTime,
            String levelId,
            int scanned,
            List<VillagerView> villagers,
            Stats stats,
            int part,
            int parts,
            boolean allMode
    ) {}

    /** 单个村民的全部繁殖相关信息。 */
    public record VillagerView(
            int seq,
            int eid,
            String uuid,
            String name,
            String profession,
            int profLevel,
            int offers,
            boolean baby,
            int age,
            String shortTag,
            String shortTagEn,
            boolean ready,
            Food food,
            Bed bed,
            BrainInfo brain,
            Pairing pairing,
            List<Condition> conditions,
            double x,
            double y,
            double z
    ) {}

    public record Food(
            int foodLevel,
            int invPoints,
            int total,
            int need,
            boolean excess,
            boolean wantsMore,
            Map<String, Integer> items,
            Map<String, Integer> foodPoints
    ) {}

    public record Bed(
            boolean hasHome,
            String homePos,
            String homeDim,
            int homeDist,
            boolean homeExists,
            int homeFreeTickets,
            boolean homeOwned,
            Boolean reachable,
            String reachableHow,
            boolean dropRisk,
            String riskReason,
            int bedsTotal,
            int bedsFree,
            int bedsFreeReachable,
            String nearestFreeBed,
            List<Integer> conflictWith
    ) {}

    public record BrainInfo(
            String activity,
            List<String> allActivities,
            String scheduleActivity,
            String effectiveActivity,
            int ticksToNextActivity,
            int nextIdleInTicks,
            int dayTime,
            String timeOfDay,
            boolean sleeping,
            boolean walkBusy,
            boolean panicking,
            String panicReason,
            boolean raidNearby,
            boolean hasJobSite,
            boolean hasMeetingPoint,
            String meetingPoint,
            boolean cantReachTarget
    ) {}

    public record Pairing(
            boolean hasTarget,
            Integer targetSeq,
            String targetName,
            Double targetDist,
            Boolean targetReady,
            List<String> targetMissing,
            boolean courting,
            Long courtingStartTick,
            Long expectedBirthTick,
            String lastPartner,
            String lastPartnerOutcome,
            Long lastPartnerTick
    ) {}

    public record Condition(String code, String label, String detail, boolean ok, boolean soft) {}

    /** 统计面板数据。 */
    public record Stats(
            long totalBirths,
            long totalFailures,
            long totalDeaths,
            long trackedVillagers,
            Map<String, Integer> failByCode,
            List<Birth> births,
            List<Failure> failures,
            List<VStat> perVillager,
            String dayString
    ) {}

    public record Birth(
            long tick,
            String time,
            int babySeq,
            String babyName,
            List<String> parents,
            String levelId,
            int x,
            int y,
            int z,
            boolean parentsConfirmed
    ) {}

    public record Failure(
            long tick,
            String time,
            int seq,
            String name,
            String code,
            String label,
            String detail
    ) {}

    public record VStat(
            int seq,
            String name,
            String profession,
            long births,
            long failures,
            boolean presentNow
    ) {}
}
