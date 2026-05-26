package ram.talia.hexal.common.casting.actions.spells.motes

import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.asActionResult
import at.petrak.hexcasting.api.casting.castables.ConstMediaAction
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import net.minecraft.world.inventory.TransientCraftingContainer
import net.minecraft.world.item.ItemStack
import ram.talia.hexal.api.casting.iota.MoteIota
import ram.talia.hexal.api.getItemStackIotaOrMoteOrList
import ram.talia.hexal.api.mulBounded
import ram.talia.hexal.api.util.Anyone
import ram.talia.moreiotas.api.casting.iota.ItemStackIota

object OpCraftMotePreview : ConstMediaAction {
    override val argc = 1

    override fun execute(args: List<Iota>, env: CastingEnvironment): List<Iota> {
        val input = args.getItemStackIotaOrMoteOrList(0, OpCraftMote.argc) ?: return listOf<Iota>().asActionResult

        val griddedStacks = makeCraftingGrid(input)

        val container = TransientCraftingContainer(OpCraftMote.AutoCraftingMenu(), 3, 3)

        for ((idx, stack) in griddedStacks.withIndex()) {
            if (stack != null)
                container.setItem(idx, stack)
        }

        val (itemResult, remainingItems) = OpCraftMote.getCraftResult(container, env) ?: return emptyList<Iota>().asActionResult

        val timesToCraft = getMinCount(griddedStacks)

        val stackResult = ItemStackIota.createFiltered(itemResult.copyWithCount(itemResult.count.mulBounded(timesToCraft)))
        val remainingIotas = remainingItems.map { ItemStackIota.createFiltered(it.copyWithCount(it.count.mulBounded(timesToCraft))) }.toMutableList()

        remainingIotas.add(0, stackResult)
        return remainingIotas.asActionResult
    }

    private fun makeCraftingGrid(input: Anyone<ItemStackIota, MoteIota, SpellList>): Array<ItemStack?> {
        val out = Array<ItemStack?>(9) { _ -> null }

        for ((idy, iota) in input.flatMap({ listOf(IndexedValue(0, it)) }, { listOf(IndexedValue(0, it)) }, { it.withIndex() })) {
            if (idy > 2) throw MishapInvalidIota.of(input.flatMap({ it }, { it }, { ListIota(it) }), 0, "crafting_recipe")
            when (iota) {
                is ItemStackIota -> out[idy * 3] = iota.itemStack.copyWithCount(1)
                is MoteIota -> out[idy * 3] = iota.record?.toStack()
                is ListIota -> {
                    for ((idx, iota) in iota.list.withIndex()) {
                        when (iota) {
                            is ItemStackIota -> out[idy * 3 + idx] = iota.itemStack.copyWithCount(1)
                            is MoteIota -> out[idy * 3 + idx] = iota.record?.toStack()
                            is NullIota -> out[idy * 3 + idx] = null
                            else -> throw MishapInvalidIota.of(input.flatMap({ it }, { it }, { ListIota(it) }), 0, "crafting_recipe")
                        }

                    }
                }
                is NullIota -> out[idy * 3] = null
                else -> throw MishapInvalidIota.of(input.flatMap({ it }, { it }, { ListIota(it) }), 0, "crafting_recipe")
            }
        }

        if (out.all { it == null })
            throw MishapInvalidIota.of(input.flatMap({ it }, { it }, { ListIota(it) }), 0, "crafting_recipe")

        return out
    }

    private fun getMinCount(griddedStacks: Array<ItemStack?>): Int = griddedStacks.minOf { iota -> iota?.count ?: Int.MAX_VALUE }
}