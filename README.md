# Spruce Farm Automator

A client-side Fabric mod for **Minecraft Java Edition 1.21.11** that runs a
stationary spruce-tree farm: plant every sapling, grow every tree, harvest every
base log with your special axe, and sell logs/sticks/excess saplings through the
server's `/sell` chest GUI **only when the inventory is nearly full**, then
restock bonemeal from the chests under the player. A finished cycle restarts on
the next tick, so the farm never idles between cycles.

The player never walks. Every action is driven from the client tick, aimed and
verified against the server-synchronised world.

## Requirements

- Minecraft **1.21.11**
- **Fabric Loader** 0.20.1 or newer
- **Fabric API** for 1.21.11 (built against `0.141.6+1.21.11`)
- Java 21 (the Minecraft runtime)

The mod is `environment: client`; install it only on the client.

## Install

1. Install Fabric Loader for 1.21.11.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and
   `sprucefarm-1.0.16.jar` in the instance's `mods/` folder (remove any older
   `sprucefarm-1.0.0.jar` through `sprucefarm-1.0.14.jar` first).
3. Launch the game, join the server, stand in the centre of the farm, and press
   **O** to open the panel.
4. Press **Start**. The first scan validates the layout before any action.

The prebuilt jar is in [`dist/sprucefarm-1.0.16.jar`](dist/sprucefarm-1.0.16.jar).
The panel title shows the running version, so a screenshot tells which build
is loaded.

## Farm assumptions

The controller is built for the described fixed layout:

- 8 isolated grass/dirt pads, each made of 4 planting blocks.
- The player stands still on the two double chests in the centre.
- A water stream surrounds the farm and carries drops to the player.
- The player carries a special axe that fells a whole spruce when its bottom
  log is broken, plus spruce saplings and bonemeal.
- Both double chests contain bonemeal.
- The server sells the contents of the `/sell` GUI when it is closed.
- The server's tree-fell may be instant, gradual, or limited by a block count.
  The mod waits for it to stop and only treats logs as leftovers after a quiet
  window (`fellQuietTicks`, 3 seconds by default).

The scan accepts grass, dirt, coarse dirt, podzol and the other blocks in the
vanilla dirt tag, so a grown tree turning its grass block into dirt does not
invalidate the pad. A pad is a horizontally connected group of soil blocks at
one height. The default validation requires **exactly 8 pads and 32 spots**;
the error tells you how many were found, how many were blocked, and how many
were out of reach. This prevents accidentally farming decorative grass or a
partially built pad.

All interactions are **visible-only** by default. The mod samples the whole
top face of a spot and the visible faces of a trunk, so a shallow view from
the centre chests still finds a legitimate camera ray instead of sending a
hit through a solid block. `directFallback` in the config can send a
direct block hit when the camera is blocked; it is **off by default** because
servers and anti-cheat plugins may treat that as a through-wall interaction.

The exception is the farm's own saplings and **signs**. A sapling has no
collision shape, so it cannot stop a placement: the click lands on the soil
behind it, exactly as when a player aims through the plant's cross. On a 2x2
pad the diagonal sapling planted a moment earlier can cover the whole sampled
top face, so planting clicks the soil through that sapling instead of stopping
with `Cannot see the planting spot ... blocked by Spruce Sapling`. That
fallback samples points across the face, because the corner nearest the eye
is shared with the soil block under the covering sapling and a ray to the
exact corner grazes that block.

A sign's board is a thin, non-solid outline, so the client can click through
it, and `interactThroughSigns` (default `true`) still lets planting, bonemeal,
harvesting and restocking step past boards. A **strict server, however, checks
the line of sight itself**: on DonutSMP-style anti-cheats a placement whose
eye-to-soil line crosses a sign is rolled back even though the click point is
legal. With the usual eight-sign box (four on the chests, four at eye level)
the upper layer walls off about half the pads; those spots cannot be planted
by any client while the box stands. Retrying does not help and only produces
the stream of instant ghost saplings.

