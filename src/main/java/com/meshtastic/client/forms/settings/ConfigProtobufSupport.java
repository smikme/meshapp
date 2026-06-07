package com.meshtastic.client.forms.settings;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Optional;
import org.meshtastic.proto.ChannelProtos;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

/**
 * Protobuf-specific helpers used by the configuration editor.
 * This class keeps oneof and channel lookup details out of JavaFX form code.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ConfigProtobufSupport {

    private static final String PAYLOAD_VARIANT_ONEOF = "payload_variant";

    private ConfigProtobufSupport() {}

    /**
     * Finds the active oneof field number for a device config message.
     *
     * @param config protobuf config message
     * @return active oneof field number or {@code -1}
     */
    public static int activeOneofFieldNumber(ConfigProtos.Config config) {
        return activeOneof(config)
            .flatMap(oneof ->
                Optional.ofNullable(config.getOneofFieldDescriptor(oneof))
            )
            .map(FieldDescriptor::getNumber)
            .orElse(-1);
    }

    /**
     * Finds the active oneof field number for a module config message.
     *
     * @param moduleConfig protobuf module config message
     * @return active oneof field number or {@code -1}
     */
    public static int activeModuleOneofFieldNumber(
        ModuleConfigProtos.ModuleConfig moduleConfig
    ) {
        return activeOneof(moduleConfig)
            .flatMap(oneof ->
                Optional.ofNullable(moduleConfig.getOneofFieldDescriptor(oneof))
            )
            .map(FieldDescriptor::getNumber)
            .orElse(-1);
    }

    /**
     * Returns the active payload message from a device config.
     *
     * @param config protobuf config message
     * @return active payload message, if present
     */
    public static Optional<Message> activeConfigPayload(
        ConfigProtos.Config config
    ) {
        return activeOneof(config)
            .flatMap(oneof ->
                Optional.ofNullable(config.getOneofFieldDescriptor(oneof))
            )
            .map(field -> (Message) config.getField(field));
    }

    /**
     * Returns the active payload message from a module config.
     *
     * @param moduleConfig protobuf module config message
     * @return active payload message, if present
     */
    public static Optional<Message> activeModulePayload(
        ModuleConfigProtos.ModuleConfig moduleConfig
    ) {
        return activeOneof(moduleConfig)
            .flatMap(oneof ->
                Optional.ofNullable(moduleConfig.getOneofFieldDescriptor(oneof))
            )
            .map(field -> (Message) moduleConfig.getField(field));
    }

    /**
     * Finds an original device config by active variant field number.
     *
     * @param configs       original configs
     * @param variantNumber active variant field number
     * @return matching config, if present
     */
    public static Optional<ConfigProtos.Config> findOriginalConfig(
        List<ConfigProtos.Config> configs,
        int variantNumber
    ) {
        return Optional
            .ofNullable(configs)
            .stream()
            .flatMap(List::stream)
            .filter(config -> activeOneofFieldNumber(config) == variantNumber)
            .findFirst();
    }

    /**
     * Finds an original module config by active variant field number.
     *
     * @param configs       original module configs
     * @param variantNumber active variant field number
     * @return matching module config, if present
     */
    public static Optional<ModuleConfigProtos.ModuleConfig> findOriginalModuleConfig(
        List<ModuleConfigProtos.ModuleConfig> configs,
        int variantNumber
    ) {
        return Optional
            .ofNullable(configs)
            .stream()
            .flatMap(List::stream)
            .filter(config ->
                activeModuleOneofFieldNumber(config) == variantNumber
            )
            .findFirst();
    }

    /**
     * Finds a channel by index.
     *
     * @param channels channel list
     * @param index    channel index
     * @return matching channel, if present
     */
    public static Optional<ChannelProtos.Channel> findChannelByIndex(
        List<ChannelProtos.Channel> channels,
        int index
    ) {
        return Optional
            .ofNullable(channels)
            .stream()
            .flatMap(List::stream)
            .filter(channel -> channel.getIndex() == index)
            .findFirst();
    }

    /**
     * Builds a disabled channel placeholder for comparisons and imports.
     *
     * @param index channel index
     * @return disabled channel message
     */
    public static ChannelProtos.Channel disabledChannel(int index) {
        return ChannelProtos.Channel.newBuilder()
            .setIndex(index)
            .setRole(ChannelProtos.Channel.Role.DISABLED)
            .build();
    }

    private static Optional<OneofDescriptor> activeOneof(Message message) {
        return Optional
            .ofNullable(message)
            .stream()
            .flatMap(value -> value.getDescriptorForType().getOneofs().stream())
            .filter(oneof -> PAYLOAD_VARIANT_ONEOF.equals(oneof.getName()))
            .findFirst();
    }
}
