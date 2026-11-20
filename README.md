# NametagOcclusion

A Paper 26.3 plugin that hides a player's nametag from every viewer who has solid blocks between them and that
player. Vanilla draws nametags through walls; with this plugin a tag is only visible when the viewer has a line of
sight to it. Each viewer is handled separately, and the player's body is never hidden.

Paper API only: no NMS, no ProtocolLib, no scoreboard teams.

## How it works

The Minecraft client does not draw the nametag of an entity that carries a passenger. The plugin uses that rule:

1. Every few ticks it ray-traces from each viewer's eyes to each nearby player's nametag position and head.
2. When both rays hit an occluding block, the target gets an "anchor": an empty item display riding on them. It
   draws nothing, has no hitbox and is invisible to everyone by default.
3. The anchor is shown (`Player#showEntity`) only to the viewers whose line of sight is blocked. Their clients now
   see the target as a vehicle and stop drawing the tag. Everyone else never receives the anchor and sees the tag
   as usual.
4. When the line is clear again the anchor is hidden from that viewer, and the vanilla tag returns. When no viewer
   needs it, the anchor is removed.

The tag stays the vanilla tag, so the plugin can only take a tag away, never add one. Sneaking, invisibility,
spectator mode, team nametag rules and players vanished by other plugins behave exactly as in vanilla.

## Build

Requires JDK 25 or newer (Paper 26.3 runs on Java 25).

```
./gradlew build
```

The jar is written to `build/libs/nametag-occlusion-1.0.0.jar`.

## Install

1. Copy the jar into the server's `plugins/` folder. When updating, delete the old jar first.
2. Start the server. `plugins/NametagOcclusion/config.yml` is created on first start.
3. Edit the config if needed and run `/nametagocclusion reload`.

## Commands and permissions

| Command | Permission | Default | What it does |
| --- | --- | --- | --- |
| `/nametagocclusion reload` | `nametagocclusion.reload` | op | Reloads config.yml and re-evaluates everyone |
| `/nametagocclusion toggle` | `nametagocclusion.toggle` | op | Turns occlusion off or on for your own view. Remembered across relogs |

Alias: `/nto`.

**Bypass:** viewers with `nametagocclusion.bypass` always see tags through blocks, as in vanilla. It defaults to
`false` for everyone, including operators, so that an op testing the plugin sees it working. Grant it with a
permissions plugin. `toggle` defaults to op because switching occlusion off gives the vanilla see-through tags back.

## Config

| Key | Default | Meaning |
| --- | --- | --- |
| `update-interval-ticks` | `3` | How often line of sight is re-checked |
| `max-distance` | `64` | Pairs further apart are not ray-traced and keep the vanilla tag. Vanilla draws tags up to 64 blocks |
| `grace-period-ticks` | `6` | A result must hold this long before a tag flips. Stops flicker at block edges. The first check of a pair has no delay |
| `transparent-materials` | glass, panes, leaves, bars, barrier | Blocks that never hide a tag. Material names, `*` globs, or `#block_tags` |
| `ignore-passable-blocks` | `true` | Blocks without collision (grass, flowers, signs) never hide a tag |
| `fluids-occlude` | `false` | `true` makes water and lava hide tags |
| `also-check-head` | `true` | Hide only when the tag position and the head are both blocked. Keeps tags visible in 2-block-high corridors, where the tag floats inside the ceiling |
| `tag-height-offset` | `0.5` | Height of the tag position above the top of the hitbox |
| `enabled-worlds` | `[]` | Worlds where the plugin is active. Empty = all worlds |

## Known limitations

- **Server-side line of sight.** The tag is hidden based on a ray trace on the server, so it can lag the client's
  view by a few ticks (the check interval plus the grace period) and it does not match per-pixel partial occlusion:
  a tag is either fully shown or fully hidden. A client-side resource pack that overrides the shader of the
  see-through text pass is the zero-cost alternative when only the visuals matter: it occludes per pixel on the GPU
  with no server work, but it affects all see-through text and needs every player to accept the pack.
