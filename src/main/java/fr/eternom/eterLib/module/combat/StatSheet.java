package fr.eternom.eterLib.module.combat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Les statistiques d'une entité à un instant, et d'où vient chaque valeur (/eterlib stats). */
public final class StatSheet {

    private final Map<Stat, Double> totals = new EnumMap<>(Stat.class);
    private final Map<Stat, Map<String, Double>> sources = new EnumMap<>(Stat.class);

    /** Ajoute value à stat, venant de source (« armure », « objet : épée », « EterMob : ratio »...). */
    public void add(Stat stat, double value, String source) {
        if (value == 0) {
            return;
        }
        totals.merge(stat, value, Double::sum);
        sources.computeIfAbsent(stat, key -> new LinkedHashMap<>()).merge(source, value, Double::sum);
    }

    public double get(Stat stat) {
        return totals.getOrDefault(stat, 0.0);
    }

    public Map<Stat, Double> totals() {
        return Collections.unmodifiableMap(totals);
    }

    public Map<String, Double> sources(Stat stat) {
        return sources.getOrDefault(stat, Map.of());
    }
}
