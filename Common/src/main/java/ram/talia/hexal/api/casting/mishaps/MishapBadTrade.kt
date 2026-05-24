package ram.talia.hexal.api.casting.mishaps

import at.petrak.hexcasting.api.casting.eval.CastingEnvironment
import at.petrak.hexcasting.api.casting.iota.Iota
import at.petrak.hexcasting.api.casting.mishaps.Mishap
import at.petrak.hexcasting.api.pigment.FrozenPigment
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor

class MishapBadTrade(val problem: String, val moteList: Iota) : Mishap() {
    override fun accentColor(env: CastingEnvironment, errorCtx: Context): FrozenPigment = dyeColor(DyeColor.BROWN)

    override fun errorMessage(env: CastingEnvironment, errorCtx: Context): Component = error("bad_trade.$problem", moteList.display())

    override fun execute(env: CastingEnvironment, errorCtx: Context, stack: MutableList<Iota>) {}
}