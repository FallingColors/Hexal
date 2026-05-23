package ram.talia.hexal.common.casting.actions.spells.motes

import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.*
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.Vec3Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.getMote
import ram.talia.hexal.api.casting.castables.VarargSpellAction
import ram.talia.hexal.api.casting.iota.MoteIota
import kotlin.math.min

/**
 * Returns Items from being Mediafied back into the world.
 */
object OpReturnMote : VarargSpellAction {
    override fun argc(stack: List<Iota>): Int {
        if (stack.isEmpty())
            return 2
        val top = stack[0]
        if (top is Vec3Iota)
            return 2
        return 3
    }

    override fun execute(args: List<Iota>, argc: Int, env: CastingEnvironment): SpellAction.Result {
        val item = args.getMote(0, argc) ?:
            throw MishapInvalidIota.of(args[0], 1, "mote_empty")
        val pos = args.getVec3(1, argc)

        val numToReturn = if (argc == 3) args.getInt(2, argc) else null

        env.assertVecInRange(pos)

        return SpellAction.Result(
                Spell(item, pos, numToReturn),
                HexalConfig.server.returnItemCost,
                listOf(ParticleSpray.burst(pos, 0.5))
        )
    }

    private data class Spell(val item: MoteIota, val pos: Vec3, val numToReturn: Int?) : RenderedSpell {
        override fun cast(env: CastingEnvironment) {
            val toDrop = item.getStacksToDrop(numToReturn?.let { min(it, HexalConfig.server.maxItemsReturned) } ?: HexalConfig.server.maxItemsReturned)

            for (stack in toDrop) {
                env.world.addFreshEntity(ItemEntity(env.world, pos.x, pos.y, pos.z, stack))
            }
        }
    }
}