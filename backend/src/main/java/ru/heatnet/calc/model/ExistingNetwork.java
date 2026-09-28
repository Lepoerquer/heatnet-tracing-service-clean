package ru.heatnet.calc.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.heatnet.calc.CalcException;

/** Существующая сеть как дерево по upstream_object_id (строит ingest/network). */
public final class ExistingNetwork {

    private final Map<String, ExistingSegment> segments;
    private final Map<String, ExistingChamber> chambers;
    private final Set<String> sourceIds;
    private final Map<String, List<ExistingSegment>> segmentsByUpstream;

    public ExistingNetwork(Collection<ExistingSegment> segmentList, Collection<ExistingChamber> chamberList,
                           Collection<String> sources) {
        Map<String, ExistingSegment> s = new LinkedHashMap<>();
        Map<String, List<ExistingSegment>> byUp = new HashMap<>();
        for (ExistingSegment seg : segmentList) {
            if (s.put(seg.getId(), seg) != null) {
                throw new CalcException("Существующая сеть: участок " + seg.getId() + " указан дважды");
            }
            if (seg.getUpstreamObjectId() != null) {
                byUp.computeIfAbsent(seg.getUpstreamObjectId(), k -> new ArrayList<>()).add(seg);
            }
        }
        Map<String, ExistingChamber> c = new LinkedHashMap<>();
        for (ExistingChamber ch : chamberList) {
            if (c.put(ch.getId(), ch) != null || s.containsKey(ch.getId())) {
                throw new CalcException("Существующая сеть: id " + ch.getId() + " не уникален");
            }
        }
        this.segments = Collections.unmodifiableMap(s);
        this.chambers = Collections.unmodifiableMap(c);
        this.sourceIds = Collections.unmodifiableSet(new HashSet<>(sources));
        this.segmentsByUpstream = byUp;
    }

    public ExistingSegment segment(String id) {
        ExistingSegment seg = segments.get(id);
        if (seg == null) {
            throw new CalcException("Существующий участок " + id + " не найден");
        }
        return seg;
    }

    public ExistingChamber chamber(String id) {
        ExistingChamber ch = chambers.get(id);
        if (ch == null) {
            throw new CalcException("Существующая камера " + id + " не найдена");
        }
        return ch;
    }

    public boolean isSegment(String id) {
        return segments.containsKey(id);
    }

    public boolean isChamber(String id) {
        return chambers.containsKey(id);
    }

    public boolean isSource(String id) {
        return sourceIds.contains(id);
    }

    public Map<String, ExistingSegment> getSegments() {
        return segments;
    }

    public Map<String, ExistingChamber> getChambers() {
        return chambers;
    }

    /** Участки, у которых upstream_object_id = objectId (примыкают со стороны потребителей). */
    public List<ExistingSegment> downstreamSegmentsOf(String objectId) {
        return Collections.unmodifiableList(
                segmentsByUpstream.getOrDefault(objectId, Collections.<ExistingSegment>emptyList()));
    }
}
