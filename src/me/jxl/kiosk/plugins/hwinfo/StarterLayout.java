package me.jxl.kiosk.plugins.hwinfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Builds screensaver panels from the readings one computer reports, so nobody starts from an empty screen. */
final class StarterLayout {
    /** Footer logos by the name the settings show, keyed to the paths in assets/brands.json. */
    static final String[][] LOGOS = {
        {"AMD", "amd"}, {"Intel", "intel"}, {"NVIDIA", "nvidia"}, {"ASUS", "asus"}, {"ROG", "republicofgamers"},
        {"MSI", "msi"}, {"Corsair", "corsair"}, {"NZXT", "nzxt"}, {"Cooler Master", "coolermaster"}, {"EK", "ekwb"}, {"Seasonic", "seasonic"}, {"Razer", "razer"},
        {"Logitech", "logitech"}, {"SteelSeries", "steelseries"}, {"Samsung", "samsung"}, {"Western Digital", "westerndigital"},
        {"Seagate", "seagate"}, {"Lenovo", "lenovo"}, {"Dell", "dell"}, {"HP", "hp"}, {"Apple", "apple"}, {"Linux", "linux"},
        {"Ubuntu", "ubuntu"}, {"Steam", "steam"}, {"PlayStation", "playstation"}, {"Epic Games", "epicgames"},
    };

    interface Ids {
        String id(HwinfoSnapshot.Reading reading);
    }

    private StarterLayout() {}

    static String logoPath(String name) {
        for (String[] logo : LOGOS) if (logo[0].equals(name)) return logo[1];
        return null;
    }

    static List<Map<String, Object>> build(HwinfoSnapshot snapshot, Ids ids) {
        List<Map<String, Object>> panels = new ArrayList<>();
        HwinfoSnapshot.Device cpu = device(snapshot, "^CPU \\[#0\\]");
        HwinfoSnapshot.Device gpu = device(snapshot, "^dGPU \\[#0\\]");
        if (gpu == null) gpu = device(snapshot, "^(GPU|iGPU) \\[#0\\]");

        List<Map<String, Object>> tiles = new ArrayList<>();
        tile(tiles, snapshot, ids, "^CPU", "^(CPU \\(Tctl/Tdie\\)|CPU Package|CPU \\(Tdie\\)|Core Max)$", "Temperature", "Auto");
        tile(tiles, snapshot, ids, "^CPU", "^(Core Clocks \\(avg\\)|Average Effective Clock)$", "Clock", "Auto");
        tile(tiles, snapshot, ids, "^CPU", "^Total CPU Usage$", "Load", "Auto");
        tile(tiles, snapshot, ids, "^CPU", "^(Core VIDs \\(avg\\)|Core 0 VID|CPU Core Voltage.*|Vcore)$", "Voltage", "Auto");
        tile(tiles, snapshot, ids, "^CPU", "^(CPU Package Power|CPU PPT)$", "Power", "Auto");
        tile(tiles, snapshot, ids, "", "^Physical Memory Load$", "Memory", "Auto");
        int cpuAt = panels.size() - 1;
        if (!tiles.isEmpty()) {
            cpuAt = panels.size();
            panels.add(panel("CPU & RAM", "mdi:cpu-64-bit", false, model(cpu), brand(cpu), tiles));
        }

        tiles = new ArrayList<>();
        tile(tiles, snapshot, ids, "GPU", "^GPU Temperature$", "Core temp", "Auto");
        tile(tiles, snapshot, ids, "GPU", "^(GPU Memory Junction Temperature|GPU Hot Spot Temperature)$", "Mem temp", "Auto");
        tile(tiles, snapshot, ids, "GPU", "^(GPU Core Load|GPU Utilization|GPU D3D Usage)$", "Load", "Auto");
        tile(tiles, snapshot, ids, "GPU", "^(GPU Clock|GPU Effective Clock)$", "Core clock", "Auto");
        tile(tiles, snapshot, ids, "GPU", "^GPU Memory Clock$", "Mem clock", "Auto");
        tile(tiles, snapshot, ids, "", "^(Framerate Presented \\(avg\\)|Framerate \\(avg\\)|Framerate)$", "FPS", "0");
        tile(tiles, snapshot, ids, "GPU", "^(GPU Power|Total GPU Power)$", "Power", "Auto");
        if (!tiles.isEmpty()) {
            panels.add(panel("Graphics card", "mdi:gamepad-variant", false, model(gpu), brand(gpu), tiles));
        }

        tiles = new ArrayList<>();
        tile(tiles, snapshot, ids, "", "^Water Temperature$", "Water temp", "1");
        tile(tiles, snapshot, ids, "", "^(Pump|Pump Speed|AIO Pump)$", "Pump RPM", "Auto", "fan");
        tile(tiles, snapshot, ids, "", "^(Water Flow|Flow)$", "Flow", "Auto");
        tile(tiles, snapshot, ids, "", "^(CPU|CPU Fan|CPU_FAN)$", "CPU fan", "Auto", "fan");
        if (!tiles.isEmpty()) {
            panels.add(panel("Cooling", "mdi:fan", true, "", "None", tiles));
        }

        tiles = new ArrayList<>();
        tile(tiles, snapshot, ids, "", "^(Total System Power|UPS Load)$", "System", "Auto");
        tile(tiles, snapshot, ids, "", "^(CPU Package Power|CPU PPT)$", "CPU", "Auto");
        tile(tiles, snapshot, ids, "GPU", "^(GPU Power|Total GPU Power)$", "GPU", "Auto");
        if (tiles.size() > 1) {
            panels.add(cpuAt + 1, panel("Power", "mdi:lightning-bolt", true, "", "None", tiles));
        }
        return panels;
    }

