package dev.local.numenpingwheel.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nx.pingwheel.common.network.PingLocationC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ping Wheel keeps no server-side copy of a marker: {@code ServerCore.onPingLocation}
 * validates the packet, rate-limits the sender and relays it to every client.
 * Injecting at the head lets us remember the coordinates (and announce them)
 * without touching Ping Wheel itself.
 *
 * <p>{@code remap = false} because this targets a mod class, not a Minecraft
 * class; {@code require = 0} plus {@code "required": false} in the config mean a
 * renamed or removed method in a future Ping Wheel only disables this hook.
 */
@Mixin(targets = "nx.pingwheel.common.core.ServerCore")
public class ServerCoreMixin {

    @Inject(method = "onPingLocation", at = @At("HEAD"), require = 0, remap = false)
    private static void numenpingwheel$onPingLocation(MinecraftServer server, ServerPlayer player,
                                                      PingLocationC2SPacket packet, CallbackInfo ci) {
        try {
            dev.local.numenpingwheel.PingHook.onPing(server, player, packet);
        } catch (Throwable ignored) {
            // never let a marker break the packet handler
        }
    }
}
