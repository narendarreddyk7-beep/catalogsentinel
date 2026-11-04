package io.catalogsentinel.domain.normalize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Variant-defining attributes extracted from a listing's title and structured attributes. Any field may be
 * {@code null} when the listing does not state it. Units are canonical: storage and RAM in GB (1 TB =
 * 1000 GB, as marketed), screen size in inches, volume in millilitres, voltage in volts. {@code ounces} keeps
 * plain "oz" figures as stated: titles rarely say whether they mean fluid or weight ounces, so they are only
 * compared with other plain-ounce figures, never converted.
 */
public record ProductAttributes(
        Integer storageGb,
        Integer ramGb,
        String color,
        Double screenInches,
        String size,
        Integer generation,
        Integer packCount,
        Integer volumeMl,
        Integer capacityMah,
        Integer wattage,
        Double voltage,
        Double ounces) {

    public static final ProductAttributes EMPTY =
            new ProductAttributes(null, null, null, null, null, null, null, null, null, null, null, null);

    /** Stable string view, used for evidence and persisted as part of the normalized listing. */
    public Map<String, String> asMap() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "storage_gb", storageGb);
        put(m, "ram_gb", ramGb);
        put(m, "color", color);
        if (screenInches != null) {
            m.put("screen_in", formatInches(screenInches));
        }
        put(m, "size", size);
        put(m, "generation", generation);
        put(m, "pack_count", packCount);
        put(m, "volume_ml", volumeMl);
        put(m, "capacity_mah", capacityMah);
        put(m, "wattage_w", wattage);
        if (voltage != null) {
            m.put("voltage_v", formatInches(voltage));
        }
        if (ounces != null) {
            m.put("ounces", formatInches(ounces));
        }
        return m;
    }

    public boolean isEmpty() {
        return asMap().isEmpty();
    }

    static String formatInches(double inches) {
        return inches == Math.rint(inches) ? Long.toString((long) inches) : Double.toString(inches);
    }

    private static void put(Map<String, String> m, String key, Object value) {
        if (value != null) {
            m.put(key, value.toString());
        }
    }
}
