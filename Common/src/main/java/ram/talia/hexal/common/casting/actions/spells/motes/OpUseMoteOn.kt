package ram.talia.hexal.common.casting.actions.spells.motes

import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.misc.MediaConstants
import at.petrak.hexcasting.api.casting.*
import at.petrak.hexcasting.api.casting.iota.EntityIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.api.utils.asTranslatedComponent
import at.petrak.hexcasting.ktxt.UseOnContext
import at.petrak.hexcasting.xplat.IXplatAbstractions.HEXCASTING
import com.mojang.authlib.GameProfile
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.getMote
import ram.talia.hexal.api.mediafieditems.MediafiedItemManager
import ram.talia.hexal.api.casting.castables.VarargSpellAction
import ram.talia.hexal.api.casting.eval.env.WispCastEnv
import ram.talia.hexal.api.casting.iota.MoteIota
import ram.talia.hexal.api.casting.mishaps.MishapNoBoundStorage
import ram.talia.hexal.xplat.IXplatAbstractions

object OpUseMoteOn : VarargSpellAction {
    override fun argc(stack: List<Iota>): Int {
        return if (stack[0] is EntityIota) 2 else 3
    }

    override fun execute(
            args: List<Iota>,
            argc: Int,
            env: CastingEnvironment
    ): SpellAction.Result {
        val item = args.getMote(0, argc) ?: throw MishapInvalidIota.of(args[0], argc - 1, "mote_empty")

        if ((item.count != 1L) && (item.tag != null))
            throw MishapInvalidIota(item, 0, "hexcasting.mishap.invalid_value.mote_with_nbt_not_size_one".asTranslatedComponent)

        if (argc == 2) {
            // Entity Version
            val target = args.getEntity(1, argc)

            env.assertEntityInRange(target)

            val storage = item.itemIndex.storage
            if (!MediafiedItemManager.isStorageLoaded(storage))
                throw MishapNoBoundStorage("storage_unloaded")

            return SpellAction.Result(
                EntityTargetSpell(target, item),
                HexalConfig.server.useItemOnCost,
                listOf(ParticleSpray.burst(Vec3.atCenterOf(target.onPos), 1.0))
            )
        }
        else {
            // Block Version
            val direction = args.getVec3(2, argc)
            val target = args.getBlockPos(1, argc)

            env.assertPosInRange(target)

            val storage = item.itemIndex.storage
            if (!MediafiedItemManager.isStorageLoaded(storage))
                throw MishapNoBoundStorage("storage_unloaded")

            return SpellAction.Result(
                BlockTargetSpell(target, direction, item),
                HexalConfig.server.useItemOnCost,
                listOf(ParticleSpray.burst(Vec3.atCenterOf(BlockPos(target)), 1.0))
            )
        }
    }

    private data class EntityTargetSpell(val entity: Entity, val item: MoteIota) : RenderedSpell {
        override fun cast(env: CastingEnvironment) {
            if (!env.isEntityInRange(entity))
                return

            val caster = env.caster ?: if (env is WispCastEnv)
                    IXplatAbstractions.INSTANCE.getFakePlayer(env.world, GameProfile(env.wisp.uuid, "[Wisp " + env.wisp.uuid.toString().substring(0, 4) + "]"))
                else
                    IXplatAbstractions.INSTANCE.getFakePlayer(env.world, HEXCASTING)

            val itemStack = item.record?.toStack(1) ?: return

            // Swap item in hand to the new stack
            val oldStack = caster.getItemInHand(env.castingHand)
            caster.setItemInHand(env.castingHand, itemStack)

            entity.interact(caster, InteractionHand.MAIN_HAND)

            // Swap back to the old item
            caster.setItemInHand(env.castingHand, oldStack)

            item.tag = itemStack.tag
            if (itemStack.isEmpty)
                item.removeItems(1)
        }
    }

    private data class BlockTargetSpell(val pos: BlockPos, val direction: Vec3, val item: MoteIota) : RenderedSpell {
        override fun cast(env: CastingEnvironment) {
            if (!env.canEditBlockAt(pos))
                return

            val itemStack = item.record?.toStack(1) ?: return

            val context = UseOnContext(
                env.world,
                env.caster,
                InteractionHand.MAIN_HAND,
                itemStack,
                BlockHitResult(Vec3.atCenterOf(pos), Direction.getNearest(direction.x, direction.y, direction.z), pos, false)
            )

            val isAllowed = IXplatAbstractions.INSTANCE.isPlacingAllowed(env.world, pos, itemStack, env.caster)
            if (!isAllowed)
                return
            itemStack.useOn(context).consumesAction()
            item.tag = itemStack.tag

            if (itemStack.isEmpty)
                item.removeItems(1)
        }
    }
}