package io.github.christian8799.thatsaban;

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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Interactive, permission-aware account network browser. */
final class AccountNetworkMenu implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final ThatsABan plugin;
    private final LinkStore store;

    AccountNetworkMenu(ThatsABan plugin, LinkStore store) {
        this.plugin = plugin;
        this.store = store;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void open(Player viewer, UUID rootId, int depth, int requestedPage) {
        if (!viewer.hasPermission("thatsaban.network") && !viewer.hasPermission("thatsaban.admin")) {
            viewer.sendMessage("Missing permission: thatsaban.network.");
            return;
        }
        if (!plugin.getConfig().getBoolean("alt-detection.enabled", false)) {
            viewer.sendMessage("Alt-account detection is disabled.");
            return;
        }
        if (store.isWriteBlocked()) {
            viewer.sendMessage("Account network unavailable: LinkStore is read-only.");
            return;
        }

        LinkStore.NetworkSnapshot graph = store.network(rootId, depth, 50);
        List<LinkStore.NetworkEntry> entries = graph.accounts();
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuHolder holder = new MenuHolder(rootId, depth, page, entries);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                "Account Network · " + (page + 1) + "/" + pages);
        holder.inventory = inventory;

        boolean showNames = viewer.hasPermission("thatsaban.view-linked-accounts")
                || viewer.hasPermission("thatsaban.admin");
        String rootName = showNames && store.nameOf(rootId) != null
                ? store.nameOf(rootId) : rootId.toString();
        inventory.setItem(4, item(Material.COMPASS, "§b§l" + rootName,
                "§7Starting account",
                "§7Maximum depth: §f" + depth,
                "§7Connections: §f" + entries.size() + (graph.truncated() ? "+" : ""),
                "§8Click a player head to inspect their network."));

        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            LinkStore.NetworkEntry entry = entries.get(index);
            String displayName = showNames && entry.name() != null
                    ? entry.name() : entry.uuid().toString();
            String viaName = showNames && store.nameOf(entry.connectedVia()) != null
                    ? store.nameOf(entry.connectedVia()) : entry.connectedVia().toString();
            String lastSeen = entry.lastSeen() > 0L
                    ? DATE_FORMAT.format(Instant.ofEpochMilli(entry.lastSeen())) : "Unknown";
            int slot = index - start;
            holder.accountSlots.put(slot, entry.uuid());
            OfflinePlayer offline = Bukkit.getOfflinePlayer(entry.uuid());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(offline);
            meta.setDisplayName((entry.depth() == 1 ? "§a" : "§e") + displayName);
            meta.setLore(List.of(
                    "§7Relation: " + (entry.depth() == 1 ? "§aDirect" : "§eIndirect"),
                    "§7Depth: §f" + entry.depth(),
                    "§7Connected via: §f" + viaName,
                    "§7Shared IP HMACs: §f" + entry.sharedIpCount(),
                    "§7Last seen: §f" + lastSeen,
                    "§8Click to open this account's network"
            ));
            head.setItemMeta(meta);
            inventory.setItem(slot, head);
        }

        if (entries.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, "§cNo stored connections",
                    "§7No connections were found within the retention period."));
        }
        inventory.setItem(45, item(Material.ARROW, "§ePrevious page"));
        inventory.setItem(47, item(Material.REDSTONE, "§cDepth -1",
                "§7Current depth: §f" + depth));
        inventory.setItem(49, item(Material.BARRIER, "§cClose"));
        inventory.setItem(51, item(Material.EMERALD, "§aDepth +1",
                "§7Current depth: §f" + depth));
        inventory.setItem(53, item(Material.ARROW, "§eNext page"));
        viewer.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();
        if (slot >= 0 && slot < PAGE_SIZE) {
            UUID selected = holder.accountSlots.get(slot);
            if (selected != null) open(player, selected, holder.depth, 0);
            return;
        }
        if (slot == 49) {
            player.closeInventory();
        } else if (slot == 45 && holder.page > 0) {
            open(player, holder.rootId, holder.depth, holder.page - 1);
        } else if (slot == 53) {
            open(player, holder.rootId, holder.depth, holder.page + 1);
        } else if (slot == 47 && holder.depth > 1) {
            open(player, holder.rootId, holder.depth - 1, 0);
        } else if (slot == 51 && holder.depth < 3) {
            open(player, holder.rootId, holder.depth + 1, 0);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(List.of(lore));
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID rootId;
        private final int depth;
        private final int page;
        private final Map<Integer, UUID> accountSlots = new HashMap<>();
        private final List<LinkStore.NetworkEntry> entries;
        private Inventory inventory;

        private MenuHolder(UUID rootId, int depth, int page, List<LinkStore.NetworkEntry> entries) {
            this.rootId = rootId;
            this.depth = depth;
            this.page = page;
            this.entries = entries;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
