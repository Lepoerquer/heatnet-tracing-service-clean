package ru.heatnet.api.dto;

import java.util.Map;

public class ServiceInfoResponse {

    private String service;
    private String version;
    private String profile;
    private String configDir;
    private String dataDir;
    private boolean configLoaded;
    private Map<String, Object> referenceTables;
    private Map<String, String> links;

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
    }

    public String getConfigDir() {
        return configDir;
    }

    public void setConfigDir(String configDir) {
        this.configDir = configDir;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public boolean isConfigLoaded() {
        return configLoaded;
    }

    public void setConfigLoaded(boolean configLoaded) {
        this.configLoaded = configLoaded;
    }

    public Map<String, Object> getReferenceTables() {
        return referenceTables;
    }

    public void setReferenceTables(Map<String, Object> referenceTables) {
        this.referenceTables = referenceTables;
    }

    public Map<String, String> getLinks() {
        return links;
    }

    public void setLinks(Map<String, String> links) {
        this.links = links;
    }
}
