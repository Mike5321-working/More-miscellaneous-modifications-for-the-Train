package com.habitrain.lottery.client.record;

import com.habitrain.core.api.client.replay.MatchReplayClientApi;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Binary-compatible client bridge from {@code habitrain_lottery} to {@link MatchReplayClientApi}.
 *
 * <p>Compiles cleanly against {@code habitrain_core-2.0.37} (which only defines
 * {@code MatchReplayClientApi.open(Screen, String, boolean)}) and dynamically binds the extended
 * nearby-replay-table live-sync methods via reflection on {@code MatchReplayClientApi} when a
 * newer {@code habitrain_core} is installed at runtime. When running against an older core JAR,
 * nearby-table probing degrades gracefully to {@code null} without breaking existing buttons.</p>
 */
public final class MatchReplayTableBridge {
    private static volatile boolean resolved;
    private static @Nullable Method nearbyTableSnapshotMethod;
    private static @Nullable Method controlNearbyTableMethod;
    private static @Nullable Method openNearbyTableExportMethod;
    private static @Nullable Method openNearbyTablePreviewMethod;

    public record NearbyTableState(
            String roundId,
            String mapName,
            double currentTick,
            int durationTicks,
            float speed,
            boolean paused,
            boolean preparing,
            boolean finishedHold,
            String followPlayerUuid,
            String followPlayerName,
            boolean killCloseupEnabled,
            boolean roofCutaway,
            double distanceBlocks
    ) {
        public double progress() {
            return durationTicks <= 0 ? 0.0 : Math.clamp(currentTick / (double) durationTicks, 0.0, 1.0);
        }

        public boolean hasFollowPlayer() {
            return followPlayerUuid != null && !followPlayerUuid.isEmpty();
        }
    }

    private MatchReplayTableBridge() {}

    private static void ensureResolved() {
        if (resolved) return;
        synchronized (MatchReplayTableBridge.class) {
            if (resolved) return;
            try {
                Class<?> api = MatchReplayClientApi.class;
                nearbyTableSnapshotMethod = findPublicStatic(api, "nearbyTableSnapshot");
                controlNearbyTableMethod = findPublicStatic(api, "controlNearbyTable",
                        String.class, double.class, String.class, boolean.class);
                openNearbyTableExportMethod = findPublicStatic(api, "openNearbyTableExport", Screen.class);
                openNearbyTablePreviewMethod = findPublicStatic(api, "openNearbyTablePreview", Screen.class);
            } catch (Throwable ignored) {
                nearbyTableSnapshotMethod = null;
                controlNearbyTableMethod = null;
                openNearbyTableExportMethod = null;
                openNearbyTablePreviewMethod = null;
            }
            resolved = true;
        }
    }

    private static @Nullable Method findPublicStatic(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }

    public static Screen openReplay(Screen parent, String matchId, boolean export) {
        try {
            return MatchReplayClientApi.open(parent, matchId, export);
        } catch (Throwable ignored) {
            return parent;
        }
    }

    public static boolean supportsNearbyTableSync() {
        ensureResolved();
        return nearbyTableSnapshotMethod != null && controlNearbyTableMethod != null;
    }

    @SuppressWarnings("unchecked")
    public static @Nullable NearbyTableState pollNearbyTable() {
        ensureResolved();
        Method method = nearbyTableSnapshotMethod;
        if (method == null) return null;
        try {
            Object raw = method.invoke(null);
            if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) return null;
            String roundId = stringOf(map.get("roundId"));
            if (roundId.isEmpty()) return null;
            return new NearbyTableState(
                    roundId,
                    stringOf(map.get("mapName")),
                    doubleOf(map.get("currentTick"), 0.0),
                    intOf(map.get("durationTicks"), 0),
                    floatOf(map.get("speed"), 1.0f),
                    boolOf(map.get("paused"), false),
                    boolOf(map.get("preparing"), false),
                    boolOf(map.get("finishedHold"), false),
                    stringOf(map.get("followPlayerUuid")),
                    stringOf(map.get("followPlayerName")),
                    boolOf(map.get("killCloseupEnabled"), false),
                    boolOf(map.get("roofCutaway"), true),
                    doubleOf(map.get("distanceBlocks"), 0.0)
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean control(String action, double numericArg, @Nullable String uuidArg, boolean flagArg) {
        ensureResolved();
        Method method = controlNearbyTableMethod;
        if (method == null || action == null || action.isEmpty()) return false;
        try {
            Object result = method.invoke(null, action, numericArg, uuidArg, flagArg);
            return result instanceof Boolean ok && ok;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static @Nullable Screen openNearbyTableExport(Screen parent) {
        ensureResolved();
        Method method = openNearbyTableExportMethod;
        if (method == null) return null;
        try {
            Object result = method.invoke(null, parent);
            return result instanceof Screen screen ? screen : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static @Nullable Screen openNearbyTablePreview(Screen parent) {
        ensureResolved();
        Method method = openNearbyTablePreviewMethod;
        if (method == null) return null;
        try {
            Object result = method.invoke(null, parent);
            return result instanceof Screen screen ? screen : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String stringOf(@Nullable Object value) {
        return value == null ? "" : value.toString();
    }

    private static double doubleOf(@Nullable Object value, double fallback) {
        return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : fallback;
    }

    private static float floatOf(@Nullable Object value, float fallback) {
        return value instanceof Number n && Float.isFinite(n.floatValue()) ? n.floatValue() : fallback;
    }

    private static int intOf(@Nullable Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static boolean boolOf(@Nullable Object value, boolean fallback) {
        return value instanceof Boolean b ? b : fallback;
    }
}
