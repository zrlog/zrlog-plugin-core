package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.message.Plugin;
import com.zrlog.plugin.message.PluginCapability;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PluginInitPayloadValidatorTest {

    private final PluginInitPayloadValidator validator = new PluginInitPayloadValidator();

    @Test
    public void shouldParseAValidBoundedPayload() {
        PluginInitPayloadValidator.ValidatedPayload payload = validator.parse(packet("{"
                + "\"id\":\"plugin-a\","
                + "\"shortName\":\"reminder\","
                + "\"name\":\"Reminder\","
                + "\"services\":[\"reminder.scan\"],"
                + "\"capabilities\":[{\"key\":\"reminder.scan\",\"exposure\":[\"scheduler\"]}]"
                + "}"));

        assertEquals("plugin-a", payload.getPlugin().getId());
        assertEquals("reminder", payload.getPlugin().getShortName());
    }

    @Test
    public void shouldRejectOversizedPayloadBeforeReadingItsBuffer() {
        MsgPacket packet = new MsgPacket();
        packet.setDataLength(PluginInitPayloadValidator.MAX_INIT_PAYLOAD_BYTES + 1);

        assertRejected(() -> validator.parse(packet), "exceeds");
    }

    @Test
    public void shouldRejectTooManyRootAndCapabilityFields() {
        JsonObject root = new JsonObject();
        for (int i = 0; i <= PluginInitPayloadValidator.MAX_ROOT_FIELDS; i++) {
            root.addProperty("field" + i, i);
        }
        JsonObject oversizedRoot = root;
        assertRejected(() -> validator.parse(packet(oversizedRoot.toString())), "field count");

        JsonObject capability = new JsonObject();
        capability.addProperty("key", "task.run");
        for (int i = 0; i < PluginInitPayloadValidator.MAX_CAPABILITY_FIELDS; i++) {
            capability.addProperty("field" + i, i);
        }
        JsonArray capabilities = new JsonArray();
        capabilities.add(capability);
        root = new JsonObject();
        root.add("capabilities", capabilities);
        JsonObject capabilityRoot = root;
        assertRejected(() -> validator.parse(packet(capabilityRoot.toString())), "field count");
    }

    @Test
    public void shouldRejectOversizedCollectionsAndExposureLists() {
        JsonArray services = new JsonArray();
        for (int i = 0; i <= PluginInitPayloadValidator.MAX_COLLECTION_ITEMS; i++) {
            services.add("service." + i);
        }
        JsonObject root = new JsonObject();
        root.add("services", services);
        JsonObject servicesRoot = root;
        assertRejected(() -> validator.parse(packet(servicesRoot.toString())), "item count");

        JsonArray exposures = new JsonArray();
        for (int i = 0; i <= PluginInitPayloadValidator.MAX_EXPOSURES; i++) {
            exposures.add("scope-" + i);
        }
        JsonObject capability = new JsonObject();
        capability.addProperty("key", "task.run");
        capability.add("exposure", exposures);
        JsonArray capabilities = new JsonArray();
        capabilities.add(capability);
        root = new JsonObject();
        root.add("capabilities", capabilities);
        JsonObject exposureRoot = root;
        assertRejected(() -> validator.parse(packet(exposureRoot.toString())), "exposure item count");
    }

    @Test
    public void shouldRejectOverlongMetadataAndCapabilityText() {
        JsonObject root = new JsonObject();
        root.addProperty("desc", "x".repeat(PluginInitPayloadValidator.MAX_DESCRIPTION_CODE_POINTS + 1));
        assertRejected(() -> validator.parse(packet(root.toString())), "plugin.desc");

        PluginCapability capability = new PluginCapability();
        capability.setKey("x".repeat(PluginInitPayloadValidator.MAX_IDENTIFIER_CODE_POINTS + 1));
        List<PluginCapability> capabilities = new ArrayList<>();
        capabilities.add(capability);
        assertRejected(() -> validator.validateCapabilities(capabilities), ".key");
    }

    @Test
    public void shouldRejectOversizedPerPluginPersistentDocuments() {
        Plugin plugin = new Plugin();
        plugin.setShortName("large-plugin");
        plugin.setPreviewImageBase64("p".repeat(PluginInitPayloadValidator.MAX_PREVIEW_IMAGE_CODE_POINTS));
        plugin.setPaths(longPaths("/path/"));
        plugin.setCacheableStaticPaths(longPaths("/cache/"));
        assertRejected(() -> validator.validatePlugin(plugin), "Plugin metadata");

        List<PluginCapability> capabilities = new ArrayList<>();
        for (int i = 0; i < PluginInitPayloadValidator.MAX_CAPABILITIES; i++) {
            PluginCapability capability = new PluginCapability();
            capability.setKey("task." + i);
            capability.setDescription("d".repeat(PluginInitPayloadValidator.MAX_DESCRIPTION_CODE_POINTS));
            capabilities.add(capability);
        }
        assertRejected(() -> validator.validateCapabilities(capabilities), "Plugin capabilities");
    }

    private Set<String> longPaths(String prefix) {
        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < PluginInitPayloadValidator.MAX_COLLECTION_ITEMS; i++) {
            String suffix = Integer.toString(i);
            paths.add(prefix + suffix + "x".repeat(
                    PluginInitPayloadValidator.MAX_PATH_CODE_POINTS - prefix.length() - suffix.length()));
        }
        return paths;
    }

    private MsgPacket packet(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        MsgPacket packet = new MsgPacket();
        packet.setData(ByteBuffer.wrap(bytes));
        packet.setDataLength(bytes.length);
        return packet;
    }

    private void assertRejected(Runnable action, String messagePart) {
        try {
            action.run();
            fail("payload should be rejected");
        } catch (PluginInitPayloadValidator.PluginInitValidationException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
        }
    }
}
