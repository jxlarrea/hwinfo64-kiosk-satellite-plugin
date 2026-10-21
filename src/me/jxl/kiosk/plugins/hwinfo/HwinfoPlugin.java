package me.jxl.kiosk.plugins.hwinfo;

import android.content.Context;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Reads the sensors of one or more PCs running HWiNFO's Remote Sensor Monitoring, sends the readings people pick to
 * Home Assistant and draws them as a native screensaver. A rule can switch to that screensaver while a reading, such
 * as the game's frame rate, stays past a limit.
 */
public final class HwinfoPlugin implements KioskPlugin {
    static final String PLUGIN_ID = "hwinfo-pc-monitor";
    static final String SCREENSAVER = "board";
    static final String MODE = "plugin:" + PLUGIN_ID + ":" + SCREENSAVER;
    static final String[] READING_FIELDS = {"exports.reading", "panels.tiles.reading", "panels.headerReading", "ruleReading"};
    static final int MAX_CHOICES = 2000;
    /** A computer that does not answer is tried again this often, so one PC that is off never slows the rest. */
    static final long RETRY_MS = 10_000;

    /** One configured computer and what it last reported. Touched only on the worker thread. */
    static final class Monitor {
        final Config.Computer computer;
        final HwinfoConnection connection;
        HwinfoSnapshot snapshot;
        String error = "";
        long retryAt;
        String catalog = "";

        Monitor(Config.Computer computer) {
            this.computer = computer;
            connection = new HwinfoConnection(computer.address, computer.port);
        }

        /** The name readings are stored under: the computer's own name, which survives a new IP address. */
        String key() {
            String host = snapshot == null ? "" : snapshot.host.trim();
            return (host.isEmpty() ? computer.address : host).toUpperCase(Locale.ROOT);
        }
    }

    private PluginHost host;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> polling;
    private int pollingSeconds;
    private volatile Config config;
    private Map<String, Object> settings = new HashMap<>();
    /** Keyed by computer name, then by address and port for computers that never answered. */
    private final Map<String, Monitor> monitors = new LinkedHashMap<>();
    private final Map<String, String> knownComputers = new HashMap<>();
    private final Set<String> sensors = new HashSet<>();
    private final Set<String> binarySensors = new HashSet<>();
    private final Map<String, Object> lastStates = new HashMap<>();
    private final Set<BoardView> views = Collections.newSetFromMap(new ConcurrentHashMap<BoardView, Boolean>());
    private final Set<String> runs = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private volatile Board board;
    private boolean choicesPublished;
    private boolean ruleActive;
    /** The Show PC sensors action switched to this screensaver until it is dismissed. */
    private boolean shownByAction;
    private long ruleSince;
    private String lastStatus = "";
    private BoardView.Fonts fonts;
    private Map<String, Path> logos;
    private JSONObject brandPaths;

