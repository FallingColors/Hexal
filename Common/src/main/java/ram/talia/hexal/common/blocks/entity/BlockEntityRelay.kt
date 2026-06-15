package ram.talia.hexal.common.blocks.entity

import at.petrak.hexcasting.api.block.HexBlockEntity
import at.petrak.hexcasting.api.casting.asActionResult
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.api.utils.asCompound
import at.petrak.hexcasting.api.utils.getList
import at.petrak.hexcasting.api.utils.putCompound
import at.petrak.hexcasting.api.utils.putList
import at.petrak.hexcasting.common.lib.HexItems
import net.minecraft.Util
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.*
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.linkable.ClientLinkableHolder
import ram.talia.hexal.api.linkable.ILinkable
import ram.talia.hexal.api.linkable.ILinkable.LazyILinkableSet
import ram.talia.hexal.api.linkable.LinkableTypes
import ram.talia.hexal.api.linkable.ServerLinkableHolder
import ram.talia.hexal.common.blocks.BlockRelay
import ram.talia.hexal.common.lib.HexalBlockEntities
import software.bernie.geckolib.animatable.GeoBlockEntity
import software.bernie.geckolib.core.animatable.GeoAnimatable
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache
import software.bernie.geckolib.core.animation.*
import software.bernie.geckolib.core.`object`.PlayState
import software.bernie.geckolib.util.GeckoLibUtil
import java.util.*
import kotlin.math.min

class BlockEntityRelay(pos: BlockPos, val state: BlockState) : HexBlockEntity(HexalBlockEntities.RELAY, pos, state), ILinkable, ILinkable.IRenderCentre, GeoBlockEntity {
    val pos: BlockPos = pos.immutable()

    private val random = RandomSource.create()

    private var relayNetwork: RelayNetwork = RelayNetwork(this, mutableSetOf(this), mutableSetOf(), mutableSetOf())
    private var relaysLinkedDirectly: MutableSet<SerialisedBlockEntityRelay> = mutableSetOf()


    private val nonRelaysLinkedDirectly: LazyILinkableSet = LazyILinkableSet()
    private var mediaExchangersLinkedDirectly: LazyILinkableSet = LazyILinkableSet()

    fun setPigment(pigment: FrozenPigment, level: Level) = relayNetwork.setPigment(pigment, level.gameTime)

    fun serverTick() {
         checkLinks()

        nonRelaysLinkedDirectly.removeIf { it.shouldRemove() || !this.isInRange(it) }
        mediaExchangersLinkedDirectly.removeIf { it.shouldRemove() || !this.isInRange(it) }

        relayNetwork.tick()

        if (level != null && !level!!.isClientSide && level!!.gameTime % 20 == 0L) {
            relaysLinkedDirectly.filter { it.loadRelay(level) }.forEach { it.getRelay(level)?.let { it1 -> combineNetworks(it1) } }

            val newNonRelays = nonRelaysLinkedDirectly.tryLoad(level as ServerLevel)
            val newMediaExchangers = mediaExchangersLinkedDirectly.tryLoad(level as ServerLevel)
            relayNetwork.nonRelays.addAll(newNonRelays)
            relayNetwork.mediaExchangers.addAll(newMediaExchangers)
            relayNetwork.numNonRelays += newNonRelays.size
            relayNetwork.numMediaExchangers += newMediaExchangers.size
        }
    }

    fun clientTick() {
        renderLinks()
    }

    fun debug() {
        HexalAPI.LOGGER.debug("relay network: {}", relayNetwork)
    }

    private fun combineNetworks(other: BlockEntityRelay) {
        if (this.relayNetwork == other.relayNetwork)
            return

        this.relayNetwork.absorb(other.relayNetwork)

        this.relaysLinkedDirectly.add(other.toSerWrap())
        other.relaysLinkedDirectly.add(this.toSerWrap())
    }