The four signs standing on the chests are **below** the sapling plane and are
harmless. Remove the upper layer (the four boards at head height) and press
**Rescan**; all 32 spots then have a direct top-face line. This also fixes
vanilla growth: a 2x2 group grows as one mega spruce whose trunk base needs a
clear 3x3, and the upper boards sit inside it. With 1.0.16 the farm detects the
first rolled-back covered placement, skips every spot that needs a through-sign
line (`skipHiddenSpots`, default `true`) and keeps the visible spots running
with an orange warning in the panel instead of stopping on `The server rejected
every sapling ...`. Set `skipHiddenSpots` to `false` to stop on the first
rejection instead. `interactThroughSigns` still works on servers that allow
covered clicks.

The controller runs from `START_CLIENT_TICK`, matching vanilla's pre-movement
right-click order. It never sends a movement packet of its own: every turn is
applied to the local player and travels in vanilla's normal movement packet,
and the click/break is always sent on a **later tick**, after the server has
the new angle. Slow mode turns in 20-degree steps; fast mode snaps in a single
step but still waits for that tick boundary. 1.0.2's post-movement hook avoided
the kick but strict anti-cheats cancelled those placements as post-motion
(ghost saplings); 1.0.1, 1.0.3 and 1.0.7 clicked in the same tick as an instant
rotation and were kicked with `Invalid sequence`.
Block placements also use the real interaction range (not the server's extra
one-block padding), the default delay is the vanilla 4-tick right-click
cooldown, and a hotbar swap waits for the server's inventory acknowledgement.
GUI clicks are paced, and the mod waits after closing a container before the
next world action.

The player must not move more than 0.75 blocks from the Start position. The mod
waits while another screen is open, while sneak is held, or while an item is in
use. It stops safely on death, a missing axe, missing saplings, a full
inventory with nothing sellable, or an obstruction that does not clear.

## What a cycle does

1. **Plant** – every detected spot is targeted individually. A placement is
   only counted after the block above the soil is confirmed to be a spruce
   sapling. Failed attempts are retried with a per-spot cooldown. Tall grass and
   flowers sitting on a spot are cleared with the axe first; anything else that
   covers a spot is reported instead of silently skipped.
2. **Grow** – bonemeal is applied to each planted sapling, round-robin, until
   the block above it is a spruce log. The visible faces of the sapling are
   sampled. Only a block occupying the sapling's own cell is cleared; the
   cycle does not advance until every spot is a tree.
3. **Harvest** – the special axe is equipped and the bottom log of each tree is
   broken. A visible face of the base log is sampled; only a solid block stops
   the click. As soon as every base is gone the cycle ends and the next one
   starts, even if the server's fell is still removing the rest of the trunks.
   The new saplings are planted during that fell. **Grow** then clears any log
   left above a fresh sapling before applying bonemeal; a gradual fell is
   still given its quiet window (`fellQuietTicks`), just in parallel with
   planting instead of as a pause between cycles. It breaks the **new lowest**
   remaining log from the same position, which lets a fell-limited axe remove
   the next segment of the same tree. A log that is genuinely out of the
   4.5-block interaction range is reported as the axe's limit, never worked
   around by moving the player. If the axe still cannot finish the tree after
   `maxHarvestPasses`, that pad is marked as skipped and the farm continues
   with the other pads; a floating trunk never stops the run. The panel's
   **Skipped** counter shows how many pads were dropped. **Start** or **Rescan**
   retries them.
4. **Sell** – only when free inventory slots fall to `sellFreeSlots` (6 by
   default). Farming is paused, `/sell` is opened, whole stacks are moved in
   batches of `sellClicksPerTick` (6) on consecutive ticks, and the GUI is
   closed. Logs and sticks always go in full; sapling stacks are sold only
   while at least 64 would remain. No stack is ever split. The inventory is
   checked afterwards and a slower pickup/place path is used only for a
   quick-move the menu ignored. When enough room is free, farming resumes.
