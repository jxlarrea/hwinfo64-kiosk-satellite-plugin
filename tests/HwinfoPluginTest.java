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
        pluginPublishesReadingsChoicesAndSwitches();
        System.out.println("HwinfoPluginTest passed");
    }

    // A pretend HWiNFO: two devices and four readings, in the version 2 layout real servers send.

    static double fps = 120;

    static byte[] sensors() {
        int sensorSize = 392, readingSize = 460, devices = 2, readings = 4;
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
        reading(memory, at + readingSize, 8, 1, 0x08000000L, "Framerate Presented (avg)", "FPS", fps);
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
        check(snapshot.readings.size() == 4, "four readings");
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
        settings.put("exports", Arrays.asList(export, flag));
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
            Map<String, Object> first = host.choices.get("panels.tiles.reading").get(0);
            check(first.get("id").equals("TESTPC/f0000501.0.1000000") && first.get("group").equals("Gaming PC · CPU [#0]: AMD Ryzen 9 9950X3D: Enhanced"), "choice id and group");
            check("mdi:thermometer".equals(first.get("icon")), "choice icon by reading type");
            check("Temperature".equals(first.get("category")) && "48\u00b0C".equals(first.get("value")), "choice category chip and value");
            check(host.screensaver != null, "native screensaver registered");

            waitFor(() -> host.commands.contains("setScreensaverMode:" + HwinfoPlugin.MODE), "the rule switching to PC sensors");
            fps = 30;
            waitFor(() -> host.commands.contains("clearScreensaverMode"), "the rule handing the screensaver back");
            check(Boolean.FALSE.equals(host.binary.get("rule_active")), "rule sensor off again");

            Map<String, Object> run = new HashMap<>();
            run.put("id", "testConnections");
            run.put("run", "r1");
            plugin.onEvent("settings.button", run);
            waitFor(() -> host.results.contains("r1:success"), "the connection test");
            plugin.stop();
        }
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

        @Override public void showWindow(String title, String message, String buttonLabel) {}
        @Override public void hideWindow() {}
        @Override public void log(String message) {}
        @Override public void status(String message, boolean error) {}
        @Override public void subscribe(String event) {}
        @Override public void publishStatusTile(String key, String title, String level, String text) {}
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
