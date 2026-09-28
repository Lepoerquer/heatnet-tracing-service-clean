package ru.heatnet.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import ru.heatnet.calc.TestReference;
import ru.heatnet.calc.model.ExistingObjectType;
import ru.heatnet.calc.model.TieInPoint;
import ru.heatnet.ingest.IngestResult;

class TieInCandidatesTest {

    @Test
    @DisplayName("камера ≤10 м: usesExistingChamberOnly, врезка в камеру")
    void chamberWithin10m() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        ExistingNetworkGeometry geom = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), TestNetworkFixtures.projection());
        Coordinate nearCh = geom.chamberPoint("ch_main");
        TieInCandidate best = TieInCandidates.bestNear(
                ingest.getExistingNetwork(), geom, TestReference.get().getRules(), nearCh, 100);
        assertNotNull(best);
        assertTrue(best.isUsesExistingChamberOnly());
        assertTrue(best.getTieIn().getExistingObjectType() == ExistingObjectType.HEAT_CHAMBER);
    }

    @Test
    @DisplayName("камера с degree=4 отсекается")
    void chamberDegreeLimit() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        ExistingNetworkGeometry geom = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), TestNetworkFixtures.projection());
        int max = TestReference.get().getRules().getMaxSegmentsPerChamber();
        int degree = ChamberDegreeCalculator.currentDegree(ingest.getExistingNetwork(), "ch_main");
        assertTrue(degree <= max);
    }

    @Test
    @DisplayName("проекция на трубу: intoPipe с distanceFromUpstreamEndM")
    void pipeProjection() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        ExistingNetworkGeometry geom = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), TestNetworkFixtures.projection());
        Coordinate mid = geom.pointAtDistanceFromUpstream("net_branch", geom.segmentLine("net_branch").getLength() / 2);
        ExistingNetworkGeometry.PipeProjection proj = geom.projectOnSegment("net_branch", mid, 2.0);
        assertNotNull(proj);
        assertTrue(proj.getDistanceFromUpstreamEndM() > 0);
    }

    @Test
    @DisplayName("ОКС вне радиуса камеры: кандидат только через проекцию на трубу")
    void oksOutsideChamberRadiusUsesPipe() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        ExistingNetworkGeometry geom = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), TestNetworkFixtures.projection());
        Coordinate mid = geom.pointAtDistanceFromUpstream("net_branch",
                geom.segmentLine("net_branch").getLength() * 0.5);
        TieInCandidate best = TieInCandidates.bestNear(
                ingest.getExistingNetwork(), geom, TestReference.get().getRules(), mid, 100);
        assertNotNull(best);
        assertFalse(best.isUsesExistingChamberOnly());
        assertTrue(best.getKind() == TieInCandidate.Kind.EXISTING_PIPE);
    }

    @Test
    @DisplayName("ближняя труба раньше далёкой камеры")
    void nearerPipeOutranksFarChamber() {
        TieInPoint chamberTie = TieInPoint.intoChamber("tie_c", "ch_far");
        TieInPoint pipeTie = TieInPoint.intoPipe("tie_p", "seg_near", 1.0, "nch");
        TieInCandidate farChamber = new TieInCandidate("c", TieInCandidate.Kind.EXISTING_CHAMBER,
                chamberTie, new Coordinate(0, 0), 180.0, 5_000_000L, true);
        TieInCandidate nearPipe = new TieInCandidate("p", TieInCandidate.Kind.EXISTING_PIPE,
                pipeTie, new Coordinate(20, 0), 23.0, 3_000_000L, false);
        List<TieInCandidate> list = Arrays.asList(farChamber, nearPipe);
        list.sort(TieInCandidates.ranking());
        assertEquals("p", list.get(0).getCandidateId());
    }

    @Test
    @DisplayName("попытки: ближайшая точка, затем камера")
    void pickAttemptsNearestThenChamber() {
        TieInPoint chamberTie = TieInPoint.intoChamber("tie_c", "ch_far");
        TieInPoint pipeTie = TieInPoint.intoPipe("tie_p", "seg_near", 1.0, "nch");
        TieInPoint pipe2Tie = TieInPoint.intoPipe("tie_p2", "seg_2", 1.0, "nch2");
        TieInCandidate nearPipe = new TieInCandidate("p", TieInCandidate.Kind.EXISTING_PIPE,
                pipeTie, new Coordinate(20, 0), 23.0, 3_000_000L, false);
        TieInCandidate farChamber = new TieInCandidate("c", TieInCandidate.Kind.EXISTING_CHAMBER,
                chamberTie, new Coordinate(0, 180), 180.0, 5_000_000L, true);
        TieInCandidate otherPipe = new TieInCandidate("p2", TieInCandidate.Kind.EXISTING_PIPE,
                pipe2Tie, new Coordinate(40, 0), 40.0, 3_000_000L, false);
        List<TieInCandidate> ranked = Arrays.asList(nearPipe, otherPipe, farChamber);
        ranked.sort(TieInCandidates.ranking());
        List<TieInCandidate> picked = TieInRouter.pickAttempts(ranked, 2, null);
        assertEquals(2, picked.size());
        assertEquals("p", picked.get(0).getCandidateId());
        assertEquals("c", picked.get(1).getCandidateId());
    }

    @Test
    @DisplayName("камера вне 10 м не предлагается как кандидат врезки")
    void farChamberExcluded() {
        IngestResult ingest = TestNetworkFixtures.simpleMagistral();
        ExistingNetworkGeometry geom = ExistingNetworkGeometry.fromIngest(
                ingest.getAcceptedFeatures(), ingest.getExistingNetwork(), TestNetworkFixtures.projection());
        Coordinate far = TestNetworkFixtures.projection().toUtm(37.6190, 55.6990);
        for (TieInCandidate c : TieInCandidates.findNear(
                ingest.getExistingNetwork(), geom, TestReference.get().getRules(), far, 100)) {
            if (c.getKind() == TieInCandidate.Kind.EXISTING_CHAMBER) {
                assertTrue(c.isUsesExistingChamberOnly());
                assertTrue(c.getReferenceDistanceM() <= TestReference.get().getRules().getTieInChamberRadiusM()
                        + TestReference.get().getRules().getGeometryToleranceM());
            }
        }
    }
}
