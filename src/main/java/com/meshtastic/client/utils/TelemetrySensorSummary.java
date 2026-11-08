package com.meshtastic.client.utils;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.TelemetryEntry;
import java.util.ArrayList;
import java.util.List;

/** Readable released sensor measurements, preserving zero values and sparse channel numbers. */
public final class TelemetrySensorSummary {
    private TelemetrySensorSummary() {}

    public static String format(TelemetryEntry entry) {
        List<String> values = new ArrayList<>();
        add(values, I18n.t("telemetry.sensor.adc_voltage", 0), entry.getAdcVoltageCh0(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 1), entry.getAdcVoltageCh1(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 2), entry.getAdcVoltageCh2(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 3), entry.getAdcVoltageCh3(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 4), entry.getAdcVoltageCh4(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 5), entry.getAdcVoltageCh5(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 6), entry.getAdcVoltageCh6(), "V");
        add(values, I18n.t("telemetry.sensor.adc_voltage", 7), entry.getAdcVoltageCh7(), "V");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 0), entry.getOneWireTemperatureCh0(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 1), entry.getOneWireTemperatureCh1(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 2), entry.getOneWireTemperatureCh2(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 3), entry.getOneWireTemperatureCh3(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 4), entry.getOneWireTemperatureCh4(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 5), entry.getOneWireTemperatureCh5(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 6), entry.getOneWireTemperatureCh6(), "°C");
        add(values, I18n.t("telemetry.sensor.one_wire_temperature", 7), entry.getOneWireTemperatureCh7(), "°C");
        add(values, I18n.t("telemetry.sensor.lightning_strike_count_1h"), entry.getLightningStrikeCount1h(), "");
        add(values, I18n.t("telemetry.sensor.lightning_distance_km"), entry.getLightningDistanceKm(), "km");
        add(values, I18n.t("telemetry.sensor.pm_status_flags"), entry.getPmStatusFlags(), "");
        add(values, I18n.t("telemetry.sensor.soil_ph"), entry.getSoilPh(), "");
        add(values, I18n.t("telemetry.sensor.ph"), entry.getPh(), "");
        add(values, I18n.t("telemetry.sensor.electrical_conductivity"), entry.getElectricalConductivity(), "mS/cm");
        add(values, I18n.t("telemetry.sensor.salinity"), entry.getSalinity(), "mg/l");
        add(values, I18n.t("telemetry.sensor.nitrogen"), entry.getNitrogen(), "mg/kg");
        add(values, I18n.t("telemetry.sensor.phosphorus"), entry.getPhosphorus(), "mg/kg");
        add(values, I18n.t("telemetry.sensor.potassium"), entry.getPotassium(), "mg/kg");
        add(values, I18n.t("telemetry.sensor.dissolved_oxygen"), entry.getDissolvedOxygen(), "mg/l");
        add(values, I18n.t("telemetry.sensor.orp"), entry.getOrp(), "mV");
        add(values, I18n.t("telemetry.sensor.chemical_oxygen_demand"), entry.getChemicalOxygenDemand(), "mg/l");
        add(values, I18n.t("telemetry.sensor.turbidity"), entry.getTurbidity(), "NTU");
        add(values, I18n.t("telemetry.sensor.nitrate"), entry.getNitrate(), "ppm");
        add(values, I18n.t("telemetry.sensor.ammonium"), entry.getAmmonium(), "ppm");
        add(values, I18n.t("telemetry.sensor.biochemical_oxygen_demand"), entry.getBiochemicalOxygenDemand(), "mg/l");
        add(values, I18n.t("telemetry.sensor.solar_irradiance"), entry.getSolarIrradiance(), "W/m²");
        return String.join("; ", values);
    }

    private static void add(List<String> values, String label, Number value, String unit) {
        if (value != null) {
            values.add(label + ": " + value + (unit.isEmpty() ? "" : " " + unit));
        }
    }
}
