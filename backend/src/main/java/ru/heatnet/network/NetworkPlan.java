package ru.heatnet.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ru.heatnet.calc.model.NewNetworkTree;
import ru.heatnet.cost.UnconnectedOks;

/** Результат M4: деревья новой сети, UTM-раскладки и неподключённые ОКС. */
public final class NetworkPlan {

    private final List<NewNetworkTree> trees;
    private final List<NetworkTreeLayout> layouts;
    private final List<UnconnectedOks> unconnectedOks;
    private final boolean jointConnection;
    /** QA-FIX H-7: нарушения топологии/геометрии, найденные при сборке плана. Норма — пусто. */
    private final List<String> diagnostics;

    public NetworkPlan(List<NewNetworkTree> trees, List<UnconnectedOks> unconnectedOks, boolean jointConnection) {
        this(trees, Collections.<NetworkTreeLayout>emptyList(), unconnectedOks, jointConnection);
    }

    public NetworkPlan(List<NewNetworkTree> trees, List<NetworkTreeLayout> layouts,
                       List<UnconnectedOks> unconnectedOks, boolean jointConnection) {
        this(trees, layouts, unconnectedOks, jointConnection, Collections.<String>emptyList());
    }

    public NetworkPlan(List<NewNetworkTree> trees, List<NetworkTreeLayout> layouts,
                       List<UnconnectedOks> unconnectedOks, boolean jointConnection,
                       List<String> diagnostics) {
        this.trees = Collections.unmodifiableList(trees);
        this.layouts = Collections.unmodifiableList(layouts);
        this.unconnectedOks = Collections.unmodifiableList(unconnectedOks);
        this.jointConnection = jointConnection;
        this.diagnostics = Collections.unmodifiableList(
                diagnostics == null ? new ArrayList<String>() : new ArrayList<>(diagnostics));
    }

    public List<NewNetworkTree> getTrees() {
        return trees;
    }

    public List<NetworkTreeLayout> getLayouts() {
        return layouts;
    }

    public List<UnconnectedOks> getUnconnectedOks() {
        return unconnectedOks;
    }

    public boolean isJointConnection() {
        return jointConnection;
    }

    /** QA-FIX H-7: список нарушений правил приложения в построенном плане. */
    public List<String> getDiagnostics() {
        return diagnostics;
    }
}
