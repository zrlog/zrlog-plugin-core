package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.util.PersistentJsonLimits;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

final class PluginInitPayloadValidator {

    static final int MAX_INIT_PAYLOAD_BYTES = 1024 * 1024;
    static final int MAX_ROOT_FIELDS = 32;
    static final int MAX_CAPABILITY_FIELDS = 32;
    static final int MAX_COLLECTION_ITEMS = 256;
    static final int MAX_CAPABILITIES = 128;
    static final int MAX_EXPOSURES = 16;
    static final int MAX_IDENTIFIER_CODE_POINTS = 256;
    static final int MAX_PATH_CODE_POINTS = 1024;
    static final int MAX_LABEL_CODE_POINTS = 1024;
    static final int MAX_DESCRIPTION_CODE_POINTS = 8 * 1024;
    static final int MAX_PREVIEW_IMAGE_CODE_POINTS = 512 * 1024;
    static final int MAX_PLUGIN_METADATA_BYTES = 1024 * 1024;
    static final int MAX_PLUGIN_CAPABILITIES_BYTES = 1024 * 1024;

    private static final String[] PLUGIN_COLLECTION_FIELDS = {
            "paths", "actions", "services", "dependentService", "cacheableStaticPaths"
    };

    private final Gson gson = new Gson();

