package ram.talia.hexal.fabric.xplat;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.msgs.IMessage;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import ram.talia.hexal.common.network.MsgToggleMacroS2C;
import ram.talia.hexal.fabric.cc.CCWispCastingManager;
import ram.talia.hexal.fabric.cc.HexalCardinalComponents;
import ram.talia.hexal.fabric.client.items.ItemRelayRenderer;
import ram.talia.hexal.xplat.IXplatAbstractions;
import software.bernie.example.client.renderer.item.JackInTheBoxRenderer;
import software.bernie.geckolib.animatable.client.RenderProvider;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static at.petrak.hexcasting.xplat.IXplatAbstractions.HEXCASTING;

public class FabricXplatImpl implements IXplatAbstractions {
//    @Override
//    public Platform platform() {
//        return Platform.FABRIC;
//    }

    @Override
    public boolean isPhysicalClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }
//
//    @Override
//    public boolean isModPresent(String id) {
//        return FabricLoader.getInstance().isModLoaded(id);
//    }
//
//    @Override
//    public void initPlatformSpecific() {
//        if (this.isModPresent(HexInterop.Fabric.GRAVITY_CHANGER_API_ID)) {
//            GravityApiInterop.init();
//        }
//        if (this.isModPresent(HexInterop.Fabric.TRINKETS_API_ID)) {
//            TrinketsApiInterop.init();
//        }
//    }

