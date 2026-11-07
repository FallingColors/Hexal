package ram.talia.hexal.common.entities

import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.api.utils.asCompound
import at.petrak.hexcasting.api.utils.getList
import at.petrak.hexcasting.api.utils.hasByte
import com.mojang.datafixers.util.Either
import net.minecraft.client.Minecraft
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.*
import ram.talia.hexal.api.HexalAPI
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.linkable.ILinkable
import ram.talia.hexal.api.nbt.SerialisedIotaList
import ram.talia.hexal.api.casting.eval.env.WispCastEnv
import ram.talia.hexal.api.casting.wisp.WispCastingManager
import ram.talia.hexal.api.casting.wisp.triggers.IWispTrigger
import ram.talia.hexal.api.casting.wisp.triggers.WispTriggerRegistry
import ram.talia.hexal.api.mulBounded
import ram.talia.hexal.api.nbt.SerialisedIota
import ram.talia.hexal.client.sounds.WispCastingSoundInstance
import ram.talia.hexal.common.blocks.entity.BlockEntityRelay
import ram.talia.hexal.common.lib.HexalSounds
import ram.talia.hexal.common.network.MsgWispCastSoundS2C
import ram.talia.hexal.xplat.IXplatAbstractions
import java.util.*
import kotlin.collections.ArrayDeque
import kotlin.math.min
import kotlin.math.pow


abstract class BaseCastingWisp(entityType: EntityType<out BaseCastingWisp>, world: Level) : BaseWisp(entityType, world) {
	open val shouldComplainNotEnoughMedia = true

	private var activeTrigger: IWispTrigger? = null
	private var soundInstance: WispCastingSoundInstance? = null

	var summonedChildThisCast = false

	private var casterUUID: UUID? = null

	var caster: Player? = null
		get() {
			return if (field != null && !field!!.isRemoved && !field!!.isDeadOrDying) {
				field
			} else if (casterUUID != null && level() is ServerLevel) {
				field = (level() as ServerLevel).getEntity(casterUUID!!) as? Player
				field
			} else {
				null
			}
		}
		set(value) {
			if (value != null) {
				casterUUID = value.uuid
				field = value
			}
		}

	override val isConsumable = true

	protected var serRavenmind: SerialisedIota = SerialisedIota()

	open fun setRavenmind(iota: CompoundTag?) {
		if (iota != null)
			serRavenmind.set(iota)
		else
			serRavenmind.set(NullIota())
	}

	open fun setRavenmind(iota: Iota?) {
		serRavenmind.set(iota ?: NullIota())
	}

	var seon: Boolean
		get() = entityData.get(SEON)
		set(value) = entityData.set(SEON, value)

	override fun fightConsume(consumer: Either<BaseCastingWisp, ServerPlayer?>): Boolean = consumer.map({ wisp ->
		wisp.caster != this.caster &&
		!whiteListTransferMedia.contains(wisp) &&
		(wisp.caster?.let { !whiteListTransferMedia.contains(IXplatAbstractions.INSTANCE.getLinkstore(it as ServerPlayer)) } ?: true)
	}, {
		it != this.caster &&
		(it == null ||
		!whiteListTransferMedia.contains(IXplatAbstractions.INSTANCE.getLinkstore(it)))
	})

	val serHex: SerialisedIotaList = SerialisedIotaList()

	fun setHex(iotas: MutableList<Iota>) {
		serHex.set(iotas)

		hexNumTrueNames = 0
		for (entity in serHex.getReferencedEntities(level() as ServerLevel)) {
			if ((entity is Player) && (entity!= caster)) {
				hexNumTrueNames++
			}
		}
	}

	private var scheduledCast: Boolean
		get() = entityData.get(SCHEDULED_CAST)
		set(value) = entityData.set(SCHEDULED_CAST, value)

	override fun get() = this

	constructor(entityType: EntityType<out BaseCastingWisp>, world: Level, pos: Vec3, caster: Player?, media: Long) : this(entityType, world) {
		setPos(pos)
		this.caster = caster
		@Suppress("LeakingThis")
		this.media = media
	}

	init {
	    serRavenmind.set(NullIota())
	}

