package dev.colonypaths.render;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.workerbuildings.BuildingBuilder;
import dev.colonypaths.ColonyPaths;
import dev.colonypaths.heatmap.HeatMap;
import dev.colonypaths.path.PathExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Renders a top-down view of the heatmap with building markers + player crosshair + legend to a PNG.
// Server-side only. Uses java.awt headlessly (BufferedImage + ImageIO, no display required).
public final class HeatMapRenderer {
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    // Returned by renderToPng so the caller can show per-category counts in chat and
    // diagnose missing-building reports without another rebuild.
    public record RenderResult(Path path, int builders, int townhalls, int residences, int others, int outsideRadius) {
        public int totalDrawn() { return builders + townhalls + residences + others; }
    }

    private static final int HEADER_H = 28;
    private static final int FOOTER_H = 18;
    private static final int LEGEND_W = 90;

    private static final Color BG = new Color(0x1A, 0x1A, 0x1A);
    private static final Color MAP_BG = new Color(0x22, 0x22, 0x22);
    private static final Color CHUNK_LINE = new Color(0x33, 0x33, 0x33);
    private static final Color HEADER_BG = new Color(0x2A, 0x2A, 0x2A);
    private static final Color HEADER_SEP = new Color(0x44, 0x44, 0x44);
    private static final Color TEXT_BRIGHT = new Color(0xEE, 0xEE, 0xEE);
    private static final Color TEXT_DIM = new Color(0xAA, 0xAA, 0xAA);
    private static final Color BUILDER_FILL = new Color(0xFF, 0xC8, 0x00);
    private static final Color TOWNHALL_FILL = new Color(0xFF, 0x50, 0x50);
    private static final Color RESIDENCE_FILL = new Color(0x70, 0xD0, 0x60);
    private static final Color OTHER_BUILDING_FILL = new Color(0x60, 0xA8, 0xFF);
    private static final Color CROSSHAIR = new Color(0xC8, 0xFF, 0x00);
    private static final Color PATH_OVERLAY = new Color(0x00, 0xE0, 0xFF);  // bright cyan, distinct from heat gradient

    public static RenderResult renderToPng(MinecraftServer server, BlockPos center, int radius) throws IOException {
        return renderToPng(server, center, radius, null, null);
    }

    public static RenderResult renderToPng(MinecraftServer server, BlockPos center, int radius,
                                           PathExtractor.Skeleton overlay) throws IOException {
        return renderToPng(server, center, radius, overlay, null);
    }

    // Overload that paints a PathExtractor.Skeleton over the heatmap and under the building
    // markers (so the proposed path's cells are visible against heat, but markers/crosshair
    // remain readable on top). `overlay == null` is identical to the no-overlay signature.
    // `commandLabel == null/empty` skips the footer; otherwise it's rendered monospaced at
    // the bottom of the image so the PNG is self-documenting about how it was generated.
    public static RenderResult renderToPng(MinecraftServer server, BlockPos center, int radius,
                                           PathExtractor.Skeleton overlay, String commandLabel) throws IOException {
        boolean showFooter = commandLabel != null && !commandLabel.isEmpty();
        int blocksWide = 2 * radius + 1;
        int pixelScale = Math.max(2, Math.min(8, 512 / blocksWide));
        int mapSize = blocksWide * pixelScale;
        int totalWidth = mapSize + LEGEND_W;
        int totalHeight = mapSize + HEADER_H + (showFooter ? FOOTER_H : 0);

        int peak = Math.max(1, HeatMap.get().peak());

        int[] counts = new int[5];  // builders, townhalls, residences, others, outside

        BufferedImage img = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(BG);
            g.fillRect(0, 0, totalWidth, totalHeight);

            drawHeader(g, totalWidth, center, radius, peak);

            int mapX = 0, mapY = HEADER_H;
            drawMapBackground(g, mapX, mapY, mapSize, blocksWide, pixelScale, center, radius);
            drawHeatmap(g, mapX, mapY, mapSize, blocksWide, center, radius, peak);
            if (overlay != null) {
                drawSkeleton(g, mapX, mapY, pixelScale, overlay);
            }
            drawBuildings(g, mapX, mapY, pixelScale, center, radius, counts);
            drawCrosshair(g, mapX + radius * pixelScale + pixelScale / 2, mapY + radius * pixelScale + pixelScale / 2);
            drawNorthArrow(g, mapX + 8, mapY + 8);
            drawScaleBar(g, mapX, mapY, mapSize, pixelScale);
            drawCornerCoords(g, mapX, mapY, mapSize, center, radius);

            drawLegend(g, mapX + mapSize, HEADER_H, LEGEND_W, mapSize, peak, overlay != null);
            if (showFooter) {
                drawFooter(g, totalWidth, HEADER_H + mapSize, commandLabel);
            }
        } finally {
            g.dispose();
        }