    ValidatedPayload parse(MsgPacket packet) {
        byte[] payloadBytes = payloadBytes(packet);
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        JsonElement root;
        try {
            root = new JsonParser().parse(payload);
        } catch (RuntimeException e) {
            throw invalid("Invalid plugin initialization payload", e);
        }
        if (root == null || !root.isJsonObject()) {
            throw invalid("Plugin initialization payload must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();
        requireItemCount("Plugin initialization field count", object.entrySet().size(), MAX_ROOT_FIELDS);
        validateCollectionShapes(object);
        validateCapabilityShapes(object);

        Plugin plugin;
        try {
            plugin = gson.fromJson(object, Plugin.class);
        } catch (RuntimeException e) {
            throw invalid("Invalid plugin initialization payload", e);
        }
        if (plugin == null) {
            throw invalid("Plugin initialization payload is empty");
        }
        validatePlugin(plugin);
        return new ValidatedPayload(plugin);
    }

    void validatePlugin(Plugin plugin) {
        if (plugin == null) {
            throw invalid("Plugin initialization payload is empty");
        }
        requireText("plugin.id", plugin.getId(), MAX_IDENTIFIER_CODE_POINTS);
        requireText("plugin.version", plugin.getVersion(), MAX_IDENTIFIER_CODE_POINTS);
        requireText("plugin.name", plugin.getName(), MAX_LABEL_CODE_POINTS);
        requireText("plugin.desc", plugin.getDesc(), MAX_DESCRIPTION_CODE_POINTS);
        requireText("plugin.author", plugin.getAuthor(), MAX_LABEL_CODE_POINTS);
        requireText("plugin.shortName", plugin.getShortName(), MAX_IDENTIFIER_CODE_POINTS);
        requireText("plugin.indexPage", plugin.getIndexPage(), MAX_PATH_CODE_POINTS);
        requireText("plugin.previewImageBase64", plugin.getPreviewImageBase64(), MAX_PREVIEW_IMAGE_CODE_POINTS);
        requireStrings("plugin.paths", plugin.getPaths(), MAX_COLLECTION_ITEMS, MAX_PATH_CODE_POINTS);
        requireStrings("plugin.actions", plugin.getActions(), MAX_COLLECTION_ITEMS, MAX_IDENTIFIER_CODE_POINTS);
        requireStrings("plugin.services", plugin.getServices(), MAX_COLLECTION_ITEMS, MAX_IDENTIFIER_CODE_POINTS);
        requireStrings("plugin.dependentService", plugin.getDependentService(), MAX_COLLECTION_ITEMS,
                MAX_IDENTIFIER_CODE_POINTS);
        requireStrings("plugin.cacheableStaticPaths", plugin.getCacheableStaticPaths(), MAX_COLLECTION_ITEMS,
                MAX_PATH_CODE_POINTS);
        validateCapabilities(plugin.getCapabilities());
        PersistentJsonLimits.requireUtf8Length("Plugin metadata", gson.toJson(plugin), MAX_PLUGIN_METADATA_BYTES);
    }

    void validateCapabilities(List<PluginCapability> capabilities) {
        List<PluginCapability> values = capabilities == null ? Collections.emptyList() : capabilities;
        requireItemCount("Plugin capability count", values.size(), MAX_CAPABILITIES);
        for (int i = 0; i < values.size(); i++) {
            PluginCapability capability = values.get(i);
            if (capability == null) {
                continue;
            }
            String prefix = "plugin.capabilities[" + i + "]";
            requireText(prefix + ".pluginId", capability.getPluginId(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".pluginName", capability.getPluginName(), MAX_LABEL_CODE_POINTS);
            requireText(prefix + ".key", capability.getKey(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".serviceName", capability.getServiceName(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".type", capability.getType(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".label", capability.getLabel(), MAX_LABEL_CODE_POINTS);
            requireText(prefix + ".description", capability.getDescription(), MAX_DESCRIPTION_CODE_POINTS);
            requireStrings(prefix + ".exposure", capability.getExposure(), MAX_EXPOSURES,
                    MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".riskLevel", capability.getRiskLevel(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".channel", capability.getChannel(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".defaultCron", capability.getDefaultCron(), MAX_IDENTIFIER_CODE_POINTS);
            requireText(prefix + ".timezone", capability.getTimezone(), MAX_IDENTIFIER_CODE_POINTS);
        }
        PersistentJsonLimits.requireUtf8Length("Plugin capabilities", gson.toJson(values),
                MAX_PLUGIN_CAPABILITIES_BYTES);
    }

    private byte[] payloadBytes(MsgPacket packet) {
        if (packet == null) {
            throw invalid("Plugin initialization payload is empty");
        }
        int declaredLength = packet.getDataLength();
        if (declaredLength < 0 || declaredLength > MAX_INIT_PAYLOAD_BYTES) {
            throw invalid("Plugin initialization payload exceeds " + MAX_INIT_PAYLOAD_BYTES + " bytes");
        }
        ByteBuffer data = packet.getData();
        if (data == null) {
            throw invalid("Plugin initialization payload is empty");
        }
        int actualLength;
        byte[] bytes;
        try {
            bytes = data.array();
            actualLength = bytes.length;
        } catch (RuntimeException e) {
            throw invalid("Invalid plugin initialization payload buffer", e);
        }
        if (actualLength != declaredLength || actualLength > MAX_INIT_PAYLOAD_BYTES) {
            throw invalid("Plugin initialization payload length does not match its buffer");
        }
        return bytes;
    }

    private void validateCollectionShapes(JsonObject object) {
        for (String field : PLUGIN_COLLECTION_FIELDS) {
            JsonElement value = object.get(field);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (!value.isJsonArray()) {
                throw invalid("plugin." + field + " must be an array");
            }
            requireItemCount("plugin." + field + " item count", value.getAsJsonArray().size(),
                    MAX_COLLECTION_ITEMS);
        }
    }

    private void validateCapabilityShapes(JsonObject object) {
        JsonElement value = object.get("capabilities");
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (!value.isJsonArray()) {
            throw invalid("plugin.capabilities must be an array");
        }
        JsonArray capabilities = value.getAsJsonArray();
        requireItemCount("Plugin capability count", capabilities.size(), MAX_CAPABILITIES);
        for (int i = 0; i < capabilities.size(); i++) {
            JsonElement item = capabilities.get(i);
            if (item == null || item.isJsonNull()) {
                continue;
            }
            if (!item.isJsonObject()) {
                throw invalid("plugin.capabilities[" + i + "] must be an object");
            }
            JsonObject capability = item.getAsJsonObject();
            requireItemCount("plugin.capabilities[" + i + "] field count",
                    capability.entrySet().size(), MAX_CAPABILITY_FIELDS);
            JsonElement exposure = capability.get("exposure");
            if (exposure == null || exposure.isJsonNull()) {
                continue;
            }
            if (!exposure.isJsonArray()) {
                throw invalid("plugin.capabilities[" + i + "].exposure must be an array");
            }
            requireItemCount("plugin.capabilities[" + i + "].exposure item count",
                    exposure.getAsJsonArray().size(), MAX_EXPOSURES);
        }
    }

    private void requireStrings(String label, Collection<String> values, int maxItems, int maxCodePoints) {
        if (values == null) {
            return;
        }
        requireItemCount(label + " item count", values.size(), maxItems);
        int index = 0;
        for (String value : values) {
            requireText(label + "[" + index + "]", value, maxCodePoints);
            index++;
        }
    }

    private void requireItemCount(String label, int count, int maxItems) {
        if (count > maxItems) {
            throw invalid(label + " exceeds " + maxItems);
        }
    }

    private void requireText(String label, String value, int maxCodePoints) {
        if (value != null && value.codePointCount(0, value.length()) > maxCodePoints) {
            throw invalid(label + " exceeds " + maxCodePoints + " characters");
        }
    }

    private PluginInitValidationException invalid(String message) {
        return new PluginInitValidationException(message);
    }

    private PluginInitValidationException invalid(String message, Throwable cause) {
        return new PluginInitValidationException(message, cause);
    }

    static final class ValidatedPayload {
        private final Plugin plugin;

        private ValidatedPayload(Plugin plugin) {
            this.plugin = plugin;
        }

        Plugin getPlugin() {
            return plugin;
        }
    }

    static final class PluginInitValidationException extends IllegalArgumentException {
        private PluginInitValidationException(String message) {
            super(message);
        }

        private PluginInitValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