5. **Restock** – when bonemeal reaches 0, opens a chest, shift-clicks one stack
   (falling back to direct pickup/place), and verifies that the inventory count
   increased. Empty chests are skipped; the last-used chest is preferred. If
   both are empty, farming stops with a clear error. Pressing **Start** or
   **Rescan** after refilling clears the cached chest counts so the mod checks
   the chests again.

Harvest completion does not wait for the drops. Once the last **base** log is
gone, the next cycle plants immediately while the water stream keeps carrying
the previous drops to the stationary player. If the server's fell is still
running, the leftover logs above the new saplings are cleared in grow, so a
slow fell can never delay the next planting pass.

The mod does not clear leaves. A complete fell takes the whole canopy with the
trunk, so the farm stays clear without any extra block breaking; if a leaf ever
does block a ray, the status line and error name the block instead of the mod
cutting it. The farm layout is never changed, and the player never moves.

At least **64 spruce saplings** are always kept when selling. Logs and sticks
are always sold in full. Selling is capacity-driven, not cycle-driven: an empty
or half-empty inventory keeps farming, and one sale clears the logs, sticks and
excess saplings when the next harvest would otherwise run out of slots.

### Speed

Fast mode performs one action per client tick, the vanilla limit of 20 actions
per second, without ever looking and clicking in the same tick. The tick a
click goes out, the next target is chosen and the camera turns towards it, so
that turn travels in the movement packet which follows the click and the next
click lands a tick later. Spot selection always takes the next target clockwise
from where the camera already points, so planting and growing sweep once around
the ring instead of zig-zagging across it. In the automated test a full grow of
32 trees runs at 20 bonemeal applications per second (previously 10).

The sell transfer sends six independent whole-stack quick-moves per tick, with
no pause between batches; a nearly full inventory is handed to `/sell` in a few
ticks (the local test sold 1825 items in 725 ms including the GUI round trip).
The harvest ends the moment the last base log is gone; a still-running server
fell is handled in grow while the next saplings are already in the ground. The
post-container pause is four ticks. There is no between-cycle collect wait and
no fixed wait for the fell to finish before replanting.

### Inventory tracking

The mod keeps its own mirror of the player inventory. Since 1.17 the client
applies its own container clicks and the server only answers with a correction
when it rejects one, so a mirror that only listened to packets would fall
behind every accepted click. The mirror is therefore updated from each click
and overwritten by any packet the server sends. That combination notices items
removed by something other than the mod (an admin `/clear`, a rejected click
rolled back) without ever believing a successful restock failed.

## Panel

Press **O** in game. The panel shows:

- Start, Pause/Resume, Stop
- current stage and status
- per-cycle and total planted / grown / harvested counts
- current bonemeal in the inventory
- cached bonemeal for each detected chest (with age, or `unknown`)
- current logs, sticks and saplings, and the sapling reserve
- detected pads/spots, blocked spots, and out-of-reach spots
- the current error or action notice
- a speed button: **FAST** one-tick rotation and a short action cadence, or a slower 200–1000 ms delay if a server rate-limits placement

**Stop** cancels new actions immediately, stops any in-progress block break,
cancels a sell/restock task, and closes any container it opened.

## Special axe

The mod recognises axes by the vanilla axe item tag, by an item id containing
`axe`, or by a display name containing `axe`. If your custom axe is not
recognised, hold it in the main hand, press **O**, then **Set axe**. The
override lasts for the current game session.

## Configuration

The config file is `config/sprucefarm.json` in the Fabric config directory. It
is created on first launch; defaults:

