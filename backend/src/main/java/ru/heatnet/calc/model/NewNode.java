package ru.heatnet.calc.model;

import java.util.Objects;

/** Узел дерева новой сети. Геометрия здесь не нужна — её держит network/. */
public final class NewNode {

    private final String id;
    private final NodeKind kind;
    private final String oksId;
    private final double oksFlowTph;

    private NewNode(String id, NodeKind kind, String oksId, double oksFlowTph) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.oksId = oksId;
        this.oksFlowTph = oksFlowTph;
    }

    public static NewNode tieIn(String id) {
        return new NewNode(id, NodeKind.TIE_IN, null, 0);
    }

    public static NewNode chamber(String id) {
        return new NewNode(id, NodeKind.NEW_CHAMBER, null, 0);
    }

    public static NewNode technical(String id) {
        return new NewNode(id, NodeKind.TECHNICAL_NODE, null, 0);
    }

    /**
     * Точка подключения ОКС.
     *
     * @param id         id точки подключения (oks_connection_point.id)
     * @param oksId      id ОКС (если в данных нет oks_id — тот же id точки)
     * @param flowTph    расчётный расход ОКС flow_tph, т/ч (heat_load не используется)
     */
    public static NewNode oks(String id, String oksId, double flowTph) {
        return new NewNode(id, NodeKind.OKS_CONNECTION, oksId == null ? id : oksId, flowTph);
    }

    public String getId() {
        return id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public String getOksId() {
        return oksId;
    }

    public double getOksFlowTph() {
        return oksFlowTph;
    }

    @Override
    public String toString() {
        return kind + "(" + id + ")";
    }
}
