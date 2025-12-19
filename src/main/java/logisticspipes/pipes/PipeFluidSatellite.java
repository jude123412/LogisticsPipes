package logisticspipes.pipes;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandlers;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import logisticspipes.api.IMUICompatiblePipe;
import logisticspipes.compat.ModularUIHelper;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import logisticspipes.LogisticsPipes;
import logisticspipes.gui.hud.HUDSatellite;
import logisticspipes.interfaces.IChestContentReceiver;
import logisticspipes.interfaces.IHeadUpDisplayRenderer;
import logisticspipes.interfaces.IHeadUpDisplayRendererProvider;
import logisticspipes.interfaces.routing.IRequestFluid;
import logisticspipes.interfaces.routing.IRequireReliableFluidTransport;
import logisticspipes.modules.ModuleSatelite;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.GuiIDs;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.packets.hud.ChestContent;
import logisticspipes.network.packets.hud.HUDStartWatchingPacket;
import logisticspipes.network.packets.hud.HUDStopWatchingPacket;
import logisticspipes.network.packets.satpipe.SatPipeNext;
import logisticspipes.network.packets.satpipe.SatPipePrev;
import logisticspipes.network.packets.satpipe.SatPipeSetID;
import logisticspipes.pipes.basic.fluid.FluidRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.request.RequestTree;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.utils.FluidIdentifier;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.Pair;

