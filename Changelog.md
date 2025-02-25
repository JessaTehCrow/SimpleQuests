Simple Quests 1.3.1
================
- Add some more context to some stuff (API)
- Add crafting task. Triggered when the player crafts an item

Simple Quests 1.3.0
================
- Add quest categories
- Add triggers for breaking and interacting with a block
  See the wiki for all the syntaxes
- Fix mixin metadata missing on forge thus causing the mod to not work properly.
  How did no one report on this?
- Add submission triggers. default to "". If a quest has this defined it can only be completed via
  command **/simplequest submit \<type\>** which requires op
- Add an player sensitive unlock condition
- Made it so errors during loading of datapack or players gets logged but does not prevent loading the whole thing completely
- Add option to execute a command upon completing a quest

Simple Quests 1.2.0
================
- Add support for week, days etc. for quest cooldown.  
  You dont need to just use ticks anymore.
  Refer to the wiki for the format of it.
- Fix not all quests showing up in gui
- Added more quest triggers. Wiki will have the formats for them:  
  **Position Entry**: Checks if a player is within a certain distance of a given position  
  **Location Entry**: Extended version of position entry. It uses vanilla location predicate which enables you to
  check for e.g. structures, light level, dimension etc.
  **Entity Interaction Entry**: For interacting (right click) an entity (with an item).
- Some stuff for existing entries got renamed. Your current datapacks will most likely not work anymore.  
  **Ingredient Entry**: 
  - id got renamed from "simplequest:ingredient" -> "simplequest:item"
  - "ingredient" -> "predicate" but it now uses vanilla item predicates instead of ingredient. See the wiki for the correct format
  - added field "consumeItems" defaulting to true and if false will not consume the items upon submitting
  
  **Kill Entry**:
  - "entity" -> "predicate" and uses now vanilla entity predicate  
  
  **Advancement Entry**
  - added field "reset" defaulting to false and if true will revoke the advancement upon completion
- Quests can now have multiple parent quests
- Quests can now set to locked. Locked quests can't be accepted till they are unlocked.
  Use ./simplequest unlock <player> <id> to unlock it. Needs op permissions.
- Fix completing child quests resetting parent quest cooldown
- Add daily quests: set "daily_quest" in the quest json to true.
  Daily quests will auto accepted and lasts for the day. Failing to complete them simply resets them
- Fix command permissions

Simple Quests 1.0.2
================
- Add option to specify quest icon

Simple Quests 1.0.1
================
- Fix kill quests giving reward but not actually finishing the quest
- Only notify player once on kill quest completion

Simple Quests 1.0.0
================
- Initial Release
- Update to 1.19