package io.github.mooy1.infinityexpansion.implementation.storage;

import io.github.mooy1.infinityexpansion.utils.Util;
import io.github.mooy1.infinitylib.core.ConfigUtils;
import io.github.mooy1.infinitylib.core.PluginUtils;
import io.github.mooy1.infinitylib.items.LoreUtils;
import io.github.mooy1.infinitylib.items.StackUtils;
import io.github.mooy1.infinitylib.players.MessageUtils;
import io.github.mooy1.infinitylib.slimefun.presets.LorePreset;
import io.github.thebusybiscuit.slimefun4.utils.tags.SlimefunTag;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.api.BlockStorage;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.cscorelib2.chat.ChatColors;
import me.mrCookieSlime.Slimefun.cscorelib2.item.CustomItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.DISPLAY_KEY;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.DISPLAY_SLOT;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.EMPTY_KEY;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.INFINITY_STORAGE;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.INPUT_SLOT;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.OUTPUT_SLOT;
import static io.github.mooy1.infinityexpansion.implementation.storage.StorageUnit.STATUS_SLOT;

/**
 * Represents a single storage unit with cached data and main functionality
 *
 * @author Mooy1
 */
final class StorageCache {

    /* Configuration */
    private static final boolean DISPLAY_SIGNS = ConfigUtils.getBoolean("storage-unit-options.display-signs", true);

    /* Menu items */
    private static final ItemStack EMPTY_ITEM = new CustomItem(Material.BARRIER, meta -> {
        meta.setDisplayName(ChatColor.WHITE + "Empty");
        meta.getPersistentDataContainer().set(EMPTY_KEY, PersistentDataType.BYTE, (byte) 1);
    });

    /* Menu strings */
    private static final String EMPTY_DISPLAY_NAME = ChatColor.WHITE + "Empty";
    private static final String VOID_EXCESS_TRUE = ChatColors.color("&7Void Excess:&e true");
    private static final String VOID_EXCESS_FALSE = ChatColors.color("&7Void Excess:&e false");

    /* BlockStorage keys */
    private static final String OLD_STORED_ITEM = "storeditem"; // old item key in block data
    private static final String STORED_AMOUNT = "stored"; // amount key in block data
    private static final String VOID_EXCESS = "void_excess";
    
    /* Instance constants */
    private final StorageUnit unit;
    private final BlockMenu menu;
    
    /* Instance variables */
    private boolean voidExcess;
    private int amount;
    private Material material;
    private ItemMeta meta;
    private String displayName;

    StorageCache(StorageUnit unit, Block block, BlockMenu menu) {
        this.unit = unit;
        this.menu = menu;
        
        Location l = block.getLocation();

        this.voidExcess = BlockStorage.getLocationInfo(l, VOID_EXCESS) != null;
        this.amount = Util.getIntData(STORED_AMOUNT, l);

        if (this.amount == 0) {
            this.displayName = EMPTY_DISPLAY_NAME;
            menu.replaceExistingItem(DISPLAY_SLOT, EMPTY_ITEM);
            return;
        }

        ItemStack display = menu.getItemInSlot(DISPLAY_SLOT);
        if (display != null) {
            ItemMeta copy = display.getItemMeta();

            // fix if they somehow store the empty item
            if (copy.getPersistentDataContainer().has(EMPTY_KEY, PersistentDataType.BYTE)) {
                // attempt to recover the correct item from output
                ItemStack output = menu.getItemInSlot(OUTPUT_SLOT);
                if (output != null) {
                    setStored(output);
                    menu.replaceExistingItem(OUTPUT_SLOT, null);
                } else {
                    setEmpty();
                }
                return;
            }

            load(display, copy);

        } else {
            // attempt to load old data
            String oldID = BlockStorage.getLocationInfo(l, OLD_STORED_ITEM);
            if (oldID != null) {
                BlockStorage.addBlockInfo(l, OLD_STORED_ITEM, null);
                ItemStack item = StackUtils.getItemByIDorType(oldID);
                if (item != null) {
                    load(item, item.getItemMeta());
                } else {
                    // shouldn't happen
                    setEmpty();
                }
            } else {
                // shouldn't happen
                setEmpty();
            }
        }
    }
    
    StorageCache load(ItemStack stored, ItemMeta copy) {
        this.menu.replaceExistingItem(DISPLAY_SLOT, stored);

        // remove the display key from copy
        copy.getPersistentDataContainer().remove(DISPLAY_KEY);

        // check if the copy has anything besides the display key
        if (copy.equals(Bukkit.getItemFactory().getItemMeta(stored.getType()))) {
            this.meta = null;
            this.displayName = StackUtils.getInternalName(stored);
        } else {
            this.meta = copy;
            this.displayName = StackUtils.getDisplayName(stored, copy);
        }
        this.material = stored.getType();
        
        return this;
    }

    void setAmount(int amount) {
        this.amount = amount;
        BlockStorage.addBlockInfo(this.menu.getLocation(), STORED_AMOUNT, String.valueOf(amount));
    }
    
