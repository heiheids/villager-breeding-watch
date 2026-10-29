package com.heishandashu.breedingwatch.client;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.view.Views;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端缓存的服务端快照。
 *
 * <p>关键约定（1.21.1 版本踩过的大坑）：服务端会混发"全量扫描快照"和"单个村民详情包"。
 * 如果每收到一包就整表替换，点一个村民看详情就会把全图数据冲掉，现场表现是
 * "只有我点的那个村民显示"。所以这里一律 **合并**，并按时间戳清理过期条目；
 * 只有服务端明确发空快照（所有开关都关闭）时才立刻清空。</p>
 */
public final class ClientData {

    private static final long STALE_MS = 10_000L;

    private volatile Views.Snapshot snapshot;
    private volatile Map<Integer, Views.VillagerView> byEid = Map.of();
    public volatile int detailEid = -1;

    /**
     * 收到最近一包快照的墙钟时刻（ms）。
     *
     * <p>判断"数据是否停止更新"必须用它，不能用世界时间：下界/末地是固定时间维度，
     * 维度时钟恒为 0，拿它做差会让 HUD 永远显示"数据已停止更新"。</p>
     */
    private volatile long lastSnapshotMillis;

    /** 分包合并缓存：key = levelId + "@" + gameTime。 */
    private final Map<String, Map<Integer, Views.Snapshot>> parts = new HashMap<>();
    private final Map<String, Long> partStamp = new HashMap<>();
    private final Map<Integer, Long> seen = new HashMap<>();
    private int commits;

    public long lastSnapshotMillis() {
        return lastSnapshotMillis;
    }

    public void accept(String json) {
        try {
            // 总闸关闭：丢弃一切来的数据，保持缓存为空。
            // 服务端本该不再推送，但换开关的那一瞬可能还有在途包，这里兜住。
            if (!ClientSettings.isMasterOn()) {
                clear();
                return;
            }
            Views.Snapshot s = Json.COMPACT.fromJson(json, Views.Snapshot.class);
            if (s == null) {
                return;
            }
            lastSnapshotMillis = System.currentTimeMillis();
            commit(s.parts() > 1 ? merge(s) : s);
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 解析快照失败：{}", e.toString());
        }
    }

    /** 把同一个 gameTime 的分包拼成完整快照；缺包时先返回当前已有的部分（不阻塞显示）。 */
    private Views.Snapshot merge(Views.Snapshot piece) {
        String key = piece.levelId() + "@" + piece.gameTime();
        long now = System.currentTimeMillis();
        if (parts.size() > 16) {
            parts.keySet().removeIf(k -> partStamp.getOrDefault(k, 0L) < now - 15_000L);
        }
        Map<Integer, Views.Snapshot> bucket = parts.computeIfAbsent(key, k -> new HashMap<>());
        partStamp.put(key, now);
        bucket.put(piece.part(), piece);

        List<Views.VillagerView> all = new ArrayList<>();
        Views.Stats stats = null;
        int got = 0;
        for (int i = 0; i < piece.parts(); i++) {
            Views.Snapshot p = bucket.get(i);
            if (p == null) {
                continue;
            }
            got++;
            if (p.villagers() != null) {
                all.addAll(p.villagers());
            }
            if (stats == null) {
                stats = p.stats();
            }
        }
        if (got == 0) {
            return piece;
        }
        return new Views.Snapshot(piece.gameTime(), piece.dayTime(), piece.levelId(), piece.scanned(), all, stats,
                0, 1, piece.allMode());
    }

    private void commit(Views.Snapshot s) {
        // 服务器主动清空（所有开关都关了）：直接清
        if (s.villagers() != null && s.villagers().isEmpty() && s.scanned() == 0) {
            this.byEid = Map.of();
            this.seen.clear();
            this.snapshot = s;
            return;
        }
        long now = System.currentTimeMillis();
        Map<Integer, Views.VillagerView> merged = new HashMap<>(this.byEid);
        if (s.villagers() != null) {
            for (Views.VillagerView v : s.villagers()) {
                merged.put(v.eid(), v);
                seen.put(v.eid(), now);
            }
        }
        if (merged.size() > 1) {
            seen.entrySet().removeIf(e -> now - e.getValue() > STALE_MS);
        }
        merged.keySet().retainAll(this.seen.keySet());
        this.byEid = merged;
        this.snapshot = s;
        if ((++commits % 5) == 1) {
            VbwMod.LOG.info("[VBW] 快照 #{}：本包 {} 名 / 现有 {} 名，常开模式={}，维度={}", commits,
                    s.villagers() == null ? 0 : s.villagers().size(), merged.size(), s.allMode(), s.levelId());
        }
    }

    public Views.Snapshot snapshot() {
        return snapshot;
    }

    public Views.VillagerView view(int eid) {
        return byEid.get(eid);
    }

    public Collection<Views.VillagerView> all() {
        return byEid.values();
    }

    public void clear() {
        byEid = Map.of();
        seen.clear();
        snapshot = null;
        lastSnapshotMillis = 0L;
    }
}