    /**
     * Uses a depth first search to find all relays still connected to this. Any that are no longer connected to this
     * must be connected to other.
     */
    private fun findSeparateNetworks(other: BlockEntityRelay) {
        val unvisited = this.relayNetwork.relays.toMutableSet()
        unvisited.remove(this)

        val visited = mutableSetOf(this)
        // only add relays that are currently loaded to the frontier
        val frontier = this.relaysLinkedDirectly.mapNotNull { this.level?.let { level -> it.getRelay(level) } }.toMutableList()

        while (frontier.isNotEmpty()) {
            val next = frontier.removeFirst()
            if (next in visited)
                continue
            if (next == other) // if we've found an alternate path from this to other, clearly their networks are still connected
                return
            visited.add(next)
            unvisited.remove(next)
            // only add relays that are currently loaded to the frontier
            frontier.addAll(next.relaysLinkedDirectly.mapNotNull { this.level?.let { level -> it.getRelay(level) } }.filter { it !in visited })
        }

        // separate out the non-relays that are connected to this's new network vs other's new network
        val thisNetwork = makeNetwork(this, visited)
        val otherNetwork = makeNetwork(other, unvisited)

        // assign everything in the new network to contain the correct sets of things in and adjacent to the network. Do likewise for other's new network.
        visited.forEach {
            it.relayNetwork = thisNetwork
        }
        unvisited.forEach {
            it.relayNetwork = otherNetwork
        }
    }

    private fun makeNetwork(root: BlockEntityRelay, relays: MutableSet<BlockEntityRelay>): RelayNetwork {
        val (newNonRelaysLinked, newMediaExchangersLinked) = relays.fold(mutableSetOf<ILinkable>() to mutableSetOf<ILinkable>())
        { (nonRelays, mediaExchangers), relay ->
            nonRelays.addAll(relay.nonRelaysLinkedDirectly)
            mediaExchangers.addAll(relay.mediaExchangersLinkedDirectly)
            nonRelays to mediaExchangers
        }

        val network = RelayNetwork(root, relays, newNonRelaysLinked, newMediaExchangersLinked)
        network.setPigment(root.pigment(), root.level?.gameTime ?: 0L)

        return network
    }

    //region Linkable

    override val asActionResult: List<Iota>
        get() = pos.asActionResult

    private var cachedLinkableHolder: ServerLinkableHolder? = null
    private var serialisedLinkableHolder: CompoundTag? = null

    override val linkableHolder: ServerLinkableHolder?
        get() = cachedLinkableHolder ?: let {
                cachedLinkableHolder = (this.level as? ServerLevel)?.let { ServerLinkableHolder(this, it) }
                serialisedLinkableHolder?.let { cachedLinkableHolder?.readFromNbt(it)?.let { serialisedLinkableHolder = null } }
                cachedLinkableHolder
            }

    override fun owner(): UUID = UUID(0, relayNetwork.root.pos.asLong())

    override fun getLinkableType() = LinkableTypes.RELAY_TYPE

    override fun getPosition(): Vec3 = Vec3.atCenterOf(pos)

    override fun maxSqrLinkRange(): Double = MAX_SQR_LINK_RANGE

    override fun shouldRemove(): Boolean = this.isRemoved

    override fun currentMediaLevel(): Long = relayNetwork.computedAverageMedia

    override fun canAcceptMedia(other: ILinkable, otherMediaLevel: Long): Long = relayNetwork.canAcceptMedia(other, otherMediaLevel)

    override fun acceptMedia(other: ILinkable, sentMedia: Long) = relayNetwork.acceptMedia(other, sentMedia)

    override fun link(other: ILinkable, linkOther: Boolean) {
        super.link(other, linkOther)

        if (other is BlockEntityRelay) {
            // don't need to run this on both relays
            if (!linkOther)
                return
            // combine the networks of other and this.
            combineNetworks(other)
            this.setChanged()
            other.setChanged()
        } else {
            // add to list of non-relays linked to network.
            nonRelaysLinkedDirectly.add(other)
            relayNetwork.nonRelays.add(other)
            relayNetwork.numNonRelays += 1
            setChanged()
            if (other.currentMediaLevel() != -1L) {
                mediaExchangersLinkedDirectly.add(other)
                relayNetwork.mediaExchangers.add(other)
                relayNetwork.numMediaExchangers += 1
            }
        }
    }

