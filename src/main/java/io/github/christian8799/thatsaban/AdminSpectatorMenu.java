package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plugin-owned admin controls. The vanilla spectator hotbar is client-side and
 * cannot be extended by a Paper plugin, so these controls use a separate GUI.
 */
final class AdminSpectatorMenu implements Listener {
    private static final int PLAYER_SLOTS = 45;

    private final ThatsABan plugin;
    private final AdminSpectatorManager manager;

    AdminSpectatorMenu(ThatsABan plugin, AdminSpectatorManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void openMain(Player player) {
        if (!player.hasPermission("thatsaban.spectator") || !manager.isObserving(player)) {
            player.sendMessage("You must be in an active admin observation to open this menu.");
            return;
        }

        MenuHolder holder = new MenuHolder(MenuType.MAIN, 0, null);
        Inventory inventory = create(holder, "Admin Spectator");
        inventory.setItem(0, item(Material.COMPASS, "§b§lQuick Teleport",
                "§7Choose another online player to observe."));
        inventory.setItem(1, item(Material.WRITABLE_BOOK, "§e§lReports",
                "§7Open the existing staff reports GUI."));
        inventory.setItem(2, item(Material.ANVIL, "§c§lOffend",
                "§7Review the currently observed player",
                "§7and confirm before running /offend."));
        inventory.setItem(8, item(Material.BARRIER, "§7Close",
                "§7Close this menu."));
        player.openInventory(inventory);
    }

    private void openPlayerSelection(Player player, int requestedPage) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.getUniqueId().equals(player.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int pages = Math.max(1, (online.size() + PLAYER_SLOTS - 1) / PLAYER_SLOTS);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(MenuType.PLAYERS, page, null);
        Inventory inventory = create(holder, "Quick Teleport · " + (page + 1) + "/" + pages);
        int start = page * PLAYER_SLOTS;
        int end = Math.min(online.size(), start + PLAYER_SLOTS);
        for (int index = start; index < end; index++) {
            Player target = online.get(index);
            int slot = index - start;
            holder.playerSlots.put(slot, target.getUniqueId());
            inventory.setItem(slot, playerHead(target,
                    "§a" + target.getName(),
                    "§7Click to observe this player."));
        }

        if (online.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "§cNo other players online"));
        }
        inventory.setItem(45, item(Material.ARROW, "§ePrevious page",
                page == 0 ? "§7No previous page." : "§7Go to the previous page."));
        inventory.setItem(49, item(Material.BARRIER, "§cBack",
                "§7Return to the admin menu."));
        inventory.setItem(53, item(Material.ARROW, "§eNext page",
                page + 1 >= pages ? "§7No next page." : "§7Go to the next page."));
        player.openInventory(inventory);
    }

    private void openOffendConfirmation(Player player) {
        if (!player.hasPermission("thatsaban.offend")) {
            player.sendMessage("Missing permission: thatsaban.offend.");
            return;
        }
        Player target = manager.currentTarget(player);
        if (target == null) {
            player.sendMessage("You are not currently spectating a player.");
            return;
        }

        MenuHolder holder = new MenuHolder(MenuType.OFFEND_CONFIRM, 0, target.getUniqueId());
        Inventory inventory = create(holder, "Confirm Offend");
        inventory.setItem(2, item(Material.LIME_WOOL, "§a§lConfirm Offend",
                "§7Run /offend for " + target.getName() + ".",
                "§7This action changes the player's Layer 2 status."));
        inventory.setItem(4, playerHead(target,
                "§eCurrently observed: " + target.getName(),
                "§7Target UUID: " + target.getUniqueId(),
                "§7Check this player before confirming."));
        inventory.setItem(6, item(Material.RED_WOOL, "§c§lCancel",
                "§7Return to the admin menu without taking action."));
        player.openInventory(inventory);
    }

    private Inventory create(MenuHolder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, holder.type == MenuType.MAIN
                || holder.type == MenuType.OFFEND_CONFIRM ? 9 : 54,
                Component.text(title, NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack playerHead(OfflinePlayer owner, String name, String... lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.setOwningPlayer(owner);
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        int slot = event.getRawSlot();

        switch (holder.type) {
            case MAIN -> {
                if (slot == 0) openPlayerSelection(player, 0);
                else if (slot == 1) {
                    player.closeInventory();
                    Bukkit.dispatchCommand(player, "reports");
                } else if (slot == 2) openOffendConfirmation(player);
                else if (slot == 8) player.closeInventory();
            }
            case PLAYERS -> {
                if (slot >= 0 && slot < PLAYER_SLOTS) {
                    UUID targetId = holder.playerSlots.get(slot);
                    Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
                    if (target == null) {
                        player.sendMessage("That player is no longer online.");
                        openPlayerSelection(player, holder.page);
                    } else if (manager.switchTarget(player, target)) {
                        player.sendMessage("Now spectating: " + target.getName());
                        openMain(player);
                    } else {
                        player.sendMessage("Could not switch spectator target.");
                    }
                } else if (slot == 45 && holder.page > 0) {
                    openPlayerSelection(player, holder.page - 1);
                } else if (slot == 49) {
                    openMain(player);
                } else if (slot == 53) {
                    openPlayerSelection(player, holder.page + 1);
                }
            }
            case OFFEND_CONFIRM -> {
                if (slot == 2) {
                    if (!player.hasPermission("thatsaban.offend")) {
                        player.closeInventory();
                        player.sendMessage("Missing permission: thatsaban.offend.");
                        return;
                    }
                    Player currentTarget = manager.currentTarget(player);
                    if (currentTarget == null || !currentTarget.getUniqueId().equals(holder.targetId)) {
                        player.closeInventory();
                        player.sendMessage("The observed player changed or went offline. Offend was not executed.");
                        return;
                    }
                    player.closeInventory();
                    Bukkit.dispatchCommand(player, "offend " + currentTarget.getName());
                } else if (slot == 6) {
                    openMain(player);
                }
            }
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private enum MenuType {
        MAIN, PLAYERS, OFFEND_CONFIRM
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final int page;
        private final UUID targetId;
        private final Map<Integer, UUID> playerSlots = new HashMap<>();
        private Inventory inventory;

        private MenuHolder(MenuType type, int page, UUID targetId) {
            this.type = type;
            this.page = page;
            this.targetId = targetId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