        Path outDir = server.getWorldPath(LevelResource.ROOT).resolve("colonypaths");
        Files.createDirectories(outDir);
        String prefix = (overlay != null) ? "preview" : "heatmap";
        String filename = prefix + "_" + center.getX() + "_" + center.getZ() + "_r" + radius
                + "_" + LocalDateTime.now().format(FILE_TS) + ".png";
        Path outPath = outDir.resolve(filename);
        if (!ImageIO.write(img, "PNG", outPath.toFile())) {
            throw new IOException("No PNG ImageWriter available in this JVM");
        }
        return new RenderResult(outPath, counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    private static void drawSkeleton(Graphics2D g, int mapX, int mapY, int pixelScale,
                                     PathExtractor.Skeleton skel) {
        int radius = skel.radius;
        int side = 2 * radius + 1;
        // Slight alpha so very bright peak cells underneath are still visible through the path.
        Color cellColor = new Color(PATH_OVERLAY.getRed(), PATH_OVERLAY.getGreen(), PATH_OVERLAY.getBlue(), 220);
        g.setColor(cellColor);
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                if (skel.mask[x][z]) {
                    g.fillRect(mapX + x * pixelScale, mapY + z * pixelScale, pixelScale, pixelScale);
                }
            }
        }
    }

    private static void drawFooter(Graphics2D g, int width, int y, String commandLabel) {
        g.setColor(HEADER_BG);
        g.fillRect(0, y, width, FOOTER_H);
        g.setColor(HEADER_SEP);
        g.fillRect(0, y, width, 1);

        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.setColor(TEXT_DIM);
        g.drawString("cmd:", 8, y + 13);
        g.setColor(TEXT_BRIGHT);
        g.drawString(commandLabel, 36, y + 13);
    }

    private static void drawHeader(Graphics2D g, int width, BlockPos center, int radius, int peak) {
        g.setColor(HEADER_BG);
        g.fillRect(0, 0, width, HEADER_H);
        g.setColor(HEADER_SEP);
        g.fillRect(0, HEADER_H - 1, width, 1);

        HeatMap h = HeatMap.get();
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(TEXT_BRIGHT);
        String line = String.format(
                "Colony Paths   center (%d, %d)   radius %d   peak %d   samples %d   chunks %d",
                center.getX(), center.getZ(), radius, peak, h.totalSamples(), h.chunksTracked());
        g.drawString(line, 8, 18);
    }

    private static void drawMapBackground(Graphics2D g, int mapX, int mapY, int mapSize,
                                          int blocksWide, int pixelScale, BlockPos center, int radius) {
        g.setColor(MAP_BG);
        g.fillRect(mapX, mapY, mapSize, mapSize);

        g.setColor(CHUNK_LINE);
        int firstChunkX = (center.getX() - radius) & ~0xF;
        for (int x = firstChunkX; x <= center.getX() + radius; x += 16) {
            int dx = x - (center.getX() - radius);
            if (dx >= 0 && dx < blocksWide) {
                g.fillRect(mapX + dx * pixelScale, mapY, 1, mapSize);
            }
        }
        int firstChunkZ = (center.getZ() - radius) & ~0xF;
        for (int z = firstChunkZ; z <= center.getZ() + radius; z += 16) {
            int dz = z - (center.getZ() - radius);
            if (dz >= 0 && dz < blocksWide) {
                g.fillRect(mapX, mapY + dz * pixelScale, mapSize, 1);
            }
        }
    }

    // Renders per-block heat into a 1px/block tile, blurs it (3x3 gaussian),
    // then upscales with bilinear interpolation onto the map area. Effect: smooth,
    // continuous heat gradient instead of scattered hard pixels.
    private static void drawHeatmap(Graphics2D g, int mapX, int mapY, int mapSize,
                                    int blocksWide, BlockPos center, int radius, int peak) {
        BufferedImage tile = new BufferedImage(blocksWide, blocksWide, BufferedImage.TYPE_INT_ARGB);
        double logPeak = Math.log(peak + 1);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int count = HeatMap.get().countAt(center.getX() + dx, center.getZ() + dz);
                if (count == 0) continue;
                double frac = Math.log(count + 1) / logPeak;
                tile.setRGB(dx + radius, dz + radius, heatColor(frac).getRGB());
            }
        }

        float[] kernel = {
                1f / 16, 2f / 16, 1f / 16,
                2f / 16, 4f / 16, 2f / 16,
                1f / 16, 2f / 16, 1f / 16
        };
        BufferedImage blurred = new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null)
                .filter(tile, null);

        Object oldHint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(blurred, mapX, mapY, mapSize, mapSize, null);
        if (oldHint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldHint);
    }

    // counts indices: 0=builders, 1=townhalls, 2=residences, 3=others, 4=outside-radius
    private static void drawBuildings(Graphics2D g, int mapX, int mapY, int pixelScale,
                                      BlockPos center, int radius, int[] counts) {
        try {
            int markerSize = Math.max(7, pixelScale + 3);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            for (IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getAllColonies()) {
                for (IBuilding building : colony.getServerBuildingManager().getBuildings().values()) {
                    BlockPos p = building.getPosition();
                    int dx = p.getX() - center.getX();
                    int dz = p.getZ() - center.getZ();
                    if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                        counts[4]++;
                        continue;
                    }

                    int mx = mapX + (dx + radius) * pixelScale + pixelScale / 2 - markerSize / 2;
                    int my = mapY + (dz + radius) * pixelScale + pixelScale / 2 - markerSize / 2;

                    String schematic = safeSchematicName(building);
                    Color fill = pickFill(building, schematic);
                    if (fill == BUILDER_FILL) counts[0]++;
                    else if (fill == TOWNHALL_FILL) counts[1]++;
                    else if (fill == RESIDENCE_FILL) counts[2]++;
                    else counts[3]++;

                    g.setColor(fill);
                    g.fillRect(mx, my, markerSize, markerSize);
                    g.setColor(Color.WHITE);
                    g.drawRect(mx, my, markerSize, markerSize);

                    String label = prettyName(schematic) + " L" + building.getBuildingLevel();
                    // shadow + bright text for legibility over either dark bg or bright heat
                    g.setColor(new Color(0, 0, 0, 180));
                    g.drawString(label, mx + markerSize + 3, my + markerSize - 1 + 1);
                    g.setColor(TEXT_BRIGHT);
                    g.drawString(label, mx + markerSize + 2, my + markerSize - 1);
                }
            }
        } catch (Throwable t) {
            ColonyPaths.LOGGER.warn("Building overlay skipped (Minecolonies not loaded?): {}", t.toString());
        }
    }

    // Pull the Minecolonies schematic name (e.g. "townhall", "citizen", "library") with fallback
    // to a cleaned class name when the API call fails or returns nothing.
    public static String safeSchematicName(IBuilding building) {
        try {
            String s = building.getSchematicName();
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {}
        return building.getClass().getSimpleName().replaceFirst("^Building", "");
    }

    private static Color pickFill(IBuilding building, String schematic) {
        if (building instanceof BuildingBuilder) return BUILDER_FILL;
        String s = schematic == null ? "" : schematic.toLowerCase();
        if (s.contains("townhall")) return TOWNHALL_FILL;
        if (s.contains("citizen") || s.contains("residence") || s.contains("home") || s.contains("house")) return RESIDENCE_FILL;
        return OTHER_BUILDING_FILL;
    }

    private static String prettyName(String s) {
        if (s == null || s.isEmpty()) return "?";
        // Strip trailing digits (e.g. "citizen1" -> "citizen") which Minecolonies uses for style variants.
        String stripped = s.replaceFirst("\\d+$", "");
        if (stripped.isEmpty()) stripped = s;
        return Character.toUpperCase(stripped.charAt(0)) + stripped.substring(1);
    }

    private static void drawCrosshair(Graphics2D g, int cx, int cy) {
        // black halo for visibility against bright heat
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(cx - 2, cy - 8, 5, 17);
        g.fillRect(cx - 8, cy - 2, 17, 5);
        g.setColor(CROSSHAIR);
        g.fillRect(cx - 1, cy - 7, 3, 15);
        g.fillRect(cx - 7, cy - 1, 15, 3);
    }

    private static void drawNorthArrow(Graphics2D g, int x, int y) {
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(x - 2, y - 1, 22, 26);
        g.setColor(TEXT_BRIGHT);
        g.drawString("N", x + 5, y + 11);
        g.fillPolygon(new int[]{x + 9, x + 5, x + 13}, new int[]{y + 14, y + 22, y + 22}, 3);
    }

    private static void drawScaleBar(Graphics2D g, int mapX, int mapY, int mapSize, int pixelScale) {
        // Pick a round number of blocks that fits ~1/4 of the map width.
        int targetPixels = mapSize / 4;
        int scaleBlocks = Math.max(8, Math.round(targetPixels / (float) pixelScale / 8f) * 8);
        int scalePixels = scaleBlocks * pixelScale;
        int sbX = mapX + mapSize - scalePixels - 10;
        int sbY = mapY + mapSize - 16;

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(sbX - 4, sbY - 14, scalePixels + 8, 24);
        g.setColor(TEXT_BRIGHT);
        g.fillRect(sbX, sbY, scalePixels, 2);
        g.fillRect(sbX, sbY - 3, 1, 8);
        g.fillRect(sbX + scalePixels - 1, sbY - 3, 1, 8);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.drawString(scaleBlocks + " blocks", sbX, sbY - 5);
    }

    private static void drawCornerCoords(Graphics2D g, int mapX, int mapY, int mapSize,
                                         BlockPos center, int radius) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        int x0 = center.getX() - radius, z0 = center.getZ() - radius;
        int x1 = center.getX() + radius, z1 = center.getZ() + radius;
        String tl = "(" + x0 + ", " + z0 + ")";
        String br = "(" + x1 + ", " + z1 + ")";
        int brWidth = g.getFontMetrics().stringWidth(br);

        // Bottom-left
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(mapX + 2, mapY + mapSize - 14, g.getFontMetrics().stringWidth(tl) + 6, 14);
        g.setColor(TEXT_DIM);
        g.drawString(tl, mapX + 5, mapY + mapSize - 4);

        // Top-right
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(mapX + mapSize - brWidth - 6, mapY + 2, brWidth + 6, 14);
        g.setColor(TEXT_DIM);
        g.drawString(br, mapX + mapSize - brWidth - 3, mapY + 12);
    }

    private static void drawLegend(Graphics2D g, int x, int y, int width, int height, int peak, boolean showPath) {
        int barX = x + 14;
        int barY = y + 32;
        int barW = 18;
        int barH = Math.max(80, height - 140);  // leave room for 5-line key

        // Gradient bar (top = peak)
        for (int py = 0; py < barH; py++) {
            double frac = 1.0 - (double) py / (barH - 1);
            g.setColor(heatColor(frac));
            g.fillRect(barX, barY + py, barW, 1);
        }
        g.setColor(Color.WHITE);
        g.drawRect(barX, barY, barW, barH);

        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setColor(TEXT_BRIGHT);
        g.drawString("hits", barX, barY - 16);
        g.setColor(TEXT_DIM);
        g.drawString("(log)", barX, barY - 4);

        // Tick labels — peak / log-mid / 0
        g.setColor(TEXT_BRIGHT);
        g.drawString(String.valueOf(peak), barX + barW + 4, barY + 4);
        int mid = (int) Math.max(1, Math.round(Math.exp(Math.log(peak + 1) * 0.5) - 1));
        g.drawString(String.valueOf(mid), barX + barW + 4, barY + barH / 2 + 4);
        g.drawString("0", barX + barW + 4, barY + barH);

        // Key entries below the bar
        int keyY = barY + barH + 18;
        drawKeyEntry(g, BUILDER_FILL, "Builder", barX, keyY);
        drawKeyEntry(g, TOWNHALL_FILL, "Town Hall", barX, keyY + 14);
        drawKeyEntry(g, RESIDENCE_FILL, "Residence", barX, keyY + 28);
        drawKeyEntry(g, OTHER_BUILDING_FILL, "Other", barX, keyY + 42);
        if (showPath) {
            drawKeyEntry(g, PATH_OVERLAY, "Proposed path", barX, keyY + 56);
            g.setColor(CROSSHAIR);
            g.drawString("+ you (center)", barX, keyY + 70);
        } else {
            g.setColor(CROSSHAIR);
            g.drawString("+ you (center)", barX, keyY + 56);
        }
    }

    private static void drawKeyEntry(Graphics2D g, Color fill, String label, int x, int y) {
        g.setColor(fill);
        g.fillRect(x, y - 8, 8, 8);
        g.setColor(Color.WHITE);
        g.drawRect(x, y - 8, 8, 8);
        g.setColor(TEXT_BRIGHT);
        g.drawString(label, x + 12, y);
    }

    // Black → dark red → red → orange → yellow → white piecewise gradient. Alpha ramps in
    // over the lowest 5% so 1-hit cells fade gracefully into the bg instead of becoming hard dots.
    private static Color heatColor(double frac) {
        frac = Math.max(0, Math.min(1, frac));
        int alpha = 255;
        if (frac < 0.05) alpha = (int) Math.round(frac / 0.05 * 255);

        Color base;
        if (frac < 0.25) base = lerp(Color.BLACK, new Color(0x80, 0x00, 0x00), frac / 0.25);
        else if (frac < 0.50) base = lerp(new Color(0x80, 0x00, 0x00), new Color(0xFF, 0x00, 0x00), (frac - 0.25) / 0.25);
        else if (frac < 0.75) base = lerp(new Color(0xFF, 0x00, 0x00), new Color(0xFF, 0xA5, 0x00), (frac - 0.50) / 0.25);
        else base = lerp(new Color(0xFF, 0xA5, 0x00), new Color(0xFF, 0xFF, 0xFF), (frac - 0.75) / 0.25);
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }

    private static Color lerp(Color a, Color b, double t) {
        return new Color(
                (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
    }
}
