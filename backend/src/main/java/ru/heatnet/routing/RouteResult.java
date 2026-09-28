package ru.heatnet.routing;

import java.util.Collections;
import java.util.List;

import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.model.SpecialSection;

/**
 * Результат M3: геометрия трассы в EPSG:32637 или NOT_FOUND без Exception.
 */
public final class RouteResult {

    private final RouteStatus status;
    private final LineString pathUtm;
    private final double lengthM;
    private final List<SpecialSection> specialSections;

    private RouteResult(RouteStatus status, LineString pathUtm, double lengthM,
                        List<SpecialSection> specialSections) {
        this.status = status;
        this.pathUtm = pathUtm;
        this.lengthM = lengthM;
        this.specialSections = specialSections == null
                ? Collections.<SpecialSection>emptyList()
                : Collections.unmodifiableList(specialSections);
    }

    public static RouteResult found(LineString pathUtm, List<SpecialSection> specialSections) {
        if (pathUtm == null || pathUtm.isEmpty()) {
            return notFound();
        }
        return new RouteResult(RouteStatus.FOUND, pathUtm, pathUtm.getLength(), specialSections);
    }

    public static RouteResult notFound() {
        return new RouteResult(RouteStatus.NOT_FOUND, null, 0.0, Collections.<SpecialSection>emptyList());
    }

    public RouteStatus getStatus() {
        return status;
    }

    public LineString getPathUtm() {
        return pathUtm;
    }

    public double getLengthM() {
        return lengthM;
    }

    public List<SpecialSection> getSpecialSections() {
        return specialSections;
    }

    public boolean isFound() {
        return status == RouteStatus.FOUND;
    }
}
