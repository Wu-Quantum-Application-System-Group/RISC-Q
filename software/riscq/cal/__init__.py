"""riscq.cal — the calibration library (specs/universal-cal): the slash-path `Config`, the fit
helpers (`fits`), every calibration class on the universal base (riscq.cal.cals), the readout
classifier and the RPE estimators (riscq.cal.analysis), the pair's CZ resolvers (riscq.cal.cz)
and the two autocalibration processes (riscq.cal.process)."""

from riscq.cal import fits
from riscq.cal.analysis.classifier import Classifier, ClassifierN, rcorr
from riscq.cal.analysis.drag import optimize_fast_drag
from riscq.cal.analysis.rpe import (Angles, RPEBranchError, cz_angles, damped_update, freq_error_hz,
                                    idle_angles, vz_correction, x90_angles)
from riscq.cal.base import Result
from riscq.cal.cals import (JAZZ, CZRPE, Amplitude, Chevron, CZAmpFreqSweep, CZAmplitude, CZFrequency,
                            CZSweep, EFAmplitude, EFFrequency, EFPhase, ErrorAmplification, Fidelity,
                            Frequency, Leakage, LengthRabi, LocalPhases, Phase, Punchout,
                            ReadoutCalibration, ReadoutFidelity, RelativePhase, Resonator, RPEAmplitude,
                            RPEFrequency, RPEPhase, Separation, SpectatorPhase, Spectroscopy, T1, T2,
                            Window, chi, classifier3)
from riscq.cal.config import Config
from riscq.cal.cz import (calc_cz_frequency, coupler_core, cz_coupler_form, cz_sandwich,
                          joint_populations, pair_key)
from riscq.cal.process.multimode import autocal_multimode
from riscq.cal.process.x6y3 import calibration_x6y3

__all__ = ["Config", "Result", "fits", "Amplitude", "Frequency", "Phase", "T1", "T2",
           "EFAmplitude", "EFFrequency", "EFPhase", "Spectroscopy", "Leakage", "optimize_fast_drag",
           "ReadoutCalibration", "ReadoutFidelity", "Separation", "Fidelity", "Window",
           "Punchout", "Resonator", "rcorr", "Classifier", "ClassifierN", "classifier3",
           "LengthRabi", "Chevron", "ErrorAmplification", "chi",
           "Angles", "RPEBranchError", "RPEAmplitude", "RPEFrequency", "RPEPhase", "CZRPE",
           "cz_angles", "damped_update", "freq_error_hz", "idle_angles", "vz_correction",
           "x90_angles",
           "JAZZ", "CZSweep", "CZFrequency", "CZAmpFreqSweep", "CZAmplitude", "LocalPhases",
           "RelativePhase", "SpectatorPhase",
           "calc_cz_frequency", "coupler_core", "cz_coupler_form", "cz_sandwich",
           "joint_populations", "pair_key", "calibration_x6y3", "autocal_multimode"]