    private static HwinfoSnapshot.Device device(HwinfoSnapshot snapshot, String pattern) {
        Pattern match = Pattern.compile(pattern);
        for (HwinfoSnapshot.Device device : snapshot.devices) if (match.matcher(device.name).find()) return device;
        return null;
    }

    private static void tile(List<Map<String, Object>> tiles, HwinfoSnapshot snapshot, Ids ids, String device, String label, String title, String decimals) {
        tile(tiles, snapshot, ids, device, label, title, decimals, "");
    }

    private static void tile(List<Map<String, Object>> tiles, HwinfoSnapshot snapshot, Ids ids, String device, String label, String title, String decimals, String type) {
        Pattern devices = Pattern.compile(device);
        Pattern labels = Pattern.compile(label);
        for (HwinfoSnapshot.Reading reading : snapshot.readings) {
            if (!devices.matcher(reading.device.name).find() || !labels.matcher(reading.label).matches()) continue;
            if (!type.isEmpty() && !type.equals(reading.type)) continue;
            Map<String, Object> tile = new LinkedHashMap<>();
            tile.put("reading", ids.id(reading));
            tile.put("label", title);
            tile.put("statistic", "Current");
            tile.put("decimals", decimals);
            tile.put("showUnit", !"MHz".equals(reading.unit) && !"RPM".equals(reading.unit) && !"FPS".equals(reading.unit));
            tile.put("highlight", false);
            tile.put("alertAbove", 90);
            tiles.add(tile);
            return;
        }
    }

    private static Map<String, Object> panel(String title, String icon, boolean stack, String footer, String logo, List<Map<String, Object>> tiles) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("title", title);
        panel.put("icon", icon);
        panel.put("columns", tiles.size() <= 4 ? String.valueOf(Math.min(3, tiles.size())) : "3");
        panel.put("stack", stack);
        panel.put("headerReading", "");
        panel.put("headerLabel", "");
        panel.put("footerText", footer);
        panel.put("footerLogo", footer.isEmpty() ? "None" : logo);
        panel.put("footerIcon", "");
        panel.put("tiles", tiles);
        return panel;
    }

    /** "CPU [#0]: AMD Ryzen 9 9950X3D: Enhanced" becomes "AMD Ryzen 9 9950X3D". */
    private static String model(HwinfoSnapshot.Device device) {
        if (device == null) return "";
        String name = device.name;
        int start = name.indexOf("]: ");
        if (start >= 0) name = name.substring(start + 3);
        int end = name.indexOf(": ");
        return (end > 0 ? name.substring(0, end) : name).trim();
    }

    private static String brand(HwinfoSnapshot.Device device) {
        String name = device == null ? "" : device.name.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("nvidia") || name.contains("geforce")) return "NVIDIA";
        if (name.contains("amd") || name.contains("radeon") || name.contains("ryzen")) return "AMD";
        if (name.contains("intel")) return "Intel";
        return "None";
    }
}
