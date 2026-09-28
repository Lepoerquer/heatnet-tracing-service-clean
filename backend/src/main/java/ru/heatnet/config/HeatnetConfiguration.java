package ru.heatnet.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.PostConstruct;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.ReferenceDataLoader;

@Configuration
public class HeatnetConfiguration {

    private final HeatnetProperties properties;

    public HeatnetConfiguration(HeatnetProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initDirectories() {
        System.setProperty(ReferenceDataLoader.CONFIG_DIR_PROPERTY, properties.getConfigDir());
        Path dataDir = Paths.get(properties.getDataDir());
        try {
            Files.createDirectories(dataDir);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось создать каталог данных: " + dataDir, ex);
        }
    }

    @Bean
    public ReferenceData referenceData() {
        return ReferenceDataLoader.load(Paths.get(properties.getConfigDir()));
    }
}
