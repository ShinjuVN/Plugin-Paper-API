package vn.smboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Du lieu 1 boss: NBT (base64), ten hien thi, exp, thong bao, danh sach drop. */
public final class BossData {

    public String nbt;
    public String name;                 // co the dung ma mau &
    public int exp = -1;                // -1 = giu exp mac dinh cua mob
    public List<String> spawnMessage;   // null = dung mau mac dinh
    public List<Map<?, ?>> rawDrops = new ArrayList<>();
    public List<Drop> drops = new ArrayList<>();

    public record Drop(Material material, int min, int max, double chance,
                       Component name, List<Component> lore, Map<String, Integer> enchants) {

        public List<ItemStack> create(ThreadLocalRandom r) {
            List<ItemStack> out = new ArrayList<>();
            int total = min >= max ? min : r.nextInt(min, max + 1);
            int stack = Math.max(1, material.getMaxStackSize());
            while (total > 0) {
                int n = Math.min(total, stack);
                total -= n;
                out.add(buildOne(n));
            }
            return out;
        }

        private ItemStack buildOne(int amount) {
            ItemStack it = new ItemStack(material, amount);
            ItemMeta meta = it.getItemMeta();
            if (meta == null) return it;
            if (name != null) meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore != null && !lore.isEmpty()) {
                List<Component> l = new ArrayList<>();
                for (Component c : lore) l.add(c.decoration(TextDecoration.ITALIC, false));
                meta.lore(l);
            }
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(e.getKey().toLowerCase(Locale.ROOT)));
                if (ench == null) continue;
                if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(ench, e.getValue(), true);
                else meta.addEnchant(ench, e.getValue(), true);
            }
            it.setItemMeta(meta);
            return it;
        }
    }

    public List<ItemStack> rollDrops() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ItemStack> out = new ArrayList<>();
        for (Drop d : drops) {
            if (r.nextDouble() * 100.0 < d.chance()) out.addAll(d.create(r));
        }
        return out;
    }
}
