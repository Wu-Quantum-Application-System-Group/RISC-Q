# This project vendors code from sandialabs/pyRPE
# Source: https://github.com/sandialabs/pyRPE
# License: Apache-2.0 or BSD-3-Clause
# Vendored from commit: e09be5a
# Files vendored under: qcal/_vendor/pyrpe/
# Copyright 2020 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
# Under the terms of Contract DE-NA0003525 with NTESS, the U.S. Government retains
# certain rights in this software.
""" Circular arithmetic and operations. """

# WARNING:
# This is written only with our specific implementation of RPE in mind.

from numpy import pi


def distance(a, b):
    """
    This finds the circular distance between two angles. No restriction
    is made on the angles.  This coresponds to the shortest arc connecting
    the two rays.
    """
    d = (b - a) % (2 * pi)
    return min(d, 2 * pi - d)


def interval_mean(lower, upper):
    """
    Finds the angle of the middle of the shortest arc connecting two rays
    (as in distance).  The angles must satisfy:

    lower < upper, 0 <= upper <= 2pi

    And guarantee that the resulting angle is in [0,2π).
    """
    return ((lower + upper) / 2) % (2 * pi)


def interval_contains(theta, lower, upper):
    """
    Checks if theta is in (lower, upper).

    The angles must satisfy:
    lower < upper, 0 <= upper <= 2pi
    """

    theta = theta % (2 * pi)
    if lower > 0:
        return (lower < theta) and (theta < upper)
    else:
        return (theta < upper) or (theta > lower)


def interval_intersect(lim, lower, upper):
    """
    Set subtracts the complement of the circular interval lower,upper from lim.

    WARNING: this does NOT handle the case where the two intervals'
    intersection is two disjoint intervals (it is impossible for our uses).
    """
    for l, u in (lim, (lower, upper)):  # noqa: E741
        assert l < u
        assert u <= 2 * pi
        assert 0 < u
        assert -2 * pi < l

    # Without loss of generality, assume lower <= lim[0]
    if lower > lim[0]:
        lim[0], lower = lower, lim[0]
        lim[1], upper = upper, lim[1]

    if (lower == -pi) and (upper == pi):
        # special case for full circle
        return

    # We've fixed the lower bound, lim[0], but must check if it wraps
    # around the circle and intersects the top (i.e., lim[1]).
    lower += 2 * pi

    # We'd better not wrap around that far!
    assert lower > upper

    if lower > lim[1]:
        # We didn't intersect, and the upper bound must be chosen.
        lim[1] = min(upper, lim[1])
    else:
        # We did intersect, so the upper bound is lim[1], but we must
        # still choose the lower bound.
        lim[0] = max(lower, lim[0])
        # assert lim[1] > upper

    if lim[0] < lim[1]:
        assert lim[1] <= 2 * pi
        assert 0 < lim[1]
        assert -2 * pi < lim[0]