    void destroy(BlockBreakEvent e) {
        if (this.amount != 0) {
            e.setDropItems(false);

            // add output slot
            ItemStack output = this.menu.getItemInSlot(OUTPUT_SLOT);
            if (output != null && matches(output)) {
                int add = Math.min(this.unit.max - this.amount, output.getAmount());
                if (add != 0) {
                    this.amount += add;
                    output.setAmount(output.getAmount() - add);
                }
            }

            ItemStack drop = this.unit.getItem().clone();
            drop.setItemMeta(StorageUnit.saveToStack(drop.getItemMeta(), this.menu.getItemInSlot(DISPLAY_SLOT), this.displayName, this.amount));
            MessageUtils.message(e.getPlayer(), "&aStored items transferred to dropped item");
            e.getBlock().getWorld().dropItemNaturally(this.menu.getLocation(), drop);
        }

        this.menu.dropItems(this.menu.getLocation(), INPUT_SLOT, OUTPUT_SLOT);
    }

    void input() {
        ItemStack input = this.menu.getItemInSlot(INPUT_SLOT);
        if (input == null) {
            return;
        }
        if (this.amount == 0) {
            // set the stored item to input
            setAmount(input.getAmount());
            setStored(input);
            this.menu.replaceExistingItem(INPUT_SLOT, null);
        } else if (this.voidExcess) {
            // input and void excess
            if (matches(input)) {
                input.setAmount(0);
                if (this.amount != this.unit.max) {
                    setAmount(Math.min(this.amount + input.getAmount(), this.unit.max));
                }
            }
        } else {
            // input as much as possible
            int max = this.unit.max - this.amount;
            if (max > 0 && matches(input)) {
                int add = input.getAmount();
                int dif = add - max;
                if (dif < 0) {
                    this.amount += add;
                    input.setAmount(0);
                } else {
                    this.amount += max;
                    input.setAmount(dif);
                }
            }
        }
    }

    void output() {
        if (this.amount > 0) {
            int remove = Math.min(this.material.getMaxStackSize(), this.amount - 1);
            if (remove == 0) {
                if (this.menu.getItemInSlot(OUTPUT_SLOT) == null) {
                    this.menu.replaceExistingItem(OUTPUT_SLOT, createItem(1), false);
                    setEmpty();
                }
            } else {
                ItemStack current = this.menu.getItemInSlot(OUTPUT_SLOT);
                if (current == null) {
                    this.menu.replaceExistingItem(OUTPUT_SLOT, createItem(remove));
                    setAmount(this.amount - remove);
                } else if (current.getAmount() != current.getMaxStackSize() && matches(current)) {
                    remove = Math.min(remove, this.material.getMaxStackSize() - current.getAmount());
                    current.setAmount(current.getAmount() + remove);
                    setAmount(this.amount - remove);
                }
            }
        }
    }

    void updateStatus(Block block) {
        if (this.menu.hasViewer()) {
            input();
            output();
            if (this.unit.max == INFINITY_STORAGE) {
                if (this.amount == 0) {
                    this.menu.replaceExistingItem(STATUS_SLOT, new CustomItem(
                            Material.CYAN_STAINED_GLASS_PANE,
                            "&bStatus",
                            "&6Stored: &e0",
                            "&7Stacks: 0",
                            this.voidExcess ? VOID_EXCESS_TRUE : VOID_EXCESS_FALSE,
                            "&7(Click to toggle)"
                    ), false);
                } else {
                    this.menu.replaceExistingItem(STATUS_SLOT, new CustomItem(
                            Material.CYAN_STAINED_GLASS_PANE,
                            "&bStatus",
                            "&6Stored: &e" + LorePreset.format(this.amount),
                            "&7Stacks: " + this.amount / this.material.getMaxStackSize(),
                            this.voidExcess ? VOID_EXCESS_TRUE : VOID_EXCESS_FALSE,
                            "&7(Click to toggle)"
                    ), false);
                }
            } else {
                if (this.amount == 0) {
                    this.menu.replaceExistingItem(STATUS_SLOT, new CustomItem(
                            Material.CYAN_STAINED_GLASS_PANE,
                            "&bStatus",
                            "&6Stored: &e0 / " + LorePreset.format(this.unit.max) + " &7(0%)",
                            "&7Stacks: 0",
                            this.voidExcess ? VOID_EXCESS_TRUE : VOID_EXCESS_FALSE,
                            "&7(Click to toggle)"
                    ), false);
                } else {
                    this.menu.replaceExistingItem(STATUS_SLOT, new CustomItem(
                            Material.CYAN_STAINED_GLASS_PANE,
                            "&bStatus",
                            "&6Stored: &e" + LorePreset.format(this.amount) + " / " + LorePreset.format(this.unit.max) + " &7(" + (100 * this.amount) / this.unit.max + "%)",
                            "&7Stacks: " + this.amount / this.material.getMaxStackSize(),
                            this.voidExcess ? VOID_EXCESS_TRUE : VOID_EXCESS_FALSE,
                            "&7(Click to toggle)"
                    ), false);
                }
            }
        }
        
        if (DISPLAY_SIGNS && (PluginUtils.getCurrentTick() & 15) == 0) {
            Block check = block.getRelative(0, 1, 0);
            if (SlimefunTag.SIGNS.isTagged(check.getType())
                    || checkWallSign(check = block.getRelative(1, 0, 0), block)
                    || checkWallSign(check = block.getRelative(-1, 0, 0), block)
                    || checkWallSign(check = block.getRelative(0, 0, 1), block)
                    || checkWallSign(check = block.getRelative(0, 0, -1), block)
            ) {
                Sign sign = (Sign) check.getState();
                sign.setLine(0, ChatColor.GRAY + "--------------");
                sign.setLine(1, this.displayName);
                sign.setLine(2, ChatColor.YELLOW.toString() + this.amount);
                sign.setLine(3, ChatColor.GRAY + "--------------");
                sign.update();
            }
        }
    }
    
