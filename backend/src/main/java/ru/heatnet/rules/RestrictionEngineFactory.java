package ru.heatnet.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.geo.ProjectionService;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.rules.model.PortalGate;
import ru.heatnet.rules.model.RestrictionFeature;

/**
 * Фабрика движка M2 по результату ingest.
 * referenceDn — DN для построения буферов (листовой DN или сумма группы, см. M3).
 * Кэш бандла живёт в {@link ThreadLocal} на время одного {@code plan()} / job.
 */
@Service
public class RestrictionEngineFactory {

    /** Допуск «точка на своём полигоне», м. */
    public static final double OWN_POLYGON_TOLERANCE_M = 0.05;

    private static final ThreadLocal<Map<String, SpatialConstraintBundle>> REQUEST_CACHE =
            ThreadLocal.withInitial(LinkedHashMap::new);

    private final IngestRestrictionsLoader restrictionsLoader;
    private final ReferenceData referenceData;
    private final ProjectionService projectionService;

    public RestrictionEngineFactory(IngestRestrictionsLoader restrictionsLoader,
                                    ReferenceData referenceData,
                                    ProjectionService projectionService) {
        this.restrictionsLoader = restrictionsLoader;
        this.referenceData = referenceData;
        this.projectionService = projectionService;
    }

    /** Начало запроса: сбрасывает кэш бандлов текущего потока. */
    public static void beginRequest() {
        REQUEST_CACHE.get().clear();
    }

    /** Конец запроса: снимает кэш, чтобы не удерживать геометрию. */
    public static void endRequest() {
        REQUEST_CACHE.remove();
    }

    public SpatialConstraintEngine create(IngestResult ingest, int referenceDn) {
        return createBundle(ingest, referenceDn).getEngine();
    }

    /** Движок для юнит-тестов и синтетики без ingest. Без кэша — списки фич разные. */
    public SpatialConstraintEngine create(List<RestrictionFeature> features, int referenceDn) {
        return createBundle(features, referenceDn).getEngine();
    }

    public SpatialConstraintBundle createBundle(IngestResult ingest, int referenceDn) {
        return fullBuild(ingest, referenceDn).bundle;
    }

    /**
     * §2.2: полигон ОКС, содержащий целевую точку, исключается из запретных буферов.
     * Остальные ограничения действуют. Финальный прямой заход добавляет маршрутизатор.
     *
     * <p>AUDIT-13 (Claude, 25.09): набор «без своего полигона» собирается из уже построенных зон полного набора
     * (см. {@link #fullBuild}) — зоны каждого ограничения зависят только от него самого и ДУ, поэтому результат тот
     * же, что у сборки заново по отфильтрованному списку (тот же порядок, тот же STRtree), но без повторного
     * построения буферов всех ограничений на каждую цель (~14 % времени расчёта конкурсного набора).</p>
     */
    public SpatialConstraintBundle createBundleForTarget(IngestResult ingest, int referenceDn, Point targetUtm) {
        if (targetUtm == null) {
            return createBundle(ingest, referenceDn);
        }
        String targetKey = Math.round(targetUtm.getX()) + "_" + Math.round(targetUtm.getY());
        String key = System.identityHashCode(ingest) + "|" + referenceDn + "|" + targetKey;
        return cached(key, () -> {
            FullBuild full = fullBuild(ingest, referenceDn);
            List<RestrictionFeature> features = full.features;
            RestrictionFeature own = findOwnOks(features, targetUtm);
            List<IndexedRestriction> kept = new ArrayList<>(features.size());
            List<PortalGate> gates = new ArrayList<>();
            Map<String, IndexedRestriction> byId = new LinkedHashMap<>();
            List<Geometry> outlines = new ArrayList<>();
            for (int i = 0; i < features.size(); i++) {
                RestrictionFeature feature = features.get(i);
                if (own != null && own.getId().equals(feature.getId())) {
                    continue;
                }
                if (isOwnOksPolygon(feature, targetUtm)) {
                    continue;
                }
                IndexedRestriction item = full.indexed.get(i);
                kept.add(item);
                gates.addAll(item.getPortalGates());
                byId.put(item.getId(), item);
                outlines.addAll(full.outlines.get(i));
            }
            SpatialConstraintBundle bundle = bundleOf(RestrictionSpatialIndex.build(kept), full.bufferFactory,
                    gates, byId, referenceDn, outlines);
            return own == null ? bundle : bundle.withOwnApproach(own.getGeometryUtm());
        });
    }

