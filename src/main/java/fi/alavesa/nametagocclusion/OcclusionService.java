package fi.alavesa.nametagocclusion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Decides per viewer which nametags are behind blocks and hides exactly those.
 *
 * <p>The client does not draw the nametag of an entity that carries a passenger. Each hidden player carries an
 * "anchor": an empty item display that draws nothing and that nobody sees by default. Showing the anchor to one
 * viewer ({@link Player#showEntity}) makes that viewer's client treat the player as a vehicle, so it stops drawing
 * the tag; hiding the anchor again brings the vanilla tag back. The tag itself stays the vanilla one, so sneaking,
 * invisibility, teams and spectator mode keep their vanilla rules: this class can only take a tag away.
 */
final class OcclusionService implements Runnable, Listener {

    static final String BYPASS_PERMISSION = "nametagocclusion.bypass";
    private static final String ANCHOR_TAG = "nametagocclusion_anchor";

    /** Line-of-sight state of one viewer looking at one target. */
    private static final class Pair {
        boolean hidden;
        /** Ticks the raw result has disagreed with {@link #hidden}. */
        int pendingTicks;
        /** The run that last checked this pair; older pairs are released. */
        int seenRun;
    }

    /** The passenger on a target, and the viewers it is shown to. */
    private static final class Anchor {
        final ItemDisplay entity;
        final Set<UUID> viewers = new HashSet<>();

        Anchor(ItemDisplay entity) {
            this.entity = entity;
        }
    }

    private final NametagOcclusionPlugin plugin;
    private final NamespacedKey optOutKey;
    private Settings settings;

    /** viewer -> target -> state */
    private final Map<UUID, Map<UUID, Pair>> pairs = new HashMap<>();
    /** target -> anchor */
    private final Map<UUID, Anchor> anchors = new HashMap<>();
    private final Set<UUID> optedOut = new HashSet<>();
    private int run;
    private boolean mountFailureLogged;

    // scratch objects, reused by every ray
    private final Location eye = new Location(null, 0, 0, 0);
    private final Location feet = new Location(null, 0, 0, 0);
    private final Vector direction = new Vector();
    private final Predicate<Block> occludes = block -> !settings.transparent.contains(block.getType());

    OcclusionService(NametagOcclusionPlugin plugin, Settings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.optOutKey = new NamespacedKey(plugin, "occlusion_off");
    }

    void start() {
        // anchors are never saved, but a /reload or a crash-free plugin swap can leave some in loaded chunks
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getScoreboardTags().contains(ANCHOR_TAG)) {
                    display.remove();
                }
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            loadOptOut(player);
        }
    }

    /** Applies a new config and re-evaluates everybody from scratch. */
    void apply(Settings settings) {
        this.settings = settings;
        reset();
    }

    /** Brings back every vanilla tag and removes every anchor. */
    void reset() {
        for (Iterator<Map.Entry<UUID, Anchor>> it = anchors.entrySet().iterator(); it.hasNext(); ) {
            discard(it.next().getValue());
            it.remove();
        }
        pairs.clear();
    }

    /** @return true when occlusion is now off for this player's own view */
    boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean off = optedOut.add(id);
        if (off) {
            player.getPersistentDataContainer().set(optOutKey, PersistentDataType.BYTE, (byte) 1);
        } else {
            optedOut.remove(id);
            player.getPersistentDataContainer().remove(optOutKey);
        }
        return off;
    }

    @Override
    public void run() {
        run++;
        dropBrokenAnchors();
        Settings settings = this.settings;
        for (World world : Bukkit.getWorlds()) {
            if (!settings.enabledIn(world)) {
                continue;
            }
            List<Player> players = world.getPlayers();
            int count = players.size();
            if (count < 2) {
                continue;
            }
            for (int i = 0; i < count; i++) {
                Player viewer = players.get(i);
                UUID viewerId = viewer.getUniqueId();
                // viewers that are skipped here get their hidden tags back in releaseUnseen()
                if (optedOut.contains(viewerId) || viewer.hasPermission(BYPASS_PERMISSION)) {
                    continue;
                }
                Map<UUID, Pair> row = pairs.get(viewerId);
                if (row == null) {
                    row = new HashMap<>();
                    pairs.put(viewerId, row);
                }
                viewer.getLocation(eye);
                eye.setY(eye.getY() + viewer.getEyeHeight());
                for (int j = 0; j < count; j++) {
                    if (j == i) {
                        continue;
                    }
                    Player target = players.get(j);
                    // a target the viewer cannot see at all (vanished by another plugin) has no tag to manage
                    if (target.isDead() || !viewer.canSee(target)) {
                        continue;
                    }
                    boolean blocked = isBlocked(settings, world, target);
                    UUID targetId = target.getUniqueId();
                    Pair pair = row.get(targetId);
                    if (pair == null) {
                        // first look at this pair: no grace period, or a tag would flash through the wall
                        pair = new Pair();
                        row.put(targetId, pair);
                        if (blocked) {
                            hide(viewer, target, pair);
                        }
                    } else if (blocked == pair.hidden) {
                        pair.pendingTicks = 0;
                    } else if ((pair.pendingTicks += settings.intervalTicks) >= settings.graceTicks) {
                        pair.pendingTicks = 0;
                        if (blocked) {
                            hide(viewer, target, pair);
                        } else {
                            show(viewerId, targetId, pair);
                        }
                    }
                    pair.seenRun = run;
                }
            }
        }
        releaseUnseen();
    }

    /** Ray-traces from {@link #eye} to the target's nametag (and head). Out of range counts as a clear line. */
    private boolean isBlocked(Settings settings, World world, Player target) {
        target.getLocation(feet);
        double dx = feet.getX() - eye.getX();
        double dz = feet.getZ() - eye.getZ();
        double tagDy = feet.getY() + target.getHeight() + settings.tagHeightOffset - eye.getY();
        if (dx * dx + tagDy * tagDy + dz * dz > settings.maxDistanceSquared) {
            return false;
        }
        if (!rayHits(settings, world, dx, tagDy, dz)) {
            return false;
        }
        return !settings.alsoCheckHead
                || rayHits(settings, world, dx, feet.getY() + target.getEyeHeight() - eye.getY(), dz);
    }

    private boolean rayHits(Settings settings, World world, double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-4) {
            return false;
        }
        direction.setX(dx).setY(dy).setZ(dz);
        return world.rayTraceBlocks(eye, direction, length, settings.fluidMode, settings.ignorePassable, occludes) != null;
    }

    private void hide(Player viewer, Player target, Pair pair) {
        UUID targetId = target.getUniqueId();
        Anchor anchor = anchors.get(targetId);
        if (anchor == null) {
            anchor = mount(target);
            if (anchor == null) {
                return;
            }
            anchors.put(targetId, anchor);
        }
        viewer.showEntity(plugin, anchor.entity);
        anchor.viewers.add(viewer.getUniqueId());
        pair.hidden = true;
        pair.pendingTicks = 0;
    }

    private void show(UUID viewerId, UUID targetId, Pair pair) {
        pair.hidden = false;
        pair.pendingTicks = 0;
        Anchor anchor = anchors.get(targetId);
        if (anchor == null || !anchor.viewers.remove(viewerId)) {
            return;
        }
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer != null) {
            viewer.hideEntity(plugin, anchor.entity);
        }
        // nobody needs it any more: take it off so the target carries a passenger only while a tag is hidden
        if (anchor.viewers.isEmpty()) {
            anchor.entity.remove();
            anchors.remove(targetId);
        }
    }

    private Anchor mount(Player target) {
        removeStrayAnchors(target);
        ItemDisplay display = target.getWorld().spawn(target.getLocation(feet), ItemDisplay.class, entity -> {
            entity.setPersistent(false);
            entity.setVisibleByDefault(false);
            entity.addScoreboardTag(ANCHOR_TAG);
        });
        if (!target.addPassenger(display)) {
            display.remove();
            if (!mountFailureLogged) {
                mountFailureLogged = true;
                plugin.getLogger().warning("Could not mount the nametag anchor on " + target.getName()
                        + " (another plugin cancelled the mount?). Their tag stays visible. Logged once.");
            }
            return null;
        }
        return new Anchor(display);
    }

    /** An anchor that died, was ejected or lost its player is dropped; its pairs start over in the same run. */
    private void dropBrokenAnchors() {
        for (Iterator<Map.Entry<UUID, Anchor>> it = anchors.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Anchor> entry = it.next();
            Anchor anchor = entry.getValue();
            Player target = Bukkit.getPlayer(entry.getKey());
            if (target != null && anchor.entity.isValid() && target.equals(anchor.entity.getVehicle())) {
                continue;
            }
            for (UUID viewerId : anchor.viewers) {
                Map<UUID, Pair> row = pairs.get(viewerId);
                if (row != null) {
                    row.remove(entry.getKey());
                }
            }
            discard(anchor);
            it.remove();
        }
    }

    /** Pairs that this run did not check (other world, out of the game, bypass, vanished) get their tag back. */
    private void releaseUnseen() {
        for (Iterator<Map.Entry<UUID, Map<UUID, Pair>>> rows = pairs.entrySet().iterator(); rows.hasNext(); ) {
            Map.Entry<UUID, Map<UUID, Pair>> row = rows.next();
            for (Iterator<Map.Entry<UUID, Pair>> it = row.getValue().entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Pair> entry = it.next();
                Pair pair = entry.getValue();
                if (pair.seenRun == run) {
                    continue;
                }
                if (pair.hidden) {
                    show(row.getKey(), entry.getKey(), pair);
                }
                it.remove();
            }
            if (row.getValue().isEmpty()) {
                rows.remove();
            }
        }
    }

    /** Removes the anchor entity and the per-viewer visibility entries that point at it. */
    private void discard(Anchor anchor) {
        for (UUID viewerId : anchor.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                viewer.hideEntity(plugin, anchor.entity);
            }
        }
        anchor.viewers.clear();
        anchor.entity.remove();
    }

    private void removeStrayAnchors(Player player) {
        for (Entity passenger : player.getPassengers()) {
            if (passenger.getScoreboardTags().contains(ANCHOR_TAG)) {
                passenger.remove();
            }
        }
    }

    /** Forgets a player as a target and as a viewer. The next run looks at them fresh. */
    private void forget(Player player) {
        UUID id = player.getUniqueId();
        Anchor anchor = anchors.remove(id);
        if (anchor != null) {
            for (UUID viewerId : anchor.viewers) {
                Map<UUID, Pair> row = pairs.get(viewerId);
                if (row != null) {
                    row.remove(id);
                }
            }
            discard(anchor);
        }
        removeStrayAnchors(player);
        Map<UUID, Pair> row = pairs.remove(id);
        if (row != null) {
            for (Map.Entry<UUID, Pair> entry : row.entrySet()) {
                if (entry.getValue().hidden) {
                    show(id, entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void loadOptOut(Player player) {
        if (player.getPersistentDataContainer().has(optOutKey, PersistentDataType.BYTE)) {
            optedOut.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        loadOptOut(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
        optedOut.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        forget(event.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        forget(event.getPlayer());
    }

    // The anchor comes off before the player leaves the world, so no copy of it travels along.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo().getWorld() != event.getFrom().getWorld()) {
            forget(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        forget(event.getPlayer());
    }
}
