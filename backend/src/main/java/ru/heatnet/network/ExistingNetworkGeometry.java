package ru.heatnet.network;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;

import ru.heatnet.calc.model.ExistingNetwork;
import ru.heatnet.calc.model.ExistingSegment;
import ru.heatnet.geo.GeoUtils;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.RawFeature;

/**
 * Геометрия существующей сети в EPSG:32637 для M4 (проекции врезок, расстояния).
 * Топология — {@link ExistingNetwork}; координаты — из ingest {@link RawFeature}.
 */
public final class ExistingNetworkGeometry {

    private final Map<String, LineString> segmentLines;
    private final Map<String, Coordinate> chamberPoints;
    private final Map<String, Coordinate> sourcePoints;
    private final Map<String, Coordinate> upstreamEndpointBySegment;
    private final Map<String, Coordinate> downstreamEndpointBySegment;

    public ExistingNetworkGeometry(Map<String, LineString> segmentLines,
                                   Map<String, Coordinate> chamberPoints,
                                   Map<String, Coordinate> sourcePoints,
                                   Map<String, Coordinate> upstreamEndpointBySegment,
                                   Map<String, Coordinate> downstreamEndpointBySegment) {
        this.segmentLines = Collections.unmodifiableMap(new LinkedHashMap<>(segmentLines));
        this.chamberPoints = Collections.unmodifiableMap(new LinkedHashMap<>(chamberPoints));
        this.sourcePoints = Collections.unmodifiableMap(new LinkedHashMap<>(sourcePoints));
        this.upstreamEndpointBySegment = Collections.unmodifiableMap(new LinkedHashMap<>(upstreamEndpointBySegment));
        this.downstreamEndpointBySegment = Collections.unmodifiableMap(new LinkedHashMap<>(downstreamEndpointBySegment));
    }

    public static ExistingNetworkGeometry fromIngest(Collection<RawFeature> features,
                                                     ExistingNetwork network,
                                                     ProjectionService projection) {
        Map<String, LineString> lines = new HashMap<>();
        Map<String, Coordinate> chambers = new HashMap<>();
        Map<String, Coordinate> sources = new HashMap<>();

        for (RawFeature feature : features) {
            switch (feature.getKind()) {
                case HEAT_NETWORK:
                    Coordinate[] wgs = feature.getLineCoordinatesWgs84();
                    if (wgs != null && wgs.length >= 2) {
                        lines.put(feature.getId(), projection.lineToUtm(wgs));
                    }
                    break;
                case HEAT_CHAMBER:
                    Coordinate c = feature.getGeometryWgs84().getCoordinate();
                    chambers.put(feature.getId(), projection.toUtm(c.x, c.y));
                    break;
                case SOURCE:
                    Coordinate s = feature.getGeometryWgs84().getCoordinate();
                    sources.put(feature.getId(), projection.toUtm(s.x, s.y));
                    break;
                default:
                    break;
            }
        }

        Map<String, Coordinate> up = new HashMap<>();
        Map<String, Coordinate> down = new HashMap<>();
        for (ExistingSegment segment : network.getSegments().values()) {
            LineString line = lines.get(segment.getId());
            if (line == null || line.getNumPoints() < 2) {
                continue;
            }
            Coordinate upstreamConn = resolveUpstreamConnection(network, chambers, sources, lines, up, down,
                    segment.getUpstreamObjectId());
            Coordinate start = line.getCoordinateN(0);
            Coordinate end = line.getCoordinateN(line.getNumPoints() - 1);
            if (upstreamConn != null) {
                if (GeoUtils.distanceMeters(start, upstreamConn) <= GeoUtils.distanceMeters(end, upstreamConn)) {
                    up.put(segment.getId(), start);
                    down.put(segment.getId(), end);
                } else {
                    up.put(segment.getId(), end);
                    down.put(segment.getId(), start);
                }
            } else {
                up.put(segment.getId(), start);
                down.put(segment.getId(), end);
            }
        }
        return new ExistingNetworkGeometry(lines, chambers, sources, up, down);
    }