```json
{
  "fastMode": true,
  "aimSettleTicks": 1,
  "actionDelayTicks": 4,
  "guiDelayTicks": 4,
  "directFallback": false,
  "interactThroughSigns": true,
  "skipHiddenSpots": true,
  "scanHorizontal": 5,
  "scanDown": 5,
  "scanUp": 3,
  "expectedPads": 8,
  "expectedSpots": 32,
  "requireFullLayout": true,
  "keepSaplings": 64,
  "sellFreeSlots": 6,
  "sellClicksPerTick": 6,
  "sellClickDelayTicks": 0,
  "sellVerifyTicks": 0,
  "maxHarvestPasses": 10,
  "maxPlantAttempts": 12,
  "maxBonemealPerSapling": 100,
  "guiOpenTimeoutTicks": 100,
  "harvestSettleTicks": 20,
  "fellFinishTimeoutTicks": 200,
  "fellQuietTicks": 60
}
```

- `fastMode` – snaps the view to a target and clicks on the same tick, so the
  farm runs at the vanilla limit of one action per tick (20 per second). Turn
  it off if a server rejects fast rotations.
- `aimSettleTicks` – ticks between the final rotation and the click. `1` is
  enough for the rotation packet to reach the server first.
- `actionDelayTicks` – slow-mode ticks between world actions. `4` = 200 ms.
  Fast mode ignores it and acts every tick.
- `guiDelayTicks` – slow-mode ticks between inventory clicks (`4` = 200 ms).
  Sell and chest menus need time to acknowledge a click; fast mode keeps its
  own safe inventory waits.
- `directFallback` – when `true`, sends a direct block hit if the camera ray is
  blocked by any block. Off by default; a server that validates line of sight
  may reject it.
- `interactThroughSigns` – when `true` (default), plants, applies bonemeal,
  harvests and opens chests through standing/wall/hanging sign boards. Only
  plants and signs may be in front; a solid block still stops the click, so
  this is not a through-wall interaction. Turn it off if an anti-cheat rejects
  a click on a covered block.
- `skipHiddenSpots` - when `true` (default), a server rollback of a placement
  whose line crosses a sign skips every such covered spot and continues with
  the visible ones instead of stopping. Remove the upper sign layer and press
  Rescan to bring the skipped spots back. Set to `false` to fail on the first
  rejection.
- `scanHorizontal`, `scanDown`, `scanUp` – layout scan box.
- `expectedPads`, `expectedSpots` – layout validation. The described farm is
  8 pads / 32 spots.
- `requireFullLayout` – require the exact pad and spot counts. Disable only if
  you deliberately want a different layout.
- `keepSaplings` – saplings never sold.
- `sellFreeSlots` – sell only when this many inventory slots (or fewer) are
  free. `0` means sell only when the inventory is completely full.
- `sellClicksPerTick`, `sellClickDelayTicks`, `sellVerifyTicks` – batch size and
  pacing for the sell GUI. Fast mode moves six stacks per tick and starts the
  next batch on the next tick; lower the batch or raise the delay if a server
  drops inventory clicks.
- `maxHarvestPasses` – extra fells allowed when the axe leaves logs. Once the
  limit is reached, that pad is skipped and farming continues.
- `maxPlantAttempts` – per-spot planting failures before stopping.
- `maxBonemealPerSapling` – applications before a sapling is treated as
  obstructed.
- `guiOpenTimeoutTicks` – maximum time to wait for `/sell` or a chest GUI.
- `harvestSettleTicks` – delay between two checks of the remaining logs;
  fast mode caps it at 8 ticks.
- `fellFinishTimeoutTicks` – quiet time before a stopped fell is reported as a
  server-side limit.
- `fellQuietTicks` – ticks with no change in the remaining-log count before the
  mod treats the fell as finished and inspects leftovers. Raise this if your
  server's axe plugin pauses for more than three seconds between blocks.

## Building

