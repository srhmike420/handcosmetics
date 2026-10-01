HandCosmetics 2.0

Target: Paper 26.2 / Java 25, CosmeticsCore 1.3.13, ItemsAdder.

WHAT IT DOES
- MAIN_HAND and OFF_HAND visual cosmetics.
- The real held/offhand item is never moved or replaced.
- Cosmetic is resent as fake equipment to wearer + observers, so it visually covers the real item.
- Main hand and off hand are independent and can both be equipped.
- Persists selections in players.yml.
- Adds Main Hand / Off Hand buttons to EMPTY slots in the normal /cosmetics inventory.
- Does not touch the CosmeticsCore wardrobe preview.
- Clicking Back runs /cosmetics.
- Includes ItemsAdder menu icons.

INSTALL
1. Put target/handcosmetics-2.0.0.jar in plugins.
2. Merge resourcepack/plugins/ItemsAdder into your server plugins/ItemsAdder.
3. Regenerate the ItemsAdder resource pack.
4. Start server once, then add your hand cosmetics to plugins/HandCosmetics/config.yml.
5. /hc reload

IMPORTANT
The CosmeticsCore menu itself is not exposed as a public category-registration API in 1.3.13.
This build injects its two tagged ItemStacks only into EMPTY slots of an inventory whose title matches config.
It will never replace a non-empty CosmeticsCore menu item. If your menu title does not contain "Cosmetics",
put the exact visible title text under cosmeticscore-menu.title-contains.

Commands: /hc, /hc main, /hc off, /hc equip <id>, /hc clear main|off, /hc reload
