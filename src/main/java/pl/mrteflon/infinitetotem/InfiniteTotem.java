package pl.mrteflon.infinitetotem;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class InfiniteTotem extends JavaPlugin implements Listener, CommandExecutor {

    private long cooldownMillis;
    private NamespacedKey infiniteKey;
    private NamespacedKey ownerKey;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> boostedPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        infiniteKey = new NamespacedKey(this, "infinite_totem");
        ownerKey = new NamespacedKey(this, "owner");

        saveDefaultConfig();
        cooldownMillis = getConfig().getLong("cooldown-seconds", 30) * 1000L;

        // Rejestracja zdarzeń i komendy
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("itotem") != null) {
            getCommand("itotem").setExecutor(this);
        } else {
            getLogger().severe("Nie udalo sie zarejestrowac komendy /itotem! Sprawdz plugin.yml.");
        }

        // Zadanie odświeżające Health Boost co sekundę (20 ticków)
        getServer().getScheduler().runTaskTimer(this, this::updateHealthBoosts, 20L, 20L);

        getLogger().info("InfiniteTotem wlaczony! Cooldown: " + (cooldownMillis / 1000) + "s.");
    }

    // ---- Pasywny Health Boost IV ----

private void updateHealthBoosts() {
    for (Player p : getServer().getOnlinePlayers()) {
        boolean holding = isHoldingOwnTotem(p);

        if (holding) {
            // Nakładanie efektu Health Boost IV (amplifier = 3) na 3 sekundy
            p.addPotionEffect(new PotionEffect(
                    PotionEffectType.HEALTH_BOOST,
                    60,
                    3,
                    false,
                    false,
                    true
            ), true);

            // Pierwsze wzięcie do ręki – bezpieczne dodanie zdrowia
            if (boostedPlayers.add(p.getUniqueId())) {
                double maxHealth = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
                double currentHealth = p.getHealth();
                
                // Ustawiamy zdrowie maksymalnie do nowego limitu (20 bazowe + 16 z Health Boost IV = 36)
                p.setHealth(Math.min(currentHealth + 16.0, maxHealth));
            }
        } else if (boostedPlayers.remove(p.getUniqueId())) {
            // Po odłożeniu z dłoni/offhandu usuwamy efekt
            p.removePotionEffect(PotionEffectType.HEALTH_BOOST);
        }
    }
}

private boolean isHoldingOwnTotem(Player player) {
    EntityEquipment equipment = player.getEquipment();
    if (equipment == null) return false;

    // Sprawdzenie głównej dłoni ORAZ drugiej dłoni (offhand)
    ItemStack mainHand = equipment.getItemInMainHand();
    ItemStack offHand = equipment.getItemInOffHand();

    return isOwnInfiniteTotem(mainHand, player) || isOwnInfiniteTotem(offHand, player);
}

