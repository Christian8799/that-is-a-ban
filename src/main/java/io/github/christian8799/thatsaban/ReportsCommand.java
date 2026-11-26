package io.github.christian8799.thatsaban;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ReportsCommand implements CommandExecutor, TabCompleter, Listener {
    private static final int PAGE_SIZE = 36;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ThatsABan plugin;
    private final ReportManager reports;

    ReportsCommand(ThatsABan plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("thatsaban.reports") && !sender.hasPermission("thatsaban.admin")) {
            sender.sendMessage(Component.text("Dafür hast du keine Berechtigung.", NamedTextColor.RED));
            return true;
        }

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("list"))) {
            if (sender instanceof Player player) openList(player, 0, Filter.OPEN);
            else sendConsoleList(sender);
            return true;
        }

        if (args.length != 2) {
            sender.sendMessage(Component.text("Benutzung: /reports [list|view|offend|resolve|dismiss] <ID>", NamedTextColor.YELLOW));
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        String id = args[1].toLowerCase(Locale.ROOT);
        ReportManager.ReportEntry entry = reports.find(id);
        if (entry == null) {
            sender.sendMessage(Component.text("Report #" + id + " wurde nicht gefunden.", NamedTextColor.RED));
            return true;
        }

        if (action.equals("view") && sender instanceof Player player) {
            openDetails(player, entry);
            return true;
        }

        switch (action) {
            case "view" -> sendDetails(sender, entry);
            case "offend" -> offend(sender, entry);
            case "resolve" -> {
                if (reports.setStatus(id, ReportManager.Status.RESOLVED)) {
                    sender.sendMessage(Component.text("Report #" + id + " wurde als erledigt markiert.", NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Der Report-Status konnte nicht dauerhaft gespeichert werden.", NamedTextColor.RED));
                }
            }
            case "dismiss" -> {
                if (reports.setStatus(id, ReportManager.Status.DISMISSED)) {
                    sender.sendMessage(Component.text("Report #" + id + " wurde verworfen.", NamedTextColor.GRAY));
                } else {
                    sender.sendMessage(Component.text("Der Report-Status konnte nicht dauerhaft gespeichert werden.", NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text("Unbekannte Aktion. Nutze /reports.", NamedTextColor.RED));
        }
        return true;
    }

    private void offend(CommandSender sender, ReportManager.ReportEntry entry) {
        if (entry.status() != ReportManager.Status.OPEN || entry.replayPath() == null) {
            sender.sendMessage(Component.text("Für diesen Report liegt kein prüfbares Replay vor.", NamedTextColor.RED));
            return;
        }
        if (!sender.hasPermission("thatsaban.offend")) {
            sender.sendMessage(Component.text("Dafür benötigst du zusätzlich thatsaban.offend.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Starte /offend für " + entry.targetName() + " …", NamedTextColor.YELLOW));
        plugin.getServer().dispatchCommand(sender, "offend " + entry.targetId());
    }

    private void sendConsoleList(CommandSender sender) {
        List<ReportManager.ReportEntry> entries = reports.reports().stream()
                .filter(entry -> entry.status() == ReportManager.Status.OPEN
                        || entry.status() == ReportManager.Status.CAPTURING
                        || entry.status() == ReportManager.Status.FAILED)
                .limit(25).toList();
        sender.sendMessage(Component.text("Offene Reports (" + entries.size() + "):", NamedTextColor.GOLD));
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("Keine offenen Reports.", NamedTextColor.GRAY));
            return;
        }
        for (ReportManager.ReportEntry entry : entries) {
            sender.sendMessage(Component.text("#" + entry.id() + " ", NamedTextColor.AQUA)
                    .append(Component.text(entry.targetName() + " — " + entry.status().name(), NamedTextColor.WHITE))
                    .append(Component.text("  /reports view " + entry.id(), NamedTextColor.GRAY)));
        }
    }

    private void openList(Player player, int requestedPage, Filter filter) {
        List<ReportManager.ReportEntry> entries = filteredReports(filter);
        int pages = Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(MenuType.LIST, page, filter, null);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Spieler-Reports · " + filter.title + " · " + (page + 1) + "/" + pages,
                        NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        inventory.setItem(4, item(Material.WRITABLE_BOOK, "§6§lReport-Verwaltung",
                "§7Reports auswählen, prüfen und bearbeiten.",
                "§7Klicke einen Eintrag für die Detailansicht."));
        int start = page * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            ReportManager.ReportEntry entry = entries.get(i);
            int slot = 9 + (i - start);
            holder.reportIds.put(slot, entry.id());
            inventory.setItem(slot, reportItem(entry));
        }

        inventory.setItem(45, filterItem(Filter.ALL, filter));
        inventory.setItem(46, filterItem(Filter.OPEN, filter));
        inventory.setItem(47, filterItem(Filter.FAILED, filter));
        inventory.setItem(48, filterItem(Filter.RESOLVED, filter));
        inventory.setItem(49, filterItem(Filter.DISMISSED, filter));
        inventory.setItem(50, item(Material.ARROW, "§eVorherige Seite", "§7Zur vorherigen Seite wechseln."));
        inventory.setItem(51, item(Material.PAPER, "§fSeite " + (page + 1) + " von " + pages,
                "§7" + entries.size() + " Reports in dieser Ansicht."));
        inventory.setItem(52, item(Material.SPECTRAL_ARROW, "§eNächste Seite", "§7Zur nächsten Seite wechseln."));
        inventory.setItem(53, item(Material.BARRIER, "§cSchließen", "§7Menü schließen."));
        player.openInventory(inventory);
    }

    private List<ReportManager.ReportEntry> filteredReports(Filter filter) {
        return reports.reports().stream().filter(entry -> switch (filter) {
            case ALL -> true;
            case OPEN -> entry.status() == ReportManager.Status.OPEN
                    || entry.status() == ReportManager.Status.CAPTURING;
            case FAILED -> entry.status() == ReportManager.Status.FAILED;
            case RESOLVED -> entry.status() == ReportManager.Status.RESOLVED;
            case DISMISSED -> entry.status() == ReportManager.Status.DISMISSED;
        }).toList();
    }

    private ItemStack reportItem(ReportManager.ReportEntry entry) {
        Material material = switch (entry.status()) {
            case OPEN -> Material.MAP;
            case CAPTURING -> Material.CLOCK;
            case FAILED -> Material.RED_WOOL;
            case RESOLVED -> Material.LIME_WOOL;
            case DISMISSED -> Material.GRAY_WOOL;
        };
        String statusColor = statusLegacyColor(entry.status());
        return item(material, statusColor + "§l#" + entry.id() + " §f" + safeLegacyText(entry.targetName()),
                "§7Status: " + statusName(entry.status()),
                "§7Gemeldet von: §f" + safeLegacyText(entry.reporterName()),
                "§7Zeit: §f" + DATE_FORMAT.format(Instant.ofEpochMilli(entry.createdAt())),
                "§7Grund: §f" + shorten(safeLegacyText(entry.reason()), 70),
                entry.replayPath() == null ? "§cKein Replay verfügbar" : "§aReplay gespeichert",
                "§eKlicken für Details");
    }

    private ItemStack filterItem(Filter option, Filter selected) {
        Material material = option == selected ? Material.LIME_DYE : Material.GRAY_DYE;
        String color = option == selected ? "§a§l" : "§7";
        return item(material, color + option.title, option == selected
                ? "§aAktiver Filter"
                : "§7Klicken, um diesen Filter anzuzeigen.");
    }

    private void openDetails(Player player, ReportManager.ReportEntry entry) {
        MenuHolder holder = new MenuHolder(MenuType.DETAIL, 0, Filter.OPEN, entry.id());
        Inventory inventory = Bukkit.createInventory(holder, 54,
                Component.text("Report #" + entry.id(), NamedTextColor.DARK_AQUA));
        holder.inventory = inventory;

        inventory.setItem(4, item(Material.PLAYER_HEAD, "§6§l" + safeLegacyText(entry.targetName()),
                "§7Spieler-UUID: §f" + entry.targetId(),
                "§7Gemeldet von: §f" + safeLegacyText(entry.reporterName()),
                "§7Reporter-UUID: §f" + entry.reporterId(),
                "§7Zeit: §f" + DATE_FORMAT.format(Instant.ofEpochMilli(entry.createdAt())),
                "§7Status: " + statusName(entry.status())));
        inventory.setItem(20, item(Material.BOOK, "§eMeldegrund",
                "§7" + shorten(safeLegacyText(entry.reason()), 120)));
        inventory.setItem(22, item(entry.replayPath() == null ? Material.BARRIER : Material.MAP,
                entry.replayPath() == null ? "§cKein Replay verfügbar" : "§aReplay-Pfad anzeigen",
                entry.replayPath() == null ? "§7Für diesen Report wurde kein Clip gespeichert."
                        : "§7Klicke hier, um den Dateipfad im Chat",
                entry.replayPath() == null ? "§7anzuzeigen." : "§7mit Kopierfunktion auszugeben."));
        inventory.setItem(29, item(Material.ANVIL, "§c§lOffenden",
                "§7Führt die bestehende /offend-Funktion aus.",
                "§7Nur nach eigener Replay-Prüfung verwenden.",
                entry.status() == ReportManager.Status.OPEN && entry.replayPath() != null
                        ? "§eKlicken zum Fortfahren" : "§cFür diesen Report nicht verfügbar"));
        inventory.setItem(31, item(Material.LIME_CONCRETE, "§aAls erledigt markieren",
                "§7Schließt den Report als geprüft ab."));
        inventory.setItem(33, item(Material.GRAY_CONCRETE, "§7Report verwerfen",
                "§7Markiert den Report als unbegründet oder ungültig."));
        inventory.setItem(49, item(Material.ARROW, "§eZurück zur Report-Liste",
                "§7Zurück zur vorherigen Übersicht."));
        inventory.setItem(53, item(Material.BARRIER, "§cSchließen", "§7Menü schließen."));
        player.openInventory(inventory);
    }

    private void sendDetails(CommandSender sender, ReportManager.ReportEntry entry) {
        sender.sendMessage(Component.text("Report #" + entry.id(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Spieler: " + entry.targetName() + " (" + entry.targetId() + ")", NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Gemeldet von: " + entry.reporterName(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("Zeit: " + DATE_FORMAT.format(Instant.ofEpochMilli(entry.createdAt())), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("Status: " + entry.status().name(), NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("Grund: " + entry.reason(), NamedTextColor.WHITE));
        sendReplayPath(sender, entry);
        sender.sendMessage(Component.text("Aktionen: /reports offend " + entry.id()
                + " | /reports resolve " + entry.id() + " | /reports dismiss " + entry.id(), NamedTextColor.GRAY));
    }

    private void sendReplayPath(CommandSender sender, ReportManager.ReportEntry entry) {
        if (entry.replayPath() == null) {
            sender.sendMessage(Component.text("Replay: noch nicht verfügbar.", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Replay-Pfad (anklicken zum Kopieren): ", NamedTextColor.GRAY)
                .append(Component.text(entry.replayPath(), NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.copyToClipboard(entry.replayPath()))));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() == null
                || event.getClickedInventory() != event.getView().getTopInventory()) return;

        int slot = event.getRawSlot();
        if (holder.type == MenuType.LIST) {
            handleListClick(player, holder, slot);
        } else {
            handleDetailsClick(player, holder, slot);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    private void handleListClick(Player player, MenuHolder holder, int slot) {
        String id = holder.reportIds.get(slot);
        if (id != null) {
            ReportManager.ReportEntry entry = reports.find(id);
            if (entry != null) openDetails(player, entry);
            return;
        }
        switch (slot) {
            case 45 -> openList(player, 0, Filter.ALL);
            case 46 -> openList(player, 0, Filter.OPEN);
            case 47 -> openList(player, 0, Filter.FAILED);
            case 48 -> openList(player, 0, Filter.RESOLVED);
            case 49 -> openList(player, 0, Filter.DISMISSED);
            case 50 -> openList(player, holder.page - 1, holder.filter);
            case 52 -> openList(player, holder.page + 1, holder.filter);
            case 53 -> player.closeInventory();
            default -> { }
        }
    }

    private void handleDetailsClick(Player player, MenuHolder holder, int slot) {
        ReportManager.ReportEntry entry = reports.find(holder.reportId);
        if (slot == 49) {
            openList(player, 0, Filter.OPEN);
            return;
        }
        if (slot == 53) {
            player.closeInventory();
            return;
        }
        if (entry == null) {
            player.sendMessage(Component.text("Dieser Report existiert nicht mehr.", NamedTextColor.RED));
            player.closeInventory();
            return;
        }
        switch (slot) {
            case 22 -> sendReplayPath(player, entry);
            case 29 -> {
                offend(player, entry);
                if (entry.status() == ReportManager.Status.OPEN && entry.replayPath() != null
                        && player.hasPermission("thatsaban.offend")) player.closeInventory();
            }
            case 31 -> {
                if (reports.setStatus(entry.id(), ReportManager.Status.RESOLVED)) {
                    player.sendMessage(Component.text("Report #" + entry.id() + " wurde als erledigt markiert.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Der Report-Status konnte nicht dauerhaft gespeichert werden.", NamedTextColor.RED));
                }
                openDetails(player, entry);
            }
            case 33 -> {
                if (reports.setStatus(entry.id(), ReportManager.Status.DISMISSED)) {
                    player.sendMessage(Component.text("Report #" + entry.id() + " wurde verworfen.", NamedTextColor.GRAY));
                } else {
                    player.sendMessage(Component.text("Der Report-Status konnte nicht dauerhaft gespeichert werden.", NamedTextColor.RED));
                }
                openDetails(player, entry);
            }
            default -> { }
        }
    }

    private ItemStack item(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacySection().deserialize(name));
        List<Component> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(LegacyComponentSerializer.legacySection().deserialize(line));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String statusLegacyColor(ReportManager.Status status) {
        return switch (status) {
            case OPEN -> "§a";
            case CAPTURING -> "§e";
            case FAILED -> "§c";
            case RESOLVED -> "§7";
            case DISMISSED -> "§8";
        };
    }

    private static String statusName(ReportManager.Status status) {
        return switch (status) {
            case OPEN -> "§aOffen";
            case CAPTURING -> "§eReplay wird gespeichert";
            case FAILED -> "§cReplay fehlgeschlagen";
            case RESOLVED -> "§7Erledigt";
            case DISMISSED -> "§8Verworfen";
        };
    }

    private static String safeLegacyText(String value) {
        return value == null ? "" : value.replace("§", "").replace('\n', ' ').replace('\r', ' ');
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("thatsaban.reports") && !sender.hasPermission("thatsaban.admin")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("list", "view", "offend", "resolve", "dismiss").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return reports.reports().stream().map(ReportManager.ReportEntry::id)
                    .filter(id -> id.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private enum Filter {
        OPEN("Offen"), ALL("Alle"), FAILED("Fehler"), RESOLVED("Erledigt"), DISMISSED("Verworfen");
        private final String title;
        Filter(String title) { this.title = title; }
    }

    private enum MenuType { LIST, DETAIL }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuType type;
        private final int page;
        private final Filter filter;
        private final String reportId;
        private final Map<Integer, String> reportIds = new HashMap<>();
        private Inventory inventory;

        private MenuHolder(MenuType type, int page, Filter filter, String reportId) {
            this.type = type;
            this.page = page;
            this.filter = filter;
            this.reportId = reportId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
