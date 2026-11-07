package ram.talia.hexal.interop.hexdebug

import at.petrak.hexcasting.api.HexAPI
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.ResolvedPatternType
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.utils.asTranslatedComponent
import at.petrak.hexcasting.common.lib.HexAttributes
import gay.`object`.hexdebug.core.api.HexDebugCoreAPI
import gay.`object`.hexdebug.core.api.debugging.StopReason
import gay.`object`.hexdebug.core.api.debugging.env.DebugEnvironment
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import ram.talia.hexal.api.casting.wisp.WispCastingManager
import ram.talia.hexal.common.entities.TickingWisp
import java.util.*

class WispDebugEnv(
    caster: ServerPlayer,
    private val wispUUID: UUID,
    private val ravenmind: Iota?
) : DebugEnvironment(caster) {
    var isPaused = false

    private val wisp: TickingWisp? get() = caster.serverLevel().getEntity(wispUUID) as? TickingWisp

    override fun resume(env: CastingEnvironment, image: CastingImage, resolutionType: ResolvedPatternType): Boolean {
        val wisp = wisp ?: return false

        WispCastingManager.WispCastResult(wisp, resolutionType.success, image).callback()
        if (!resolutionType.success) return false

        isPaused = false
        return true
    }

    override fun restart(threadId: Int) {
        val wisp = wisp ?: return

        // TODO: should we also reset other things here?
        wisp.setStack(listOf(EntityIota(wisp)))
        wisp.setRavenmind(ravenmind)
        wisp.currentMoveMultiplier = 1f
        wisp.clearTargetMovePos()
        isPaused = false

        HexDebugCoreAPI.INSTANCE.createDebugThread(this, threadId)
    }

    override fun terminate() {
        wisp?.discard()
    }

    override fun isCasterInRange(): Boolean {
        val ambitRadius = caster.getAttributeValue(HexAttributes.AMBIT_RADIUS)
        return wisp?.let { caster.distanceToSqr(it) <= ambitRadius * ambitRadius } ?: false
    }

    override fun getName(): Component {
        return "entity.hexal.wisp.ticking".asTranslatedComponent
    }

    override fun postStep(env: CastingEnvironment, image: CastingImage, reason: StopReason?) {
        // don't bother updating the stack/ravenmind if we just resumed (because we also do it in resume) or if the wisp is being terminated
        if (reason == null || reason == StopReason.TERMINATED) return

        val wisp = wisp ?: return

        // update these in postStep so that the increased cost for storing a truename applies immediately
        wisp.setStack(image.stack)
        wisp.setRavenmind(image.userData.getCompound(HexAPI.RAVENMIND_USERDATA))
    }
}
