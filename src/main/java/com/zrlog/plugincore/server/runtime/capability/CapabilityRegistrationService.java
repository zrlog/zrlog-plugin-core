package com.zrlog.plugincore.server.runtime.capability;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class CapabilityRegistrationService {

    private final CapabilityStore capabilityStore;
    private final Gson gson = new Gson();

    public CapabilityRegistrationService(CapabilityStore capabilityStore) {
        this.capabilityStore = capabilityStore;
    }

    public List<PluginCapability> registerCapabilitiesFromInitPayload(Plugin plugin, String initPayload) {
        List<PluginCapability> capabilities = prepareCapabilitiesFromInitPayload(plugin, initPayload);
        registerPreparedCapabilities(plugin, capabilities);
        return capabilities;
    }

    public List<PluginCapability> prepareCapabilitiesFromInitPayload(Plugin plugin, String initPayload) {
        if (plugin == null) {
            return new ArrayList<>();
        }
        List<PluginCapability> capabilities = parseExplicitCapabilities(plugin, initPayload);
        if (capabilities.isEmpty()) {
            return prepareCapabilities(plugin);
        }
        return capabilities;
    }

    public List<PluginCapability> prepareCapabilities(Plugin plugin) {
        if (plugin == null) {
            return new ArrayList<>();
        }
        List<PluginCapability> capabilities = capabilitiesFromPlugin(plugin);
        if (capabilities.isEmpty()) {
            capabilities = legacyCapabilities(plugin);
        }
        return capabilities;
    }

    public void registerPreparedCapabilities(Plugin plugin, List<PluginCapability> capabilities) {
        if (plugin == null) {
            return;
        }
        String pluginId = plugin.getId();
        if (pluginId != null) {
            capabilityStore.replacePluginCapabilities(pluginId,
                    Arrays.asList(plugin.getShortName(), PluginSessions.nameOrShortName(plugin)),
                    capabilities);
        }
    }

    public List<PluginCapability> legacyCapabilities(Plugin plugin) {
        List<PluginCapability> capabilities = new ArrayList<>();
        if (plugin == null || plugin.getServices() == null) {
            return capabilities;
        }
        for (String serviceName : plugin.getServices()) {
            Optional<PluginCapability> capability = CapabilityStore.fromLegacyService(plugin.getId(), PluginSessions.nameOrShortName(plugin), serviceName);
            if (capability.isPresent()) {
                capabilities.add(capability.get());
            }
        }
        return capabilities;
    }

    private List<PluginCapability> capabilitiesFromPlugin(Plugin plugin) {
        if (plugin == null || plugin.getCapabilities() == null) {
            return new ArrayList<>();
        }
        return normalizeCapabilities(plugin, plugin.getCapabilities());
    }

    private List<PluginCapability> parseExplicitCapabilities(Plugin plugin, String initPayload) {
        List<PluginCapability> capabilities = new ArrayList<>();
        if (initPayload == null || initPayload.trim().isEmpty()) {
            return capabilities;
        }
        JsonElement root = new JsonParser().parse(initPayload);
        if (!root.isJsonObject()) {
            return capabilities;
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement capabilityElement = object.get("capabilities");
        if (capabilityElement == null || !capabilityElement.isJsonArray()) {
            return capabilities;
        }
        JsonArray array = capabilityElement.getAsJsonArray();
        for (JsonElement item : array) {
            PluginCapability capability = gson.fromJson(item, PluginCapability.class);
            if (capability == null || capability.getKey() == null || capability.getKey().trim().isEmpty()) {
                continue;
            }
            capabilities.add(capability);
        }
        return normalizeCapabilities(plugin, capabilities);
    }

    private List<PluginCapability> normalizeCapabilities(Plugin plugin, List<PluginCapability> input) {
        List<PluginCapability> capabilities = new ArrayList<>();
        if (input == null) {
            return capabilities;
        }
        for (PluginCapability capability : input) {
            if (capability == null || isBlank(capability.getKey())) {
                continue;
            }
            capability.setPluginId(plugin.getId());
            if (isBlank(capability.getPluginName())) {
                capability.setPluginName(PluginSessions.nameOrShortName(plugin));
            }
            capabilities.add(capability);
        }
        return capabilities;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
