#!/bin/bash

# Predict with an existing MWE model.
# Usage: ./mwe_identify.sh model input

set -eu
set -o pipefail

input=$1 # word and POS tag on each line (tab-separated)

#creates output in the same location as the input files
./predict_sst.sh $input
# > $input.pred.tags

#src/tags2sst.py -gold_label $input.pred.tags > $input.pred.sst

