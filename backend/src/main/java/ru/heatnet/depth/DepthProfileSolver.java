package ru.heatnet.depth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;

import ru.heatnet.calc.reference.DepthRules;
import ru.heatnet.calc.reference.GabaritTable;
import ru.heatnet.cost.DepthCoefficient;

/**
 * Профиль глубины вдоль уже найденной 2D-трассы.
 * Обычная глубина 3,0 м. На точечном пересечении выбирается более дешёвый проход
 * выше или ниже препятствия; площадка 4 м только у газа, кабеля и теплосети.
 * Уклон не круче {@code max_slope}. Если между пересечениями не влезает выход на 3,0 м,
 * остаётся один транзитный коридор.
 */
public final class DepthProfileSolver {

    private static final double EPS = 1e-4;
    private static final double END_MARGIN_M = 0.25;
    private static final double PARALLEL_SKIP_M = 1.0;

    private final DepthRules rules;
    private final GabaritTable gabarits;
    private final DepthCoefficient coefficient;

    public DepthProfileSolver(DepthRules rules, GabaritTable gabarits) {
        this.rules = rules;
        this.gabarits = gabarits;
        this.coefficient = new DepthCoefficient(rules);
    }

    /**
     * @return куски профиля или null, если вся линия остаётся на обычной глубине
     */
    public List<DepthSpan> solve(LineString lineUtm, int ourDn, List<DepthObstacle> obstacles) {
        if (lineUtm == null || lineUtm.getNumPoints() < 2 || obstacles == null || obstacles.isEmpty()) {
            return null;
        }
        double length = lineUtm.getLength();
        if (!(length > EPS)) {
            return null;
        }
        double ourHeight = gabarits.spec(ourDn).getHeightM();
        double normal = rules.getNormalDepthM();
        LengthIndexedLine indexed = new LengthIndexedLine(lineUtm);

        List<Floor> floors = new ArrayList<>();
        List<PendingHit> hits = new ArrayList<>();
        for (DepthObstacle obstacle : obstacles) {
            if (obstacle.getGeometryUtm() == null || obstacle.getGeometryUtm().isEmpty()) {
                continue;
            }
            if (!lineUtm.getEnvelopeInternal().intersects(obstacle.getGeometryUtm().getEnvelopeInternal())) {
                continue;
            }
            Geometry hit;
            try {
                hit = lineUtm.intersection(obstacle.getGeometryUtm());
            } catch (RuntimeException ex) {
                continue;
            }
            if (hit == null || hit.isEmpty()) {
                continue;
            }
            if (obstacle.isSurface()) {
                addFloors(indexed, hit, Math.max(obstacle.getMinTopDepthM(), rules.getMinDepthM()), floors);
            } else {
                hits.add(new PendingHit(obstacle, hit));
            }
        }
        List<Crossing> crossings = new ArrayList<>();
        for (PendingHit pending : hits) {
            addCrossings(indexed, pending.hit, length, pending.obstacle, ourHeight, floors, crossings);
        }
        if (crossings.isEmpty()) {
            return null;
        }
        Collections.sort(crossings, new Comparator<Crossing>() {
            @Override
            public int compare(Crossing a, Crossing b) {
                return Double.compare(a.chainage, b.chainage);
            }
        });
        List<Interval> plateaus = mergePlateaus(toPlateaus(crossings, length), length, normal);
        if (plateaus.isEmpty()) {
            return null;
        }
        List<Vertex> vertices = buildVertices(plateaus, length, normal);
        if (!departsFromNormal(vertices, normal)) {
            return null;
        }
        return toSpans(indexed, vertices, normal);
    }

    private void addFloors(LengthIndexedLine indexed, Geometry hit, double minTop, List<Floor> floors) {
        List<double[]> ranges = new ArrayList<>();
        collectRanges(indexed, hit, ranges);
        for (double[] range : ranges) {
            floors.add(new Floor(range[0], range[1], minTop));
        }
    }

