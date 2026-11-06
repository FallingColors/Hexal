package ram.talia.hexal.xplat;

import at.petrak.hexcasting.api.HexAPI;
import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.casting.math.HexPattern;
import at.petrak.hexcasting.common.msgs.IMessage;
import com.mojang.authlib.GameProfile;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ram.talia.hexal.api.everbook.Everbook;
import ram.talia.hexal.api.linkable.ILinkable;
import ram.talia.hexal.api.linkable.PlayerLinkstore;
import ram.talia.hexal.api.casting.wisp.WispCastingManager;
import ram.talia.hexal.common.entities.BaseCastingWisp;
import ram.talia.hexal.common.items.ItemRelay;

import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.stream.Collectors;

public interface IXplatAbstractions {
//    Platform platform();
//
//    boolean isModPresent(String id);

    boolean isPhysicalClient();
//
//    void initPlatformSpecific();

    void sendPacketToPlayer(ServerPlayer target, IMessage packet);

    void sendPacketNear(Vec3 pos, double radius, ServerLevel dimension, IMessage packet);

    void sendPacketTracking(Entity entity, IMessage packet);
    void sendPacketTracking(BlockEntity blockEntity, IMessage packet);
    void sendPacketTracking(BlockPos pos, ServerLevel dimension, IMessage packet);
    void sendPacketTracking(ChunkPos pos, ServerLevel dimension, IMessage packet);


    // https://github.com/VazkiiMods/Botania/blob/13b7bcd9cbb6b1a418b0afe455662d29b46f1a7f/Xplat/src/main/java/vazkii/botania/xplat/IXplatAbstractions.java#L157
    Packet<?> toVanillaClientboundPacket(IMessage message);

//    double getReachDistance(Player player);

    // Things that used to be caps

    boolean isInteractingAllowed(ServerLevel level, BlockPos pos, Direction direction, InteractionHand hand, @Nullable Player player);

    WispCastingManager getWispCastingManager(ServerPlayer caster);

    /**
     * Takes in a caster and wisp, and sets that caster's Seon (wisp that costs significantly less to maintain) to the
     * accepted wisp. The old Seon if one exists is unmarked.
     */
    void setSeon(ServerPlayer caster, @Nullable BaseCastingWisp wisp);

    @Nullable
    BaseCastingWisp getSeon(ServerPlayer caster);

    PlayerLinkstore getLinkstore(ServerPlayer player);

    PlayerLinkstore.RenderCentre getPlayerRenderCentre(Player player);
    
    void syncAddRenderLink(ILinkable sourceLink, ILinkable sinkLink, ServerLevel level);

    void syncRemoveRenderLink(ILinkable sourceLink, ILinkable sinkLink, ServerLevel level);

    void syncSetRenderLinks(ILinkable sourceLink, List<ILinkable> sinks, ServerLevel level);

    //region Transmission
    ILinkable getPlayerTransmittingTo (ServerPlayer player);
    void setPlayerTransmittingTo (ServerPlayer player, int to);
    void resetPlayerTransmittingTo (ServerPlayer player);
    //endregion

    //region Everbook
    Iota getEverbookIota(ServerPlayer player, HexPattern key);
    void setEverbookIota(ServerPlayer player, HexPattern key, Iota iota);
    
    void removeEverbookIota(ServerPlayer player, HexPattern key);
    
    void setFullEverbook(ServerPlayer player, Everbook everbook);
    
    List<Iota> getEverbookMacro (ServerPlayer player, HexPattern key);
    
    void toggleEverbookMacro (ServerPlayer player, HexPattern key);

    @Nullable UUID getBoundStorage(ServerPlayer player);

    void setBoundStorage(ServerPlayer player, @Nullable UUID storage);

    //endregion

    //region Geckolib
    @NotNull ItemRelay getItemRelay(Item.Properties properties);

    @NotNull Object getItemRelayRenderProvider();

    //endregion

    // Blocks

//    <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(BiFunction<BlockPos, BlockState, T> func,
//        Block... blocks);
//
//    boolean tryPlaceFluid(Level level, InteractionHand hand, BlockPos pos, ItemStack stack, Fluid fluid);


    // misc


//    boolean isCorrectTierForDrops(Tier tier, BlockState bs);
//
//    ResourceLocation getID(Block block);
//
//    ResourceLocation getID(Item item);
//
//    ResourceLocation getID(VillagerProfession profession);
//
//    Ingredient getUnsealedIngredient(ItemStack stack);
//
//    IXplatTags tags();
//
//    LootItemCondition.Builder isShearsCondition();
//
//    String getModName(String namespace);
//
    ServerPlayer getFakePlayer(ServerLevel level, UUID uuid);
    ServerPlayer getFakePlayer(ServerLevel level, GameProfile profile);

    boolean isBreakingAllowed(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player player);

    boolean isPlacingAllowed(ServerLevel level, BlockPos pos, ItemStack stack, @Nullable Player player);

    // interop

//    PehkuiInterop.ApiAbstraction getPehkuiApi();

    ///

    IXplatAbstractions INSTANCE = find();

    private static IXplatAbstractions find() {
        var providers = ServiceLoader.load(IXplatAbstractions.class).stream().toList();
        if (providers.size() != 1) {
            var names = providers.stream().map(p -> p.type().getName()).collect(Collectors.joining(",", "[", "]"));
            throw new IllegalStateException(
                "There should be exactly one IXplatAbstractions implementation on the classpath. Found: " + names);
        } else {
            var provider = providers.get(0);
            HexAPI.LOGGER.debug("Instantiating xplat impl: " + provider.type().getName());
            return provider.get();
        }
    }
}
