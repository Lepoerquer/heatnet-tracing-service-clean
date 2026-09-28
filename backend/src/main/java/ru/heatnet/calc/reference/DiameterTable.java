package ru.heatnet.calc.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import ru.heatnet.calc.CalcException;

/** Табл. 4.1: номенклатура условных диаметров, упорядоченная по возрастанию DN. */
public final class DiameterTable {

    private final List<DiameterSpec> specs;

    public DiameterTable(List<DiameterSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new CalcException("Таблица диаметров пуста");
        }
        List<DiameterSpec> copy = new ArrayList<>(specs);
        for (int i = 1; i < copy.size(); i++) {
            DiameterSpec prev = copy.get(i - 1);
            DiameterSpec cur = copy.get(i);
            if (cur.getDn() <= prev.getDn()) {
                throw new CalcException("Таблица диаметров: DN должны строго возрастать, нарушено на " + cur);
            }
            if (cur.getCapacityTph() <= prev.getCapacityTph()) {
                throw new CalcException("Таблица диаметров: пропускная способность должна возрастать, нарушено на " + cur);
            }
        }
        this.specs = Collections.unmodifiableList(copy);
    }

    public List<DiameterSpec> all() {
        return specs;
    }

    public int size() {
        return specs.size();
    }

    /** Строка таблицы для DN; исключение, если такого DN нет в номенклатуре. */
    public DiameterSpec spec(int dn) {
        return specs.get(indexOf(dn));
    }

    /** Позиция DN в номенклатуре (0 = DN50). */
    public int indexOf(int dn) {
        for (int i = 0; i < specs.size(); i++) {
            if (specs.get(i).getDn() == dn) {
                return i;
            }
        }
        throw new CalcException("DN" + dn + " отсутствует в номенклатуре табл. 4.1");
    }

    public DiameterSpec byIndex(int index) {
        return specs.get(index);
    }

    public boolean contains(int dn) {
        for (DiameterSpec s : specs) {
            if (s.getDn() == dn) {
                return true;
            }
        }
        return false;
    }

    /**
     * Минимальный DN, пропускная способность которого не меньше расхода (разд. 3, 7).
     * Пусто, если расход больше пропускной способности наибольшего DN.
     */
    public Optional<DiameterSpec> minFor(double flowTph) {
        if (Double.isNaN(flowTph) || flowTph < 0) {
            throw new CalcException("Некорректный расход: " + flowTph + " т/ч");
        }
        for (DiameterSpec s : specs) {
            if (s.getCapacityTph() >= flowTph) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Следующая номенклатура после DN (200 → 250). Пусто для наибольшего DN. */
    public Optional<DiameterSpec> next(int dn) {
        int i = indexOf(dn);
        return i + 1 < specs.size() ? Optional.of(specs.get(i + 1)) : Optional.<DiameterSpec>empty();
    }
}