    private void addCrossings(LengthIndexedLine indexed, Geometry hit, double length, DepthObstacle obstacle,
                              double ourHeight, List<Floor> floors, List<Crossing> crossings) {
        if (hit.getLength() > PARALLEL_SKIP_M && hit.getDimension() == 1) {
            return;
        }
        List<Double> points = new ArrayList<>();
        collectPoints(indexed, hit, points);
        DepthRules.Utility utility = rules.getUtilities().get(obstacle.getType());
        if (utility == null) {
            return;
        }
        double obstacleHeight = obstacleHeight(obstacle, utility);
        if (Double.isNaN(obstacleHeight)) {
            return;
        }
        // AUDIT-24.09 (Claude): по актуальной табл. 2 вертикальный просвет — 0,2 м (газ) и 0,5 м
        // (кабель, теплосеть). Параметр min_offset_m = 0,7 м — из устного протокола 16.09, в актуальном
        // приложении его нет (Разъяснение №16: применяется актуальное приложение); он завышал заглубление
        // и Kгл при проходе под газопроводом.
        double gap = utility.getVerticalClearanceM();
        double top = utility.getTopDepthM();
        double bottom = top + obstacleHeight;
        double hAbove = top - gap - ourHeight;
        double hBelow = bottom + gap;
        for (Double chainage : points) {
            if (chainage < END_MARGIN_M || chainage > length - END_MARGIN_M) {
                continue;
            }
            double floor = floorAt(chainage, floors);
            Choice choice = choose(hAbove, hBelow, floor, top, bottom, gap, ourHeight);
            if (choice == null) {
                continue;
            }
            if (Math.abs(choice.depth - rules.getNormalDepthM()) < 1e-3) {
                continue;
            }
            crossings.add(new Crossing(chainage, choice.depth, choice.note(obstacle)));
        }
    }

    private Choice choose(double hAbove, double hBelow, double floor, double obstacleTop, double obstacleBottom,
                          double gap, double ourHeight) {
        boolean aboveOk = fits(hAbove, floor, obstacleTop, obstacleBottom, gap, ourHeight, true);
        boolean belowOk = fits(hBelow, floor, obstacleTop, obstacleBottom, gap, ourHeight, false);
        double kAbove = aboveOk ? coefficient.of(quantize(hAbove, true)) : Double.POSITIVE_INFINITY;
        double kBelow = belowOk ? coefficient.of(quantize(hBelow, false)) : Double.POSITIVE_INFINITY;
        if (!aboveOk && !belowOk) {
            return null;
        }
        boolean takeAbove;
        if (kAbove < kBelow - 1e-12) {
            takeAbove = true;
        } else if (kBelow < kAbove - 1e-12) {
            takeAbove = false;
        } else {
            double da = aboveOk ? Math.abs(quantize(hAbove, true) - rules.getNormalDepthM()) : Double.POSITIVE_INFINITY;
            double db = belowOk ? Math.abs(quantize(hBelow, false) - rules.getNormalDepthM()) : Double.POSITIVE_INFINITY;
            takeAbove = da <= db;
        }
        if (takeAbove) {
            double h = quantize(hAbove, true);
            return new Choice(h, "выше препятствия, верх габарита " + format(h) + " м, Kгл="
                    + format(coefficient.of(h)));
        }
        double h = quantize(hBelow, false);
        return new Choice(h, "ниже препятствия, верх габарита " + format(h) + " м, Kгл="
                + format(coefficient.of(h)));
    }

    private boolean fits(double hTop, double floor, double obstacleTop, double obstacleBottom, double gap,
                         double ourHeight, boolean wantAbove) {
        if (!(hTop >= floor - 1e-9) || !(hTop >= rules.getMinDepthM() - 1e-9)) {
            return false;
        }
        double hBottom = hTop + ourHeight;
        if (wantAbove) {
            return hBottom <= obstacleTop - gap + 1e-6;
        }
        return hTop >= obstacleBottom + gap - 1e-6;
    }

    private double quantize(double depth, boolean above) {
        Double step = rules.getStepM();
        if (step == null || !(step > 0)) {
            return depth;
        }
        if (above) {
            double q = Math.floor((depth + 1e-9) / step) * step;
            if (q < rules.getMinDepthM()) {
                q = Math.ceil((rules.getMinDepthM() - 1e-9) / step) * step;
            }
            return q;
        }
        return Math.ceil((depth - 1e-9) / step) * step;
    }

    private double obstacleHeight(DepthObstacle obstacle, DepthRules.Utility utility) {
        if (utility.isGabaritByDn()) {
            if (obstacle.getDiameterMm() <= 0) {
                return Double.NaN;
            }
            return gabarits.spec(obstacle.getDiameterMm()).getHeightM();
        }
        Double height = utility.getHeightM();
        return height == null ? Double.NaN : height;
    }

    private static double floorAt(double chainage, List<Floor> floors) {
        double floor = 0;
        for (Floor f : floors) {
            if (chainage >= f.start - EPS && chainage <= f.end + EPS) {
                floor = Math.max(floor, f.minTop);
            }
        }
        return floor;
    }

    private List<Interval> toPlateaus(List<Crossing> crossings, double length) {
        double half = rules.getPointCrossingPlateauM() / 2.0;
        List<Interval> raw = new ArrayList<>();
        for (Crossing crossing : crossings) {
            double a = Math.max(0, crossing.chainage - half);
            double b = Math.min(length, crossing.chainage + half);
            if (b - a > EPS) {
                raw.add(new Interval(a, b, crossing.depth, crossing.note));
            }
        }
        return raw;
    }

