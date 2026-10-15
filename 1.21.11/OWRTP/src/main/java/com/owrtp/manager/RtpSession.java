package com.owrtp.manager;

import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

final class RtpSession {

    enum State { COUNTDOWN, SEARCHING, TELEPORTING }

    final UUID playerId;
    volatile State state = State.COUNTDOWN;
    int remaining;
    BukkitTask task;

    RtpSession(UUID playerId) {
        this.playerId = playerId;
    }
}
