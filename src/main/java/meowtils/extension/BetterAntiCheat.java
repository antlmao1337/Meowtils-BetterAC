package meowtils.extension;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityHeadLook;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import wtf.tatp.meowtils.Meowtils;
import wtf.tatp.meowtils.config.Config;
import wtf.tatp.meowtils.event.ClientTickEvent;
import wtf.tatp.meowtils.event.ReceivePacketEvent;
import wtf.tatp.meowtils.event.api.EventTarget;
import wtf.tatp.meowtils.extension.Extension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

public class BetterAntiCheat extends Extension {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final Pattern BOT_NAME = Pattern.compile(
            "^(NPC|BOT|CIT-|CITIZEN|Shop|Guard|Villager|\\[NPC\\]).*",
            Pattern.CASE_INSENSITIVE
    );

    /** How close a post-snap rotation must land on the pre-snap rotation to count as a silent restore. */
    private static final float RESTORE_YAW = 16.0F;
    private static final float RESTORE_PITCH = 12.0F;
    private static final int AIM_SNAP_WINDOW = 80;
    private static final int SNAP_BACK_WINDOW = 100;
    private static final int OFF_AIM_COUNT = 3;
    private static final int OFF_AIM_WINDOW = 70;
    private static final long SWING_MATCH_NS = 120_000_000L;
    private static final long RESTORE_WINDOW_NS = 250_000_000L;
    private static final long MERGE_NS = 5_000_000L;
    private static final int CHAT_COOLDOWN = 30;

    @Config public boolean enabled = true;
    @Config public int key = 0;

    @Config public boolean debug = false;
    @Config public boolean flagSound = true;
    @Config public boolean ignoreBots = true;
    @Config public int vlThreshold = 7;

    // Combat
    @Config public boolean checkKillaura = true;
    @Config public boolean checkKillauraSnap = true;
    @Config public boolean checkKillauraConsistency = true;
    @Config public boolean checkMultiAura = true;
    @Config public boolean checkAutoBlock = true;
    @Config public boolean checkNoSlow = true;
    @Config public boolean checkAimSnap = true;

    // Scaffold (split toggles — only Telly on by default)
    @Config public boolean checkScaffoldPlaceRate = false;
    @Config public boolean checkScaffoldSnap = false;
    @Config public boolean checkScaffoldTelly = true;
    @Config public boolean checkLegitScaffold = false;

    @Config public double maxAngle = 90.0;
    @Config public int multiAuraTicks = 3;
    @Config public double snapThreshold = 45.0;
    /** Hitbox angle that still counts as "looking at" a player. Interpolation and the 1.4° packet grid need slack. */
    @Config public double onTargetAngle = 36.0;

    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<UUID, PlayerData>();
    private int tickCounter = 0;
    private int localLastHurtTime = 0;
    private int localLastHurtTick = -999;

    public BetterAntiCheat() {
        super("BetterAntiCheat", "Improved client-side AC");

        check("Debug Mode", "debug");
        check("Flag Sound", "flagSound");
        check("Ignore Bots", "ignoreBots");
        slider("VL Threshold", 4, 20, 1, null, "vlThreshold", int.class);

        expand("Checks", e -> {
            e.check("Killaura Angle", "checkKillaura");
            e.check("Killaura Snap-Hit", "checkKillauraSnap");
            e.check("Killaura Consistency", "checkKillauraConsistency");
            e.check("MultiAura", "checkMultiAura");
            e.check("AutoBlock", "checkAutoBlock");
            e.check("NoSlow", "checkNoSlow");
            e.check("Aim Snap", "checkAimSnap");
            e.check("Scaffold Place Rate", "checkScaffoldPlaceRate");
            e.check("Scaffold Snap", "checkScaffoldSnap");
            e.check("Scaffold Telly", "checkScaffoldTelly");
            e.check("Legit Scaffold", "checkLegitScaffold");
        });

        expand("Thresholds", e -> {
            e.slider("Max Angle", 50, 130, 1, "°", "maxAngle", double.class);
            e.slider("MultiAura Ticks", 2, 8, 1, null, "multiAuraTicks", int.class);
            e.slider("Snap Threshold", 30, 90, 1, "°", "snapThreshold", double.class);
            e.slider("On-Target Angle", 18, 48, 1, "°", "onTargetAngle", double.class);
        });
    }

    @Override
    public void onEnable() {
        dataMap.clear();
        localLastHurtTime = 0;
        localLastHurtTick = -999;
        if (debug) Meowtils.addMessage("§a[BetterAC] Enabled");
    }

    @Override
    public void onDisable() {
        dataMap.clear();
        if (debug) Meowtils.addMessage("§c[BetterAC] Disabled");
    }

    private boolean isBot(EntityPlayer player) {
        if (!ignoreBots || player == null || player == mc.thePlayer) return false;
        if (!BOT_NAME.matcher(player.getName()).matches()) return false;

        double motion = Math.hypot(player.posX - player.lastTickPosX, player.posZ - player.lastTickPosZ);
        if (motion > 0.08) return false;

        PlayerData existing = dataMap.get(player.getUniqueID());
        if (existing != null && (existing.movingForward || existing.placesLastSecond() >= 1)) {
            return false;
        }
        return true;
    }

    private double targetAngle() {
        return onTargetAngle < 10.0 ? 32.0 : onTargetAngle;
    }

    @EventTarget
    public void onTick(ClientTickEvent event) {
        if (event.getPhase() != ClientTickEvent.Phase.POST) return;
        if (!enabled || mc.thePlayer == null || mc.theWorld == null) return;

        tickCounter++;

        if (tickCounter % 40 == 0) {
            dataMap.entrySet().removeIf(e -> {
                EntityPlayer p = mc.theWorld.getPlayerEntityByUUID(e.getKey());
                return p == null || p.isDead || mc.thePlayer.getDistanceToEntity(p) > 48;
            });
        }

        List<EntityPlayer> tracked = new ArrayList<EntityPlayer>();
        for (EntityPlayer player : new ArrayList<EntityPlayer>(mc.theWorld.playerEntities)) {
            if (player == null || player == mc.thePlayer || player.isDead) continue;
            if (mc.thePlayer.getDistanceToEntity(player) > 48) continue;

            PlayerData data = dataMap.computeIfAbsent(player.getUniqueID(), uuid -> new PlayerData(player));
            prepareFrame(data, player);
            if (isBot(player)) {
                data.endFrame();
                continue;
            }
            data.tick(player);
            data.updateHurt();
            tracked.add(player);
        }
        updateLocalHurt();

        for (int i = 0; i < tracked.size(); i++) {
            EntityPlayer player = tracked.get(i);
            if (mc.thePlayer.getDistanceToEntity(player) > 32) continue;
            PlayerData data = dataMap.get(player.getUniqueID());
            if (data == null) continue;

            resolveCombat(data);
            if (checkNoSlow) checkNoSlow(data);
            if (checkScaffoldPlaceRate || checkScaffoldSnap || checkScaffoldTelly) {
                checkScaffold(data);
            }
            if (checkLegitScaffold) checkLegitScaffold(data);

            if (data.vl > 0 && tickCounter % 20 == 0) {
                data.vl = Math.max(0, data.vl - 1);
            }
        }

        for (int i = 0; i < tracked.size(); i++) {
            PlayerData data = dataMap.get(tracked.get(i).getUniqueID());
            if (data != null) data.endFrame();
        }
    }

