package ram.talia.hexal.common.casting.actions.spells

import at.petrak.hexcasting.api.casting.ParticleSpray
import at.petrak.hexcasting.api.casting.RenderedSpell
import at.petrak.hexcasting.api.casting.castables.SpellAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.getBlockPos
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.MishapBadBlock
import at.petrak.hexcasting.api.casting.mishaps.MishapBadOffhandItem
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.BlockParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.getBlockTypeOrBlockItemStackOrBlockMote
import ram.talia.hexal.api.casting.iota.MoteIota
import ram.talia.hexal.api.casting.mishaps.MishapItemNotFound
import ram.talia.hexal.api.util.Anyone

object OpPlaceType : SpellAction {
    override val argc = 2

    override fun execute(args: List<Iota>, env: CastingEnvironment): SpellAction.Result {
        val block = args.getBlockTypeOrBlockItemStackOrBlockMote(0, argc) ?:
            throw MishapInvalidIota.ofType(args[0], 1, "type.block.able")
        val pos = args.getBlockPos(1, argc)

        env.assertVecInRange(pos.center)

        // Mishap if pos already contains a block that can't be replaced
        val blockHit = BlockHitResult(
                Vec3.atCenterOf(pos), env.caster?.direction ?: Direction.UP, pos, false
        )
        val itemUseCtx = UseOnContext(env.world, env.caster, env.castingHand, env.caster?.mainHandItem ?: ItemStack(Items.COBBLESTONE), blockHit)
        val placeContext = BlockPlaceContext(itemUseCtx)

        val worldState = env.world.getBlockState(pos)
        if (!worldState.canBeReplaced(placeContext))
            throw MishapBadBlock.of(pos, "replaceable")

        // TODO: queryForMatchingStack() only searches the hotbar. This should be changed either to use a custom
        //       function once hex 0.11.4 makes getUsableStacks() public, or to specify the full inventory once
        //       https://github.com/FallingColors/HexMod/issues/1052 gets implemented.
        val placeeStack = block.flatMap(
            { block -> env.queryForMatchingStack { it.item is BlockItem && (it.item as BlockItem).block == block }?.copy() },
            { itemStack -> env.queryForMatchingStack { ItemStack.isSameItemSameTags(it, itemStack) }?.copy() },
            { itemIota -> if (itemIota.item is BlockItem) itemIota.record?.toStack()?.takeUnless { it.isEmpty } else null }
        )
        if (placeeStack == null) {
            if (block.isC) throw MishapInvalidIota.of(args[0], 1, "placeable")
            else throw MishapItemNotFound(args[0])
        }

        return SpellAction.Result(
                Spell(pos, placeeStack, block),
                HexalConfig.server.placeTypeCost,
                listOf(ParticleSpray.cloud(Vec3.atCenterOf(pos), 1.0))
        )
    }

    private data class Spell(val pos: BlockPos, val placeeStack: ItemStack, val blockOrMoteIota: Anyone<Block, ItemStack, MoteIota>) : RenderedSpell {
        override fun cast(env: CastingEnvironment) {
            val caster = env.caster

            val blockHit = BlockHitResult(
                    Vec3.atCenterOf(pos), caster?.direction ?: Direction.NORTH, pos, false
            )

            val bstate = env.world.getBlockState(pos)

            if (!IXplatAbstractions.INSTANCE.isPlacingAllowed(env.world, pos, placeeStack, env.caster))
                return

            if (placeeStack.isEmpty)
                return

            // https://github.com/VazkiiMods/Psi/blob/master/src/main/java/vazkii/psi/common/spell/trick/block/PieceTrickPlaceBlock.java#L143
            val spoofedStack = placeeStack.copy()

            // we temporarily give the player the stack, place it using mc code, then give them the old stack back.
            spoofedStack.count = 1

            val itemUseCtx = UseOnContext(env.world, caster, env.castingHand, spoofedStack, blockHit)
            val placeContext = BlockPlaceContext(itemUseCtx)
            if (!bstate.canBeReplaced(placeContext))
                return

            if (blockOrMoteIota.isA && !env.withdrawItem({ ItemStack.isSameItem(it, placeeStack) }, 1, false))
                return

            val res = spoofedStack.useOn(placeContext)

            if (res == InteractionResult.FAIL)
                return

            blockOrMoteIota.map(
                { env.withdrawItem({ ItemStack.isSameItem(it, placeeStack) }, 1, true) },
                { env.withdrawItem({ ItemStack.isSameItemSameTags(it, placeeStack) }, 1, true) },
                { it.removeItems(1) } )

            env.world.playSound(
                env.caster,
                pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                bstate.soundType.placeSound, SoundSource.BLOCKS, 1.0f,
                1.0f + (Math.random() * 0.5 - 0.25).toFloat()
            )
            val particle = BlockParticleOption(ParticleTypes.BLOCK, bstate)
            env.world.sendParticles(
                particle, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(),
                4, 0.1, 0.2, 0.1, 0.1
            )
        }
    }
}