"""Generate VFN cubic coexistence seeds and compare them to CoolProp. Not used by the mod."""

import argparse
from math import expm1, log, sqrt, tanh

from scipy.optimize import brentq
from CoolProp.CoolProp import PropsSI


class CubicReference:
    min_temperature = 50
    shape_bracket = (0.2, 0.7)

    def __init__(self, name, r, cv, tc, pc, tm, tb, boiling_volume, fusion_heat, delta_cp, test_pressure, sample_temperatures):
        self.name, self.r, self.cv = name, r, cv
        self.tc, self.pc, self.tm, self.tb = tc, pc, tm, tb
        self.boiling_volume, self.l_fusion, self.delta_cp = boiling_volume, fusion_heat, delta_cp
        self.test_pressure, self.sample_temperatures = test_pressure, sample_temperatures
        self.b = 0.0777960739038885 * r * tc / pc
        self.a = 0.4572355289213822 * (r * tc) ** 2 / pc
        self.vc = 0.307401308698704 * r * tc / pc
        self.low, self.high = (1 - sqrt(2)) * self.b, (1 + sqrt(2)) * self.b

    def attraction(self, t, k):
        f = 1 + k * (1 - sqrt(t / self.tc))
        return self.a * f * f, -self.a * k * f / sqrt(t * self.tc)

    def phase(self, t, v, k, solid=False):
        a, slope = self.attraction(t, k)
        potential = log((v + self.low) / (v + self.high)) / (self.high - self.low)
        p = self.r * t / (v - self.b) - a / (v * v + 2 * self.b * v - self.b * self.b)
        u, s = self.ideal(t)
        u += (a - t * slope) * potential
        s += self.r * log(v - self.b) - slope * potential
        if solid:
            u += self.delta_cp * (t - self.tm) - self.l_fusion
            s += self.delta_cp * log(t / self.tm) - self.l_fusion / self.tm
        return p, u, s

    def ideal(self, t):
        return self.cv * t, self.cv * log(t)

    def endpoints(self, t, k):
        if t == self.tc:
            return self.vc, self.vc
        a, _ = self.attraction(t, k)

        def dp(v):
            d = v * v + 2 * self.b * v - self.b * self.b
            return -self.r * t / (v - self.b) ** 2 + 2 * a * (v + self.b) / d ** 2

        def pressure(v):
            return self.phase(t, v, k)[0]

        low, high = self.b * 1.000001, 1e4
        lturn, gturn = brentq(dp, low, self.vc), brentq(dp, self.vc, high)

        def roots(p):
            return (brentq(lambda v: pressure(v) - p, low, lturn, xtol=1e-16),
                    brentq(lambda v: pressure(v) - p, gturn, high, xtol=1e-16))

        def mismatch(p):
            vl, vg = roots(p)
            _, ul, sl = self.phase(t, vl, k, t < self.tm)
            _, ug, sg = self.phase(t, vg, k)
            return ug - ul + p * (vg - vl) - t * (sg - sl)

        p_lo, p_hi = max(pressure(high), pressure(lturn)), pressure(gturn)
        inset = (p_hi - p_lo) * 1e-8
        return roots(brentq(mismatch, p_lo + inset, p_hi - inset))

    def report(self):
        k = brentq(lambda shape: self.phase(self.tb, self.endpoints(self.tb, shape)[1], shape)[0] - 1e5, *self.shape_bracket)
        vl, _ = self.endpoints(self.tb, k)
        shift = self.boiling_volume - vl
        p, u, s = self.phase(self.tb, vl, k)
        h0 = u + p * (vl + shift)
        print("shape, volume translation, h and s zeros:", k, shift, h0, s)
        for solid, count in ((False, 16), (True, 8)):
            temps = [
                self.tm - (self.tm - self.min_temperature) * i / count if solid else self.tc - (self.tc - self.tm) * (i / count) ** 2
                for i in range(count + 1)
            ]
            pairs = [self.endpoints(t, k) for t in temps]
            for side in (0, 1):
                print("solid" if solid else "fluid", side, ", ".join(str(log(row[side])) for row in pairs))
        print(f"{self.test_pressure / 1e6:g} MPa: T, reference/model kg/m3, reference/model internal EU/kg relative to boiling liquid enthalpy")
        reference_zero = PropsSI("H", "P", 1e5, "Q", 0, self.name)
        for t in self.sample_temperatures:
            v = brentq(lambda volume: self.phase(t, volume, k)[0] - self.test_pressure, self.b * 1.000001, 1)
            print(t, PropsSI("D", "P", self.test_pressure, "T", t, self.name), 1 / (v + shift),
                  PropsSI("U", "P", self.test_pressure, "T", t, self.name) - reference_zero, self.phase(t, v, k)[1] - h0)
        for t in (self.min_temperature, (self.min_temperature + self.tm) / 2, self.tm, self.tb, (self.tb + self.tc) / 2, self.tc - 3):
            vl, vg = self.endpoints(t, k)
            pl, ul, sl = self.phase(t, vl, k, t < self.tm)
            pg, ug, sg = self.phase(t, vg, k)
            assert abs(pl - pg) < 1e-3 and abs(ug - ul + pg * (vg - vl) - t * (sg - sl)) < 1e-4
            print("coexistence K, Pa, latent EU/kg:", t, pg, t * (sg - sl))
        return k


class NormalHydrogen(CubicReference):
    min_temperature = 10
    shape_bracket = (-0.3, 0.2)
    rotation_k = 389.7373429990642

    def attraction(self, t, k):
        y = tanh((t - self.tc) / self.tc)
        d = 1 + 4 * max(y, 0)
        return (self.a * (1 + k * y - 0.2 * y * y / d),
                self.a * (k - 0.2 * y * (1 + d) / d ** 2) * (1 - y * y) / self.tc)

    def ideal(self, t):
        z = self.rotation_k / t
        n = 1 / expm1(z)
        return (self.cv * t + self.r * self.rotation_k * n,
                self.cv * log(t) + self.r * (z * n - log(-expm1(-z))))

    def report(self):
        k = super().report()
        # At fixed T the residual Cv is linear in the potential. Check both limiting volumes
        for i in range(6901):
            t = 10 + i / 10
            for v in (self.b * 1.000001, 1e6):
                solid = t < self.tm
                lo = self.phase(t - 0.001, v, k, solid)
                hi = self.phase(t + 0.001, v, k, solid)
                assert hi[1] > lo[1] and hi[2] > lo[2], (t, v)
                assert abs((hi[1] - lo[1]) / (hi[2] - lo[2]) - t) < 1e-3, (t, v)


FLUIDS = {
    "hydrogen": NormalHydrogen("Hydrogen", 4124.5, 6186.75, 33.14433, 1296357.6, 13.957, 20.3244, 0.0141046,
                              58200, -3200, 2e6, (18, 23, 27, 33, 34, 36, 40, 70, 295)),
    "nitrogen": CubicReference("Nitrogen", 296.8, 754.2, 126.192, 3395800, 63.151, 77.2435, 1.23979e-3,
                              25700, -591, 4e6, (100, 110, 120, 126, 130, 140, 160, 200, 300)),
    # Effective Cv anchors the 295 K isobar to 325100 EU/kg above the boiling liquid enthalpy zero
    "oxygen": CubicReference("Oxygen", 259.84, 655.4229272412394, 154.581, 5043000, 54.36, 90.0621, 0.875813e-3,
                             13890, -399, 6e6, (100, 125, 140, 150, 155, 160, 170, 200, 300)),
}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fluid", choices=FLUIDS)
    FLUIDS[parser.parse_args().fluid].report()
