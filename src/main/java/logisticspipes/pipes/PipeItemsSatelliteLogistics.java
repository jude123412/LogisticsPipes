/*
 * Copyright (c) Krapht, 2011 "LogisticsPipes" is distributed under the terms of the Minecraft Mod Public License 1.0,
 * or MMPL. Please check the contents of the license located in http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package logisticspipes.pipes;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.common.util.ForgeDirection;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandlers;
import com.cleanroommc.modularui.widgets.layout.Column;
import com.cleanroommc.modularui.widgets.layout.Row;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

import logisticspipes.LogisticsPipes;
import logisticspipes.api.IMUICompatiblePipe;
import logisticspipes.compat.ModularUIHelper;
import logisticspipes.interfaces.IChestContentReceiver;
import logisticspipes.interfaces.routing.IAdditionalTargetInformation;
import logisticspipes.interfaces.routing.IRequestItems;
import logisticspipes.interfaces.routing.IRequireReliableTransport;
import logisticspipes.items.ItemModule;
import logisticspipes.logisticspipes.ItemModuleInformationManager;
import logisticspipes.modules.ModuleSatelite;
import logisticspipes.modules.abstractmodules.LogisticsModule;
import logisticspipes.network.PacketHandler;
import logisticspipes.network.abstractpackets.ModernPacket;
import logisticspipes.network.packets.hud.ChestContent;
import logisticspipes.network.packets.satpipe.SatPipeNext;
import logisticspipes.network.packets.satpipe.SatPipePrev;
import logisticspipes.network.packets.satpipe.SatPipeSetID;
import logisticspipes.pipes.basic.CoreRoutedPipe;
import logisticspipes.proxy.MainProxy;
import logisticspipes.proxy.SimpleServiceLocator;
import logisticspipes.request.RequestTree;
import logisticspipes.security.SecuritySettings;
import logisticspipes.textures.Textures;
import logisticspipes.textures.Textures.TextureType;
import logisticspipes.utils.InventoryHelper;
import logisticspipes.utils.PlayerCollectionList;
import logisticspipes.utils.SidedInventoryMinecraftAdapter;
import logisticspipes.utils.item.ItemIdentifierStack;
import logisticspipes.utils.tuples.LPPosition;
import lombok.Setter;

public class PipeItemsSatelliteLogistics extends CoreRoutedPipe
        implements IRequestItems, IRequireReliableTransport, IMUICompatiblePipe, IChestContentReceiver {

    public final PlayerCollectionList localModeWatchers = new PlayerCollectionList();
    public final LinkedList<ItemIdentifierStack> itemList = new LinkedList<>();
    public final LinkedList<ItemIdentifierStack> oldList = new LinkedList<>();

    public PipeItemsSatelliteLogistics(Item item) {
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
    public TextureType getCenterTexture() {
        return Textures.LOGISTICSPIPE_SATELLITE_TEXTURE;
    }

    @Override
    public void enabledUpdateEntity() {
        super.enabledUpdateEntity();
        if (isNthTick(20) && localModeWatchers.size() > 0) {
            updateInv(false);
        }
    }

    @Override
    public LogisticsModule getLogisticsModule() {
        return new ModuleSatelite(this);
    }

    @Override
    public ItemSendMode getItemSendMode() {
        return ItemSendMode.Normal;
    }

    private IInventory getRawInventory(ForgeDirection ori) {
        LPPosition pos = new LPPosition(getX(), getY(), getZ());
        pos.moveForward(ori);
        TileEntity tile = pos.getTileEntity(getWorld());
        if (SimpleServiceLocator.pipeInformationManager.isItemPipe(tile)) {
            return null;
        }
        if (!(tile instanceof IInventory)) {
            return null;
        }
        return InventoryHelper.getInventory((IInventory) tile);
    }

    private IInventory getInventory(ForgeDirection ori) {
        IInventory rawInventory = getRawInventory(ori);
        if (rawInventory instanceof net.minecraft.inventory.ISidedInventory) {
            return new SidedInventoryMinecraftAdapter(
                    (net.minecraft.inventory.ISidedInventory) rawInventory,
                    ori.getOpposite(),
                    false);
        }
        return rawInventory;
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
        for (ForgeDirection ori : ForgeDirection.VALID_DIRECTIONS) {
            if (!this.container.isPipeConnected(ori)) continue;
            IInventory inv = getInventory(ori);
            if (inv != null) {
                for (int i = 0; i < inv.getSizeInventory(); i++) {
                    if (inv.getStackInSlot(i) != null) {
                        addToList(ItemIdentifierStack.getFromStack(inv.getStackInSlot(i)));
                    }
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
    public void playerStartWatching(EntityPlayer player, int mode) {
        if (mode == 1) {
            localModeWatchers.add(player);
            final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId)
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

    @Override
    public void setReceivedChestContent(Collection<ItemIdentifierStack> list) {
        itemList.clear();
        itemList.addAll(list);
    }

    public static Set<PipeItemsSatelliteLogistics> AllSatellites = Collections.newSetFromMap(new WeakHashMap<>());

    // called only on server shutdown
    public static void cleanup() {
        PipeItemsSatelliteLogistics.AllSatellites.clear();
    }

    protected final LinkedList<ItemIdentifierStack> _lostItems = new LinkedList<>();

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
            for (final PipeItemsSatelliteLogistics sat : PipeItemsSatelliteLogistics.AllSatellites) {
                if (sat.satelliteId == potentialId) {
                    conflict = true;
                    break;
                }
            }
        }
        return potentialId;
    }

    @Override
    public boolean handleClick(EntityPlayer entityplayer, SecuritySettings settings) {
        if (entityplayer.getCurrentEquippedItem() == null) {
            return super.handleClick(entityplayer, settings);
        }

        if (!entityplayer.isSneaking() && entityplayer.getCurrentEquippedItem().getItem() == LogisticsPipes.ModuleItem
                && ItemModule.isCrafter(entityplayer.getCurrentEquippedItem())) {
            if (MainProxy.isServer(getWorld())) {
                if (settings == null || settings.openGui) {
                    ItemStack crafterStack = entityplayer.getCurrentEquippedItem();
                    logisticspipes.modules.ModuleCrafter crafter = (logisticspipes.modules.ModuleCrafter) LogisticsPipes.ModuleItem
                            .getModuleForItem(crafterStack, null, this, this);

                    if (crafter != null) {
                        // First read the existing module data to preserve crafting configuration
                        ItemModuleInformationManager.readInformation(crafterStack, crafter);

                        crafter.satelliteId = this.satelliteId;

                        ItemModuleInformationManager.saveInfotmation(crafterStack, crafter);
                        entityplayer.addChatComponentMessage(
                                new ChatComponentTranslation("lp.chat.satelliteid.set", this.satelliteId));
                    }
                } else {
                    entityplayer.addChatComponentMessage(new ChatComponentTranslation("lp.chat.permissiondenied"));
                }
            }
            return true;
        }

        return false;
    }

    protected void ensureAllSatelliteStatus() {
        if (MainProxy.isClient()) {
            return;
        }
        if (satelliteId == 0) {
            PipeItemsSatelliteLogistics.AllSatellites.remove(this);
        }
        if (satelliteId != 0) {
            PipeItemsSatelliteLogistics.AllSatellites.add(this);
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
                ((PipeItemsSatelliteLogistics) container.pipe).localModeWatchers);
    }

    @Override
    public void onAllowedRemoval() {
        if (MainProxy.isClient(getWorld())) {
            return;
        }
        PipeItemsSatelliteLogistics.AllSatellites.remove(this);
    }

    @Override
    public void onWrenchClicked(EntityPlayer entityplayer) {
        // Send the satellite id when opening gui
        final ModernPacket packet = PacketHandler.getPacket(SatPipeSetID.class).setSatID(satelliteId).setPosX(getX())
                .setPosY(getY()).setPosZ(getZ());
        MainProxy.sendPacketToPlayer(packet, entityplayer);
        entityplayer.openGui(LogisticsPipes.instance, GuiIDs.GUI_SatelitePipe_ID, getWorld(), getX(), getY(), getZ());
        openGui(entityplayer, this);
    }
    }

    @Override
    public void throttledUpdateEntity() {
        super.throttledUpdateEntity();
        if (_lostItems.isEmpty()) {
            return;
        }
        final Iterator<ItemIdentifierStack> iterator = _lostItems.iterator();
        while (iterator.hasNext()) {
            ItemIdentifierStack stack = iterator.next();
            int received = RequestTree.requestPartial(stack, (CoreRoutedPipe) container.pipe, null);
            if (received > 0) {
                if (received == stack.getStackSize()) {
                    iterator.remove();
                } else {
                    stack.setStackSize(stack.getStackSize() - received);
                }
            }
        }
    }

    @Override
    public void itemLost(ItemIdentifierStack item, IAdditionalTargetInformation info) {
        _lostItems.add(item);
    }

    @Override
    public void itemArrived(ItemIdentifierStack item, IAdditionalTargetInformation info) {}

}
