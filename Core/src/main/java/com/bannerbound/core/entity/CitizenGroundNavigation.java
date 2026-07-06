package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/**
 * Ground navigation for citizens: vanilla {@link GroundPathNavigation} wired to a
 * {@link CitizenNodeEvaluator} (with canPassDoors), which makes closed fence gates routable. The
 * A* node budget is raised to 4x the default so long, winding land routes (e.g. a fisher forced the
 * long way around water) resolve in ONE computation instead of a partial path to the nearest
 * reachable tile that then re-paths from the shore and looks like it "tried to swim." The larger
 * budget only costs more on genuinely long detours; short direct paths terminate early regardless.
 */
@ApiStatus.Internal
public class CitizenGroundNavigation extends GroundPathNavigation {
    public CitizenGroundNavigation(Mob mob, Level level) {
        super(mob, level);
        this.setMaxVisitedNodesMultiplier(4.0F);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new CitizenNodeEvaluator();
        this.nodeEvaluator.setCanPassDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }
}
