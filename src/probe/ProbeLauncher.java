package probe;

import io.github.rwpp.event.EventPriority;
import io.github.rwpp.event.GlobalEventChannel;
import io.github.rwpp.event.events.ChatMessageEvent;
import io.github.rwpp.event.events.GameLoadedEvent;
import io.github.rwpp.event.events.GameOverEvent;
import io.github.rwpp.event.events.KeyboardEvent;
import io.github.rwpp.event.events.LocalPlayerBuildingConstructedEvent;
import io.github.rwpp.event.events.LocalPlayerUnitCreatedEvent;
import io.github.rwpp.event.events.PlayerDefeatedEvent;
import io.github.rwpp.external.ExtensionLauncher;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RWPP 只读探针 + 操作记录器（1.6.2）。
 * 只读：不改游戏状态、不下指令。记录的是「看到什么」以及「游戏什么时候发了什么事件」。
 */
public final class ProbeLauncher extends ExtensionLauncher {

    private static final int DEFAULT_PORT = 17999;
    private static final String BUILD_TAG = "2026-10-28-panel";
    private static final long SAMPLE_MS = 200;
    private static final long HEARTBEAT_MS = 5000;

    private static final Object WRITE_LOCK = new Object();
    private static BufferedWriter writer;
    private static File sessionFile;
    private static volatile int eventCount = 0;

    private final long startedAt = System.currentTimeMillis();
    private volatile int port = DEFAULT_PORT;

    @Override
    public void init() {
        String id = "unknown";
        String version = "?";
        try {
            if (getExtension() != null && getExtension().getConfig() != null) {
                id = getExtension().getConfig().getId();
                version = getExtension().getConfig().getVersion();
            }
        } catch (Throwable ignored) {
        }
        log("probe init: id=" + id + " version=" + version);
        log("probe user.dir=" + System.getProperty("user.dir"));

        startSessionFile();
        subscribeEvents();
        startSampler();
        startHttp();
    }

    // ---------- 记录文件 ----------

