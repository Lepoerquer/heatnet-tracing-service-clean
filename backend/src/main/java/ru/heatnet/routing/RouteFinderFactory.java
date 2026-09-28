package ru.heatnet.routing;

import org.springframework.stereotype.Service;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

import ru.heatnet.calc.DiameterSelector;
import ru.heatnet.calc.reference.ReferenceData;
import ru.heatnet.calc.reference.RestrictionRule;
import ru.heatnet.ingest.IngestResult;
import ru.heatnet.rules.RestrictionEngineFactory;
import ru.heatnet.rules.SpatialConstraintBundle;

/**
 * Фабрика M3: связывает M2 bundle с PortaledVisibilityRouteFinder.
 */
@Service
public class RouteFinderFactory {

    private final RestrictionEngineFactory engineFactory;
    private final ReferenceData referenceData;

    public RouteFinderFactory(RestrictionEngineFactory engineFactory, ReferenceData referenceData) {
        this.engineFactory = engineFactory;
        this.referenceData = referenceData;
    }

    public RouteFinder create(SpatialConstraintBundle bundle, int referenceDn) {
        PortaledVisibilityRouteFinder finder = new PortaledVisibilityRouteFinder(
                bundle.getEngine(),
                bundle.getBlockedOutlines(),
                referenceData.getRules(),
                referenceDn);
        finder.setOwnApproach(bundle.getOwnApproachPolygon(), null);
        return finder;
    }

    public RouteFinder createForLeaf(IngestResult ingest, double oksFlowTph, Point fromUtm, Point toUtm) {
        return createForFlow(ingest, oksFlowTph, fromUtm, toUtm);
    }

    public RouteFinder createForMagistral(IngestResult ingest, double totalFlowTph, Point fromUtm, Point toUtm) {
        return createForFlow(ingest, totalFlowTph, fromUtm, toUtm);
    }

    public RouteFinder createForLeaf(SpatialConstraintBundle bundle, double flowTph) {
        return create(bundle, leafDn(flowTph));
    }

    /**
     * @deprecated Используйте {@link #createForLeaf(IngestResult, double, Point, Point)} —
     * без toUtm отключается §2.2 (исключение полигона ОКС).
     */
    @Deprecated
    public RouteFinder createForLeaf(IngestResult ingest, double oksFlowTph) {
        throw new UnsupportedOperationException(
                "createForLeaf без toUtm отключает §2.2; используйте createForLeaf(ingest, flow, fromUtm, toUtm)");
    }

    /**
     * @deprecated Используйте {@link #createForMagistral(IngestResult, double, Point, Point)}.
     */
    @Deprecated
    public RouteFinder createForMagistral(IngestResult ingest, double totalFlowTph) {
        throw new UnsupportedOperationException(
                "createForMagistral без toUtm отключает §2.2; используйте createForMagistral(ingest, flow, fromUtm, toUtm)");
    }

    public RouteFinder createForMagistral(SpatialConstraintBundle bundle, double totalFlowTph) {
        return create(bundle, magistralDn(totalFlowTph));
    }

    public int leafDn(double oksFlowTph) {
        return selector().minDiameter(oksFlowTph);
    }

    public int magistralDn(double totalFlowTph) {
        return selector().minDiameter(totalFlowTph);
    }

    public SpatialConstraintBundle bundle(IngestResult ingest, int dn) {
        return engineFactory.createBundle(ingest, dn);
    }

    public SpatialConstraintBundle bundleForTarget(IngestResult ingest, int dn, Point targetUtm) {
        return engineFactory.createBundleForTarget(ingest, dn, targetUtm);
    }

    public org.locationtech.jts.geom.Geometry ownOksGeometry(IngestResult ingest, Point targetUtm) {
        return engineFactory.ownOksGeometry(ingest, targetUtm);
    }

    private RouteFinder createForFlow(IngestResult ingest, double flowTph) {
        return createForFlow(ingest, flowTph, null, null);
    }

    private RouteFinder createForFlow(IngestResult ingest, double flowTph, Point fromUtm, Point toUtm) {
        return createForDn(ingest, selector().minDiameter(flowTph), toUtm);
    }

    /**
     * Маршрутизатор ввода к точке {@code toUtm} с буферами по {@code dn}.
     *
     * <p>AUDIT-24.09 (Claude): если точка лежит в своём полигоне ОКС, сначала работает
     * {@link EntryAwareRouteFinder} (финальный прямой заход §2.2 как у дерева Штейнера), а прежний
     * {@link PortaledVisibilityRouteFinder} остаётся запасным. Раньше был только прежний — на конкурсном
     * наборе он не находил ни одного ввода, а все запасные ветви планировщика (перетрассировка после
     * M5, отдельный ввод, совместный планировщик) опираются на него.</p>
     */
    public RouteFinder createForDn(IngestResult ingest, int dn, Point toUtm) {
        SpatialConstraintBundle bundle = toUtm == null
                ? engineFactory.createBundle(ingest, dn)
                : engineFactory.createBundleForTarget(ingest, dn, toUtm);
        PortaledVisibilityRouteFinder finder = new PortaledVisibilityRouteFinder(
                bundle.getEngine(),
                bundle.getBlockedOutlines(),
                referenceData.getRules(),
                dn);
        finder.setOwnApproach(bundle.getOwnApproachPolygon(), toUtm);
        if (toUtm == null) {
            return finder;
        }
        Geometry own = engineFactory.ownOksGeometry(ingest, toUtm);
        if (own == null || own.isEmpty()) {
            return finder;
        }
        RestrictionRule rule = referenceData.getRules().getRestrictions().get("oks_existing");
        double clearance = (rule == null ? 5.0 : rule.minOffsetM(dn))
                + referenceData.getGabarits().spec(dn).getWidthM() / 2.0;
        return new EntryAwareRouteFinder(engineFactory.createBundle(ingest, dn), bundle.getEngine(), own,
                clearance, referenceData.getRules(), dn, finder);
    }

    private DiameterSelector selector() {
        return new DiameterSelector(referenceData.getDiameters());
    }
}