    private static Coordinate resolveUpstreamConnection(ExistingNetwork network,
                                                        Map<String, Coordinate> chambers,
                                                        Map<String, Coordinate> sources,
                                                        Map<String, LineString> lines,
                                                        Map<String, Coordinate> up,
                                                        Map<String, Coordinate> down,
                                                        String upstreamId) {
        if (upstreamId == null) {
            return null;
        }
        if (network.isSource(upstreamId)) {
            return sources.get(upstreamId);
        }
        if (network.isChamber(upstreamId)) {
            return chambers.get(upstreamId);
        }
        if (network.isSegment(upstreamId)) {
            Coordinate cachedDown = down.get(upstreamId);
            if (cachedDown != null) {
                return cachedDown;
            }
            LineString parent = lines.get(upstreamId);
            if (parent == null) {
                return null;
            }
            ExistingSegment parentSeg = network.segment(upstreamId);
            Coordinate parentUpConn = resolveUpstreamConnection(network, chambers, sources, lines, up, down,
                    parentSeg.getUpstreamObjectId());
            Coordinate pStart = parent.getCoordinateN(0);
            Coordinate pEnd = parent.getCoordinateN(parent.getNumPoints() - 1);
            if (parentUpConn == null) {
                return pStart;
            }
            return GeoUtils.distanceMeters(pStart, parentUpConn) <= GeoUtils.distanceMeters(pEnd, parentUpConn)
                    ? pEnd : pStart;
        }
        return null;
    }

    /** Допуск совпадения конца трубы с камерой при подсчёте примыканий, м (как snap ingest). */
    public static final double CHAMBER_INCIDENCE_TOLERANCE_M = 1.0;

    private final Map<String, Integer> chamberDegreeCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * AUDIT-24.09 (Claude). Число линейных участков существующей сети, примыкающих к камере
     * геометрически (Разъяснение №12, §2.1 приложения): каждый участок, заканчивающийся в камере, —
     * одно примыкание; линия, проходящая через камеру (камера внутри линии), — два примыкания.
     * Раньше степень считалась по цепочке upstream_object_id, которой в актуальном наборе нет
     * (дерево восстанавливалось по геометрии и могло не совпадать с фактическими примыканиями).
     */
    public int chamberDegree(String chamberId) {
        Integer cached = chamberDegreeCache.get(chamberId);
        if (cached != null) {
            return cached;
        }
        Coordinate c = chamberPoints.get(chamberId);
        if (c == null) {
            return 0;
        }
        double tol = CHAMBER_INCIDENCE_TOLERANCE_M;
        org.locationtech.jts.geom.Point p = null;
        int degree = 0;
        for (LineString line : segmentLines.values()) {
            if (line == null || line.getNumPoints() < 2) {
                continue;
            }
            Coordinate a = line.getCoordinateN(0);
            Coordinate b = line.getCoordinateN(line.getNumPoints() - 1);
            boolean endA = a.distance(c) <= tol;
            boolean endB = b.distance(c) <= tol;
            if (endA || endB) {
                degree += (endA ? 1 : 0) + (endB ? 1 : 0);
                continue;
            }
            if (!line.getEnvelopeInternal().intersects(new org.locationtech.jts.geom.Envelope(
                    c.x - tol, c.x + tol, c.y - tol, c.y + tol))) {
                continue;
            }
            if (p == null) {
                p = line.getFactory().createPoint(c);
            }
            if (line.distance(p) <= tol) {
                degree += 2;
            }
        }
        chamberDegreeCache.put(chamberId, degree);
        return degree;
    }

    private static final double MAX_PROJECTION_M = 20_000.0;