    override fun unlink(other: ILinkable, unlinkOther: Boolean) {
        super.unlink(other, unlinkOther)

        if (other is BlockEntityRelay) {
            // don't need to run this on both relays
            if (!unlinkOther)
                return
            this.relaysLinkedDirectly.remove(other.toSerWrap())
            other.relaysLinkedDirectly.remove(this.toSerWrap())
            // uncombine the networks of other and this.
            findSeparateNetworks(other)
            this.setChanged()
            other.setChanged()
        } else {
            // remove from list of non-relays linked to network.
            nonRelaysLinkedDirectly.remove(other)
            relayNetwork.nonRelays.remove(other)
            relayNetwork.numNonRelays -= 1
            setChanged()
            if (other.currentMediaLevel() != -1L) {
                mediaExchangersLinkedDirectly.remove(other)
                relayNetwork.mediaExchangers.remove(other)
                relayNetwork.numMediaExchangers -= 1
            }
        }
    }

    fun disconnectAll() {
        for (relay in relaysLinkedDirectly)
            relay.getRelay(level)?.let { unlink(it) }
    }

    override fun receiveIota(sender: ILinkable, iota: Iota) {
        relayNetwork.nonRelays.forEach { if (it != sender) it.receiveIota(sender, iota) }
    }

    //endregion

    //region Linkable.IRenderCentre
    private var cachedClientLinkableHolder: ClientLinkableHolder? = null

    override val clientLinkableHolder: ClientLinkableHolder?
        get() = cachedClientLinkableHolder ?: let {
            cachedClientLinkableHolder = this.level?.let { if (it.isClientSide) ClientLinkableHolder(this, it, random) else null }
            cachedClientLinkableHolder
        }

    override fun renderCentre(other: ILinkable.IRenderCentre, recursioning: Boolean): Vec3 {
        return Vec3.atCenterOf(pos) + getBobberPosition()
    }

    override fun pigment(): FrozenPigment = relayNetwork.pigment

    //endregion

    //region IAnimatable
    private val instanceCache: AnimatableInstanceCache = GeckoLibUtil.createInstanceCache(this)

    override fun getAnimatableInstanceCache(): AnimatableInstanceCache = instanceCache

    override fun registerControllers(data: AnimatableManager.ControllerRegistrar) {
        data.add(AnimationController(this, "controller", 0, this::predicate))
    }



    private fun <E : GeoAnimatable> predicate(event: AnimationState<E>): PlayState {
        event.controller.setAnimation(
                RawAnimation.begin()
                    .then("animation.model.place", Animation.LoopType.PLAY_ONCE)
                    .then("animation.model.idle", Animation.LoopType.LOOP)
        )

        return PlayState.CONTINUE
    }

    private fun getBobberPosition(): Vec3 {
        val manager = instanceCache.getManagerForId<BlockEntityRelay>(0)
        val bobber = manager.boneSnapshotCollection["Bobber"] ?: return Vec3.ZERO
        return (bobber.offsetY + 10) / 16.0 * Vec3.atLowerCornerOf(state.getValue(BlockRelay.FACING).normal)
    }
    //endregion

    override fun loadModData(tag: CompoundTag) {
        if (tag.contains(TAG_PIGMENT))
            relayNetwork.pigment = FrozenPigment.fromNBT(tag.getCompound(TAG_PIGMENT))
        if (tag.contains(TAG_PIGMENT_TIME))
            relayNetwork.timeColouriserSet = tag.getLong(TAG_PIGMENT_TIME)
        if (tag.contains(TAG_LINKABLE_HOLDER))
            serialisedLinkableHolder = tag.getCompound(TAG_LINKABLE_HOLDER)
        if (tag.contains(TAG_RELAYS_LINKED_DIRECTLY))
            relaysLinkedDirectlyFromTag(tag.getList(TAG_RELAYS_LINKED_DIRECTLY, ListTag.TAG_LIST))
        if (tag.contains(TAG_NON_RELAYS_LINKED_DIRECTLY))
            nonRelaysLinkedDirectlyFromTag(tag.getList(TAG_NON_RELAYS_LINKED_DIRECTLY, ListTag.TAG_COMPOUND))
        if (tag.contains(TAG_SYNC_NETWORK_ROOT)) {
            val newNetwork = (level?.getBlockEntity(listTagToBlockPos(tag.getList(TAG_SYNC_NETWORK_ROOT, ListTag.TAG_INT))) as? BlockEntityRelay)?.relayNetwork
            if (newNetwork != null)
                this.relayNetwork = newNetwork
        }
    }

