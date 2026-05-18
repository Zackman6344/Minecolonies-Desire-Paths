package dev.colonypaths.heatmap;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import dev.colonypaths.ColonyPaths;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Optional;

// Samples loaded-citizen positions every SAMPLE_INTERVAL_TICKS into the shared heatmap.
// Wrapped in try/catch so the mod degrades gracefully if Minecolonies isn't loaded
// (we declared it "optional" in neoforge.mods.toml for the spike).
@EventBusSubscriber(modid = ColonyPaths.MODID)
public final class CitizenTracker {
    // 20 ticks = 1s. Citizens don't move fast enough that we need every tick.
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static int tickCounter = 0;
    private static boolean apiBroken = false;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (apiBroken) return;
        if (++tickCounter < SAMPLE_INTERVAL_TICKS) return;
        tickCounter = 0;
        try {
            sample();
        } catch (LinkageError t) {
            // LinkageError covers NoClassDefFoundError too — Minecolonies missing from classpath.
            ColonyPaths.LOGGER.warn("Minecolonies not on classpath — disabling CitizenTracker. ({})", t.toString());
            apiBroken = true;
        } catch (Throwable t) {
            ColonyPaths.LOGGER.warn("CitizenTracker sample failed; disabling: {}", t.toString());
            apiBroken = true;
        }
    }

    private static void sample() {
        IColonyManager mgr = IMinecoloniesAPI.getInstance().getColonyManager();
        for (IColony colony : mgr.getAllColonies()) {
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                Optional<AbstractEntityCitizen> entityOpt = citizen.getEntity();
                if (entityOpt.isEmpty()) continue;
                AbstractEntityCitizen entity = entityOpt.get();
                HeatMap.get().increment(entity.blockPosition());
            }
        }
    }
}
