package ram.talia.hexal.api.casting.mishaps

import at.petrak.hexcasting.api.casting.asActionResult
import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.GarbageIota
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.iota.ListIota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.pigment.FrozenPigment
import at.petrak.hexcasting.api.utils.isOfTag
import at.petrak.hexcasting.common.lib.hex.HexIotaTypes
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import ram.talia.hexal.api.casting.iota.IMappableIota
import ram.talia.hexal.common.lib.HexalTags

class MishapIllegalInterworldIota(val iota: Iota) : Mishap() {
    override fun accentColor(env: CastingEnvironment, errorCtx: Context): FrozenPigment = dyeColor(DyeColor.GREEN)

    override fun errorMessage(env: CastingEnvironment, errorCtx: Context): Component = error("illegal_interworld_iota", iota.display())

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {
        env.caster?.let { it.health /= 2 } // Bad but better than freaking TODO()
    }

    companion object {
        private fun iotaTypeIsIllegal(iota: Iota): Boolean {
            val resourceKey = HexIotaTypes.REGISTRY.getKey(iota.type) ?: return false
            return isOfTag(HexIotaTypes.REGISTRY, resourceKey, HexalTags.ILLEGAL_INTERWORLD)
        }

        fun getFromNestedIota(iota: Iota): Iota? {
            val poolToSearch = ArrayDeque<Iota>()
            poolToSearch.addLast(iota)

            while (poolToSearch.isNotEmpty()) {
                val iotaToCheck = poolToSearch.removeFirst()
                if (iotaTypeIsIllegal(iotaToCheck))
                    return iotaToCheck
                if (iotaToCheck.subIotas() != null)
                    poolToSearch.addAll(iotaToCheck.subIotas()!!)
            }

            return null
        }

        fun replaceInNestedIota(iota: Iota): Iota {
            return when {
                iotaTypeIsIllegal(iota) -> GarbageIota()
                iota is IMappableIota -> iota.mapSubIotas(::replaceInNestedIota)
                iota is ListIota -> iota.list.map { replaceInNestedIota(it) }.asActionResult[0]
                else -> iota
            }
        }
    }
}