    private static void startSessionFile() {
        try {
            File out = new File(System.getProperty("user.dir"), "../../rwpp-probe/records");
            out = out.getCanonicalFile();
            if (!out.isDirectory() && !out.mkdirs()) {
                out = new File(System.getProperty("user.dir"), "probe-records");
                out.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            sessionFile = new File(out, "rec-" + stamp + ".jsonl");
            writer = new BufferedWriter(new FileWriter(sessionFile, true));
            log("probe recording to " + sessionFile.getAbsolutePath());
        } catch (Throwable t) {
            log("probe cannot open record file: " + t);
        }
    }

    private static void append(String json) {
        if (writer == null) return;
        synchronized (WRITE_LOCK) {
            try {
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void recordEvent(String name, Map<String, Object> extra) {
        eventCount++;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", System.currentTimeMillis());
        m.put("k", "event");
        m.put("name", name);
        if (extra != null) m.putAll(extra);
        append(json(m));
    }

    // ---------- 事件订阅（只记时间与键码，不拦截） ----------

    private void subscribeEvents() {
        try {
            GlobalEventChannel ch = GlobalEventChannel.Companion;
            kotlin.coroutines.CoroutineContext ctx = kotlin.coroutines.EmptyCoroutineContext.INSTANCE;

            ch.subscribeGlobalAlways(KeyboardEvent.class, ctx, EventPriority.MONITOR,
                    new kotlin.jvm.functions.Function2<KeyboardEvent, Object, Object>() {
                        @Override
                        public Object invoke(KeyboardEvent e, Object cont) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("keyCode", e.getKeyCode());
                            m.put("key", keyName(e.getKeyCode()));
                            recordEvent("key", m);
                            return kotlin.Unit.INSTANCE;
                        }
                    });

            ch.subscribeGlobalAlways(ChatMessageEvent.class, ctx, EventPriority.MONITOR,
                    new kotlin.jvm.functions.Function2<ChatMessageEvent, Object, Object>() {
                        @Override
                        public Object invoke(ChatMessageEvent e, Object cont) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("sender", e.getSender());
                            m.put("message", e.getMessage());
                            recordEvent("chat", m);
                            return kotlin.Unit.INSTANCE;
                        }
                    });

            subscribeSimple(ch, ctx, LocalPlayerUnitCreatedEvent.class, "unitCreated");
            subscribeSimple(ch, ctx, LocalPlayerBuildingConstructedEvent.class, "buildingBuilt");
            subscribeSimple(ch, ctx, GameLoadedEvent.class, "gameLoaded");
            subscribeSimple(ch, ctx, GameOverEvent.class, "gameOver");
            ch.subscribeGlobalAlways(PlayerDefeatedEvent.class, ctx, EventPriority.MONITOR,
                    new kotlin.jvm.functions.Function2<PlayerDefeatedEvent, Object, Object>() {
                        @Override
                        public Object invoke(PlayerDefeatedEvent e, Object cont) {
                            recordEvent("playerDefeated", null);
                            return kotlin.Unit.INSTANCE;
                        }
                    });

            log("probe events subscribed");
        } catch (Throwable t) {
            log("probe event subscribe failed: " + t);
        }
    }

    private static <T extends io.github.rwpp.event.Event> void subscribeSimple(
            GlobalEventChannel ch, kotlin.coroutines.CoroutineContext ctx, Class<T> type, String name) {
        ch.subscribeGlobalAlways(type, ctx, EventPriority.MONITOR,
                new kotlin.jvm.functions.Function2<T, Object, Object>() {
                    @Override
                    public Object invoke(T e, Object cont) {
                        recordEvent(name, null);
                        return kotlin.Unit.INSTANCE;
                    }
                });
    }

    private static String keyName(int code) {
        switch (code) {
            case 90: return "Z";
            case 88: return "X";
            case 67: return "C";
            case 66: return "B";
            case 65: return "A";
            case 83: return "S";
            case 68: return "D";
            case 87: return "W";
            case 80: return "P";
            case 27: return "ESC";
            case 32: return "SPACE";
            case 17: return "CTRL";
            case 16: return "SHIFT";
            case 37: return "LEFT";
            case 38: return "UP";
            case 39: return "RIGHT";
            case 40: return "DOWN";
            default: return "k" + code;
        }
    }

    // ---------- 采样 ----------

    private void startSampler() {
        Thread t = new Thread(() -> {
            Map<String, Object> last = null;
            long lastBeat = 0;
            int tick = 0;
            String lastUiSig = "";
            String lastSelSig = "";
            while (true) {
                try {
                    Map<String, Object> now = sample();
                    long t0 = System.currentTimeMillis();
                    boolean changed = last == null || !now.equals(last);
                    if (changed || t0 - lastBeat >= HEARTBEAT_MS) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("t", t0);
                        m.put("k", changed ? "state" : "beat");
                        m.putAll(now);
                        append(json(m));
                        last = now;
                        lastBeat = t0;
                    }
                    // UI 变化
                    List<String> views = uiViews();
                    String uiSig = String.join(",", views);
                    if (!uiSig.equals(lastUiSig)) {
                        lastUiSig = uiSig;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("t", System.currentTimeMillis());
                        m.put("k", "ui");
                        m.put("views", views);
                        append(json(m));
                        eventCount++;
                    }
                    // 选中变化
                    Map<String, Object> sel = selectionDetail();
                    String selSig = sel.get("count") + ":" + String.valueOf(sel.get("byType"));
                    if (!selSig.equals(lastSelSig)) {
                        lastSelSig = selSig;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("t", System.currentTimeMillis());
                        m.put("k", "sel");
                        m.putAll(sel);
                        append(json(m));
                    }
                    // 每秒一条富记录：经济、生产、战斗、地图
                    tick += 1;
                    if (tick * SAMPLE_MS >= 1000) {
                        tick = 0;
                        Map<String, Object> rich = richSnapshot();
                        if (rich != null) {
                            rich.put("t", System.currentTimeMillis());
                            rich.put("k", "rich");
                            append(json(rich));
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    Thread.sleep(SAMPLE_MS);
                } catch (InterruptedException ignored) {
                }
            }
        }, "rwpp-probe-sampler");
        t.setDaemon(true);
        t.start();
    }

    /** 经济 / 生产 / 战斗 / 地图 —— 每秒采一次，读不到就少写几项，不抛。 */
    private static Map<String, Object> richSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        Object game = null;
        try {
            org.koin.core.Koin koin = io.github.rwpp.GlobalKt.getAppKoin();
            game = koin == null ? null
                    : koin.get(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(io.github.rwpp.game.Game.class), null, null);
        } catch (Throwable t) {
            return null;
        }
        if (!(game instanceof io.github.rwpp.game.Game)) return null;
        io.github.rwpp.game.Game g = (io.github.rwpp.game.Game) game;

        Integer myTeam = null;
        try {
            io.github.rwpp.game.GameRoom room = g.getGameRoom();
            if (room != null) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("name", room.getDisplayMapName());
                map.put("players", room.getMaxPlayerCount());
                map.put("host", room.isHost());
                out.put("map", map);
                io.github.rwpp.game.Player me = room.getLocalPlayer();
                if (me != null) {
                    myTeam = me.getTeam();
                    Map<String, Object> eco = new LinkedHashMap<>();
                    eco.put("credits", me.getCredits());
                    eco.put("income", me.getIncome());
                    eco.put("team", me.getTeam());
                    out.put("eco", eco);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            io.github.rwpp.game.world.World world = g.getWorld();
            if (world != null) {
                List<?> all = world.getAllObject();
                int ownCount = 0;
                double ownHp = 0;
                int enemyCount = 0;
                double enemyHp = 0;
                Map<String, Object> byType = new LinkedHashMap<>();
                Map<String, Object> enemyTeams = new LinkedHashMap<>();
                if (all != null) {
                    for (Object o : all) {
                        if (!(o instanceof io.github.rwpp.game.units.GameUnit)) continue;
                        io.github.rwpp.game.units.GameUnit u = (io.github.rwpp.game.units.GameUnit) o;
                        int team;
                        try {
                            io.github.rwpp.game.Player p = u.getPlayer();
                            if (p == null) continue;
                            team = p.getTeam();
                        } catch (Throwable t) {
                            continue;
                        }
                        double hp = 0;
                        try {
                            hp = u.getHealth();
                        } catch (Throwable ignored) {
                        }
                        boolean mine = myTeam != null && team == myTeam;
                        if (mine) {
                            ownCount++;
                            ownHp += hp;
                            String name = "unknown";
                            try {
                                if (u.getType() != null) {
                                    String dn = u.getType().getDisplayName();
                                    String nm = u.getType().getName();
                                    name = dn != null && !dn.isEmpty() ? dn : (nm != null ? nm : name);
                                }
                            } catch (Throwable ignored) {
                            }
                            Object prev = byType.get(name);
                            byType.put(name, (prev instanceof Number ? ((Number) prev).intValue() : 0) + 1);
                        } else {
                            enemyCount++;
                            enemyHp += hp;
                            String tk = "t" + team;
                            Object prev = enemyTeams.get(tk);
                            enemyTeams.put(tk, (prev instanceof Number ? ((Number) prev).intValue() : 0) + 1);
                        }
                    }
                }
                Map<String, Object> own = new LinkedHashMap<>();
                own.put("count", ownCount);
                own.put("hp", Math.round(ownHp));
                own.put("byType", byType);
                Map<String, Object> enemy = new LinkedHashMap<>();
                enemy.put("count", enemyCount);
                enemy.put("hp", Math.round(enemyHp));
                enemy.put("byTeam", enemyTeams);
                out.put("own", own);
                out.put("enemy", enemy);
                out.put("cam", List.of(round(world.getCameraX()), round(world.getCameraY())));
                out.put("zoom", round(world.getZoom()));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 与 /state 同源，但只收轻量字段，方便做差分。 */
    private static Map<String, Object> sample() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            out.put("gaming", io.github.rwpp.desktop.GlobalKt.isGaming());
            out.put("over", io.github.rwpp.desktop.GlobalKt.getGameOver());
        } catch (Throwable t) {
            out.put("gaming", null);
        }
        try {
            PointerInfo pi = MouseInfo.getPointerInfo();
            Point p = pi == null ? null : pi.getLocation();
            out.put("cursor", p == null ? List.of(0, 0) : List.of(p.x, p.y));
        } catch (Throwable t) {
            out.put("cursor", List.of(0, 0));
        }
        try {
            org.koin.core.Koin koin = io.github.rwpp.GlobalKt.getAppKoin();
            Object game = koin == null ? null
                    : koin.get(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(io.github.rwpp.game.Game.class), null, null);
            if (game instanceof io.github.rwpp.game.Game) {
                io.github.rwpp.game.world.World world = ((io.github.rwpp.game.Game) game).getWorld();
                if (world != null) {
                    out.put("cam", List.of(round(world.getCameraX()), round(world.getCameraY())));
                    out.put("zoom", round(world.getZoom()));
                    List<?> sel = world.getSelectedUnits();
                    out.put("sel", sel == null ? 0 : sel.size());
                    List<?> all = world.getAllObject();
                    out.put("obj", all == null ? 0 : all.size());
                }
            }
        } catch (Throwable t) {
            out.put("worldError", String.valueOf(t));
        }
        return out;
    }

    private static double round(float v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 当前打开了哪些界面（RWPP 的 UI 单例上有开关）。 */
    private static List<String> uiViews() {
        List<String> views = new ArrayList<>();
        try {
            io.github.rwpp.ui.UI ui = io.github.rwpp.ui.UI.INSTANCE;
            if (ui == null) return views;
            if (ui.getShowMissionView()) views.add("mission");
            if (ui.getShowMultiplayerView()) views.add("multiplayer");
            if (ui.getShowReplayView()) views.add("replay");
            if (ui.getShowSettingsView()) views.add("settings");
            if (ui.getShowModsView()) views.add("mods");
            if (ui.getShowRoomView()) views.add("room");
            if (ui.getShowExtensionView()) views.add("extension");
            if (ui.getShowContributorList()) views.add("contributors");
            if (ui.getShowResourceBrowser()) views.add("resourceBrowser");
            if (ui.getShowNetworkDialog()) views.add("networkDialog");
            try {
                if (ui.getDialogWidget() != null) views.add("dialog");
                if (ui.getQuestion() != null) views.add("question");
                if (ui.getWarning() != null) views.add("warning");
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        return views;
    }

    /** 选中单位的明细：类型、血量、世界坐标。 */
    private static Map<String, Object> selectionDetail() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();
        Map<String, Object> byType = new LinkedHashMap<>();
        int total = 0;
        try {
            org.koin.core.Koin koin = io.github.rwpp.GlobalKt.getAppKoin();
            Object game = koin == null ? null
                    : koin.get(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(io.github.rwpp.game.Game.class), null, null);
            if (!(game instanceof io.github.rwpp.game.Game)) return out;
            io.github.rwpp.game.world.World world = ((io.github.rwpp.game.Game) game).getWorld();
            if (world == null) return out;
            List<?> selected = world.getSelectedUnits();
            if (selected == null) return out;
            total = selected.size();
            for (Object o : selected) {
                if (!(o instanceof io.github.rwpp.game.units.GameUnit)) continue;
                io.github.rwpp.game.units.GameUnit u = (io.github.rwpp.game.units.GameUnit) o;
                Map<String, Object> item = new LinkedHashMap<>();
                String name = "unknown";
                try {
                    if (u.getType() != null) {
                        String dn = u.getType().getDisplayName();
                        String nm = u.getType().getName();
                        name = dn != null && !dn.isEmpty() ? dn : (nm != null ? nm : name);
                    }
                } catch (Throwable ignored) {
                }
                item.put("type", name);
                try { item.put("hp", Math.round(u.getHealth())); } catch (Throwable ignored) { }
                try { item.put("x", Math.round(u.getX())); } catch (Throwable ignored) { }
                try { item.put("y", Math.round(u.getY())); } catch (Throwable ignored) { }
                if (items.size() < 30) items.add(item);
                Object prev = byType.get(name);
                byType.put(name, (prev instanceof Number ? ((Number) prev).intValue() : 0) + 1);
            }
        } catch (Throwable ignored) {
        }
        out.put("count", total);
        out.put("byType", byType);
        out.put("items", items);
        return out;
    }

    // ---------- HTTP ----------

    private void startHttp() {
        for (int candidate = DEFAULT_PORT; candidate < DEFAULT_PORT + 20; candidate++) {
            ServerSocket server = tryBind(candidate);
            if (server == null) continue;
            port = candidate;
            Thread t = new Thread(() -> serve(server), "rwpp-probe-http");
            t.setDaemon(true);
            t.start();
            log("probe listening on http://127.0.0.1:" + port + "/state");
            return;
        }
        log("probe failed: no free port in " + DEFAULT_PORT + ".." + (DEFAULT_PORT + 19));
    }

    private static ServerSocket tryBind(int candidate) {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), candidate));
            return server;
        } catch (IOException e) {
            return null;
        }
    }

    private static void log(String message) {
        System.out.println("[rwpp-probe] " + message);
        System.err.println("[rwpp-probe] " + message);
    }

    private void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                String requestLine = readRequestLine(socket.getInputStream());
                String path = parsePath(requestLine);
                String query = parseQuery(requestLine);
                String body;
                int code = 200;
                if (path.equals("/ping")) {
                    body = json(mapOf("ok", true, "id", "probe", "build", BUILD_TAG, "uptimeMs", System.currentTimeMillis() - startedAt,
                            "events", eventCount, "record", sessionFile == null ? null : sessionFile.getName()));
                } else if (path.equals("/state")) {
                    body = json(snapshot());
                } else if (path.equals("/cmdlist")) {
                    body = json(commandList());
                } else if (path.equals("/cmd")) {
                    body = json(runCommand(firstParam(query, "line")));
                } else if (path.equals("/units")) {
                    body = json(unitPositions(firstParam(query, "side"), firstParam(query, "neutral")));
                } else if (path.equals("/missions")) {
                    body = json(missionList());
                } else if (path.equals("/panel")) {
                    body = json(panelInfo());
                } else if (path.equals("/act")) {
                    body = json(runAction(firstParam(query, "do"), firstParam(query, "v")));
                } else if (path.equals("/tail")) {
                    int n = intParam(query, "n", 40);
                    body = json(mapOf("file", sessionFile == null ? null : sessionFile.getAbsolutePath(),
                            "lines", tail(n)));
                } else if (path.equals("/records")) {
                    body = json(mapOf("files", listRecords()));
                } else {
                    code = 404;
                    body = json(mapOf("error", "only /ping /state /tail /records", "path", path));
                }
                writeResponse(socket.getOutputStream(), code, body);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按名字 + 参数类型找一个单参数方法（重载友好）。 */
    private static java.lang.reflect.Method findOneArgMethod(Class<?> start, String name, Class<?> argType) {
        for (Class<?> k = start; k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1) continue;
                if (ps[0].isAssignableFrom(argType)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** 拿对象的内部实现（类名以 com.corrodinggames 开头就本体，否则翻字段找）。 */
    private static Object internalOfPrefix(Object obj, String prefix) {
        if (obj == null) return null;
        if (obj.getClass().getName().startsWith(prefix)) return obj;
        for (Class<?> k = obj.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (!f.getType().getName().startsWith(prefix)) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    /** 场上所有单位的坐标：分「我方 / 其他队伍」。 */
    private static Map<String, Object> unitPositions(String side, String neutral) {
        io.github.rwpp.game.Game game = gameOrNull();
        if (game == null) return mapOf("ok", false, "error", "拿不到 Game 实例");
        io.github.rwpp.game.world.World world = game.getWorld();
        if (world == null) return mapOf("ok", false, "error", "对局未就绪");
        boolean skipNeutral = !"1".equals(neutral);
        Integer myTeam = null;
        try {
            io.github.rwpp.game.GameRoom room = game.getGameRoom();
            io.github.rwpp.game.Player me = room == null ? null : room.getLocalPlayer();
            if (me != null) myTeam = me.getTeam();
        } catch (Throwable ignored) {
        }
        List<Object> mine = new ArrayList<>();
        List<Object> others = new ArrayList<>();
        int otherTotal = 0;
        Map<String, Object> teamCounts = new LinkedHashMap<>();
        List<?> all = world.getAllObject();
        if (all != null) {
            for (Object o : all) {
                if (!(o instanceof io.github.rwpp.game.units.GameUnit)) continue;
                io.github.rwpp.game.units.GameUnit u = (io.github.rwpp.game.units.GameUnit) o;
                int team;
                try {
                    if (u.getPlayer() == null) continue;
                    team = u.getPlayer().getTeam();
                } catch (Throwable t) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("team", team);
                item.put("type", unitTypeName(u));
                item.put("x", Math.round(u.getX()));
                item.put("y", Math.round(u.getY()));
                try { item.put("hp", Math.round(u.getHealth())); } catch (Throwable ignored) { }
                boolean isMine = myTeam != null && team == myTeam;
                if (!isMine && skipNeutral && team < 0) continue;
                if (isMine) {
                    if (mine.size() < 200) mine.add(item);
                } else {
                    otherTotal += 1;
                    String tk = "t" + team;
                    Object prev = teamCounts.get(tk);
                    teamCounts.put(tk, (prev instanceof Number ? ((Number) prev).intValue() : 0) + 1);
                    if (!"mine".equals(side) && others.size() < 200) others.add(item);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("myTeam", myTeam);
        out.put("mineCount", mine.size());
        out.put("otherCount", otherTotal);
        out.put("otherByTeam", teamCounts);
        if (!"other".equals(side)) out.put("mine", mine);
        if (!"mine".equals(side)) out.put("others", others);
        return out;
    }

    private static String unitTypeName(io.github.rwpp.game.units.GameUnit u) {
        try {
            if (u.getType() == null) return "unknown";
            String dn = u.getType().getDisplayName();
            String nm = u.getType().getName();
            return dn != null && !dn.isEmpty() ? dn : (nm != null ? nm : "unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 战役地图清单。 */
    private static Map<String, Object> missionList() {
        io.github.rwpp.game.Game game = gameOrNull();
        if (game == null) return mapOf("ok", false, "error", "拿不到 Game 实例");
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            List<Object> types = new ArrayList<>();
            List<?> ts = game.getAllMissionTypes();
            if (ts != null) {
                for (Object o : ts) {
                    if (!(o instanceof io.github.rwpp.game.map.MissionType)) continue;
                    io.github.rwpp.game.map.MissionType t = (io.github.rwpp.game.map.MissionType) o;
                    types.add(t.pathName());
                }
            }
            List<Object> missions = new ArrayList<>();
            List<?> ms = game.getAllMissions();
            if (ms != null) {
                for (Object o : ms) {
                    if (!(o instanceof io.github.rwpp.game.map.Mission)) continue;
                    io.github.rwpp.game.map.Mission m = (io.github.rwpp.game.map.Mission) o;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", m.getName());
                    item.put("map", m.getMapName());
                    item.put("type", m.getType() == null ? null : m.getType().pathName());
                    missions.add(item);
                }
            }
            out.put("ok", true);
            out.put("typeCount", types.size());
            out.put("types", types);
            out.put("missionCount", missions.size());
            out.put("missions", missions);
        } catch (Throwable t) {
            return mapOf("ok", false, "error", String.valueOf(t));
        }
        return out;
    }

    /** 单位的动作列表：优先 aX()。 */
    private static List<?> unitActions(Object unit, Object typeObj) {
        try {
            Object v = findZeroArg(unit.getClass(), "aX").invoke(unit);
            if (v instanceof List && !((List<?>) v).isEmpty()) return (List<?>) v;
        } catch (Throwable ignored) {
        }
        return actionListOf(typeObj);
    }

    /** 单位类型的动作列表：候选方法名挨个试，返回第一个 List。 */
    private static List<?> actionListOf(Object typeObj) {
        if (typeObj == null) return null;
        List<?> fallback = null;
        for (Class<?> k = typeObj.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(typeObj);
                    if (!(v instanceof List)) continue;
                    List<?> list = (List<?>) v;
                    if (!list.isEmpty()) return list;
                    if (fallback == null) fallback = list;
                } catch (Throwable ignored) {
                }
            }
        }
        return fallback;
    }

    private static Object commandFor(io.github.rwpp.game.Game game) throws Exception {
        Class<?> fwClass = Class.forName("com.corrodinggames.rts.gameFramework.l");
        Object fw = fwClass.getMethod("B").invoke(null);
        Object cc = fieldValue(fw, "cf");
        Object player = game.getGameRoom().getLocalPlayer();
        java.lang.reflect.Method m = findOneArgMethod(cc.getClass(), "a", player.getClass());
        if (m == null) throw new NoSuchMethodException("CommandController 没有 a(player)");
        return m.invoke(cc, player);
    }

    private static void addUnitToCommand(Object cmd, Object unit) throws Exception {
        Object target = internalOfPrefix(unit, "com.corrodinggames");
        if (target == null) target = unit;
        java.lang.reflect.Method m = findOneArgMethod(cmd.getClass(), "a", target.getClass());
        if (m == null) throw new NoSuchMethodException("命令对象没有 a(unit)");
        m.invoke(cmd, target);
    }

    private static void invokeQuiet(Object target, String name) {
        try {
            findZeroArg(target.getClass(), name).invoke(target);
        } catch (Throwable ignored) {
        }
    }

    /** 选中单位的面板动作列表（RWPP/游戏右侧面板的内容）。 */
    private static Map<String, Object> panelInfo() {
        io.github.rwpp.game.Game game = gameOrNull();
        if (game == null) return mapOf("ok", false, "error", "拿不到 Game 实例");
        io.github.rwpp.game.world.World world = game.getWorld();
        List<?> sel = world == null ? null : world.getSelectedUnits();
        if (sel == null || sel.isEmpty()) return mapOf("ok", false, "error", "没有选中的单位");
        Object unit = sel.get(0);
        Object typeObj = null;
        try {
            java.lang.reflect.Method gt = unit.getClass().getMethod("r");
            typeObj = gt.invoke(unit);
        } catch (Throwable t) {
            try {
                java.lang.reflect.Method gt = unit.getClass().getMethod("getType");
                typeObj = gt.invoke(unit);
            } catch (Throwable ignored) {
            }
        }
        if (typeObj == null) return mapOf("ok", false, "error", "拿不到单位类型");
        List<?> actions = actionListOf(unit);
        Map<String, Object> probe = new LinkedHashMap<>();
        for (String name : new String[]{"C", "A", "B", "D", "N", "M", "P", "E", "F", "G"}) {
            try {
                Object v = findZeroArg(typeObj.getClass(), name).invoke(typeObj);
                if (v instanceof List) {
                    probe.put(name, "List(" + ((List<?>) v).size() + ")");
                    if (actions == null && !((List<?>) v).isEmpty()) actions = (List<?>) v;
                } else {
                    probe.put(name, v == null ? "null" : v.getClass().getSimpleName());
                }
            } catch (Throwable ignored) {
            }
        }
        List<Object> out = new ArrayList<>();
        if (actions != null) {
            int idx = 0;
            for (Object a : actions) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("index", idx++);
                item.put("class", a == null ? "null" : a.getClass().getName());
                for (String m : new String[]{"a", "b"}) {
                    try {
                        Object v = findZeroArg(a.getClass(), m).invoke(a);
                        if (v instanceof String && !((String) v).isEmpty()) item.put(m, v);
                    } catch (Throwable ignored) {
                    }
                }
                out.add(item);
            }
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("unit", unitTypeNameQuiet(unit));
        res.put("count", out.size());
        res.put("probe", probe);
        Map<String, Object> listMethods = new LinkedHashMap<>();
        for (Class<?> k = unit.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(unit);
                    listMethods.put(m.getName(), v == null ? "null" : "List(" + ((List<?>) v).size() + ")");
                    if (v instanceof List && !((List<?>) v).isEmpty() && actions == null) {
                        actions = (List<?>) v;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        for (Class<?> k = typeObj.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                try {
                    m.setAccessible(true);
                    Object v = m.invoke(typeObj);
                    listMethods.put("type:" + m.getName(), v == null ? "null" : "List(" + ((List<?>) v).size() + ")");
                } catch (Throwable ignored) {
                }
            }
        }
        res.put("listMethods", listMethods);
        res.put("actions", out);
        return res;
    }

    private static java.lang.reflect.Method findZeroArg(Class<?> start, String name) throws NoSuchMethodException {
        for (Class<?> k = start; k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 0) continue;
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object fieldValue(Object target, String name) throws Exception {
        for (Class<?> k = target.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void setFieldValue(Object target, String name, Object value) throws Exception {
        for (Class<?> k = target.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String unitTypeNameQuiet(Object unit) {
        try {
            java.lang.reflect.Method m = unit.getClass().getMethod("r");
            Object t = m.invoke(unit);
            if (t != null) {
                java.lang.reflect.Method nm = t.getClass().getMethod("i");
                return String.valueOf(nm.invoke(t));
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String modifierTag(java.lang.reflect.Field f) {        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) return "static ";
        if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) return "final ";
        return "";
    }

    /** 从 API 的 GameUnit 包装对象里反射出游戏内部的单位对象（com.corrodinggames.*）。 */
    private static Object internalUnitOf(Object apiUnit) {
        for (Class<?> k = apiUnit.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (!f.getType().getName().startsWith("com.corrodinggames")) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(apiUnit);
                    if (v != null) return v;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static String firstParam(String query, String key) {        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (part.substring(0, eq).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (Throwable t) {
                    return part.substring(eq + 1);
                }
            }
        }
        return "";
    }

    private static io.github.rwpp.game.Game gameOrNull() {
        try {
            org.koin.core.Koin koin = io.github.rwpp.GlobalKt.getAppKoin();
            Object g = koin == null ? null
                    : koin.get(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(io.github.rwpp.game.Game.class), null, null);
            return g instanceof io.github.rwpp.game.Game ? (io.github.rwpp.game.Game) g : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在游戏线程上跑一段逻辑并等它结束（未就绪就超时返回）。 */
    private static boolean onGameThread(io.github.rwpp.game.Game game, Runnable body) {
        if (game == null) return false;
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] ok = {false};
        try {
            game.post(new kotlin.jvm.functions.Function0<kotlin.Unit>() {
                @Override
                public kotlin.Unit invoke() {
                    try {
                        body.run();
                        ok[0] = true;
                    } catch (Throwable ignored) {
                    } finally {
                        latch.countDown();
                    }
                    return kotlin.Unit.INSTANCE;
                }
            });
            latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
        }
        return ok[0];
    }

    /** RWPP 内置聊天命令的清单（只读）。 */
    private static Map<String, Object> commandList() {
        List<Object> commands = new ArrayList<>();
        String prefix = "";
        try {
            io.github.rwpp.command.CommandHandler handler = io.github.rwpp.GlobalKt.getCommands();
            if (handler != null) {
                prefix = handler.getPrefix();
                List<?> list = handler.getCommandList();
                if (list != null) {
                    for (Object o : list) {
                        if (!(o instanceof io.github.rwpp.command.CommandHandler.Command)) continue;
                        io.github.rwpp.command.CommandHandler.Command c = (io.github.rwpp.command.CommandHandler.Command) o;
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("text", c.getText());
                        m.put("params", c.getParamText());
                        m.put("desc", c.getDescription());
                        commands.add(m);
                    }
                }
            }
        } catch (Throwable t) {
            return mapOf("ok", false, "error", String.valueOf(t));
        }
        return mapOf("ok", true, "prefix", prefix, "count", commands.size(), "commands", commands);
    }

    /** 执行一条聊天命令（会真的生效，慎用）。 */
    private static Map<String, Object> runCommand(String line) {
        if (line == null || line.trim().isEmpty()) return mapOf("ok", false, "error", "缺少 line 参数");
        try {
            io.github.rwpp.command.CommandHandler handler = io.github.rwpp.GlobalKt.getCommands();
            if (handler == null) return mapOf("ok", false, "error", "命令系统未初始化");
            Object resp = handler.handleMessage(line);
            if (resp instanceof io.github.rwpp.command.CommandHandler.CommandResponse) {
                io.github.rwpp.command.CommandHandler.CommandResponse r = (io.github.rwpp.command.CommandHandler.CommandResponse) resp;
                return mapOf("ok", true, "line", line, "type", String.valueOf(r.getType()),
                        "command", r.getCommand() == null ? null : r.getCommand().getText(),
                        "run", r.getRunCommand());
            }
            return mapOf("ok", true, "line", line, "response", String.valueOf(resp));
        } catch (Throwable t) {
            return mapOf("ok", false, "error", String.valueOf(t));
        }
    }

    /** 少量写操作：开局 / 改钱 / 按类型选中 / 取消选中。 */
    private static Map<String, Object> runAction(String action, String value) {
        io.github.rwpp.game.Game game = gameOrNull();
        if (game == null) return mapOf("ok", false, "error", "拿不到 Game 实例");
        if (action == null || action.isEmpty()) return mapOf("ok", false, "error", "缺少 do 参数");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        final Object[] detail = new Object[1];
        boolean ran = onGameThread(game, () -> {
            try {
                if ("start".equals(action)) {
                    game.hostNewSinglePlayer(true);
                    detail[0] = "hostNewSinglePlayer(true) 已调用";
                } else if ("startmatch".equals(action)) {
                    io.github.rwpp.game.GameRoom room = game.getGameRoom();
                    if (room == null) { detail[0] = "没有房间对象"; return; }
                    room.startGame();
                    detail[0] = "startGame() 已调用";
                } else if ("spawn".equals(action)) {
                    io.github.rwpp.game.GameRoom room = game.getGameRoom();
                    io.github.rwpp.game.Player me = room == null ? null : room.getLocalPlayer();
                    if (room == null || me == null) { detail[0] = "没有房间或本地玩家"; return; }
                    String[] parts = (value == null ? "" : value).split(",");
                    String needle = parts.length > 0 ? parts[0].trim().toLowerCase() : "";
                    float sx = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 0f;
                    float sy = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 0f;
                    int count = parts.length > 3 ? Integer.parseInt(parts[3].trim()) : 1;
                    io.github.rwpp.game.units.UnitType picked = null;
                    List<?> types = game.getAllUnitTypes();
                    if (types != null) {
                        for (Object o : types) {
                            if (!(o instanceof io.github.rwpp.game.units.UnitType)) continue;
                            io.github.rwpp.game.units.UnitType t = (io.github.rwpp.game.units.UnitType) o;
                            String label = (String.valueOf(t.getDisplayName()) + " " + String.valueOf(t.getName())).toLowerCase();
                            if (label.contains(needle)) { picked = t; break; }
                        }
                    }
                    if (picked == null) { detail[0] = "没找到单位类型：" + needle; return; }
                    room.spawnUnit(me, picked, sx, sy, count);
                    detail[0] = "spawnUnit(" + picked.getDisplayName() + ", " + sx + "," + sy + " x" + count + ") 已调用";
                } else if ("unpause".equals(action)) {
                    io.github.rwpp.game.GameRoom room = game.getGameRoom();
                    if (room == null) { detail[0] = "没有房间对象"; return; }
                    room.pauseOrResumeGame(false);
                    detail[0] = "pauseOrResumeGame(false) 已调用";
                } else if ("build".equals(action)) {
                    // v = 建筑类型关键字,x,y
                    io.github.rwpp.game.world.World world = game.getWorld();
                    List<?> sel = world == null ? null : world.getSelectedUnits();
                    if (sel == null || sel.isEmpty()) { detail[0] = "没有选中单位"; return; }
                    String[] parts = (value == null ? "" : value).split(",");
                    String needle = parts.length > 0 ? parts[0].trim().toLowerCase() : "";
                    float bx = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 0f;
                    float by = parts.length > 2 ? Float.parseFloat(parts[2].trim()) : 0f;
                    Object typeObj = null;
                    String typeLabel = "";
                    List<?> types = game.getAllUnitTypes();
                    if (types != null) {
                        for (Object o : types) {
                            if (!(o instanceof io.github.rwpp.game.units.UnitType)) continue;
                            io.github.rwpp.game.units.UnitType t = (io.github.rwpp.game.units.UnitType) o;
                            String label = (String.valueOf(t.getDisplayName()) + " " + String.valueOf(t.getName())).toLowerCase();
                            if (label.contains(needle)) { typeObj = t; typeLabel = String.valueOf(t.getDisplayName()); break; }
                        }
                    }
                    if (typeObj == null) { detail[0] = "没找到建筑类型：" + needle; return; }
                    Object am = internalOfPrefix(typeObj, "com.corrodinggames");
                    if (am == null) { detail[0] = "建筑类型拿不到内部对象"; return; }
                    int queued = 0;
                    List<String> errs = new ArrayList<>();
                    for (Object u : sel) {
                        try {
                            Object unit = u;
                            java.lang.reflect.Method mN = findOneArgMethod(unit.getClass(), "n", am.getClass());
                            if (mN == null) { errs.add("找不到 n(单位类型) 方法"); continue; }
                            Object wp = mN.invoke(unit, am);
                            if (wp != null) {
                                java.lang.reflect.Method mB = wp.getClass().getMethod("b", float.class, float.class);
                                mB.invoke(wp, bx, by);
                                queued += 1;
                            }
                        } catch (Throwable t) {
                            if (errs.size() < 3) errs.add(String.valueOf(t));
                        }
                    }
                    detail[0] = "下建造令 " + queued + "/" + sel.size() + "：" + typeLabel + " → (" + bx + "," + by + ")"
                            + (errs.isEmpty() ? "" : " 错误：" + String.join(" | ", errs));
                } else if ("inspect".equals(action)) {
                    io.github.rwpp.game.world.World world = game.getWorld();
                    List<?> sel = world == null ? null : world.getSelectedUnits();
                    if (sel == null || sel.isEmpty()) { detail[0] = "没有选中的单位"; return; }
                    Object u = sel.get(0);
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("apiClass", u.getClass().getName());
                    List<String> fields = new ArrayList<>();
                    for (Class<?> k = u.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            fields.add(modifierTag(f) + f.getType().getSimpleName() + " " + f.getName());
                        }
                    }
                    List<String> moveMethods = new ArrayList<>();
                    for (Class<?> k = u.getClass(); k != null && !k.equals(Object.class); k = k.getSuperclass()) {
                        for (java.lang.reflect.Method m2 : k.getDeclaredMethods()) {
                            Class<?>[] ps = m2.getParameterTypes();
                            if (ps.length == 2 && ps[0] == float.class && ps[1] == float.class) {
                                moveMethods.add(k.getSimpleName() + "." + m2.getName() + "(float,float)");
                            }
                        }
                    }
                    info.put("fields", fields);
                    info.put("floatFloatMethods", moveMethods);
                    List<String> oneArg = new ArrayList<>();
                    for (Class<?> k2 = u.getClass(); k2 != null && !k2.equals(Object.class); k2 = k2.getSuperclass()) {
                        for (java.lang.reflect.Method m3 : k2.getDeclaredMethods()) {
                            if (m3.getParameterCount() == 1) {
                                oneArg.add(k2.getSimpleName() + "." + m3.getName() + "(" + m3.getParameterTypes()[0].getSimpleName() + ")");
                            }
                        }
                    }
                    info.put("oneArgMethods", oneArg);
                    detail[0] = info;
                } else if ("move".equals(action)) {
                    io.github.rwpp.game.world.World world = game.getWorld();
                    List<?> sel = world == null ? null : world.getSelectedUnits();
                    if (sel == null || sel.isEmpty()) { detail[0] = "没有选中的单位"; return; }
                    String[] parts = (value == null ? "" : value).split(",");
                    float tx = parts.length > 0 ? Float.parseFloat(parts[0].trim()) : 0f;
                    float ty = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 0f;
                    int okCount = 0;
                    List<String> errors = new ArrayList<>();
                    for (Object u : sel) {
                        try {
                            Object internal = u;
                            java.lang.reflect.Method mv;
                            try {
                                mv = internal.getClass().getMethod("e", float.class, float.class);
                            } catch (NoSuchMethodException nsm) {
                                internal = internalUnitOf(u);
                                if (internal == null) { errors.add("找不到内部单位对象"); continue; }
                                mv = internal.getClass().getMethod("e", float.class, float.class);
                            }
                            mv.invoke(internal, tx, ty);
                            okCount += 1;
                        } catch (Throwable t) {
                            if (errors.size() < 3) errors.add(String.valueOf(t));
                        }
                    }
                    detail[0] = "下移动令 " + okCount + "/" + sel.size() + " 到 (" + tx + "," + ty + ")"
                            + (errors.isEmpty() ? "" : " 错误：" + String.join(" | ", errors));
                } else if ("use".equals(action)) {
                    // v = 面板动作名或序号[,x,y]
                    io.github.rwpp.game.world.World world = game.getWorld();
                    List<?> sel = world == null ? null : world.getSelectedUnits();
                    if (sel == null || sel.isEmpty()) { detail[0] = "没有选中单位"; return; }
                    Object unit = sel.get(0);
                    Object typeObj;
                    try {
                        typeObj = findZeroArg(unit.getClass(), "r").invoke(unit);
                    } catch (Throwable t) {
                        detail[0] = "拿不到单位类型：" + t; return;
                    }
                    List<?> acts;
                    try {
                        acts = actionListOf(unit);
                    } catch (Throwable t) {
                        detail[0] = "读不到动作列表：" + t; return;
                    }
                    if (acts == null || acts.isEmpty()) { detail[0] = "动作列表是空的"; return; }
                    String[] parts = (value == null ? "" : value).split(",");
                    String needle = parts.length > 0 ? parts[0].trim() : "";
                    Object picked = null;
                    String pickedLabel = "";
                    int idx = 0;
                    for (Object a : acts) {
                        List<String> labels = new ArrayList<>();
                        for (String m : new String[]{"b", "a"}) {
                            try {
                                Object s2 = findZeroArg(a.getClass(), m).invoke(a);
                                if (s2 instanceof String && !((String) s2).isEmpty()) labels.add((String) s2);
                            } catch (Throwable ignored) {
                            }
                        }
                        boolean hit = String.valueOf(idx).equals(needle);
                        if (!hit) {
                            for (String label : labels) {
                                if (label.equalsIgnoreCase(needle) || label.toLowerCase().contains(needle.toLowerCase())) { hit = true; break; }
                            }
                        }
                        if (hit) {
                            picked = a;
                            pickedLabel = labels.isEmpty() ? ("#" + idx) : labels.get(0);
                            break;
                        }
                        idx += 1;
                    }
                    if (picked == null) { detail[0] = "面板里没有这个动作：" + needle; return; }
                    // 动作对象自带的执行入口：b(单位)
                    try {
                        Object target = internalOfPrefix(unit, "com.corrodinggames");
                        if (target == null) target = unit;
                        java.lang.reflect.Method exec = findOneArgMethod(picked.getClass(), "b", target.getClass());
                        if (exec != null) {
                            Object r = exec.invoke(picked, target);
                            detail[0] = "面板动作「" + pickedLabel + "」已执行，返回 " + r;
                            return;
                        }
                    } catch (Throwable t) {
                        detail[0] = "执行失败：" + t;
                        return;
                    }
                    if (parts.length > 2) {
                        float px = Float.parseFloat(parts[1].trim());
                        float py = Float.parseFloat(parts[2].trim());
                        try {
                            Object cmd = commandFor(game);
                            addUnitToCommand(cmd, unit);
                            setFieldValue(cmd, "k", picked);
                            java.lang.reflect.Method mv = findOneArgMethod(cmd.getClass(), "a", float.class);
                            if (mv != null) { mv.invoke(cmd, px, py); }
                            invokeQuiet(cmd, "b");
                            invokeQuiet(cmd, "g");
                            detail[0] = "已下达：" + pickedLabel + " → (" + px + "," + py + ")";
                        } catch (Throwable t) {
                            detail[0] = "下达失败：" + t;
                        }
                        return;
                    }
                    try {
                        Object cmd = commandFor(game);
                        addUnitToCommand(cmd, unit);
                        setFieldValue(cmd, "k", picked);
                        invokeQuiet(cmd, "b");
                        invokeQuiet(cmd, "g");
                        detail[0] = "已执行面板动作：" + pickedLabel;
                    } catch (Throwable t) {
                        detail[0] = "执行失败：" + t;
                    }
                } else if ("mission".equals(action)) {
                    String needle = value == null ? "" : value.toLowerCase();
                    io.github.rwpp.game.map.Mission picked = null;
                    List<?> ms = game.getAllMissions();
                    if (ms != null) {
                        for (Object o : ms) {
                            if (!(o instanceof io.github.rwpp.game.map.Mission)) continue;
                            io.github.rwpp.game.map.Mission m = (io.github.rwpp.game.map.Mission) o;
                            if (String.valueOf(m.getName()).toLowerCase().contains(needle)) { picked = m; break; }
                        }
                    }
                    if (picked == null) { detail[0] = "没找到战役：" + needle; return; }
                    game.startNewMissionGame(io.github.rwpp.game.base.Difficulty.Medium, picked);
                    detail[0] = "startNewMissionGame(" + picked.getName() + ", Medium) 已调用";
                } else if ("credits".equals(action)) {
                    io.github.rwpp.game.GameRoom room = game.getGameRoom();
                    io.github.rwpp.game.Player me = room == null ? null : room.getLocalPlayer();
                    if (me == null) { detail[0] = "没有本地玩家"; return; }
                    int v = Integer.parseInt(value);
                    me.setCredits(v);
                    detail[0] = "资金设为 " + v + "（实际 " + me.getCredits() + "）";
                } else if ("select".equals(action)) {
                    io.github.rwpp.game.world.World world = game.getWorld();
                    io.github.rwpp.game.GameRoom room = game.getGameRoom();
                    io.github.rwpp.game.Player me = room == null ? null : room.getLocalPlayer();
                    if (world == null || me == null) { detail[0] = "对局未就绪"; return; }
                    world.clearSelectedUnits();
                    int picked = 0;
                    String needle = value == null ? "" : value.toLowerCase();
                    List<?> all = world.getAllObject();
                    if (all != null) {
                        for (Object o : all) {
                            if (!(o instanceof io.github.rwpp.game.units.GameUnit)) continue;
                            io.github.rwpp.game.units.GameUnit u = (io.github.rwpp.game.units.GameUnit) o;
                            if (u.getPlayer() == null || u.getPlayer().getTeam() != me.getTeam()) continue;
                            String typeName = "";
                            try {
                                if (u.getType() != null) {
                                    typeName = (u.getType().getDisplayName() + " " + u.getType().getName()).toLowerCase();
                                }
                            } catch (Throwable ignored) {
                            }
                            if (!needle.isEmpty() && !typeName.contains(needle)) continue;
                            world.selectUnit(u);
                            picked += 1;
                        }
                    }
                    detail[0] = "选中 " + picked + " 个（匹配「" + (value == null ? "" : value) + "」）";
                } else if ("unselect".equals(action)) {
                    io.github.rwpp.game.world.World world = game.getWorld();
                    if (world != null) world.clearSelectedUnits();
                    detail[0] = "已取消选中";
                } else {
                    detail[0] = "不支持的动作：" + action;
                }
            } catch (Throwable t) {
                detail[0] = String.valueOf(t);
            }
        });
        result.put("ok", ran);
        result.put("detail", detail[0]);
        return result;
    }

    private static int intParam(String query, String key, int fallback) {
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (part.substring(0, eq).equals(key)) {
                try {
                    return Integer.parseInt(part.substring(eq + 1));
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static List<String> tail(int n) {
        List<String> out = new ArrayList<>();
        if (sessionFile == null || !sessionFile.exists()) return out;
        try {
            List<String> all = Files.readAllLines(sessionFile.toPath(), StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - n);
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (Throwable t) {
            return out;
        }
    }

    private static List<String> listRecords() {
        List<String> names = new ArrayList<>();
        File dir = sessionFile == null ? null : sessionFile.getParentFile();
        if (dir == null) return names;
        File[] files = dir.listFiles((d, name) -> name.startsWith("rec-") && name.endsWith(".jsonl"));
        if (files == null) return names;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) names.add(f.getName() + " (" + f.length() + "B)");
        return names;
    }

    private static String readRequestLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (prev == '\r' && b == '\n') break;
            if (b != '\r') buf.write(b);
            prev = b;
            if (buf.size() > 4096) break;
        }
        return buf.toString(StandardCharsets.UTF_8.name()).trim();
    }

    private static String parsePath(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return "/";
        String target = parts[1];
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private static String parseQuery(String requestLine) {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return "";
        String target = parts[1];
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(q + 1) : "";
    }

    private static void writeResponse(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.flush();
    }

    // ---------- 状态快照 ----------

    private Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("uptimeMs", System.currentTimeMillis() - startedAt);
        out.put("events", eventCount);
        out.put("record", sessionFile == null ? null : sessionFile.getAbsolutePath());
        out.putAll(sample());

        out.put("ui", uiViews());
        out.put("selDetail", selectionDetail());

        Map<String, Object> rich = richSnapshot();
        if (rich != null) out.putAll(rich);

        Object game = null;
        try {
            org.koin.core.Koin koin = io.github.rwpp.GlobalKt.getAppKoin();
            out.put("koin", koin != null);
            if (koin != null) {
                game = koin.get(kotlin.jvm.internal.Reflection.getOrCreateKotlinClass(io.github.rwpp.game.Game.class), null, null);
            }
            out.put("game", game != null);
        } catch (Throwable t) {
            out.put("gameError", String.valueOf(t));
        }
        if (game instanceof io.github.rwpp.game.Game) {
            try {
                io.github.rwpp.game.GameRoom room = ((io.github.rwpp.game.Game) game).getGameRoom();
                if (room != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("displayMapName", room.getDisplayMapName());
                    r.put("isHost", room.isHost());
                    r.put("maxPlayerCount", room.getMaxPlayerCount());
                    out.put("room", r);
                }
            } catch (Throwable t) {
                out.put("roomError", String.valueOf(t));
            }
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static String json(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(',');
                first = false;
                writeJson(sb, item);
            }
            sb.append(']');
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else {
            sb.append('"').append(escape(String.valueOf(value))).append('"');
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
