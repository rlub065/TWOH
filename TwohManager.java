package com.twoh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

@Mod.EventBusSubscriber(modid = TwohMod.MOD_ID)
public class TwohManager {

    private static final Map<UUID, PlayerState> STATE = new HashMap<>();

    // Reality Overwrite modes
    public static final String[] MODES = {
            "DAMAGE",           // massive damage + true damage feel
            "HEAL_TARGET",      // heal any living
            "KILL",             // полное стирание (discard + частицы)
            "TRANSFORM_ZOMBIE", // turn into zombie
            "TRANSFORM_SKELETON",
            "TRANSFORM_CREEPER",
            "TRANSFORM_ENDERMAN",
            "TRANSFORM_WITHER_SKELETON",
            "FORCE_ATTACK",     // force mob to attack nearest other mob
            "FRIENDLY",         // make mob stop attacking + remove AI hostility temporarily
            "BUFF_STRENGTH",
            "BUFF_SPEED",
            "BUFF_RESISTANCE",
            "CLEAR_EFFECTS",    // remove all effects from target
            "LEVITATE",
            "FREEZE",           // long slowness + mining fatigue
            "LIGHTNING_STRIKE"
    };

    public static class PlayerState {
        boolean standActive = false;
        long timeStopEnd = 0;
        int overwriteMode = 0;
        long lastMsg = 0;
        long barrageEnd = 0;
        Set<UUID> frozen = new HashSet<>();
    }

    private static PlayerState get(ServerPlayer p) {
        return STATE.computeIfAbsent(p.getUUID(), u -> new PlayerState());
    }

    public static void handleKey(ServerPlayer p, int key, int action, boolean shift) {
        if (action != 0) return; // only on press
        PlayerState s = get(p);
        long now = p.level().getGameTime();

        switch (key) {
            case 0 -> toggleStand(p, s);
            case 1 -> timeStop(p, s, now);
            case 2 -> realityOverwrite(p, s, now);
            case 3 -> barrage(p, s, now);
            case 4 -> knives(p, s);
            case 5 -> selfOverwrite(p, s, shift);
            case 6 -> lightning(p, s);
            case 7 -> cycleMode(p, s);
        }
    }

