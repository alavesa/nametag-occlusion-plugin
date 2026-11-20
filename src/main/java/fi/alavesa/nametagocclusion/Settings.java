package fi.alavesa.nametagocclusion;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

/** An immutable snapshot of config.yml. A reload builds a new one. */
final class Settings {

    final int intervalTicks;
    final int graceTicks;
    final double maxDistance;
    final double maxDistanceSquared;
    final double tagHeightOffset;
    final boolean alsoCheckHead;
    final boolean ignorePassable;
    final FluidCollisionMode fluidMode;
    final Set<Material> transparent;
    private final Set<String> enabledWorlds;

    private Settings(FileConfiguration config, Logger log) {
        intervalTicks = Math.max(1, config.getInt("update-interval-ticks", 3));
        graceTicks = Math.max(0, config.getInt("grace-period-ticks", 6));
        maxDistance = Math.max(1.0, config.getDouble("max-distance", 64.0));
        maxDistanceSquared = maxDistance * maxDistance;
        tagHeightOffset = config.getDouble("tag-height-offset", 0.5);
        alsoCheckHead = config.getBoolean("also-check-head", true);
        ignorePassable = config.getBoolean("ignore-passable-blocks", true);
        fluidMode = config.getBoolean("fluids-occlude", false) ? FluidCollisionMode.ALWAYS : FluidCollisionMode.NEVER;
        transparent = parseMaterials(config.getStringList("transparent-materials"), log);
        enabledWorlds = new HashSet<>(config.getStringList("enabled-worlds"));
    }

    static Settings load(FileConfiguration config, Logger log) {
        return new Settings(config, log);
    }

    boolean enabledIn(World world) {
        return enabledWorlds.isEmpty() || enabledWorlds.contains(world.getName());
    }

    /** Entries are material names (GLASS), globs (*_STAINED_GLASS) or block tags (#minecraft:leaves). */
    private static Set<Material> parseMaterials(List<String> entries, Logger log) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String raw : entries) {
            String entry = raw.trim();
            int before = result.size();
            if (entry.startsWith("#")) {
                NamespacedKey key = NamespacedKey.fromString(entry.substring(1).toLowerCase(Locale.ROOT));
                Tag<Material> tag = key == null ? null : Bukkit.getTag(Tag.REGISTRY_BLOCKS, key, Material.class);
                if (tag == null) {
                    log.warning("transparent-materials: unknown block tag '" + entry + "'");
                    continue;
                }
                result.addAll(tag.getValues());
            } else if (entry.contains("*")) {
                Pattern pattern = Pattern.compile(
                        Pattern.quote(entry.toUpperCase(Locale.ROOT)).replace("*", "\\E.*\\Q"));
                for (Material material : Material.values()) {
                    if (!material.isLegacy() && material.isBlock() && pattern.matcher(material.name()).matches()) {
                        result.add(material);
                    }
                }
            } else {
                Material material = Material.matchMaterial(entry);
                if (material == null || !material.isBlock()) {
                    log.warning("transparent-materials: unknown block '" + entry + "'");
                    continue;
                }
                result.add(material);
            }
            if (result.size() == before && entry.contains("*")) {
                log.warning("transparent-materials: '" + entry + "' matches no block");
            }
        }
        return result;
    }
}