	override fun tick() {
		super.tick()

		// clear entities that have been removed from the world at least once per second
		// to prevent any memory leak type errors
		if (!level().isClientSide && (tickCount % 20 == 0)) {
			serHex.refreshIotas(level() as ServerLevel)
            serRavenmind.refreshIota(level() as ServerLevel)
			tryLoadTransferMediaFilters()
		}

		// if media is <= 0 or caster uuid is missing, destroy the wisp; otherwise, continue processing
		if (media <= 0 || casterUUID == null) {
			discard()
		}

//		HexalAPI.LOGGER.info("wisp $uuid ticked and ${if (scheduledCast) "does" else "doesn't"} have a cast scheduled.")

		oldPos = position()

		if (!scheduledCast && caster != null) {
			if (!level().isClientSide) {
				deductMedia()
				sendMediaToNeighbours()
			}

			childTick()
			move()
		}
		tryCheckInsideBlocks() // let the nether portal know if this wisp is inside it.

		// TODO: move all this into BaseWisp
		if (level().isClientSide) {
			val colouriser = FrozenPigment.fromNBT(entityData.get(PIGMENT))
			playWispParticles(colouriser)
			playTrailParticles(colouriser)
			clientLinkableHolder!!.renderLinks()
		}
	}


	/**
	 * Called in [tick], expected to reduce the amount of [media] remaining in the wisp.
	 */
	fun deductMedia() {
		var cost = when(canScheduleCast()) {
			true  -> normalCostPerTick
			false -> untriggeredCostPerTick
		}
		cost += HexalConfig.server.linkUpkeepPerTick * numLinked()

		HexalAPI.LOGGER.debug("Num contained players: ${wispNumContainedPlayers()}")
		cost = (cost * HexalConfig.server.storingPlayerCostScaleFactor.pow(wispNumContainedPlayers().toDouble())).toLong()

		if (seon)
			cost = (cost / HexalConfig.server.seonDiscountFactor).toLong()
		media -= cost
	}

	//region Trueplayer handling stuff
	private var receivedIotasNumTrueNames: Int = 0
		set(value) { field = if (value >= 0) value else 0 }
	private var hexNumTrueNames: Int = 0
		set(value) { field = if (value >= 0) value else 0 }

	open fun wispNumContainedPlayers(): Int = receivedIotasNumTrueNames + hexNumTrueNames

	override fun owner(): UUID = casterUUID ?: uuid

	override fun receiveIota(sender: ILinkable, iota: Iota) {
		super.receiveIota(sender, iota)
		receivedIotasNumTrueNames += countTrueNamesInIota(iota, caster)
	}

	override fun nextReceivedIota(): Iota {
		val iota = super.nextReceivedIota()
		receivedIotasNumTrueNames -= countTrueNamesInIota(iota, caster)
		return iota
	}

	override fun clearReceivedIotas() {
		super.clearReceivedIotas()
		receivedIotasNumTrueNames = 0
	}

	fun countTrueNamesInIota(iota: Iota, caster: Player?): Int {
		val poolToSearch = ArrayDeque<Iota>()
		poolToSearch.addLast(iota)

		var numTrueNames = 0

		while (poolToSearch.isNotEmpty()) {
			val datumToCheck = poolToSearch.removeFirst()
			if (datumToCheck is EntityIota && datumToCheck.entity is Player && datumToCheck.entity != caster)
				numTrueNames += 1
			if (datumToCheck is ListIota)
				poolToSearch.addAll(datumToCheck.list)
		}

		return numTrueNames
	}
	//endregion

	open val normalCostPerTick: Long get() = HexalConfig.server.projectileWispUpkeepPerTick
	open val untriggeredCostPerTick: Long get() = (normalCostPerTick * HexalConfig.server.untriggeredWispUpkeepDiscount).toLong()

	private fun tryLoadTransferMediaFilters() {
		blackListTransferMedia.tryLoad(level() as ServerLevel)
		whiteListTransferMedia.tryLoad(level() as ServerLevel)
	}

	private fun sendMediaToNeighbours() {
		for (i in 0 until this.numLinked()) {
			val linked = this.getLinked(i) ?: continue
			if (shouldBlockTransfer(linked))
				continue

			val requested = linked.canAcceptMedia(this, this.media)

			if (requested > 0) {
				val sent = min(requested, this.media)
				this.media -= sent
				linked.acceptMedia(this, sent)
			}
		}
	}

	private val blackListTransferMedia: ILinkable.LazyILinkableSet = ILinkable.LazyILinkableSet()
	private val whiteListTransferMedia: ILinkable.LazyILinkableSet = ILinkable.LazyILinkableSet()

	fun addToBlackListTransferMedia(other: ILinkable) = blackListTransferMedia.add(other)
	fun addToWhiteListTransferMedia(other: ILinkable) = whiteListTransferMedia.add(other)
	fun removeFromBlackListTransferMedia(other: ILinkable) = blackListTransferMedia.remove(other)
	fun removeFromWhiteListTransferMedia(other: ILinkable) = whiteListTransferMedia.remove(other)

