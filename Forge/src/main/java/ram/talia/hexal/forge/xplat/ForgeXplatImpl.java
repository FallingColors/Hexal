package ram.talia.hexal.forge.xplat;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.msgs.IMessage;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ram.talia.hexal.api.everbook.Everbook;
import ram.talia.hexal.api.linkable.ILinkable;
import ram.talia.hexal.api.linkable.PlayerLinkstore;
import ram.talia.hexal.api.casting.wisp.WispCastingManager;
import ram.talia.hexal.common.entities.BaseCastingWisp;
import ram.talia.hexal.common.items.ItemRelay;
import ram.talia.hexal.common.network.MsgAddRenderLinkS2C;
import ram.talia.hexal.common.network.MsgRemoveRenderLinkS2C;
import ram.talia.hexal.common.network.MsgSetRenderLinksAck;
import ram.talia.hexal.forge.client.items.ItemRelayRenderer;
import ram.talia.hexal.forge.eventhandlers.BoundStorageEventHandler;
import ram.talia.hexal.forge.eventhandlers.EverbookEventHandler;
import ram.talia.hexal.forge.eventhandlers.PlayerLinkstoreEventHandler;
import ram.talia.hexal.forge.eventhandlers.WispCastingMangerEventHandler;
import ram.talia.hexal.forge.network.ForgePacketHandler;
import ram.talia.hexal.xplat.IXplatAbstractions;
import software.bernie.example.client.renderer.item.JackInTheBoxRenderer;

import java.util.List;
import java.util.UUID;

import static at.petrak.hexcasting.xplat.IXplatAbstractions.HEXCASTING;

public class ForgeXplatImpl implements IXplatAbstractions {

	@Override
	public boolean isPhysicalClient() {
		return FMLLoader.getDist() == Dist.CLIENT;
	}

	@Override
	public void sendPacketToPlayer(ServerPlayer target, IMessage packet) {
		ForgePacketHandler.getNetwork().send(PacketDistributor.PLAYER.with(() -> target), packet);
	}
	
