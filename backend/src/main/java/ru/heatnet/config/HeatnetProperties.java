package ru.heatnet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heatnet")
public class HeatnetProperties {

    /** Каталог с diameters.yaml, rules.yaml и т.д. */
    private String configDir = "../config";

    /** Каталог загруженных GeoJSON и результатов jobs. */
    private String dataDir = "../data";

    /** Допуск snap концов труб к камерам/источнику/другим концам, м (EPSG:32637). */
    private double ingestSnapToleranceM = 1.0;

    /** Допуск для отчёта расхождений геометрии камеры и концов труб, м. */
    private double ingestChamberSnapReportToleranceM = 0.5;

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

    public double getIngestSnapToleranceM() {
        return ingestSnapToleranceM;
    }

    public void setIngestSnapToleranceM(double ingestSnapToleranceM) {
        this.ingestSnapToleranceM = ingestSnapToleranceM;
    }

    public double getIngestChamberSnapReportToleranceM() {
        return ingestChamberSnapReportToleranceM;
    }

    public void setIngestChamberSnapReportToleranceM(double ingestChamberSnapReportToleranceM) {
        this.ingestChamberSnapReportToleranceM = ingestChamberSnapReportToleranceM;
    }
}