    /** Полигон restriction:oks, внутри которого лежит точка подключения. */
    public Geometry ownOksGeometry(IngestResult ingest, Point targetUtm) {
        if (ingest == null || targetUtm == null) {
            return null;
        }
        List<RestrictionFeature> features = features(ingest);
        RestrictionFeature own = findOwnOks(features, targetUtm);
        return own == null ? null : own.getGeometryUtm();
    }

    // ------------------------------------------------------------------ AUDIT-13: общий набор на весь расчёт

    /**
     * AUDIT-13 (Claude, 25.09). Полный набор ограничений (зоны, индекс, контуры) для одного ingest и ДУ. Раньше
     * он жил только в кэше запроса одного {@code NetworkPlanner.plan()} и строился заново для каждой стратегии M7
     * и каждого уточняющего прохода (6 раз на конкурсном наборе), а наборы «без своего полигона» — заново по
     * всем ограничениям для каждой цели. Входные данные расчёта не меняются, зоны зависят только от ограничения
     * и ДУ, поэтому результат повторной сборки совпадает с первой. Кэш привязан к объекту ingest слабой ссылкой
     * (WeakHashMap): после расчёта геометрия освобождается вместе с ним; на один ingest хранится не больше
     * {@value #FULL_BUILDS_PER_INGEST} ДУ.
     */
    private static final int FULL_BUILDS_PER_INGEST = 6;

