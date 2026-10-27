package com.heishandashu.breedingwatch.server;

import com.heishandashu.breedingwatch.VbwMod;
import com.heishandashu.breedingwatch.json.Json;
import com.heishandashu.breedingwatch.view.Views;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 出生 / 失败 / 单个村民统计，以及稳定唯一编号（UUID -> #序号）。
 *
 * <p>存档根目录下 {@code villager_breeding_watch.json}，与 1.21.1 版本保持同名同结构，
 * 老存档里的编号和统计可以直接接着用。</p>
 */
public final class StatsStore {

    private static final int HISTORY_CAP = 500;
    private static final int SYNC_TAIL = 60;
    private static final long AUTOSAVE_TICKS = 6000L;

    private final Map<String, Integer> seqByUuid = new LinkedHashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<Integer, String> professions = new HashMap<>();
    private final Map<Integer, long[]> counts = new HashMap<>();
    private final Map<Integer, Integer> lastPartnerSeq = new HashMap<>();
    private final Map<Integer, String> lastPartnerOutcome = new HashMap<>();
    private final Map<Integer, Long> lastPartnerTick = new HashMap<>();
    private final Map<String, Integer> failByCode = new LinkedHashMap<>();
    private final List<Views.Birth> births = new ArrayList<>();
    private final List<Views.Failure> failures = new ArrayList<>();
    private long totalBirths;
    private long totalFailures;
    private long totalDeaths;
    private int nextSeq = 1;

    private Path file;
    private boolean dirty;
    /** 用布尔量表达"还没保存过"，不要让时间戳用 Long.MIN_VALUE 当哨兵（会算术溢出）。 */
    private boolean savedOnce;
    private long lastSave;

    public synchronized int seqOf(UUID id) {
        String key = id.toString();
        Integer existing = seqByUuid.get(key);
        if (existing != null) {
            return existing;
        }
        int seq = nextSeq++;
        seqByUuid.put(key, seq);
        dirty = true;
        return seq;
    }

    public synchronized void note(int seq, String name, String profession) {
        if (name != null && !name.isBlank()) {
            names.put(seq, name);
        }
        if (profession != null) {
            professions.put(seq, profession);
        }
        counts.computeIfAbsent(seq, k -> new long[2]);
    }

    public synchronized long birthsOf(int seq) {
        long[] c = counts.get(seq);
        return c == null ? 0L : c[0];
    }

    public synchronized void addBirth(Views.Birth birth, Collection<Integer> parents) {
        totalBirths++;
        push(births, birth);
        for (Integer seq : parents) {
            counts.computeIfAbsent(seq, k -> new long[2])[0]++;
        }
        dirty = true;
    }

    public synchronized void addFailure(Views.Failure failure) {
        totalFailures++;
        push(failures, failure);
        failByCode.merge(failure.code(), 1, Integer::sum);
        counts.computeIfAbsent(failure.seq(), k -> new long[2])[1]++;
        dirty = true;
    }

    public synchronized void addDeath(int seq) {
        totalDeaths++;
        note(seq, names.get(seq), professions.get(seq));
        dirty = true;
    }

    private static <T> void push(List<T> list, T item) {
        list.add(0, item);              // 最新的在最前
        while (list.size() > HISTORY_CAP) {
            list.remove(list.size() - 1);
        }
    }

    public synchronized Views.Stats stats(Set<Integer> presentSeqs, long dayTime) {
        List<Views.VStat> perVillager = new ArrayList<>();
        for (Map.Entry<Integer, long[]> e : counts.entrySet()) {
            int seq = e.getKey();
            long birthsN = e.getValue()[0];
            long failuresN = e.getValue()[1];
            boolean present = presentSeqs != null && presentSeqs.contains(seq);
            if (birthsN == 0 && failuresN == 0 && !present) {
                continue;
            }
            perVillager.add(new Views.VStat(seq,
                    names.getOrDefault(seq, "#" + seq),
                    professions.getOrDefault(seq, "none"),
                    birthsN, failuresN, present));
        }
        perVillager.sort((a, b) -> {
            int c = Long.compare(b.births(), a.births());
            return c != 0 ? c : Integer.compare(a.seq(), b.seq());
        });
        return new Views.Stats(totalBirths, totalFailures, totalDeaths, seqByUuid.size(),
                new LinkedHashMap<>(failByCode),
                new ArrayList<>(births.subList(0, Math.min(SYNC_TAIL, births.size()))),
                new ArrayList<>(failures.subList(0, Math.min(SYNC_TAIL, failures.size()))),
                perVillager,
                Json.dayClock(dayTime));
    }

