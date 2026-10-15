package com.petshield.data;

import java.util.UUID;

/** Dữ liệu một thú cưng đã lưu. Chỉ truy cập ở main thread. */
public final class PetRecord {

    public final UUID owner;
    public final int id;

    public String name;
    public String type;            // tên EntityType
    public UUID entityUuid;        // UUID thực thể hiện tại
    public boolean alive = true;

    public String deathWorld;
    public double deathX, deathY, deathZ;
    public long deathTime;

    public long registered;
    public long lastRevive;        // 0 = chưa từng hồi sinh

    public String snapshot;        // NBT thực thể (Base64)

    public PetRecord(UUID owner, int id) {
        this.owner = owner;
        this.id = id;
    }
}
