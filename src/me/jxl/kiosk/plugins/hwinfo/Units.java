package me.jxl.kiosk.plugins.hwinfo;

import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** How a HWiNFO unit reads on screen and what Home Assistant calls it. */
final class Units {
    private Units() {}

    /** Decimals that suit the unit when the user leaves it to the plugin. */
    static int autoDecimals(double value, String unit) {
        switch (unit.toLowerCase(Locale.ROOT)) {
            case "v":
            case "a":
                return 2;
            case "°c":
            case "°f":
            case "w":
            case "mhz":
            case "rpm":
            case "%":
            case "fps":
            case "yes/no":
                return 0;
            case "l/h":
                return 1;
            default:
                double size = Math.abs(value);
                return size < 10 ? 2 : size < 100 ? 1 : 0;
        }
    }

    /** The number alone, grouped the way the kiosk's language groups it, such as 2,234. */
    static String number(double value, String unit, int decimals) {
        int places = decimals >= 0 ? decimals : autoDecimals(value, unit);
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(places);
        format.setMaximumFractionDigits(places);
        String text = format.format(value);
        // Rounding can leave a lone minus in front of zero.
        return text.matches("-0([.,]0+)?") ? text.substring(1) : text;
    }

    /** The value with its unit, the way the settings pickers show it. */
    static String describe(double value, String unit) {
        if ("Yes/No".equalsIgnoreCase(unit)) return value != 0 ? "Yes" : "No";
        String number = number(value, unit, -1);
        return unit.isEmpty() ? number : unit.startsWith("°") || unit.equals("%") ? number + unit : number + " " + unit;
    }

    /** Whether HWiNFO reports the reading as a yes or no answer. */
    static boolean isBinary(String unit) {
        return "Yes/No".equalsIgnoreCase(unit);
    }

    /** The Material Design Icon the reading pickers show for the reading. */
    static String icon(String type, String unit) {
        if (isBinary(unit)) return "mdi:alert-circle-outline";
        switch (type) {
            case "temperature": return "mdi:thermometer";
            case "voltage": return "mdi:sine-wave";
            case "fan": return "mdi:fan";
            case "current": return "mdi:current-dc";
            case "power": return "mdi:lightning-bolt";
            case "clock": return "mdi:speedometer";
            case "usage": return "mdi:gauge";
            default: break;
        }
        switch (unit.toLowerCase(Locale.ROOT)) {
            case "fps": return "mdi:monitor-shimmer";
            case "l/h": return "mdi:water";
            case "mb":
            case "gb": return "mdi:memory";
            case "mb/s":
            case "kb/s": return "mdi:swap-vertical";
            case "ms": return "mdi:timer-outline";
            default: return "mdi:chart-line";
        }
    }

    /** ESPHome sensor metadata for the reading, with a Home Assistant device class where one fits. */
    static Map<String, Object> metadata(String type, String unit) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String clean = unit.length() > 32 ? unit.substring(0, 32) : unit;
        metadata.put("unit", clean);
        metadata.put("deviceClass", deviceClass(type, clean));
        metadata.put("stateClass", "measurement");
        metadata.put("accuracyDecimals", Math.min(6, Math.max(0, autoDecimals(10, clean))));
        return metadata;
    }

    private static String deviceClass(String type, String unit) {
        switch (unit) {
            case "°C":
            case "°F":
                return "temperature";
            case "V":
            case "mV":
                return "voltage";
            case "A":
            case "mA":
                return "current";
            case "W":
            case "kW":
                return "power";
            case "MHz":
            case "GHz":
                return "frequency";
            case "KB":
            case "MB":
            case "GB":
            case "TB":
                return "data_size";
            case "KB/s":
            case "MB/s":
            case "GB/s":
                return "data_rate";
            case "Wh":
            case "kWh":
                return "energy";
            default:
                return "";
        }
    }
}
