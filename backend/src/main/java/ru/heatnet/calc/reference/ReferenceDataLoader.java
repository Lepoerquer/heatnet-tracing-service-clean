package ru.heatnet.calc.reference;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

import ru.heatnet.calc.CalcException;

/**
 * Загрузка config/diameters.yaml, gabarits.yaml, rules.yaml, depth-rules.yaml
 * с проверкой целостности. Все ошибки — {@link CalcException} с путём к полю.
 */
public final class ReferenceDataLoader {

    public static final String CONFIG_DIR_PROPERTY = "heatnet.config.dir";
    public static final String CONFIG_DIR_ENV = "HEATNET_CONFIG_DIR";

    private ReferenceDataLoader() {
    }

    /**
     * Каталог конфигурации: системное свойство heatnet.config.dir, переменная окружения
     * HEATNET_CONFIG_DIR, затем ./config и ../config (запуск тестов из backend/).
     */
    public static Path resolveDefaultDir() {
        String explicit = System.getProperty(CONFIG_DIR_PROPERTY);
        if (explicit == null || explicit.isEmpty()) {
            explicit = System.getenv(CONFIG_DIR_ENV);
        }
        if (explicit != null && !explicit.isEmpty()) {
            return Paths.get(explicit);
        }
        for (String candidate : new String[] {"config", "../config"}) {
            Path p = Paths.get(candidate);
            if (Files.isRegularFile(p.resolve("diameters.yaml"))) {
                return p;
            }
        }
        throw new CalcException("Не найден каталог config/ с diameters.yaml; задайте -D"
                + CONFIG_DIR_PROPERTY + " или " + CONFIG_DIR_ENV);
    }

    public static ReferenceData loadDefault() {
        return load(resolveDefaultDir());
    }

    public static ReferenceData load(Path dir) {
        DiameterTable diameters = parseDiameters(read(dir.resolve("diameters.yaml")));
        GabaritTable gabarits = parseGabarits(read(dir.resolve("gabarits.yaml")), diameters);
        RulesConfig rules = parseRules(read(dir.resolve("rules.yaml")), diameters);
        DepthRules depth = parseDepth(read(dir.resolve("depth-rules.yaml")));
        return new ReferenceData(diameters, gabarits, rules, depth);
    }

    // ------------------------------------------------------------------ diameters

