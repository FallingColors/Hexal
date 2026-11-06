package ram.talia.hexal.common.casting.actions.spells.motes

import at.petrak.hexcasting.api.casting.ParticleSpray
import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.getBlockPos
import at.petrak.hexcasting.api.casting.iota.Iota
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.casting.iota.MoteIota
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.mediafieditems.MediafiedItemManager
import ram.talia.hexal.common.blocks.BlockMediafiedStorage
import ram.talia.hexal.common.blocks.entity.BlockEntityMediafiedStorage
import ram.talia.hexal.xplat.IXplatAbstractions

class OpBindStorage(private val isTemporaryBinding: Boolean) : SpellAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): SpellAction.Result {
        val pos = args.getBlockPos(0, argc)

        env.assertVecInRange(pos.center)

        val storage = env.world.getBlockState(pos).block

        return SpellAction.Result(
            Spell(if (storage is BlockMediafiedStorage) pos else null, isTemporaryBinding),
            if (isTemporaryBinding) HexalConfig.server.bindTemporaryStorageCost else HexalConfig.server.bindStorageCost,
            listOf(ParticleSpray.burst(Vec3.atCenterOf(pos), 1.5))
        )
    }

    private data class Spell(val pos: BlockPos?, val isTemporaryBinding: Boolean) : RenderedSpell {
        override fun cast(env: CastingEnvironment) {
            throw IllegalStateException("call cast(env, image) instead.")
        }

        override fun cast(env: CastingEnvironment, image: CastingImage): CastingImage? {
            if (pos == null) {
                if (!isTemporaryBinding) {
                    val caster = env.caster ?: return null
                    MediafiedItemManager.setBoundStorage(caster, null)
                    return null
                } else {
                    val userData = image.userData.copy()
                    userData.remove(MoteIota.TAG_TEMP_STORAGE)
                    return image.copy(userData = userData)
                }
            }

            if (!env.canEditBlockAt(pos) || !IXplatAbstractions.INSTANCE.isInteractingAllowed(env.world, pos, Direction.UP, env.castingHand, env.caster))
                return null

            val storage = env.world.getBlockEntity(pos) as? BlockEntityMediafiedStorage ?: return null

            if (isTemporaryBinding) {
                val userData = image.userData.copy()
                userData.putUUID(MoteIota.TAG_TEMP_STORAGE, storage.uuid)
                return image.copy(userData = userData)
            }

            val caster = env.caster ?: return null
            MediafiedItemManager.setBoundStorage(caster, storage.uuid)
            return null
        }
    }
}
