package ram.talia.hexal.datagen

import at.petrak.paucal.api.datagen.PaucalLootTableSubProvider
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.loot.LootTable
import ram.talia.hexal.api.HexalAPI
import ram.talia.hexal.common.lib.HexalBlocks
import java.util.function.BiConsumer

class HexalLootTables : PaucalLootTableSubProvider(HexalAPI.MOD_ID) {
    override fun makeLootTables(blockTables: MutableMap<Block, LootTable.Builder>, lootTables: MutableMap<ResourceLocation, LootTable.Builder>) {
        dropSelf(blockTables, HexalBlocks.MEDIAFIED_STORAGE)
        dropSelf(blockTables, HexalBlocks.RELAY)
    }
}