    override fun saveModData(tag: CompoundTag) {
        tag.put(TAG_PIGMENT, relayNetwork.pigment.serializeToNBT())
        tag.putLong(TAG_PIGMENT_TIME, relayNetwork.timeColouriserSet)
        tag.putCompound(TAG_LINKABLE_HOLDER, linkableHolder!!.writeToNbt())
        tag.putList(TAG_RELAYS_LINKED_DIRECTLY, relaysDirectlyLinkedToTag())
        tag.putList(TAG_NON_RELAYS_LINKED_DIRECTLY, nonRelaysLinkedDirectlyToTag())
    }

    override fun getUpdateTag(): CompoundTag {
        val tag = super.getUpdateTag()
        tag.putList(TAG_SYNC_NETWORK_ROOT, blockPosToListTag(relayNetwork.root.pos))
        return tag
    }

    private fun relaysDirectlyLinkedToTag(): ListTag {
        val out = ListTag()
        relaysLinkedDirectly.forEach { out.add(blockPosToListTag(it.pos)) }
        return out
    }

    private fun relaysLinkedDirectlyFromTag(tag: ListTag) {
        tag.forEach {
            relaysLinkedDirectly.add(SerialisedBlockEntityRelay(listTagToBlockPos(it as ListTag)))
        }
    }

    private fun nonRelaysLinkedDirectlyToTag(): ListTag {
        val out = ListTag()
        val unloadedMediaExchangers = mediaExchangersLinkedDirectly.getLazies().map { it.getUnloaded() }
        nonRelaysLinkedDirectly.getLazies().map {
            val tag = it.getUnloaded()
            tag.putBoolean(TAG_MEDIA_EXCHANGER, tag in unloadedMediaExchangers)
            out.add(tag)
        }
        return out
    }

    private fun nonRelaysLinkedDirectlyFromTag(tag: ListTag) {
        nonRelaysLinkedDirectly.clear()
        mediaExchangersLinkedDirectly.clear()

        tag.forEach {
            val ctag = it.asCompound
            val lazy = ILinkable.LazyILinkable.from(ctag)
            if (ctag.getBoolean(TAG_MEDIA_EXCHANGER))
                mediaExchangersLinkedDirectly.add(lazy)
            nonRelaysLinkedDirectly.add(lazy)
        }
    }

    private fun toSerWrap(): SerialisedBlockEntityRelay = SerialisedBlockEntityRelay(this)

    private data class SerialisedBlockEntityRelay(val pos: BlockPos) {
        constructor(relay: BlockEntityRelay) : this(relay.pos) {
            this.relay = relay
        }

        private var relay: BlockEntityRelay? = null

        fun getRelay(level: Level?): BlockEntityRelay? = relay ?: let {
            relay = level?.getBlockEntity(pos) as? BlockEntityRelay
            relay
        }

        fun loadRelay(level: Level?): Boolean = if (relay != null) false else getRelay(level) != null
    }

    private data class RelayNetwork(val root: BlockEntityRelay, val relays: MutableSet<BlockEntityRelay>, val nonRelays: MutableSet<ILinkable>, val mediaExchangers: MutableSet<ILinkable>) {
        var numNonRelays = nonRelays.size
        var numMediaExchangers = mediaExchangers.size
        var lastTickComputedAverageMedia = 0L
        var computedAverageMedia = 0L // average media among all ILinkables connected to the relay network.
            /**
             * sets computedAverageMedia to the average amount of media among all [ILinkable]s connected to the relay network, if it hasn't been called before this tick.
             */
            get() {
                if (lastTickComputedAverageMedia >= (root.level?.gameTime ?: 0))
                    return field
                if (numMediaExchangers == 0) {
                    field = 0
                    return 0
                }

                field = mediaExchangers.fold(0L to 0.0)
                    { (cum, n), curr -> (cum / (n+1) * n).toLong().addBounded((curr.currentMediaLevel() / (n+1)).toLong()) to (n+1) }.first
                return field
            }