private boolean isOwnInfiniteTotem(ItemStack item, Player player) {
    if (item == null || item.getType() != Material.TOTEM_OF_UNDYING) return false;
    if (!isInfinite(item)) return false;

    UUID owner = getOwner(item);
    return owner != null && owner.equals(player.getUniqueId());
}



    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boostedPlayers.remove(event.getPlayer().getUniqueId());
    }

    // ---- Mechanika Totemu ----

    @EventHandler
    public void onResurrect(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        EntityEquipment equipment = player.getEquipment();
        if (equipment == null) return;

        EquipmentSlot hand = event.getHand();
        ItemStack item = (hand == EquipmentSlot.HAND)
                ? equipment.getItemInMainHand()
                : equipment.getItemInOffHand();

        if (item == null || item.getType() != Material.TOTEM_OF_UNDYING || !isInfinite(item)) return;

        UUID owner = getOwner(item);

        if (owner == null || !owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.DARK_RED + "Ten totem nie należy do Ciebie!");
            return;
        }

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(owner, 0L);

        if (now < readyAt) {
            event.setCancelled(true);
            long remaining = (readyAt - now + 999) / 1000;
            player.sendMessage(ChatColor.RED + "Totem się odnawia! Pozostało: " + remaining + "s.");
            return;
        }

        cooldowns.put(owner, now + cooldownMillis);
        player.sendMessage(ChatColor.GOLD + "Totem Wiecznotrwały aktywowany!");
        player.setCooldown(Material.TOTEM_OF_UNDYING, (int) (cooldownMillis / 50));

        ItemStack restored = item.clone();
        restored.setAmount(1);

        getServer().getScheduler().runTask(this, () -> {
            if (hand == EquipmentSlot.HAND) {
                equipment.setItemInMainHand(restored);
            } else {
                equipment.setItemInOffHand(restored);
            }
        });
    }

    private boolean isInfinite(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(infiniteKey, PersistentDataType.BYTE);
    }

    private UUID getOwner(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---- Komendy ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("itotem")) return false;

        if (args.length == 0) {
            if (sender instanceof Player p) {
                if (!p.hasPermission("infinitetotem.give")) {
                    p.sendMessage(ChatColor.RED + "Brak uprawnień.");
                    return true;
                }
                p.getInventory().addItem(createInfiniteTotem(p));
                p.sendMessage(ChatColor.GREEN + "Otrzymano Totem Wiecznotrwały.");
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Użycie: /itotem give <gracz> | /itotem cooldown <sekundy> | /itotem sprawdz");
            }
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "cooldown":
                return handleCooldownCommand(sender, args);
            case "sprawdz":
            case "check":
                return handleCheckCommand(sender);
            case "give":
                return handleGiveCommand(sender, args);
            default:
                sender.sendMessage(ChatColor.YELLOW + "Użycie: /itotem give <gracz> | /itotem cooldown <sekundy> | /itotem sprawdz");
                return true;
        }
    }

    private boolean handleGiveCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infinitetotem.give")) {
            sender.sendMessage(ChatColor.RED + "Brak uprawnień.");
            return true;
        }

        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayer(args[1]);
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage(ChatColor.RED + "Określ gracza: /itotem give <gracz>");
            return true;
        }

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Nie znaleziono gracza.");
            return true;
        }

        target.getInventory().addItem(createInfiniteTotem(target));
        sender.sendMessage(ChatColor.GREEN + "Wydano totem dla " + target.getName() + ".");
        return true;
    }

    private boolean handleCheckCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Ta komenda jest przeznaczona tylko dla graczy.");
            return true;
        }

        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        long remainingMillis = readyAt - now;

        if (remainingMillis <= 0) {
            player.sendMessage(ChatColor.GREEN + "Totem jest gotowy do użycia.");
        } else {
            long remainingSeconds = (remainingMillis + 999) / 1000;
            player.sendMessage(ChatColor.YELLOW + "Totem odnowi się za " + remainingSeconds + "s.");
        }
        return true;
    }

    private boolean handleCooldownCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("infinitetotem.cooldown")) {
            sender.sendMessage(ChatColor.RED + "Brak uprawnień.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Aktualny cooldown: " + (cooldownMillis / 1000) + "s. Użycie: /itotem cooldown <sekundy>");
            return true;
        }

        int seconds;
        try {
            seconds = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Niepoprawna liczba: " + args[1]);
            return true;
        }

        if (seconds < 0 || seconds > 86400) {
            sender.sendMessage(ChatColor.RED + "Podaj wartość z zakresu 0 - 86400 sekund.");
            return true;
        }

        cooldownMillis = seconds * 1000L;
        getConfig().set("cooldown-seconds", seconds);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "Cooldown totemu ustawiony na " + seconds + "s.");
        return true;
    }

    private ItemStack createInfiniteTotem(Player owner) {
        ItemStack item = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Totem Wiecznotrwały");
            meta.setLore(List.of(
                    ChatColor.GRAY + "Przypisany do: " + ChatColor.WHITE + owner.getName(),
                    ChatColor.DARK_GRAY + "Bonus: Zdrowie IV (w dłoni)"
            ));
            meta.setCustomModelData(910001);
            meta.getPersistentDataContainer().set(infiniteKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId().toString());
            item.setItemMeta(meta);
        }
        return item;
    }
}
