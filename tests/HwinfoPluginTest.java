package me.jxl.kiosk.plugins.hwinfo;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.PluginHost;

/** Runs the plugin against a pretend HWiNFO server on localhost and a recording host. */
public final class HwinfoPluginTest {
    public static void main(String[] args) throws Exception {
        protocolParsesSharedMemory();
        protocolRejectsOtherServers();
        unitsReadWell();
        configReadsRows();
        starterLayoutFindsReadings();
        globsMatchWholeNames();
        pluginPublishesReadingsChoicesAndSwitches();
        pickersStayOffWithoutAnAnsweringComputer();
        System.out.println("HwinfoPluginTest passed");
    }

    // A pretend HWiNFO: two devices and six readings, in the version 2 layout real servers send.

    static double fps = 120;

    static byte[] sensors() {
        int sensorSize = 392, readingSize = 460, devices = 2, readings = 6;
        int sensorOffset = 48, readingOffset = sensorOffset + sensorSize * devices;
        ByteBuffer memory = ByteBuffer.allocate(readingOffset + readingSize * readings).order(ByteOrder.LITTLE_ENDIAN);
        memory.put("HWiS".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(1).putLong(0)
                .putInt(sensorOffset).putInt(sensorSize).putInt(devices).putInt(readingOffset).putInt(readingSize).putInt(readings).putInt(2000);
        device(memory, sensorOffset, 0xf0000501L, 0, "CPU [#0]: AMD Ryzen 9 9950X3D: Enhanced");
        device(memory, sensorOffset + sensorSize, 0xf0000ff0L, 0, "PresentMon [game.exe]");
        int at = readingOffset;
        reading(memory, at, 1, 0, 0x01000000L, "CPU (Tctl/Tdie)", "°C", 47.625);
        reading(memory, at += readingSize, 5, 0, 0x05000000L, "CPU Package Power", "W", 79.9);
        reading(memory, at += readingSize, 8, 0, 0x08000000L, "PMIC High Temperature", "Yes/No", 0);
        reading(memory, at += readingSize, 8, 1, 0x08000000L, "Framerate Presented (avg)", "FPS", fps);
        reading(memory, at += readingSize, 6, 0, 0x06000000L, "Core 0 Clock", "MHz", 4000);
        reading(memory, at + readingSize, 6, 0, 0x06000001L, "Core 1 Clock", "MHz", 5200);
        byte[] shared = memory.array();
        ByteBuffer body = ByteBuffer.allocate(12 + shared.length).order(ByteOrder.LITTLE_ENDIAN);
        body.put("PRWH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(shared.length).put(shared);
        return body.array();
    }

    static void device(ByteBuffer memory, int at, long id, int instance, String name) {
        memory.putInt(at, (int) id).putInt(at + 4, instance);
        put(memory, at + 8, name, StandardCharsets.ISO_8859_1);
        put(memory, at + 136, name, StandardCharsets.ISO_8859_1);
        put(memory, at + 264, name, StandardCharsets.UTF_8);
    }

    static void reading(ByteBuffer memory, int at, int type, int device, long id, String label, String unit, double value) {
        memory.putInt(at, type).putInt(at + 4, device).putInt(at + 8, (int) id);
        put(memory, at + 12, label, StandardCharsets.ISO_8859_1);
        put(memory, at + 140, label, StandardCharsets.ISO_8859_1);
        put(memory, at + 268, unit, StandardCharsets.ISO_8859_1);
        memory.putDouble(at + 284, value).putDouble(at + 292, value - 1).putDouble(at + 300, value + 1).putDouble(at + 308, value);
        put(memory, at + 316, label, StandardCharsets.UTF_8);
        put(memory, at + 444, unit, StandardCharsets.UTF_8);
    }

    static void put(ByteBuffer memory, int at, String text, java.nio.charset.Charset charset) {
        byte[] bytes = text.getBytes(charset);
        for (int i = 0; i < bytes.length; i++) memory.put(at + i, bytes[i]);
    }

    static byte[] info() {
        ByteBuffer body = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        body.put("PRWH".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        put(body, 8, "TESTPC", StandardCharsets.ISO_8859_1);
        put(body, 40, "HWiNFO 64 Pro v8.54", StandardCharsets.ISO_8859_1);
        return body.array();
    }

    static ServerSocket server() throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    OutputStream out = socket.getOutputStream();
                    byte[] request = new byte[128];
                    while (true) {
                        in.readFully(request);
                        int command = ByteBuffer.wrap(request).order(ByteOrder.LITTLE_ENDIAN).getInt(4);
                        byte[] body = command == 1 ? info() : sensors();
                        ByteBuffer header = ByteBuffer.allocate(132).order(ByteOrder.LITTLE_ENDIAN);
                        header.put("RRWH".getBytes(StandardCharsets.US_ASCII)).putInt(command).putInt(0).putInt(body.length);
                        out.write(header.array());
                        out.write(body);
                        out.flush();
                    }
                } catch (IOException closed) {
                    // The client left. Wait for the next one.
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server;
    }

    static void protocolParsesSharedMemory() throws IOException {
        HwinfoSnapshot snapshot = HwinfoProtocol.parseSensors(sensors(), "TESTPC", "v8");
        check(snapshot.devices.size() == 2, "two devices");
        check(snapshot.readings.size() == 6, "six readings");
        HwinfoSnapshot.Reading temperature = snapshot.byKey.get("f0000501.0.1000000");
        check(temperature != null && temperature.label.equals("CPU (Tctl/Tdie)"), "reading keyed by device and reading ID");
        check(temperature.unit.equals("°C") && temperature.type.equals("temperature"), "unit and type");
        check(temperature.pick("max") == 48.625, "maximum");
        check(snapshot.pollingMillis == 2000, "polling period");
        String[] info = HwinfoProtocol.parseInfo(info());
        check(info[0].equals("TESTPC") && info[1].startsWith("HWiNFO"), "computer name and version");
    }

    static void protocolRejectsOtherServers() {
        byte[] header = new byte[132];
        try {
            HwinfoProtocol.bodyLength(header, 2);
            throw new AssertionError("accepted a header without RRWH");
        } catch (IOException expected) {
            // Not HWiNFO.
        }
        byte[] body = sensors();
        try {
            HwinfoProtocol.parseSensors(Arrays.copyOf(body, body.length - 100), "", "");
            throw new AssertionError("accepted cut short data");
        } catch (IOException expected) {
            // Cut short.
        }
    }

    static void unitsReadWell() {
        java.util.Locale.setDefault(java.util.Locale.US);
        check(Units.number(2234.4, "MHz", -1).equals("2,234"), "groups thousands");
        check(Units.number(1.391, "V", -1).equals("1.39"), "volts take two decimals");
        check(Units.number(30.24, "°C", 1).equals("30.2"), "chosen decimals win");
        check(Units.number(-0.2, "W", -1).equals("0"), "no negative zero");
        check(Units.describe(47.6, "°C").equals("48°C"), "degrees sit on the number");
        check(Units.describe(148.4, "l/h").equals("148.4 l/h"), "other units take a space");
        check(Units.metadata("temperature", "°C").get("deviceClass").equals("temperature"), "temperature class");
        check(Units.metadata("other", "FPS").get("deviceClass").equals(""), "no class for frame rates");
    }

    static void configReadsRows() {
        Map<String, Object> settings = settings(0);
        Config config = Config.of(settings);
        check(config.computers.size() == 1 && config.computers.get(0).name.equals("Gaming PC"), "computer row");
        check(config.panels.get(0).columns == 3 && config.panels.get(0).tiles.get(0).decimals == -1, "panel defaults");
        check(Double.isNaN(config.panels.get(0).tiles.get(0).alertAbove), "no limit unless highlighted");
        check("Dark".equals(config.look.theme) && "Auto".equals(Config.of(new HashMap<String, Object>()).look.theme), "theme with Auto as the fallback");
        check(config.look.label == 0xFFFFC933 && config.look.header == 0xFFE8EAED, "label color set, header color at its default");
        check(config.look.valueScale == 1.5f && config.look.labelScale == 0.8f && config.look.scale == 1f, "text, value and label sizes apart");
        check(!config.look.custom && config.look.accent == 0xFFFFC933, "theme colors unless custom, with the saved accent kept");
        check(config.ruleValue == 60 && Config.decimal("1,5", 0) == 1.5 && Double.isNaN(Config.decimal("sixty", Double.NaN)), "typed limits");
        check(config.readings().contains("TESTPC/f0000ff0.0.8000000"), "rule reading counts as used");
    }

    static void starterLayoutFindsReadings() throws IOException {
        HwinfoSnapshot snapshot = HwinfoProtocol.parseSensors(sensors(), "TESTPC", "v8");
        List<Map<String, Object>> panels = StarterLayout.build(snapshot, reading -> "TESTPC/" + reading.key);
        check(panels.size() == 2, "CPU and graphics panels");
        Map<String, Object> cpu = panels.get(0);
        check(cpu.get("footerText").equals("AMD Ryzen 9 9950X3D") && cpu.get("footerLogo").equals("AMD"), "CPU model and logo");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tiles = (List<Map<String, Object>>) cpu.get("tiles");
        check(tiles.get(0).get("reading").equals("TESTPC/f0000501.0.1000000"), "temperature first");
    }

    static void globsMatchWholeNames() {
        check(HwinfoPlugin.glob("Core * Effective Clock").matcher("Core 12 T1 Effective Clock").matches(), "a star stands for any text");
        check(!HwinfoPlugin.glob("Core * Effective Clock").matcher("Average Effective Clock").matches(), "the rest of the name must match");
        check(HwinfoPlugin.glob("ups load").matcher("UPS Load").matches(), "case does not matter");
        check(HwinfoPlugin.glob("*Clock").matcher("GPU Clock").matches() && HwinfoPlugin.glob("GPU*").matcher("GPU Clock").matches(), "stars at either end");
        check(HwinfoPlugin.glob("Load (W)").matcher("Load (W)").matches(), "other characters are plain text");
        // The two CCDs of a 16 core Ryzen, two threads per core.
        java.util.regex.Pattern ccd0 = HwinfoPlugin.glob("Core {0-7} T* Utility"), ccd1 = HwinfoPlugin.glob("Core {8-15} T* Utility");
        int first = 0, second = 0;
        for (int core = 0; core < 16; core++) {
            for (int thread = 0; thread < 2; thread++) {
                String name = "Core " + core + " T" + thread + " Utility";
                check(ccd0.matcher(name).matches() != ccd1.matcher(name).matches(), "each thread in one CCD: " + name);
                if (ccd0.matcher(name).matches()) first++; else second++;
            }
        }
        check(first == 16 && second == 16, "16 threads per CCD");
        check(!ccd1.matcher("Core 1 T0 Utility").matches() && !ccd1.matcher("Core 16 T0 Utility").matches(), "only the numbers in the range");
        check(HwinfoPlugin.glob("Core {0,2,4} Clock").matcher("Core 2 Clock").matches() && !HwinfoPlugin.glob("Core {0,2,4} Clock").matcher("Core 3 Clock").matches(), "a list of numbers");
        check(HwinfoPlugin.glob("Fan {a}").matcher("Fan {a}").matches() && HwinfoPlugin.glob("Fan {9-2}").matcher("Fan {9-2}").matches(), "other braces are plain text");
        check(HwinfoPlugin.apply("Average", new double[] {2, 4}) == 3 && HwinfoPlugin.apply("Minimum", new double[] {2, 4}) == 2, "functions");
    }

    static Map<String, Object> settings(int port) {
        Map<String, Object> settings = new HashMap<>();
        Map<String, Object> computer = new LinkedHashMap<>();
        computer.put("name", "Gaming PC");
        computer.put("address", "127.0.0.1:" + port);
        settings.put("computers", Collections.singletonList(computer));
        settings.put("interval", 1.0);
        Map<String, Object> export = new LinkedHashMap<>();
        export.put("reading", "TESTPC/f0000501.0.1000000");
        export.put("name", "");
        Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("reading", "TESTPC/f0000501.0.8000000");
        flag.put("name", "PMIC alarm");
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("reading", "combined/Max clock");
        merged.put("name", "");
        settings.put("exports", Arrays.asList(export, flag, merged));
        // The highest core clock by name, and a sum of picked sensors that leaves the temperature out for its unit.
        Map<String, Object> highest = new LinkedHashMap<>();
        highest.put("name", "Max clock");
        highest.put("function", "Maximum");
        highest.put("source", "Match a name");
        highest.put("sensors", Collections.emptyList());
        highest.put("computer", "TESTPC");
        highest.put("pattern", "Core * Clock");
        highest.put("unit", "");
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("name", "Total clock");
        total.put("function", "Sum");
        total.put("source", "Pick them");
        total.put("sensors", Arrays.asList(Collections.singletonMap("reading", "TESTPC/f0000501.0.6000000"),
                Collections.singletonMap("reading", "TESTPC/f0000501.0.6000001"), Collections.singletonMap("reading", "TESTPC/f0000501.0.1000000")));
        total.put("computer", "");
        total.put("pattern", "");
        total.put("unit", "");
        settings.put("combined", Arrays.asList(highest, total));
        Map<String, Object> tile = new LinkedHashMap<>();
        tile.put("reading", "TESTPC/f0000501.0.1000000");
        tile.put("label", "Temperature");
        tile.put("statistic", "Current");
        tile.put("decimals", "Auto");
        tile.put("showUnit", true);
        tile.put("highlight", false);
        tile.put("alertAbove", 90.0);
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("title", "CPU");
        panel.put("icon", "mdi:cpu-64-bit");
        panel.put("columns", "3");
        panel.put("stack", false);
        panel.put("tiles", Collections.singletonList(tile));
        panel.put("headerReading", "");
        panel.put("headerLabel", "");
        panel.put("footerText", "");
        panel.put("footerLogo", "None");
        panel.put("footerIcon", "");
        settings.put("panels", Collections.singletonList(panel));
        settings.put("accentColor", "#FFC933");
        settings.put("panelColor", "#262B34");
        settings.put("backgroundColor", "#000000");
        settings.put("theme", "Dark");
        settings.put("labelColor", "#FFC933");
        settings.put("textSize", 100.0);
        settings.put("valueSize", 150.0);
        settings.put("labelSize", 80.0);
        settings.put("autoSwitch", true);
        settings.put("ruleReading", "TESTPC/f0000ff0.0.8000000");
        settings.put("ruleDirection", "Above");
        settings.put("ruleValue", 60.0);
        settings.put("ruleFor", 0.0);
        settings.put("ruleRelease", 0.0);
        settings.put("ruleStart", false);
        return settings;
    }

    static void pluginPublishesReadingsChoicesAndSwitches() throws Exception {
        try (ServerSocket server = server()) {
            Host host = new Host();
            HwinfoPlugin plugin = new HwinfoPlugin();
            plugin.start(host, settings(server.getLocalPort()));
            waitFor(() -> host.sensors.containsKey("r" + HwinfoPlugin.hash("TESTPC/f0000501.0.1000000")), "the exported temperature");
            String key = "r" + HwinfoPlugin.hash("TESTPC/f0000501.0.1000000");
            check(host.sensors.get(key).equals(47.625), "temperature value");
            check(host.names.get(key).equals("Gaming PC CPU (Tctl/Tdie)"), "default name from the computer and reading");
            check(host.binary.containsKey("r" + HwinfoPlugin.hash("TESTPC/f0000501.0.8000000")), "Yes/No readings become binary sensors");
            check(Boolean.TRUE.equals(host.binary.get("pc_" + HwinfoPlugin.hash("127.0.0.1:" + server.getLocalPort()))), "computer online");
            check(host.choices.keySet().containsAll(Arrays.asList(HwinfoPlugin.READING_FIELDS)), "every reading field gets choices");
            Map<String, Object> first = host.choices.get("panels.tiles.reading").stream().filter(c -> "TESTPC/f0000501.0.1000000".equals(c.get("id"))).findFirst().orElse(Collections.<String, Object>emptyMap());
            check(first.get("id").equals("TESTPC/f0000501.0.1000000") && first.get("group").equals("Gaming PC · CPU [#0]: AMD Ryzen 9 9950X3D: Enhanced"), "choice id and group");
            check("mdi:thermometer".equals(first.get("icon")), "choice icon by reading type");
            check("Temperature".equals(first.get("category")) && "48\u00b0C".equals(first.get("value")), "choice category chip and value");
            check(host.screensaver != null, "native screensaver registered");

            // Combined sensors.
            waitFor(() -> "5,200 MHz".equals(choice(host, "combined/Max clock").get("value")), "the highest core clock");
            check("9,200 MHz".equals(choice(host, "combined/Total clock").get("value")), "picked clocks added up without the temperature");
            check("Combined sensors".equals(choice(host, "combined/Max clock").get("group")), "combined sensors under their own heading");
            check(host.choices.get(HwinfoPlugin.PART_FIELD).stream().noneMatch(c -> String.valueOf(c.get("id")).startsWith("combined/")), "no combined sensor inside another");
            check("TESTPC".equals(host.choices.get(HwinfoPlugin.COMPUTER_FIELD).get(0).get("id")), "computers to match names on");
            String mergedKey = "r" + HwinfoPlugin.hash("combined/Max clock");
            waitFor(() -> Double.valueOf(5200).equals(host.sensors.get(mergedKey)), "the combined sensor in Home Assistant");
            check("Max clock".equals(host.names.get(mergedKey)), "named after the combined sensor");

            waitFor(() -> host.commands.contains("setScreensaverMode:" + HwinfoPlugin.MODE), "the rule switching to PC sensors");
            fps = 30;
            waitFor(() -> host.commands.contains("clearScreensaverMode"), "the rule handing the screensaver back");
            check(Boolean.FALSE.equals(host.binary.get("rule_active")), "rule sensor off again");

            check(host.pickersOff.isEmpty(), "pickers on while the computer answers");

            // The Overview tile goes away with its setting and comes back.
            waitFor(() -> "on|1 of 1 PC online".equals(host.tile), "the Overview tile");
            Map<String, Object> noTile = settings(server.getLocalPort());
            noTile.put("statusTile", false);
            plugin.configure(noTile);
            waitFor(() -> host.tile == null, "the Overview tile removed");
            plugin.configure(settings(server.getLocalPort()));
            waitFor(() -> "on|1 of 1 PC online".equals(host.tile), "the Overview tile back");

            // With a settings page open, the picker values follow the live reading.
            String fpsId = "TESTPC/f0000ff0.0.8000000";
            fps = 75;
            plugin.onEvent("settings.page", Collections.<String, Object>singletonMap("open", true));
            waitFor(() -> "75 FPS".equals(choice(host, fpsId).get("value")), "fresh picker values while the page is open");
            plugin.onEvent("settings.page", Collections.<String, Object>singletonMap("open", false));

            // A renamed computer heads its sensors under the new name.
            Map<String, Object> renamed = settings(server.getLocalPort());
            @SuppressWarnings("unchecked")
            Map<String, Object> computer = (Map<String, Object>) ((List<?>) renamed.get("computers")).get(0);
            computer.put("name", "Office PC");
            plugin.configure(renamed);
            waitFor(() -> String.valueOf(choice(host, fpsId).get("group")).startsWith("Office PC · "), "the new computer name in the pickers");

            Map<String, Object> run = new HashMap<>();
            run.put("id", "testConnections");
            run.put("run", "r1");
            plugin.onEvent("settings.button", run);
            waitFor(() -> host.results.contains("r1:success"), "the connection test");
            plugin.stop();
        }
    }

    static Map<String, Object> choice(Host host, String id) {
        List<Map<String, Object>> list = host.choices.get("ruleReading");
        if (list != null) for (Map<String, Object> choice : list) if (id.equals(choice.get("id"))) return choice;
        return Collections.emptyMap();
    }

    static void pickersStayOffWithoutAnAnsweringComputer() throws Exception {
        java.util.List<String> fields = new ArrayList<>(Arrays.asList(HwinfoPlugin.READING_FIELDS));
        fields.add(HwinfoPlugin.PART_FIELD);
        fields.add(HwinfoPlugin.COMPUTER_FIELD);
        // No computer added.
        Host host = new Host();
        HwinfoPlugin plugin = new HwinfoPlugin();
        Map<String, Object> none = settings(0);
        none.put("computers", Collections.emptyList());
        plugin.start(host, none);
        waitFor(() -> host.pickersOff.containsAll(fields), "the pickers off without a computer");
        plugin.stop();
        // A computer that does not answer: a port nothing listens on.
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        Host other = new Host();
        HwinfoPlugin quiet = new HwinfoPlugin();
        quiet.start(other, settings(closed));
        waitFor(() -> other.pickersOff.containsAll(fields), "the pickers off while no computer answers");
        quiet.stop();
    }

    interface Condition {
        boolean met();
    }

    static void waitFor(Condition condition, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            if (condition.met()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + what);
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static final class Host implements PluginHost {
        final Map<String, Object> sensors = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, Object> binary = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String, List<Map<String, Object>>> choices = new java.util.concurrent.ConcurrentHashMap<>();
        final List<String> commands = new CopyOnWriteArrayList<>();
        final List<String> results = new CopyOnWriteArrayList<>();
        volatile OverlayFactory screensaver;
        volatile String tile;

        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
        @Override public void status(String message, boolean error) {}
        @Override public void subscribe(String event) {}
        @Override public void publishStatusTile(String key, String title, String level, String text) { tile = level + "|" + text; }
        @Override public void removeStatusTile(String key) { tile = null; }
        final java.util.Set<String> pickersOff = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
        @Override public void setSettingChoicesEnabled(String key, boolean enabled) { if (enabled) pickersOff.remove(key); else pickersOff.add(key); }
        @Override public void publishScreensaverView(String key, String title, OverlayFactory factory) { screensaver = factory; }
        @Override public void setSettingChoices(String key, List<Map<String, Object>> list) { choices.put(key, new ArrayList<>(list)); }
        @Override public boolean setupResult(String run, String state, String message) { results.add(run + ":" + state); return true; }

        @Override public void publishSensor(String key, String name, Map<String, Object> metadata, Double state) {
            names.put(key, name);
            if (state == null) sensors.remove(key);
            else sensors.put(key, state);
        }

        @Override public void publishBinarySensor(String key, String name, String deviceClass, Boolean state) {
            names.put(key, name);
            if (state == null) binary.remove(key);
            else binary.put(key, state);
        }

        @Override public void removeSensor(String key) { sensors.remove(key); }
        @Override public void removeBinarySensor(String key) { binary.remove(key); }

        @Override public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            commands.add(arguments.containsKey("mode") ? command + ":" + arguments.get("mode") : command);
            callback.onResult(true, null, null);
        }
    }
}