    @SuppressWarnings("unused")
    boolean interactHandler(Player p, int slot, ItemStack item, ClickAction action) {
        if (this.amount == 1) {
            if (action.isShiftClicked() && !action.isRightClicked()) {
                depositAll(p);
            } else {
                withdrawLast(p);
            }
        } else {
            if (action.isRightClicked()) {
                if (action.isShiftClicked()) {
                    withdraw(p, this.amount - 1);
                } else {
                    withdraw(p, Math.min(this.material.getMaxStackSize(), this.amount - 1));
                }
            } else {
                if (action.isShiftClicked()) {
                    depositAll(p);
                } else {
                    withdraw(p, 1);
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    boolean voidExcessHandler(Player p, int slot, ItemStack item, ClickAction action) {
        if (this.voidExcess) {
            BlockStorage.addBlockInfo(this.menu.getLocation(), VOID_EXCESS, null);
            LoreUtils.replaceLine(this.menu.getItemInSlot(STATUS_SLOT), VOID_EXCESS_TRUE, VOID_EXCESS_FALSE);
            this.voidExcess = false;
        } else {
            BlockStorage.addBlockInfo(this.menu.getLocation(), VOID_EXCESS, "true");
            LoreUtils.replaceLine(this.menu.getItemInSlot(STATUS_SLOT), VOID_EXCESS_FALSE, VOID_EXCESS_TRUE);
            this.voidExcess = true;
        }
        return false;
    }

    private static boolean checkWallSign(Block sign, Block block) {
        return SlimefunTag.WALL_SIGNS.isTagged(sign.getType())
                && sign.getRelative(((WallSign) sign.getBlockData()).getFacing().getOppositeFace()).equals(block);
    }
    
    private void setStored(ItemStack input) {
        if (input.hasItemMeta()) {
            this.meta = input.getItemMeta();
            this.displayName = StackUtils.getDisplayName(input, this.meta);
        } else {
            this.meta = null;
            this.displayName = StackUtils.getInternalName(input);
        }
        this.material = input.getType();

        // add the display key to the display input and set amount 1
        ItemMeta meta = input.getItemMeta();
        meta.getPersistentDataContainer().set(DISPLAY_KEY, PersistentDataType.BYTE, (byte) 1);
        input.setItemMeta(meta);
        input.setAmount(1);

        this.menu.replaceExistingItem(DISPLAY_SLOT, input);
    }

    private void setEmpty() {
        this.displayName = EMPTY_DISPLAY_NAME;
        this.meta = null;
        this.material = null;
        this.menu.replaceExistingItem(DISPLAY_SLOT, EMPTY_ITEM);
        setAmount(0);
    }
    
    boolean isEmpty() {
        return this.amount == 0;
    }

    boolean matches(ItemStack item) {
        return item.getType() == this.material
                && item.hasItemMeta() == (this.meta != null)
                && (this.meta == null || this.meta.equals(item.getItemMeta()));
    }

    private ItemStack createItem(int amount) {
        ItemStack item = new ItemStack(this.material, amount);
        if (this.meta != null) {
            item.setItemMeta(this.meta);
        }
        return item;
    }

    private void withdraw(Player p, int withdraw) {
        ItemStack remaining = p.getInventory().addItem(createItem(withdraw)).get(0);
        if (remaining != null) {
            if (remaining.getAmount() != withdraw) {
                setAmount(this.amount - withdraw + remaining.getAmount());
            }
        } else {
            setAmount(this.amount - withdraw);
        }
    }

    private void withdrawLast(Player p) {
        if (p.getInventory().addItem(createItem(1)).isEmpty()) {
            setEmpty();
        }
    }

    private void depositAll(Player p) {
        int notDeposited = this.unit.max - this.amount;
        if (notDeposited != 0) {
            for (ItemStack item : p.getInventory().getStorageContents()) {
                if (item != null && matches(item)) {
                    if (item.getAmount() > notDeposited) {
                        notDeposited -= item.getAmount();
                        item.setAmount(0);
                    } else {
                        // the storage is full after this
                        item.setAmount(item.getAmount() - notDeposited);
                        setAmount(this.unit.max);
                        return;
                    }
                }
            }
            setAmount(this.unit.max - notDeposited);
        }
    }

}