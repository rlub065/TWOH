package com.twoh;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class KeyPacket {
    private final int key;
    private final int action; // 0 = press, 1 = release
    private final boolean shift;

    public KeyPacket(int key, int action, boolean shift) {
        this.key = key;
        this.action = action;
        this.shift = shift;
    }

    public static void encode(KeyPacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.key);
        buf.writeByte(msg.action);
        buf.writeBoolean(msg.shift);
    }

    public static KeyPacket decode(FriendlyByteBuf buf) {
        return new KeyPacket(buf.readByte(), buf.readByte(), buf.readBoolean());
    }

    public static void handle(KeyPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer p = c.getSender();
            if (p != null) TwohManager.handleKey(p, msg.key, msg.action, msg.shift);
        });
        c.setPacketHandled(true);
    }
}
