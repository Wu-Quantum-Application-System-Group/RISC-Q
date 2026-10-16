# This project vendors code from sandialabs/pyRPE
# Source: https://github.com/sandialabs/pyRPE
# License: Apache-2.0 or BSD-3-Clause
# Vendored from commit: e09be5a
# Files vendored under: qcal/_vendor/pyrpe/
# Copyright 2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
# Under the terms of Contract DE-NA0003525 with NTESS, the U.S. Government retains
# certain rights in this software.
from collections import OrderedDict, defaultdict

import numpy

print_state = True


class Q(object):
    """
    "Q" interface definition. Abstract away the implementation of the quantum
    computing---simulation, or otherwise.
    """

    def __init__(self, *args, **kwargs):
        self._measured = defaultdict(lambda: numpy.zeros(4, dtype=int))
        super().__init__(*args, **kwargs)

    def process_sin(self, k, val):
        """
        Incorporate new information. See algorithms -> theta_N for the notation.
        """
        self._measured[k][:2] += val

    def process_cos(self, k, val):
        """
        Incorporate new information. See algorithms -> theta_N for the notation.
        """
        self._measured[k][2:] += val

    def theta_N(self, N):
        """
        Determine the raw angles from the count data. This corresponds to the
        angle of U^N, i.e., it is N times the phase of U.
        """

        # The measured data (counts)
        Cp_Ns, Cm_Ns, Cp_Nc, Cm_Nc = self._measured[N]

        # The measurement outcomes have probability:
        # P^{γ'γ}_{Ns} = |<γ' y| U^N |γ x>|² = |<γ' x| U^N |-γ y>|² = (1 ± sin(θ))/2
        # P^{γ'γ}_{Nc} = |<γ' x| U^N |γ x>|² = |<γ' y| U^N | γ y>|² = (1 ± cos(θ))/2
        # So the MLE for these probabilities is

        Pp_Ns = Cp_Ns / (Cp_Ns + Cm_Ns)
        Pp_Nc = Cp_Nc / (Cp_Nc + Cm_Nc)

        return numpy.arctan2(2 * Pp_Ns - 1, 2 * Pp_Nc - 1) % (2 * numpy.pi)

    def amplitude_N(self, N):
        """
        Auxiliary routine to extract the amplitudes of the probabilities.

        Ideally, this would return unity.  While RPE makes no demands of this
        quantity, it may be valuable for testing purposes, especially statistical
        ones.
        """

        Cp_Ns, Cm_Ns, Cp_Nc, Cm_Nc = self._measured[N]
        Pp_Ns = Cp_Ns / (Cp_Ns + Cm_Ns)
        Pp_Nc = Cp_Nc / (Cp_Nc + Cm_Nc)

        return numpy.sqrt((2 * Pp_Ns - 1) ** 2 + (2 * Pp_Nc - 1) ** 2)

    @property
    def raw_angles(self):
        meas = OrderedDict()
        for N in sorted(self._measured.keys()):
            meas[N] = self.theta_N(N)

        return meas
