package com.zrlog.plugincore.server.runtime.scheduler;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

public class SchedulerSetting {

    private Boolean enabled = Boolean.TRUE;
    private Boolean hostDriven = Boolean.FALSE;
    private String externalHost;
    private List<SchedulerProviderSetting> providers = new ArrayList<>();

    public Boolean getHostDriven() { return Boolean.TRUE.equals(hostDriven); }

    public void setHostDriven(Boolean hostDriven) { this.hostDriven = Boolean.TRUE.equals(hostDriven); }

    public Boolean getEnabled() {
        return Boolean.TRUE;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = Boolean.TRUE;
    }

    public String getExternalHost() {
        return externalHost;
    }

    public void setExternalHost(String externalHost) {
        this.externalHost = externalHost;
    }

    public List<SchedulerProviderSetting> getProviders() {
        return providers;
    }

    public void setProviders(List<SchedulerProviderSetting> providers) {
        this.providers = providers;
    }

    public SchedulerProviderSetting ensureDefaultProvider() {
        if (providers == null) {
            providers = new ArrayList<>();
        }
        for (SchedulerProviderSetting provider : providers) {
            if (Objects.equals("default", provider.getId())) {
                if (provider.getSecret() == null || provider.getSecret().isEmpty()) {
                    provider.setSecret(generateSecret());
                }
                if (provider.getEnabled() == null) {
                    provider.setEnabled(Boolean.FALSE);
                }
                return provider;
            }
        }
        SchedulerProviderSetting provider = new SchedulerProviderSetting();
        provider.setId("default");
        provider.setEnabled(Boolean.FALSE);
        provider.setSecret(generateSecret());
        providers.add(provider);
        return provider;
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