    @Override
    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        this.settings = new HashMap<>(settings);
        config = Config.of(settings);
        worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hwinfo-pc-monitor");
            thread.setDaemon(true);
            return thread;
        });
        board = emptyBoard(config);
        if (!host.isHeadless()) publishScreensaver();
        try {
            host.subscribe("screensaver.state");
        } catch (RuntimeException | LinkageError error) {
            host.log("Screensaver state is unavailable: " + error.getMessage());
        }
        worker.execute(this::apply);
    }

    @Override
    public void configure(Map<String, Object> settings) {
        this.settings = new HashMap<>(settings);
        config = Config.of(settings);
        worker.execute(this::apply);
    }

    @Override
    public void execute(String command, Map<String, Object> arguments) {
        if ("showNow".equals(command)) {
            worker.execute(() -> {
                shownByAction = true;
                command("setScreensaverMode", Collections.<String, Object>singletonMap("mode", MODE));
                command("startScreensaver", Collections.<String, Object>emptyMap());
            });
        }
    }

    @Override
    public void onEvent(String event, Map<String, Object> payload) {
        String run = payload.get("run") instanceof String ? (String) payload.get("run") : "";
        switch (event) {
            case "settings.choices":
                runs.add(run);
                worker.execute(() -> refreshChoices(run));
                break;
            case "settings.button":
                runs.add(run);
                if ("testConnections".equals(payload.get("id"))) worker.execute(() -> testConnections(run));
                else if ("buildLayout".equals(payload.get("id"))) worker.execute(() -> buildLayout(run));
                break;
            case "settings.cancel":
                runs.remove(run);
                break;
            case "ks.screensaver.state":
                if (!Boolean.TRUE.equals(payload.get("active"))) worker.execute(this::actionEnded);
                break;
            default:
                break;
        }
    }

    @Override
    public void stop() {
        ScheduledExecutorService current = worker;
        if (current != null) current.shutdownNow();
        // The host is revoked before stop, so the connections close without it.
        for (Monitor monitor : monitors.values()) monitor.connection.close();
        monitors.clear();
        views.clear();
    }

    // Worker thread from here on.

    private void apply() {
        Config current = config;
        Map<String, Monitor> next = new LinkedHashMap<>();
        for (Config.Computer computer : current.computers) {
            Monitor existing = monitors.remove(computer.endpoint());
            if (existing != null && existing.computer.name.equals(computer.name)) {
                next.put(computer.endpoint(), existing);
            } else {
                if (existing != null) existing.connection.close();
                next.put(computer.endpoint(), new Monitor(computer));
            }
        }
        for (Monitor gone : monitors.values()) gone.connection.close();
        monitors.clear();
        monitors.putAll(next);
        if (polling == null || pollingSeconds != current.intervalSeconds) {
            if (polling != null) polling.cancel(false);
            pollingSeconds = current.intervalSeconds;
            polling = worker.scheduleWithFixedDelay(this::tick, 0, pollingSeconds, TimeUnit.SECONDS);
        } else {
            publish(current);
        }
    }

    private void tick() {
        try {
            poll(false);
            publish(config);
        } catch (RuntimeException error) {
            // A revoked host after stop, or a bad reading. The next tick tries again.
            host.log("Poll failed: " + error);
        }
    }

    private void poll(boolean now) {
        long time = System.currentTimeMillis();
        for (Monitor monitor : monitors.values()) {
            if (!now && monitor.snapshot == null && time < monitor.retryAt) continue;
            try {
                monitor.snapshot = monitor.connection.poll();
                monitor.error = "";
                knownComputers.put(monitor.key(), monitor.computer.name);
            } catch (IOException error) {
                monitor.snapshot = null;
                monitor.error = message(error);
                monitor.retryAt = time + Math.max(RETRY_MS, pollingSeconds * 1000L);
            }
        }
    }

    private void publish(Config current) {
        boolean catalogChanged = false;
        for (Monitor monitor : monitors.values()) {
            String catalog = monitor.snapshot == null ? monitor.catalog : monitor.key() + monitor.snapshot.readings.size() + ":" + monitor.snapshot.devices.size();
            if (!catalog.equals(monitor.catalog)) {
                monitor.catalog = catalog;
                catalogChanged = true;
            }
        }
        if (catalogChanged || !choicesPublished) publishChoices();
        publishEntities(current);
        board = buildBoard(current);
        for (BoardView view : views) view.show(board);
        evaluateRule(current);
        publishStatus();
    }

    /** "CAPRICA/f0000501.0.1000000" to the reading it names, or null while that computer is offline. */
    private HwinfoSnapshot.Reading resolve(String id) {
        int slash = id.indexOf('/');
        if (slash <= 0) return null;
        String computer = id.substring(0, slash);
        for (Monitor monitor : monitors.values()) {
            if (monitor.snapshot != null && monitor.key().equals(computer)) return monitor.snapshot.byKey.get(id.substring(slash + 1));
        }
        return null;
    }

    private String computerName(String id) {
        int slash = id.indexOf('/');
        String key = slash > 0 ? id.substring(0, slash) : id;
        String name = knownComputers.get(key);
        return name == null ? key : name;
    }

    static String id(Monitor monitor, HwinfoSnapshot.Reading reading) {
        return monitor.key() + "/" + reading.key;
    }

    // Settings choices.

    private void publishChoices() {
        List<Map<String, Object>> choices = new ArrayList<>();
        for (Monitor monitor : monitors.values()) {
            if (monitor.snapshot == null) continue;
            for (HwinfoSnapshot.Reading reading : monitor.snapshot.readings) {
                if (choices.size() >= MAX_CHOICES) break;
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("id", id(monitor, reading));
                choice.put("label", clip(reading.label, 120));
                choice.put("group", clip(monitor.computer.name + " · " + reading.device.name, 120));
                choice.put("value", clip(Units.describe(reading.value, reading.unit), 40));
                choice.put("category", Units.category(reading.type, reading.unit));
                choice.put("icon", Units.icon(reading.type, reading.unit));
                choices.add(choice);
            }
        }
        // Nothing to offer yet. Keep whatever KS remembers from the last session instead of an empty list.
        if (choices.isEmpty()) return;
        try {
            for (String field : READING_FIELDS) host.setSettingChoices(field, choices);
            choicesPublished = true;
        } catch (UnsupportedOperationException | LinkageError error) {
            choicesPublished = true;
            host.status("Update Kiosk Satellite to pick sensors from a list.", true);
        }
    }

    private void refreshChoices(String run) {
        if (monitors.isEmpty()) {
            report(run, "error", "Add a computer under Computers first.");
            return;
        }
        report(run, "running", "Reading sensors…");
        poll(true);
        int readings = 0, online = 0;
        for (Monitor monitor : monitors.values()) {
            if (monitor.snapshot == null) continue;
            online++;
            readings += monitor.snapshot.readings.size();
        }
        choicesPublished = false;
        publish(config);
        if (online == 0) report(run, "error", "No computer answered. " + errors());
        else report(run, "success", "Found " + readings + " sensors on " + online + (online == 1 ? " computer." : " computers."));
    }

    // Settings buttons.

    private void testConnections(String run) {
        if (monitors.isEmpty()) {
            report(run, "error", "Add a computer first.");
            return;
        }
        StringBuilder result = new StringBuilder();
        boolean any = false;
        for (Monitor monitor : monitors.values()) {
            if (!report(run, "running", "Connecting to " + monitor.computer.name + "…")) return;
            try {
                monitor.snapshot = monitor.connection.poll();
                monitor.error = "";
                any = true;
                knownComputers.put(monitor.key(), monitor.computer.name);
                append(result, monitor.computer.name + ": " + monitor.snapshot.version + " on " + monitor.snapshot.host
                        + ", " + monitor.snapshot.readings.size() + " sensors.");
            } catch (IOException error) {
                monitor.snapshot = null;
                monitor.error = message(error);
                append(result, monitor.computer.name + ": " + monitor.error);
            }
        }
        publish(config);
        report(run, any ? "success" : "error", result.toString());
    }

    private void buildLayout(String run) {
        report(run, "running", "Reading sensors…");
        poll(true);
        for (Monitor monitor : monitors.values()) {
            if (monitor.snapshot == null) continue;
            List<Map<String, Object>> panels = StarterLayout.build(monitor.snapshot, reading -> id(monitor, reading));
            if (panels.isEmpty()) continue;
            try {
                host.updateSettings(Collections.<String, Object>singletonMap("panels", panels));
            } catch (RuntimeException | LinkageError error) {
                report(run, "error", "The layout could not be saved: " + error.getMessage());
                return;
            }
            Map<String, Object> next = new HashMap<>(settings);
            next.put("panels", panels);
            settings = next;
            config = Config.of(next);
            publish(config);
            report(run, "success", "Added " + panels.size() + (panels.size() == 1 ? " panel" : " panels") + " from " + monitor.computer.name
                    + ". Open Screensaver panels to change them.");
            return;
        }
        report(run, "error", monitors.isEmpty() ? "Add a computer first." : "No computer answered. " + errors());
    }

    private boolean report(String run, String state, String message) {
        if (!runs.contains(run)) return false;
        boolean live;
        try {
            live = host.setupResult(run, state, clip(message, 1000));
        } catch (RuntimeException | LinkageError error) {
            live = false;
        }
        if (!live || !"running".equals(state)) runs.remove(run);
        return live;
    }

    // Home Assistant entities.

    private void publishEntities(Config current) {
        Set<String> wantedSensors = new HashSet<>();
        Set<String> wantedBinary = new HashSet<>();
        for (Monitor monitor : monitors.values()) {
            String key = "pc_" + hash(monitor.computer.endpoint());
            wantedBinary.add(key);
            boolean online = monitor.snapshot != null;
            if (!binarySensors.contains(key) || !Boolean.valueOf(online).equals(lastStates.get(key))) {
                host.publishBinarySensor(key, clip(monitor.computer.name + " online", 80), "connectivity", online);
                binarySensors.add(key);
                lastStates.put(key, online);
            }
        }
        for (Config.Export export : current.exports) {
            HwinfoSnapshot.Reading reading = resolve(export.reading);
            String key = "r" + hash(export.reading);
            boolean binary = reading != null ? Units.isBinary(reading.unit) : binarySensors.contains(key);
            (binary ? wantedBinary : wantedSensors).add(key);
            // Waits for the first reading, so the entity starts with its real name and unit.
            if (reading == null && !sensors.contains(key) && !binarySensors.contains(key)) continue;
            String name = clip(export.name.isEmpty() ? computerName(export.reading) + " " + (reading == null ? "sensor" : reading.label) : export.name, 80);
            if (binary) {
                Boolean state = reading == null ? null : reading.value != 0;
                if (!binarySensors.contains(key) || !equal(state, lastStates.get(key))) {
                    host.publishBinarySensor(key, name, "", state);
                    binarySensors.add(key);
                    lastStates.put(key, state);
                }
            } else {
                Double state = reading == null || Double.isNaN(reading.value) || Double.isInfinite(reading.value) ? null : reading.value;
                String identity = name + "|" + (reading == null ? "" : reading.unit);
                if (!sensors.contains(key) || !equal(state, lastStates.get(key)) || !identity.equals(lastStates.get(key + "#"))) {
                    Map<String, Object> metadata = reading == null ? Units.metadata("other", "") : Units.metadata(reading.type, reading.unit);
                    if (reading == null && lastStates.get(key + "#") instanceof String) {
                        // Offline: keep the name and unit Home Assistant already knows.
                        String known = (String) lastStates.get(key + "#");
                        name = known.substring(0, known.lastIndexOf('|'));
                        metadata = Units.metadata("other", known.substring(known.lastIndexOf('|') + 1));
                        identity = known;
                    }
                    host.publishSensor(key, name, metadata, state);
                    sensors.add(key);
                    lastStates.put(key, state);
                    lastStates.put(key + "#", identity);
                }
            }
        }
        if (current.autoSwitch) {
            wantedBinary.add("rule_active");
            if (!binarySensors.contains("rule_active") || !Boolean.valueOf(ruleActive).equals(lastStates.get("rule_active"))) {
                host.publishBinarySensor("rule_active", "Sensor screensaver active", "", ruleActive);
                binarySensors.add("rule_active");
                lastStates.put("rule_active", ruleActive);
            }
        }
        for (String key : new ArrayList<>(sensors)) {
            if (wantedSensors.contains(key)) continue;
            host.removeSensor(key);
            sensors.remove(key);
            lastStates.remove(key);
            lastStates.remove(key + "#");
        }
        for (String key : new ArrayList<>(binarySensors)) {
            if (wantedBinary.contains(key)) continue;
            host.removeBinarySensor(key);
            binarySensors.remove(key);
            lastStates.remove(key);
        }
    }

    // The screensaver.

    private Board emptyBoard(Config current) {
        Board.Value[][] tiles = new Board.Value[current.panels.size()][];
        for (int i = 0; i < tiles.length; i++) tiles[i] = new Board.Value[current.panels.get(i).tiles.size()];
        return new Board(current.look, current.panels, tiles, new Board.Value[current.panels.size()]);
    }

    private Board buildBoard(Config current) {
        Board empty = emptyBoard(current);
        for (int p = 0; p < current.panels.size(); p++) {
            Config.Panel panel = current.panels.get(p);
            for (int t = 0; t < panel.tiles.size(); t++) {
                Config.Tile tile = panel.tiles.get(t);
                HwinfoSnapshot.Reading reading = resolve(tile.reading);
                if (reading != null) empty.tiles[p][t] = new Board.Value(reading.pick(tile.statistic), reading.unit);
            }
            if (!panel.headerReading.isEmpty()) {
                HwinfoSnapshot.Reading reading = resolve(panel.headerReading);
                if (reading != null) empty.headers[p] = new Board.Value(reading.value, reading.unit);
            }
        }
        return empty;
    }

    private void publishScreensaver() {
        try {
            host.publishScreensaverView(SCREENSAVER, "PC sensors", new OverlayFactory() {
                @Override
                public View create(Context context, KsTheme theme) {
                    BoardView view = new BoardView(context, theme, fonts(theme), HwinfoPlugin.this::logo);
                    views.add(view);
                    view.show(board);
                    return view;
                }

                @Override
                public void onDestroy(View view) {
                    views.remove(view);
                }
            });
        } catch (UnsupportedOperationException | LinkageError error) {
            host.status("Update Kiosk Satellite to show the PC sensors screensaver.", true);
        }
    }

    /** Main thread. */
    private BoardView.Fonts fonts(KsTheme theme) {
        if (fonts == null) {
            fonts = new BoardView.Fonts(font("fonts/BarlowSemiCondensed-Medium.ttf", theme, 500),
                    font("fonts/BarlowSemiCondensed-SemiBold.ttf", theme, 600), font("fonts/BarlowSemiCondensed-Bold.ttf", theme, 700));
        }
        return fonts;
    }

    private Typeface font(String asset, KsTheme theme, int weight) {
        try {
            return Typeface.createFromFile(host.assetPath(asset));
        } catch (RuntimeException | LinkageError error) {
            return theme.typeface(weight);
        }
    }

    /** Main thread. */
    private Path logo(String name) {
        String key = StarterLayout.logoPath(name);
        if (key == null) return null;
        if (logos == null) logos = new HashMap<>();
        if (logos.containsKey(key)) return logos.get(key);
        Path path = null;
        try {
            if (brandPaths == null) brandPaths = new JSONObject(readAsset("brands.json"));
            String data = brandPaths.optString(key, "");
            if (!data.isEmpty()) path = SvgPath.parse(data);
        } catch (Exception | LinkageError error) {
            path = null;
        }
        logos.put(key, path);
        return path;
    }

    private String readAsset(String name) throws IOException {
        try (InputStream in = host.openAsset(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }

    // Switching screensavers.

    private void evaluateRule(Config current) {
        if (!current.autoSwitch || current.ruleReading.isEmpty()) {
            if (ruleActive) setRule(false, current);
            ruleSince = 0;
            return;
        }
        HwinfoSnapshot.Reading reading = resolve(current.ruleReading);
        boolean met = reading != null && !Double.isNaN(current.ruleValue) && (current.ruleAbove ? reading.value > current.ruleValue : reading.value < current.ruleValue);
        long now = System.currentTimeMillis();
        // Counts how long the reading has been on the other side of the limit from where the rule stands.
        if (met != ruleActive) {
            if (ruleSince == 0) ruleSince = now;
            long hold = (ruleActive ? current.ruleReleaseSeconds : current.ruleForSeconds) * 1000L;
            if (now - ruleSince >= hold) {
                ruleSince = 0;
                setRule(met, current);
            }
        } else {
            ruleSince = 0;
        }
    }

    private void setRule(boolean active, Config current) {
        ruleActive = active;
        if (active) {
            command("setScreensaverMode", Collections.<String, Object>singletonMap("mode", MODE));
            if (current.ruleStart) command("startScreensaver", Collections.<String, Object>emptyMap());
        } else if (!shownByAction) {
            command("clearScreensaverMode", Collections.<String, Object>emptyMap());
        }
        publishEntities(current);
    }

    /** The screensaver the action started was dismissed: hand the choice back unless the rule holds it. */
    private void actionEnded() {
        if (!shownByAction) return;
        shownByAction = false;
        if (!ruleActive) command("clearScreensaverMode", Collections.<String, Object>emptyMap());
    }

    private void command(String name, Map<String, Object> arguments) {
        try {
            host.executeCommand(name, arguments, (ok, data, error) -> {
                if (!ok) host.log(name + " failed: " + error);
            });
        } catch (RuntimeException | LinkageError error) {
            host.log(name + " failed: " + error.getMessage());
        }
    }

    // Status.

    private void publishStatus() {
        StringBuilder text = new StringBuilder();
        int online = 0;
        for (Monitor monitor : monitors.values()) {
            if (monitor.snapshot != null) {
                online++;
                append(text, monitor.computer.name + ": " + monitor.snapshot.readings.size() + " sensors from " + monitor.snapshot.version + ".");
            } else if (!monitor.error.isEmpty()) {
                append(text, monitor.computer.name + ": " + monitor.error);
            } else {
                append(text, monitor.computer.name + ": connecting…");
            }
        }
        if (monitors.isEmpty()) text.append("Add the PCs that run HWiNFO under Computers.");
        Config current = config;
        if (current.autoSwitch && Double.isNaN(current.ruleValue)) append(text, "Set a limit for the automatic screensaver.");
        if (ruleActive) append(text, "Showing the PC sensors screensaver while the rule holds.");
        String status = text.toString();
        boolean error = !monitors.isEmpty() && online == 0 && status.indexOf("connecting") < 0;
        if (!status.equals(lastStatus)) {
            lastStatus = status;
            host.status(clip(status, 1000), error);
            try {
                String level = monitors.isEmpty() ? "" : online == monitors.size() ? "on" : online > 0 ? "warn" : "off";
                String tile = monitors.isEmpty() ? "No computers" : online + " of " + monitors.size() + (monitors.size() == 1 ? " PC online" : " PCs online");
                host.publishStatusTile("pcs", "PC monitor", level, tile);
            } catch (UnsupportedOperationException | LinkageError ignored) {
                // Older Kiosk Satellite without status tiles.
            }
        }
    }

    private String errors() {
        StringBuilder text = new StringBuilder();
        for (Monitor monitor : monitors.values()) {
            if (!monitor.error.isEmpty()) append(text, monitor.computer.name + ": " + monitor.error);
        }
        return text.toString();
    }

    private static String message(IOException error) {
        String text = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (error instanceof java.net.ConnectException) return "Connection refused. Check that HWiNFO runs with Remote Sensor Monitoring on.";
        if (error instanceof java.net.SocketTimeoutException) return "No answer. Check the address and the PC's firewall for port 27007.";
        if (error instanceof java.net.UnknownHostException) return "Unknown address.";
        if (error instanceof java.io.EOFException) return "HWiNFO closed the connection.";
        return text;
    }

    private static void append(StringBuilder text, String line) {
        if (text.length() > 0) text.append('\n');
        text.append(line);
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    static String hash(String text) {
        CRC32 crc = new CRC32();
        crc.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