@Log4j2
public class PipeFluidSatellite extends FluidRoutedPipe implements IRequestFluid, IRequireReliableFluidTransport,
    IMUICompatiblePipe, IChestContentReceiver {

    public final PlayerCollectionList localModeWatchers = new PlayerCollectionList();
    public final LinkedList<ItemIdentifierStack> itemList = new LinkedList<>();
    public final LinkedList<ItemIdentifierStack> oldList = new LinkedList<>();
    private final HUDSatellite HUD = new HUDSatellite(this);

    public PipeFluidSatellite(Item item) {
        super(item);
        throttleTime = 40;
    }

    @Override
    public void addUIWidgets(ModularPanel panel, PosGuiData data, PanelSyncManager syncManager) {
        panel.background(ModularUIHelper.BACKGROUND_TEXTURE).child(
            new Column().widthRel(1.0f).top(6).coverChildrenHeight()
                .child(
                    new Row()
                        .marginTop(5).mainAxisAlignment(Alignment.MainAxis.CENTER)
                        .crossAxisAlignment(Alignment.CrossAxis.CENTER).widthRel(
                            1.0F)
                        .coverChildrenHeight().child(IKey.lang("gui.satellite.SatelliteID").asWidget()))
                .child(
                    new Column().widthRel(1.0f).top(6).coverChildrenHeight().child(
                        new Row().marginTop(15).mainAxisAlignment(Alignment.MainAxis.CENTER)
                            .coverChildrenHeight().child(
                                new TextFieldWidget().width(60).setNumbers(0, Integer.MAX_VALUE)
                                    .value(
                                        SyncHandlers.intNumber(
                                            () -> this.satelliteId,
                                            value -> this.satelliteId = value))))));
    }

    @Override
    public String getId() {
        return "satelite_pipe";
    }

    @Override
    public int getGuiWidth() {
        return 116;
    }

    @Override
    public int getGuiHeight() {
        return 70;
    }

    @Override
    public boolean canInsertFromSideToTanks() {
        return true;
    }

    @Override
    public boolean canInsertToTanks() {
        return true;
    }

    @Override
    public TextureType getCenterTexture() {
        return Textures.LOGISTICSPIPE_LIQUID_SATELLITE;
    }

    @Override
    public LogisticsModule getLogisticsModule() {
        return new ModuleSatelite(this);
    }

    @Override
    public ItemSendMode getItemSendMode() {
        return ItemSendMode.Normal;
    }

    @Override
    public void enabledUpdateEntity() {
        super.enabledUpdateEntity();
        if (isNthTick(20) && localModeWatchers.size() > 0) {
            updateInv(false);
        }
    }

    @Override
    public void sendFailed(FluidIdentifier liquid, Integer amount) {
        liquidLost(liquid, amount);
    }

    private void addToList(ItemIdentifierStack stack) {
        for (ItemIdentifierStack ident : itemList) {
            if (ident.getItem().equals(stack.getItem())) {
                ident.setStackSize(ident.getStackSize() + stack.getStackSize());
                return;
            }
        }
        itemList.addLast(stack);
    }

    private void updateInv(boolean force) {
        itemList.clear();
        for (Pair<TileEntity, ForgeDirection> pair : getAdjacentTanks(false)) {
            if (!(pair.getValue1() instanceof IFluidHandler)) {
                continue;
            }
            IFluidHandler tankContainer = (IFluidHandler) pair.getValue1();
            FluidTankInfo[] tanks = tankContainer.getTankInfo(pair.getValue2().getOpposite());
            if (tanks == null) {
                continue;
            }
            for (FluidTankInfo tank : tanks) {
                if (tank == null) {
                    continue;
                }
                FluidStack liquid = tank.fluid;
                if (liquid != null && liquid.getFluidID() != 0) {
                    addToList(FluidIdentifier.get(liquid).getItemIdentifier().makeStack(liquid.amount));
                }
            }
        }
        if (!itemList.equals(oldList) || force) {
            oldList.clear();
            oldList.addAll(itemList);
            MainProxy.sendToPlayerList(
                    PacketHandler.getPacket(ChestContent.class).setIdentList(itemList).setPosX(getX()).setPosY(getY())
                            .setPosZ(getZ()),
                    localModeWatchers);
        }
    }

    @Override
    public void setReceivedChestContent(Collection<ItemIdentifierStack> list) {
        itemList.clear();
        itemList.addAll(list);
    }

    @Override
    public void playerStartWatching(EntityPlayer player, int mode) {
        if (mode == 1) {
            localModeWatchers.add(player);
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID((this).satelliteId)
                    .setPosX(getX()).setPosY(getY()).setPosZ(getZ());
            MainProxy.sendPacketToPlayer(packet, player);
            updateInv(true);
        } else {
            super.playerStartWatching(player, mode);
        }
    }

    @Override
    public void playerStopWatching(EntityPlayer player, int mode) {
        super.playerStopWatching(player, mode);
        localModeWatchers.remove(player);
    }

    // from baseLogicLiquidSatellite
    public static HashSet<PipeFluidSatellite> AllSatellites = new HashSet<>();

    // called only on server shutdown
    public static void cleanup() {
        PipeFluidSatellite.AllSatellites.clear();
    }

    protected final Map<FluidIdentifier, Integer> _lostItems = new HashMap<>();

    @Setter
    public int satelliteId;

    @Override
    public void readFromNBT(NBTTagCompound nbttagcompound) {
        super.readFromNBT(nbttagcompound);
        satelliteId = nbttagcompound.getInteger("satelliteid");
        ensureAllSatelliteStatus();
    }

    @Override
    public void writeToNBT(NBTTagCompound nbttagcompound) {
        nbttagcompound.setInteger("satelliteid", satelliteId);
        super.writeToNBT(nbttagcompound);
    }

    protected int findId(int increment) {
        if (MainProxy.isClient(getWorld())) {
            return satelliteId;
        }
        int potentialId = satelliteId;
        boolean conflict = true;
        while (conflict) {
            potentialId += increment;
            if (potentialId < 0) {
                return 0;
            }
            conflict = false;
            for (final PipeFluidSatellite sat : PipeFluidSatellite.AllSatellites) {
                if (sat.satelliteId == potentialId) {
                    conflict = true;
                    break;
                }
            }
        }
        return potentialId;
    }

    protected void ensureAllSatelliteStatus() {
        if (MainProxy.isClient()) {
            return;
        }
        if (satelliteId == 0) {
            PipeFluidSatellite.AllSatellites.remove(this);
        }
        if (satelliteId != 0) {
            PipeFluidSatellite.AllSatellites.add(this);
        }
    }

    public void setNextId(EntityPlayer player) {
        satelliteId = findId(1);
        ensureAllSatelliteStatus();
        if (MainProxy.isClient(player.worldObj)) {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeNext.class).setPosX(getX()).setPosY(getY())
                    .setPosZ(getZ());
            MainProxy.sendPacketToServer(packet);
        } else {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId)
                    .setPosX(getX()).setPosY(getY()).setPosZ(getZ());
            MainProxy.sendPacketToPlayer(packet, player);
        }
        updateWatchers();
    }

    public void setPrevId(EntityPlayer player) {
        satelliteId = findId(-1);
        ensureAllSatelliteStatus();
        if (MainProxy.isClient(player.worldObj)) {
            final ModernPacket packet = PacketHandler.getPacket(SatPipePrev.class).setPosX(getX()).setPosY(getY())
                    .setPosZ(getZ());
            MainProxy.sendPacketToServer(packet);
        } else {
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId)
                    .setPosX(getX()).setPosY(getY()).setPosZ(getZ());
            MainProxy.sendPacketToPlayer(packet, player);
        }
        updateWatchers();
    }

    private void updateWatchers() {
        MainProxy.sendToPlayerList(
                PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId).setPosX(getX()).setPosY(getY())
                        .setPosZ(getZ()),
                ((PipeFluidSatellite) container.pipe).localModeWatchers);
    }

    @Override
    public void onAllowedRemoval() {
        if (MainProxy.isClient(getWorld())) {
            return;
        }
        PipeFluidSatellite.AllSatellites.remove(this);
    }

    @Override
    public void onWrenchClicked(EntityPlayer entityplayer) {
        // Send the satellite id when opening gui
        final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId).setPosX(getX())
                .setPosY(getY()).setPosZ(getZ());
        MainProxy.sendPacketToPlayer(packet, entityplayer);
        openGui(entityplayer, this);
    }

    @Override
    public void throttledUpdateEntity() {
        super.throttledUpdateEntity();
        if (_lostItems.isEmpty()) {
            return;
        }
        final Iterator<Entry<FluidIdentifier, Integer>> iterator = _lostItems.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<FluidIdentifier, Integer> stack = iterator.next();
            int received = RequestTree.requestFluidPartial(stack.getKey(), stack.getValue(), this, null);

            if (received > 0) {
                if (received == stack.getValue()) {
                    iterator.remove();
                } else {
                    stack.setValue(stack.getValue() - received);
                }
            }
        }
    }

    @Override
    public void liquidLost(FluidIdentifier item, int amount) {
        if (_lostItems.containsKey(item)) {
            _lostItems.put(item, _lostItems.get(item) + amount);
        } else {
            _lostItems.put(item, amount);
        }
    }

    @Override
    public void liquidArrived(FluidIdentifier item, int amount) {}

    @Override
    public void liquidNotInserted(FluidIdentifier item, int amount) {
        liquidLost(item, amount);
    }

    @Override
    public boolean canReceiveFluid() {
        return false;
    }
}