The build needs a **JDK 25** to run Gradle/Loom 1.19.3, while the mod itself is
compiled for **Java 21** (Minecraft's runtime). A Gradle wrapper is included.

```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew build
```

The remapped mod jar is written to `build/libs/sprucefarm-1.0.16.jar`.

The automated in-game test opens a real Minecraft client and rebuilds the
supplied litematic in a flat world with the controller's own scan:

```bash
./gradlew runClientGameTest
```
It places the 32 raised pods, chests and chest platform, then runs the real
controller in creative and survival. It checks the exact 32-spot layout,
planting/growing/harvesting 32/32/32 with strict visible-only interactions,
that the next cycle starts directly after the 32nd harvest, that a cycle with
spare slots does **not** sell, that a nearly-full inventory
sells once and keeps 64 saplings, the opt-in direct-fallback toggle, resuming a
tree left standing at Start, and recovering after both chests are emptied and
one is refilled. It also emulates a server that fells a tree gradually (one log
every five ticks, after a two-second delay) and asserts the next cycle starts
while the fell is still running, then emulates a two-log fell limit and asserts
the controller finishes the tree from the same position, with the player's
position unchanged throughout. A permanent zero-log limit verifies that an
unfinishable trunk is skipped and the farm keeps running. The final phase
rebuilds the player's eight-sign box (four signs on the centre chests, four at
eye level) and runs it against a strict server emulation that rolls back any
placement whose eye-to-soil line crosses a sign. The farm must skip the 16
covered spots after the first rollback, keep the visible 16, and show the
orange warning. The upper layer is then removed; the same run presses Rescan
and completes all 32 plants, grows and harvests through the four lower boards,
including the no-bonemeal restock that opens a chest through them.
Because the test axe stand-in removes logs only, the harness sweeps the test
canopy so the remaining phases test farming; another final phase proves that a
leaf sitting on a planting spot is reported and left untouched instead of
being broken. The special axe is a netherite axe plus a test-only
server mixin; `/sell` is a test chest menu, because those behaviours are
server-specific.

Unit tests cover look math, pad grouping, capacity-triggered selling and the
sapling reserve:

```bash
./gradlew test
```

## Verification status

Verified locally on Minecraft 1.21.11 / Fabric Loader 0.20.1 / Fabric API
`0.141.6+1.21.11`:

- 14/14 unit tests pass.
- Client gametest passes on the supplied litematic: `planted/grown/harvested=32/32/32`,
  strict mode and direct-fallback-off by default.
- A normal cycle keeps its logs (no sale) with 30 free slots; filling the
  inventory triggers one sale that removes every log, keeps 64-127 saplings and
  leaves more than six slots free.
- A gradual server fell is awaited and produces zero extra fells; a two-log
  fell limit is finished by breaking the new lowest log from the same position,
  with the player's position unchanged.
- A trunk the axe never finishes is skipped after `maxHarvestPasses`, the farm
  continues, and the player does not move.
- With leaf handling removed, a leaf on a planting spot is reported and left
  in place; the mod does not break it.
- The cycle restarts directly after the last harvest: the gametest measures the
  time from the 32nd harvest to the next PLANT/SELL/RESTOCK transition and fails
  above six ticks (local run: 0 ticks).
- The batched capacity sale moved 1825 sellable items in one pass in 725 ms,
  including opening, closing and verifying the GUI (local gametest log).
- The full gametest logs 13 PASS lines.
- The strict sampler finds visible rays on the far row without a glass
  through-wall hit; the opt-in direct fallback is covered separately.
- Double chests are grouped and restocked correctly.
- A tree present at Start is harvested instead of rejected.
- Both-empty chest error is reported, and a refilled chest resumes farming.
- A soil click through a neighbouring sapling's cross is accepted by the
  server: the gametest places a sapling on the diagonal spot of a 2x2 pad,
  clicks the soil behind it, and the spruce sapling appears on the server.
- The panel fits a 854×480 window (GUI scale 2) and displays the expected data.
- The controller runs in `START_CLIENT_TICK`, before the movement packet and
  the tick-end packet.
