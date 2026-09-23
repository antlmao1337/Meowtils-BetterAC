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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class BetterAntiCheat extends Extension {

    private static final Minecraft mc = Minecraft.getMinecraft();

    private static final Pattern BOT_NAME = Pattern.compile(
            "^(NPC|BOT|CIT-|CITIZEN|Shop|Guard|Villager|\\[NPC\\]).*",
            Pattern.CASE_INSENSITIVE
    );

    @Config public boolean enabled = true;
    @Config public int key = 0;

    @Config public boolean debug = false;
    @Config public boolean flagSound = true;
    @Config public boolean ignoreBots = true;
    @Config public int vlThreshold = 12;

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
    @Config public double snapThreshold = 55.0;

    private final Map<UUID, PlayerData> dataMap = new ConcurrentHashMap<UUID, PlayerData>();
    private int tickCounter = 0;

    public BetterAntiCheat() {
        super("BetterAntiCheat", "Improved client-side AC");

        check("Debug Mode", "debug");
        check("Flag Sound", "flagSound");
        check("Ignore Bots", "ignoreBots");
        slider("VL Threshold", 5, 25, 1, null, "vlThreshold", int.class);

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
        });
    }

    @Override
    public void onEnable() {
        dataMap.clear();
        if (debug) Meowtils.addMessage("§a[BetterAC] Enabled");
    }

    @Override
    public void onDisable() {
        dataMap.clear();
        if (debug) Meowtils.addMessage("§c[BetterAC] Disabled");
    }

    private boolean isBot(EntityPlayer player) {
        if (!ignoreBots || player == null) return false;
        if (!BOT_NAME.matcher(player.getName()).matches()) return false;

        double motion = Math.hypot(player.posX - player.lastTickPosX, player.posZ - player.lastTickPosZ);
        if (motion > 0.08) return false;

        PlayerData existing = dataMap.get(player.getUniqueID());
        if (existing != null && (existing.movingForward || existing.placesLastSecond() >= 1)) {
            return false;
        }
        return true;
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

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.isDead) continue;
            if (isBot(player)) continue;
            if (mc.thePlayer.getDistanceToEntity(player) > 32) continue;

            PlayerData data = dataMap.computeIfAbsent(player.getUniqueID(), uuid -> new PlayerData(player));
            data.tick(player);

            if (checkNoSlow) checkNoSlow(data);
            if (checkScaffoldPlaceRate || checkScaffoldSnap || checkScaffoldTelly) {
                checkScaffold(data);
            }
            if (checkLegitScaffold) checkLegitScaffold(data);

            if (data.vl > 0 && tickCounter % 15 == 0) {
                data.vl = Math.max(0, data.vl - 1);
            }
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
            if (e instanceof EntityPlayer && e != mc.thePlayer && !isBot((EntityPlayer) e)) {
                PlayerData data = dataMap.computeIfAbsent(e.getUniqueID(), uuid -> new PlayerData((EntityPlayer) e));
                data.updateHeadYaw((p.getYaw() * 360.0F) / 256.0F);
            }
        } else if (packet instanceof S14PacketEntity) {
            S14PacketEntity p = (S14PacketEntity) packet;
            Entity e = p.getEntity(mc.theWorld);
            if (e instanceof EntityPlayer && e != mc.thePlayer && !isBot((EntityPlayer) e)) {
                PlayerData data = dataMap.get(e.getUniqueID());
                if (data != null && p.func_149060_h()) {
                    data.updateBodyRotation(p.func_149066_f() * 360.0F / 256.0F, p.func_149063_g() * 360.0F / 256.0F);
                }
            }
        } else if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport p = (S18PacketEntityTeleport) packet;
            Entity e = mc.theWorld.getEntityByID(p.getEntityId());
            if (e instanceof EntityPlayer && e != mc.thePlayer && !isBot((EntityPlayer) e)) {
                PlayerData data = dataMap.computeIfAbsent(e.getUniqueID(), uuid -> new PlayerData((EntityPlayer) e));
                data.updateBodyRotation(p.getYaw() * 360.0F / 256.0F, p.getPitch() * 360.0F / 256.0F);
                data.lastPosX = p.getX() / 32.0;
                data.lastPosY = p.getY() / 32.0;
                data.lastPosZ = p.getZ() / 32.0;
            }
        } else if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation p = (S0BPacketAnimation) packet;
            if (p.getAnimationType() == 0) {
                Entity e = mc.theWorld.getEntityByID(p.getEntityID());
                if (e instanceof EntityPlayer && e != mc.thePlayer && !isBot((EntityPlayer) e)) {
                    PlayerData data = dataMap.computeIfAbsent(e.getUniqueID(), uuid -> new PlayerData((EntityPlayer) e));
                    data.onSwing();

                    if (data.inScaffoldContext()) {
                        if (checkScaffoldPlaceRate || checkScaffoldSnap || checkScaffoldTelly) {
                            data.notePlaceSwing(tickCounter);
                        }
                    } else {
                        if (checkKillaura) checkKillauraAngle(data);
                        if (checkKillauraSnap) checkKillauraSnapHit(data);
                        if (checkKillauraConsistency) checkKillauraConsistency(data);
                        if (checkAimSnap) checkAimSnap(data);
                        if (checkMultiAura) checkMultiAura(data);
                        if (checkAutoBlock) checkAutoBlock(data);
                    }
                }
            }
        }
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

    private double aimAngleTo(PlayerData data, EntityPlayer target) {
        EntityPlayer attacker = data.player;
        double dx = target.posX - attacker.posX;
        double dy = (target.posY + target.getEyeHeight()) - (attacker.posY + attacker.getEyeHeight());
        double dz = target.posZ - attacker.posZ;

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yawTo = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitchTo = (float) -(Math.atan2(dy, distXZ) * 180.0 / Math.PI);

        float yawDiff = MathHelper.wrapAngleTo180_float(data.headYaw - yawTo);
        float pitchDiff = MathHelper.wrapAngleTo180_float(data.pitch - pitchTo);
        return Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
    }

    private void checkKillauraAngle(PlayerData data) {
        EntityPlayer attacker = data.player;
        if (attacker == null) return;

        EntityPlayer bestTarget = null;
        double bestDist = 4.2;

        for (EntityPlayer other : mc.theWorld.playerEntities) {
            if (other == attacker || other == mc.thePlayer || other.isDead || isBot(other)) continue;
            double dist = attacker.getDistanceToEntity(other);
            if (dist < bestDist) {
                bestDist = dist;
                bestTarget = other;
            }
        }
        if (bestTarget == null) return;

        double angle = aimAngleTo(data, bestTarget);
        data.lastCombatAngle = (float) angle;
        data.lastCombatSwingTick = tickCounter;
        data.recentCombatSwings++;

        // Pitch outside normal human range
        if (data.pitch < -90.0F || data.pitch > 90.0F) {
            data.addVL(4, "Killaura-Pitch", String.format("%.1f", data.pitch));
        }

        if (angle > maxAngle && bestDist > 1.5 && bestDist < 4.0) {
            data.addVL(3, "Killaura-Angle", String.format("%.1f°", angle));
        }

        if (angle < 8.0 && bestDist < 3.8) {
            data.lowAngleHits++;
        }
    }

    /** Large rotation immediately followed by a combat swing */
    private void checkKillauraSnapHit(PlayerData data) {
        if (data.yawHistory.size() < 2) return;
        if (!data.hasNearbyCombatTarget()) return;

        float last = data.yawHistory.get(data.yawHistory.size() - 1);
        float prev = data.yawHistory.get(data.yawHistory.size() - 2);
        float delta = Math.abs(MathHelper.wrapAngleTo180_float(last - prev));

        if (delta > 45.0F && data.swingsThisTick > 0) {
            data.addVL(3, "Killaura-SnapHit", String.format("%.1f°", delta));
        }
    }

    /**
     * Near-zero aim angles with high combat swing rate (lock-on aura pattern).
     */
    private void checkKillauraConsistency(PlayerData data) {
        if (tickCounter % 20 == 0) {
            data.recentCombatSwings = Math.max(0, data.recentCombatSwings - 2);
            data.lowAngleHits = Math.max(0, data.lowAngleHits - 1);
        }

        if (data.recentCombatSwings >= 8 && data.lowAngleHits >= 5) {
            data.addVL(4, "Killaura-Consistency", "lock-on " + data.lowAngleHits + "/" + data.recentCombatSwings);
            data.lowAngleHits = Math.max(0, data.lowAngleHits - 3);
            data.recentCombatSwings = Math.max(0, data.recentCombatSwings - 3);
        }
    }

    private void checkMultiAura(PlayerData data) {
        if (data.lastSwingTargets.size() < 2) return;

        long now = System.currentTimeMillis();
        Set<Integer> seen = new HashSet<Integer>();
        int recentDistinct = 0;

        for (int i = data.lastSwingTargets.size() - 1; i >= 0; i--) {
            SwingTarget st = data.lastSwingTargets.get(i);
            if (now - st.time > multiAuraTicks * 50L) break;
            if (seen.add(st.entityId)) recentDistinct++;
        }

        if (recentDistinct >= 3) {
            data.addVL(5, "MultiAura", recentDistinct + " targets");
        }
    }

    private void checkAimSnap(PlayerData data) {
        if (data.inScaffoldContext()) return;
        if (data.yawHistory.size() < 3) return;

        float last = data.yawHistory.get(data.yawHistory.size() - 1);
        float prev = data.yawHistory.get(data.yawHistory.size() - 2);
        float delta = Math.abs(MathHelper.wrapAngleTo180_float(last - prev));

        if (delta > snapThreshold && data.swingsThisTick > 0 && data.hasNearbyCombatTarget()) {
            data.addVL(2, "AimSnap", String.format("%.1f°", delta));
        }
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
            data.swordSwingsWhileBlocking++;
            if (data.swordSwingsWhileBlocking >= 3) {
                data.addVL(3, "AutoBlock", "swing while blocked x" + data.swordSwingsWhileBlocking);
            }
        } else if (data.useItemTime == 0) {
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
        int likelyShown;
        int confirmedShown;
        int regularShown;

        // Combat consistency
        int recentCombatSwings;
        int lowAngleHits;
        int lastCombatSwingTick = -999;
        float lastCombatAngle = 999f;

        final Map<String, Integer> flagCounts = new HashMap<String, Integer>();
        final List<Float> yawHistory = new ArrayList<Float>(8);
        final List<SwingTarget> lastSwingTargets = new ArrayList<SwingTarget>(6);
        final List<Integer> placeTicks = new ArrayList<Integer>(24);
        final List<Integer> placeSwingTicks = new ArrayList<Integer>(24);

        PlayerData(EntityPlayer p) {
            this.player = p;
            this.lastPosX = p.posX;
            this.lastPosY = p.posY;
            this.lastPosZ = p.posZ;
            this.headYaw = p.rotationYawHead;
            this.pitch = p.rotationPitch;
        }

        void tick(EntityPlayer p) {
            this.player = p;
            deltaX = p.posX - lastPosX;
            deltaY = p.posY - lastPosY;
            deltaZ = p.posZ - lastPosZ;
            lastPosX = p.posX;
            lastPosY = p.posY;
            lastPosZ = p.posZ;

            swingsThisTick = 0;
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

            this.headYaw = p.rotationYawHead;
            this.pitch = p.rotationPitch;

            float moveYaw = speed > 0.01 ? (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F : headYaw;
            moveLookDiff = Math.abs(MathHelper.wrapAngleTo180_float(headYaw - moveYaw));

            prune(placeTicks, 20);
            prune(placeSwingTicks, 20);
            if (tickCounter % 40 == 0) accuratePlaces = Math.max(0, accuratePlaces - 1);
            if (yawHistory.size() > 6) yawHistory.remove(0);
        }

        boolean inScaffoldContext() {
            if (placesLastSecond() > 0 || placeSwingsLastSecond() > 0) return true;
            if (holdingBlock && movingForward && pitch > 48.0F && !hasNearbyCombatTarget()) return true;
            return tickCounter - lastSnapTick <= 6 && holdingBlock && pitch > 40.0F;
        }

        boolean hasNearbyCombatTarget() {
            if (player == null || mc.theWorld == null) return false;
            for (EntityPlayer other : mc.theWorld.playerEntities) {
                if (other == player || other == mc.thePlayer || other.isDead || isBot(other)) continue;
                if (player.getDistanceToEntity(other) < 4.2) return true;
            }
            return false;
        }

        void updateHeadYaw(float yaw) {
            lastHeadYaw = headYaw;
            headYaw = yaw;
            yawHistory.add(yaw);
            if (yawHistory.size() > 6) yawHistory.remove(0);

            float change = Math.abs(MathHelper.wrapAngleTo180_float(yaw - lastHeadYaw));
            avgYawChange = (avgYawChange * 0.75f) + (change * 0.25f);
            noteRotationDelta(change, 0.0F);
        }

        void updateBodyRotation(float yaw, float newPitch) {
            lastPitch = pitch;
            pitch = newPitch;
            float pitchDelta = Math.abs(MathHelper.wrapAngleTo180_float(newPitch - lastPitch));
            avgPitchChange = (avgPitchChange * 0.7f) + (pitchDelta * 0.3f);
            noteRotationDelta(0.0F, pitchDelta);
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
            placeTicks.add(tick);
            prune(placeTicks, 20);

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
                float yawDiff = MathHelper.wrapAngleTo180_float(headYaw - yawTo);
                float pitchDiff = MathHelper.wrapAngleTo180_float(pitch - pitchTo);
                double angle = Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
                if (angle < 28.0) accuratePlaces++;
            }
        }

        void notePlaceSwing(int tick) {
            placeSwingTicks.add(tick);
            prune(placeSwingTicks, 20);
            if (tick - lastSnapTick <= 4) snapPlaces++;
        }

        int placesLastSecond() {
            prune(placeTicks, 20);
            return placeTicks.size();
        }

        int placeSwingsLastSecond() {
            prune(placeSwingTicks, 20);
            return placeSwingTicks.size();
        }

        void prune(List<Integer> ticks, int window) {
            while (!ticks.isEmpty() && tickCounter - ticks.get(0) > window) {
                ticks.remove(0);
            }
        }

        void onSwing() {
            swingsThisTick++;
            if (inScaffoldContext()) return;
            for (EntityPlayer other : mc.theWorld.playerEntities) {
                if (other == player || other.isDead || isBot(other)) continue;
                if (player.getDistanceToEntity(other) < 4.2) {
                    lastSwingTargets.add(new SwingTarget(other.getEntityId(), System.currentTimeMillis()));
                    if (lastSwingTargets.size() > 8) lastSwingTargets.remove(0);
                    break;
                }
            }
        }

        void addVL(int amount, String check, String extra) {
            vl += amount;
            String reason = check + (extra != null ? " (" + extra + ")" : "");

            if (debug) {
                Meowtils.addMessage("§7[AC Debug] " + player.getName() + " +" + amount + " (" + reason + ") VL=" + vl);
            }

            if (vl >= vlThreshold) {
                int times = flagCounts.containsKey(check) ? flagCounts.get(check) + 1 : 1;
                flagCounts.put(check, times);

                boolean spoke = false;
                if (times >= 6) {
                    if (confirmedShown < 2) {
                        Meowtils.addMessage("§4§l[BetterAC] " + player.getName() + " is 100% CHEATING (" + check + ")");
                        confirmedShown++;
                        spoke = true;
                    }
                } else if (times >= 3) {
                    if (likelyShown < 1) {
                        Meowtils.addMessage("§c[BetterAC] " + player.getName() + " is most likely cheating (" + check + ")");
                        likelyShown++;
                        spoke = true;
                    }
                } else if (regularShown < 1 && likelyShown == 0 && confirmedShown == 0) {
                    Meowtils.addMessage("§c[BetterAC] §f" + player.getName() + " §7flagged §c" + reason);
                    regularShown++;
                    spoke = true;
                }

                if (spoke && flagSound) {
                    mc.thePlayer.playSound("random.orb", 1.0F, 1.0F);
                }
                vl = Math.max(0, vl - 6);
            }
        }
    }

    private static class SwingTarget {
        final int entityId;
        final long time;

        SwingTarget(int id, long t) {
            this.entityId = id;
            this.time = t;
        }
    }
}