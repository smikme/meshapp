package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.PowerMonProtos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValueFormatterTest {

    private static final FieldDescriptor IP_FIELD =
            ConfigProtos.Config.NetworkConfig.IpV4Config.getDescriptor().findFieldByName("ip");
    private static final FieldDescriptor ENABLED_PROTOCOLS_FIELD =
            ConfigProtos.Config.NetworkConfig.getDescriptor().findFieldByName("enabled_protocols");
    private static final FieldDescriptor POSITION_FLAGS_FIELD =
            ConfigProtos.Config.PositionConfig.getDescriptor().findFieldByName("position_flags");
    private static final FieldDescriptor POWERMON_ENABLES_FIELD =
            ConfigProtos.Config.PowerConfig.getDescriptor().findFieldByName("powermon_enables");
    private static final FieldDescriptor DEVICE_BATTERY_INA_ADDRESS_FIELD =
            ConfigProtos.Config.PowerConfig.getDescriptor().findFieldByName("device_battery_ina_address");
    private static final FieldDescriptor IGNORE_INCOMING_FIELD =
            ConfigProtos.Config.LoRaConfig.getDescriptor().findFieldByName("ignore_incoming");

    @Test
    void formatsIpv4Fixed32AsDottedDecimal() {
        assertEquals("192.168.1.57", ConfigValueFormatter.formatValue(IP_FIELD, 956410048));
    }

    @Test
    void formatsSignedFixed32Ipv4AsUnsignedAddress() {
        assertEquals("192.168.1.211", ConfigValueFormatter.formatValue(IP_FIELD, (int) 0xD301A8C0L));
    }

    @Test
    void parsesDottedDecimalIpv4BackToFixed32() {
        assertEquals(16885952,
                ConfigValueFormatter.parseTextValue(IP_FIELD, Integer.class, "192.168.1.1"));
    }

    @Test
    void acceptsLegacyUnsignedIpv4NumberForBackwardCompatibility() {
        assertEquals((int) 0xD301A8C0L,
                ConfigValueFormatter.parseTextValue(IP_FIELD, Integer.class, "3540101312"));
    }

    @Test
    void rejectsInvalidIpv4Octets() {
        assertThrows(IllegalArgumentException.class,
                () -> ConfigValueFormatter.parseTextValue(IP_FIELD, Integer.class, "192.168.999.1"));
    }

    @Test
    void formatsNodeIdFieldAsMeshtasticNodeId() {
        assertEquals("!9e755af0",
                ConfigValueFormatter.formatValue(IGNORE_INCOMING_FIELD, (int) 0x9E755AF0L));
    }

    @Test
    void parsesNodeIdBackToUint32Value() {
        assertEquals((int) 0x9E755AF0L,
                ConfigValueFormatter.parseTextValue(IGNORE_INCOMING_FIELD, Integer.class, "!9e755af0"));
    }

    @Test
    void formatsI2cAddressAsHex() {
        assertEquals("0x40",
                ConfigValueFormatter.formatValue(DEVICE_BATTERY_INA_ADDRESS_FIELD, 64));
    }

    @Test
    void parsesI2cHexAddressBackToInteger() {
        assertEquals(64,
                ConfigValueFormatter.parseTextValue(DEVICE_BATTERY_INA_ADDRESS_FIELD, Integer.class, "0x40"));
    }

    @Test
    void formatsEnabledProtocolsAsReadableChoice() {
        assertEquals("UDP broadcast (локальная сеть)",
                ConfigValueFormatter.formatValue(ENABLED_PROTOCOLS_FIELD,
                        ConfigProtos.Config.NetworkConfig.ProtocolFlags.UDP_BROADCAST.getNumber()));
    }

    @Test
    void formatsPositionFlagsAsReadableList() {
        int flags = ConfigProtos.Config.PositionConfig.PositionFlags.ALTITUDE.getNumber()
                | ConfigProtos.Config.PositionConfig.PositionFlags.TIMESTAMP.getNumber();

        assertEquals("Высота, Временная метка",
                ConfigValueFormatter.formatValue(POSITION_FLAGS_FIELD, flags));
    }

    @Test
    void buildsBitmaskValueFromSelectedOptions() {
        var fakeItem = new com.meshtastic.client.model.ConfigTreeItem(
                "Enabled protocols",
                "enabled_protocols",
                0,
                Integer.class,
                null,
                ENABLED_PROTOCOLS_FIELD,
                "config",
                0
        );
        List<ConfigValueFormatter.BitmaskOption> selected = List.of(
                new ConfigValueFormatter.BitmaskOption(
                        ConfigProtos.Config.NetworkConfig.ProtocolFlags.UDP_BROADCAST.getNumber(),
                        "UDP broadcast (локальная сеть)"
                )
        );

        assertEquals(1, ConfigValueFormatter.buildBitmaskValue(fakeItem, selected));
    }

    @Test
    void exposesPowermonOptionsAsBitmaskSelector() {
        var fakeItem = new com.meshtastic.client.model.ConfigTreeItem(
                "Powermon enables",
                "powermon_enables",
                PowerMonProtos.PowerMon.State.GPS_Active.getNumber(),
                Long.class,
                null,
                POWERMON_ENABLES_FIELD,
                "config",
                0
        );

        assertTrue(ConfigValueFormatter.hasBitmaskOptions(fakeItem));
        assertEquals("GPS Active",
                ConfigValueFormatter.formatValue(POWERMON_ENABLES_FIELD,
                        (long) PowerMonProtos.PowerMon.State.GPS_Active.getNumber()));
    }
}
