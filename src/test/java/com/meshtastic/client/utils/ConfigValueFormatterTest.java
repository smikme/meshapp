package com.meshtastic.client.utils;

import com.google.protobuf.Descriptors.FieldDescriptor;
import org.junit.jupiter.api.Test;
import org.meshtastic.proto.ConfigProtos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigValueFormatterTest {

    private static final FieldDescriptor IP_FIELD =
            ConfigProtos.Config.NetworkConfig.IpV4Config.getDescriptor().findFieldByName("ip");

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
}
