#!/bin/bash

mvn $1 $2 -Dmaven.test.skip=true install
