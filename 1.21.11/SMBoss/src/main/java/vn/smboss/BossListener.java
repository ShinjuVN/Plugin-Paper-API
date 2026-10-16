package vn.smboss;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public final class BossListener implements Listener {

    private final BossManager manager;

    public BossListener(BossManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity entity = e.getEntity();
        if (!entity.getScoreboardTags().contains("smboss")) return;

        String id = null;
        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("smboss_")) { id = tag.substring("smboss_".length()); break; }
        }
        if (id == null) return;

        BossData data = manager.get(id);
        if (data == null) return;

        e.getDrops().addAll(data.rollDrops());   // trang bi tren nguoi van roi theo drop_chances
        if (data.exp >= 0) e.setDroppedExp(data.exp);
    }
}
