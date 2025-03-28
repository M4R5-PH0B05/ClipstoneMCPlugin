package Clipstone.MC;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class ClipstoneMC extends JavaPlugin implements Listener, PluginMessageListener {
    private Connection dbConnection;
    private Map<UUID, Location> frozenPlayers = new HashMap<>();

    @Override
    public void onEnable() {
        // Save default config if not exists
        saveDefaultConfig();

        // Load MySQL JDBC Driver
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            getLogger().severe("MySQL JDBC Driver not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Connect to database
        connectToMySQL();

        // Register events
        getServer().getPluginManager().registerEvents(this, this);

        // Register plugin message channel
        getServer().getMessenger().registerIncomingPluginChannel(this, "clipstone:registration", this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "clipstone:registration");
    }

    // Method to connect to MySQL database
    private void connectToMySQL() {
        // Load database credentials from config
        String host = getConfig().getString("database.host", "localhost");
        int port = getConfig().getInt("database.port", 3306);
        String database = getConfig().getString("database.name", "minecraft_smp");
        String username = getConfig().getString("database.username");
        String password = getConfig().getString("database.password");

        String url = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                host, port, database);

        try {
            dbConnection = DriverManager.getConnection(url, username, password);

            // Ensure users table exists
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS users (" +
                            "   minecraft_uuid VARCHAR(36) PRIMARY KEY," +
                            "   discord_id BIGINT UNIQUE," +
                            "   current_username VARCHAR(255) NOT NULL," +
                            "   registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                            ") ENGINE=InnoDB"
            )) {
                stmt.execute();
            }

            getLogger().info("Successfully connected to MySQL database!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "MySQL connection failed", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("clipstone:registration")) {
            return;
        }

        try {
            ByteArrayInputStream stream = new ByteArrayInputStream(message);
            DataInputStream in = new DataInputStream(stream);

            // Read the Discord user ID and Minecraft UUID
            long discordId = in.readLong();
            String minecraftUuid = in.readUTF();

            // Validate the player's UUID matches the message
            if (player.getUniqueId().toString().equals(minecraftUuid)) {
                updatePlayerRegistration(player, discordId);
            } else {
                getLogger().warning("UUID mismatch during registration");
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error processing plugin message", e);
        }
    }

    private void updatePlayerRegistration(Player player, long discordId) {
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                    "UPDATE users SET discord_id = ? WHERE minecraft_uuid = ?"
            )) {
                stmt.setLong(1, discordId);
                stmt.setString(2, player.getUniqueId().toString());

                int rowsUpdated = stmt.executeUpdate();

                getServer().getScheduler().runTask(this, () -> {
                    if (rowsUpdated > 0) {
                        unfreezePlayer(player);
                        player.sendMessage("§aYour account has been successfully registered!");
                    } else {
                        player.sendMessage("§cCould not complete registration.");
                    }
                });
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to update registration", e);
                getServer().getScheduler().runTask(this, () ->
                        player.sendMessage("§cCould not complete registration.")
                );
            }
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("register")) {
            // Only players can use this command
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cOnly players can use this command.");
                return true;
            }

            Player player = (Player) sender;
            UUID playerUUID = player.getUniqueId();

            // Check if player is not registered in the database
            try {
                PreparedStatement stmt = dbConnection.prepareStatement(
                        "SELECT discord_id FROM users WHERE minecraft_uuid = ?"
                );
                stmt.setString(1, playerUUID.toString());
                ResultSet rs = stmt.executeQuery();

                // If registered and has a Discord link
                if (rs.next() && rs.getLong("discord_id") != 0) {
                    player.sendMessage("§aYou are now registered and can move freely!");

                    // Immediately unfreeze the player
                    unfreezePlayer(player);

                    return true;
                }

                // Create a clickable, hoverable UUID message using Spigot's chat API
                TextComponent message = new TextComponent("Your UUID: " + playerUUID.toString());
                message.setColor(net.md_5.bungee.api.ChatColor.GOLD);
                message.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, playerUUID.toString()));
                message.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ComponentBuilder("Click to copy UUID to clipboard!").color(net.md_5.bungee.api.ChatColor.GREEN).create()));

                // Send the message using Spigot's API
                player.spigot().sendMessage(message);

                TextComponent instructionMessage = new TextComponent("Please use this UUID to register on the Discord bot.");
                instructionMessage.setColor(net.md_5.bungee.api.ChatColor.GRAY);
                player.spigot().sendMessage(instructionMessage);

                // Ensure player remains frozen
                freezePlayer(player);

            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Registration check failed", e);
                player.sendMessage("§cAn error occurred while checking your registration status.");
            }

            return true;
        }
        return false;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Check if player is registered and linked
        try {
            PreparedStatement stmt = dbConnection.prepareStatement(
                    "SELECT discord_id FROM users WHERE minecraft_uuid = ?"
            );
            stmt.setString(1, playerUUID.toString());
            ResultSet rs = stmt.executeQuery();

            // If not registered or no Discord link, freeze player
            if (!rs.next() || rs.getLong("discord_id") == 0) {
                freezePlayer(player);
                player.sendMessage("§cYou must register using /register and link your account on Discord!");
            } else {
                // Explicitly unfreeze if linked
                unfreezePlayer(player);
            }
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Registration check failed", e);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Prevent movement if player is frozen
        if (frozenPlayers.containsKey(playerUUID)) {
            Location frozenLocation = frozenPlayers.get(playerUUID);
            event.getPlayer().teleport(frozenLocation);
            event.setCancelled(true);
        }
    }

    public void freezePlayer(Player player) {
        // Store player's current location
        Location currentLocation = player.getLocation();
        frozenPlayers.put(player.getUniqueId(), currentLocation);
    }

    public void unfreezePlayer(Player player) {
        frozenPlayers.remove(player.getUniqueId());
    }

    @Override
    public void onDisable() {
        // Close database connection
        if (dbConnection != null) {
            try {
                dbConnection.close();
                getLogger().info("Database connection closed.");
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Error closing database connection", e);
            }
        }
    }
}