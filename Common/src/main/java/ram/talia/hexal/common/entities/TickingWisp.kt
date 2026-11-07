package ram.talia.hexal.common.entities

import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.utils.hasByte
import at.petrak.hexcasting.api.utils.hasFloat
import at.petrak.hexcasting.common.lib.HexAttributes
import gay.`object`.hexdebug.core.api.HexDebugCoreAPI
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.network.chat.Component
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.casting.wisp.WispCastingManager
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.minus
import ram.talia.hexal.api.nbt.SerialisedIotaList
import ram.talia.hexal.api.plus
import ram.talia.hexal.api.times
import ram.talia.hexal.common.lib.HexalEntities
import ram.talia.hexal.interop.hexdebug.WispDebugEnv
import java.lang.Double.min
import java.util.UUID

class TickingWisp : BaseCastingWisp {
	override val shouldComplainNotEnoughMedia = false

	private var serStack: SerialisedIotaList = SerialisedIotaList()

	fun setStack(iotas: List<Iota>) {
		serStack.set(iotas.toMutableList())

		stackNumTrueNames = 0
		for (entity in serStack.getReferencedEntities(level() as ServerLevel)) {
			if ((entity is Player) && (entity != caster)) {
				stackNumTrueNames++
			}
		}
	}
	override fun setRavenmind(iota: CompoundTag?) {
		super.setRavenmind(iota)

		ravenmindNumTrueNames = 0
		for (entity in serRavenmind.getReferencedEntities(level() as ServerLevel)) {
			if ((entity is Player) && (entity!= caster)) {
				ravenmindNumTrueNames++
			}
		}
	}

	override fun setRavenmind(iota: Iota?) {
		super.setRavenmind(iota)

		ravenmindNumTrueNames = 0
		for (entity in serRavenmind.getReferencedEntities(level() as ServerLevel)) {
			if ((entity is Player) && (entity!= caster)) {
				ravenmindNumTrueNames++
			}
		}
	}

	var currentMoveMultiplier: Float
		get() = entityData.get(CURRENT_MOVE_MULTIPLIER)
		set(value) {
			if (value > entityData.get(MAXIMUM_MOVE_MULTIPLIER))
				entityData.set(MAXIMUM_MOVE_MULTIPLIER, value)
			entityData.set(CURRENT_MOVE_MULTIPLIER, value)
		}

	val maximumMoveMultiplier: Float
		get() = entityData.get(MAXIMUM_MOVE_MULTIPLIER)

	private var debugSessionId: UUID? = null

	val isDebugging: Boolean
		get() = debugSessionId != null

	private val isDebuggingAndPaused: Boolean
		get() = isDebugging && getDebugEnv()?.isPaused != false

	fun getDebugEnv(): WispDebugEnv? {
		val debugSessionId = debugSessionId ?: return null
		val caster = caster as? ServerPlayer ?: return null
		return HexDebugCoreAPI.INSTANCE.getDebugEnv(caster, debugSessionId) as? WispDebugEnv
	}

	fun setDebugEnv(debugEnv: WispDebugEnv) {
		debugSessionId = debugEnv.sessionId
	}

	constructor(entityType: EntityType<out BaseCastingWisp>, world: Level) : super(entityType, world)
	constructor(
		entityType: EntityType<out TickingWisp>,
		world: Level,
		pos: Vec3,
		caster: Player?,
		media: Long,
	) : super(entityType, world, pos, caster, media) {
		setTargetMovePos(pos)
	}

	constructor(world: Level, pos: Vec3, caster: Player?, media: Long) : super(HexalEntities.TICKING_WISP, world, pos, caster, media) {
		setTargetMovePos(pos)
	}

	init {
		serStack.set(mutableListOf(EntityIota(this)))
	}

	override fun transmittingTargetReturnDisplay(): List<Component> {
		if (level().isClientSide)
			throw Exception("TickingWisp.transmittingTargetReturnDisplay should only be called on server.") // TODO
		return serStack.getIotas(level() as ServerLevel).map(Iota::display)
	}

	//region Trueplayer handling stuff
	private var stackNumTrueNames: Int = 0
		set(value) { field = if (value >= 0) value else 0 }
	private var ravenmindNumTrueNames: Int = 0
		set(value) { field = if (value >= 0) value else 0 }

	override fun tick() {
		if (firstTick && !level().isClientSide) {
			stackNumTrueNames = 0
			for (entity in serStack.getReferencedEntities(level() as ServerLevel)) {
				if ((entity is Player) && (entity != caster)) {
					stackNumTrueNames++
				}
			}

			ravenmindNumTrueNames = 0
			for (entity in serRavenmind.getReferencedEntities(level() as ServerLevel)) {
				if ((entity is Player) && (entity!= caster)) {
                    ravenmindNumTrueNames++
				}
			}
		}

		// if the debug session has ended, destroy the wisp
		// TODO: this can result in a zombie session when the player dies (https://github.com/object-Object/HexDebug/issues/64)
		if (isDebugging && caster != null && getDebugEnv() == null) {
			discard()
		}

		super.tick()
	}

