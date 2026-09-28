package ru.heatnet.export;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import ru.heatnet.ContestDatasetPaths;
import ru.heatnet.HeatnetApplication;
import ru.heatnet.jobs.CalculationPipeline;

/**
 * Одноразовый запуск: конкурсный GeoJSON → data/result.geojson.
 * Не входит в обычный {@code mvn test}.
 */
public final class WriteResultMain {

    private WriteResultMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dataset = ContestDatasetPaths.require();
        Path out = args.length > 0
                ? Path.of(args[0])
                : Path.of("data", "result.geojson");
        if (!out.isAbsolute()) {
            out = Path.of("").toAbsolutePath().resolve(out).normalize();
        }
        Files.createDirectories(out.getParent());

        SpringApplication app = new SpringApplication(HeatnetApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("local");
        try (ConfigurableApplicationContext ctx = app.run(args)) {
            CalculationPipeline pipeline = ctx.getBean(CalculationPipeline.class);
            System.out.println("Вход: " + dataset.toAbsolutePath());
            System.out.println("Выход: " + out.toAbsolutePath());
            try (InputStream in = Files.newInputStream(dataset);
                 OutputStream output = Files.newOutputStream(out)) {
                pipeline.run(in, output, false, (stage, progress) ->
                        System.out.println(stage + " " + progress + "%"));
            }
            System.out.println("Готово: " + out.toAbsolutePath() + " (" + Files.size(out) + " байт)");
        }
    }
}
