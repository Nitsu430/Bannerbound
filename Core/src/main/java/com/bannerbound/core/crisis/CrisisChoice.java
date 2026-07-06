package com.bannerbound.core.crisis;

import java.util.List;

/**
 * One selectable path in a scripted crisis: its label/description/outcome text plus the viability
 * requirements that decide whether the choice is offered and the objectives the player must complete
 * to resolve it. Loaded from data packs; the compact constructor null-coerces every field and
 * defensively copies the lists so malformed JSON never yields nulls.
 */
public record CrisisChoice(
    String id,
    String label,
    String description,
    String outcome,
    List<CrisisViabilityRequirement> viability,
    List<CrisisObjectiveDefinition> objectives
) {
    public CrisisChoice {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        description = description == null ? "" : description;
        outcome = outcome == null ? "" : outcome;
        viability = viability == null ? List.of() : List.copyOf(viability);
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }
}
