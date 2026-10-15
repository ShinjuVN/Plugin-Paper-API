package com.owrtp.manager;

import com.owrtp.OwRtpPlugin;
import com.owrtp.config.RtpConfig;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Tìm vị trí ngẫu nhiên trong vành khuyên [min, max] quanh (0, 0).
 * Mỗi lần thử load chunk bằng getChunkAtAsync (Paper) rồi mới kiểm tra block ở main thread,
 * các lần thử chạy tuần tự nên không gây tụt TPS.
 */
public final class LocationFinder {

    private final OwRtpPlugin plugin;

    public LocationFinder(OwRtpPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param valid    trả false khi phiên RTP đã bị hủy (dừng tìm)
     * @param callback nhận Location, hoặc null nếu không tìm được / bị hủy. Luôn gọi ở main thread.
     */
    public void find(World world, Location origin, BooleanSupplier valid, Consumer<Location> callback) {
        attempt(world, origin, 0, plugin.rtpConfig().maxAttempts(), valid, callback);
    }

    private void attempt(World world, Location origin, int tried, int max,
                         BooleanSupplier valid, Consumer<Location> callback) {
        if (!valid.getAsBoolean() || tried >= max) {
            callback.accept(null);
            return;
        }

        int[] xz = randomXZ(world);
        if (xz == null) {
            attempt(world, origin, tried + 1, max, valid, callback);
            return;
        }
        final int x = xz[0];
        final int z = xz[1];

        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, err) -> runSync(() -> {
            if (!valid.getAsBoolean()) {
                callback.accept(null);
                return;
            }
            Location result = (err == null && chunk != null) ? evaluate(world, x, z, origin) : null;
            if (result != null) {
                callback.accept(result);
            } else {
                attempt(world, origin, tried + 1, max, valid, callback);
            }
        }));
    }

    private void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    /** Chọn điểm đồng đều theo diện tích trong vành khuyên, kiểm tra khoảng cách bằng số nguyên. */
    private int[] randomXZ(World world) {
        RtpConfig cfg = plugin.rtpConfig();
        long min = cfg.minRadius();
        long max = cfg.maxRadius();
        long min2 = min * min;
        long max2 = max * max;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        for (int i = 0; i < 32; i++) {
            double r = Math.sqrt(rnd.nextDouble() * (max2 - min2) + min2);
            double theta = rnd.nextDouble() * Math.PI * 2;
            int x = (int) Math.round(r * Math.cos(theta));
            int z = (int) Math.round(r * Math.sin(theta));
            long d2 = (long) x * x + (long) z * z;
            if (d2 < min2 || d2 > max2) {
                continue;
            }
            if (!world.getWorldBorder().isInside(new Location(world, x + 0.5, 64, z + 0.5))) {
                continue;
            }
            return new int[]{x, z};
        }
        return null;
    }

    /** Kiểm tra cột (x, z); trả về Location đứng được hoặc null. Chạy ở main thread. */
    private Location evaluate(World world, int x, int z, Location origin) {
        RtpConfig cfg = plugin.rtpConfig();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        int groundY;
        if (world.getEnvironment() == World.Environment.NETHER) {
            groundY = findNetherGround(world, x, z, minY, maxY);
        } else {
            groundY = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }
        if (groundY <= minY || groundY + 2 >= maxY) {
            return null; // hư vô hoặc sát trần thế giới
        }

        if (!isStandable(world, x, groundY, z)) {
            return null;
        }
        if (cfg.safeLocation() && !isSafe(world, x, groundY, z, cfg.unsafeBlocks())) {
            return null;
        }

        return new Location(world, x + 0.5, groundY + 1, z + 0.5, origin.getYaw(), origin.getPitch());
    }

    /** Nether có trần bedrock: quét từ dưới trần xuống để tìm chỗ đứng. */
    private int findNetherGround(World world, int x, int z, int minY, int maxY) {
        int start = Math.min(maxY - 4, 120);
        for (int y = start; y > minY; y--) {
            if (isStandable(world, x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Luôn bắt buộc (cả khi safe-location=false): block rắn để đứng, chân/đầu không kẹt, không ngập chất lỏng. */
    private boolean isStandable(World world, int x, int y, int z) {
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        return ground.isSolid() && !ground.isLiquid()
                && feet.isPassable() && !feet.isLiquid()
                && head.isPassable() && !head.isLiquid();
    }

    /** Chế độ an toàn: không có block nguy hiểm ở nền/chân/đầu và không có dung nham/lửa xung quanh. */
    private boolean isSafe(World world, int x, int y, int z, Set<Material> unsafe) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    Material type = world.getBlockAt(x + dx, y + dy, z + dz).getType();
                    boolean center = dx == 0 && dz == 0;
                    if (unsafe.contains(type)) {
                        // block ở ngay cột đứng, hoặc dung nham/lửa kề bên đều bị loại
                        if (center || type == Material.LAVA || type == Material.FIRE
                                || type == Material.SOUL_FIRE || type == Material.MAGMA_BLOCK) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
}
