package me.marquez.wcp;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.mojang.datafixers.util.Pair;
import net.minecraft.server.v1_16_R3.*;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld;
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer;
import org.bukkit.craftbukkit.v1_16_R3.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WheelChairPlugin extends JavaPlugin implements Listener {

    private String itemType;
    private int customModelData;
    private double offsetY;
    private double sizeX;
    private double sizeZ;
    private double speed;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemType = getConfig().getString("WheelChair.Type");
        customModelData = getConfig().getInt("WheelChair.CustomModelData");
        offsetY = getConfig().getDouble("WheelChair.offset-Y");
        sizeX = getConfig().getDouble("WheelChair.size.X");
        sizeZ = getConfig().getDouble("WheelChair.size.Z");
        speed = getConfig().getDouble("WheelChair.speed");

        getCommand("wcp").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        onRightClick(event.getPlayer(), event.getPacket().getIntegers().read(0));
                    }
                }
        );
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(this, PacketType.Play.Client.STEER_VEHICLE) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        if(event.isPlayerTemporary()) return;
                        PacketContainer container = event.getPacket();
                        Player player = event.getPlayer();

                        float side = container.getFloat().read(0);
                        float forward = container.getFloat().read(1);
//                        boolean space = container.getBooleans().read(0);
                        boolean shift = container.getBooleans().read(1);

                        if(shift) {
                            ridingMap.remove(player);
                            return;
                        }

                        if(ridingMap.containsKey(player)) moveWC(player, ridingMap.get(player), side, forward);
                    }
                }
        );
    }

    @Override
    public void onDisable() {
        destroyWC();
    }

    public void destroyWC() {
        entityMap.keySet().forEach(entityId -> {
            PacketPlayOutEntityDestroy destroyPacket = new PacketPlayOutEntityDestroy(entityId);
            sendPackets(destroyPacket);
        });
        entityMap.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(sender instanceof Player player) {
            if (args.length == 0) {
                player.sendMessage("/wcp spawn");
                player.sendMessage("/wcp clear");
            } else {
                switch (args[0]) {
                    case "spawn":
                        spawnWC(player.getLocation());
                        break;
                    case "clear":
                        destroyWC();
                        break;
                }
            }
        }
        return true;
    }

    private void spawnWC(Location location) {
        EntityArmorStand as = new EntityArmorStand(((CraftWorld)location.getWorld()).getHandle(), location.getX(), location.getY()+offsetY, location.getZ());

        PacketPlayOutSpawnEntityLiving spawnPacket = new PacketPlayOutSpawnEntityLiving(as);

        as.setInvisible(true);
        as.setNoGravity(true);

        PacketPlayOutEntityMetadata dataPacket = new PacketPlayOutEntityMetadata(as.getId(), as.getDataWatcher(), true);

        ItemStack item = new ItemStack(Material.getMaterial(itemType));
        ItemMeta itemMeta = item.getItemMeta();
        itemMeta.setCustomModelData(customModelData);
        item.setItemMeta(itemMeta);

        PacketPlayOutEntityEquipment equipPacket = new PacketPlayOutEntityEquipment(as.getId(), List.of(Pair.of(EnumItemSlot.HEAD, CraftItemStack.asNMSCopy(item))));

        sendPackets(spawnPacket, dataPacket, equipPacket);

        entityMap.put(as.getId(), as);
    }

    private void sendPackets(Packet<?>... packets) {
        Bukkit.getOnlinePlayers().forEach(player -> {
            PlayerConnection connection = ((CraftPlayer)player).getHandle().playerConnection;
            for (Packet<?> packet : packets) {
                connection.sendPacket(packet);
            }
        });
    }

    private final Map<Integer, Entity> entityMap = new HashMap<>();
    private final Map<Player, Integer> ridingMap = new HashMap<>();

    private void rideWC(Player player, int entityId) {
        PacketPlayOutMount mountPacket = new PacketPlayOutMount();
        try {
            Field fieldA = PacketPlayOutMount.class.getDeclaredField("a");
            Field fieldB = PacketPlayOutMount.class.getDeclaredField("b");
            fieldA.setAccessible(true);
            fieldB.setAccessible(true);
            fieldA.set(mountPacket, entityId);
            fieldB.set(mountPacket, new int[]{ player.getEntityId() });
        }catch(NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        sendPackets(mountPacket);
        ridingMap.put(player, entityId);
    }

    private void moveWC(Player player, int entityId, float side, float forward) {
        EntityArmorStand as = (EntityArmorStand)entityMap.get(entityId);
        Vector direction = as.getBukkitEntity().getLocation().getDirection();
        if(forward != 0) {
            Vector vector = direction.multiply(speed).multiply(forward);
            double x = as.locX()+vector.getX(), y = as.locY()-offsetY, z = as.locZ()+vector.getZ();
//            org.bukkit.Chunk chunk = player.getWorld().getBlockAt((int)x, (int)y, (int)z).getChunk();
//            if(chunk.isLoaded()) {
//                chunk.load(true);
//            }
            double resultY = 0;
            for(int i = 0; i < sizeX; i++) {
                for (int j = 0; j < sizeZ; j++) {
                    double fx = (x - (sizeX / 2 - 0.5)) + i;
                    double fz = (z - (sizeZ / 2 - 0.5)) + j;
                    double fy = y;
                    org.bukkit.block.Block block = player.getWorld().getBlockAt((int) fx, (int) fy, (int) fz);
                    fy = block.getY();
                    if (block.getType() == Material.AIR) {
                        block = block.getRelative(0, -1, 0);
                    } else if (block.getRelative(0, 1, 0).getType() != Material.AIR) {
                        continue;
                    } else {
                        fy += 1;
                    }
                    Material material = block.getType();
                    if (material == Material.AIR) {
                        fy -= 1;
                    } else if (material.toString().contains("SLAB")) {
                        fy -= 0.5;
                    } else if (material == Material.SNOW) {
                        fy -= 1.0-((Snow)block.getBlockData()).getLayers()/8.0;
                    }
                    resultY = Math.max(resultY, fy);
                }
            }
            as.setPosition(x, resultY+offsetY, z);
            Bukkit.getScheduler().runTask(this, () -> ((CraftPlayer)player).getHandle().setPosition(as.locX(), as.locY(), as.locZ()));
        }
        if(side != 0) {
            as.yaw -= side*2 * (forward >= 0 ? 1 : -1);
        }
        PacketPlayOutEntityTeleport tpPacket = new PacketPlayOutEntityTeleport(as);
        sendPackets(tpPacket);
    }

    public void onRightClick(Player player, int entityId) {
        if(entityMap.containsKey(entityId)) {
            rideWC(player, entityId);
        }
    }
}