    static DiameterTable parseDiameters(Map<String, Object> root) {
        List<DiameterSpec> specs = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> row : listOfMaps(root, "diameters", "diameters.yaml")) {
            String at = "diameters.yaml: diameters[" + i++ + "]";
            specs.add(new DiameterSpec(
                    intReq(row, "dn", at),
                    positive(dblReq(row, "capacity_tph", at), at + ".capacity_tph"),
                    positive(dblReq(row, "max_length_m", at), at + ".max_length_m"),
                    longPositive(row, "new_cost_rub_m", at),
                    longPositive(row, "reconstruction_cost_rub_m", at)));
        }
        return new DiameterTable(specs);
    }

    // ------------------------------------------------------------------ gabarits

    static GabaritTable parseGabarits(Map<String, Object> root, DiameterTable diameters) {
        List<GabaritSpec> specs = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> row : listOfMaps(root, "gabarits", "gabarits.yaml")) {
            String at = "gabarits.yaml: gabarits[" + i++ + "]";
            GabaritSpec g = new GabaritSpec(intReq(row, "dn", at), dblReq(row, "outer_diameter_m", at),
                    dblReq(row, "gap_m", at), dblReq(row, "width_m", at), dblReq(row, "height_m", at));
            double expectedWidth = 2 * g.getOuterDiameterM() + g.getGapM();
            if (Math.abs(expectedWidth - g.getWidthM()) > 0.0005) {
                throw new CalcException(at + ": ширина " + g.getWidthM() + " ≠ 2·D + просвет = " + expectedWidth);
            }
            if (Math.abs(g.getHeightM() - g.getOuterDiameterM()) > 0.0005) {
                throw new CalcException(at + ": высота должна быть равна наружному диаметру оболочки");
            }
            specs.add(g);
        }
        GabaritTable table = new GabaritTable(specs);
        for (DiameterSpec d : diameters.all()) {
            table.spec(d.getDn());
        }
        if (table.all().size() != diameters.size()) {
            throw new CalcException("gabarits.yaml и diameters.yaml содержат разный набор DN");
        }
        return table;
    }

    // ------------------------------------------------------------------ rules

    static RulesConfig parseRules(Map<String, Object> root, DiameterTable diameters) {
        String f = "rules.yaml";
        Map<String, RestrictionRule> restrictions = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(root, "restrictions", f).entrySet()) {
            String at = f + ": restrictions." + e.getKey();
            Map<String, Object> r = asMap(e.getValue(), at);
            String ruleStr = strReq(r, "rule", at);
            RestrictionRule.Kind kind;
            if ("prohibited".equals(ruleStr)) {
                kind = RestrictionRule.Kind.PROHIBITED;
            } else if ("special".equals(ruleStr)) {
                kind = RestrictionRule.Kind.SPECIAL;
            } else {
                throw new CalcException(at + ".rule: ожидается prohibited|special, получено '" + ruleStr + "'");
            }
            List<DnBand> offsets = null;
            if (r.containsKey("min_offset_by_dn")) {
                offsets = bands(listOfMaps(r, "min_offset_by_dn", at), "offset_m", at + ".min_offset_by_dn");
                checkCoversAll(offsets, diameters, at + ".min_offset_by_dn");
            }
            RestrictionRule.ZoneKind zone = RestrictionRule.ZoneKind.NONE;
            double margin = 0;
            if (r.containsKey("special_zone")) {
                Map<String, Object> z = map(r, "special_zone", at);
                String zk = strReq(z, "kind", at + ".special_zone");
                if ("polygon_margin".equals(zk)) {
                    zone = RestrictionRule.ZoneKind.POLYGON_MARGIN;
                } else if ("point_margin".equals(zk)) {
                    zone = RestrictionRule.ZoneKind.POINT_MARGIN;
                } else {
                    throw new CalcException(at + ".special_zone.kind: неизвестное значение '" + zk + "'");
                }
                margin = dblReq(z, "margin_m", at + ".special_zone");
            }
            restrictions.put(e.getKey(), new RestrictionRule(e.getKey(), kind, dblOpt(r, "min_offset_m"), offsets,
                    dblOpt(r, "min_crossing_angle_deg"), dblOpt(r, "k_spec"), zone, margin,
                    Boolean.TRUE.equals(r.get("team_default"))));
        }

        Map<String, Object> overlap = map(root, "overlap", f);
        if (!"max".equals(strReq(overlap, "k_spec_mode", f + ": overlap"))) {
            throw new CalcException(f + ": overlap.k_spec_mode поддерживается только 'max' (протокол 16.09, п. 9)");
        }
        Map<String, Object> angle = map(root, "angle", f);
        List<Double> stdAngles = new ArrayList<>();
        for (Object o : list(angle, "standard_angles_deg", f + ": angle")) {
            stdAngles.add(num(o, f + ": angle.standard_angles_deg").doubleValue());
        }
        Map<String, Object> network = map(root, "network", f);
        Map<String, Object> geometry = map(root, "geometry", f);
        Map<String, Object> costs = map(root, "costs", f);
        List<DnBand> chamberScale = bands(listOfMaps(costs, "chamber_scale", f + ": costs"), "cost_rub",
                f + ": costs.chamber_scale");
        checkCoversAll(chamberScale, diameters, f + ": costs.chamber_scale");
        Map<String, Object> penalty = map(costs, "unconnected_penalty", f + ": costs");
        Map<String, Object> ranking = map(root, "ranking", f);
        Map<String, Object> routing = map(root, "routing", f);

        return new RulesConfig(restrictions,
                Boolean.TRUE.equals(overlap.get("prohibited_has_priority")),
                dblReq(angle, "k_angle", f + ": angle"), stdAngles,
                dblReq(angle, "angle_tolerance_deg", f + ": angle"),
                dblReq(network, "tie_in_chamber_radius_m", f + ": network"),
                intReq(network, "max_segments_per_chamber", f + ": network"),
                intReq(network, "length_limit_max_dn_steps", f + ": network"),
                dblReq(geometry, "tolerance_m", f + ": geometry"),
                dblReq(routing, "workspace_margin_m", f + ": routing"),
                dblReq(routing, "vertex_outward_m", f + ": routing"),
                dblReq(routing, "turn_penalty_m", f + ": routing"),
                dblReq(routing, "grid_cell_m", f + ": routing"),
                intReq(routing, "grid_fallback_min_vertices", f + ": routing"),
                dblOptOr(routing, "vertex_simplify_m", 0.005),
                longOptOr(routing, "vg_deadline_ms", 12_000L),
                longOptOr(routing, "grid_deadline_ms", 8_000L),
                longOptOr(routing, "route_budget_ms", 20_000L),
                longOptOr(routing, "crossing_budget_ms", 15_000L),
                dblOptOr(routing, "cluster_prefix_m", 20.0),
                dblOptOr(routing, "joint_group_m", 250.0),
                (int) longOptOr(routing, "tie_in_attempts", 2L),
                dblOptOr(routing, "start_exit_max_m", 12.0),
                longPositive(costs, "tie_in_rub", f + ": costs"),
                chamberScale,
                longPositive(penalty, "fixed_rub", f + ": costs.unconnected_penalty"),
                longPositive(penalty, "per_tph_rub", f + ": costs.unconnected_penalty"),
                dblReq(ranking, "weight_cost", f + ": ranking"),
                dblReq(ranking, "weight_length", f + ": ranking"),
                dblReq(ranking, "base_cost_rub", f + ": ranking"),
                dblReq(ranking, "base_length_m", f + ": ranking"),
                intReq(ranking, "score_scale", f + ": ranking"),
                intReq(ranking, "max_variants", f + ": ranking"));
    }

    // ------------------------------------------------------------------ depth

    static DepthRules parseDepth(Map<String, Object> root) {
        String f = "depth-rules.yaml";
        Map<String, Object> d = map(root, "depth", f);
        Map<String, Object> cost = map(d, "cost", f + ": depth");
        Map<String, DepthRules.Utility> utilities = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(root, "existing_utilities", f).entrySet()) {
            String at = f + ": existing_utilities." + e.getKey();
            Map<String, Object> u = asMap(e.getValue(), at);
            boolean byDn = Boolean.TRUE.equals(u.get("gabarit_by_dn"));
            Double w = dblOpt(u, "width_m");
            Double h = dblOpt(u, "height_m");
            if (!byDn && (w == null || h == null)) {
                throw new CalcException(at + ": нужны width_m и height_m либо gabarit_by_dn: true");
            }
            utilities.put(e.getKey(), new DepthRules.Utility(w, h, byDn, dblReq(u, "top_depth_m", at),
                    dblReq(u, "vertical_clearance_m", at)));
        }
        Map<String, DepthRules.SurfaceCrossing> surface = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map(root, "surface_crossings", f).entrySet()) {
            String at = f + ": surface_crossings." + e.getKey();
            Map<String, Object> s = asMap(e.getValue(), at);
            surface.put(e.getKey(), new DepthRules.SurfaceCrossing(
                    dblReq(s, "min_top_depth_below_surface_m", at), dblReq(s, "zone_margin_m", at)));
        }
        Double step = dblOpt(d, "step_m");
        if (step != null && step <= 0) {
            throw new CalcException(f + ": depth.step_m должен быть > 0 или null");
        }
        return new DepthRules(dblReq(d, "normal_depth_m", f), dblReq(d, "min_depth_m", f),
                dblReq(d, "min_offset_m", f), step, dblReq(d, "max_slope_m_per_m", f),
                dblReq(d, "point_crossing_plateau_m", f),
                dblReq(cost, "threshold_depth_m", f + ": depth.cost"),
                dblReq(cost, "rate_per_extra_m", f + ": depth.cost"), utilities, surface);
    }

    // ------------------------------------------------------------------ helpers

    private static void checkCoversAll(List<DnBand> bands, DiameterTable diameters, String at) {
        for (DiameterSpec s : diameters.all()) {
            int hits = 0;
            for (DnBand b : bands) {
                if (b.contains(s.getDn())) {
                    hits++;
                }
            }
            if (hits != 1) {
                throw new CalcException(at + ": DN" + s.getDn() + " попадает в " + hits + " диапазон(ов), нужен ровно 1");
            }
        }
    }

    private static List<DnBand> bands(List<Map<String, Object>> rows, String valueKey, String at) {
        List<DnBand> result = new ArrayList<>();
        int i = 0;
        for (Map<String, Object> row : rows) {
            String rowAt = at + "[" + i++ + "]";
            int from = intReq(row, "dn_from", rowAt);
            int to = intReq(row, "dn_to", rowAt);
            if (to < from) {
                throw new CalcException(rowAt + ": dn_to < dn_from");
            }
            result.add(new DnBand(from, to, dblReq(row, valueKey, rowAt)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new CalcException("Не найден файл конфигурации: " + file.toAbsolutePath());
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object data = new Yaml().load(reader);
            if (!(data instanceof Map)) {
                throw new CalcException(file.getFileName() + ": корень YAML должен быть объектом");
            }
            return (Map<String, Object>) data;
        } catch (IOException ex) {
            throw new CalcException("Не удалось прочитать " + file + ": " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            if (ex instanceof CalcException) {
                throw ex;
            }
            throw new CalcException("Ошибка разбора YAML " + file.getFileName() + ": " + ex.getMessage(), ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String at) {
        if (!(o instanceof Map)) {
            throw new CalcException(at + ": ожидается объект");
        }
        return (Map<String, Object>) o;
    }

    private static Map<String, Object> map(Map<String, Object> m, String key, String at) {
        if (!m.containsKey(key)) {
            throw new CalcException(at + ": отсутствует раздел '" + key + "'");
        }
        return asMap(m.get(key), at + "." + key);
    }

    private static List<?> list(Map<String, Object> m, String key, String at) {
        Object o = m.get(key);
        if (!(o instanceof List)) {
            throw new CalcException(at + ": '" + key + "' должен быть списком");
        }
        return (List<?>) o;
    }

    private static List<Map<String, Object>> listOfMaps(Map<String, Object> m, String key, String at) {
        List<Map<String, Object>> result = new ArrayList<>();
        int i = 0;
        for (Object o : list(m, key, at)) {
            result.add(asMap(o, at + "." + key + "[" + i++ + "]"));
        }
        if (result.isEmpty()) {
            throw new CalcException(at + ": список '" + key + "' пуст");
        }
        return result;
    }

    private static Number num(Object o, String at) {
        if (!(o instanceof Number)) {
            throw new CalcException(at + ": ожидается число, получено '" + o + "'");
        }
        return (Number) o;
    }

    private static String strReq(Map<String, Object> m, String key, String at) {
        Object o = m.get(key);
        if (o == null) {
            throw new CalcException(at + ": отсутствует поле '" + key + "'");
        }
        return o.toString();
    }

    private static double dblReq(Map<String, Object> m, String key, String at) {
        if (m.get(key) == null) {
            throw new CalcException(at + ": отсутствует поле '" + key + "'");
        }
        return num(m.get(key), at + "." + key).doubleValue();
    }

    private static Double dblOpt(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o == null ? null : num(o, key).doubleValue();
    }

    private static double dblOptOr(Map<String, Object> m, String key, double fallback) {
        Double v = dblOpt(m, key);
        return v == null ? fallback : v;
    }

    private static long longOptOr(Map<String, Object> m, String key, long fallback) {
        Object o = m.get(key);
        if (o == null) {
            return fallback;
        }
        return num(o, key).longValue();
    }

    private static int intReq(Map<String, Object> m, String key, String at) {
        Number n = num(m.get(key), at + "." + key);
        if (n.doubleValue() != Math.rint(n.doubleValue())) {
            throw new CalcException(at + "." + key + ": ожидается целое число");
        }
        return n.intValue();
    }

    private static long longPositive(Map<String, Object> m, String key, String at) {
        Number n = num(m.get(key), at + "." + key);
        if (n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() <= 0) {
            throw new CalcException(at + "." + key + ": ожидается целое положительное число рублей");
        }
        return n.longValue();
    }

    private static double positive(double v, String at) {
        if (!(v > 0)) {
            throw new CalcException(at + ": значение должно быть > 0");
        }
        return v;
    }
}
