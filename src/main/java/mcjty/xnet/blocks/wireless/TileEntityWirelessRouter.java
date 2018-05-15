package mcjty.xnet.blocks.wireless;

import mcjty.lib.tileentity.GenericEnergyReceiverTileEntity;
import mcjty.lib.varia.GlobalCoordinate;
import mcjty.theoneprobe.api.IProbeHitData;
import mcjty.theoneprobe.api.IProbeInfo;
import mcjty.theoneprobe.api.ProbeMode;
import mcjty.theoneprobe.api.TextStyleClass;
import mcjty.xnet.api.channels.IChannelType;
import mcjty.xnet.api.channels.IConnectorSettings;
import mcjty.xnet.api.keys.NetworkId;
import mcjty.xnet.api.keys.SidedConsumer;
import mcjty.xnet.blocks.generic.CableColor;
import mcjty.xnet.blocks.router.TileEntityRouter;
import mcjty.xnet.config.GeneralConfiguration;
import mcjty.xnet.init.ModBlocks;
import mcjty.xnet.logic.LogicTools;
import mcjty.xnet.multiblock.*;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Optional;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

public final class TileEntityWirelessRouter extends GenericEnergyReceiverTileEntity implements ITickable {

    public static final PropertyBool ERROR = PropertyBool.create("error");

    private boolean error = false;
    private int counter = 10;

    public TileEntityWirelessRouter() {
        super(GeneralConfiguration.wirelessRouterMaxRF, GeneralConfiguration.wirelessRouterRfPerTick);
    }

    @Override
    public void update() {
        if (!world.isRemote) {
            counter--;
            if (counter > 0) {
                return;
            }
            counter = 10;

            boolean err = false;
            if (world.getBlockState(pos.up()).getBlock() != ModBlocks.antennaBaseBlock) {
                err = true;
            }

            if (!err) {
                NetworkId networkId = findRoutingNetwork();
                if (networkId != null) {
                    LogicTools.consumers(getWorld(), networkId)
                            .forEach(consumerPos -> LogicTools.routers(getWorld(), consumerPos)
                                    .forEach(r -> publishChannels(r, networkId)));
                }
            }

            setError(err);
        }
    }

    private void publishChannels(TileEntityRouter router, NetworkId networkId) {
        // @todo bug: Multiple wireless routers have same channel name and end up with only one entry in the wireless data
        XNetWirelessChannels blobData = XNetWirelessChannels.getWirelessChannels(world);
        for (String channel : router.getPublishedChannels()) {
            blobData.publishChannel(channel, world.provider.getDimension(),
                    pos, networkId);
        }
    }

    public void addWirelessConnectors(Map<SidedConsumer, IConnectorSettings> connectors, String channelName, IChannelType type) {
        // @todo test if wireless router is active/no error/enough power
        XNetWirelessChannels.WirelessChannelInfo info = XNetWirelessChannels.getWirelessChannels(world).findChannel(channelName);
        if (info != null) {
            // @todo channels should match on type too!
            // @todo check if other side is chunkloaded
            GlobalCoordinate pos = info.getWirelessRouterPos();
            WorldServer otherWorld = DimensionManager.getWorld(pos.getDimension());
            TileEntity otherTE = otherWorld.getTileEntity(pos.getCoordinate());
            if (otherTE instanceof TileEntityWirelessRouter) {
                TileEntityWirelessRouter otherRouter = (TileEntityWirelessRouter) otherTE;
                LogicTools.routers(otherWorld, pos.getCoordinate()).
                        forEach(router -> router.addConnectorsFromConnectedNetworks(connectors, channelName, type));
            }
        }
    }


    private void setError(boolean err) {
        if (error != err) {
            error = err;
            markDirtyClient();
        }
    }
    private boolean inError() {
        return error;
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        boolean oldError = inError();

        super.onDataPacket(net, packet);

        if (getWorld().isRemote) {
            // If needed send a render update.
            if (oldError != inError()) {
                getWorld().markBlockRangeForRenderUpdate(getPos(), getPos());
            }
        }
    }

    @Nullable
    private NetworkId findRoutingNetwork() {
        WorldBlob worldBlob = XNetBlobData.getBlobData(getWorld()).getWorldBlob(getWorld());
        return LogicTools.routingConnectors(getWorld(), getPos())
                .findFirst()
                .map(worldBlob::getNetworkAt)
                .orElse(null);
    }


    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tagCompound) {
        tagCompound.setBoolean("error", error);
        return super.writeToNBT(tagCompound);
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound) {
        super.readFromNBT(tagCompound);
        error = tagCompound.getBoolean("error");
    }

    @Override
    public void writeRestorableToNBT(NBTTagCompound tagCompound) {
        super.writeRestorableToNBT(tagCompound);
    }

    @Override
    public void readRestorableFromNBT(NBTTagCompound tagCompound) {
        super.readRestorableFromNBT(tagCompound);
    }

    @Override
    @Optional.Method(modid = "theoneprobe")
    public void addProbeInfo(ProbeMode mode, IProbeInfo probeInfo, EntityPlayer player, World world, IBlockState blockState, IProbeHitData data) {
        super.addProbeInfo(mode, probeInfo, player, world, blockState, data);
        XNetBlobData blobData = XNetBlobData.getBlobData(world);
        WorldBlob worldBlob = blobData.getWorldBlob(world);
        Set<NetworkId> networks = worldBlob.getNetworksAt(data.getPos());
        for (NetworkId networkId : networks) {
            probeInfo.text(TextStyleClass.LABEL + "Network: " + TextStyleClass.INFO + networkId.getId());
            if (mode != ProbeMode.EXTENDED) {
                break;
            }
        }
        if (inError()) {
            probeInfo.text(TextStyleClass.ERROR + "Missing antenna or not enough power for channels!");
        } else {
//            probeInfo.text(TextStyleClass.LABEL + "Channels: " + TextStyleClass.INFO + getChannelCount());
        }

        if (mode == ProbeMode.DEBUG) {
            BlobId blobId = worldBlob.getBlobAt(data.getPos());
            if (blobId != null) {
                probeInfo.text(TextStyleClass.LABEL + "Blob: " + TextStyleClass.INFO + blobId.getId());
            }
            ColorId colorId = worldBlob.getColorAt(data.getPos());
            if (colorId != null) {
                probeInfo.text(TextStyleClass.LABEL + "Color: " + TextStyleClass.INFO + colorId.getId());
            }
        }
    }


    @Override
    public void onBlockBreak(World workd, BlockPos pos, IBlockState state) {
        super.onBlockBreak(workd, pos, state);
        if (!world.isRemote) {
            XNetBlobData blobData = XNetBlobData.getBlobData(world);
            WorldBlob worldBlob = blobData.getWorldBlob(world);
            worldBlob.removeCableSegment(pos);
            blobData.save();
        }
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            XNetBlobData blobData = XNetBlobData.getBlobData(world);
            WorldBlob worldBlob = blobData.getWorldBlob(world);
            NetworkId networkId = worldBlob.newNetwork();
            worldBlob.createNetworkProvider(pos, new ColorId(CableColor.ROUTING.ordinal() + 1), networkId);
            blobData.save();
        }
    }


    @Override
    public IBlockState getActualState(IBlockState state) {
        return state.withProperty(ERROR, inError());
    }

}