        var timeColouriserSet = 0L
        var pigment: FrozenPigment = FrozenPigment(HexItems.DYE_PIGMENTS[DyeColor.PURPLE]?.let { ItemStack(it) }, Util.NIL_UUID)

        fun setPigment(pigment: FrozenPigment, time: Long) {
            this.pigment = pigment
            timeColouriserSet = time
            root.sync()
        }

        var lastTickAcceptedMedia = 0L
        val linkablesAcceptedFromThisTick: MutableSet<ILinkable> = mutableSetOf()

        fun canAcceptMedia(other: ILinkable, otherMediaLevel: Long): Long {
            if (lastTickAcceptedMedia < (root.level?.gameTime ?: 0L)) {
                linkablesAcceptedFromThisTick.clear()
                lastTickAcceptedMedia = root.level?.gameTime ?: 0L
            }
            if (other in linkablesAcceptedFromThisTick)
                return 0

            if (numMediaExchangers - 1 == 0)
                return 0
            val averageMediaWithoutOther = (computedAverageMedia * numMediaExchangers - otherMediaLevel) / (numMediaExchangers - 1)

            if (otherMediaLevel <= averageMediaWithoutOther)
                return 0

            return ((otherMediaLevel - averageMediaWithoutOther).mulBounded(HexalConfig.server.mediaFlowRateOverLink))
        }

        fun acceptMedia(other: ILinkable, sentMedia: Long) {
            var remainingMedia = sentMedia
            // distribute incoming media to all other linked objects
            for (mediaAcceptor in mediaExchangers.shuffled()) {
                if (other == mediaAcceptor)
                    continue

                val averageMediaWithoutAcceptor = (computedAverageMedia * numMediaExchangers - mediaAcceptor.currentMediaLevel()) / (numMediaExchangers - 1)
                val toSend = mediaAcceptor.canAcceptMedia(root, averageMediaWithoutAcceptor)
                mediaAcceptor.acceptMedia(root, min(toSend, remainingMedia))
                remainingMedia -= min(toSend, remainingMedia)
                if (remainingMedia <= 0)
                    break
            }
            // if there's any media left over, send it back where it came from
            if (remainingMedia > 0)
                other.acceptMedia(root, remainingMedia)

            linkablesAcceptedFromThisTick.add(other)
        }

        fun absorb(other: RelayNetwork) {
            if (this.timeColouriserSet < other.timeColouriserSet) {
                this.setPigment(other.pigment, other.timeColouriserSet)
            }

            this.relays.addAll(other.relays)
            this.nonRelays.addAll(other.nonRelays)
            this.mediaExchangers.addAll(other.mediaExchangers)
            this.numNonRelays += other.numNonRelays
            this.numMediaExchangers += other.numMediaExchangers

            other.relays.forEach { it.relayNetwork = this; it.sync() }
        }

        fun tick() {
            nonRelays.removeIf { it.shouldRemove().also { if (it) numNonRelays -= 1 } }
            mediaExchangers.removeIf { it.shouldRemove().also { if (it) numMediaExchangers -= 1 } }
        }
    }

    companion object {
        const val MAX_SQR_LINK_RANGE = 32.0*32.0

        const val TAG_PIGMENT = "hexal:pigment"
        const val TAG_PIGMENT_TIME = "hexal:pigment_time"
        const val TAG_LINKABLE_HOLDER = "hexal:linkable_holder"
        const val TAG_RELAYS_LINKED_DIRECTLY = "hexal:relays_linked_directly"
        const val TAG_NON_RELAYS_LINKED_DIRECTLY = "hexal:non_relays_linked_directly"
        const val TAG_MEDIA_EXCHANGER = "relay:media_exchanger"
        const val TAG_SYNC_NETWORK_ROOT = "relay:sync_network_root"

        private fun blockPosToListTag(pos: BlockPos): ListTag {
            val listTag = ListTag()
            listTag.add(IntTag.valueOf(pos.x))
            listTag.add(IntTag.valueOf(pos.y))
            listTag.add(IntTag.valueOf(pos.z))
            return listTag
        }

        private fun listTagToBlockPos(listTag: ListTag): BlockPos {
            val x = listTag.getInt(0)
            val y = listTag.getInt(1)
            val z = listTag.getInt(2)
            return BlockPos(x, y, z)
        }
    }
}