	override fun wispNumContainedPlayers(): Int = super.wispNumContainedPlayers() + stackNumTrueNames + ravenmindNumTrueNames
	//endregion

	override val normalCostPerTick =  HexalConfig.server.tickingWispUpkeepPerTick

	override fun childTick() {
//		HexalAPI.LOGGER.info("ticking wisp $uuid childTick called, caster is $caster")
		if (level().isClientSide) return

		// clear entities that have been removed from the world at least once per second
		// to prevent any memory leak type errors
		if (level().gameTime % 20 == 0L) {
			serStack.refreshIotas(level() as ServerLevel)
		}

		scheduleCast(CASTING_SCHEDULE_PRIORITY, serHex, serStack, serRavenmind.getTag())
	}

	override fun move() {
		if (reachedTargetPos() || isDebuggingAndPaused) // also checks if within close enough distance of target.
			return

		val currentTarget = getTargetMovePosRaw()
		val diffVec = currentTarget - position()
		val sqrDist = diffVec.lengthSqr()

		// smoothly slow down as it approaches the goal
		val distToStep = currentMoveMultiplier * BASE_MAX_SPEED_PER_TICK * sqrDist / (sqrDist + SCALE)

		// multiplied by min(distToStep, diffVec.length()) rather than just distToStep so that if the player sets the
		// move speed multiplier high enough to overshoot the target, the wisp instead jumps to the target.
		val step = maxMove(diffVec.normalize() * min(distToStep, diffVec.length()))

		setPos(position() + step)
	}

	// Seon wisps have the same max range as the caster.
	override fun maxSqrCastingDistance() = if (seon) {
		caster?.getAttributeValue(HexAttributes.AMBIT_RADIUS) ?:
		(PlayerBasedCastEnv.DEFAULT_AMBIT_RADIUS * PlayerBasedCastEnv.DEFAULT_AMBIT_RADIUS)
	} else { CASTING_RADIUS * CASTING_RADIUS }

	override fun canScheduleCast(): Boolean {
		return super.canScheduleCast() && !isDebuggingAndPaused
	}

	override fun castCallback(result: WispCastingManager.WispCastResult) {
//		HexalAPI.LOGGER.info("ticking wisp $uuid had a cast successfully completed!")
		if (!result.cancelled) {
			setStack(result.endStack)
			setRavenmind(result.endRavenmind)
		}

		super.castCallback(result)
	}
	fun getTargetMovePos(): Vec3? = if (reachedTargetPos()) null else getTargetMovePosRaw()

	private fun getTargetMovePosRaw(): Vec3 =
			Vec3(entityData.get(TARGET_MOVE_POS_X).toDouble(),
			     entityData.get(TARGET_MOVE_POS_Y).toDouble(),
			     entityData.get(TARGET_MOVE_POS_Z).toDouble())

	fun setTargetMovePos(pos: Vec3) {
		entityData.set(HAS_TARGET_MOVE_POS, true)
		entityData.set(TARGET_MOVE_POS_X, pos.x.toFloat())
		entityData.set(TARGET_MOVE_POS_Y, pos.y.toFloat())
		entityData.set(TARGET_MOVE_POS_Z, pos.z.toFloat())
	}

	fun clearTargetMovePos() {
		entityData.set(HAS_TARGET_MOVE_POS, false)
	}

	fun reachedTargetPos(): Boolean {
		return if (!entityData.get(HAS_TARGET_MOVE_POS)) {
			true
		} else if ((getTargetMovePosRaw() - position()).lengthSqr() < 0.01) {
			setPos(getTargetMovePosRaw())
			clearTargetMovePos()
			true
		} else {
			false
		}
	}

	override fun remove(reason: RemovalReason) {
		if (reason.shouldDestroy()) {
			getDebugEnv()?.let { HexDebugCoreAPI.INSTANCE.removeDebugThread(it) }
		}
		super.remove(reason)
	}

