package com.twoh;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = TwohMod.MOD_ID, value = Dist.CLIENT)
public class TwohClient {

    public static final String CATEGORY = "key.categories.twoh";

    // 0 = summon, 1 = timestop, 2 = overwrite, 3 = barrage, 4 = knives, 5 = heal, 6 = lightning, 7 = cycle mode
    public static final KeyMapping[] KEYS = new KeyMapping[8];
    private static final boolean[] WAS_DOWN = new boolean[8];

    static {
        KEYS[0] = new KeyMapping("key.twoh.summon", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
        KEYS[1] = new KeyMapping("key.twoh.timestop", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
        KEYS[2] = new KeyMapping("key.twoh.overwrite", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, CATEGORY);
        KEYS[3] = new KeyMapping("key.twoh.barrage", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY);
        KEYS[4] = new KeyMapping("key.twoh.knives", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY);
        KEYS[5] = new KeyMapping("key.twoh.heal", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C, CATEGORY);
        KEYS[6] = new KeyMapping("key.twoh.lightning", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);
        KEYS[7] = new KeyMapping("key.twoh.cycle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    }

    @Mod.EventBusSubscriber(modid = TwohMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            for (KeyMapping k : KEYS) event.register(k);
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        boolean shift = mc.options.keyShift.isDown();
        for (int i = 0; i < KEYS.length; i++) {
            boolean down = KEYS[i].isDown();
            if (down && !WAS_DOWN[i]) {
                TwohMod.CHANNEL.sendToServer(new KeyPacket(i, 0, shift));
            } else if (!down && WAS_DOWN[i]) {
                TwohMod.CHANNEL.sendToServer(new KeyPacket(i, 1, shift));
            }
            WAS_DOWN[i] = down;
        }
    }
}
