package com.petshield.manager;

import com.petshield.PetShieldPlugin;
import com.petshield.config.Messages;
import com.petshield.config.PetConfig;
import com.petshield.data.DataManager;
import com.petshield.data.PetRecord;
import com.petshield.data.PlayerData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Logic đăng ký, snapshot NBT, đánh dấu chết và hồi sinh. Chỉ dùng ở main thread. */
public final class PetManager {

    private final PetShieldPlugin plugin;
    private final Map<UUID, Long> registering = new HashMap<>();
    private BukkitTask snapshotTask;

    public PetManager(PetShieldPlugin plugin) {
        this.plugin = plugin;
    }

    private DataManager data() { return plugin.data(); }
    private PetConfig cfg() { return plugin.petConfig(); }
    private Messages msg() { return plugin.messages(); }

    // ---------------------------------------------------------------- giới hạn

    public int limitFor(Player player) {
        int best = cfg().defaultLimit();
        for (Map.Entry<String, Integer> e : cfg().limitPermissions().entrySet()) {
            if (e.getValue() > best && player.hasPermission("petshield.limit." + e.getKey())) {
                best = e.getValue();
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- chế độ đăng ký

    public void startRegistering(Player player) {
        PlayerData pd = data().get(player.getUniqueId());
        int limit = limitFor(player);
        if (pd.size() >= limit) {
            msg().send(player, "limit-reached", "limit", String.valueOf(limit));
            return;
        }
        int timeout = cfg().registerTimeout();
        registering.put(player.getUniqueId(), System.currentTimeMillis() + timeout * 1000L);
        msg().send(player, "register-start", "timeout", String.valueOf(timeout));
    }

    public void cancelRegistering(Player player) {
        if (registering.remove(player.getUniqueId()) != null) {
            msg().send(player, "register-cancelled");
        }
    }

    public void forget(UUID playerId) {
        registering.remove(playerId);
    }

    public boolean isRegistering(Player player) {
        Long until = registering.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            registering.remove(player.getUniqueId());
            msg().send(player, "register-expired");
            return false;
        }
        return true;
    }

    /** Người chơi đang ở chế độ đăng ký và chuột phải vào một sinh vật. */
    public void tryRegister(Player player, LivingEntity entity) {
        EntityType type = entity.getType();
        if (!cfg().isAllowed(type)) {
            msg().send(player, "invalid-mob");
            return;
        }

        PetRecord existing = data().findByEntity(entity.getUniqueId());
        if (existing != null) {
            msg().send(player, existing.owner.equals(player.getUniqueId())
                    ? "already-registered-self" : "already-registered-other");
            return;
        }

        if (cfg().requireTamed() && entity instanceof Tameable tameable) {
            if (!tameable.isTamed()) {
                msg().send(player, "not-tamed");
                return;
            }
            AnimalTamer owner = tameable.getOwner();
            if (owner == null || !owner.getUniqueId().equals(player.getUniqueId())) {
                msg().send(player, "not-owner");
                return;
            }
        }

        PlayerData pd = data().get(player.getUniqueId());
        int limit = limitFor(player);
        if (pd.size() >= limit) {
            registering.remove(player.getUniqueId());
            msg().send(player, "limit-reached", "limit", String.valueOf(limit));
            return;
        }

        // Pet đã lưu không được biến mất khi ở xa / không được coi là "không lưu"
        entity.setPersistent(true);
        if (entity instanceof Mob mob) {
            mob.setRemoveWhenFarAway(false);
        }

        byte[] snap = serialize(entity);
        if (snap == null) {
            msg().send(player, "register-failed");
            return;
        }

        PetRecord rec = pd.create();
        rec.type = type.name();
        rec.entityUuid = entity.getUniqueId();
        rec.alive = true;
        rec.registered = System.currentTimeMillis();
        String custom = plainName(entity);
        rec.name = custom != null ? custom : cfg().typeName(rec.type) + " #" + rec.id;
        rec.snapshot = Base64.getEncoder().encodeToString(snap);

        data().index(rec);
        data().markDirty(player.getUniqueId());
        registering.remove(player.getUniqueId());

        msg().send(player, "register-success",
                "name", rec.name,
                "type", cfg().typeName(rec.type),
                "count", String.valueOf(pd.size()),
                "limit", String.valueOf(limit));
    }

    // ---------------------------------------------------------------- snapshot

    public void restartSnapshotTask() {
        if (snapshotTask != null) {
            snapshotTask.cancel();
            snapshotTask = null;
        }
        int seconds = cfg().snapshotInterval();
        if (seconds > 0) {
            long ticks = seconds * 20L;
            snapshotTask = Bukkit.getScheduler().runTaskTimer(plugin, this::snapshotAll, ticks, ticks);
        }
    }

    /** Chụp lại NBT của mọi pet đang sống và đang được load. */
    public void snapshotAll() {
        for (PetRecord rec : data().aliveRecords()) {
            Entity e = Bukkit.getEntity(rec.entityUuid);
            if (e != null && e.isValid()) {
                snapshot(rec, e);
            }
        }
    }

    /** Chụp NBT một pet (gọi trước khi chết, khi unload chunk, sau tương tác...). */
    public void snapshot(PetRecord rec, Entity entity) {
        if (!rec.alive || !entity.isValid()) {
            return;
        }
        byte[] snap = serialize(entity);
        if (snap == null) {
            return;
        }
        rec.snapshot = Base64.getEncoder().encodeToString(snap);
        String custom = plainName(entity);
        if (custom != null) {
            rec.name = custom;
        }
        data().markDirty(rec.owner);
    }

    @SuppressWarnings("deprecation")
    private byte[] serialize(Entity entity) {
        try {
            return Bukkit.getUnsafe().serializeEntity(entity);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.FINE, "Không serialize được entity " + entity.getType(), ex);
            return null;
        }
    }

    private String plainName(Entity entity) {
        Component custom = entity.customName();
        if (custom == null) {
            return null;
        }
        String s = PlainTextComponentSerializer.plainText().serialize(custom).trim();
        return s.isEmpty() ? null : s;
    }

    // ---------------------------------------------------------------- chết

    public void markDead(PetRecord rec, Location loc) {
        if (!rec.alive) {
            return;
        }
        rec.alive = false;
        rec.deathWorld = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        rec.deathX = loc.getX();
        rec.deathY = loc.getY();
        rec.deathZ = loc.getZ();
        rec.deathTime = System.currentTimeMillis();
        data().markDirty(rec.owner);

        Player owner = Bukkit.getPlayer(rec.owner);
        if (owner != null) {
            msg().send(owner, "pet-died",
                    "name", rec.name,
                    "world", rec.deathWorld,
                    "x", String.valueOf(loc.getBlockX()),
                    "y", String.valueOf(loc.getBlockY()),
                    "z", String.valueOf(loc.getBlockZ()));
        }
    }

    // ---------------------------------------------------------------- hồi sinh

    public long cooldownRemainingSeconds(PetRecord rec) {
        if (rec.lastRevive <= 0) {
            return 0;
        }
        long left = rec.lastRevive + cfg().reviveCooldownMillis() - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    public enum ReviveResult { SUCCESS, NO_FUNDS, FAILED }

    /** Hồi sinh lần lượt các pet; dừng khi không đủ kinh nghiệm, các lỗi khác chỉ bỏ qua pet đó. */
    public void reviveAll(Player player, List<PetRecord> targets) {
        for (PetRecord rec : new ArrayList<>(targets)) {
            if (revive(player, rec) == ReviveResult.NO_FUNDS) {
                break;
            }
        }
    }

    public ReviveResult revive(Player player, PetRecord rec) {
        if (rec.alive) {
            msg().send(player, "revive-alive", "name", rec.name);
            return ReviveResult.FAILED;
        }

        boolean bypassCooldown = player.hasPermission("petshield.bypass.cooldown");
        long left = cooldownRemainingSeconds(rec);
        if (!bypassCooldown && left > 0) {
            msg().send(player, "revive-cooldown", "name", rec.name, "time", Messages.formatTime(left));
            return ReviveResult.FAILED;
        }

        boolean free = !cfg().costEnabled() || player.hasPermission("petshield.bypass.cost");
        if (!free && !canAfford(player)) {
            msg().send(player, "revive-no-funds", "name", rec.name, "cost", costText());
            return ReviveResult.NO_FUNDS;
        }

        if (rec.snapshot == null) {
            msg().send(player, "revive-failed", "name", rec.name);
            return ReviveResult.FAILED;
        }

        Entity spawned = spawnFromSnapshot(rec, player.getLocation());
        if (spawned == null) {
            msg().send(player, "revive-failed", "name", rec.name);
            return ReviveResult.FAILED;
        }

        if (!free) {
            charge(player);
        }

        data().unindex(rec);
        rec.entityUuid = spawned.getUniqueId();
        rec.alive = true;
        rec.lastRevive = System.currentTimeMillis();
        data().index(rec);
        snapshot(rec, spawned);
        data().markDirty(rec.owner);

        msg().send(player, "revive-success", "name", rec.name, "cost", free ? msg().plain("cost-free") : costText());
        return ReviveResult.SUCCESS;
    }

    @SuppressWarnings("deprecation")
    private Entity spawnFromSnapshot(PetRecord rec, Location at) {
        try {
            byte[] data = Base64.getDecoder().decode(rec.snapshot);
            Entity entity = Bukkit.getUnsafe().deserializeEntity(data, at.getWorld(), false);
            entity.spawnAt(at, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (!entity.isValid()) {
                return null;
            }

            // Dọn các trạng thái gây chết lại ngay (lửa, ngạt nước, độc...) nhưng giữ nguyên toàn bộ NBT còn lại
            entity.setFireTicks(0);
            entity.setFallDistance(0);
            entity.setVelocity(new Vector(0, 0, 0));
            if (entity instanceof LivingEntity living) {
                living.setRemainingAir(living.getMaximumAir());
                living.removePotionEffect(PotionEffectType.POISON);
                living.removePotionEffect(PotionEffectType.WITHER);
                if (cfg().reviveFullHealth()) {
                    living.setHealth(living.getMaxHealth());
                } else if (living.getHealth() <= 0) {
                    living.setHealth(Math.min(1.0, living.getMaxHealth()));
                }
            }
            return entity;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Hồi sinh pet thất bại (" + rec.type + ")", ex);
            return null;
        }
    }

    // ---------------------------------------------------------------- chi phí

    private boolean canAfford(Player player) {
        int amount = cfg().costAmount();
        return cfg().costLevels() ? player.getLevel() >= amount : ExperienceUtil.total(player) >= amount;
    }

    private void charge(Player player) {
        int amount = cfg().costAmount();
        if (cfg().costLevels()) {
            player.setLevel(player.getLevel() - amount);
        } else {
            ExperienceUtil.setTotal(player, ExperienceUtil.total(player) - amount);
        }
    }

    private String costText() {
        String key = cfg().costLevels() ? "cost-levels" : "cost-points";
        return msg().plain(key, "amount", String.valueOf(cfg().costAmount()));
    }

    // ---------------------------------------------------------------- tìm pet theo tên/id

    public PetRecord find(UUID owner, String input) {
        PlayerData pd = data().get(owner);
        String trimmed = input.trim();
        if (trimmed.matches("\\d+")) {
            try {
                PetRecord byId = pd.get(Integer.parseInt(trimmed));
                if (byId != null) {
                    return byId;
                }
            } catch (NumberFormatException ignored) {
                // số quá lớn -> thử theo tên
            }
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (PetRecord r : pd.pets()) {
            if (r.name != null && r.name.toLowerCase(Locale.ROOT).equals(lower)) {
                return r;
            }
        }
        return null;
    }
}
