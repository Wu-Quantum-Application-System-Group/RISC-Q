# This project vendors code from sandialabs/pyRPE
# Source: https://github.com/sandialabs/pyRPE
# License: Apache-2.0 or BSD-3-Clause
# Vendored from commit: e09be5a
# Files vendored under: riscq/cal/_vendor/pyrpe/
# Copyright 2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
# Under the terms of Contract DE-NA0003525 with NTESS, the U.S. Government retains
# certain rights in this software.
"""pyRPE — the robust-phase-estimation analysis kernel (counts -> angle).

`Q` accumulates the per-depth (cos, sin) count pairs; `RobustPhaseEstimation` turns them into a
per-generation angle ladder with the branch selection that makes RPE robust. Vendored (not pip
depended-on) because the package is not on PyPI under a stable name and the board install stays
numpy-only; this mirrors what qcal does.
"""
from .classical import RobustPhaseEstimation
from .quantum import Q

__all__ = ["Q", "RobustPhaseEstimation"]
