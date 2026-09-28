package ru.heatnet.routing;

import java.util.ArrayList;
import java.util.List;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import ru.heatnet.rules.model.SpecialSection;

/**
 * AUDIT-13 (Claude, 25.09). Стоимость звена трассы в «метрах × Kспец» так же, как её считает смета M6 (§6
 * приложения: {@code Cуч = L · cнов(ДУ) · Kспец} только для специального участка; Разъяснение №8 — на наложении
 * берётся наибольший Kспец, коэффициенты не суммируются и не перемножаются).
 *
 * <p>Раньше поиск пути ({@code SharedVisibilityRouter.edgeCost}, {@code SteinerPlanner.weightedLength},
 * {@code VisibilityGraphBuilder}) умножал на наибольший Kспец ВСЮ длину звена: прямое звено 150 м, пересекающее
 * дорогу шириной 12 м (спецучасток 18 м, K = 1,60), стоило 240 м вместо 160,8 м. Прямая через дорогу
 * проигрывала ломаной, у которой рядом с дорогой стоит лишняя вершина, — отсюда необоснованные изломы у
 * пересечений дорог, трамвая, газопровода, кабеля и существующей теплосети и отказ спрямления («прямая дороже»),
 * хотя по смете прямая дешевле. Здесь спецучастки звена переводятся в интервалы вдоль звена, и Kспец
 * применяется только к ним (кусками, с максимумом на наложении) — ровно как {@code RouteSegmentSplitter}.</p>
 */
public final class SpecialCost {

    private static final double EPS = 1e-9;

    private SpecialCost() {
    }

    /**
     * @param segment  звено или ломаная трассы
     * @param sections спецучастки этого звена ({@code extractSpecialSections}); {@code null}/пусто — обычный участок
     * @return длина с учётом Kспец по кускам
     */
    public static double weightedLength(LineString segment, List<SpecialSection> sections) {
        double length = segment.getLength();
        if (sections == null || sections.isEmpty() || length < EPS) {
            return length;
        }
        List<double[]> intervals = new ArrayList<>(sections.size());
        for (SpecialSection section : sections) {
            LineString g = section.getGeometry();
            if (g == null || g.isEmpty() || section.getKSpec() <= 1.0 + EPS) {
                continue;
            }
            double a = positionAlong(segment, g.getCoordinateN(0));
            double b = positionAlong(segment, g.getCoordinateN(g.getNumPoints() - 1));
            double lo = Math.max(0.0, Math.min(a, b));
            double hi = Math.min(length, Math.max(a, b));
            if (hi - lo > EPS) {
                intervals.add(new double[] {lo, hi, section.getKSpec()});
            }
        }
        if (intervals.isEmpty()) {
            return length;
        }
        List<Double> cuts = new ArrayList<>(intervals.size() * 2 + 2);
        cuts.add(0.0);
        cuts.add(length);
        for (double[] iv : intervals) {
            cuts.add(iv[0]);
            cuts.add(iv[1]);
        }
        java.util.Collections.sort(cuts);
        double total = 0.0;
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double lo = cuts.get(i);
            double hi = cuts.get(i + 1);
            if (hi - lo <= EPS) {
                continue;
            }
            double mid = (lo + hi) / 2.0;
            double k = 1.0;
            for (double[] iv : intervals) {
                if (iv[0] < mid && mid < iv[1]) {
                    k = Math.max(k, iv[2]);
                }
            }
            total += (hi - lo) * k;
        }
        return total;
    }

    /** Положение ближайшей к {@code p} точки ломаной, м от начала. */
    static double positionAlong(LineString path, Coordinate p) {
        double best = 0.0;
        double bestDist = Double.MAX_VALUE;
        double acc = 0.0;
        for (int i = 0; i + 1 < path.getNumPoints(); i++) {
            Coordinate a = path.getCoordinateN(i);
            Coordinate b = path.getCoordinateN(i + 1);
            double abx = b.x - a.x;
            double aby = b.y - a.y;
            double len2 = abx * abx + aby * aby;
            double len = Math.sqrt(len2);
            double t = len2 < EPS ? 0.0 : ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double d = Math.hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby));
            if (d < bestDist) {
                bestDist = d;
                best = acc + t * len;
            }
            acc += len;
        }
        return best;
    }
}