	fun blackListContains(other: ILinkable): Boolean = blackListTransferMedia.contains(other)
	fun whiteListContains(other: ILinkable): Boolean = whiteListTransferMedia.contains(other)

	private fun shouldBlockTransfer(other: ILinkable): Boolean {
		if (other is BlockEntityRelay) {
			// if the blacklist contains any relay in the target relay's network, block transfer
			for (blEntry in blackListTransferMedia)
				if (blEntry is BlockEntityRelay && blEntry.sameNetwork(other)) return true
			// if the whitelist contains any relay in the target relay's network, allow transfer
			for (wlEntry in whiteListTransferMedia)
				if (wlEntry is BlockEntityRelay && wlEntry.sameNetwork(other)) return false
			// if neither of the above apply, block transfer (relays must be manually whitelisted to allow transfer)
			return true
		}
		return blackListTransferMedia.contains(other) || (other.owner() != this.owner() && !whiteListTransferMedia.contains(other))
	}

	override fun currentMediaLevel(): Long = media

	override fun canAcceptMedia(other: ILinkable, otherMediaLevel: Long): Long {
		if (otherMediaLevel == -1L)
			return (Int.MAX_VALUE - this.media)
		if (shouldBlockTransfer(other))
			return 0
		if (otherMediaLevel <= this.media)
			return 0

		return ((otherMediaLevel - this.media).mulBounded(HexalConfig.server.mediaFlowRateOverLink))
	}

	override fun acceptMedia(other: ILinkable, sentMedia: Long) {
		media += sentMedia
	}

	/**
	 * Called in [tick] to execute other code that child classes may want to execute every tick; respects not executing if the wisp is waiting for a cast to be executed.
	 * Is called before [move]
	 */
	open fun childTick() {}

	/**
	 * Called in [tick], expected to update the Wisp's position. Is called after [childTick].
	 */
	abstract fun move()

	/**
	 * Called in [WispCastEnv] to determine the
	 * maximum range the wisp should be able to affect and make them able to affect things inside that range.
	 */
	abstract fun maxSqrCastingDistance(): Double

	override fun maxSqrLinkRange() = maxSqrCastingDistance()

	fun setTrigger(trigger: IWispTrigger) {
		activeTrigger = trigger
	}

	/**
	 * Returns true if there are no triggers limiting when the wisp can cast, false otherwise
	 */
	open fun canScheduleCast(): Boolean {
//		HexalAPI.LOGGER.info("active trigger is $activeTrigger, shouldRemove: ${activeTrigger?.shouldRemoveTrigger(this)}, shouldTrigger: ${activeTrigger?.shouldTrigger(this)}")
		if (activeTrigger?.shouldRemoveTrigger(this) == true)
			activeTrigger = null
		return activeTrigger?.shouldTrigger(this) ?: true
	}

	/**
	 * Schedules casting the hex passed as [hex], with the initial stack [initialStack] and initial ravenmind [initialRavenmind]. If a callback is needed (e.g. to save
	 * the results of the cast somewhere) a callback can be provided as [castCallback]. Returns whether the hex was successfully scheduled.
	 */
	fun scheduleCast(
			priority: Int,
			hex: SerialisedIotaList,
			initialStack: SerialisedIotaList,
			initialRavenmind: CompoundTag?,
	): Boolean {
		if (level().isClientSide || caster == null || !canScheduleCast())
			return false // return dummy data, not expecting anything to be done with it

		scheduledCast = true
		IXplatAbstractions.INSTANCE.getWispCastingManager(caster as ServerPlayer)
				.scheduleCast(this, priority, hex, initialStack, initialRavenmind)

		return scheduledCast
	}

	fun scheduleCastSound() {
		if (level().isClientSide)
			throw Exception("BaseWisp.scheduleCastSound should only be called on server.") // TODO
//		HexalAPI.LOGGER.debug("scheduling casting sound, level is $level")
		IXplatAbstractions.INSTANCE.sendPacketNear(position(), 32.0, level() as ServerLevel, MsgWispCastSoundS2C(this))
	}

	fun playCastSoundClient() {
		if (!level().isClientSide)
			throw Exception("BaseWisp.playCastSoundClient should only be called on client.") // TODO

//		HexalAPI.LOGGER.debug("playing casting sound, level is $level")
		if (soundInstance == null || soundInstance!!.isStopped) {
			soundInstance = WispCastingSoundInstance(this)
			Minecraft.getInstance().soundManager.play(soundInstance!!)
			HexalSounds.WISP_CASTING_START.playAt(level(), position(), .3f, 1f + (random.nextFloat() - 0.5f) * 0.2f, false)
		}

		soundInstance!!.keepAlive()
	}