    private static void toggleStand(ServerPlayer p, PlayerState s) {
        s.standActive = !s.standActive;
        if (s.standActive) {
            msg(p, "§6§lTHE WORLD OVER HEAVEN §e— призван");
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.END_PORTAL_SPAWN, SoundSource.PLAYERS, 1.0f, 1.4f);
            // visual burst
            ServerLevel w = p.serverLevel();
            w.sendParticles(ParticleTypes.END_ROD, p.getX(), p.getY() + 1, p.getZ(), 40, 0.8, 1.2, 0.8, 0.15);
            w.sendParticles(ParticleTypes.FLASH, p.getX(), p.getY() + 1, p.getZ(), 3, 0, 0, 0, 0);
        } else {
            msg(p, "§7Стенд убран");
            p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 1.0f, 1.2f);
        }
    }

    private static void timeStop(ServerPlayer p, PlayerState s, long now) {
        if (!s.standActive) {
            msg(p, "§cСначала призови стенд (G)");
            return;
        }
        if (now < s.timeStopEnd) {
            // already active - cancel
            s.timeStopEnd = 0;
            unfreezeAll(p.serverLevel(), s);
            msg(p, "§eВремя возобновлено");
            return;
        }
        s.timeStopEnd = now + 200; // 10 seconds
        ServerLevel w = p.serverLevel();
        AABB box = p.getBoundingBox().inflate(48);
        // Freeze living entities
        for (LivingEntity e : w.getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e.isAlive())) {
            s.frozen.add(e.getUUID());
            e.setNoGravity(true);
            e.setDeltaMovement(Vec3.ZERO);
            if (e instanceof Mob mob) {
                mob.setNoAi(true);
                mob.setTarget(null);
            }
        }
        // Freeze projectiles too (arrows, fireballs, tridents, etc.)
        for (Entity e : w.getEntitiesOfClass(Entity.class, box, e -> e != p && !(e instanceof LivingEntity) && !(e instanceof Player))) {
            if (e instanceof net.minecraft.world.entity.projectile.Projectile || e instanceof net.minecraft.world.entity.item.ItemEntity) {
                s.frozen.add(e.getUUID());
                e.setDeltaMovement(Vec3.ZERO);
                e.setNoGravity(true);
            }
        }
        msg(p, "§6§lЗА МНОЙ, ВРЕМЯ!");
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 1.5f, 0.7f);
        w.sendParticles(ParticleTypes.REVERSE_PORTAL, p.getX(), p.getY() + 1, p.getZ(), 80, 2, 2, 2, 0.1);
    }

    private static void realityOverwrite(ServerPlayer p, PlayerState s, long now) {
        if (!s.standActive) {
            msg(p, "§cСначала призови стенд (G)");
            return;
        }
        LivingEntity target = findTarget(p, 6.0);
        if (target == null) {
            msg(p, "§cНет цели рядом");
            return;
        }

        String mode = MODES[s.overwriteMode];
        ServerLevel w = p.serverLevel();

        // visual + sound
        w.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 1.2f, 1.5f);
        w.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + 1, target.getZ(), 50, 0.6, 1.0, 0.6, 0.2);
        w.sendParticles(ParticleTypes.FLASH, target.getX(), target.getY() + 1, target.getZ(), 2, 0, 0, 0, 0);

        switch (mode) {
            case "DAMAGE" -> {
                target.hurt(p.damageSources().magic(), 40.0f);
                target.invulnerableTime = 0;
                msg(p, "§c§lПерезапись: УРОН");
            }
            case "HEAL_TARGET" -> {
                target.heal(40.0f);
                target.clearFire();
                msg(p, "§a§lПерезапись: ИСЦЕЛЕНИЕ ЦЕЛИ");
            }
            case "KILL" -> {
                // Полное стирание: частицы + discard
                double x = target.getX(), y = target.getY() + 1, z = target.getZ();
                w.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1, 0, 0, 0, 0);
                w.sendParticles(ParticleTypes.END_ROD, x, y, z, 60, 0.8, 1.0, 0.8, 0.25);
                w.sendParticles(ParticleTypes.SOUL, x, y, z, 30, 0.5, 0.8, 0.5, 0.1);
                w.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.PLAYERS, 1.2f, 1.4f);
                target.discard();
                msg(p, "§4§lПерезапись: СТИРАНИЕ ИЗ РЕАЛЬНОСТИ");
            }
            case "TRANSFORM_ZOMBIE" -> transform(p, target, EntityType.ZOMBIE);
            case "TRANSFORM_SKELETON" -> transform(p, target, EntityType.SKELETON);
            case "TRANSFORM_CREEPER" -> transform(p, target, EntityType.CREEPER);
            case "TRANSFORM_ENDERMAN" -> transform(p, target, EntityType.ENDERMAN);
            case "TRANSFORM_WITHER_SKELETON" -> transform(p, target, EntityType.WITHER_SKELETON);
            case "FORCE_ATTACK" -> {
                if (target instanceof Mob mob) {
                    LivingEntity nearest = findNearestOther(w, target, 24);
                    if (nearest != null) {
                        // Без ограничений: любой моб может атаковать любого (варден вардена, кролик свинью и т.д.)
                        mob.setTarget(nearest);
                        mob.setAggressive(true);
                        mob.setLastHurtByMob(nearest);
                        // Для пассивных мобов дополнительно форсим агрессию
                        if (!(mob instanceof Monster)) {
                            mob.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 400, 2));
                            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 400, 1));
                        }
                        // Чтобы цель тоже могла ответить
                        if (nearest instanceof Mob nm) {
                            nm.setTarget(mob);
                            nm.setAggressive(true);
                            nm.setLastHurtByMob(mob);
                        }
                        msg(p, "§e§lПерезапись: ПРИНУДИТЕЛЬНАЯ АГРЕССИЯ (без ограничений)");
                    } else {
                        msg(p, "§cНет другой цели рядом");
                    }
                } else {
                    msg(p, "§cЦель не моб");
                }
            }
            case "FRIENDLY" -> {
                if (target instanceof Mob mob) {
                    mob.setTarget(null);
                    mob.setAggressive(false);
                    mob.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 400, 10));
                    msg(p, "§a§lПерезапись: УСМИРЕНИЕ");
                }
            }
            case "BUFF_STRENGTH" -> {
                target.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 600, 4));
                msg(p, "§6§lПерезапись: СИЛА");
            }
            case "BUFF_SPEED" -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600, 3));
                msg(p, "§b§lПерезапись: СКОРОСТЬ");
            }
            case "BUFF_RESISTANCE" -> {
                target.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 600, 3));
                msg(p, "§9§lПерезапись: СОПРОТИВЛЕНИЕ");
            }
            case "CLEAR_EFFECTS" -> {
                target.removeAllEffects();
                msg(p, "§f§lПерезапись: ОЧИСТКА ЭФФЕКТОВ");
            }
            case "LEVITATE" -> {
                target.addEffect(new MobEffectInstance(MobEffects.LEVITATION, 100, 2));
                msg(p, "§d§lПерезапись: ЛЕВИТАЦИЯ");
            }
            case "FREEZE" -> {
                target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 6));
                target.addEffect(new MobEffectInstance(MobEffects.DIG_SLOWDOWN, 200, 4));
                msg(p, "§3§lПерезапись: ЗАМОРОЗКА");
            }
            case "LIGHTNING_STRIKE" -> {
                Entity bolt = EntityType.LIGHTNING_BOLT.create(w);
                if (bolt != null) {
                    bolt.moveTo(target.getX(), target.getY(), target.getZ());
                    w.addFreshEntity(bolt);
                }
                msg(p, "§e§lПерезапись: МОЛНИЯ");
            }
        }
    }

    private static void transform(ServerPlayer p, LivingEntity target, EntityType<? extends Mob> type) {
        if (target instanceof Player) {
            msg(p, "§cИгроков трансформировать нельзя (пока)");
            return;
        }
        ServerLevel w = p.serverLevel();
        Mob newMob = type.create(w);
        if (newMob == null) return;
        newMob.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), target.getXRot());
        newMob.setCustomName(target.getCustomName());
        newMob.setCustomNameVisible(target.isCustomNameVisible());
        target.discard();
        w.addFreshEntity(newMob);
        msg(p, "§5§lПерезапись: ТРАНСФОРМАЦИЯ → " + type.getDescription().getString());
        w.sendParticles(ParticleTypes.LARGE_SMOKE, newMob.getX(), newMob.getY() + 1, newMob.getZ(), 30, 0.5, 0.8, 0.5, 0.1);
    }

    private static void selfOverwrite(ServerPlayer p, PlayerState s, boolean shift) {
        if (!s.standActive) {
            msg(p, "§cСначала призови стенд (G)");
            return;
        }
        // shift = clear all negative, normal = strong self buffs + full heal
        if (shift) {
            p.removeAllEffects();
            msg(p, "§f§lСамоперезапись: все эффекты сняты");
        } else {
            p.heal(40.0f);
            p.clearFire();
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 1200, 3));
            p.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 1200, 2));
            p.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 1200, 2));
            p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 2));
            p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 1200, 4));
            p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1200, 0));
            p.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1200, 0));
            msg(p, "§a§lСамоперезапись: полный бафф + хил");
        }
        p.level().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 1.0f, 1.3f);
        p.serverLevel().sendParticles(ParticleTypes.TOTEM_OF_UNDYING, p.getX(), p.getY() + 1, p.getZ(), 40, 0.6, 1.0, 0.6, 0.15);
    }

    private static void barrage(ServerPlayer p, PlayerState s, long now) {
        if (!s.standActive) return;
        s.barrageEnd = now + 40; // 2 sec
        LivingEntity t = findTarget(p, 5.0);
        if (t != null) {
            for (int i = 0; i < 8; i++) {
                t.hurt(p.damageSources().playerAttack(p), 4.5f);
                t.invulnerableTime = 0;
            }
            p.serverLevel().sendParticles(ParticleTypes.CRIT, t.getX(), t.getY() + 1, t.getZ(), 25, 0.4, 0.6, 0.4, 0.1);
            p.level().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0f, 1.4f);
        }
        msg(p, "§6MUDA MUDA MUDA!");
    }

    private static void knives(ServerPlayer p, PlayerState s) {
        if (!s.standActive) return;
        ServerLevel w = p.serverLevel();
        Vec3 look = p.getLookAngle();
        for (int i = 0; i < 7; i++) {
            double ox = (p.getRandom().nextDouble() - 0.5) * 0.6;
            double oy = (p.getRandom().nextDouble() - 0.5) * 0.4;
            double oz = (p.getRandom().nextDouble() - 0.5) * 0.6;
            // simple damage ray
            for (int d = 1; d <= 20; d++) {
                Vec3 pos = p.getEyePosition().add(look.scale(d * 0.7)).add(ox, oy, oz);
                AABB box = new AABB(pos.x - 0.4, pos.y - 0.4, pos.z - 0.4, pos.x + 0.4, pos.y + 0.4, pos.z + 0.4);
                for (LivingEntity e : w.getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e.isAlive())) {
                    e.hurt(p.damageSources().magic(), 8.0f);
                    e.invulnerableTime = 0;
                    w.sendParticles(ParticleTypes.CRIT, e.getX(), e.getY() + 1, e.getZ(), 8, 0.2, 0.3, 0.2, 0.05);
                }
            }
        }
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0f, 1.6f);
        msg(p, "§eНожи!");
    }

    private static void lightning(ServerPlayer p, PlayerState s) {
        if (!s.standActive) return;
        ServerLevel w = p.serverLevel();
        AABB box = p.getBoundingBox().inflate(12);
        int count = 0;
        for (LivingEntity e : w.getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e.isAlive())) {
            Entity bolt = EntityType.LIGHTNING_BOLT.create(w);
            if (bolt != null) {
                bolt.moveTo(e.getX(), e.getY(), e.getZ());
                w.addFreshEntity(bolt);
                count++;
            }
            if (count >= 8) break;
        }
        // also self centered if no targets
        if (count == 0) {
            Entity bolt = EntityType.LIGHTNING_BOLT.create(w);
            if (bolt != null) {
                bolt.moveTo(p.getX(), p.getY(), p.getZ());
                w.addFreshEntity(bolt);
            }
        }
        msg(p, "§e§lНебесные молнии!");
        w.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 2.0f, 1.0f);
    }

    private static void cycleMode(ServerPlayer p, PlayerState s) {
        s.overwriteMode = (s.overwriteMode + 1) % MODES.length;
        msg(p, "§dРежим перезаписи: §f" + MODES[s.overwriteMode]);
    }

    // ========== helpers ==========

    private static LivingEntity findTarget(ServerPlayer p, double range) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getLookAngle();
        LivingEntity best = null;
        double bestDot = 0.6;
        AABB box = p.getBoundingBox().inflate(range);
        for (LivingEntity e : p.serverLevel().getEntitiesOfClass(LivingEntity.class, box, e -> e != p && e.isAlive())) {
            Vec3 to = e.getEyePosition().subtract(eye).normalize();
            double dot = look.dot(to);
            if (dot > bestDot && eye.distanceTo(e.getEyePosition()) <= range) {
                bestDot = dot;
                best = e;
            }
        }
        return best;
    }

    private static LivingEntity findNearestOther(ServerLevel w, LivingEntity from, double range) {
        LivingEntity best = null;
        double bestDist = range * range;
        for (LivingEntity e : w.getEntitiesOfClass(LivingEntity.class, from.getBoundingBox().inflate(range), e -> e != from && e.isAlive())) {
            double d = from.distanceToSqr(e);
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    private static void unfreezeAll(ServerLevel w, PlayerState s) {
        for (UUID id : s.frozen) {
            Entity e = w.getEntity(id);
            if (e != null) {
                e.setNoGravity(false);
                if (e instanceof LivingEntity le && le instanceof Mob mob) {
                    mob.setNoAi(false);
                }
            }
        }
        s.frozen.clear();
    }

    private static void msg(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(text), true);
    }

    // Tick for time stop maintenance + cleanup
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = event.getServer().overworld().getGameTime();
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            PlayerState s = STATE.get(p.getUUID());
            if (s == null) continue;
            if (s.timeStopEnd > 0 && now >= s.timeStopEnd) {
                s.timeStopEnd = 0;
                unfreezeAll(p.serverLevel(), s);
                msg(p, "§eВремя возобновлено");
            }
            // keep frozen entities + projectiles locked
            if (s.timeStopEnd > now) {
                for (UUID id : new HashSet<>(s.frozen)) {
                    Entity e = p.serverLevel().getEntity(id);
                    if (e == null) {
                        s.frozen.remove(id);
                        continue;
                    }
                    e.setDeltaMovement(Vec3.ZERO);
                    e.setNoGravity(true);
                    if (e instanceof LivingEntity le && le.isAlive()) {
                        if (le instanceof Mob mob) {
                            mob.setNoAi(true);
                            mob.setTarget(null);
                        }
                    } else if (!(e instanceof net.minecraft.world.entity.projectile.Projectile) && !(e instanceof net.minecraft.world.entity.item.ItemEntity)) {
                        s.frozen.remove(id);
                    }
                }
            }
        }
    }

    // Optional: reduce damage while stand is active (small tankiness)
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            PlayerState s = STATE.get(p.getUUID());
            if (s != null && s.standActive) {
                event.setAmount(event.getAmount() * 0.65f);
            }
        }
    }
}
