package me.jxl.kiosk.plugins.hwinfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One poll of a HWiNFO server: the devices it watches and every reading under them. */
final class HwinfoSnapshot {
    /** HWiNFO's reading types, in the order of its shared memory enum. */
    static final String[] TYPES = {"none", "temperature", "voltage", "fan", "current", "power", "clock", "usage", "other"};

    static final class Device {
        final int index;
        final long id;
        final long instance;
        final String name;

        Device(int index, long id, long instance, String name) {
            this.index = index;
            this.id = id;
            this.instance = instance;
            this.name = name;
        }
    }

    static final class Reading {
        /** Stable across restarts of HWiNFO: device ID, device instance and reading ID. */
        final String key;
        final String type;
        final Device device;
        final String label;
        final String unit;
        final double value;
        final double min;
        final double max;
        final double avg;

        Reading(String key, String type, Device device, String label, String unit, double value, double min, double max, double avg) {
            this.key = key;
            this.type = type;
            this.device = device;
            this.label = label;
            this.unit = unit;
            this.value = value;
            this.min = min;
            this.max = max;
            this.avg = avg;
        }

        double pick(String statistic) {
            switch (statistic) {
                case "min": return min;
                case "max": return max;
                case "avg": return avg;
                default: return value;
            }
        }
    }

    final String host;
    final String version;
    final int pollingMillis;
    final List<Device> devices;
    final List<Reading> readings;
    final Map<String, Reading> byKey;

    HwinfoSnapshot(String host, String version, int pollingMillis, List<Device> devices, List<Reading> readings) {
        this.host = host;
        this.version = version;
        this.pollingMillis = pollingMillis;
        this.devices = Collections.unmodifiableList(devices);
        this.readings = Collections.unmodifiableList(readings);
        Map<String, Reading> keys = new LinkedHashMap<>();
        for (Reading reading : readings) keys.put(reading.key, reading);
        byKey = Collections.unmodifiableMap(keys);
    }
}