    public LineString segmentLine(String segmentId) {
        LineString line = segmentLines.get(segmentId);
        if (line == null) {
            throw new NetworkException("Нет геометрии участка " + segmentId);
        }
        return line;
    }

    public Coordinate chamberPoint(String chamberId) {
        Coordinate c = chamberPoints.get(chamberId);
        if (c == null) {
            throw new NetworkException("Нет координат камеры " + chamberId);
        }
        return c;
    }

    public Coordinate upstreamEndpoint(String segmentId) {
        Coordinate c = upstreamEndpointBySegment.get(segmentId);
        if (c == null) {
            throw new NetworkException("Не определён upstream-конец участка " + segmentId);
        }
        return c;
    }

    public Coordinate downstreamEndpoint(String segmentId) {
        Coordinate c = downstreamEndpointBySegment.get(segmentId);
        if (c == null) {
            throw new NetworkException("Не определён downstream-конец участка " + segmentId);
        }
        return c;
    }

    public Map<String, Coordinate> getChamberPoints() {
        return chamberPoints;
    }

    public Map<String, LineString> getSegmentLines() {
        return segmentLines;
    }

    /**
     * Точка на оси участка на расстоянии от upstream-конца, м.
     *
     * <p>QA-FIX P1 (C-1): отсчёт ведётся <b>вдоль полилинии</b>. Прежняя линейная интерполяция
     * по хорде up→down уводила точку с изогнутой трубы (дерево ОКС 11 «висело» в 17 м от сети).
     */
    public Coordinate pointAtDistanceFromUpstream(String segmentId, double distanceFromUpstreamEndM) {
        LineString oriented = orientedFromUpstream(segmentId);
        double total = oriented.getLength();
        double d = Math.max(0.0, Math.min(total, distanceFromUpstreamEndM));
        return new LengthIndexedLine(oriented).extractPoint(d);
    }

    /** Полилиния участка, ориентированная от upstream-конца. */
    LineString orientedFromUpstream(String segmentId) {
        LineString line = segmentLine(segmentId);
        Coordinate up = upstreamEndpoint(segmentId);
        Coordinate first = line.getCoordinateN(0);
        Coordinate last = line.getCoordinateN(line.getNumPoints() - 1);
        return first.distance(up) <= last.distance(up) ? line : (LineString) line.reverse();
    }

    /**
     * Ортогональная проекция точки на участок.
     *
     * @return расстояние от upstream-конца до точки проекции вдоль оси, м; null если проекция вне участка
     */
    public PipeProjection projectOnSegment(String segmentId, Coordinate point, double endpointMarginM) {
        LineString line = segmentLine(segmentId);
        double totalLen = line.getLength();
        if (totalLen < 1e-6) {
            return null;
        }

        double bestDist = Double.MAX_VALUE;
        Coordinate best = null;
        for (int i = 0; i < line.getNumPoints() - 1; i++) {
            Coordinate a = line.getCoordinateN(i);
            Coordinate b = line.getCoordinateN(i + 1);
            ProjectionHit hit = projectOnSegment(a, b, point);
            if (hit.distanceM < bestDist) {
                bestDist = hit.distanceM;
                best = hit.projection;
            }
        }
        // AUDIT-24.09 (Claude): был жёсткий предел 500 м — ОКС дальше 500 м от любой трубы (в конкурсном
        // наборе есть 476–480 м) не получали кандидатов врезки в резервных планировщиках.
        if (best == null || bestDist > MAX_PROJECTION_M) {
            return null;
        }
        // QA-FIX P1 (C-1): положение вдоль полилинии (было: евклидово расстояние от upstream-конца)
        double bestAlong = new LengthIndexedLine(orientedFromUpstream(segmentId)).indexOf(best);
        if (bestAlong < endpointMarginM || bestAlong > totalLen - endpointMarginM) {
            return null;
        }
        return new PipeProjection(segmentId, best, bestAlong, totalLen, bestDist);
    }

