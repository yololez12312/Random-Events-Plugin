# RandomWorldEvents (Paper/Purpur)

An original Java plugin inspired by server-wide random SMP events. This is a Paper/Purpur plugin, not a Fabric mod or a copy of any private server plugin.

## Events included
- Exhausted — Mining Fatigue
- Starvation — Hunger effect
- Double Drops — non-player mobs drop twice their normal item loot
- Forbidden Fruit — chance for leaves to drop golden apples
- Miner's Haven — chance for bonus ore drops
- Vulnerable — max health reduced to 10 hearts
- Anonymous — hides player nametags
- Ultra Hardcore — sets saturation to zero (not a full natural-regeneration gamerule override)
- **Flimsy Armor:** equipped armor takes 15 extra durability damage per hit during the event (5× the previous extra-wear setting).
- Glow and Behold — glowing effect
- Shieldless — blocks shield right-click
- Supply Rush — spawns supply chests with randomized loot near online players

## Build
Requires JDK 21 and Maven. In this folder, run:

```bash
mvn clean package
```

The plugin jar will be in `target/RandomWorldEvents-1.0.0.jar`. The Paper API is marked `provided`, so don't put Paper API in your server's `plugins` folder.

## Install
1. Build the jar.
2. Put `RandomWorldEvents-1.0.0.jar` in the server's `plugins` folder.
3. Restart the server.
4. Edit `plugins/RandomWorldEvents/config.yml` if desired, then restart.

Commands (OP by default):
- `/randomworldevents start`
- `/randomworldevents stop`
- `/randomworldevents next`
- `/randomworldevents reload`

Default schedule: starts one event after 5 seconds, then tries to start an event every 15 minutes. Event length defaults to 3 minutes. Supply Rush creates 3 supply chests by default; configure `settings.supply-chest-count` in config.yml. Non-empty supply chests are left in the world after the event ends; empty event chests are removed.

## Notes
Test on a backup/test server first. Paper API availability can vary by exact server build; if Maven cannot resolve `1.21.11-R0.1-SNAPSHOT`, change the Paper API dependency version to the exact API version offered by your server's Paper repository.


### Supply Rush
- Spawns one supply chest immediately, then another every 3 minutes while the event is active.
- Announces each chest's world name and X/Y/Z coordinates in global chat.
- Each chest contains 32 golden apples, regular loot, and has a chance to contain up to 2 max-enchanted netherite armor pieces.
- Supply Rush lasts 10 minutes by default; change `settings.supply-rush-duration-seconds` to adjust it.
- Change `settings.supply-rush-chest-count and supply-rush-area-size-blocks` to change the chest spawn interval.


## Admin commands

All commands require `randomworldevents.admin` (operators have it by default).

- `/randomworldevents` — show command help.
- `/randomworldevents force <event>` — force-start a named event immediately. Event names can be entered with underscores, e.g. `flimsy_armor`, `supply_rush`, `double_drops`, or `glow_and_behold`.
- `/randomworldevents start [event]` — enable random scheduling, optionally force-start a specific event.
- `/randomworldevents stop` — stop the current event but leave random scheduling enabled.
- `/randomworldevents stop all` — stop the current event and disable random scheduling.
- `/randomworldevents reroll` — replace the current event with a different random event.
- `/randomworldevents next` — start a different random event immediately.
- `/randomworldevents list` — list all event names.
- `/randomworldevents status` — show whether scheduling is enabled, the active event, and remaining time.
- `/randomworldevents reload` — reload the config.
- `/randomworldevents enable` or `/randomworldevents disable` — enable or disable the random-event system.

Tab completion is included for command names and event names.
