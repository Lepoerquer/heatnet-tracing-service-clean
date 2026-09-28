package ru.heatnet.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.config.HeatnetProperties;

/**
 * Оркестрация M1: потоковый парсинг, валидация, дерево существующей сети, отчёт.
 */
@Service
public class IngestService {

    private final StreamingGeoJsonReader reader;
    private final SchemaValidator schemaValidator;
    private final NetworkTreeBuilder networkTreeBuilder;
    private final ChamberIncidence chamberIncidence;
    private final HeatnetProperties properties;

    public IngestService(StreamingGeoJsonReader reader,
                         SchemaValidator schemaValidator,
                         NetworkTreeBuilder networkTreeBuilder,
                         ChamberIncidence chamberIncidence,
                         HeatnetProperties properties) {
        this.reader = reader;
        this.schemaValidator = schemaValidator;
        this.networkTreeBuilder = networkTreeBuilder;
        this.chamberIncidence = chamberIncidence;
        this.properties = properties;
    }

    /** Порог размера файла, с которого включается двухпроходное чтение с отбором по району, байт. */
    public static final long TWO_PASS_THRESHOLD_BYTES = 256L * 1024 * 1024;
    /** Запас вокруг района расчёта при отборе ограничений (охват графа планировщика + страховка), м. */
    static final double AREA_MARGIN_M = 1000.0;

