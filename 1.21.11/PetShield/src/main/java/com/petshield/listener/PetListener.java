package com.petshield.listener;

import com.petshield.PetShieldPlugin;
import com.petshield.data.PetRecord;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.UUID;

public final class PetListener implements Listener {

    private final PetShieldPlugin plugin;

    public PetListener(PetShieldPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- đăng ký / tương tác

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // Server bắn cả InteractAt lẫn Interact: chỉ xử lý một loại để không báo trùng
        if (event instanceof PlayerInteractAtEntityEvent || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Entity target = event.getRightClicked();

        if (plugin.pets().isRegistering(player)) {
            if (target instanceof LivingEntity living && !(target instanceof Player)) {
                event.setCancelled(true);
                plugin.pets().tryRegister(player, living);
            }
            return;
        }

        // Người chơi đổi giáp/yên/... cho pet -> chụp lại NBT sau khi thay đổi đã áp dụng
        PetRecord rec = plugin.data().findByEntity(target.getUniqueId());
        if (rec != null && rec.alive) {
            UUID id = target.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Entity e = Bukkit.getEntity(id);
                if (e != null) {
                    plugin.pets().snapshot(rec, e);
                }
            }, 2L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.pets().forget(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- chết

    /** Chụp NBT ngay trước khi pet nhận sát thương chí mạng (mọi nguyên nhân: rơi, dung nham, /kill, void...). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        PetRecord rec = plugin.data().findByEntity(living.getUniqueId());
        if (rec == null || !rec.alive) {
            return;
        }
        if (living.getHealth() - event.getFinalDamage() <= 0) {
            plugin.pets().snapshot(rec, living);
        }
    }

    /** Chặn mọi item/exp rơi ra từ pet đã lưu (chống farm) và đánh dấu pet đã chết. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        PetRecord rec = plugin.data().findByEntity(event.getEntity().getUniqueId());
        if (rec == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.pets().markDead(rec, event.getEntity().getLocation());
    }

    /**
     * Dự phòng cho trường hợp pet bị xóa mà không có sự kiện chết (plugin khác remove, discard...).
     * Bỏ qua unload chunk / chết thường / người chơi thoát / biến đổi, và kiểm tra lại sau 2 tick
     * để không nhầm với việc pet đổi chiều không gian (entity mới cùng UUID).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        PetRecord rec = plugin.data().findByEntity(entity.getUniqueId());
        if (rec == null || !rec.alive) {
            return;
        }
        String cause = event.getCause().name();
        if (cause.equals("UNLOAD") || cause.equals("DEATH") || cause.equals("PLAYER_QUIT")
                || cause.equals("TRANSFORMATION")) {
            return;
        }

        UUID id = entity.getUniqueId();
        Location last = entity.getLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (rec.alive && plugin.data().findByEntity(id) == rec && Bukkit.getEntity(id) == null) {
                plugin.pets().markDead(rec, last);
            }
        }, 2L);
    }

    /** Chụp NBT khi chunk chứa pet bị unload. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity e : event.getEntities()) {
            PetRecord rec = plugin.data().findByEntity(e.getUniqueId());
            if (rec != null && rec.alive) {
                plugin.pets().snapshot(rec, e);
            }
        }
    }
}
