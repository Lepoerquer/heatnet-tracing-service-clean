package ru.heatnet.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

@Configuration
public class OpenApiConfiguration {

    @Bean
    public OpenAPI heatnetOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Heatnet Tracing Service API")
                        .description("ЛЦТ-2026: сервис моделирования трассировки тепловых сетей")
                        .version("0.1.0-SNAPSHOT"));
    }
}