- **Plugin teleports to another world fail while a tag is hidden.** On Paper 26.3, `Player#teleport` to a different
  world returns `false` when the player carries any passenger, and the anchor is a passenger. Vanilla `/tp`,
  `/execute in ... run tp`, portals and same-world plugin teleports work. A plugin that moves players between worlds
  through the API (hub or multi-world plugins) will fail for a player whose tag is currently hidden from someone.
  There is no API hook before that check, so the plugin cannot work around it.
- **One passenger slot is taken.** While a tag is hidden the player carries the anchor. Plugins that count or eject
  player passengers will see it (tagged `nametagocclusion_anchor`). If it is ejected, it is replaced on the next check.
- **Passengers already hide tags.** A player who carries any other passenger has no nametag at all in vanilla.
- **One ray per point.** Line of sight is tested to two points (tag and head), not to the whole text. A tag can be
  hidden while a corner of it would be visible, and the other way round.
- **Not for Folia.** The check runs on the main server thread with the Bukkit scheduler.
- Only player nametags are handled, not mobs or armor stands with custom names.

## Performance

Each check is one distance test per ordered pair of players in the same world, and one or two block ray traces for
pairs within `max-distance`. 20 players standing together are 380 pairs every 3 ticks. Raise
`update-interval-ticks` or lower `max-distance` if the server is tight; `/spark profiler` shows the cost under
`OcclusionService.run`.

## Test checklist

Use two accounts, A (viewer) and B (target). Neither may have `nametagocclusion.bypass`.

- [ ] **Wall:** B stands behind a stone wall. A does not see B's tag. A third player with a clear view still does.
- [ ] **Wall removed:** break the wall. The tag is back within about half a second.
- [ ] **Glass:** replace the wall with glass, stained glass and glass panes. The tag stays visible.
- [ ] **Leaves:** a leaf wall keeps the tag visible. Remove `#minecraft:leaves` from the config, reload: now it hides.
- [ ] **Water:** B stands under water or behind a water curtain. Tag visible. With `fluids-occlude: true` it hides.
- [ ] **Passable blocks:** tall grass, flowers and signs between A and B do not hide the tag.
- [ ] **Edge flicker:** B walks slowly past the corner of a wall. The tag switches once, without flickering.
- [ ] **Low corridor:** both stand in a 2-high corridor with a clear view. The tag stays visible.
- [ ] **Sneaking:** B sneaks in the open: dimmed tag as in vanilla. B sneaks behind a wall: no tag.
- [ ] **Invisibility potion:** B drinks one in the open. No tag, as in vanilla. It returns when the effect ends.
- [ ] **Spectator mode:** B in spectator is invisible to A with no tag. A in spectator sees tags by the same rules.
- [ ] **Vanish plugin:** a vanished B never shows a tag to A.
- [ ] **Bypass:** give A `nametagocclusion.bypass`. A sees B's tag through the wall again.
- [ ] **Toggle:** `/nto toggle` on A shows tags through walls for A only. Toggle again to restore. Survives a relog.
- [ ] **World change:** B goes through a nether portal and back. No errors, tags are correct in both worlds, and
      `/execute if entity @e[tag=nametagocclusion_anchor]` never counts more anchors than online players.
- [ ] **Death and respawn:** kill B behind the wall. After respawn the tag follows the rules again.
- [ ] **Relog:** B and A relog behind the wall. The tag is hidden right away, without flashing.
- [ ] **Plugin disable:** stop the server or disable the plugin. All tags are vanilla again and the anchor count is 0.
- [ ] **Reload:** change `max-distance`, run `/nto reload`. The new value applies.
- [ ] **20+ players:** with 20 or more players in one area, run `/spark profiler` for a minute and check that
      `OcclusionService.run` stays a small part of the tick. Check `/mspt` with the plugin on and off.
