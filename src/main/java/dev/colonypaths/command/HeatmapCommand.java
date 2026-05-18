package dev.colonypaths.command;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.colonypaths.ColonyPaths;
import dev.colonypaths.heatmap.HeatMap;
import dev.colonypaths.render.HeatMapRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.nio.file.Path;
import java.util.Random;

// /colonypaths heatmap [radius]  — dumps an ASCII heatmap around the caller's position.
//   Output goes to the server log (monospaced) and a short summary to chat.
// /colonypaths stats              — quick counts.
// /colonypaths reset              — clears the heatmap.
@EventBusSubscriber(modid = ColonyPaths.MODID)
public final class HeatmapCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("colonypaths")
                .then(Commands.literal("heatmap")
                        .executes(ctx -> heatmap(ctx.getSource(), 16))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> heatmap(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx.getSource())))
                .then(Commands.literal("reset")
                        .executes(ctx -> {
                            HeatMap.get().clear();
                            ctx.getSource().sendSuccess(() -> Component.literal("Heatmap cleared."), false);
                            return 1;
                        }))
                .then(Commands.literal("builders")
                        .executes(ctx -> listBuilders(ctx.getSource())))
                .then(Commands.literal("seed")
                        .executes(ctx -> seed(ctx.getSource(), 8, 200))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> seed(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius"), 200))
                                .then(Commands.argument("hits", IntegerArgumentType.integer(1, 10000))
                                        .executes(ctx -> seed(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "radius"),
                                                IntegerArgumentType.getInteger(ctx, "hits"))))))
                .then(Commands.literal("render")
                        .executes(ctx -> render(ctx.getSource(), 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(8, 256))
                                .executes(ctx -> render(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "radius")))))
                .then(Commands.literal("buildings")
                        .executes(ctx -> listAllBuildings(ctx.getSource()))));
    }

    // Comprehensive listing of every building in every colony (vs `builders` which only shows builder huts).
    // Useful for cross-checking when something looks missing on the rendered PNG.
    private static int listAllBuildings(CommandSourceStack source) {
        try {
            int total = 0;
            for (IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getAllColonies()) {
                int colonyId = colony.getID();
                for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                    String name = HeatMapRenderer.safeSchematicName(building);
                    BlockPos p = building.getPosition();
                    int level = building.getBuildingLevel();
                    source.sendSuccess(() -> Component.literal(
                            "Colony #" + colonyId + "  " + name + " L" + level
                                    + " @ " + p.getX() + "," + p.getY() + "," + p.getZ()), false);
                    total++;
                }
            }
            final int t = total;
            source.sendSuccess(() -> Component.literal("Total buildings: " + t), false);
            return t;
        } catch (Throwable e) {
            source.sendFailure(Component.literal("listAllBuildings failed: " + e));
            return 0;
        }
    }

    // Writes a PNG to <world>/colonypaths/heatmap_<x>_<z>_r<radius>_<ts>.png with the heatmap,
    // color-coded building markers, and a player crosshair at the caller's position.
    // Chat message reports per-category counts so you can spot missing-building issues without re-reading the image.
    private static int render(CommandSourceStack source, int radius) {
        try {
            BlockPos center = BlockPos.containing(source.getPosition());
            HeatMapRenderer.RenderResult r = HeatMapRenderer.renderToPng(source.getServer(), center, radius);
            source.sendSuccess(() -> Component.literal("PNG: " + r.path().toAbsolutePath()), false);
            source.sendSuccess(() -> Component.literal(
                    "Drew " + r.totalDrawn() + " buildings ("
                            + r.builders() + " builder, "
                            + r.townhalls() + " townhall, "
                            + r.residences() + " residence, "
                            + r.others() + " other), "
                            + r.outsideRadius() + " outside radius."), false);
            return 1;
        } catch (Throwable e) {
            ColonyPaths.LOGGER.error("Render failed", e);
            source.sendFailure(Component.literal("Render failed: " + e));
            return 0;
        }
    }

    // Seeds the heatmap with synthetic samples around the caller. Gaussian-distributed so
    // the result looks like a believable "real" path peak rather than a uniform blob.
    // Useful for poking the visualization without waiting on actual citizens.
    private static int seed(CommandSourceStack source, int radius, int hits) {
        BlockPos center = BlockPos.containing(source.getPosition());
        Random rng = new Random();
        double sigma = Math.max(1.0, radius / 2.0);
        for (int i = 0; i < hits; i++) {
            int dx = (int) Math.round(rng.nextGaussian() * sigma);
            int dz = (int) Math.round(rng.nextGaussian() * sigma);
            HeatMap.get().increment(center.offset(dx, 0, dz));
        }
        source.sendSuccess(() -> Component.literal(
                "Seeded " + hits + " synthetic hits around "
                        + center.getX() + "," + center.getZ()
                        + " (gaussian, sigma=" + sigma + ")."), false);
        return hits;
    }

    // Walks every colony's buildings, reports each BuildingBuilder by position/level.
    // Validates the building-enumeration half of the Phase 2 work-order recipe.
    private static int listBuilders(CommandSourceStack source) {
        try {
            int total = 0;
            for (IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getAllColonies()) {
                for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                    if (building instanceof BuildingBuilder) {
                        BlockPos p = building.getPosition();
                        int level = building.getBuildingLevel();
                        int colonyId = colony.getID();
                        source.sendSuccess(() -> Component.literal(
                                "Colony #" + colonyId + " builder L" + level
                                        + " @ " + p.getX() + "," + p.getY() + "," + p.getZ()), false);
                        total++;
                    }
                }
            }
            final int t = total;
            source.sendSuccess(() -> Component.literal("Found " + t + " builders across all colonies."), false);
            return t;
        } catch (Throwable e) {
            source.sendFailure(Component.literal("listBuilders failed: " + e));
            return 0;
        }
    }

    private static int heatmap(CommandSourceStack source, int radius) {
        BlockPos center = BlockPos.containing(source.getPosition());
        int peak = Math.max(1, HeatMap.get().peak());
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== Colony Paths heatmap @ ").append(center.getX()).append(',').append(center.getZ())
                .append(" (radius=").append(radius).append(", peak=").append(peak).append(") ===\n");
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int count = HeatMap.get().countAt(center.getX() + dx, center.getZ() + dz);
                sb.append(glyph(count, peak));
            }
            sb.append('\n');
        }
        // MC chat font isn't monospaced, so the grid won't align in-game. Dump to log
        // where it WILL be monospaced (most terminals/IDE consoles), and notify in chat.
        ColonyPaths.LOGGER.info("{}", sb);
        source.sendSuccess(() -> Component.literal(
                "Heatmap (peak=" + peak + ", radius=" + radius
                        + ") logged to console. See logs/latest.log in your instance folder."), false);
        return 1;
    }

    private static int stats(CommandSourceStack source) {
        HeatMap h = HeatMap.get();
        source.sendSuccess(() -> Component.literal(
                "Heatmap: " + h.totalSamples() + " samples across " + h.chunksTracked()
                        + " chunks, peak=" + h.peak()), false);
        return 1;
    }

    private static char glyph(int count, int peak) {
        if (count == 0) return '.';
        double frac = (double) count / peak;
        if (frac < 0.2) return '·';
        if (frac < 0.4) return ':';
        if (frac < 0.6) return 'o';
        if (frac < 0.8) return 'O';
        return '#';
    }
}
