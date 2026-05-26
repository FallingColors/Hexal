@file:Suppress("NAME_SHADOWING")

package ram.talia.hexal.common.casting.actions.spells.motes

import at.petrak.hexcasting.api.casting.SpellList
import at.petrak.hexcasting.api.casting.asActionResult
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.iota.NullIota
import at.petrak.hexcasting.api.casting.mishaps.MishapInvalidIota
import com.mojang.datafixers.util.Either
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.TransientCraftingContainer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.server.level.ServerPlayer
import ram.talia.hexal.api.casting.castables.UserDataConstMediaAction
import ram.talia.hexal.api.casting.iota.MoteIota
import ram.talia.hexal.api.casting.iota.MoteIota.TAG_TEMP_STORAGE
import ram.talia.hexal.api.casting.mishaps.MishapNoBoundStorage
import ram.talia.hexal.api.config.HexalConfig
import ram.talia.hexal.api.getMoteOrList
import ram.talia.hexal.api.mediafieditems.ItemRecord
import ram.talia.hexal.api.mediafieditems.MediafiedItemManager.getBoundStorage
import ram.talia.hexal.api.mediafieditems.MediafiedItemManager.isStorageLoaded
import ram.talia.hexal.api.mulBounded

/**
 * Automate crafting with hex casting! Takes a list of item iotas that will be crafted with, returns a list containing the results of the craft.
 * The list should be one of the following:
 * \[item\], \[row of items\], \[\[row of items\], ...\], where the first list in the outermost list is the top row
 * e.g.
 * if you wanted to craft a diamond pickaxe, which has the following recipe
 * D D D
 *   S
 *   S
 * you'd pass the list \[\[D, D, D\], \[null, S\], \[null, S\]\].
 */
object OpCraftMote : UserDataConstMediaAction {
    override val argc = 1
    override val mediaCost: Long
        get() = HexalConfig.server.craftItemCost

    override fun execute(args: List<Iota>, userData: CompoundTag, env: CastingEnvironment): List<Iota> {
        val input = args.getMoteOrList(0, argc) ?: return listOf<Iota>().asActionResult

        val storage = if (userData.contains(TAG_TEMP_STORAGE))
                userData.getUUID(TAG_TEMP_STORAGE)
            else
                (env.castingEntity as? ServerPlayer)?.let { getBoundStorage(it) }
            ?: throw MishapNoBoundStorage()

        if (!isStorageLoaded(storage))
            throw MishapNoBoundStorage("storage_unloaded")

        val griddedIotas = makeMoteIotaCraftingGrid(input)

        val container = TransientCraftingContainer(AutoCraftingMenu(), 3, 3)

        for ((idx, iota) in griddedIotas.withIndex()) {
            if (iota != null)
                iota.record?.toStack()?.let { container.setItem(idx, it) }
        }

        val (itemResult, remainingItems) = getCraftResult(container, env) ?: return emptyList<Iota>().asActionResult

        val timesToCraft = getMinCount(griddedIotas)

        val moteIotaResult = MoteIota.makeIfStorageLoaded(ItemRecord(itemResult.item, itemResult.tag, itemResult.count.toLong().mulBounded(timesToCraft)), storage) ?: return emptyList<Iota>().asActionResult
        val remainingMoteIotas = remainingItems.map { MoteIota.makeIfStorageLoaded(ItemRecord(it.item, it.tag, it.count.toLong().mulBounded(timesToCraft)), storage)!! }.toMutableList()

        for (item in griddedIotas) item?.removeItems(timesToCraft)

        remainingMoteIotas.add(0, moteIotaResult)
        return remainingMoteIotas.asActionResult
    }

    internal fun getCraftResult(container: TransientCraftingContainer, env: CastingEnvironment): Pair<ItemStack, List<ItemStack>>? {
        val recman = env.world.recipeManager
        val recipes = recman.getAllRecipesFor(RecipeType.CRAFTING)
        val recipe = recipes.find { it.matches(container, env.world) } ?: return null

        val itemResult = recipe.assemble(container, env.world.registryAccess())
        val remainingItems = recipe.getRemainingItems(container).filter { item -> !item.isEmpty }

        return itemResult to remainingItems
    }

    private fun makeMoteIotaCraftingGrid(input: Either<MoteIota, SpellList>): Array<MoteIota?> {
        val out = Array<MoteIota?>(9) { _ -> null }

        for ((idy, iota) in input.map({ listOf(IndexedValue(0, it)) }, { it.withIndex() })) {
            if (idy > 2) throw MishapInvalidIota.of(input.map({ it }, { ListIota(it) }), 0, "crafting_recipe")
            when (iota) {
                is MoteIota -> out[idy * 3] = iota.selfOrNull()
                is ListIota -> {
                    for ((idx, iota) in iota.list.withIndex()) {
                        when (iota) {
                            is MoteIota -> out[idy * 3 + idx] = iota.selfOrNull()
                            is NullIota -> out[idy * 3 + idx] = null
                            else -> throw MishapInvalidIota.of(input.map({ it }, { ListIota(it) }), 0, "crafting_recipe")
                        }

                    }
                }
                is NullIota -> out[idy * 3] = null
                else -> throw MishapInvalidIota.of(input.map({ it }, { ListIota(it) }), 0, "crafting_recipe")
            }
        }

        if (out.all { it == null })
            throw MishapInvalidIota.of(input.map({ it }, { ListIota(it) }), 0, "crafting_recipe")

        for (a in out.indices) {
            for (b in out.indices) {
                if (a != b && out[a] != null && out[a]?.itemIndex == out[b]?.itemIndex)
                    throw MishapInvalidIota.of(input.map({ it }, { ListIota(it) }), 0, "mote_duplicated")
            }
        }

        return out
    }

    private fun getMinCount(griddedIotas: Array<MoteIota?>): Long = griddedIotas.minOf { iota -> iota?.count ?: Long.MAX_VALUE }

    // from AE2 https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/9965a2fd4d3fbf9eadd0a0e7190e0812537f836e/src/main/java/appeng/menu/AutoCraftingMenu.java#L30
    internal class AutoCraftingMenu : AbstractContainerMenu(null, 0) {
        override fun quickMoveStack(player: Player, index: Int): ItemStack = ItemStack.EMPTY

        override fun stillValid(player: Player) = false
    }
}