- Fast mode keeps one action per tick (20 actions per second, measured in the
  gametest) while sending every click a tick after the turn. The test client
  log showed no interaction in the same tick as a rotation packet. Slow mode
  still turns in several steps and clicks only after vanilla has sent the
  final rotation in a normal movement packet.
- The inventory mirror follows the mod's own clicks and is overwritten by every
  server packet, so an admin `/clear` is noticed while a successful restock is
  never reported as a failure.

Not verified against DonutSMP itself: the exact custom axe plugin, the exact
`/sell` plugin, and its anti-cheat cannot be run locally. The original
first `Invalid sequence` kick was caused by the controller running in
`END_CLIENT_TICK`, after `ServerboundClientTickEndPacket`. 1.0.1 used
`START_CLIENT_TICK` without the range/hotbar fixes and still kicked. 1.0.2 moved
the controller to `END_WORLD_TICK`, which stopped the kick but made placements
post-motion and the server silently cancelled them (ghost blocks). 1.0.3 sent
an instant rotation packet and clicked on the same tick, which kicked again.
1.0.4 keeps the pre-movement hook, aims smoothly over several ticks, lets
vanilla send the rotation in the normal movement packet, and clicks on the next
tick. 1.0.7's fast mode built its own rotation packet and clicked on the same
tick, which brought the kick back; 1.0.8 removed that packet and pipelines the
turn one target ahead, so the rate stays at one action per tick with every
click a tick behind the rotation. 1.0.9 adds the edge samples and the
through-sapling planting click, so a spot covered by the pad's own diagonal
sapling is planted instead of reported blind. 1.0.11 adds capacity-driven
batched selling, waits for a gradual server fell before judging leftover logs,
finishes fell-limited trees from the same spot. 1.0.12 removes the leaf-clearing
system: a complete fell keeps the farm clear, and the mod no longer breaks any
leaf or block that is not on a planting spot. It also no longer stops on a
floating trunk: a tree the axe cannot finish is skipped and the rest of the farm
keeps running. 1.0.13 removes the between-cycle collect wait (the next cycle
starts immediately while the water stream keeps collecting) and makes the sell
transfer six stacks per tick with no pause between batches. 1.0.14 removes the
remaining wait for a slow server fell: the next cycle starts as soon as the
base logs are gone and leftovers are cleared during grow, and it adds sign-aware
clicks so a sign box on the centre chests no longer blocks planting, bonemeal,
harvesting or restocking. 1.0.15 handles the strict-server
case: when a covered placement is rolled back, the farm skips the spots hidden
behind the upper sign layer, keeps the visible half running, and shows a
warning to remove that layer and Rescan; the four lower signs remain usable.
1.0.16 also skips a single spot the server keeps refusing even though the
scan sees its top face (a claim, a neighbour's block or a ghost support
block), so one dead spot no longer stops a farm that needs the upper signs
for leaf protection. The warning lists how many spots were skipped; fix or
move the spot and press Rescan to bring them back.
Test in a safe area
first. If the server still cancels placements, raise `actionDelayTicks` and
send the new error line, which reports the block state the server returned.

## Layout of the project

```
src/main/java/com/sprucefarm/
  SpruceFarmClient.java      Fabric client entrypoint, keybinding, tick hook
  FarmController.java        state machine, layout/chest scans, safety checks
  FarmScreen.java            in-game panel
  FarmLayout.java            pad and chest detection
  InteractionHelper.java     aiming, raycasts, hotbar selection, breaking
  InventoryHelper.java       item/slot helpers
  SellTask.java              /sell GUI transfer and verification
  RestockTask.java           chest restock and verification
  FarmTask.java              task contract
  PlantSpot.java             per-spot tracking
  logic/                     pure, unit-tested math and planning
src/test/java/               JUnit tests
src/gametest/                Fabric client gametest harness
```

## License

MIT. See [LICENSE](LICENSE).
