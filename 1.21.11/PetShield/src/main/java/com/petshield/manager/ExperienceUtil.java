package com.petshield.manager;

import org.bukkit.entity.Player;

/** Tính/đặt tổng điểm EXP theo công thức vanilla (không dùng giveExp nên không có âm thanh/mending). */
final class ExperienceUtil {

    private ExperienceUtil() {
    }

    static int expToNext(int level) {
        if (level <= 15) return 2 * level + 7;
        if (level <= 30) return 5 * level - 38;
        return 9 * level - 158;
    }

    static long pointsAtLevel(int level) {
        if (level <= 16) return (long) level * level + 6L * level;
        if (level <= 31) return Math.round(2.5 * level * level - 40.5 * level + 360);
        return Math.round(4.5 * level * level - 162.5 * level + 2220);
    }

    static long total(Player p) {
        int level = p.getLevel();
        return pointsAtLevel(level) + Math.round(p.getExp() * expToNext(level));
    }

    static void setTotal(Player p, long total) {
        long rem = Math.max(0, total);
        int level = 0;
        while (rem >= expToNext(level)) {
            rem -= expToNext(level);
            level++;
        }
        p.setLevel(level);
        p.setExp((float) rem / expToNext(level));
    }
}
