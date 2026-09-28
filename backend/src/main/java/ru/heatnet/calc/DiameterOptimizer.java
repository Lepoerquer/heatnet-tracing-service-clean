package ru.heatnet.calc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.calc.model.NewNode;
import ru.heatnet.calc.model.NewSegment;
import ru.heatnet.calc.model.NodeKind;
import ru.heatnet.calc.reference.DiameterSpec;
import ru.heatnet.calc.reference.DiameterTable;

/**
 * AUDIT-24.09 (Claude). Точный подбор ДУ с учётом предельной длины (§2.3 приложения, Разъяснения №1–2).
 *
 * <p>Ограничения:</p>
 * <ol>
 *   <li>ДУ участка не меньше минимального по расчётному расходу (табл. 1);</li>
 *   <li>на пути между узлами, где меняется расход (узлы разветвления), ДУ одинаков по всей длине —
 *       поэтому ДУ назначается «отрезку постоянного расхода» (цепочке участков через техузлы/камеры
 *       без ветвления) целиком; менять ДУ только ради нового отсчёта нельзя;</li>
 *   <li>от точки подключения к врезке ДУ не убывает;</li>
 *   <li>по каждому пути «врезка → ОКС» длина каждой непрерывной плети одного ДУ не больше предельной;
 *       общий участок входит в каждый путь, параллельные ветви не суммируются.</li>
 * </ol>
 *
 * <p>Среди допустимых назначений выбирается минимальное по стоимости L·c(ДУ) (+ стоимость камер
 * разветвления по §3.2) — «произвольное завышение ДУ» тем самым исключено: любое увеличение ДУ
 * оправдано предельной длиной. Прежний алгоритм при превышении поднимал ДУ СРАЗУ ВСЕЙ плети,
 * что могло завышать ДУ участка, для которого хватало подъёма соседнего (например, лист 200 м
 * DN80 + магистраль 200 м DN80 → оба DN100, хотя достаточно поднять только магистраль).</p>
 *
 * <p>Динамическое программирование по дереву отрезков постоянного расхода; для каждого отрезка
 * и ДУ хранится Парето-фронт (стоимость, длина плети вниз). Размеры фронтов малы.</p>
 */
public final class DiameterOptimizer {

    private final DiameterTable table;
    private final ChamberCost chamberCost;

    /** Стоимость камеры по ДУ (для учёта в целевой функции); может вернуть 0. */
    public interface ChamberCost {
        long costFor(int dn);
    }

    public DiameterOptimizer(DiameterTable table, ChamberCost chamberCost) {
        this.table = table;
        this.chamberCost = chamberCost;
    }

