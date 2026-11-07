package net.runelite.client.plugins.microbot.herbihunter;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
public class HerbiboarSession {

    long startMs;
    long endMs;
    int caught;
    long xpGained;
    int startLevel;
    int endLevel;
    int bankTrips;
    int restocks;
    int confusions;
    String stopReason;
    /** Null in older saved sessions. */
    String playStyle;
    // the overlay reads this while the script adds to it
    List<Long> catchTimes = new CopyOnWriteArrayList<>();

    HerbiboarSession(long startMs) {
        this.startMs = startMs;
    }

    void recordCatch(long atMs) {
        caught++;
        catchTimes.add(atMs);
    }

    long durationMs(long nowMs) {
        return Math.max(0, (endMs > 0 ? endMs : nowMs) - startMs);
    }

    double catchesPerHour(long nowMs) {
        long ms = durationMs(nowMs);
        return ms < 60_000 ? -1 : caught * 3_600_000.0 / ms;
    }

    /** Windows before the session began are -1: drawn as nothing, not as a zero. Pure. */
    static int[] buckets(List<Long> times, long startMs, long nowMs, int n, long bucketMs) {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            long to = nowMs - (long) (n - 1 - i) * bucketMs;
            long from = to - bucketMs;
            if (to <= startMs) {
                out[i] = -1;
                continue;
            }
            int c = 0;
            for (long t : times) if (t > from && t <= to) c++;
            out[i] = c;
        }
        return out;
    }

    static File dir() {
        return new File(new File(new File(System.getProperty("user.home"), ".runelite"), "microbot"), "herbiboar");
    }

    static File historyFile(File dir) {
        return new File(dir, "sessions.jsonl");
    }

    static File checkpointFile(File dir) {
        return new File(dir, "current-session.json");
    }

    static List<HerbiboarSession> loadHistory(File dir, Gson gson) {
        File f = historyFile(dir);
        if (!f.isFile()) return Collections.emptyList();
        List<HerbiboarSession> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (line.trim().isEmpty()) continue;
                try {
                    HerbiboarSession s = gson.fromJson(line, HerbiboarSession.class);
                    if (s != null) out.add(s);
                } catch (RuntimeException bad) {
                    log.debug("Skipping a damaged Herbiboar session line: {}", bad.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Could not read Herbiboar session history {}: {}", f, e.getMessage());
        }
        return out;
    }

    static void saveFinished(File dir, Gson gson, HerbiboarSession s) {
        try {
            Files.createDirectories(dir.toPath());
            Files.write(historyFile(dir).toPath(), (gson.toJson(s) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.deleteIfExists(checkpointFile(dir).toPath());
        } catch (IOException e) {
            log.warn("Could not save the Herbiboar session to {}: {}", dir, e.getMessage());
        }
    }

    /** Atomic: a crash mid-write keeps the previous checkpoint. */
    static void checkpoint(File dir, Gson gson, HerbiboarSession s) {
        try {
            Files.createDirectories(dir.toPath());
            File tmp = new File(dir, "current-session.json.tmp");
            Files.write(tmp.toPath(), gson.toJson(s).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), checkpointFile(dir).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.debug("Herbiboar checkpoint failed: {}", e.getMessage());
        }
    }

    static void recoverCheckpoint(File dir, Gson gson) {
        File f = checkpointFile(dir);
        if (!f.isFile()) return;
        try {
            HerbiboarSession s = gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                    HerbiboarSession.class);
            if (s != null && s.caught > 0) {
                if (s.endMs <= 0) s.endMs = f.lastModified();
                s.stopReason = "client closed or crashed";
                saveFinished(dir, gson, s);
                return;
            }
            Files.deleteIfExists(f.toPath());
        } catch (IOException | RuntimeException e) {
            log.debug("Herbiboar checkpoint unreadable, dropped: {}", e.getMessage());
        }
    }
}