    @EventTarget
    public void onPacket(ReceivePacketEvent event) {
        if (!enabled || mc.thePlayer == null || mc.theWorld == null) return;

        Packet<?> packet = event.getPacket();

        if (packet instanceof S23PacketBlockChange) {
            S23PacketBlockChange p = (S23PacketBlockChange) packet;
            handleBlockUpdate(p.getBlockPosition(), p.getBlockState());
        } else if (packet instanceof S22PacketMultiBlockChange) {
            S22PacketMultiBlockChange p = (S22PacketMultiBlockChange) packet;
            S22PacketMultiBlockChange.BlockUpdateData[] changes = p.getChangedBlocks();
            if (changes != null) {
                for (int i = 0; i < changes.length; i++) {
                    S22PacketMultiBlockChange.BlockUpdateData c = changes[i];
                    if (c != null) handleBlockUpdate(c.getPos(), c.getBlockState());
                }
            }
        } else if (packet instanceof S19PacketEntityHeadLook) {
            S19PacketEntityHeadLook p = (S19PacketEntityHeadLook) packet;
            Entity e = p.getEntity(mc.theWorld);
            offerRotation(e, new RotSample((p.getYaw() * 360.0F) / 256.0F, 0.0F, true, false, false, System.nanoTime()));
        } else if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            boolean look = packet instanceof S14PacketEntity.S16PacketEntityLook
                    || packet instanceof S14PacketEntity.S17PacketEntityLookMove;
            if (!look) return;
            Entity e = p.getEntity(mc.theWorld);
            offerRotation(e, new RotSample(
                    p.func_149066_f() * 360.0F / 256.0F,
                    p.func_149063_g() * 360.0F / 256.0F,
                    true, true, false, System.nanoTime()));
        } else if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            Entity e = mc.theWorld.getEntityByID(p.getEntityId());
            offerRotation(e, new RotSample(
                    p.getYaw() * 360.0F / 256.0F,
                    p.getPitch() * 360.0F / 256.0F,
                    true, true, true, System.nanoTime()));
        } else if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation p = (S0BPacketAnimation) packet;
            if (p.getAnimationType() != 0) return;
            Entity e = mc.theWorld.getEntityByID(p.getEntityID());
            if (!(e instanceof EntityPlayer) || e == mc.thePlayer) return;
            if (mc.thePlayer.getDistanceToEntity(e) > 64.0F) return;
            PlayerData data = dataMap.computeIfAbsent(e.getUniqueID(), uuid -> new PlayerData((EntityPlayer) e));
            data.offerSwing(System.nanoTime());
        }
    }

    /**
     * Packet events run on the Netty thread before vanilla applies them.
     * Only enqueue here. Judgement happens on the client tick, after the world has the new positions.
     */
    private void offerRotation(Entity e, RotSample sample) {
        if (!(e instanceof EntityPlayer) || e == mc.thePlayer || mc.thePlayer == null) return;
        if (mc.thePlayer.getDistanceToEntity(e) > 64.0F) return;
        PlayerData data = dataMap.computeIfAbsent(e.getUniqueID(), uuid -> new PlayerData((EntityPlayer) e));
        data.offerRotation(sample);
    }

    private void updateLocalHurt() {
        if (mc.thePlayer == null) return;
        int ht = mc.thePlayer.hurtTime;
        if (ht > localLastHurtTime && ht > 0) localLastHurtTick = tickCounter;
        localLastHurtTime = ht;
    }

    private void handleBlockUpdate(BlockPos pos, IBlockState newState) {
        if (pos == null || newState == null || mc.theWorld == null || mc.thePlayer == null) return;
        if (newState.getBlock().getMaterial().isReplaceable()) return;

        double dx = (pos.getX() + 0.5) - mc.thePlayer.posX;
        double dz = (pos.getZ() + 0.5) - mc.thePlayer.posZ;
        if ((dx * dx + dz * dz) > 48 * 48) return;

        IBlockState oldState = mc.theWorld.getBlockState(pos);
        if (oldState != null && !oldState.getBlock().getMaterial().isReplaceable()) return;

        EntityPlayer best = null;
        double bestDistSq = 6.5 * 6.5;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead || isBot(player)) continue;
            if (pos.getY() < player.posY - 2.2 || pos.getY() > player.posY + 1.6) continue;

            double pdx = (pos.getX() + 0.5) - player.posX;
            double pdz = (pos.getZ() + 0.5) - player.posZ;
            double distSq = pdx * pdx + pdz * pdz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = player;
            }
        }

        if (best == null) return;

        final EntityPlayer placer = best;
        PlayerData data = dataMap.computeIfAbsent(placer.getUniqueID(), uuid -> new PlayerData(placer));
        data.onPlacedBlock(pos, tickCounter);
    }

    // ===================== COMBAT =====================

    private void prepareFrame(PlayerData data, EntityPlayer player) {
        data.player = player;
        data.frameRaw.clear();
        data.frameSwings.clear();

        RotSample sample;
        while ((sample = data.rotations.poll()) != null) {
            data.frameRaw.add(sample);
        }
        Long swing;
        while ((swing = data.swingTimes.poll()) != null) {
            data.frameSwings.add(swing);
        }
        data.swingsThisTick = data.frameSwings.size();

        boolean teleport = false;
        for (int i = 0; i < data.frameRaw.size(); i++) {
            if (data.frameRaw.get(i).teleport) {
                teleport = true;
                break;
            }
        }

        boolean burst = data.forceBurst || data.frameRaw.size() >= 8;
        data.forceBurst = false;
        long now = System.nanoTime();
        if (burst) {
            data.graceUntilNanos = now + 200_000_000L;
        }
        if (teleport) {
            data.lastPosX = player.posX;
            data.lastPosY = player.posY;
            data.lastPosZ = player.posZ;
            data.graceUntilNanos = now + 250_000_000L;
            data.clearPending();
        }

        data.frameGrace = teleport || burst || now < data.graceUntilNanos;
        data.readySteps.clear();
        if (data.frameGrace) {
            applyLastSample(data);
            data.clearPending();
            data.prevStep = 0.0F;
            data.prevStep2 = 0.0F;
            return;
        }

        float yaw = data.aimYaw;
        float pitch = data.aimPitch;
        float prev = data.prevStep;
        float prev2 = data.prevStep2;
        int i = 0;
        while (i < data.frameRaw.size()) {
            RotSample s = data.frameRaw.get(i);
            float ny = s.yawSet ? s.yaw : yaw;
            float np = s.pitchSet ? s.pitch : pitch;
            long nanos = s.nanos;
            int j = i + 1;
            while (j < data.frameRaw.size()) {
                RotSample n = data.frameRaw.get(j);
                if (n.nanos - nanos > MERGE_NS) break;
                if (s.yawSet && n.yawSet && Math.abs(wrap(n.yaw - ny)) > 1.0F) break;
                if (n.yawSet) ny = n.yaw;
                if (n.pitchSet) np = n.pitch;
                j++;
            }

            float yawDelta = wrap(ny - yaw);
            float pitchDelta = wrap(np - pitch);
            float step = (float) Math.hypot(yawDelta, pitchDelta);
            if (step > 0.05F) {
                Step built = new Step(nanos, yaw, pitch, ny, np, yawDelta, pitchDelta, step, prev, prev2);
                data.readySteps.add(built);
                data.lastHeadYaw = data.headYaw;
                data.lastPitch = data.pitch;
                data.headYaw = ny;
                data.pitch = np;
                data.avgYawChange = data.avgYawChange * 0.75F + Math.abs(yawDelta) * 0.25F;
                data.avgPitchChange = data.avgPitchChange * 0.7F + Math.abs(pitchDelta) * 0.3F;
                data.noteRotationDelta(Math.abs(yawDelta), Math.abs(pitchDelta));
                prev2 = prev;
                prev = step;
                yaw = ny;
                pitch = np;
            }
            i = j;
        }

        if (data.readySteps.size() >= 5) {
            data.frameGrace = true;
            data.graceUntilNanos = now + 200_000_000L;
            data.clearPending();
            data.readySteps.clear();
            data.prevStep = 0.0F;
            data.prevStep2 = 0.0F;
        } else {
            data.prevStep2 = prev2;
            data.prevStep = prev;
        }
        data.aimYaw = yaw;
        data.aimPitch = pitch;
        data.headYaw = yaw;
        data.pitch = pitch;
    }

    private void applyLastSample(PlayerData data) {
        for (int i = data.frameRaw.size() - 1; i >= 0; i--) {
            RotSample s = data.frameRaw.get(i);
            if (s.yawSet) data.aimYaw = s.yaw;
            if (s.pitchSet) data.aimPitch = s.pitch;
            if (s.yawSet || s.pitchSet) break;
        }
        data.headYaw = data.aimYaw;
        data.pitch = data.aimPitch;
    }

    private void resolveCombat(PlayerData data) {
        if (data.frameGrace) {
            if (checkAutoBlock) checkAutoBlock(data);
            if (checkKillaura) checkInvalidPitch(data);
            return;
        }

        if (data.inScaffoldContext()) {
            data.clearPending();
            for (int i = 0; i < data.swingsThisTick; i++) {
                data.notePlaceSwing(tickCounter);
            }
            return;
        }

        if (checkAutoBlock) checkAutoBlock(data);

        List<Step> steps = data.readySteps;
        int n = steps.size();
        AimTarget[] aims = new AimTarget[n];
        boolean lookedAtSomeone = false;
        double onTarget = targetAngle();

        for (int i = 0; i < n; i++) {
            Step s = steps.get(i);
            aims[i] = bestAim(data, s.yaw, s.pitch, s.preYaw, s.prePitch);
            if (aims[i] != null && aims[i].boxAngle <= maxAngle && aims[i].distance <= 6.0) {
                lookedAtSomeone = true;
            }
        }

        boolean[] chosen = new boolean[n];
        int plainSwings = 0;
        if (data.swingsThisTick > 0 && n > 0) {
            for (int w = 0; w < data.frameSwings.size(); w++) {
                long sw = data.frameSwings.get(w);
                int bestI = -1;
                double bestImprove = -1.0E9;
                for (int i = 0; i < n; i++) {
                    if (Math.abs(steps.get(i).nanos - sw) > SWING_MATCH_NS) continue;
                    AimTarget at = aims[i];
                    double improve = at == null ? -1000.0 : at.preBoxAngle - at.boxAngle;
                    if (improve > bestImprove) {
                        bestImprove = improve;
                        bestI = i;
                    }
                }
                if (bestI >= 0) chosen[bestI] = true;
                else plainSwings++;
            }
        } else if (data.swingsThisTick > 0) {
            plainSwings = data.swingsThisTick;
        }

        boolean armedThisFrame = false;
        int notedBefore = data.lastSwingTargets.size();
        for (int i = 0; i < n; i++) {
            Step s = steps.get(i);
            boolean restored = false;
            if (data.pendingActive != 0 && s.nanos + 1_000_000L < data.pendingNanos) {
                restored = false;
            } else if (data.pendingActive != 0 && s.nanos - data.pendingNanos > RESTORE_WINDOW_NS) {
                finishPending(data);
            } else if (data.pendingActive != 0) {
                restored = advancePending(data, s);
            }
            if (restored || !chosen[i]) continue;

            AimTarget at = aims[i];
            if (data.pendingActive == 0 && (checkAimSnap || checkKillauraSnap)) {
                if (maybeArm(data, s, at, onTarget)) armedThisFrame = true;
            }
            scoreSwing(data, s.step, at, onTarget);
        }

        if (plainSwings > 0 && !anyChosen(chosen)) {
            AimTarget held = bestAim(data, data.aimYaw, data.aimPitch, data.aimYaw, data.aimPitch);
            if (held != null && held.boxAngle <= maxAngle && held.distance <= 6.0) lookedAtSomeone = true;
            scoreSwing(data, 0.0F, held, onTarget);
        }

        if (checkMultiAura && data.lastSwingTargets.size() > notedBefore) {
            evaluateMultiAura(data);
        }
        if (data.swingsThisTick > 0 && checkKillaura && !lookedAtSomeone) {
            checkOffAimHurt(data);
        }
        if (!armedThisFrame) finishPending(data);
        if (checkKillaura) checkInvalidPitch(data);
    }

    private static boolean anyChosen(boolean[] chosen) {
        for (int i = 0; i < chosen.length; i++) {
            if (chosen[i]) return true;
        }
        return false;
    }

    private boolean maybeArm(PlayerData data, Step s, AimTarget at, double onTarget) {
        if (at == null || System.nanoTime() < data.graceUntilNanos) return false;
        boolean isolated = s.step >= (float) snapThreshold && s.prevStep < 24.0F && s.prevStep2 < 36.0F;
        boolean landed = at.boxAngle <= onTarget && at.distance <= 6.0 && at.distance >= 0.4;
        double from = Math.max(30.0, onTarget + 6.0);
        boolean improved = at.preBoxAngle >= from && (at.preBoxAngle - at.boxAngle) >= 16.0;
        if (!isolated || !landed || !improved) return false;

        data.pendingActive = 1;
        data.pendingNanos = s.nanos;
        data.pendingPreYaw = s.preYaw;
        data.pendingPrePitch = s.prePitch;
        data.pendingSnapYawDelta = s.yawDelta;
        data.pendingSnapPitchDelta = s.pitchDelta;
        data.pendingTargetId = at.entityId;
        data.pendingEyeAngle = (float) Math.hypot(at.eyeYawErr, at.eyePitchErr);
        data.pendingSwept = false;
        if (debug) {
            Meowtils.addMessage(String.format(
                    "§8[§cBetterAC§8] §8debug §f%s §7snap §f%.0f° §7onto §f%s §8· §7eye §f%.1f°",
                    data.player.getName(), s.step, at.entity.getName(), data.pendingEyeAngle));
        }
        return true;
    }

    /** @return true when this step was a silent restore and should not also start a new snap */
    private boolean advancePending(PlayerData data, Step s) {
        if (data.pendingActive == 0) return false;
        Entity entity = mc.theWorld.getEntityByID(data.pendingTargetId);
        if (!(entity instanceof EntityPlayer) || entity.isDead) {
            data.clearPending();
            return false;
        }

        float backYaw = Math.abs(wrap(s.yaw - data.pendingPreYaw));
        float backPitch = Math.abs(wrap(s.pitch - data.pendingPrePitch));
        double boxNow = boxAngleTo(data, (EntityPlayer) entity, s.yaw, s.pitch);
        boolean restored = backYaw <= RESTORE_YAW && backPitch <= RESTORE_PITCH && boxNow >= 20.0;

        double pendMag = Math.hypot(data.pendingSnapYawDelta, data.pendingSnapPitchDelta);
        double dot = s.yawDelta * data.pendingSnapYawDelta + s.pitchDelta * data.pendingSnapPitchDelta;
        boolean sweep = s.step > 18.0F && pendMag > 1.0 && dot > 0.55 * s.step * pendMag;

        if (restored) {
            data.recordSnapBack();
            data.clearPending();
            return true;
        }
        if (sweep) {
            data.pendingSwept = true;
            data.clearPending();
        }
        return false;
    }

    private void finishPending(PlayerData data) {
        if (data.pendingActive == 0) return;
        if (System.nanoTime() - data.pendingNanos < RESTORE_WINDOW_NS) return;

        if (!data.pendingSwept && checkAimSnap) {
            Entity entity = mc.theWorld.getEntityByID(data.pendingTargetId);
            if (entity instanceof EntityPlayer && !entity.isDead) {
                double box = boxAngleTo(data, (EntityPlayer) entity, data.aimYaw, data.aimPitch);
                if (box <= targetAngle() + 8.0) data.recordAimSnap();
            }
        }
        data.clearPending();
    }

    private void scoreSwing(PlayerData data, float step, AimTarget at, double onTarget) {
        if (at == null) return;
        if (at.boxAngle <= onTarget && at.distance <= 5.5 && at.distance >= 0.45) {
            data.noteAimedTarget(at.entityId);
        }
        if (!checkKillauraConsistency) return;
        if (at.distance < 1.8 || at.distance > 5.5) return;
        if (at.boxAngle > 20.0 || at.speed < 0.14 || step > 45.0F) return;
        data.trackConsistency(at.eyeYawErr, at.eyePitchErr);
    }

    private void evaluateMultiAura(PlayerData data) {
        if (tickCounter - data.lastMultiFlagTick < 15) return;
        int shortDistinct = data.distinctTargets(Math.max(1, multiAuraTicks));
        int midDistinct = data.distinctTargets(12);
        int longDistinct = data.distinctTargets(25);
        if (shortDistinct >= 3) {
            data.addVL(5, "MultiAura", shortDistinct + " targets");
            data.lastMultiFlagTick = tickCounter;
            data.keepLastTarget();
        } else if (midDistinct >= 3) {
            data.addVL(4, "MultiAura", midDistinct + " targets");
            data.lastMultiFlagTick = tickCounter;
            data.keepLastTarget();
        } else if (longDistinct >= 4) {
            data.addVL(4, "MultiAura", longDistinct + " in a second");
            data.lastMultiFlagTick = tickCounter;
            data.keepLastTarget();
        }
    }

    private void checkOffAimHurt(PlayerData data) {
        EntityPlayer attacker = data.player;
        if (attacker == null) return;
        double[] ap = posOf(attacker);

        EntityPlayer victim = null;
        double best = 3.6 * 3.6;
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == null || other == attacker || other.isDead) continue;
            if (other != mc.thePlayer && isBot(other)) continue;
            if (!hurtThisTick(other)) continue;
            if (!soleSwinger(data, other)) continue;

            double[] op = posOf(other);
            double dx = op[0] - ap[0];
            double dy = op[1] - ap[1];
            double dz = op[2] - ap[2];
            if (Math.abs(dy) > 2.4) continue;
            double flat = dx * dx + dz * dz;
            if (flat < best && flat > 0.16) {
                best = flat;
                victim = other;
            }
        }
        if (victim == null) return;

        double box = boxAngleTo(data, victim, data.aimYaw, data.aimPitch);
        if (box <= maxAngle) return;

        data.offAimTicks.add(tickCounter);
        data.prune(data.offAimTicks, OFF_AIM_WINDOW);
        int hits = data.offAimTicks.size();
        if (hits >= OFF_AIM_COUNT) {
            data.addVL(5, "Killaura-Angle", "swinging away from a hit");
            data.offAimTicks.clear();
        } else {
            data.addVL(2, "Killaura-Angle", "swinging away from a hit");
        }
    }

    private boolean hurtThisTick(EntityPlayer player) {
        int ht = player == mc.thePlayer ? localLastHurtTick : -999;
        if (player != mc.thePlayer) {
            PlayerData d = dataMap.get(player.getUniqueID());
            ht = d == null ? -999 : d.lastHurtTick;
        }
        return ht == tickCounter;
    }

    private boolean soleSwinger(PlayerData attacker, EntityPlayer victim) {
        double[] vp = posOf(victim);
        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == null || other == attacker.player || other == victim || other.isDead) continue;
            if (other == mc.thePlayer) continue;
            PlayerData otherData = dataMap.get(other.getUniqueID());
            if (otherData == null || otherData.swingsThisTick <= 0) continue;
            double[] op = posOf(other);
            double dx = op[0] - vp[0];
            double dz = op[2] - vp[2];
            if (dx * dx + dz * dz < 36.0) return false;
        }
        return true;
    }

    private void checkInvalidPitch(PlayerData data) {
        if (System.nanoTime() < data.graceUntilNanos) {
            data.invalidPitchTicks = 0;
            return;
        }
        if (data.aimPitch < -90.01F || data.aimPitch > 90.01F) {
            data.invalidPitchTicks++;
            if (data.invalidPitchTicks == 5 || (data.invalidPitchTicks > 5 && data.invalidPitchTicks % 20 == 0)) {
                data.addVL(4, "Killaura-Pitch", String.format("%.1f", data.aimPitch));
            }
        } else {
            data.invalidPitchTicks = 0;
        }
    }

    private AimTarget bestAim(PlayerData attacker, float yaw, float pitch, float preYaw, float prePitch) {
        EntityPlayer self = attacker.player;
        if (self == null || mc.theWorld == null) return null;

        double[] ap = posOf(self);
        double eyeY = ap[1] + self.getEyeHeight();
        EntityPlayer best = null;
        double bestBox = 999.0;
        double bestDist = 999.0;
        double[] bestPos = null;

        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == null || other == self || other.isDead) continue;
            if (other != mc.thePlayer && isBot(other)) continue;

            double[] op = posOf(other);
            double dx = op[0] - ap[0];
            double dy = op[1] - ap[1];
            double dz = op[2] - ap[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist < 0.45 || dist > 6.0) continue;

            double box = boxAngle(ap[0], eyeY, ap[2], yaw, pitch, other, op);
            if (box < bestBox - 0.01 || (Math.abs(box - bestBox) <= 0.01 && dist < bestDist)) {
                bestBox = box;
                bestDist = dist;
                best = other;
                bestPos = op;
            }
        }
        if (best == null || bestPos == null) return null;

        double preBox = boxAngle(ap[0], eyeY, ap[2], preYaw, prePitch, best, bestPos);
        float[] err = eyeError(ap[0], eyeY, ap[2], yaw, pitch,
                bestPos[0], bestPos[1] + best.getEyeHeight(), bestPos[2]);
        double speed = Math.hypot(best.posX - best.lastTickPosX, best.posZ - best.lastTickPosZ);
        return new AimTarget(best, best.getEntityId(), bestDist, bestBox, preBox, err[0], err[1], speed);
    }

    private double boxAngleTo(PlayerData attacker, EntityPlayer target, float yaw, float pitch) {
        if (attacker.player == null || target == null) return 180.0;
        double[] ap = posOf(attacker.player);
        double[] tp = posOf(target);
        return boxAngle(ap[0], ap[1] + attacker.player.getEyeHeight(), ap[2], yaw, pitch, target, tp);
    }

    private static double boxAngle(double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                                   EntityPlayer target, double[] pos) {
        double half = Math.max(0.3, target.width * 0.5) + 0.12;
        double minX = pos[0] - half;
        double maxX = pos[0] + half;
        double minY = pos[1] - 0.05;
        double maxY = pos[1] + Math.max(1.5, target.height) + 0.05;
        double minZ = pos[2] - half;
        double maxZ = pos[2] + half;

        if (eyeX >= minX && eyeX <= maxX && eyeY >= minY && eyeY <= maxY && eyeZ >= minZ && eyeZ <= maxZ) {
            return 0.0;
        }

        double cx = MathHelper.clamp_double(eyeX, minX, maxX);
        double cy = MathHelper.clamp_double(eyeY, minY, maxY);
        double cz = MathHelper.clamp_double(eyeZ, minZ, maxZ);
        double dx = cx - eyeX;
        double dy = cy - eyeY;
        double dz = cz - eyeZ;
        double mag = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (mag < 1.0E-4) return 0.0;

        double yawRad = yaw * Math.PI / 180.0;
        double pitchRad = pitch * Math.PI / 180.0;
        double cosPitch = Math.cos(pitchRad);
        double lookX = -Math.sin(yawRad) * cosPitch;
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * cosPitch;
        double dot = (lookX * dx + lookY * dy + lookZ * dz) / mag;
        if (dot > 1.0) dot = 1.0;
        if (dot < -1.0) dot = -1.0;
        return Math.toDegrees(Math.acos(dot));
    }

    private static float[] eyeError(double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                                    double tx, double ty, double tz) {
        double dx = tx - eyeX;
        double dy = ty - eyeY;
        double dz = tz - eyeZ;
        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yawTo = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitchTo = (float) -(Math.atan2(dy, distXZ) * 180.0 / Math.PI);
        return new float[]{wrap(yaw - yawTo), wrap(pitch - pitchTo)};
    }

    private static double[] posOf(EntityPlayer player) {
        double x = player.serverPosX / 32.0;
        double y = player.serverPosY / 32.0;
        double z = player.serverPosZ / 32.0;
        if ((x == 0.0 && y == 0.0 && z == 0.0)
                || Math.abs(x - player.posX) > 64.0
                || Math.abs(y - player.posY) > 64.0) {
            return new double[]{player.posX, player.posY, player.posZ};
        }
        return new double[]{x, y, z};
    }

    private static float wrap(float angle) {
        return MathHelper.wrapAngleTo180_float(angle);
    }

    private static String prettyCheck(String check) {
        if ("Killaura-SnapHit".equals(check)) return "Snap Hit";
        if ("Killaura-Angle".equals(check)) return "Kill Aura";
        if ("Killaura-Pitch".equals(check)) return "Invalid Pitch";
        if ("Killaura-Consistency".equals(check)) return "Aim Lock";
        if ("AimSnap".equals(check)) return "Aim Snap";
        if ("MultiAura".equals(check)) return "Multi Aura";
        if ("AutoBlock".equals(check)) return "Auto Block";
        if ("NoSlow".equals(check)) return "No Slow";
        if ("LegitScaffold".equals(check)) return "Scaffold Assist";
        return check;
    }

    private static double stddev(List<Float> values) {
        double mean = 0.0;
        for (int i = 0; i < values.size(); i++) mean += values.get(i);
        mean /= values.size();
        double acc = 0.0;
        for (int i = 0; i < values.size(); i++) {
            double d = values.get(i) - mean;
            acc += d * d;
        }
        return Math.sqrt(acc / values.size());
    }

    private static double meanAbs(List<Float> values) {
        double sum = 0.0;
        for (int i = 0; i < values.size(); i++) sum += Math.abs(values.get(i));
        return sum / values.size();
    }

    private void checkAutoBlock(PlayerData data) {
        EntityPlayer p = data.player;
        if (p == null) return;

        ItemStack held = p.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemSword)) {
            data.swordSwingsWhileBlocking = 0;
            return;
        }

        if (data.swingsThisTick > 0 && data.useItemTime > 8) {
            int before = data.swordSwingsWhileBlocking;
            data.swordSwingsWhileBlocking += data.swingsThisTick;
            boolean crossed = before < 3 && data.swordSwingsWhileBlocking >= 3;
            boolean still = data.swordSwingsWhileBlocking >= 3 && tickCounter % 10 == 0;
            if (crossed || still) {
                data.addVL(3, "AutoBlock", "swing while blocked x" + data.swordSwingsWhileBlocking);
            }
        } else if (data.swingsThisTick > 0 && data.useItemTime == 0) {
            data.swordSwingsWhileBlocking = Math.max(0, data.swordSwingsWhileBlocking - 1);
        }
    }

    private void checkNoSlow(PlayerData data) {
        EntityPlayer p = data.player;
        if (p == null || p.isRiding()) return;
        if (data.useItemTime <= 8) return;

        double speedSq = data.deltaX * data.deltaX + data.deltaZ * data.deltaZ;
        if (speedSq < 0.09) return;
        if (data.moveLookDiff > 120.0F) return;

        ItemStack held = p.getHeldItem();
        boolean sword = held != null && held.getItem() instanceof ItemSword;

        if (data.sprintTime > 8 && (sword || speedSq >= 0.12)) {
            data.addVL(2, "NoSlow", String.format("%.2f", Math.sqrt(speedSq)));
        }
    }

    // ===================== SCAFFOLD =====================

    private void checkScaffold(PlayerData data) {
        int places = data.placesLastSecond();
        int swings = data.placeSwingsLastSecond();
        boolean moving = data.movingForward;

        if (checkScaffoldPlaceRate && places >= 11 && moving) {
            data.addVL(4, "Scaffold", "place rate " + places + "/s");
        }

        if (checkScaffoldSnap && data.snapPlaces >= 4 && places >= 4 && moving) {
            data.addVL(4, "Scaffold", "snap-to-place x" + data.snapPlaces);
        }

        if (checkScaffoldTelly && data.tellyBounces >= 3 && (places >= 4 || swings >= 5) && moving) {
            data.addVL(5, "Scaffold", "telly pitch");
        }

        if (tickCounter % 20 == 0) {
            data.snapPlaces = Math.max(0, data.snapPlaces - 2);
            data.tellyBounces = Math.max(0, data.tellyBounces - 1);
            if (data.sameYPlaces > 0) data.sameYPlaces--;
        }
    }

    private void checkLegitScaffold(PlayerData data) {
        int places = data.placesLastSecond();
        if (places >= 7
                && data.avgYawChange < 0.9F
                && data.movingForward
                && data.accuratePlaces >= 5
                && data.pitch > 55.0F) {
            data.addVL(2, "LegitScaffold", "assist bridging");
        }
    }

    // ===================== DATA =====================

    private class PlayerData {
        EntityPlayer player;
        float headYaw, pitch, lastHeadYaw, lastPitch;
        float aimYaw, aimPitch;
        double lastPosX, lastPosY, lastPosZ;
        double deltaX, deltaY, deltaZ;
        boolean isUsingItem;
        int useItemTime;
        int sprintTime;
        int swordSwingsWhileBlocking;
        int swingsThisTick;
        boolean movingForward;
        boolean rising;
        boolean holdingBlock;
        float avgYawChange;
        float avgPitchChange;
        float moveLookDiff;
        int vl;
        int lastPlaceY = Integer.MIN_VALUE;
        int sameYPlaces;
        int snapPlaces;
        int accuratePlaces;
        int tellyBounces;
        int lastSnapTick = -999;
        int lastDownSnapTick = -999;
        int lastUpSnapTick = -999;

        float prevStep;
        float prevStep2;
        volatile boolean forceBurst;
        boolean frameGrace;
        long graceUntilNanos;
        int pendingActive;
        long pendingNanos;
        float pendingPreYaw, pendingPrePitch;
        float pendingSnapYawDelta, pendingSnapPitchDelta;
        int pendingTargetId;
        float pendingEyeAngle;
        boolean pendingSwept;
        int invalidPitchTicks;
        int vlThisTick;
        int lastHurtTime;
        int lastHurtTick = -999;
        int lastMultiFlagTick = -999;
        int lastConsistencyTick = -999;

        final Object placeLock = new Object();
        final Map<String, Integer> flagCounts = new HashMap<String, Integer>();
        final Map<String, Integer> lastChatTick = new HashMap<String, Integer>();
        final List<SwingTarget> lastSwingTargets = new ArrayList<SwingTarget>(8);
        final List<Integer> placeTicks = new ArrayList<Integer>(24);
        final List<Integer> placeSwingTicks = new ArrayList<Integer>(24);
        final List<Integer> aimSnapTicks = new ArrayList<Integer>(6);
        final List<Integer> snapBackTicks = new ArrayList<Integer>(6);
        final List<Integer> offAimTicks = new ArrayList<Integer>(8);
        final List<Float> consYaw = new ArrayList<Float>(16);
        final List<Float> consPitch = new ArrayList<Float>(16);
        final List<RotSample> frameRaw = new ArrayList<RotSample>(8);
        final List<Long> frameSwings = new ArrayList<Long>(8);
        final List<Step> readySteps = new ArrayList<Step>(8);
        final ConcurrentLinkedQueue<RotSample> rotations = new ConcurrentLinkedQueue<RotSample>();
        final ConcurrentLinkedQueue<Long> swingTimes = new ConcurrentLinkedQueue<Long>();

        PlayerData(EntityPlayer p) {
            this.player = p;
            this.lastPosX = p.posX;
            this.lastPosY = p.posY;
            this.lastPosZ = p.posZ;
            this.headYaw = p.rotationYawHead;
            this.pitch = p.rotationPitch;
            this.aimYaw = this.headYaw;
            this.aimPitch = this.pitch;
            this.graceUntilNanos = System.nanoTime() + 500_000_000L;
        }

        void offerRotation(RotSample sample) {
            rotations.add(sample);
            if (rotations.size() > 32) {
                forceBurst = true;
                while (rotations.size() > 8) rotations.poll();
            }
        }

        void offerSwing(long nanos) {
            swingTimes.add(nanos);
            while (swingTimes.size() > 20) swingTimes.poll();
        }

        void tick(EntityPlayer p) {
            this.player = p;
            deltaX = p.posX - lastPosX;
            deltaY = p.posY - lastPosY;
            deltaZ = p.posZ - lastPosZ;
            lastPosX = p.posX;
            lastPosY = p.posY;
            lastPosZ = p.posZ;

            double speed = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            movingForward = speed > 0.08;
            rising = deltaY > 0.08;

            ItemStack held = p.getHeldItem();
            holdingBlock = held != null && held.getItem() instanceof ItemBlock;

            isUsingItem = p.isUsingItem();
            if (isUsingItem) useItemTime++;
            else {
                useItemTime = 0;
                swordSwingsWhileBlocking = 0;
            }

            if (p.isSprinting()) sprintTime++;
            else sprintTime = 0;

            float moveYaw = speed > 0.01 ? (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F : headYaw;
            moveLookDiff = Math.abs(wrap(headYaw - moveYaw));

            synchronized (placeLock) {
                prune(placeTicks, 20);
                prune(placeSwingTicks, 20);
            }
            if (tickCounter % 40 == 0) accuratePlaces = Math.max(0, accuratePlaces - 1);
        }

        void updateHurt() {
            int ht = player.hurtTime;
            if (ht > lastHurtTime && ht > 0) lastHurtTick = tickCounter;
            lastHurtTime = ht;
        }

        void endFrame() {
            frameRaw.clear();
            frameSwings.clear();
            readySteps.clear();
            swingsThisTick = 0;
            vlThisTick = 0;
            frameGrace = false;
        }

        boolean inScaffoldContext() {
            if (placesLastSecond() > 0 || placeSwingsLastSecond() > 0) return true;
            if (holdingBlock && movingForward && pitch > 48.0F && !hasNearbyCombatTarget()) return true;
            return tickCounter - lastSnapTick <= 6 && holdingBlock && pitch > 40.0F;
        }

        boolean hasNearbyCombatTarget() {
            if (player == null || mc.theWorld == null) return false;
            for (EntityPlayer other : mc.theWorld.playerEntities) {
                if (other == null || other == player || other.isDead) continue;
                if (other != mc.thePlayer && isBot(other)) continue;
                if (player.getDistanceToEntity(other) < 4.2) return true;
            }
            return false;
        }

        void noteRotationDelta(float yawDelta, float pitchDelta) {
            float combined = yawDelta + pitchDelta;
            if (combined > snapThreshold) {
                lastSnapTick = tickCounter;
                if (inScaffoldContext() || (holdingBlock && pitch > 40.0F)) {
                    snapPlaces++;
                }
            }

            if (pitchDelta > 28.0F) {
                if (pitch > lastPitch) {
                    lastDownSnapTick = tickCounter;
                    if (tickCounter - lastUpSnapTick <= 8) tellyBounces++;
                } else {
                    lastUpSnapTick = tickCounter;
                    if (tickCounter - lastDownSnapTick <= 8) tellyBounces++;
                }
            }
        }

        void onPlacedBlock(BlockPos pos, int tick) {
            synchronized (placeLock) {
                placeTicks.add(tick);
                prune(placeTicks, 20);
            }

            if (lastPlaceY == pos.getY()) sameYPlaces++;
            else sameYPlaces = 1;
            lastPlaceY = pos.getY();

            if (tick - lastSnapTick <= 4) snapPlaces++;

            if (player != null) {
                double dx = (pos.getX() + 0.5) - player.posX;
                double dy = (pos.getY() + 0.5) - (player.posY + player.getEyeHeight());
                double dz = (pos.getZ() + 0.5) - player.posZ;
                double distXZ = Math.sqrt(dx * dx + dz * dz);
                float yawTo = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
                float pitchTo = (float) -(Math.atan2(dy, distXZ) * 180.0 / Math.PI);
                float yawDiff = wrap(headYaw - yawTo);
                float pitchDiff = wrap(pitch - pitchTo);
                double angle = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
                if (angle < 28.0) accuratePlaces++;
            }
        }

        void notePlaceSwing(int tick) {
            synchronized (placeLock) {
                placeSwingTicks.add(tick);
                prune(placeSwingTicks, 20);
            }
            if (tick - lastSnapTick <= 4) snapPlaces++;
        }

        int placesLastSecond() {
            synchronized (placeLock) {
                prune(placeTicks, 20);
                return placeTicks.size();
            }
        }

        int placeSwingsLastSecond() {
            synchronized (placeLock) {
                prune(placeSwingTicks, 20);
                return placeSwingTicks.size();
            }
        }

        void prune(List<Integer> ticks, int window) {
            while (!ticks.isEmpty() && tickCounter - ticks.get(0) > window) {
                ticks.remove(0);
            }
        }

        void clearPending() {
            pendingActive = 0;
            pendingSwept = false;
            pendingEyeAngle = 180.0F;
        }

        void noteAimedTarget(int entityId) {
            lastSwingTargets.add(new SwingTarget(entityId, tickCounter));
            if (lastSwingTargets.size() > 12) lastSwingTargets.remove(0);
        }

        void keepLastTarget() {
            if (lastSwingTargets.isEmpty()) return;
            SwingTarget last = lastSwingTargets.get(lastSwingTargets.size() - 1);
            lastSwingTargets.clear();
            lastSwingTargets.add(last);
        }

        int distinctTargets(int window) {
            HashSet<Integer> seen = new HashSet<Integer>();
            for (int i = lastSwingTargets.size() - 1; i >= 0; i--) {
                SwingTarget st = lastSwingTargets.get(i);
                if (tickCounter - st.tick > window) break;
                seen.add(st.entityId);
            }
            return seen.size();
        }

        void trackConsistency(float yawErr, float pitchErr) {
            consYaw.add(yawErr);
            consPitch.add(pitchErr);
            while (consYaw.size() > 16) {
                consYaw.remove(0);
                consPitch.remove(0);
            }
            if (consYaw.size() < 10 || tickCounter - lastConsistencyTick <= 60) return;
            double ys = stddev(consYaw);
            double ps = stddev(consPitch);
            if (ys < 2.4 && ps < 2.4 && meanAbs(consYaw) < 22.0 && meanAbs(consPitch) < 22.0) {
                addVL(4, "Killaura-Consistency", String.format("yaw %.1f°  pitch %.1f°", ys, ps));
                consYaw.clear();
                consPitch.clear();
                lastConsistencyTick = tickCounter;
            }
        }

        void recordSnapBack() {
            if (!checkKillauraSnap) return;
            snapBackTicks.add(tickCounter);
            prune(snapBackTicks, SNAP_BACK_WINDOW);
            if (snapBackTicks.size() >= 2) {
                addVL(6, "Killaura-SnapHit", "back to the old angle");
                snapBackTicks.clear();
                aimSnapTicks.clear();
            } else {
                addVL(3, "Killaura-SnapHit", "back to the old angle");
            }
        }

        void recordAimSnap() {
            if (!checkAimSnap) return;
            aimSnapTicks.add(tickCounter);
            prune(aimSnapTicks, AIM_SNAP_WINDOW);
            if (aimSnapTicks.size() >= 2) {
                addVL(5, "AimSnap", "snapped onto a player");
                aimSnapTicks.clear();
            } else {
                addVL(2, "AimSnap", "snapped onto a player");
            }
        }

        void addVL(int amount, String check, String extra) {
            if (amount <= 0 || vlThisTick >= 8) return;
            if (vlThisTick + amount > 8) amount = 8 - vlThisTick;
            vlThisTick += amount;

            vl += amount;
            String nice = prettyCheck(check);

            if (debug) {
                Meowtils.addMessage("§8[§cBetterAC§8] §8debug §f" + player.getName()
                        + " §7+" + amount + " " + nice
                        + (extra != null ? " §8· §7" + extra : "")
                        + " §8· §7VL §f" + vl);
            }

            if (vl >= vlThreshold) {
                int last = lastChatTick.containsKey(check) ? lastChatTick.get(check) : -1000;
                if (tickCounter - last < CHAT_COOLDOWN) return;

                int times = flagCounts.containsKey(check) ? flagCounts.get(check) + 1 : 1;
                flagCounts.put(check, times);
                lastChatTick.put(check, tickCounter);

                String detail = extra == null ? "" : " §8· §7" + extra;
                String line;
                if (times >= 6) {
                    line = "§8[§cBetterAC§8] §f" + player.getName()
                            + " §8» §4Confirmed §f" + nice + " §8x" + times + detail;
                } else if (times >= 3) {
                    line = "§8[§cBetterAC§8] §f" + player.getName()
                            + " §8» §eLikely §f" + nice + " §8x" + times + detail;
                } else {
                    line = "§8[§cBetterAC§8] §f" + player.getName()
                            + " §8» §c" + nice + detail;
                }
                Meowtils.addMessage(line);
                if (flagSound && mc.thePlayer != null) {
                    mc.thePlayer.playSound("random.orb", 0.7F, 1.15F);
                }
                vl = Math.max(0, vl - 3);
            }
        }
    }

    private static final class RotSample {
        final float yaw;
        final float pitch;
        final boolean yawSet;
        final boolean pitchSet;
        final boolean teleport;
        final long nanos;

        RotSample(float yaw, float pitch, boolean yawSet, boolean pitchSet, boolean teleport, long nanos) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.yawSet = yawSet;
            this.pitchSet = pitchSet;
            this.teleport = teleport;
            this.nanos = nanos;
        }
    }

    private static final class Step {
        final long nanos;
        final float preYaw, prePitch, yaw, pitch;
        final float yawDelta, pitchDelta, step, prevStep, prevStep2;

        Step(long nanos, float preYaw, float prePitch, float yaw, float pitch,
             float yawDelta, float pitchDelta, float step, float prevStep, float prevStep2) {
            this.nanos = nanos;
            this.preYaw = preYaw;
            this.prePitch = prePitch;
            this.yaw = yaw;
            this.pitch = pitch;
            this.yawDelta = yawDelta;
            this.pitchDelta = pitchDelta;
            this.step = step;
            this.prevStep = prevStep;
            this.prevStep2 = prevStep2;
        }
    }

    private static final class AimTarget {
        final EntityPlayer entity;
        final int entityId;
        final double distance;
        final double boxAngle;
        final double preBoxAngle;
        final float eyeYawErr;
        final float eyePitchErr;
        final double speed;

        AimTarget(EntityPlayer entity, int entityId, double distance, double boxAngle, double preBoxAngle,
                  float eyeYawErr, float eyePitchErr, double speed) {
            this.entity = entity;
            this.entityId = entityId;
            this.distance = distance;
            this.boxAngle = boxAngle;
            this.preBoxAngle = preBoxAngle;
            this.eyeYawErr = eyeYawErr;
            this.eyePitchErr = eyePitchErr;
            this.speed = speed;
        }
    }

    private static class SwingTarget {
        final int entityId;
        final int tick;

        SwingTarget(int id, int tick) {
            this.entityId = id;
            this.tick = tick;
        }
    }
}
