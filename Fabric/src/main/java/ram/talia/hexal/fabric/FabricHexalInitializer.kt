package ram.talia.hexal.fabric

import at.petrak.hexcasting.api.casting.math.HexDir
import at.petrak.hexcasting.api.casting.math.HexPattern
import at.petrak.hexcasting.common.lib.hex.HexActions
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import at.petrak.hexcasting.xplat.IXplatAbstractions
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.biome.v1.BiomeModifications
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors
import net.fabricmc.fabric.impl.biome.modification.BuiltInRegistryKeys
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess.RegistryEntry
import net.minecraft.core.registries.Registries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.GenerationStep
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import ram.talia.hexal.api.HexalAPI
import ram.talia.hexal.api.gates.GateManager
import ram.talia.hexal.api.gates.GateSavedData
import ram.talia.hexal.common.lib.*
import ram.talia.hexal.common.lib.HexalFeatures
import ram.talia.hexal.common.lib.hex.HexalActions
import ram.talia.hexal.common.lib.hex.HexalArithmetics
import ram.talia.hexal.common.lib.hex.HexalIotaTypes
import ram.talia.hexal.common.recipe.HexalRecipeSerializers
import ram.talia.hexal.common.recipe.HexalRecipeTypes
import ram.talia.hexal.fabric.interop.phantom.OpPhaseBlock
import ram.talia.hexal.fabric.network.FabricPacketHandler
import java.util.function.BiConsumer

object FabricHexalInitializer : ModInitializer {
    const val FILE_GATE_MANAGER = "hexal_gate_manager"

    override fun onInitialize() {
        HexalAPI.LOGGER.info("Hello Fabric World!")

        FabricHexalConfig.setup()
        FabricPacketHandler.initServerBound()

        initListeners()

        initRegistries()
    }

    private fun initListeners() {
        ServerLifecycleEvents.SERVER_STARTED.register {
            val savedData = it.overworld().dataStorage.computeIfAbsent(::GateSavedData, ::GateSavedData, FILE_GATE_MANAGER)
            savedData.setDirty()
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            val savedData = it.overworld().dataStorage.computeIfAbsent(::GateSavedData, ::GateSavedData, FILE_GATE_MANAGER)
            GateManager.shouldClearOnWrite = true
            savedData.setDirty()
        }
    }

    private fun initRegistries() {
        fabricOnlyRegistration()

        HexalFeatures.registerFeatures(bind(BuiltInRegistries.FEATURE))
        BiomeModifications.addFeature(
            BiomeSelectors.foundInOverworld(),
            GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            ResourceKey.create(Registries.PLACED_FEATURE, HexalAPI.modLoc("amethyst_slipway_geode"))
        )

        HexalSounds.registerSounds(bind(BuiltInRegistries.SOUND_EVENT))
        HexalBlocks.registerBlocks(bind(BuiltInRegistries.BLOCK))
        HexalBlocks.registerBlockItems(bind(BuiltInRegistries.ITEM))
        HexalItems.registerItems(bind(BuiltInRegistries.ITEM))
        HexalBlockEntities.registerBlockEntities(bind(BuiltInRegistries.BLOCK_ENTITY_TYPE))
        HexalEntities.registerEntities(bind(BuiltInRegistries.ENTITY_TYPE))

        HexalRecipeSerializers.registerSerializers(bind(BuiltInRegistries.RECIPE_SERIALIZER))
        HexalRecipeTypes.registerTypes(bind(BuiltInRegistries.RECIPE_TYPE))

        HexalIotaTypes.registerTypes(bind(HexIotaTypes.REGISTRY))
        HexalActions.register(bind(HexActions.REGISTRY))
        HexalArithmetics.register(bind(IXplatAbstractions.INSTANCE.arithmeticRegistry))
    }

    private fun fabricOnlyRegistration() {
        HexalActions.make("interop/fabric_only/phase_block", HexPattern.fromAngles("daqqqa", HexDir.WEST), OpPhaseBlock)
    }


    private fun <T> bind(registry: Registry<in T>): BiConsumer<T, ResourceLocation> =
        BiConsumer<T, ResourceLocation> { t, id -> Registry.register(registry, id, t) }
}