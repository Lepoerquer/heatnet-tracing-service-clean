package ru.heatnet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Расположение конкурсного GeoJSON: dataset/dataset_updated.geojson. */
public final class ContestDatasetPaths {

    private static final String[][] CANDIDATES = {
            {"dataset", "dataset_updated.geojson"},
            {"..", "dataset", "dataset_updated.geojson"},
    };

    private ContestDatasetPaths() {
    }

    public static Optional<Path> find() {
        for (String[] parts : CANDIDATES) {
            Path path = Path.of(parts[0], Arrays.copyOfRange(parts, 1, parts.length)).normalize();
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    public static Path require() {
        return find().orElseThrow(() -> new IllegalStateException(
                "Конкурсный dataset не найден. Ожидается dataset/dataset_updated.geojson "
                        + "относительно корня репозитория или backend/."));
    }

    /** Для JUnit {@code @EnabledIf}. */
    @SuppressWarnings("unused")
    public static boolean isPresent() {
        return find().isPresent();
    }
}
