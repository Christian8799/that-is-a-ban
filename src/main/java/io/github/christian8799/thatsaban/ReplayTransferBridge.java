package io.github.christian8799.thatsaban;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class ReplayTransferBridge implements PluginMessageListener {
    static final String CHANNEL = "thatsaban:replay_transfer";
    private static final int PROTOCOL_VERSION = 1;
    private static final int REQUEST_REPLAY = 1;
    private static final int TRANSFER_START = 2;
    private static final int TRANSFER_CHUNK = 3;
    private static final int TRANSFER_END = 4;
    private static final int TRANSFER_ERROR = 5;
    private static final int TRANSFER_CANCEL = 6;
    private static final int MENU_REQUEST_REPLAY = 7;
    private static final int CHUNK_SIZE = 24 * 1024;
    private static final long MAX_REPLAY_BYTES = 256L * 1024L * 1024L;

    private final ThatsABan plugin;
    private final ReportManager reports;
    private final ConcurrentHashMap<UUID, Transfer> transfers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastRequests = new ConcurrentHashMap<>();
    private boolean enabled;

    ReplayTransferBridge(ThatsABan plugin, ReportManager reports) {
        this.plugin = plugin;
        this.reports = reports;
    }

    void enable() {
        if (enabled) return;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        enabled = true;
        plugin.getLogger().info("Optional Fabric Replay Viewer channel registered.");
    }

    void shutdown() {
        if (!enabled) return;
        for (Transfer transfer : transfers.values()) {
            closeTransfer(transfer);
        }
        transfers.clear();
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        enabled = false;
    }

    boolean requestFromMenu(Player player, String reportId) {
        if (!enabled || !player.isOnline()) return false;
        if (!player.hasPermission("thatsaban.replay.view")
                && !player.hasPermission("thatsaban.reports")
                && !player.hasPermission("thatsaban.admin")) {
            player.sendMessage("You do not have permission to view report replays.");
            return true;
        }

        ReportManager.ReportEntry entry = reports.find(reportId);
        if (entry == null || entry.status() != ReportManager.Status.OPEN || entry.replayPath() == null) {
            player.sendMessage("No completed replay is available for this report.");
            return true;
        }

        player.sendPluginMessage(plugin, CHANNEL, encode(out -> {
            out.writeByte(PROTOCOL_VERSION);
            out.writeByte(MENU_REQUEST_REPLAY);
            out.writeUTF(reportId);
        }));
        player.sendMessage("Replay request sent to your That'sABan Replay Viewer.");
        return true;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || !enabled || message.length > 1024) return;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(message))) {
            int version = input.readUnsignedByte();
            int opcode = input.readUnsignedByte();
            if (version != PROTOCOL_VERSION) {
                sendError(player, "Unsupported replay transfer protocol version.");
                return;
            }
            if (opcode == REQUEST_REPLAY) {
                String reportId = input.readUTF();
                if (input.available() != 0 || !reportId.matches("[a-fA-F0-9]{8}")) {
                    sendError(player, "Invalid report ID.");
                    return;
                }
                requestReplay(player, reportId.toLowerCase(java.util.Locale.ROOT));
            } else if (opcode == TRANSFER_CANCEL) {
                long transferId = input.readLong();
                Transfer transfer = transfers.get(player.getUniqueId());
                if (transfer != null && transfer.id == transferId) {
                    closeTransfer(transfer);
                    transfers.remove(player.getUniqueId(), transfer);
                }
            } else {
                sendError(player, "Unknown replay transfer request.");
            }
        } catch (IOException | RuntimeException exception) {
            sendError(player, "Could not read the replay request.");
            plugin.getLogger().warning("Invalid replay viewer request from " + player.getName()
                    + ": " + exception.getClass().getSimpleName());
        }
    }

    private void requestReplay(Player player, String reportId) {
        if (!player.hasPermission("thatsaban.replay.view")
                && !player.hasPermission("thatsaban.reports")
                && !player.hasPermission("thatsaban.admin")) {
            sendError(player, "You do not have permission to view report replays.");
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastRequests.put(player.getUniqueId(), now);
        if (previous != null && now - previous < 3000L) {
            sendError(player, "Please wait before requesting another replay.");
            return;
        }

        ReportManager.ReportEntry entry = reports.find(reportId);
        if (entry == null || entry.status() != ReportManager.Status.OPEN || entry.replayPath() == null) {
            sendError(player, "No available replay exists for that report.");
            return;
        }

        Path file;
        try {
            Path reportDirectory = plugin.getDataFolder().toPath().resolve("reports").toRealPath();
            file = Path.of(entry.replayPath()).toRealPath();
            if (!file.startsWith(reportDirectory) || !Files.isRegularFile(file)) {
                sendError(player, "The report replay is unavailable.");
                return;
            }
            long size = Files.size(file);
            if (size <= 0 || size > MAX_REPLAY_BYTES) {
                sendError(player, "The replay exceeds the transfer size limit.");
                return;
            }
            startTransfer(player, file, size);
        } catch (IOException | RuntimeException exception) {
            sendError(player, "The report replay could not be opened.");
            plugin.getLogger().warning("Could not prepare replay transfer for report " + reportId
                    + ": " + exception.getClass().getSimpleName());
        }
    }

    private void startTransfer(Player player, Path file, long size) throws IOException {
        Transfer previous = transfers.remove(player.getUniqueId());
        if (previous != null) closeTransfer(previous);

        InputStream input = Files.newInputStream(file);
        long transferId = ThreadLocalRandom.current().nextLong();
        Transfer transfer = new Transfer(player.getUniqueId(), transferId, input, size);
        transfers.put(player.getUniqueId(), transfer);

        player.sendPluginMessage(plugin, CHANNEL, encode(out -> {
            out.writeByte(PROTOCOL_VERSION);
            out.writeByte(TRANSFER_START);
            out.writeLong(transferId);
            out.writeLong(size);
        }));

        transfer.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> sendNext(player, transfer), 1L, 1L);
    }

    private void sendNext(Player player, Transfer transfer) {
        if (!player.isOnline() || transfers.get(transfer.playerId) != transfer) {
            closeTransfer(transfer);
            transfers.remove(transfer.playerId, transfer);
            return;
        }

        try {
            byte[] chunk = transfer.input.readNBytes(CHUNK_SIZE);
            if (chunk.length == 0) {
                if (transfer.sentBytes != transfer.size) {
                    sendError(player, "The replay file changed during transfer.");
                } else {
                    player.sendPluginMessage(plugin, CHANNEL, encode(out -> {
                        out.writeByte(PROTOCOL_VERSION);
                        out.writeByte(TRANSFER_END);
                        out.writeLong(transfer.id);
                    }));
                }
                closeTransfer(transfer);
                transfers.remove(transfer.playerId, transfer);
                return;
            }

            transfer.sentBytes += chunk.length;
            if (transfer.sentBytes > transfer.size) {
                sendError(player, "The replay file changed during transfer.");
                closeTransfer(transfer);
                transfers.remove(transfer.playerId, transfer);
                return;
            }

            int chunkIndex = transfer.nextChunk++;
            player.sendPluginMessage(plugin, CHANNEL, encode(out -> {
                out.writeByte(PROTOCOL_VERSION);
                out.writeByte(TRANSFER_CHUNK);
                out.writeLong(transfer.id);
                out.writeInt(chunkIndex);
                out.writeInt(chunk.length);
                out.write(chunk);
            }));
        } catch (IOException | RuntimeException exception) {
            sendError(player, "Replay transfer failed.");
            closeTransfer(transfer);
            transfers.remove(transfer.playerId, transfer);
            plugin.getLogger().warning("Replay transfer failed for " + player.getName()
                    + ": " + exception.getClass().getSimpleName());
        }
    }

    private void sendError(Player player, String message) {
        if (!player.isOnline() || !enabled) return;
        try {
            player.sendPluginMessage(plugin, CHANNEL, encode(out -> {
                out.writeByte(PROTOCOL_VERSION);
                out.writeByte(TRANSFER_ERROR);
                out.writeUTF(message);
            }));
        } catch (RuntimeException exception) {
            plugin.getLogger().fine("Could not send replay viewer error to " + player.getName() + ".");
        }
    }

    private void closeTransfer(Transfer transfer) {
        if (transfer.task != null) transfer.task.cancel();
        try {
            transfer.input.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] encode(IoWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writer.write(output);
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode replay transfer message.", exception);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream output) throws IOException;
    }

    private static final class Transfer {
        private final UUID playerId;
        private final long id;
        private final InputStream input;
        private final long size;
        private long sentBytes;
        private int nextChunk;
        private BukkitTask task;

        private Transfer(UUID playerId, long id, InputStream input, long size) {
            this.playerId = playerId;
            this.id = id;
            this.input = input;
            this.size = size;
        }
    }
}