//    @Override
//    public double getReachDistance(Player player) {
//        return ReachEntityAttributes.getReachDistance(player, 5.0);
//    }

    @Override
    public void sendPacketToPlayer(ServerPlayer target, IMessage packet) {
        ServerPlayNetworking.send(target, packet.getFabricId(), packet.toBuf());
    }

    @Override
    public void sendPacketNear(Vec3 pos, double radius, ServerLevel dimension, IMessage packet) {
        sendPacketToPlayers(PlayerLookup.around(dimension, pos, radius), packet);
    }

    @Override
    public void sendPacketTracking(Entity entity, IMessage packet) {
        sendPacketToPlayers(PlayerLookup.tracking(entity), packet);
    }

    @Override
    public void sendPacketTracking(BlockEntity blockEntity, IMessage packet) {
        sendPacketToPlayers(PlayerLookup.tracking(blockEntity), packet);
    }

    @Override
    public void sendPacketTracking(BlockPos pos, ServerLevel dimension, IMessage packet) {
        sendPacketToPlayers(PlayerLookup.tracking(dimension, pos), packet);
    }

    @Override
    public void sendPacketTracking(ChunkPos pos, ServerLevel dimension, IMessage packet) {
        sendPacketToPlayers(PlayerLookup.tracking(dimension, pos), packet);
    }

    private void sendPacketToPlayers(Collection<ServerPlayer> players, IMessage packet) {
        var pkt = ServerPlayNetworking.createS2CPacket(packet.getFabricId(), packet.toBuf());
        for (var p : players) {
            p.connection.send(pkt);
        }
    }

    @Override
    public Packet<?> toVanillaClientboundPacket(IMessage message) {
        return ServerPlayNetworking.createS2CPacket(message.getFabricId(), message.toBuf());
    }

    @Override
    public boolean isInteractingAllowed(ServerLevel level, BlockPos pos, Direction direction, InteractionHand hand, @Nullable Player player) {
        if (player == null)
            player = FakePlayer.get(level, HEXCASTING);
        return UseBlockCallback.EVENT.invoker()
                .interact(player, level, hand, new BlockHitResult(Vec3.atCenterOf(pos), direction, pos, true)) != InteractionResult.FAIL; // I think this is right but I'm not sure
    }

    @Override
    public WispCastingManager getWispCastingManager (ServerPlayer caster) {
        return HexalCardinalComponents.WISP_CASTING_MANAGER.get(caster).getManager();
    }

    @Override
    public void setSeon(ServerPlayer caster, @Nullable BaseCastingWisp wisp) {
        CCWispCastingManager manager = HexalCardinalComponents.WISP_CASTING_MANAGER.get(caster);
        BaseCastingWisp old = manager.getSeon();
        if (old != null)
            old.setSeon(false);
        if (wisp != null)
            wisp.setSeon(true);
        manager.setSeon(wisp);
    }

    @Override
    public @Nullable BaseCastingWisp getSeon(ServerPlayer caster) {
        return HexalCardinalComponents.WISP_CASTING_MANAGER.get(caster).getSeon();
    }

    @Override
    public PlayerLinkstore getLinkstore (ServerPlayer player) {
        return HexalCardinalComponents.PLAYER_LINKSTORE.get(player).getLinkstore();
    }

    @Override
    public PlayerLinkstore.RenderCentre getPlayerRenderCentre(Player player) {
        return HexalCardinalComponents.PLAYER_LINKSTORE.get(player).getRenderCentre();
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

    //region Transmitting
    @Override
    public ILinkable getPlayerTransmittingTo (ServerPlayer player) {
        return HexalCardinalComponents.PLAYER_LINKSTORE.get(player).getTransmittingTo();
    }
    
    @Override
    public void setPlayerTransmittingTo (ServerPlayer player, int to) {
        HexalCardinalComponents.PLAYER_LINKSTORE.get(player).setTransmittingTo(to);
    }
    
    @Override
    public void resetPlayerTransmittingTo (ServerPlayer player) {
        HexalCardinalComponents.PLAYER_LINKSTORE.get(player).resetTransmittingTo();
    }
    //endregion
    
    @Override
    public Iota getEverbookIota (ServerPlayer player, HexPattern key) {
        return HexalCardinalComponents.EVERBOOK.get(player).getIota(key, player.serverLevel());
    }
    
    @Override
    public void setEverbookIota (ServerPlayer player, HexPattern key, Iota iota) {
        HexalCardinalComponents.EVERBOOK.get(player).setIota(key, iota);
    }
    
    @Override
    public void removeEverbookIota (ServerPlayer player, HexPattern key) {
        HexalCardinalComponents.EVERBOOK.get(player).removeIota(key);
    }
    
    @Override
    public void setFullEverbook (ServerPlayer player, Everbook everbook) {
        HexalCardinalComponents.EVERBOOK.get(player).setFullEverbook(everbook);
    }
    
    @Override
    public List<Iota> getEverbookMacro (ServerPlayer player, HexPattern key) {
        return HexalCardinalComponents.EVERBOOK.get(player).getMacro(key, player.serverLevel());
    }
    
    @Override
    public void toggleEverbookMacro (ServerPlayer player, HexPattern key) {
        HexalCardinalComponents.EVERBOOK.get(player).toggleMacro(key);
        IXplatAbstractions.INSTANCE.sendPacketToPlayer(player, new MsgToggleMacroS2C(key));
    }

    @Override
    public @Nullable UUID getBoundStorage(ServerPlayer player) {
        return HexalCardinalComponents.BOUND_STORAGE.get(player).getStorage();
    }

    @Override
    public void setBoundStorage(ServerPlayer player, @Nullable UUID storage) {
        HexalCardinalComponents.BOUND_STORAGE.get(player).setStorage(storage);
    }

    @Override
    public @NotNull ItemRelay getItemRelay(Item.Properties properties) {
        return new FabricItemRelay(properties);
    }

    @Override
    public @NotNull Object getItemRelayRenderProvider() {
        return new RenderProvider() {
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
        return FakePlayer.get(level, profile);
    }

    @Override
    public boolean isBreakingAllowed(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player player) {
        if (player == null)
            player = FakePlayer.get(level, HEXCASTING);
        return PlayerBlockBreakEvents.BEFORE.invoker()
                .beforeBlockBreak(level, player, pos, state, level.getBlockEntity(pos));
    }

    @Override
    public boolean isPlacingAllowed(ServerLevel level, BlockPos pos, ItemStack stack, @Nullable Player player) {
        if (player == null)
            player = FakePlayer.get(level, HEXCASTING);
        ItemStack cached = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        var success = UseItemCallback.EVENT.invoker().interact(player, level, InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, cached);
        return success.getResult() == InteractionResult.PASS; // No other mod tried to consume this
    }

    //    @Override
//    public <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(BiFunction<BlockPos, BlockState, T> func,
//        Block... blocks) {
//        return FabricBlockEntityTypeBuilder.create(func::apply, blocks).build();
//    }
//
//    @Override
//    @SuppressWarnings("UnstableApiUsage")
//    public boolean tryPlaceFluid(Level level, InteractionHand hand, BlockPos pos, ItemStack stack, Fluid fluid) {
//        Storage<FluidVariant> target = FluidStorage.SIDED.find(level, pos, Direction.UP);
//        Storage<FluidVariant> emptyFrom = FluidStorage.ITEM.find(stack, ContainerItemContext.withInitial(stack));
//        return StorageUtil.move(emptyFrom, target, (f) -> true, FluidConstants.BUCKET, null) > 0;
//    }
//
//    @Override
//    public ResourceLocation getID(Block block) {
//        return Registry.BLOCK.getKey(block);
//    }
//
//    @Override
//    public ResourceLocation getID(Item item) {
//        return Registry.ITEM.getKey(item);
//    }
//
//    @Override
//    public ResourceLocation getID(VillagerProfession profession) {
//        return Registry.VILLAGER_PROFESSION.getKey(profession);
//    }
//
//    @Override
//    public Ingredient getUnsealedIngredient(ItemStack stack) {
//        return FabricUnsealedIngredient.of(stack);
//    }
//
//    private static CreativeModeTab TAB = null;
//
//    @Override
//    public CreativeModeTab getTab() {
//        if (TAB == null) {
//            TAB = FabricItemGroupBuilder.create(modLoc("creative_tab"))
//                .icon(HexItems::tabIcon)
//                .build();
//        }
//
//        return TAB;
//    }
//
//    // do a stupid hack from botania
//    private static List<ItemStack> stacks(Item... items) {
//        return Stream.of(items).map(ItemStack::new).toList();
//    }
//
//    private static final List<List<ItemStack>> HARVEST_TOOLS_BY_LEVEL = List.of(
//        stacks(Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_HOE, Items.WOODEN_SHOVEL),
//        stacks(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_HOE, Items.STONE_SHOVEL),
//        stacks(Items.IRON_PICKAXE, Items.IRON_AXE, Items.IRON_HOE, Items.IRON_SHOVEL),
//        stacks(Items.DIAMOND_PICKAXE, Items.DIAMOND_AXE, Items.DIAMOND_HOE, Items.DIAMOND_SHOVEL),
//        stacks(Items.NETHERITE_PICKAXE, Items.NETHERITE_AXE, Items.NETHERITE_HOE, Items.NETHERITE_SHOVEL)
//    );
//
//    @Override
//    public boolean isCorrectTierForDrops(Tier tier, BlockState bs) {
//        if (!bs.requiresCorrectToolForDrops()) {
//            return true;
//        }
//
//        int level = HexConfig.server()
//            .opBreakHarvestLevelBecauseForgeThoughtItWasAGoodIdeaToImplementHarvestTiersUsingAnHonestToGodTopoSort();
//        for (var tool : HARVEST_TOOLS_BY_LEVEL.get(level)) {
//            if (tool.isCorrectToolForDrops(bs)) {
//                return true;
//            }
//        }
//
//        return false;
//    }
//
//    @Override
//    public Item.Properties addEquipSlotFabric(EquipmentSlot slot) {
//        return new FabricItemSettings().equipmentSlot(s -> slot);
//    }
//
//    private static final IXplatTags TAGS = new IXplatTags() {
//        @Override
//        public TagKey<Item> amethystDust() {
//            return HexItemTags.create(new ResourceLocation("c", "amethyst_dusts"));
//        }
//
//        @Override
//        public TagKey<Item> gems() {
//            return HexItemTags.create(new ResourceLocation("c", "gems"));
//        }
//    };
//
//    @Override
//    public IXplatTags tags() {
//        return TAGS;
//    }
//
//    @Override
//    public LootItemCondition.Builder isShearsCondition() {
//        return AlternativeLootItemCondition.alternative(
//            MatchTool.toolMatches(ItemPredicate.Builder.item().of(Items.SHEARS)),
//            MatchTool.toolMatches(ItemPredicate.Builder.item().of(
//                HexItemTags.create(new ResourceLocation("c", "shears"))))
//        );
//    }
//
//    @Override
//    public String getModName(String namespace) {
//        if (namespace.equals("c")) {
//            return "Common";
//        }
//        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(namespace);
//        if (container.isPresent()) {
//            return container.get().getMetadata().getName();
//        }
//        return namespace;
//    }
//
//    @Override
//    public boolean isBreakingAllowed(Level world, BlockPos pos, BlockState state, Player player) {
//        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(world, player, pos, state, world.getBlockEntity(pos));
//    }
//
//    @Override
//    public boolean isPlacingAllowed(Level world, BlockPos pos, ItemStack blockStack, Player player) {
//        ItemStack cached = player.getMainHandItem();
//        player.setItemInHand(InteractionHand.MAIN_HAND, blockStack.copy());
//        var success = UseItemCallback.EVENT.invoker().interact(player, world, InteractionHand.MAIN_HAND);
//        player.setItemInHand(InteractionHand.MAIN_HAND, cached);
//        return success.getResult() == InteractionResult.PASS; // No other mod tried to consume this
//    }
}