    private static final Map<IngestResult, IngestCache> INGEST_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<IngestResult, IngestCache>());

    private static final class IngestCache {
        private List<RestrictionFeature> features;
        private final Map<Integer, FullBuild> byDn = new LinkedHashMap<Integer, FullBuild>(8, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, FullBuild> eldest) {
                return size() > FULL_BUILDS_PER_INGEST;
            }
        };
    }

    /** Полный набор: исходные ограничения, их зоны (в том же порядке) и контуры по каждому ограничению. */
    private static final class FullBuild {
        private final List<RestrictionFeature> features;
        private final List<IndexedRestriction> indexed;
        private final List<List<Geometry>> outlines;
        private final BufferFactory bufferFactory;
        private final SpatialConstraintBundle bundle;

        private FullBuild(List<RestrictionFeature> features, List<IndexedRestriction> indexed,
                          List<List<Geometry>> outlines, BufferFactory bufferFactory, SpatialConstraintBundle bundle) {
            this.features = features;
            this.indexed = indexed;
            this.outlines = outlines;
            this.bufferFactory = bufferFactory;
            this.bundle = bundle;
        }
    }

    private IngestCache ingestCache(IngestResult ingest) {
        synchronized (INGEST_CACHE) {
            return INGEST_CACHE.computeIfAbsent(ingest, k -> new IngestCache());
        }
    }

    private List<RestrictionFeature> features(IngestResult ingest) {
        IngestCache cache = ingestCache(ingest);
        synchronized (cache) {
            if (cache.features == null) {
                cache.features = java.util.Collections.unmodifiableList(
                        new ArrayList<>(restrictionsLoader.load(ingest, referenceData)));
            }
            return cache.features;
        }
    }

    private FullBuild fullBuild(IngestResult ingest, int referenceDn) {
        IngestCache cache = ingestCache(ingest);
        synchronized (cache) {
            FullBuild hit = cache.byDn.get(referenceDn);
            if (hit != null) {
                return hit;
            }
        }
        List<RestrictionFeature> features = features(ingest);
        RestrictionIndexBuilder builder = new RestrictionIndexBuilder(referenceData, projectionService.utmFactory());
        RestrictionIndexBuilder.BuiltIndex built =
                builder.build(features, referenceDn, referenceData.getRules().getGeometryToleranceM());
        List<IndexedRestriction> indexed = built.getSpatialIndex().allRestrictions();
        List<List<Geometry>> outlines = new ArrayList<>(indexed.size());
        List<Geometry> all = new ArrayList<>();
        for (IndexedRestriction restriction : indexed) {
            List<Geometry> own = outlinesOf(restriction);
            outlines.add(own);
            all.addAll(own);
        }
        SpatialConstraintBundle bundle = bundleOf(built.getSpatialIndex(), built.getBufferFactory(),
                built.getPortalGates(), built.getById(), referenceDn, all);
        FullBuild created = new FullBuild(features, indexed, outlines, built.getBufferFactory(), bundle);
        synchronized (cache) {
            FullBuild raced = cache.byDn.get(referenceDn);
            if (raced != null) {
                return raced;
            }
            cache.byDn.put(referenceDn, created);
        }
        return created;
    }

    private SpatialConstraintBundle bundleOf(RestrictionSpatialIndex index, BufferFactory bufferFactory,
                                             List<PortalGate> gates, Map<String, IndexedRestriction> byId,
                                             int referenceDn, List<Geometry> outlines) {
        SpatialConstraintEngine engine = new DefaultSpatialConstraintEngine(
                index,
                bufferFactory,
                new PortalGateGenerator(projectionService.utmFactory()),
                referenceData.getRules(),
                gates,
                byId,
                referenceDn);
        return new SpatialConstraintBundle(engine, outlines);
    }

    public static boolean isOwnOksPolygon(RestrictionFeature feature, Point targetUtm) {
        if (feature == null || targetUtm == null || feature.getGeometryUtm() == null) {
            return false;
        }
        if (!"oks_existing".equals(feature.getRulesKey())) {
            return false;
        }
        try {
            // AUDIT-24.09 (Claude): точка на границе полигона после перепроецирования может оказаться
            // снаружи на доли миллиметра — считаем её «своей» с допуском 5 см.
            return feature.getGeometryUtm().covers(targetUtm)
                    || feature.getGeometryUtm().distance(targetUtm) <= OWN_POLYGON_TOLERANCE_M;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static RestrictionFeature findOwnOks(List<RestrictionFeature> features, Point targetUtm) {
        if (features == null || targetUtm == null) {
            return null;
        }
        for (RestrictionFeature feature : features) {
            if (isOwnOksPolygon(feature, targetUtm)) {
                return feature;
            }
        }
        return null;
    }

    public SpatialConstraintBundle createBundle(List<RestrictionFeature> features, int referenceDn) {
        RestrictionIndexBuilder builder = new RestrictionIndexBuilder(referenceData, projectionService.utmFactory());
        double toleranceM = referenceData.getRules().getGeometryToleranceM();
        RestrictionIndexBuilder.BuiltIndex built = builder.build(features, referenceDn, toleranceM);
        PortalGateGenerator portalGenerator = new PortalGateGenerator(projectionService.utmFactory());
        SpatialConstraintEngine engine = new DefaultSpatialConstraintEngine(
                built.getSpatialIndex(),
                built.getBufferFactory(),
                portalGenerator,
                referenceData.getRules(),
                built.getPortalGates(),
                built.getById(),
                referenceDn);
        return new SpatialConstraintBundle(engine, collectBlockedOutlines(built.getSpatialIndex()));
    }

    private static SpatialConstraintBundle cached(String key, Supplier<SpatialConstraintBundle> factory) {
        Map<String, SpatialConstraintBundle> cache = REQUEST_CACHE.get();
        SpatialConstraintBundle hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        SpatialConstraintBundle created = factory.get();
        cache.put(key, created);
        return created;
    }

    private static List<Geometry> collectBlockedOutlines(RestrictionSpatialIndex index) {
        List<Geometry> outlines = new ArrayList<>();
        for (IndexedRestriction restriction : index.allRestrictions()) {
            outlines.addAll(outlinesOf(restriction));
        }
        return outlines;
    }

    /** Контуры запретной зоны одного ограничения (зона ∪ коридор отступа), по полигонам. */
    private static List<Geometry> outlinesOf(IndexedRestriction restriction) {
        Geometry blocked = restriction.getBlockedZone();
        Geometry offset = restriction.getPolygonOffsetCorridor();
        Geometry outline = blocked;
        if (offset != null && !offset.isEmpty()) {
            try {
                outline = blocked == null || blocked.isEmpty() ? offset : blocked.union(offset);
            } catch (RuntimeException ex) {
                outline = offset;
            }
        }
        if (outline == null || outline.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Geometry> out = new ArrayList<>(1);
        appendOutline(out, outline);
        return out;
    }

    private static void appendOutline(List<Geometry> target, Geometry geometry) {
        if (geometry instanceof Polygon) {
            target.add(geometry);
            return;
        }
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry part = geometry.getGeometryN(i);
            if (part instanceof Polygon && !part.isEmpty()) {
                target.add(part);
            }
        }
    }
}
