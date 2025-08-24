package ram.talia.hexal.api.casting.iota;

import at.petrak.hexcasting.api.casting.iota.Iota;

import java.util.function.UnaryOperator;

/**
 * An Interface to facilitate replacing illegal Iotas.
 */
public interface IMappableIota {
    /**
     * Returns a new iota consisting of the result of applying the given function to the contents of this iota.
     * <br />
     * Implementations should apply <code>mapper</code> to every sub-iota ({@link Iota#subIotas()}), then return a copy of itself containing the mapped iotas.
     */
    Iota mapSubIotas(UnaryOperator<Iota> mapper);
}