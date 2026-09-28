package ru.heatnet.network;

import ru.heatnet.calc.model.NewNetworkTree;

/** Дерево новой сети + UTM-раскладка для M4 (CrossingResolver, export). */
public final class BuiltNetworkTree {

    private final NewNetworkTree tree;
    private final NetworkTreeLayout layout;

    public BuiltNetworkTree(NewNetworkTree tree, NetworkTreeLayout layout) {
        this.tree = tree;
        this.layout = layout;
    }

    public NewNetworkTree getTree() {
        return tree;
    }

    public NetworkTreeLayout getLayout() {
        return layout;
    }
}
