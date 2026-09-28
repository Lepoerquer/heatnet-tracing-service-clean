package ru.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import ru.heatnet.config.HeatnetProperties;

@SpringBootApplication
@EnableConfigurationProperties(HeatnetProperties.class)
public class HeatnetApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HeatnetApplication.class);
        boolean cli = false;
        for (String arg : args) {
            if (arg != null && arg.startsWith("--heatnet.cli.input")) {
                cli = true;
                break;
            }
        }
        if (cli) {
            // AUDIT-12 (Claude, 24.09): режим командной строки (ru.heatnet.cli.ContestCliRunner) — без веб-сервера,
            // процесс завершается после расчёта.
            app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            int code;
            try {
                code = SpringApplication.exit(app.run(args));
            } catch (RuntimeException ex) {
                ex.printStackTrace();
                code = 1;
            }
            System.exit(code);
        }
        app.run(args);
    }
}
