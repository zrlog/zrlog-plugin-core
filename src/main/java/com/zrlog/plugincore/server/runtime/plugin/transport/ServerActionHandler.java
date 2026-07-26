package com.zrlog.plugincore.server.runtime.plugin.transport;

import com.google.gson.Gson;
import com.hibegin.common.dao.DAO;
import com.hibegin.common.util.EnvKit;
import com.hibegin.common.util.LoggerUtil;
import com.zrlog.plugin.IOSession;
import com.zrlog.plugin.RunConstants;
import com.zrlog.plugin.api.IActionHandler;
import com.zrlog.plugin.common.BasicCronParser;
import com.zrlog.plugin.common.KvRepository;
import com.zrlog.plugin.common.model.Comment;
import com.zrlog.plugin.common.model.CreateArticleRequest;
import com.zrlog.plugin.common.model.TemplatePath;
import com.zrlog.plugin.data.codec.BaseHttpRequestInfo;
import com.zrlog.plugin.data.codec.HttpResponseInfo;
import com.zrlog.plugin.data.codec.MsgPacket;
import com.zrlog.plugin.data.codec.MsgPacketStatus;
import com.zrlog.plugin.message.*;
import com.zrlog.plugin.type.ActionType;
import com.zrlog.plugin.type.RunType;
import com.zrlog.plugincore.server.dao.*;
import com.zrlog.plugincore.server.model.PluginCore;
import com.zrlog.plugincore.server.runtime.PluginRuntimeBridge;
import com.zrlog.plugincore.server.runtime.article.ArticleExtensionRepository;
import com.zrlog.plugincore.server.runtime.capability.CapabilityRegistrationService;
import com.zrlog.plugincore.server.runtime.capability.CapabilityStore;
import com.zrlog.plugincore.server.runtime.capability.InvokeContext;
import com.zrlog.plugincore.server.runtime.capability.RuntimeCapabilityInvokerFactory;
import com.zrlog.plugincore.server.runtime.notification.*;
import com.zrlog.plugincore.server.runtime.plugin.bootstrap.PluginBootstrapService;
import com.zrlog.plugincore.server.runtime.plugin.config.PluginConfig;
import com.zrlog.plugincore.server.runtime.plugin.log.PluginLogContext;
import com.zrlog.plugincore.server.runtime.plugin.session.PluginSessions;
import com.zrlog.plugincore.server.runtime.scheduler.AutomationStore;
import com.zrlog.plugincore.server.runtime.scheduler.RuntimeAutomationService;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerQueryService;
import com.zrlog.plugincore.server.runtime.scheduler.SchedulerUpdateService;
import com.zrlog.plugincore.server.runtime.state.DefaultPluginRuntimeStarter;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateService;
import com.zrlog.plugincore.server.runtime.state.PluginRuntimeStateStore;
import com.zrlog.plugincore.server.runtime.store.WebsiteRuntimeKvStore;
import com.zrlog.plugincore.server.util.HttpUtils;
import com.zrlog.plugincore.server.util.PublicInfoLoader;
import com.zrlog.plugincore.server.vo.PluginVO;
import org.jsoup.Jsoup;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ServerActionHandler implements IActionHandler {

    private static final Logger LOGGER = LoggerUtil.getLogger(ServerActionHandler.class);

    @Override
    public void service(final IOSession session, final MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (msgPacket.getStatus() == MsgPacketStatus.SEND_REQUEST) {
                new ServiceMsgPacketHandler(session).doHandle(msgPacket);
            }
        }
    }

    private void doRefreshCache() {
        if (RunConstants.runType != RunType.BLOG) {
            return;
        }
        try {
            Map<String, String> requestHeaders = new HashMap<>();
            requestHeaders.put("X-Plugin-Token", PluginRuntimeBridge.hostConnection().getBlogPluginToken());
            byte[] bytes = HttpUtils.sendGetRequest(PluginRuntimeBridge.hostConnection().getBlogApiHomeUrl()
                    + "/api/admin/refreshCache", requestHeaders);
            if (EnvKit.isDevMode()) {
                LOGGER.info(PluginLogContext.prefix("refresh cache success " + new String(bytes)));
            }
        } catch (Exception e) {
            LOGGER.warning(PluginLogContext.prefix("Refresh cache failed,  " + e.getMessage()));
        }
    }

    @Override
    public void initConnect(IOSession session, MsgPacket msgPacket) {
        Plugin plugin = new Gson().fromJson(msgPacket.getDataStr(), Plugin.class);
        plugin.setId(Objects.requireNonNullElse(plugin.getId(), UUID.randomUUID().toString()));
        PluginLogContext.bind(session, plugin);
        session.setPlugin(plugin);
        try (PluginLogContext.Scope ignored = PluginLogContext.open(plugin)) {
            String pluginName = PluginSessions.nameOrShortName(plugin);
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            CapabilityStore capabilityStore = new CapabilityStore(kvStore);
            List<PluginCapability> capabilities;
            try {
                capabilities = initializePluginLifecycle(session, msgPacket, plugin, pluginName, kvStore, capabilityStore);
            } catch (RuntimeException e) {
                failPluginLifecycleInitialization(session, msgPacket, plugin, pluginName, runtimeStateService(kvStore, session), e);
                return;
            }
            bootstrapRuntimeFeaturesBestEffort(kvStore, capabilityStore, capabilities);
            //doRefreshCache(20);
        }
    }

    private List<PluginCapability> initializePluginLifecycle(IOSession session,
                                                             MsgPacket msgPacket,
                                                             Plugin plugin,
                                                             String pluginName,
                                                             WebsiteRuntimeKvStore kvStore,
                                                             CapabilityStore capabilityStore) {
        pluginBootstrap().registerPlugin(session);
        PluginRuntimeStateService stateService = runtimeStateService(kvStore, session);
        Long processId = PluginSessions.processId(session);
        stateService.markInitializing(plugin.getId(), pluginName, null, processId);
        List<PluginCapability> capabilities = new CapabilityRegistrationService(capabilityStore)
                .registerCapabilitiesFromInitPayload(plugin, msgPacket.getDataStr());
        stateService.markReady(plugin.getId(), pluginName, processId);
        session.sendJsonMsg(new PluginTransportModels.InitResponse(RunConstants.runType.toString()),
                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
        return capabilities;
    }

    private void failPluginLifecycleInitialization(IOSession session,
                                                   MsgPacket msgPacket,
                                                   Plugin plugin,
                                                   String pluginName,
                                                   PluginRuntimeStateService stateService,
                                                   RuntimeException e) {
        LOGGER.log(Level.WARNING, PluginLogContext.prefix("init plugin runtime error"), e);
        session.sendJsonMsg(PluginTransportModels.InitErrorResponse.error(e.getMessage()),
                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
        if (plugin == null) {
            return;
        }
        if (plugin.getShortName() != null && !plugin.getShortName().trim().isEmpty()) {
            pluginBootstrap().stopPlugin(plugin.getShortName());
        }
        String pluginId = plugin.getId();
        if (pluginId != null && !pluginId.trim().isEmpty()) {
            stateService.markFailed(pluginId, pluginName, e.getMessage());
        }
    }

    private void bootstrapRuntimeFeaturesBestEffort(KvRepository kvStore,
                                                    CapabilityStore capabilityStore,
                                                    List<PluginCapability> capabilities) {
        try {
            new RuntimeAutomationService(new AutomationStore(kvStore), capabilityStore, new BasicCronParser())
                    .ensureDefaultAutomations(capabilities, null);
        } catch (RuntimeException e) {
            // Scheduler bootstrap is runtime feature setup; it must not fail plugin lifecycle initialization.
            LOGGER.log(Level.WARNING, PluginLogContext.prefix("init plugin default automations error"), e);
        }
    }

    @Override
    public void capabilityInvoke(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            CapabilityInvokeRequest request = new Gson().fromJson(msgPacket.getDataStr(), CapabilityInvokeRequest.class);
            if (request == null) {
                sendCapabilityResult(session, msgPacket, capabilityError("Capability invoke request is invalid"));
                return;
            }
            InvokeContext context = new InvokeContext();
            context.setSource("internal");
            context.setRequestId(request.getRequestId());
            context.setTraceId(request.getTraceId());
            CapabilityInvokeResult result = RuntimeCapabilityInvokerFactory.socket(kvStore)
                    .invoke(request.getPluginId(), request.getCapabilityKey(), request.getPayload(), context);
            sendCapabilityResult(session, msgPacket, result);
        }
    }

    @Override
    public void notificationPublish(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
            NotificationRequest request = new Gson().fromJson(msgPacket.getDataStr(), NotificationRequest.class);
            if (Objects.isNull(request.getSourcePluginId())) {
                request.setSourcePluginId(session.getPlugin().getId());
            }
            if (Objects.isNull(request.getSourcePluginName())) {
                request.setSourcePluginName(PluginSessions.nameOrShortName(session.getPlugin()));
            }
            NotificationRuntime notificationRuntime = new NotificationRuntime(
                    new CapabilityStore(kvStore),
                    new NotificationDeliveryStore(kvStore),
                    new NotificationProviderResolver(),
                    pluginCore.getSetting().getNotification(),
                    RuntimeCapabilityInvokerFactory.socket(kvStore, pluginCore)
            );
            NotificationPublishResult result = notificationRuntime.publish(request);
            session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                    result.getFailedCount() == 0 ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    @Override
    public void notificationChannelQuery(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            try {
                session.sendJsonMsg(notificationChannelQueryResult(), msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, PluginLogContext.prefix("query notification channels error"), e);
                NotificationChannelQueryResult result = NotificationChannelQueryResult.error(
                        e.getMessage() == null ? "query notification channels failed" : e.getMessage());
                session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
            }
        }
    }

    private NotificationChannelQueryResult notificationChannelQueryResult() {
        WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
        PluginCore pluginCore = PluginCoreDAO.getInstance().loadSnapshot();
        List<PluginCapability> providers = new CapabilityStore(kvStore).listByType("notification_channel");
        List<PluginCapability> notificationProviders = new ArrayList<>();
        Set<String> channels = new TreeSet<>();
        for (PluginCapability provider : providers) {
            if (provider == null || provider.getExposure() == null
                    || !provider.getExposure().contains("notification")
                    || provider.getChannel() == null || provider.getChannel().trim().isEmpty()) {
                continue;
            }
            notificationProviders.add(provider);
            channels.add(provider.getChannel());
        }
        NotificationProviderResolver resolver = new NotificationProviderResolver();
        NotificationSetting setting = pluginCore.getSetting().getNotification();
        Map<String, Plugin> pluginsById = pluginsById(pluginCore);
        List<NotificationChannelProvider> items = new ArrayList<>();
        for (String channel : channels) {
            PluginCapability selected = resolver.resolve(channel, notificationProviders, setting).orElse(null);
            boolean reviewRequired = resolver.reviewRequired(channel, notificationProviders, setting);
            for (PluginCapability provider : notificationProviders) {
                if (!Objects.equals(channel, provider.getChannel())) {
                    continue;
                }
                Plugin plugin = pluginsById.get(provider.getPluginId());
                NotificationChannelProvider item = new NotificationChannelProvider();
                item.setChannel(channel);
                item.setProviderPluginId(provider.getPluginId());
                item.setProviderPluginName(pluginDisplayName(plugin));
                item.setProviderPluginPreviewImageBase64(pluginPreviewImageBase64(plugin));
                item.setChannelIconBase64(pluginPreviewImageBase64(plugin));
                item.setCapabilityKey(provider.getKey());
                item.setCapabilityLabel(provider.getLabel());
                item.setProviderStatus("available");
                item.setSelected(selected != null
                        && Objects.equals(selected.getPluginId(), provider.getPluginId())
                        && Objects.equals(selected.getKey(), provider.getKey()));
                item.setConfirmed(configuredNotificationProvider(setting, channel, provider));
                item.setReviewRequired(reviewRequired);
                items.add(item);
            }
        }
        return NotificationChannelQueryResult.success(items);
    }

    private Map<String, Plugin> pluginsById(PluginCore pluginCore) {
        Map<String, Plugin> pluginsById = new HashMap<>();
        if (pluginCore == null || pluginCore.getPluginInfoMap() == null) {
            return pluginsById;
        }
        for (PluginVO pluginVO : pluginCore.getPluginInfoMap().values()) {
            if (pluginVO == null || pluginVO.getPlugin() == null || pluginVO.getPlugin().getId() == null) {
                continue;
            }
            pluginsById.put(pluginVO.getPlugin().getId(), pluginVO.getPlugin());
        }
        return pluginsById;
    }

    private boolean configuredNotificationProvider(NotificationSetting setting, String channel, PluginCapability provider) {
        if (setting == null || setting.getDefaultProviders() == null) {
            return false;
        }
        NotificationProviderSetting configured = setting.getDefaultProviders().get(channel);
        return configured != null
                && Objects.equals(configured.getPluginId(), provider.getPluginId())
                && Objects.equals(configured.getCapabilityKey(), provider.getKey());
    }

    private String pluginDisplayName(Plugin plugin) {
        if (plugin == null || plugin.getName() == null || plugin.getName().trim().isEmpty()) {
            return "未命名插件";
        }
        return plugin.getName();
    }

    private String pluginPreviewImageBase64(Plugin plugin) {
        if (plugin == null || plugin.getPreviewImageBase64() == null || plugin.getPreviewImageBase64().trim().isEmpty()) {
            return "";
        }
        return plugin.getPreviewImageBase64();
    }

    @Override
    public void schedulerQuery(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            SchedulerQueryRequest request = new Gson().fromJson(msgPacket.getDataStr(), SchedulerQueryRequest.class);
            SchedulerQueryResult result = new SchedulerQueryService(
                    new AutomationStore(kvStore),
                    new CapabilityStore(kvStore)
            ).query(session.getPlugin(), request);
            session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                    result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    @Override
    public void schedulerUpdate(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            WebsiteRuntimeKvStore kvStore = new WebsiteRuntimeKvStore();
            SchedulerUpdateRequest request = new Gson().fromJson(msgPacket.getDataStr(), SchedulerUpdateRequest.class);
            SchedulerUpdateResult result = new SchedulerUpdateService(
                    new AutomationStore(kvStore),
                    new CapabilityStore(kvStore),
                    new BasicCronParser()
            ).update(session.getPlugin(), request, null);
            session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                    result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    private void sendCapabilityResult(IOSession session, MsgPacket msgPacket, CapabilityInvokeResult result) {
        session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
    }

    private CapabilityInvokeResult capabilityError(String message) {
        CapabilityInvokeResult result = new CapabilityInvokeResult();
        result.setSuccess(false);
        result.setErrorMessage(message);
        return result;
    }

    @Override
    public void getFile(IOSession session, MsgPacket msgPacket) {

    }

    private PluginRuntimeStateService runtimeStateService(KvRepository kvStore, IOSession session) {
        return new PluginRuntimeStateService(
                new PluginRuntimeStateStore(kvStore),
                new DefaultPluginRuntimeStarter(),
                PluginSessions.runtimeInstanceId(session)
        );
    }

    private PluginBootstrapService pluginBootstrap() {
        return PluginRuntimeBridge.pluginBootstrap();
    }

    private String toWebSiteName(IOSession session, String key) {
        return session.getPlugin().getShortName() + "_" + key;
    }

    @Override
    public void loadWebSite(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            PluginTransportModels.WebsiteLoadRequest request =
                    new Gson().fromJson(msgPacket.getDataStr(), PluginTransportModels.WebsiteLoadRequest.class);
            String[] rawKeys = request.rawKeys();
            try {
                Map<String, Object> webSiteByNameIn = new WebSiteDAO().getWebSiteByNameIn(Arrays.asList(Arrays.stream(rawKeys).map(e -> {
                    return toWebSiteName(session, e);
                }).toArray(String[]::new)));
                Map<String, Object> resultMap = new LinkedHashMap<>();
                for (String rawKey : rawKeys) {
                    resultMap.put(rawKey, webSiteByNameIn.get(toWebSiteName(session, rawKey)));
                }
                session.sendJsonMsg(resultMap, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("load website failed"), e);
            }
        }
    }

    @Override
    public void setWebSite(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            Gson gson = new Gson();
            Map<String, Object> map = gson.fromJson(msgPacket.getDataStr(), Map.class);
            PluginTransportModels.WebsiteSyncOptions syncOptions =
                    gson.fromJson(msgPacket.getDataStr(), PluginTransportModels.WebsiteSyncOptions.class);

            Map<String, Object> resultMap = new HashMap<>();
            try {
                WebSiteDAO webSiteDAO = new WebSiteDAO();
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    values.put(toWebSiteName(session, entry.getKey()), entry.getValue());
                }
                Map<String, Boolean> results = webSiteDAO.saveOrUpdateChanged(values);
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    Map<String, Object> result = new HashMap<>();
                    result.put("result", Boolean.TRUE.equals(results.get(toWebSiteName(session, entry.getKey()))));
                    resultMap.put(entry.getKey(), result);
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("set website failed"), e);
            }
            if (syncOptions != null && syncOptions.hasSyncTemplate()) {
                if (syncOptions.isSyncTemplateEnabled()) {
                    String accessHost = syncOptions.getHost();
                    String accessFolder = syncOptions.getFolder();
                    if (accessHost != null && accessFolder != null) {
                        accessHost = accessFolder + "/" + accessFolder;
                    }
                    if (accessHost != null) {
                        try {
                            new WebSiteDAO().saveOrUpdateChanged("staticResourceHost", accessHost);
                        } catch (SQLException e) {
                            LOGGER.log(Level.SEVERE, PluginLogContext.prefix("sync static resource host failed"), e);
                        }
                    }
                } else {
                    try {
                        new WebSiteDAO().saveOrUpdateChanged("staticResourceHost", "");
                    } catch (SQLException e) {
                        LOGGER.log(Level.SEVERE, PluginLogContext.prefix("clear static resource host failed"), e);
                    }
                }
            }
            session.sendJsonMsg(resultMap, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            try {
                doRefreshCache();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void httpMethod(final IOSession session, final MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            if (msgPacket.getStatus() == MsgPacketStatus.SEND_REQUEST) {
                try {
                    BaseHttpRequestInfo httpRequestInfo = new Gson().fromJson(msgPacket.getDataStr(), BaseHttpRequestInfo.class);
                    HttpResponseInfo httpResponseInfo = HttpUtils.doRequest(httpRequestInfo);
                    session.sendJsonMsg(httpResponseInfo, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                } catch (Exception e) {
                    HttpResponseInfo httpResponseInfo = new HttpResponseInfo();
                    httpResponseInfo.setStatusCode(500);
                    httpResponseInfo.setHeader(new HashMap<>());
                    httpResponseInfo.setResponseBody(LoggerUtil.recordStackTraceMsg(e).getBytes(StandardCharsets.UTF_8));
                    session.sendJsonMsg(httpResponseInfo, msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                }
            }
        }
    }

    @Override
    public void deleteComment(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            Comment comment = new Gson().fromJson(msgPacket.getDataStr(), Comment.class);
            if (comment.getPostId() != null) {
                try {
                    boolean result = new CommentDAO().set("postId", comment.getPostId()).delete();
                    session.sendJsonMsg(PluginTransportModels.OperationResult.success(result),
                            msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, PluginLogContext.prefix("delete comment error"), e);
                    session.sendJsonMsg(PluginTransportModels.OperationResult.success(false),
                            msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
                }
            }
        }
    }

    @Override
    public void addComment(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            Comment comment = new Gson().fromJson(msgPacket.getDataStr(), Comment.class);
            try {
                boolean result = new CommentDAO().set("userHome", comment.getHome()).set("userMail", comment.getMail()).set("userIp", comment.getIp()).set("userName", comment.getName()).set("logId", comment.getLogId()).set("postId", comment.getPostId()).set("userComment", comment.getContent()).set("commTime", comment.getCreatedTime()).set("td", new Date()).set("header", comment.getHeadPortrait()).set("hide", 1).save();

                session.sendJsonMsg(PluginTransportModels.OperationResult.success(result),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("save comment error"), e);
                session.sendJsonMsg(PluginTransportModels.OperationResult.success(false),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
            }
        }
    }

    @Override
    public void plugin(IOSession session, MsgPacket msgPacket) {

    }

    @Override
    public void getDbProperties(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            session.sendJsonMsg(new DbPropertiesResponse(pluginConfig().getDbPropertiesFile().toString()),
                    msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
        }
    }

    @Override
    public void attachment(IOSession session, MsgPacket msgPacket) {

    }


    @Override
    public void loadPublicInfo(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            try {
                session.sendJsonMsg(PublicInfoLoader.loadPublicInfo(), msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("load public info failed"), e);
            }
        }
    }

    @Override
    public void getCurrentTemplate(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            try {
                String templatePath = (String) new WebSiteDAO().queryValueByName("template");
                TemplatePath template = new TemplatePath();
                template.setValue(templatePath);
                session.sendJsonMsg(template, ActionType.CURRENT_TEMPLATE.name(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("load current template failed"), e);
            }
        }
    }

    @Override
    public void getBlogRuntimePath(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            session.sendJsonMsg(pluginConfig().getBlogRunTime(), ActionType.BLOG_RUN_TIME.name(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
        }
    }

    private PluginConfig pluginConfig() {
        return PluginRuntimeBridge.pluginConfig();
    }

    public String getPlainSearchTxt(String content) {
        return Jsoup.parse(content).body().text();
    }

    @Override
    public void createArticle(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            CreateArticleRequest createArticleRequest = new Gson().fromJson(msgPacket.getDataStr(), CreateArticleRequest.class);
            Integer typeId = 0;
            if (createArticleRequest.getTypeId() > 0) {
                typeId = createArticleRequest.getTypeId();
            } else {
                try {
                    typeId = (Integer) new TypeDAO().findByName(createArticleRequest.getType());
                    if (typeId == null) {
                        new TypeDAO().set("typeName", createArticleRequest.getType()).set("alias", createArticleRequest.getType()).save();
                        //query again;
                        typeId = (Integer) new TypeDAO().findByName(createArticleRequest.getType());

                    }
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, PluginLogContext.prefix("Create article type failed"), e);
                }
            }
            String alias = createArticleRequest.getAlias();

            if (alias == null) {
                try {
                    alias = new ArticleDAO().queryFirstObj("select max(logId) from log") + "";
                } catch (SQLException e) {
                    LOGGER.log(Level.SEVERE, PluginLogContext.prefix("Build article alias failed"), e);
                }
            }
            try {
                Integer logId = (Integer) new ArticleDAO().queryFirstObj("select logId from log where alias = ?", alias);
                DAO articleDAO = new ArticleDAO().set("releaseTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(createArticleRequest.getReleaseDate())).set("last_update_date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(createArticleRequest.getReleaseDate())).set("content", createArticleRequest.getContent()).set("title", createArticleRequest.getTitle()).set("markdown", createArticleRequest.getMarkdown()).set("digest", createArticleRequest.getDigest()).set("typeId", typeId).set("private", createArticleRequest.is_private()).set("rubbish", createArticleRequest.isRubbish()).set("alias", alias).set("plain_content", getPlainSearchTxt(createArticleRequest.getContent())).set("thumbnail", createArticleRequest.getThumbnail()).set("canComment", createArticleRequest.isCanComment()).set("recommended", createArticleRequest.isRecommended()).set("keywords", createArticleRequest.getKeywords()).set("editor_type", createArticleRequest.getEditorType()).set("userId", createArticleRequest.getUserId());
                if (logId == null) {
                    try {
                        boolean result = articleDAO.save();
                        doRefreshCache();
                        session.sendJsonMsg(PluginTransportModels.OperationResult.success(result),
                                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, PluginLogContext.prefix("save article error"), e);
                        session.sendJsonMsg(PluginTransportModels.OperationResult.success(false),
                                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
                    }
                } else {
                    try {
                        Map<String, Object> cond = new HashMap<>();
                        cond.put("logId", logId);
                        boolean result = articleDAO.update(cond);
                        doRefreshCache();
                        session.sendJsonMsg(PluginTransportModels.OperationResult.success(result),
                                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
                    } catch (Exception e) {
                        LOGGER.log(Level.SEVERE, PluginLogContext.prefix("update article error"), e);
                        session.sendJsonMsg(PluginTransportModels.OperationResult.success(false),
                                msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
                    }
                }
            } catch (SQLException e) {
                LOGGER.log(Level.SEVERE, PluginLogContext.prefix("Create article failed"), e);
            }
        }
    }

    @Override
    public void getArticleExtension(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            ArticleExtensionGetRequest request =
                    new Gson().fromJson(msgPacket.getDataStr(), ArticleExtensionGetRequest.class);
            ArticleExtensionResult result;
            if (request == null || request.getArticleId() == null) {
                result = ArticleExtensionResult.error("articleId is required");
            } else {
                result = new ArticleExtensionRepository().get(
                        request.getArticleId(), articleExtensionNamespace(session));
            }
            sendArticleExtensionResult(session, msgPacket, result);
        }
    }

    @Override
    public void setArticleExtension(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            ArticleExtensionSetRequest request =
                    new Gson().fromJson(msgPacket.getDataStr(), ArticleExtensionSetRequest.class);
            ArticleExtensionResult result = new ArticleExtensionRepository()
                    .set(articleExtensionNamespace(session), request);
            sendArticleExtensionResult(session, msgPacket, result);
        }
    }

    @Override
    public void queryArticleExtension(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            ArticleExtensionQueryRequest request =
                    new Gson().fromJson(msgPacket.getDataStr(), ArticleExtensionQueryRequest.class);
            ArticleExtensionQueryResult result = new ArticleExtensionRepository()
                    .query(articleExtensionNamespace(session), request);
            session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                    result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
        }
    }

    private void sendArticleExtensionResult(IOSession session, MsgPacket msgPacket,
                                            ArticleExtensionResult result) {
        session.sendJsonMsg(result, msgPacket.getMethodStr(), msgPacket.getMsgId(),
                result.isSuccess() ? MsgPacketStatus.RESPONSE_SUCCESS : MsgPacketStatus.RESPONSE_ERROR);
    }

    private String articleExtensionNamespace(IOSession session) {
        if (session == null || session.getPlugin() == null) {
            return "";
        }
        return session.getPlugin().getShortName();
    }

    @Override
    public void refreshCache(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            try {
                doRefreshCache();
                session.sendJsonMsg(PluginTransportModels.OperationResult.success(true),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (Exception e) {
                session.sendJsonMsg(new PluginTransportModels.EmptyResponse(),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
            }
        }
    }

    @Override
    public void articleVisitViewCountAddOne(IOSession session, MsgPacket msgPacket) {
        try (PluginLogContext.Scope ignored = PluginLogContext.open(session)) {
            PluginTransportModels.ArticleVisitRequest request =
                    new Gson().fromJson(msgPacket.getDataStr(), PluginTransportModels.ArticleVisitRequest.class);
            String alias = request.getAlias();
            try {
                new DAO().execute("update log set click = click + 1  where logId=? or alias=?", alias, alias);
                session.sendJsonMsg(PluginTransportModels.OperationResult.success(true),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_SUCCESS);
            } catch (SQLException e) {
                session.sendJsonMsg(new PluginTransportModels.EmptyResponse(),
                        msgPacket.getMethodStr(), msgPacket.getMsgId(), MsgPacketStatus.RESPONSE_ERROR);
            }
        }
    }

    @Override
    public void listComment(IOSession session, MsgPacket msgPacket) {

    }
}