	@Override
	public void sendPacketNear(Vec3 pos, double radius, ServerLevel dimension, IMessage packet) {
		ForgePacketHandler.getNetwork().send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
						pos.x, pos.y, pos.z, radius * radius, dimension.dimension()
		)), packet);
	}

	@Override
	public void sendPacketTracking(Entity entity, IMessage packet) {
		ForgePacketHandler.getNetwork().send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet);
	}

	@Override
	public void sendPacketTracking(BlockEntity blockEntity, IMessage packet) {
		sendPacketTracking(blockEntity.getBlockPos(), (ServerLevel) blockEntity.getLevel(), packet);
	}

	@Override
	public void sendPacketTracking(BlockPos pos, ServerLevel dimension, IMessage packet) {
		ForgePacketHandler.getNetwork().send(PacketDistributor.TRACKING_CHUNK.with(() -> dimension.getChunkAt(pos)), packet);
	}

	@Override
	public void sendPacketTracking(ChunkPos pos, ServerLevel dimension, IMessage packet) {
		sendPacketTracking(pos.getWorldPosition(), dimension, packet);
	}

	@Override
	public Packet<?> toVanillaClientboundPacket(IMessage message) {
		return ForgePacketHandler.getNetwork().toVanillaPacket(message, NetworkDirection.PLAY_TO_CLIENT);
	}

	@Override
	public boolean isInteractingAllowed(ServerLevel level, BlockPos pos, Direction direction, InteractionHand hand, @Nullable Player player) {
		if (player == null)
			player = FakePlayerFactory.get(level, HEXCASTING);
		return !MinecraftForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(player, hand, pos, new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, true)));
	}

	@Override
	public WispCastingManager getWispCastingManager (ServerPlayer caster) {
		return WispCastingMangerEventHandler.getCastingManager(caster);
	}

	@Override
	public void setSeon(ServerPlayer caster, @Nullable BaseCastingWisp wisp) {
		WispCastingMangerEventHandler.setSeon(caster, wisp);
	}

	@Override
	public @Nullable BaseCastingWisp getSeon(ServerPlayer caster) {
		return WispCastingMangerEventHandler.getSeon(caster);
	}

	@Override
	public PlayerLinkstore getLinkstore (ServerPlayer player) {
		return PlayerLinkstoreEventHandler.getLinkstore(player);
	}

	@Override
	public PlayerLinkstore.RenderCentre getPlayerRenderCentre(Player player) {
		return PlayerLinkstoreEventHandler.getRenderCentre(player);
	}

	@Override
	public void syncAddRenderLink(ILinkable sourceLink, ILinkable sinkLink, ServerLevel level) {
		sendPacketTracking(BlockPos.containing(sourceLink.getPosition()), level, new MsgAddRenderLinkS2C(sourceLink, sinkLink));
	}
	
	@Override
	public void syncRemoveRenderLink(ILinkable sourceLink, ILinkable sinkLink, ServerLevel level) {
		sendPacketTracking(BlockPos.containing(sourceLink.getPosition()), level, new MsgRemoveRenderLinkS2C(sourceLink, sinkLink));
	}

	@Override
	public void syncSetRenderLinks(ILinkable sourceLink, List<ILinkable> sinks, ServerLevel level) {
		sendPacketTracking(BlockPos.containing(sourceLink.getPosition()), level, new MsgSetRenderLinksAck(sourceLink, sinks));
	}
	
	@Override
	public ILinkable getPlayerTransmittingTo (ServerPlayer player) {
		return PlayerLinkstoreEventHandler.getTransmittingTo(player);
	}
	
	@Override
	public void setPlayerTransmittingTo (ServerPlayer player, int to) {
		PlayerLinkstoreEventHandler.setTransmittingTo(player, to);
	}
	
	@Override
	public void resetPlayerTransmittingTo (ServerPlayer player) {
		PlayerLinkstoreEventHandler.resetTransmittingTo(player);
	}
	
	@Override
	public Iota getEverbookIota (ServerPlayer player, HexPattern key) {
		return EverbookEventHandler.getIota(player, key);
	}
	
	@Override
	public void setEverbookIota (ServerPlayer player, HexPattern key, Iota iota) {
		EverbookEventHandler.setIota(player, key, iota);
	}
	
	@Override
	public void removeEverbookIota (ServerPlayer player, HexPattern key) {
		EverbookEventHandler.removeIota(player, key);
	}
	
	@Override
	public void setFullEverbook (ServerPlayer player, Everbook everbook) {
		EverbookEventHandler.setEverbook(player, everbook);
	}
	
	@Override
	public List<Iota> getEverbookMacro (ServerPlayer player, HexPattern key) {
		return EverbookEventHandler.getMacro(player, key);
	}
	
	@Override
	public void toggleEverbookMacro (ServerPlayer player, HexPattern key) {
		EverbookEventHandler.toggleMacro(player, key);
	}

	@Override
	public @Nullable UUID getBoundStorage(ServerPlayer player) {
		return BoundStorageEventHandler.getBoundStorage(player);
	}

	@Override
	public void setBoundStorage(ServerPlayer player, @Nullable UUID storage) {
		BoundStorageEventHandler.setBoundStorage(player, storage);
	}

	@Override
	public @NotNull ItemRelay getItemRelay(Item.Properties properties) {
		return new ForgeItemRelay(properties);
	}

	@Override
    public @NotNull Object getItemRelayRenderProvider() {
        return new IClientItemExtensions() {
			private ItemRelayRenderer renderer;

			@Override
			public BlockEntityWithoutLevelRenderer getCustomRenderer() {
				if (this.renderer == null)
					this.renderer = new ItemRelayRenderer();

				return this.renderer;
			}
		};
    }

    @Override
	public ServerPlayer getFakePlayer(ServerLevel level, UUID uuid) {
		return getFakePlayer(level, new GameProfile(uuid, "[Hexal]"));
	}

	@Override
	public ServerPlayer getFakePlayer(ServerLevel level, GameProfile profile) {
		return FakePlayerFactory.get(level, profile);
	}

	public boolean isBreakingAllowed(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player player) {
		if (player == null) {
			player = FakePlayerFactory.get(level, HEXCASTING);
		}

		return !MinecraftForge.EVENT_BUS.post(new BlockEvent.BreakEvent(level, pos, state, player));
	}

	public boolean isPlacingAllowed(ServerLevel level, BlockPos pos, ItemStack stack, @Nullable Player player) {
		if (player == null) {
			player = FakePlayerFactory.get(level, HEXCASTING);
		}

		ItemStack cached = player.getMainHandItem();
		player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
		PlayerInteractEvent.RightClickBlock evt = ForgeHooks.onRightClickBlock(player, InteractionHand.MAIN_HAND, pos, new BlockHitResult(Vec3.atCenterOf(pos), Direction.DOWN, pos, true));
		player.setItemInHand(InteractionHand.MAIN_HAND, cached);
		return !evt.isCanceled();
	}
}