	open fun castCallback(result: WispCastingManager.WispCastResult) {
		scheduledCast = false

		if (result.cancelled)
			return

		// the cast errored, delete the wisp
		if (!result.succeeded)
			discard()

//		if (result.makesCastSound)
//			scheduleCastSound()
	}

	override fun setDeltaMovement(dV: Vec3) {
		super.setDeltaMovement(dV)
		setLookVector(dV)
	}

	override fun remove(reason: RemovalReason) {
		if (reason.shouldDestroy() && this.seon && this.caster != null)
			IXplatAbstractions.INSTANCE.setSeon(this.caster!! as ServerPlayer, null)
		super.remove(reason)
	}

	override fun readAdditionalSaveData(compound: CompoundTag) {
		super.readAdditionalSaveData(compound)

		if (compound.hasUUID(TAG_CASTER)) {
			casterUUID = compound.getUUID(TAG_CASTER)
//			HexalAPI.LOGGER.info("loading wisp $uuid's casterUUID as $casterUUID")
		}

		when (val ravenmindTag = compound.get(TAG_RAVENMIND)) {
			null -> serRavenmind.set(NullIota())
			else -> serRavenmind.set(ravenmindTag as CompoundTag)
		}

		when (val hexTag = compound.get(TAG_HEX)) {
			null -> serHex.set(mutableListOf())
			else -> serHex.set(hexTag as ListTag)
		}

		activeTrigger = when (val activeTriggerTag = compound.get(TAG_ACTIVE_TRIGGER)) {
			null -> null
			else -> WispTriggerRegistry.fromNbt(activeTriggerTag.asCompound, level() as ServerLevel)
		}

		seon = if (compound.hasByte(TAG_SEON)) { compound.getBoolean(TAG_SEON) } else { false }

		blackListTransferMedia.set(compound.getList(TAG_BLACKLIST_MEDIA_TRANSFER, Tag.TAG_COMPOUND))
		whiteListTransferMedia.set(compound.getList(TAG_WHITELIST_MEDIA_TRANSFER, Tag.TAG_COMPOUND))
	}

	override fun addAdditionalSaveData(compound: CompoundTag) {
		super.addAdditionalSaveData(compound)

//		HexalAPI.LOGGER.info("saving wisp $uuid's caster $caster based on $casterUUID")

		if (casterUUID != null)
			compound.putUUID(TAG_CASTER, casterUUID!!)

//		HexalAPI.LOGGER.info("saving wisp $uuid's hex as $hexTag")
		compound.put(TAG_HEX, serHex.getTag())
		compound.put(TAG_RAVENMIND, serRavenmind.getTag())
		if (activeTrigger != null)
			compound.put(TAG_ACTIVE_TRIGGER, WispTriggerRegistry.wrapNbt(activeTrigger!!))
		compound.putBoolean(TAG_SEON, seon)

		compound.put(TAG_BLACKLIST_MEDIA_TRANSFER, blackListTransferMedia.getUnloaded())
		compound.put(TAG_WHITELIST_MEDIA_TRANSFER, whiteListTransferMedia.getUnloaded())
	}

	override fun defineSynchedData() {
		super.defineSynchedData()

		// defines the entry in SynchedEntityData associated with the EntityDataAccessor COLOURISER, and gives it a default value
//		HexalAPI.LOGGER.info("defineSynchedData for $uuid called!")
		entityData.define(SCHEDULED_CAST, false)
		entityData.define(SEON, false)
	}

	override fun getAddEntityPacket(): Packet<ClientGamePacketListener> {
		super.getAddEntityPacket() // called to call LinkableEntity.linkableHolder.syncAll()
		return ClientboundAddEntityPacket(this, caster?.id ?: 0)
	}

	override fun recreateFromPacket(packet: ClientboundAddEntityPacket) {
		super.recreateFromPacket(packet)
		val caster = level().getEntity(packet.data) as? Player
		if (caster != null) {
			this.caster = caster
		}
	}

	companion object {
		@JvmStatic
		val SCHEDULED_CAST: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BaseCastingWisp::class.java, EntityDataSerializers.BOOLEAN)
		@JvmStatic
		val SEON: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(BaseCastingWisp::class.java, EntityDataSerializers.BOOLEAN)


		const val TAG_CASTER = "caster"
		const val TAG_HEX = "hex"
		const val TAG_RAVENMIND = "ravenmind"
		const val TAG_ACTIVE_TRIGGER = "active_trigger"
		const val TAG_SEON = "seon"
		const val TAG_BLACKLIST_MEDIA_TRANSFER = "blacklist_media_transfer"
		const val TAG_WHITELIST_MEDIA_TRANSFER = "whitelist_media_transfer"
	}
}

