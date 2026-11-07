package ram.talia.hexal.common.entities

import at.petrak.hexcasting.api.casting.eval.env.PlayerBasedCastEnv
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.common.lib.HexAttributes
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.casting.wisp.WispCastingManager
import ram.talia.hexal.api.nbt.SerialisedIotaList
import ram.talia.hexal.api.plus
import ram.talia.hexal.common.lib.HexalEntities

open class ProjectileWisp : BaseCastingWisp {
	var isAffectedByGravity = true

	constructor(entityType: EntityType<out BaseCastingWisp>, world: Level) : super(entityType, world)
	constructor(entityType: EntityType<out ProjectileWisp>, world: Level, pos: Vec3, vel: Vec3, caster: Player?, media: Long) : super(entityType, world, pos, caster, media) {
		deltaMovement = vel
	}
	constructor(world: Level, pos: Vec3, vel: Vec3, caster: Player?, media: Long) : super(HexalEntities.PROJECTILE_WISP, world, pos, caster, media) {
		deltaMovement = vel
	}

	override fun move() {
		if (isAffectedByGravity)
			deltaMovement += Vec3(0.0, -0.05, 0.0)

		// either [position] + [velocity] if there was nothing in between the two points of the
		// trace, or the collision point if there was.
		val endPos = traceAnyHit(position(), position() + deltaMovement)

		setPos(endPos)
	}

	// Seon wisps have the same max range as the caster.
	override fun maxSqrCastingDistance() = if (seon) {
        caster?.getAttributeValue(HexAttributes.AMBIT_RADIUS) ?:
		(PlayerBasedCastEnv.DEFAULT_AMBIT_RADIUS * PlayerBasedCastEnv.DEFAULT_AMBIT_RADIUS)
	} else { CASTING_RADIUS * CASTING_RADIUS }

	fun getHitResult(start: Vec3, end: Vec3): BlockHitResult = level().clip(ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this))

	protected fun findHitEntity(start: Vec3, end: Vec3): EntityHitResult? =
		ProjectileUtil.getEntityHitResult(
			level(),
			this,
			start,
			end,
			boundingBox.expandTowards(deltaMovement).inflate(1.0),
			this::canHitEntity
		)

	protected fun canHitEntity(entity: Entity): Boolean {
		return if (!entity.isSpectator && entity.isAlive && entity.isPickable) {
			caster == null || !caster!!.isPassengerOfSameVehicle(entity)
		} else {
			false
		}
	}

	fun traceAnyHit(start: Vec3, end: Vec3): Vec3 {
		return traceAnyHit(getHitResult(start, end), start, end)
	}

	fun traceAnyHit(raytraceResult: HitResult?, start: Vec3, end: Vec3): Vec3 {
		var tEnd = end


		if (raytraceResult != null && raytraceResult.type != HitResult.Type.MISS) {
			tEnd = raytraceResult.location
		}

		// get any entities in between the start location and tEnd, which is either the
		// first location on the line start-end intersecting a block, or end.
		val entityRaytraceResult = findHitEntity(start, tEnd)

		val tRaytraceResult = entityRaytraceResult ?: raytraceResult

		//TODO: Figure out best way to keep !ForgeEventFactory.onProjectileImpact(this, tRaytraceResult)
		if (tRaytraceResult != null && tRaytraceResult.type != HitResult.Type.MISS) {
			onHit(tRaytraceResult)
			hasImpulse = true
		}

		return tEnd
	}

	fun onHit(result: HitResult) {
		val type = result.type
		if (type == HitResult.Type.ENTITY) {
			onHitEntity((result as EntityHitResult))
		} else if (type == HitResult.Type.BLOCK) {
			onHitBlock((result as BlockHitResult))
		}
		if (type != HitResult.Type.MISS) {
			this.gameEvent(GameEvent.PROJECTILE_LAND, this.caster)
		}
	}
	fun onHitEntity(result: EntityHitResult) {
		setPos(result.location)
		if (level().isClientSide)
			playTrailParticles()
		else {
			val serStack = SerialisedIotaList(mutableListOf(EntityIota(this), EntityIota(result.entity)))
			scheduleCast(CASTING_SCHEDULE_PRIORITY, serHex, serStack, serRavenmind.getTag())
		}
	}

	fun onHitBlock(result: BlockHitResult) {
		setPos(result.location)
		if (level().isClientSide)
			playTrailParticles()
		else {
			val serStack = SerialisedIotaList(mutableListOf(EntityIota(this),
					Vec3Iota(Vec3.atCenterOf(result.blockPos))))
			scheduleCast(CASTING_SCHEDULE_PRIORITY, serHex, serStack, serRavenmind.getTag())
		}
	}

	override fun castCallback(result: WispCastingManager.WispCastResult) {
		super.castCallback(result)
		discard()
	}

	companion object {
		const val CASTING_SCHEDULE_PRIORITY = 0
		const val CASTING_RADIUS = 4.0
	}
}