    /**
     * AUDIT-24.09 (Claude). ТЗ 3.2: файл до 3 ГБ «с использованием потоковой обработки и временного
     * дискового хранилища, не загружая файл целиком в оперативную память». Раньше все объекты файла
     * удерживались в памяти (для файла в гигабайты — OutOfMemoryError при -Xmx6g). Для больших файлов
     * теперь два потоковых прохода: 1) сеть, камеры, источник, точки ОКС; 2) только те ограничения,
     * которые попадают в район расчёта (точки ОКС, ближайшие к ним трубы, сеть при охвате ≤ 3 км,
     * плюс 1 км запаса — больше, чем охватывает граф планировщика). Прочие ограничения лишь
     * учитываются в отчёте. Малые файлы читаются как раньше, одним проходом.
     */
    public IngestResult ingest(java.nio.file.Path file) throws IOException {
        long size = java.nio.file.Files.size(file);
        if (size < TWO_PASS_THRESHOLD_BYTES) {
            try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file), 1 << 16)) {
                return ingest(in);
            }
        }
        IngestReport report = new IngestReport();
        List<RawFeature> first;
        try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file), 1 << 16)) {
            first = reader.read(in, report, k -> k != RawFeature.Kind.RESTRICTION, null);
        }
        org.locationtech.jts.geom.Envelope areaWgs = areaOfInterestWgs(first);
        report.info("INGEST_TWO_PASS", null, "Файл " + (size >> 20) + " МБ: ограничения отобраны по району расчёта "
                + (areaWgs == null ? "(нет ОКС — все)" : areaWgs.toString()));
        List<RawFeature> second;
        try (InputStream in = new java.io.BufferedInputStream(java.nio.file.Files.newInputStream(file), 1 << 16)) {
            second = reader.read(in, report, k -> k == RawFeature.Kind.RESTRICTION,
                    f -> areaWgs == null || f.getGeometryWgs84() == null
                            || f.getGeometryWgs84().getEnvelopeInternal().intersects(areaWgs));
        }
        List<RawFeature> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return build(all, report);
    }

    /** Район расчёта в WGS84: ОКС, ближайшие к ним точки сети; вся сеть, если её охват ≤ 3 км; + запас. */
    private org.locationtech.jts.geom.Envelope areaOfInterestWgs(List<RawFeature> features) {
        List<org.locationtech.jts.geom.Coordinate> oks = new ArrayList<>();
        List<org.locationtech.jts.geom.LineString> pipes = new ArrayList<>();
        ru.heatnet.geo.ProjectionService proj = new ru.heatnet.geo.ProjectionService();
        for (RawFeature f : features) {
            if (f.getGeometryWgs84() == null) {
                continue;
            }
            if (f.getKind() == RawFeature.Kind.OKS_CONNECTION_POINT) {
                org.locationtech.jts.geom.Coordinate c = f.getGeometryWgs84().getCoordinate();
                oks.add(proj.toUtm(c.x, c.y));
            } else if (f.getKind() == RawFeature.Kind.HEAT_NETWORK && f.getLineCoordinatesWgs84() != null) {
                pipes.add(proj.lineToUtm(f.getLineCoordinatesWgs84()));
            }
        }
        if (oks.isEmpty()) {
            return null;
        }
        org.locationtech.jts.geom.Envelope focus = new org.locationtech.jts.geom.Envelope();
        org.locationtech.jts.geom.GeometryFactory gf = proj.utmFactory();
        for (org.locationtech.jts.geom.Coordinate c : oks) {
            focus.expandToInclude(c);
            double best = Double.POSITIVE_INFINITY;
            org.locationtech.jts.geom.Coordinate bestC = null;
            for (org.locationtech.jts.geom.LineString l : pipes) {
                org.locationtech.jts.geom.Coordinate[] near =
                        org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(l, gf.createPoint(c));
                double d = near[0].distance(c);
                if (d < best) {
                    best = d;
                    bestC = near[0];
                }
            }
            if (bestC != null) {
                focus.expandToInclude(bestC);
            }
        }
        org.locationtech.jts.geom.Envelope full = new org.locationtech.jts.geom.Envelope(focus);
        for (org.locationtech.jts.geom.LineString l : pipes) {
            full.expandToInclude(l.getEnvelopeInternal());
        }
        org.locationtech.jts.geom.Envelope chosen = Math.hypot(full.getWidth(), full.getHeight()) <= 3000.0
                ? full : focus;
        chosen.expandBy(AREA_MARGIN_M);
        org.locationtech.jts.geom.Coordinate a = proj.toWgs(chosen.getMinX(), chosen.getMinY());
        org.locationtech.jts.geom.Coordinate b = proj.toWgs(chosen.getMaxX(), chosen.getMaxY());
        org.locationtech.jts.geom.Coordinate c2 = proj.toWgs(chosen.getMinX(), chosen.getMaxY());
        org.locationtech.jts.geom.Coordinate d2 = proj.toWgs(chosen.getMaxX(), chosen.getMinY());
        org.locationtech.jts.geom.Envelope wgs = new org.locationtech.jts.geom.Envelope(a);
        wgs.expandToInclude(b);
        wgs.expandToInclude(c2);
        wgs.expandToInclude(d2);
        return wgs;
    }

    public IngestResult ingest(InputStream geoJsonStream) throws IOException {
        IngestReport report = new IngestReport();
        List<RawFeature> parsed = reader.read(geoJsonStream, report);
        return build(parsed, report);
    }

    private IngestResult build(List<RawFeature> parsed, IngestReport report) {

        List<RawFeature> accepted = new ArrayList<>();
        List<OksConnectionPoint> oksPoints = new ArrayList<>();
        Map<String, Geometry> restrictions = new LinkedHashMap<>();
        List<RawFeature> networkFeatures = new ArrayList<>();

        for (RawFeature feature : parsed) {
            SchemaValidator.ValidationOutcome outcome = schemaValidator.validate(feature, report);
            if (!outcome.isAccepted()) {
                report.incrementSkipped();
                continue;
            }
            RawFeature valid = outcome.getFeature();
            accepted.add(valid);
            switch (valid.getKind()) {
                case OKS_CONNECTION_POINT:
                    oksPoints.add(toOksPoint(valid));
                    break;
                case RESTRICTION:
                    restrictions.put(valid.getId(), valid.getGeometryWgs84());
                    break;
                case HEAT_NETWORK:
                case HEAT_CHAMBER:
                case SOURCE:
                    networkFeatures.add(valid);
                    break;
                case OKS_FUTURE:
                    break;
                default:
                    break;
            }
        }

        ExistingNetwork network = networkTreeBuilder.build(
                networkFeatures,
                properties.getIngestSnapToleranceM(),
                report);

        if (!network.getSegments().isEmpty() || !network.getChambers().isEmpty()) {
            chamberIncidence.analyze(
                    network,
                    networkFeatures,
                    properties.getIngestChamberSnapReportToleranceM(),
                    properties.getIngestSnapToleranceM(),
                    report);
        }

        return new IngestResult(report, network, oksPoints, restrictions, accepted);
    }

    private OksConnectionPoint toOksPoint(RawFeature feature) {
        Object flow = feature.getProperties().get("flow_tph");
        double flowTph = flow instanceof Number ? ((Number) flow).doubleValue()
                : RawFeature.parseDoubleLenient(flow);
        return new OksConnectionPoint(feature.getId(), flowTph, feature.isNumericId());
    }
}