    /**
     * QA-FIX C-5. Проекция на участок, смещённая вдоль трубы до первой точки вне запретных зон.
     *
     * <p>Табл. 2 требует отступа 5/7/9 м от полигона ОКС для новой сети; исключение §2.2 действует
     * только для <b>своего</b> полигона. Если труба проходит вплотную к чужому зданию, ближайшая
     * к ОКС точка трубы попадает в его зону отступа — такую врезку предлагать нельзя. По §2.4
     * и Разъяснению №11 точка присоединения — любая допустимая точка участка, поэтому скользим вдоль
     * трубы до первой допустимой точки вместо отказа от трубы целиком.
     *
     * @param blockedZones объединённые запретные зоны в UTM; null — проверка не выполняется
     * @return допустимая проекция или null, если вся труба лежит в запретных зонах
     */
    public PipeProjection projectOutsideBlocked(String segmentId, Coordinate point,
                                                org.locationtech.jts.geom.Geometry blockedZones,
                                                double endpointMarginM) {
        PipeProjection base = projectOnSegment(segmentId, point, endpointMarginM);
        if (base == null || blockedZones == null || blockedZones.isEmpty()) {
            return base;
        }
        LineString oriented = orientedFromUpstream(segmentId);
        org.locationtech.jts.geom.GeometryFactory gf = oriented.getFactory();
        if (!blockedZones.covers(gf.createPoint(base.getPoint()))) {
            return base;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(oriented);
        double total = oriented.getLength();
        double step = Math.max(0.5, endpointMarginM / 4.0);
        for (double shift = step; shift <= total; shift += step) {
            for (int sign = -1; sign <= 1; sign += 2) {
                double at = base.getDistanceFromUpstreamEndM() + sign * shift;
                if (at < endpointMarginM || at > total - endpointMarginM) {
                    continue;
                }
                Coordinate c = indexed.extractPoint(at);
                if (!blockedZones.covers(gf.createPoint(c))) {
                    return new PipeProjection(segmentId, c, at, total, GeoUtils.distanceMeters(point, c));
                }
            }
        }
        return null;
    }

    private static ProjectionHit projectOnSegment(Coordinate a, Coordinate b, Coordinate p) {
        double abx = b.x - a.x;
        double aby = b.y - a.y;
        double len2 = abx * abx + aby * aby;
        if (len2 < 1e-12) {
            return new ProjectionHit(a, GeoUtils.distanceMeters(a, p));
        }
        double t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        Coordinate proj = new Coordinate(a.x + t * abx, a.y + t * aby);
        return new ProjectionHit(proj, GeoUtils.distanceMeters(p, proj));
    }

    /** Результат проекции на трубу. */
    public static final class PipeProjection {
        private final String segmentId;
        private final Coordinate point;
        private final double distanceFromUpstreamEndM;
        private final double segmentLengthM;
        private final double offsetFromAxisM;

        PipeProjection(String segmentId, Coordinate point, double distanceFromUpstreamEndM,
                       double segmentLengthM, double offsetFromAxisM) {
            this.segmentId = segmentId;
            this.point = point;
            this.distanceFromUpstreamEndM = distanceFromUpstreamEndM;
            this.segmentLengthM = segmentLengthM;
            this.offsetFromAxisM = offsetFromAxisM;
        }

        public String getSegmentId() {
            return segmentId;
        }

        public Coordinate getPoint() {
            return point;
        }

        public double getDistanceFromUpstreamEndM() {
            return distanceFromUpstreamEndM;
        }

        public double getSegmentLengthM() {
            return segmentLengthM;
        }

        public double getOffsetFromAxisM() {
            return offsetFromAxisM;
        }
    }

    private static final class ProjectionHit {
        private final Coordinate projection;
        private final double distanceM;

        ProjectionHit(Coordinate projection, double distanceM) {
            this.projection = projection;
            this.distanceM = distanceM;
        }
    }
}
