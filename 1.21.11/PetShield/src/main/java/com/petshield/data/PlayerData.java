package com.petshield.data;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerData {

    private final UUID owner;
    private int nextId = 1;
    private final Map<Integer, PetRecord> pets = new LinkedHashMap<>();

    public PlayerData(UUID owner) {
        this.owner = owner;
    }

    public UUID owner() { return owner; }
    public int nextId() { return nextId; }
    public void setNextId(int nextId) { this.nextId = nextId; }
    public int size() { return pets.size(); }
    public Collection<PetRecord> pets() { return Collections.unmodifiableCollection(pets.values()); }
    public PetRecord get(int id) { return pets.get(id); }

    public PetRecord create() {
        PetRecord rec = new PetRecord(owner, nextId++);
        pets.put(rec.id, rec);
        return rec;
    }

    public void add(PetRecord rec) {
        pets.put(rec.id, rec);
        if (rec.id >= nextId) {
            nextId = rec.id + 1;
        }
    }

    public void remove(int id) {
        pets.remove(id);
    }
}
