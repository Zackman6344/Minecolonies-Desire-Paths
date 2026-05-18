package dev.colonypaths;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ColonyPaths.MODID)
public class ColonyPaths {
    public static final String MODID = "colonypaths";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ColonyPaths(IEventBus modEventBus, ModContainer modContainer) {
        // CitizenTracker and HeatmapCommand use @EventBusSubscriber, so no manual registration
        // is needed — NeoForge picks them up via classpath scan.
        LOGGER.info("[{}] spike loaded", MODID);
    }
}
