package gregtech.api.metatileentity.implementations.integratedfluid.amount;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class SubstanceAmount implements Comparable<SubstanceAmount> {

    public static final long SCALE = 1_000_000L;
    public static final SubstanceAmount ZERO = new SubstanceAmount(0L);

    private final long rawUnits;

    private SubstanceAmount(long rawUnits) {
        if (rawUnits < 0L) {
            throw new IllegalArgumentException("substance amount cannot be negative");
        }
        this.rawUnits = rawUnits;
    }

    public static SubstanceAmount fromRawUnits(long rawUnits) {
        return rawUnits == 0L ? ZERO : new SubstanceAmount(rawUnits);
    }

    public static SubstanceAmount fromKilograms(long kilograms) {
        if (kilograms < 0L) {
            throw new IllegalArgumentException("mass cannot be negative");
        }
        return fromRawUnits(Math.multiplyExact(kilograms, SCALE));
    }

    public BigDecimal kilograms() {
        return BigDecimal.valueOf(rawUnits, 6);
    }

    public String formatKilograms() {
        return kilograms().stripTrailingZeros().toPlainString();
    }

    public long rawUnits() {
        return rawUnits;
    }

    public long toWholeKilograms() {
        return rawUnits / SCALE;
    }

    public SubstanceAmount plus(SubstanceAmount other) {
        if (other == null || other.rawUnits == 0L) {
            return this;
        }
        return fromRawUnits(Math.addExact(rawUnits, other.rawUnits));
    }

    public SubstanceAmount proportionalShare(long numerator, long denominator) {
        if (numerator < 0L) {
            throw new IllegalArgumentException("numerator cannot be negative");
        }
        if (denominator <= 0L) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        if (numerator == 0L || rawUnits == 0L) {
            return ZERO;
        }
        BigInteger product = BigInteger.valueOf(rawUnits).multiply(BigInteger.valueOf(numerator));
        return fromRawUnits(product.divide(BigInteger.valueOf(denominator)).longValueExact());
    }

    public boolean isZero() {
        return rawUnits == 0L;
    }

    @Override
    public int compareTo(SubstanceAmount other) {
        return Long.compare(rawUnits, other.rawUnits);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SubstanceAmount)) {
            return false;
        }
        return rawUnits == ((SubstanceAmount) obj).rawUnits;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(rawUnits);
    }

    @Override
    public String toString() {
        return rawUnits + " substance units";
    }
}
