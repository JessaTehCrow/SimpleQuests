package io.github.flemmli97.simplequests.quest;

import com.google.gson.JsonObject;
import io.github.flemmli97.simplequests.player.PlayerData;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Function;

public interface QuestEntry {

    boolean submit(ServerPlayer player);

    JsonObject serialize();

    ResourceLocation getId();

    MutableComponent translation(MinecraftServer server);

    default void onAccept(PlayerData data) {
    }

    default Function<PlayerData, Boolean> tickable() {
        return null;
    }
}
