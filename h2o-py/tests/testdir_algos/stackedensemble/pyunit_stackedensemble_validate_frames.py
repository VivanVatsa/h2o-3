#!/usr/bin/env python
# -*- encoding: utf-8 -*-
from __future__ import print_function

import h2o

import sys

from h2o.estimators import H2OGradientBoostingEstimator, H2ORandomForestEstimator
from h2o.exceptions import H2OTypeError

sys.path.insert(1,"../../../")  # allow us to run this standalone

from h2o.estimators.stackedensemble import H2OStackedEnsembleEstimator
from tests import pyunit_utils as pu

seed = 1


def import_dataset(seed=0):
    df = h2o.import_file(path=pu.locate("smalldata/prostate/prostate.csv"))
    target = "CAPSULE"
    df[target] = df[target].asfactor()
    fr = df.split_frame(ratios=[.8,.1], seed=seed)
    return dict(train=fr[0], valid=fr[1], blend=fr[2], target=target)


def test_frames_can_be_passed_to_constructor():
    ds = import_dataset()

    gbm = H2OGradientBoostingEstimator(ntrees=10, nfolds=0, seed=seed,
                                       training_frame=ds['train'],
                                       validation_frame=ds['valid'])
    gbm.train(y=ds['target'])

    rf = H2ORandomForestEstimator(ntrees=10, nfolds=0, seed=seed,
                                  training_frame=ds['train'],
                                  validation_frame=ds['valid'])
    rf.train(y=ds['target'])

    se = H2OStackedEnsembleEstimator(base_models=[gbm, rf], seed=seed,
                                     training_frame=ds['train'],
                                     validation_frame=ds['valid'],
                                     blending_frame=ds['blend'])
    se.train(y=ds['target'])

    assert se.auc() > 0


def test_frames_can_be_overridden_in_train_method():
    ds = import_dataset()

    dummy_frame = h2o.H2OFrame([1, 2, 3])

    gbm = H2OGradientBoostingEstimator(ntrees=10, nfolds=0, seed=seed,
                                       training_frame=dummy_frame,
                                       validation_frame=dummy_frame)
    gbm.train(y=ds['target'],
              training_frame=ds['train'],
              validation_frame=ds['valid'])

    rf = H2ORandomForestEstimator(ntrees=10, nfolds=0, seed=seed,
                                  training_frame=dummy_frame,
                                  validation_frame=dummy_frame)
    rf.train(y=ds['target'],
             training_frame=ds['train'],
             validation_frame=ds['valid'])

    se = H2OStackedEnsembleEstimator(base_models=[gbm, rf], seed=seed,
                                     training_frame=dummy_frame,
                                     validation_frame=dummy_frame,
                                     blending_frame=dummy_frame)
    se.train(y=ds['target'],
             training_frame=ds['train'],
             validation_frame=ds['valid'],
             blending_frame=ds['blend'])

    assert se.auc() > 0



def test_frames_cannot_be_passed_as_key():
    ds = import_dataset()

    kw_args = [
        dict(training_frame=ds['train'].frame_id),
        dict(training_frame=ds['train'], validation_frame=ds['valid'].frame_id),
        dict(training_frame=ds['train'], blending_frame=ds['blend'].frame_id),
    ]

    # Constructor validation
    for kwargs in kw_args:
        try:
            H2OStackedEnsembleEstimator(base_models=[], **kwargs)
            assert False, "should have thrown due to invalid frame"
        except H2OTypeError as e:
            attr = next(k for k, v in kwargs.items() if v is not ds['train'])
            assert "'{}' must be a valid H2OFrame".format(attr) in str(e), str(e)


    # train method validation
    se = H2OStackedEnsembleEstimator(base_models=[])

    for kwargs in kw_args:
        try:
            se.train(y=ds['target'], **kwargs)
            assert False, "should have thrown due to invalid frame"
        except H2OTypeError as e:
            attr = next(k for k, v in kwargs.items() if v is not ds['train'])
            assert "'{}' must be a valid H2OFrame".format(attr) in str(e), str(e)


pu.run_tests([
    test_frames_can_be_passed_to_constructor,
    test_frames_can_be_overridden_in_train_method,
    test_frames_cannot_be_passed_as_key
])