	override fun readAdditionalSaveData(compound: CompoundTag) {
		super.readAdditionalSaveData(compound)

		when (val stackTag = compound.get(TAG_STACK)) {
			null -> serStack.set(mutableListOf())
			else -> serStack.set(stackTag as ListTag)
		}
		debugSessionId = when (compound.hasUUID(TAG_DEBUG_SESSION_ID)) {
			true -> compound.getUUID(TAG_DEBUG_SESSION_ID)
			false -> null
		}
		entityData.set(HAS_TARGET_MOVE_POS, when(compound.hasByte(TAG_HAS_TARGET_MOVE_POS)) {
			true -> compound.getBoolean(TAG_HAS_TARGET_MOVE_POS)
			false -> false
		})
		entityData.set(TARGET_MOVE_POS_X, when(compound.hasFloat(TAG_TARGET_MOVE_POS_X)) {
			true -> compound.getFloat(TAG_TARGET_MOVE_POS_X)
			false -> position().x.toFloat()
		})
		entityData.set(TARGET_MOVE_POS_Y, when(compound.hasFloat(TAG_TARGET_MOVE_POS_Y)) {
			true -> compound.getFloat(TAG_TARGET_MOVE_POS_Y)
			false -> position().y.toFloat()
		})
		entityData.set(TARGET_MOVE_POS_Z, when(compound.hasFloat(TAG_TARGET_MOVE_POS_Z)) {
			true -> compound.getFloat(TAG_TARGET_MOVE_POS_Z)
			false -> position().z.toFloat()
		})
		entityData.set(CURRENT_MOVE_MULTIPLIER, when(compound.hasFloat(TAG_CURRENT_MOVE_MULTIPLIER)) {
			true -> compound.getFloat(TAG_CURRENT_MOVE_MULTIPLIER)
			false -> 1f
		})
		entityData.set(MAXIMUM_MOVE_MULTIPLIER, when(compound.hasFloat(TAG_MAXIMUM_MOVE_MULTIPLIER)) {
			true -> compound.getFloat(TAG_MAXIMUM_MOVE_MULTIPLIER)
			false -> 1f
		})
	}

	override fun addAdditionalSaveData(compound: CompoundTag) {
		super.addAdditionalSaveData(compound)

		compound.put(TAG_STACK, serStack.getTag())
		debugSessionId?.let { compound.putUUID(TAG_DEBUG_SESSION_ID, it) }
		compound.putBoolean(TAG_HAS_TARGET_MOVE_POS, entityData.get(HAS_TARGET_MOVE_POS))
		compound.putFloat(TAG_TARGET_MOVE_POS_X, entityData.get(TARGET_MOVE_POS_X))
		compound.putFloat(TAG_TARGET_MOVE_POS_Y, entityData.get(TARGET_MOVE_POS_Y))
		compound.putFloat(TAG_TARGET_MOVE_POS_Z, entityData.get(TARGET_MOVE_POS_Z))
		compound.putFloat(TAG_CURRENT_MOVE_MULTIPLIER, entityData.get(CURRENT_MOVE_MULTIPLIER))
		compound.putFloat(TAG_MAXIMUM_MOVE_MULTIPLIER, entityData.get(MAXIMUM_MOVE_MULTIPLIER))
	}

	override fun defineSynchedData() {
		super.defineSynchedData()

		entityData.define(HAS_TARGET_MOVE_POS, false)
		entityData.define(TARGET_MOVE_POS_X, position().x.toFloat())
		entityData.define(TARGET_MOVE_POS_Y, position().y.toFloat())
		entityData.define(TARGET_MOVE_POS_Z, position().z.toFloat())
		entityData.define(CURRENT_MOVE_MULTIPLIER, 1f)
		entityData.define(MAXIMUM_MOVE_MULTIPLIER, 1f)
	}

	companion object {
		val HAS_TARGET_MOVE_POS: EntityDataAccessor<Boolean> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.BOOLEAN)
		val TARGET_MOVE_POS_Y: EntityDataAccessor<Float> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.FLOAT)
		val TARGET_MOVE_POS_Z: EntityDataAccessor<Float> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.FLOAT)
		val TARGET_MOVE_POS_X: EntityDataAccessor<Float> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.FLOAT)
		val CURRENT_MOVE_MULTIPLIER: EntityDataAccessor<Float> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.FLOAT)
		val MAXIMUM_MOVE_MULTIPLIER: EntityDataAccessor<Float> = SynchedEntityData.defineId(TickingWisp::class.java, EntityDataSerializers.FLOAT)

		const val TAG_STACK = "stack"
		const val TAG_DEBUG_SESSION_ID = "debug_session_id"
		const val TAG_HAS_TARGET_MOVE_POS = "has_target_move_pos"
		const val TAG_TARGET_MOVE_POS_X = "target_move_pos_x"
		const val TAG_TARGET_MOVE_POS_Y = "target_move_pos_y"
		const val TAG_TARGET_MOVE_POS_Z = "target_move_pos_z"
		const val TAG_CURRENT_MOVE_MULTIPLIER = "current_move_multiplier"
		const val TAG_MAXIMUM_MOVE_MULTIPLIER = "maximum_move_multiplier"

		const val CASTING_SCHEDULE_PRIORITY = -5
		const val CASTING_RADIUS = 8.0

		const val BASE_MAX_SPEED_PER_TICK = 6.0 / 20
		const val SCALE = 0.2
	}
}
