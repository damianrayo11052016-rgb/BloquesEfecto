package com.example.bloquesefecto;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class BloquesEfecto extends JavaPlugin {

    private record Entrada(PotionEffectType tipo, int nivel) {}

    private final Map<Material, Entrada> bloques = new EnumMap<>(Material.class);
    private BukkitTask task;

    private int radius;
    private int checkInterval;
    private int effectDuration;
    private boolean particles;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cargar();
        iniciar();
        getLogger().info("BloquesEfecto activado con " + bloques.size() + " bloques.");
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
    }

    private void cargar() {
        reloadConfig();
        radius = Math.max(1, Math.min(10, getConfig().getInt("radius", 5)));
        checkInterval = Math.max(1, getConfig().getInt("check-interval-ticks", 40));
        effectDuration = Math.max(checkInterval + 220, getConfig().getInt("effect-duration-ticks", 260));
        particles = getConfig().getBoolean("show-particles", false);

        bloques.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("blocks");
        if (sec == null) {
            return;
        }
        for (String nombre : sec.getKeys(false)) {
            Material material = Material.matchMaterial(nombre);
            if (material == null) {
                getLogger().warning("Bloque desconocido en config: " + nombre);
                continue;
            }
            String efecto = sec.getString(nombre + ".effect", "");
            NamespacedKey key = NamespacedKey.fromString(efecto.toLowerCase());
            PotionEffectType tipo = key == null ? null : Registry.EFFECT.get(key);
            if (tipo == null) {
                getLogger().warning("Efecto desconocido para " + nombre + ": " + efecto);
                continue;
            }
            int nivel = Math.max(0, sec.getInt(nombre + ".amplifier", 0));
            bloques.put(material, new Entrada(tipo, nivel));
        }
    }

    private void iniciar() {
        if (task != null) {
            task.cancel();
        }
        task = getServer().getScheduler().runTaskTimer(this, this::revisar, checkInterval, checkInterval);
    }

    private void revisar() {
        if (bloques.isEmpty()) {
            return;
        }
        for (Player player : getServer().getOnlinePlayers()) {
            if (player.isDead() || !player.hasPermission("bloquesefecto.use")) {
                continue;
            }
            Map<PotionEffectType, Integer> mejores = escanear(player);
            for (Map.Entry<PotionEffectType, Integer> e : mejores.entrySet()) {
                aplicar(player, e.getKey(), e.getValue());
            }
        }
    }

    /** Busca los bloques cercanos y devuelve el mejor nivel por cada tipo de efecto. */
    private Map<PotionEffectType, Integer> escanear(Player player) {
        Map<PotionEffectType, Integer> mejores = new HashMap<>();
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int px = loc.getBlockX();
        int py = loc.getBlockY();
        int pz = loc.getBlockZ();
        int minY = Math.max(world.getMinHeight(), py - radius);
        int maxY = Math.min(world.getMaxHeight() - 1, py + radius);
        long radioCuadrado = (long) radius * radius;

        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    long dx = x - px, dy = y - py, dz = z - pz;
                    if (dx * dx + dy * dy + dz * dz > radioCuadrado) {
                        continue;
                    }
                    Entrada entrada = bloques.get(world.getBlockAt(x, y, z).getType());
                    if (entrada != null) {
                        mejores.merge(entrada.tipo(), entrada.nivel(), Math::max);
                    }
                }
            }
        }
        return mejores;
    }

    private void aplicar(Player player, PotionEffectType tipo, int nivel) {
        PotionEffect actual = player.getPotionEffect(tipo);
        // No pisar un efecto mejor que ya tenga el jugador (pocion, baliza, etc.)
        if (actual != null && actual.getAmplifier() > nivel) {
            return;
        }
        player.addPotionEffect(new PotionEffect(tipo, effectDuration, nivel, true, particles, true));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("bloquesefecto")) {
            return false;
        }
        if (!sender.hasPermission("bloquesefecto.admin")) {
            sender.sendMessage(color(getConfig().getString("messages.no-permission", "&cSin permiso.")));
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            cargar();
            iniciar();
            sender.sendMessage(color(getConfig().getString("messages.reloaded", "&aRecargado.")));
            return true;
        }
        return false;
    }

    private Component color(String texto) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(texto);
    }
}
