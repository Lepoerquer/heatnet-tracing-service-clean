package ru.heatnet.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import ru.heatnet.api.dto.ServiceInfoResponse;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.config.HeatnetProperties;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Service", description = "Служебные эндпоинты (M0)")
public class ServiceInfoController {

    private final HeatnetProperties properties;
    private final ReferenceData referenceData;
    private final Environment environment;

    @Value("${heatnet.service-version:0.1.0-SNAPSHOT}")
    private String serviceVersion;

    public ServiceInfoController(
            HeatnetProperties properties,
            ReferenceData referenceData,
            Environment environment) {
        this.properties = properties;
        this.referenceData = referenceData;
        this.environment = environment;
    }

    @GetMapping("/info")
    @Operation(summary = "Информация о сервисе и загруженных справочниках")
    public ServiceInfoResponse info() {
        ServiceInfoResponse response = new ServiceInfoResponse();
        response.setService("heatnet-tracing-service");
        response.setVersion(serviceVersion);
        response.setProfile(String.join(",", environment.getActiveProfiles()));
        if (response.getProfile().isEmpty()) {
            response.setProfile("default");
        }
        response.setConfigDir(properties.getConfigDir());
        response.setDataDir(properties.getDataDir());
        response.setConfigLoaded(true);

        Map<String, Object> tables = new LinkedHashMap<String, Object>();
        tables.put("diameters", referenceData.getDiameters().size());
        tables.put("gabarits", referenceData.getGabarits().all().size());
        tables.put("restrictionRules", referenceData.getRules().getRestrictions().size());
        response.setReferenceTables(tables);

        Map<String, String> links = new LinkedHashMap<String, String>();
        links.put("health", "/actuator/health");
        links.put("swagger", "/swagger-ui.html");
        links.put("openapi", "/api-docs");
        response.setLinks(links);
        return response;
    }
}