    /** 进入世界时调用一次；文件不存在不是错误（退出时才创建）。 */
    public synchronized void load(MinecraftServer server) {
        if (file != null) {
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        file = root.resolve("villager_breeding_watch.json");
        if (!Files.exists(file)) {
            VbwMod.LOG.info("[VBW] 统计文件不存在，将在退出时创建：{}", file);
            return;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Saved saved = Json.COMPACT.fromJson(text, Saved.class);
            if (saved == null) {
                return;
            }
            if (saved.seqByUuid() != null) {
                seqByUuid.putAll(saved.seqByUuid());
            }
            if (saved.names() != null) {
                names.putAll(saved.names());
            }
            if (saved.professions() != null) {
                professions.putAll(saved.professions());
            }
            if (saved.counts() != null) {
                for (Map.Entry<Integer, long[]> e : saved.counts().entrySet()) {
                    long[] v = e.getValue();
                    counts.put(e.getKey(), new long[] {
                            v == null || v.length < 2 ? 0 : v[0],
                            v == null || v.length < 2 ? 0 : v[1]});
                }
            }
            if (saved.failByCode() != null) {
                failByCode.putAll(saved.failByCode());
            }
            if (saved.births() != null) {
                births.addAll(saved.births());
            }
            if (saved.failures() != null) {
                failures.addAll(saved.failures());
            }
            totalBirths = saved.totalBirths();
            totalFailures = saved.totalFailures();
            totalDeaths = saved.totalDeaths();
            if (saved.lastPartnerSeq() != null) {
                lastPartnerSeq.putAll(saved.lastPartnerSeq());
            }
            if (saved.lastPartnerOutcome() != null) {
                lastPartnerOutcome.putAll(saved.lastPartnerOutcome());
            }
            if (saved.lastPartnerTick() != null) {
                lastPartnerTick.putAll(saved.lastPartnerTick());
            }
            nextSeq = Math.max(saved.nextSeq(), nextSeq);
            VbwMod.LOG.info("[VBW] 已载入统计：出生 {} 次 / 失败 {} 次 / 记录村民 {} 个",
                    totalBirths, totalFailures, seqByUuid.size());
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 读取统计文件失败：{}", e.toString());
        }
    }

    public synchronized void saveIfDirty(long gameTime) {
        // 同样的溢出陷阱：lastSave 初值若是 Long.MIN_VALUE，gameTime - lastSave 会溢出成负数，
        // 于是 gameTime - lastSave < AUTOSAVE_TICKS 恒成立，自动保存永远不触发。
        // 用"savedOnce"表达"还没保存过"，值不参与算术。
        if (file == null || !dirty) {
            return;
        }
        if (savedOnce && gameTime - lastSave < AUTOSAVE_TICKS) {
            return;
        }
        saveNow();
        lastSave = gameTime;
        savedOnce = true;
    }

    public synchronized void saveNow() {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Saved saved = new Saved(nextSeq, new LinkedHashMap<>(seqByUuid), new HashMap<>(names),
                    new HashMap<>(professions), new HashMap<>(counts), totalBirths, totalFailures, totalDeaths,
                    new LinkedHashMap<>(failByCode), new ArrayList<>(births), new ArrayList<>(failures),
                    new HashMap<>(lastPartnerSeq), new HashMap<>(lastPartnerOutcome),
                    new HashMap<>(lastPartnerTick));
            Files.writeString(file, Json.GSON.toJson(saved), StandardCharsets.UTF_8);
            dirty = false;
        } catch (Exception e) {
            VbwMod.LOG.warn("[VBW] 写入统计文件失败：{}", e.toString());
        }
    }

    public synchronized Set<Integer> allKnownSeqs() {
        return new HashSet<>(counts.keySet());
    }

    public synchronized String nameOf(int seq) {
        return names.getOrDefault(seq, "#" + seq);
    }

    public synchronized void notePairing(int seq, int partnerSeq, String outcome, long tick) {
        lastPartnerSeq.put(seq, partnerSeq);
        lastPartnerOutcome.put(seq, outcome);
        lastPartnerTick.put(seq, tick);
        dirty = true;
    }

    public synchronized String lastPartnerName(int seq) {
        Integer p = lastPartnerSeq.get(seq);
        return p == null ? null : names.getOrDefault(p, "#" + p);
    }

    public synchronized Integer lastPartnerSeq(int seq) {
        return lastPartnerSeq.get(seq);
    }

    public synchronized String lastPartnerOutcome(int seq) {
        return lastPartnerOutcome.get(seq);
    }

    public synchronized Long lastPartnerTick(int seq) {
        return lastPartnerTick.get(seq);
    }

    public synchronized String describeCounts() {
        return String.format(Locale.ROOT, "births=%d failures=%d deaths=%d villagers=%d",
                totalBirths, totalFailures, totalDeaths, seqByUuid.size());
    }

    /**
     * 存盘结构。
     *
     * <p>故意保持成 public record 且字段与 1.21.1 版本一致：老存档可以直接读，
     * 新版本新增字段缺失时会取默认值（Gson 对缺失字段保留 null/0）。</p>
     */
    public record Saved(int nextSeq,
                        Map<String, Integer> seqByUuid,
                        Map<Integer, String> names,
                        Map<Integer, String> professions,
                        Map<Integer, long[]> counts,
                        long totalBirths,
                        long totalFailures,
                        long totalDeaths,
                        Map<String, Integer> failByCode,
                        List<Views.Birth> births,
                        List<Views.Failure> failures,
                        Map<Integer, Integer> lastPartnerSeq,
                        Map<Integer, String> lastPartnerOutcome,
                        Map<Integer, Long> lastPartnerTick) {}
}