    /**
     * @return ДУ по участкам или null, если допустимого назначения нет (путь длиннее предела DN1400)
     */
    public Map<String, Integer> optimize(NewNetworkTree tree, Map<String, Double> flows, double toleranceM) {
        List<Stretch> stretches = buildStretches(tree, flows);
        if (stretches.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<Stretch, Map<Integer, List<State>>> memo = new HashMap<>();
        Stretch root = stretches.get(0);
        Map<Integer, List<State>> rootSol = solve(root, memo, toleranceM);
        State best = null;
        for (List<State> front : rootSol.values()) {
            for (State s : front) {
                if (best == null || s.cost < best.cost - 1e-6
                        || (Math.abs(s.cost - best.cost) <= 1e-6 && s.dn < best.dn)) {
                    best = s;
                }
            }
        }
        if (best == null) {
            return null;
        }
        Map<String, Integer> out = new HashMap<>();
        assign(best, out);
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (NewSegment seg : tree.segmentsTopDown()) {
            Integer d = out.get(seg.getId());
            if (d == null) {
                return null;
            }
            ordered.put(seg.getId(), d);
        }
        return ordered;
    }

    private void assign(State s, Map<String, Integer> out) {
        for (String segId : s.stretch.segmentIds) {
            out.put(segId, s.dn);
        }
        for (State child : s.children) {
            assign(child, out);
        }
    }

    private Map<Integer, List<State>> solve(Stretch st, Map<Stretch, Map<Integer, List<State>>> memo, double tol) {
        Map<Integer, List<State>> cached = memo.get(st);
        if (cached != null) {
            return cached;
        }
        List<Map<Integer, List<State>>> kids = new ArrayList<>();
        for (Stretch child : st.children) {
            kids.add(solve(child, memo, tol));
        }
        Map<Integer, List<State>> result = new LinkedHashMap<>();
        int minIdx = minIndexFor(st.flow);
        for (int i = minIdx; i < table.size(); i++) {
            DiameterSpec spec = table.byIndex(i);
            int d = spec.getDn();
            double maxLen = spec.getMaxLengthM();
            // комбинации детей: список частичных состояний (стоимость, h, выбранные дети)
            List<Partial> combos = new ArrayList<>();
            combos.add(new Partial(0.0, 0.0, Collections.<State>emptyList()));
            boolean feasible = true;
            for (Map<Integer, List<State>> kid : kids) {
                List<Partial> options = new ArrayList<>();
                for (Map.Entry<Integer, List<State>> e : kid.entrySet()) {
                    int dk = e.getKey();
                    if (dk > d) {
                        continue; // ДУ не убывает к врезке
                    }
                    for (State ks : e.getValue()) {
                        if (dk == d) {
                            options.add(new Partial(ks.cost, ks.h, Collections.singletonList(ks)));
                        } else {
                            // плеть ребёнка заканчивается в узле разветвления
                            if (ks.h <= table.spec(dk).getMaxLengthM() + tol) {
                                options.add(new Partial(ks.cost, 0.0, Collections.singletonList(ks)));
                            }
                        }
                    }
                }
                options = pareto(options);
                if (options.isEmpty()) {
                    feasible = false;
                    break;
                }
                List<Partial> next = new ArrayList<>();
                for (Partial a : combos) {
                    for (Partial b : options) {
                        List<State> chosen = new ArrayList<>(a.chosen);
                        chosen.addAll(b.chosen);
                        next.add(new Partial(a.cost + b.cost, Math.max(a.h, b.h), chosen));
                    }
                }
                combos = pareto(next);
            }
            if (!feasible) {
                continue;
            }
            double own = st.lengthM * spec.getNewCostRubPerM();
            if (st.endsInChamber && chamberCost != null) {
                own += chamberCost.costFor(d);
            }
            List<State> front = new ArrayList<>();
            for (Partial p : combos) {
                double h = p.h + st.lengthM;
                if (h > maxLen + tol) {
                    continue;
                }
                front.add(new State(st, d, p.cost + own, h, p.chosen));
            }
            front = paretoStates(front);
            if (!front.isEmpty()) {
                result.put(d, front);
            }
        }
        memo.put(st, result);
        return result;
    }

    private int minIndexFor(double flow) {
        for (int i = 0; i < table.size(); i++) {
            if (table.byIndex(i).getCapacityTph() >= flow) {
                return i;
            }
        }
        return table.size() - 1;
    }

    private static List<Partial> pareto(List<Partial> in) {
        in.sort((a, b) -> a.cost != b.cost ? Double.compare(a.cost, b.cost) : Double.compare(a.h, b.h));
        List<Partial> out = new ArrayList<>();
        double bestH = Double.POSITIVE_INFINITY;
        for (Partial p : in) {
            if (p.h < bestH - 1e-9) {
                out.add(p);
                bestH = p.h;
            }
        }
        return out;
    }

    private static List<State> paretoStates(List<State> in) {
        in.sort((a, b) -> a.cost != b.cost ? Double.compare(a.cost, b.cost) : Double.compare(a.h, b.h));
        List<State> out = new ArrayList<>();
        double bestH = Double.POSITIVE_INFINITY;
        for (State s : in) {
            if (s.h < bestH - 1e-9) {
                out.add(s);
                bestH = s.h;
            }
        }
        return out;
    }

    /** Отрезки постоянного расхода: цепочки участков между узлами разветвления. */
    private static List<Stretch> buildStretches(NewNetworkTree tree, Map<String, Double> flows) {
        List<Stretch> out = new ArrayList<>();
        NewSegment first = tree.rootSegment();
        build(tree, flows, first, out);
        return out;
    }

    private static Stretch build(NewNetworkTree tree, Map<String, Double> flows, NewSegment start, List<Stretch> out) {
        Stretch st = new Stretch();
        out.add(st);
        st.flow = flows.get(start.getId());
        NewSegment cur = start;
        while (true) {
            st.segmentIds.add(cur.getId());
            st.lengthM += cur.getLengthM();
            List<NewSegment> children = tree.childrenOf(cur.getToNodeId());
            NewNode end = tree.node(cur.getToNodeId());
            if (children.size() == 1) {
                cur = children.get(0);
                continue;
            }
            st.endsInChamber = end.getKind() == NodeKind.NEW_CHAMBER && children.size() >= 2;
            for (NewSegment child : children) {
                st.children.add(build(tree, flows, child, out));
            }
            break;
        }
        return st;
    }

    private static final class Stretch {
        private final List<String> segmentIds = new ArrayList<>();
        private final List<Stretch> children = new ArrayList<>();
        private double lengthM;
        private double flow;
        private boolean endsInChamber;
    }

    private static final class State {
        private final Stretch stretch;
        private final int dn;
        private final double cost;
        private final double h;
        private final List<State> children;

        private State(Stretch stretch, int dn, double cost, double h, List<State> children) {
            this.stretch = stretch;
            this.dn = dn;
            this.cost = cost;
            this.h = h;
            this.children = children;
        }
    }

    private static final class Partial {
        private final double cost;
        private final double h;
        private final List<State> chosen;

        private Partial(double cost, double h, List<State> chosen) {
            this.cost = cost;
            this.h = h;
            this.chosen = chosen;
        }
    }
}