    private List<Interval> mergePlateaus(List<Interval> raw, double length, double normal) {
        if (raw.isEmpty()) {
            return raw;
        }
        Collections.sort(raw, new Comparator<Interval>() {
            @Override
            public int compare(Interval a, Interval b) {
                return Double.compare(a.start, b.start);
            }
        });
        List<Interval> merged = new ArrayList<>();
        Interval cur = raw.get(0);
        for (int i = 1; i < raw.size(); i++) {
            Interval next = raw.get(i);
            double need = rampLength(cur.depth, normal) + rampLength(next.depth, normal);
            if (next.start < cur.end + need - EPS) {
                cur = new Interval(cur.start, Math.max(cur.end, next.end),
                        mergeDepth(cur.depth, next.depth, normal), cur.note + " " + next.note);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(clamp(cur, length));
        return merged;
    }

    private static Interval clamp(Interval interval, double length) {
        double a = Math.max(0, interval.start);
        double b = Math.min(length, interval.end);
        return new Interval(a, b, interval.depth, interval.note);
    }

    private double mergeDepth(double a, double b, double normal) {
        if (Math.abs(a - b) < 1e-6) {
            return a;
        }
        boolean aAbove = a < normal - 1e-6;
        boolean bAbove = b < normal - 1e-6;
        if (aAbove && bAbove) {
            return Math.min(a, b);
        }
        return Math.max(a, b);
    }

    private double rampLength(double depth, double normal) {
        double slope = rules.getMaxSlopeMPerM();
        if (!(slope > 0)) {
            return 0;
        }
        return Math.abs(depth - normal) / slope;
    }

    private List<Vertex> buildVertices(List<Interval> plateaus, double length, double normal) {
        List<Vertex> vertices = new ArrayList<>();
        vertices.add(new Vertex(0, normal, null));
        double cursor = 0;
        for (Interval interval : plateaus) {
            double target = interval.depth;
            double needed = rampLength(target, normal);
            double available = Math.max(0, interval.start - cursor);
            double depthAtStart;
            if (available + EPS >= needed) {
                double rampIn = interval.start - needed;
                if (rampIn > cursor + EPS) {
                    vertices.add(new Vertex(rampIn, normal, null));
                }
                depthAtStart = target;
            } else {
                double sign = Math.signum(target - normal);
                depthAtStart = normal + sign * available * rules.getMaxSlopeMPerM();
            }
            double plateauStart = Math.max(cursor, interval.start);
            vertices.add(new Vertex(plateauStart, depthAtStart, interval.note));
            if (interval.end > plateauStart + EPS) {
                vertices.add(new Vertex(interval.end, depthAtStart, interval.note));
            }
            double back = rampLength(depthAtStart, normal);
            double rampOut = Math.min(length, interval.end + back);
            if (rampOut > interval.end + EPS) {
                vertices.add(new Vertex(rampOut, normal, null));
                cursor = rampOut;
            } else {
                cursor = interval.end;
            }
        }
        Vertex tail = vertices.get(vertices.size() - 1);
        if (tail.chainage < length - EPS) {
            double tailDepth = cursor >= length - EPS ? tail.depth : normal;
            vertices.add(new Vertex(length, tailDepth, null));
        }
        return dedup(vertices);
    }

    private static List<Vertex> dedup(List<Vertex> vertices) {
        List<Vertex> out = new ArrayList<>();
        for (Vertex vertex : vertices) {
            if (!out.isEmpty() && vertex.chainage <= out.get(out.size() - 1).chainage + EPS) {
                Vertex prev = out.get(out.size() - 1);
                String note = vertex.note != null ? vertex.note : prev.note;
                out.set(out.size() - 1, new Vertex(vertex.chainage, vertex.depth, note));
                continue;
            }
            out.add(vertex);
        }
        return out;
    }

    private static boolean departsFromNormal(List<Vertex> vertices, double normal) {
        for (Vertex vertex : vertices) {
            if (Math.abs(vertex.depth - normal) > 1e-3) {
                return true;
            }
        }
        return false;
    }

    private List<DepthSpan> toSpans(LengthIndexedLine indexed, List<Vertex> vertices, double normal) {
        List<Vertex> split = splitAtThreshold(vertices, rules.getCostThresholdDepthM());
        List<DepthSpan> spans = new ArrayList<>();
        for (int i = 0; i < split.size() - 1; i++) {
            Vertex a = split.get(i);
            Vertex b = split.get(i + 1);
            if (b.chainage - a.chainage <= EPS) {
                continue;
            }
            LineString line = subLine(indexed, a.chainage, b.chainage);
            String note = null;
            if (Math.abs(a.depth - b.depth) < 1e-6 && Math.abs(a.depth - normal) > 1e-3) {
                note = a.note != null ? a.note : b.note;
            }
            spans.add(new DepthSpan(a.chainage, b.chainage, a.depth, b.depth, line, note));
        }
        return spans.isEmpty() ? null : spans;
    }

    private static List<Vertex> splitAtThreshold(List<Vertex> vertices, double threshold) {
        List<Vertex> out = new ArrayList<>();
        out.add(vertices.get(0));
        for (int i = 1; i < vertices.size(); i++) {
            Vertex prev = out.get(out.size() - 1);
            Vertex next = vertices.get(i);
            boolean crosses = (prev.depth < threshold && next.depth > threshold)
                    || (prev.depth > threshold && next.depth < threshold);
            if (crosses && Math.abs(next.depth - prev.depth) > EPS) {
                double frac = (threshold - prev.depth) / (next.depth - prev.depth);
                double mid = prev.chainage + (next.chainage - prev.chainage) * frac;
                if (mid > prev.chainage + EPS && mid < next.chainage - EPS) {
                    out.add(new Vertex(mid, threshold, "пересечение глубины " + format(threshold) + " м"));
                }
            }
            out.add(next);
        }
        return out;
    }

    private static LineString subLine(LengthIndexedLine indexed, double from, double to) {
        Geometry extracted = indexed.extractLine(from, to);
        Coordinate start = indexed.extractPoint(from);
        Coordinate end = indexed.extractPoint(to);
        if (extracted instanceof LineString && extracted.getNumPoints() >= 2) {
            LineString line = (LineString) extracted;
            Coordinate[] coords = line.getCoordinates();
            coords[0] = new Coordinate(start.x, start.y);
            coords[coords.length - 1] = new Coordinate(end.x, end.y);
            return line.getFactory().createLineString(coords);
        }
        return extracted.getFactory().createLineString(new Coordinate[] {
                new Coordinate(start.x, start.y),
                new Coordinate(end.x, end.y)
        });
    }

    private static void collectPoints(LengthIndexedLine indexed, Geometry hit, List<Double> points) {
        if (hit instanceof Point) {
            points.add(indexed.indexOf(hit.getCoordinate()));
            return;
        }
        if (hit instanceof LineString) {
            LineString line = (LineString) hit;
            if (line.getNumPoints() > 0) {
                Coordinate mid = line.getCoordinateN(line.getNumPoints() / 2);
                points.add(indexed.indexOf(mid));
            }
            return;
        }
        for (int i = 0; i < hit.getNumGeometries(); i++) {
            collectPoints(indexed, hit.getGeometryN(i), points);
        }
    }

    private static void collectRanges(LengthIndexedLine indexed, Geometry hit, List<double[]> ranges) {
        if (hit instanceof Point) {
            double s = indexed.indexOf(hit.getCoordinate());
            ranges.add(new double[] {s, s});
            return;
        }
        if (hit instanceof LineString) {
            LineString line = (LineString) hit;
            if (line.getNumPoints() == 0) {
                return;
            }
            double a = indexed.indexOf(line.getCoordinateN(0));
            double b = indexed.indexOf(line.getCoordinateN(line.getNumPoints() - 1));
            ranges.add(new double[] {Math.min(a, b), Math.max(a, b)});
            return;
        }
        for (int i = 0; i < hit.getNumGeometries(); i++) {
            collectRanges(indexed, hit.getGeometryN(i), ranges);
        }
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.3f", value);
    }

    private static final class PendingHit {
        private final DepthObstacle obstacle;
        private final Geometry hit;

        private PendingHit(DepthObstacle obstacle, Geometry hit) {
            this.obstacle = obstacle;
            this.hit = hit;
        }
    }

    private static final class Crossing {
        private final double chainage;
        private final double depth;
        private final String note;

        private Crossing(double chainage, double depth, String note) {
            this.chainage = chainage;
            this.depth = depth;
            this.note = note;
        }
    }

    private static final class Choice {
        private final double depth;
        private final String text;

        private Choice(double depth, String text) {
            this.depth = depth;
            this.text = text;
        }

        private String note(DepthObstacle obstacle) {
            return "Пересечение " + obstacle.getType() + " (" + obstacle.getId() + "): " + text
                    + ". Площадка " + "постоянной глубины.";
        }
    }

    private static final class Floor {
        private final double start;
        private final double end;
        private final double minTop;

        private Floor(double start, double end, double minTop) {
            this.start = start;
            this.end = end;
            this.minTop = minTop;
        }
    }

    private static final class Interval {
        private final double start;
        private final double end;
        private final double depth;
        private final String note;

        private Interval(double start, double end, double depth, String note) {
            this.start = start;
            this.end = end;
            this.depth = depth;
            this.note = note;
        }
    }

    private static final class Vertex {
        private final double chainage;
        private final double depth;
        private final String note;

        private Vertex(double chainage, double depth, String note) {
            this.chainage = chainage;
            this.depth = depth;
            this.note = note;
        }
    }
}
